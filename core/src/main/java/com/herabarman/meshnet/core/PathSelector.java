package com.herabarman.meshnet.core;

import java.util.List;
import java.util.Random;

/**
 * Turns "several known first hops" into "the one to use now". Cost of a path (lower is better), additive over hops
 * like ETX routing:
 *
 *   cost = (hops - 1) + firstHopCost
 *   firstHopCost = etx(link) / successRate(end to end) * energyFactor * loadFactor * mobilityFactor
 *
 * Only the first hop's state is known to the sender, so later hops count as 1.0 each; the next relay does the same
 * calculation with its own neighbours, so the whole path ends up chosen hop by hop with no global knowledge.
 * Among paths whose cost is within {@code pathTolerance} of the best one the pick is random, weighted by 1/cost, which
 * spreads traffic (and battery drain) over equally good relays instead of wearing out one.
 */
public final class PathSelector {
    private PathSelector() {}

    /** Battery 0..1 to a multiplier >= 1 (a plugged-in phone is slightly preferred, a nearly empty one strongly avoided). */
    public static double energyFactor(double battery, boolean charging, MeshConfig cfg) {
        if (charging) return 0.85;
        if (battery >= cfg.energyHealthy) return 1.0;
        double d = (cfg.energyHealthy - Math.max(0.0, battery)) / cfg.energyHealthy;
        return 1.0 + cfg.energyWeight * d * d;
    }

    /** Cost of going through this first hop. n may be null (nothing known: neutral). */
    public static double firstHopCost(NeighborInfo n, MeshConfig cfg, long nowMs) {
        if (n == null) return 1.0;
        double c = n.etx() / Math.max(0.25, n.successRate());
        if (cfg.energyAware && n.lastBeaconMs >= 0) c *= energyFactor(n.battery, n.charging, cfg);
        c *= 1.0 + cfg.loadWeight * Math.min(1.0, n.load);
        c *= 1.0 + cfg.mobilityWeight * n.mobility / 3.0;
        if (cfg.mobilityAware && n.expiring(cfg.linkExpiryHorizonMs)) c *= 3.0;
        if (n.recentlyBad(nowMs, 30_000)) c *= 4.0;
        return c;
    }

    public static double cost(MultiRouteTable.Path p, NeighborInfo n, MeshConfig cfg, long nowMs) {
        return Math.max(0, p.hops - 1) + firstHopCost(n, cfg, nowMs);
    }

    /** Callback to look up neighbour state by id. */
    public interface Lookup {
        NeighborInfo get(int peer);
    }

    /** @return the chosen next hop, or -1 for an empty list */
    public static int choose(List<MultiRouteTable.Path> paths, Lookup neighbors, MeshConfig cfg, long nowMs, Random rng) {
        if (paths.isEmpty()) return -1;
        if (paths.size() == 1) return paths.get(0).nextHop;
        double[] cost = new double[paths.size()];
        double best = Double.MAX_VALUE;
        for (int i = 0; i < cost.length; i++) {
            MultiRouteTable.Path p = paths.get(i);
            cost[i] = cost(p, neighbors.get(p.nextHop), cfg, nowMs);
            if (cost[i] < best) best = cost[i];
        }
        double limit = best * Math.max(1.0, cfg.pathTolerance);
        double total = 0;
        for (int i = 0; i < cost.length; i++) if (cost[i] <= limit) total += 1.0 / cost[i];
        double r = rng.nextDouble() * total;
        int pick = -1;
        for (int i = 0; i < cost.length; i++) {
            if (cost[i] > limit) continue;
            pick = i;
            r -= 1.0 / cost[i];
            if (r <= 0) break;
        }
        return paths.get(pick).nextHop;
    }

    public static final class PathDecision {
        public final int nextHop;
        public final double totalCost;
        public final double energyPenalty;
        public final double aiReliability;
        public final boolean avoidedLowBattery;

        PathDecision(int nextHop, double totalCost, double energyPenalty, double aiReliability, boolean avoidedLowBattery) {
            this.nextHop = nextHop;
            this.totalCost = totalCost;
            this.energyPenalty = energyPenalty;
            this.aiReliability = aiReliability;
            this.avoidedLowBattery = avoidedLowBattery;
        }
    }

    /**
     * Enhanced Energy-Aware + AI Predictive + Fairness Path Selection:
     * Strongly spares nodes below cfg.lowBattery when healthier relays exist, rewards charging relays,
     * and scales path desirability by PredictiveAiRouter reliability & RelayFairnessLedger credit score.
     */
    public static PathDecision chooseSmart(
            List<MultiRouteTable.Path> paths,
            Lookup neighbors,
            MeshConfig cfg,
            PredictiveAiRouter aiRouter,
            RelayFairnessLedger fairness,
            int dstNode,
            long nowMs) {
        if (paths.isEmpty()) return new PathDecision(-1, Double.MAX_VALUE, 1.0, 0.0, false);

        boolean hasHealthyAlt = false;
        for (MultiRouteTable.Path p : paths) {
            NeighborInfo n = neighbors.get(p.nextHop);
            if (n == null || n.charging || n.battery >= cfg.lowBattery) {
                hasHealthyAlt = true;
                break;
            }
        }

        boolean sawLowBatt = false;
        int bestHop = -1;
        double bestCost = Double.MAX_VALUE;
        double bestEnergy = 1.0;
        double bestAi = 0.5;
        int predictedDstZone = aiRouter == null ? -1 : aiRouter.predictNextZone(dstNode);

        for (MultiRouteTable.Path p : paths) {
            NeighborInfo n = neighbors.get(p.nextHop);
            double eFactor = 1.0;
            if (n != null && cfg.energyAware) {
                eFactor = energyFactor(n.battery, n.charging, cfg);
                if (!n.charging && n.battery < cfg.lowBattery) {
                    sawLowBatt = true;
                    if (hasHealthyAlt) eFactor *= 6.0; // spare critical-battery relay so network lives longer
                }
            }
            double base = firstHopCost(n, cfg, nowMs);
            if (n != null && !n.charging && n.battery < cfg.lowBattery && hasHealthyAlt) {
                base *= 6.0;
            }
            double fair = fairness == null ? 0.6 : fairness.fairnessScore(p.nextHop);
            double aiRel = 0.65;
            if (aiRouter != null) {
                double online = aiRouter.onlineLikelihood(p.nextHop, nowMs);
                double dstOnline = aiRouter.onlineLikelihood(dstNode, nowMs);
                int relayZone = aiRouter.currentZone(p.nextHop);
                double zoneMatch = (predictedDstZone >= 0 && relayZone == predictedDstZone) ? 1.0 : 0.3;
                double linkQ = n == null ? 0.85 : Math.min(1.0, n.successRate() / Math.max(1.0, n.etx()));
                double batt = n == null ? 0.80 : Math.max(0.0, Math.min(1.0, n.battery));
                double chg = (n != null && n.charging) ? 1.0 : 0.0;
                double loadMargin = n == null ? 0.80 : Math.max(0.0, 1.0 - Math.min(1.0, n.load));
                aiRel = aiRouter.scoreCandidate(new double[]{
                    1.0, online, Math.max(0.0, online - 0.3), dstOnline,
                    zoneMatch, fair, linkQ, batt, chg, loadMargin, 0.75, 0.1
                });
            }
            double total = Math.max(0, p.hops - 1) + (base / (0.4 + 0.6 * aiRel)) * (1.25 - 0.4 * fair);
            if (total < bestCost) {
                bestCost = total;
                bestHop = p.nextHop;
                bestEnergy = eFactor;
                bestAi = aiRel;
            }
        }
        NeighborInfo chosenInfo = neighbors.get(bestHop);
        boolean avoided = sawLowBatt && (chosenInfo == null || chosenInfo.charging || chosenInfo.battery >= cfg.lowBattery);
        return new PathDecision(bestHop, bestCost, bestEnergy, bestAi, avoided);
    }

    public static final class AdaptiveLinkReport {
        public final int nextHop;
        public final double rssiDbm;
        public final double rttMs;
        public final double packetLossPct;
        public final double batmanTq;
        public final double compositeScore;

        AdaptiveLinkReport(int nextHop, double rssiDbm, double rttMs, double packetLossPct, double batmanTq, double compositeScore) {
            this.nextHop = nextHop;
            this.rssiDbm = rssiDbm;
            this.rttMs = rttMs;
            this.packetLossPct = packetLossPct;
            this.batmanTq = batmanTq;
            this.compositeScore = compositeScore;
        }
    }

    /**
     * AOMDV + B.A.T.M.A.N. TQ Adaptive Path Selector combining:
     *   - Radio RSSI + fading slope prediction
     *   - End-to-end RTT latency penalty
     *   - Measured beacon/packet loss rate (TQ = receiveQuality * successRate)
     *   - Multi-path hop count + energy awareness
     */
    public static AdaptiveLinkReport chooseAdaptiveBatmanAomdv(
            List<MultiRouteTable.Path> paths,
            Lookup neighbors,
            MeshConfig cfg,
            long nowMs) {
        if (paths == null || paths.isEmpty()) {
            return new AdaptiveLinkReport(-1, Double.NaN, Double.NaN, 100.0, 0.0, 0.0);
        }
        int bestHop = paths.get(0).nextHop;
        double bestScore = -1.0;
        double bestRssi = -60.0;
        double bestRtt = 25.0;
        double bestLoss = 0.0;
        double bestTq = 0.9;

        for (MultiRouteTable.Path p : paths) {
            NeighborInfo n = neighbors.get(p.nextHop);
            double rssiQ = n == null ? 0.85 : n.rssiQuality();
            double rssiVal = (n != null && n.hasRssi()) ? n.rssiDbm() : -62.0;
            double rtt = (n != null && !Double.isNaN(n.rttMs())) ? n.rttMs() : 30.0;
            double recvQ = n == null ? 0.90 : n.receiveQuality();
            double succ = n == null ? 0.90 : n.successRate();
            double tq = recvQ * succ;
            double lossPct = Math.max(0.0, Math.min(100.0, (1.0 - tq) * 100.0));
            double latencyFactor = 1.0 / (1.0 + Math.max(0.0, rtt) / 120.0);
            double hopFactor = 1.0 / (1.0 + Math.max(0, p.hops - 1) * 0.45);
            double energyPenalty = (n != null && cfg.energyAware) ? energyFactor(n.battery, n.charging, cfg) : 1.0;
            double fadePenalty = (n != null && n.expiring(cfg.linkExpiryHorizonMs)) ? 0.45 : 1.0;
            double badPenalty = (n != null && n.recentlyBad(nowMs, 30_000L)) ? 0.35 : 1.0;

            double score = (0.42 * tq + 0.33 * rssiQ + 0.25 * latencyFactor)
                    * hopFactor * fadePenalty * badPenalty / Math.sqrt(energyPenalty);
            if (score > bestScore) {
                bestScore = score;
                bestHop = p.nextHop;
                bestRssi = rssiVal;
                bestRtt = rtt;
                bestLoss = lossPct;
                bestTq = tq;
            }
        }
        return new AdaptiveLinkReport(bestHop, bestRssi, bestRtt, bestLoss, bestTq, bestScore);
    }
}
