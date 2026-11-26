package com.herabarman.meshnet.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Relay Incentive & Priority Fairness System:
 * Rewards helpful nodes that relay other peers' traffic with higher priority credits & rate shares,
 * while clamping free-riding "leech" nodes (sending only their own bursts without relaying).
 */
public final class RelayFairnessLedger {
    public static final class NodeCredit {
        public final int nodeId;
        public long relayedCount;
        public long ownCount;
        public double credits;
        public double fairnessScore;

        NodeCredit(int nodeId, long relayedCount, long ownCount, double credits, double fairnessScore) {
            this.nodeId = nodeId;
            this.relayedCount = relayedCount;
            this.ownCount = ownCount;
            this.credits = credits;
            this.fairnessScore = fairnessScore;
        }
    }

    private final Map<Integer, NodeCredit> map = new HashMap<>();

    public synchronized void recordRelayForPeer(int relayNodeId, int bytesRelayed) {
        NodeCredit nc = getOrCreate(relayNodeId);
        nc.relayedCount++;
        nc.credits += 1.0 + Math.min(4.0, bytesRelayed / 1024.0);
        recompute(nc);
    }

    public synchronized void recordOwnTraffic(int originNodeId, int bytesSent) {
        NodeCredit nc = getOrCreate(originNodeId);
        nc.ownCount++;
        nc.credits = Math.max(-20.0, nc.credits - (0.35 + Math.min(1.5, bytesSent / 2048.0)));
        recompute(nc);
    }

    public synchronized double fairnessScore(int nodeId) {
        NodeCredit nc = map.get(nodeId);
        return nc == null ? 0.50 : nc.fairnessScore;
    }

    public synchronized double creditBalance(int nodeId) {
        NodeCredit nc = map.get(nodeId);
        return nc == null ? 0.0 : nc.credits;
    }

    /** Priority boost/throttle applied to nodeId's outgoing mesh packets (SOS stays untouched). */
    public synchronized int effectivePriority(int nodeId, int requestedPriority) {
        if (requestedPriority >= Priority.SOS) return requestedPriority;
        NodeCredit nc = map.get(nodeId);
        if (nc == null) return requestedPriority;
        if (nc.credits >= 8.0 && nc.fairnessScore >= 0.68) {
            return Math.min(Priority.SOS - 1, requestedPriority + 64); // helpful relay boost
        }
        if (nc.ownCount >= 5 && nc.relayedCount == 0 && nc.credits < -3.0) {
            return Math.min(requestedPriority, Priority.BULK); // leech clamped to BULK
        }
        return requestedPriority;
    }

    /** Rate-limit multiplier: 0.45x (leech) .. 1.75x (generous relay). */
    public synchronized double rateScale(int nodeId) {
        return 0.45 + 1.30 * fairnessScore(nodeId);
    }

    public synchronized NodeCredit snapshot(int nodeId) {
        NodeCredit nc = getOrCreate(nodeId);
        return new NodeCredit(nc.nodeId, nc.relayedCount, nc.ownCount, nc.credits, nc.fairnessScore);
    }

    private NodeCredit getOrCreate(int nodeId) {
        NodeCredit nc = map.get(nodeId);
        if (nc == null) {
            nc = new NodeCredit(nodeId, 0L, 0L, 0.0, 0.50);
            map.put(nodeId, nc);
        }
        return nc;
    }

    private static void recompute(NodeCredit nc) {
        double ratio = (nc.relayedCount + 1.0) / (nc.relayedCount + nc.ownCount + 2.0);
        double creditNorm = Math.max(-0.2, Math.min(0.25, nc.credits / 40.0));
        nc.fairnessScore = Math.max(0.10, Math.min(0.99, ratio + creditNorm));
    }
}
