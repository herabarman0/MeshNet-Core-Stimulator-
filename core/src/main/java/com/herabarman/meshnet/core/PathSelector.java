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
}
