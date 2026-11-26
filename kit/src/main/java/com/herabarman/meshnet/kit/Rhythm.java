package com.herabarman.meshnet.kit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * When do I usually meet each node? 24 hourly bins per node (UTC hour), slowly forgetting. This is the "learns
 * who is around at which time of day" part: likelihood(node, hour) is 1.0 in the node's busiest hour and near 0
 * in hours I never saw it. A high value for the destination at this hour means "it is probably around me now,
 * hold the message here rather than spreading copies".
 */
final class Rhythm {
    private final Map<Integer, double[]> bins = new HashMap<>();

    static int hourOf(long nowMs) { return (int) ((nowMs / 3_600_000L) % 24); }

    void hit(int node, long nowMs) {
        double[] b = bins.get(node);
        if (b == null) { b = new double[24]; bins.put(node, b); if (bins.size() > 1000) bins.remove(bins.keySet().iterator().next()); }
        for (int i = 0; i < 24; i++) b[i] *= 0.995;
        b[hourOf(nowMs)] += 1.0;
    }

    double likelihood(int node, long nowMs) {
        double[] b = bins.get(node);
        if (b == null) return 0;
        double max = 0;
        for (double v : b) max = Math.max(max, v);
        return max <= 0 ? 0 : b[hourOf(nowMs)] / max;
    }

    void write(DataOutputStream o) throws IOException {
        o.writeShort(bins.size());
        for (Map.Entry<Integer, double[]> e : bins.entrySet()) {
            o.writeInt(e.getKey());
            for (double v : e.getValue()) o.writeFloat((float) v);
        }
    }

    void read(DataInputStream in) throws IOException {
        int n = in.readUnsignedShort();
        bins.clear();
        for (int i = 0; i < n; i++) {
            int node = in.readInt();
            double[] b = new double[24];
            for (int j = 0; j < 24; j++) b[j] = in.readFloat();
            bins.put(node, b);
        }
    }
}
