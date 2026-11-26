package com.herabarman.meshnet.kit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * PRoPHET delivery predictability: P(me -> x) is how likely I am to meet x soon (or meet someone who will).
 *   meeting x:      P = P + (1 - P) * 0.75
 *   time passing:   P = P * 0.98 per 30 minutes
 *   transitivity:   P(me,c) += (1 - P(me,c)) * P(me,b) * P(b,c) * 0.25     (when b tells me its own table)
 * Engine thread only.
 */
final class Prophet {
    static final double P_INIT = 0.75, BETA = 0.25, GAMMA = 0.98;
    static final long UNIT_MS = 30L * 60 * 1000;

    private final Map<Integer, Double> p = new HashMap<>();
    private final int self;
    private long lastAge;

    Prophet(int self, long now) { this.self = self; this.lastAge = now; }

    void age(long now) {
        long k = (now - lastAge) / UNIT_MS;
        if (k <= 0) return;
        double f = Math.pow(GAMMA, k);
        java.util.Iterator<Map.Entry<Integer, Double>> it = p.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Double> e = it.next();
            double v = e.getValue() * f;
            if (v < 0.01) it.remove(); else e.setValue(v);
        }
        lastAge += k * UNIT_MS;
    }

    void encounter(int node, long now) {
        age(now);
        double o = get(node);
        p.put(node, o + (1 - o) * P_INIT);
    }

    void transitive(int via, Map<Integer, Double> theirs, long now) {
        age(now);
        double pab = get(via);
        for (Map.Entry<Integer, Double> e : theirs.entrySet()) {
            int dst = e.getKey();
            if (dst == self || dst == via) continue;
            double o = get(dst);
            p.put(dst, Math.min(1.0, o + (1 - o) * pab * e.getValue() * BETA));
        }
        if (p.size() > 500) trim(400);
    }

    double get(int node) { Double d = p.get(node); return d == null ? 0 : d; }

    private void trim(int keep) {
        java.util.List<Map.Entry<Integer, Double>> l = new java.util.ArrayList<>(p.entrySet());
        java.util.Collections.sort(l, new java.util.Comparator<Map.Entry<Integer, Double>>() {
            @Override public int compare(Map.Entry<Integer, Double> a, Map.Entry<Integer, Double> b) { return Double.compare(b.getValue(), a.getValue()); }
        });
        p.clear();
        for (int i = 0; i < Math.min(keep, l.size()); i++) p.put(l.get(i).getKey(), l.get(i).getValue());
    }

    /** The N most likely contacts as a small table for neighbours: count(1) + (node(4) p(1 byte))*count */
    byte[] table(int n) {
        java.util.List<Map.Entry<Integer, Double>> l = new java.util.ArrayList<>(p.entrySet());
        java.util.Collections.sort(l, new java.util.Comparator<Map.Entry<Integer, Double>>() {
            @Override public int compare(Map.Entry<Integer, Double> a, Map.Entry<Integer, Double> b) { return Double.compare(b.getValue(), a.getValue()); }
        });
        int cnt = Math.min(n, l.size());
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(1 + 5 * cnt).put((byte) cnt);
        for (int i = 0; i < cnt; i++) b.putInt(l.get(i).getKey()).put((byte) Math.round(l.get(i).getValue() * 255));
        return b.array();
    }

    static Map<Integer, Double> parseTable(byte[] t) {
        Map<Integer, Double> m = new HashMap<>();
        if (t.length < 1) return m;
        int cnt = t[0] & 0xFF;
        if (t.length < 1 + 5 * cnt) return m;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(t, 1, 5 * cnt);
        for (int i = 0; i < cnt; i++) { int node = b.getInt(); m.put(node, (b.get() & 0xFF) / 255.0); }
        return m;
    }

    void write(DataOutputStream o) throws IOException {
        o.writeLong(lastAge);
        o.writeShort(p.size());
        for (Map.Entry<Integer, Double> e : p.entrySet()) { o.writeInt(e.getKey()); o.writeFloat(e.getValue().floatValue()); }
    }

    void read(DataInputStream in) throws IOException {
        lastAge = in.readLong();
        int n = in.readUnsignedShort();
        p.clear();
        for (int i = 0; i < n; i++) p.put(in.readInt(), (double) in.readFloat());
    }
}
