package com.herabarman.meshnet.sim;

import com.herabarman.meshnet.core.VoiceLayer;
import java.util.ArrayList;
import java.util.List;

/**
 * Measures live voice over the simulated mesh: one talk spurt of N seconds, packets of frameMs, from a plain node
 * in zone 0 to a plain node in another zone. Run: java -cp ... com.herabarman.meshnet.sim.VoiceBench
 * Numbers come from the simulator's radio model (per-hop delay, loss, capacity), not from real phones.
 */
public final class VoiceBench {
    public static final class Result {
        public int sent, played, lost, late;
        public double meanLatencyMs, p95LatencyMs;
        public int bufferDelayMs;
        public long voiceTx;           // radio transmissions of voice packets, whole network
        public int reportedLoss;
        public double txPerPacket() { return sent == 0 ? 0 : (double) voiceTx / sent; }
    }

    static int plain(SimNetwork net, int zone, int zoneSize) {
        for (int id = zone * zoneSize; id < (zone + 1) * zoneSize; id++) if (!net.node(id).isGateway()) return id;
        return zone * zoneSize;
    }

    /** @param dstZone 0 = same zone, otherwise another zone */
    public static Result run(SimConfig c, int dstZone, int seconds, int frameMs, int bytesPerPacket) {
        SimNetwork net = new SimNetwork(c);
        net.enableVoice();
        if (c.discoveryOn()) net.clock.runFor(10_000);
        int src = plain(net, 0, c.zoneSize);
        int dst = dstZone == 0 ? (src + 1 < c.zoneSize ? src + 1 : src - 1) : plain(net, dstZone, c.zoneSize);
        final VoiceLayer tx = net.voiceLayer(src);
        final int call = tx.open(dst, frameMs);
        // a normal message first: it resolves the zone and teaches nothing about voice, like a real call setup
        net.sendMessage(src, dst, new byte[]{1}, 100);
        net.clock.runFor(2_000);
        final int total = seconds * 1000 / frameMs;
        final byte[] frame = new byte[bytesPerPacket];
        final List<Long> sentAt = new ArrayList<>();
        long t0 = net.clock.nowMs();
        long voiceBefore = net.metrics.txByType[com.herabarman.meshnet.core.Packet.TYPE_VOICE];
        for (int i = 0; i < total; i++) {
            final int seq = i;
            net.clock.schedule((long) i * frameMs, new Runnable() {
                @Override public void run() {
                    byte[] f = frame.clone();
                    f[0] = (byte) seq;
                    f[1] = (byte) (seq >> 8);
                    sentAt.add(net.clock.nowMs());
                    tx.send(call, f);
                }
            });
        }
        net.clock.schedule((long) total * frameMs, new Runnable() {
            @Override public void run() { tx.endTalk(call); }
        });
        net.clock.runFor((long) total * frameMs + 5_000);
        Result r = new Result();
        r.sent = total;
        List<Long> lat = new ArrayList<>();
        for (SimNetwork.VoiceEvent e : net.voiceEvents()) {
            if (e.node != dst || e.from != src) continue;
            if ("lost".equals(e.kind)) r.lost++;
            else if ("frame".equals(e.kind)) {
                int seq = (e.data[0] & 0xFF) | ((e.data[1] & 0xFF) << 8);
                if (seq < sentAt.size()) lat.add(e.timeMs - sentAt.get(seq));
                r.played++;
            }
        }
        java.util.Collections.sort(lat);
        double sum = 0;
        for (long l : lat) sum += l;
        r.meanLatencyMs = lat.isEmpty() ? 0 : sum / lat.size();
        r.p95LatencyMs = lat.isEmpty() ? 0 : lat.get((int) Math.min(lat.size() - 1, Math.floor(lat.size() * 0.95)));
        r.voiceTx = net.metrics.txByType[com.herabarman.meshnet.core.Packet.TYPE_VOICE] - voiceBefore;
        r.late = (int) (net.voiceLayer(dst).incoming(src, call) == null ? 0 : net.voiceLayer(dst).incoming(src, call).late());
        r.bufferDelayMs = net.voiceLayer(dst).incoming(src, call) == null ? 0 : net.voiceLayer(dst).incoming(src, call).delayMs();
        r.reportedLoss = tx.lossPercent(call);
        return r;
    }

    public static void main(String[] args) {
        System.out.println("path    loss/tx  redundancy  played lost  mean(ms) p95(ms)  tx/packet");
        int[][] cases = {{0, 0, 0}, {1, 0, 0}, {1, 5, 0}, {1, 5, 1}, {1, 10, 0}, {1, 10, 1}, {1, 20, 0}, {1, 20, 1}};
        for (int[] cs : cases) {
            SimConfig c = new SimConfig();
            c.voiceAutoRedundancy = cs[2] == 1;
            c.nodes = 120;
            c.zoneSize = 12;
            c.onlineRatio = 0.0;
            c.seed = 11;
            c.lossRate = cs[1] / 100.0;
            c.backbone = "full";
            Result r = run(c, cs[0] == 0 ? 0 : 6, 20, 60, 36);
            System.out.printf("%-7s %4d%%    %-9s %7d %4d  %8.0f %7.0f  %8.1f%n",
                    cs[0] == 0 ? "1 zone" : "2 zones", cs[1], cs[2] == 1 ? "auto" : "off", r.played, r.lost, r.meanLatencyMs, r.p95LatencyMs, r.txPerPacket());
        }
    }
}
