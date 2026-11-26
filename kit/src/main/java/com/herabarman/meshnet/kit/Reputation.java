package com.herabarman.meshnet.kit;

import java.util.HashMap;
import java.util.Map;

/**
 * Fairness by observation, not by promises. A neighbour cannot tell me how much it relays (it could lie), so I
 * only count what I see myself: of the packets that reach me through neighbour N, how many were written by N
 * (its own traffic) and how many by somebody else (N relayed for them).
 *
 *   score(N) = (relayed + 1) / (relayed + own + 2)      0.5 for a stranger, towards 1 for a neighbour that carries
 *                                                        other people's packets, towards 0 for one that only sends its own.
 *
 * The score scales N's rate limit (0.5x .. 1.5x) and is an input to the DTN learner, so helpful neighbours get
 * more service. Limits: only traffic that reaches ME is visible; a group of fake nodes can inflate each other's
 * numbers; scores are per device and not persisted. Engine thread only.
 */
final class Reputation {
    private final Map<Integer, long[]> m = new HashMap<>();   // node -> {relayedForOthers, own}

    void onDelivered(int src, int from) {
        if (from <= 0) return;                 // local or server
        long[] c = m.get(from);
        if (c == null) { c = new long[2]; m.put(from, c); }
        if (from == src) c[1]++; else c[0]++;
    }

    double score(int node) {
        long[] c = m.get(node);
        if (c == null) return 0.5;
        return (c[0] + 1.0) / (c[0] + c[1] + 2.0);
    }

    /** Rate-limit multiplier for this neighbour: 0.5 .. 1.5 */
    double scale(int node) { return 0.5 + score(node); }

    long relayed(int node) { long[] c = m.get(node); return c == null ? 0 : c[0]; }
    long own(int node) { long[] c = m.get(node); return c == null ? 0 : c[1]; }
}
