package com.herabarman.meshnet.sim;

import com.herabarman.meshnet.core.JitterBuffer;
import com.herabarman.meshnet.core.Packet;
import com.herabarman.meshnet.core.VoiceLayer;
import java.util.List;

/** Plain main() tests for live voice (no test framework needed): java -cp ... VoiceTests */
public final class VoiceTests {
    static int passed, failed;

    static void check(boolean ok, String name) {
        if (ok) passed++; else { failed++; System.out.println("FAIL: " + name); }
    }

    static byte[] f(int tag) { return new byte[]{(byte) tag, 1, 2, 3}; }

    // ---- JitterBuffer ---------------------------------------------------------------------

    static void jitterBuffer() {
        JitterBuffer jb = new JitterBuffer(80, 600);
        jb.push(10, f(10), 60, true, 1000);
        jb.push(12, f(12), 60, false, 1100);             // 11 is missing
        jb.push(13, f(13), 60, false, 1190);
        check(jb.poll(1079).isEmpty(), "nothing before the playout delay");
        List<JitterBuffer.Out> o = jb.poll(1080);
        check(o.size() == 1 && o.get(0).seq == 10 && o.get(0).data != null, "first frame at firstArrival + delay");
        o = jb.poll(1140 + 60);
        check(o.size() == 2 && o.get(0).seq == 11 && o.get(0).data == null && o.get(1).seq == 12 && o.get(1).data != null, "hole reported as lost, then next frame");
        check(jb.lost() == 1, "lost counted once");
        check(!jb.push(11, f(11), 60, false, 1500), "a frame after its turn is dropped as late");
        check(jb.late() == 1, "late counted");
        check(!jb.push(13, f(13), 60, false, 1210), "duplicate dropped");
        check(jb.duplicates() == 1, "duplicate counted");
    }

    static void jitterBufferWrapAndRecover() {
        JitterBuffer jb = new JitterBuffer(80, 600);
        jb.push(65534, f(1), 60, true, 0);
        jb.push(65535, f(2), 60, false, 60);
        jb.push(0, f(3), 60, false, 120);                 // 16-bit wrap
        jb.push(1, f(4), 60, false, 180);
        List<JitterBuffer.Out> o = jb.poll(80 + 3 * 60);
        check(o.size() == 4 && o.get(2).seq == 0 && o.get(3).seq == 1, "sequence numbers wrap");
        JitterBuffer r = new JitterBuffer(80, 600);
        r.push(5, f(5), 60, true, 0);
        r.push(7, f(7), 60, false, 120);                  // 6 lost
        check(r.pushRecovered(6, f(6), 121), "a redundant copy fills the hole");
        check(!r.pushRecovered(6, f(6), 121), "...only once");
        List<JitterBuffer.Out> o2 = r.poll(80 + 3 * 60);
        check(o2.size() == 3 && o2.get(1).data != null && o2.get(1).seq == 6, "recovered frame plays in order");
        check(r.recovered() == 1 && r.lost() == 0, "recovered counted, nothing lost");
    }

    static void jitterAdaptsAfterLateFrames() {
        JitterBuffer jb = new JitterBuffer(80, 600);
        jb.push(0, f(0), 60, true, 0);
        for (int i = 1; i < 20; i++) jb.push(i, f(i), 60, false, i * 60 + (i % 2 == 0 ? 90 : 0));   // bursty arrival
        jb.poll(5000);
        jb.reset();
        jb.push(100, f(1), 60, true, 10_000);
        check(jb.delayMs() > 80, "bursty link gets a bigger buffer in the next spurt (" + jb.delayMs() + " ms)");
    }

    // ---- over the simulated mesh ----------------------------------------------------------

    static SimConfig cfg() {
        SimConfig c = new SimConfig();
        c.nodes = 120;
        c.zoneSize = 12;
        c.onlineRatio = 0.0;
        c.seed = 11;
        c.backbone = "full";
        return c;
    }

    static void sendSpurt(final SimNetwork net, final VoiceLayer tx, final int call, int count, int frameMs) {
        for (int i = 0; i < count; i++) {
            final int seq = i;
            net.clock.schedule((long) i * frameMs, new Runnable() {
                @Override public void run() { tx.send(call, new byte[]{(byte) seq, 0, 9, 9, 9, 9}); }
            });
        }
        net.clock.schedule((long) count * frameMs, new Runnable() {
            @Override public void run() { tx.endTalk(call); }
        });
        net.clock.runFor((long) count * frameMs + 3000);
    }

    static int count(SimNetwork net, int node, int from, String kind) {
        int n = 0;
        for (SimNetwork.VoiceEvent e : net.voiceEvents()) if (e.node == node && e.from == from && kind.equals(e.kind)) n++;
        return n;
    }

    static void sameZoneAndCrossZone() {
        for (int dstZone : new int[]{0, 6}) {
            SimConfig c = cfg();
            c.encrypt = true;                                       // voice is encrypted like every message
            SimNetwork net = new SimNetwork(c);
            net.enableVoice();
            int src = 2, dst = dstZone == 0 ? 3 : 6 * 12 + 4;
            VoiceLayer tx = net.voiceLayer(src);
            int call = tx.open(dst, 60);
            sendSpurt(net, tx, call, 50, 60);
            String tag = dstZone == 0 ? "same zone" : "other zone";
            check(count(net, dst, src, "frame") == 50, tag + ": all 50 packets played (" + count(net, dst, src, "frame") + ")");
            check(count(net, dst, src, "lost") == 0, tag + ": none lost");
            check(count(net, dst, src, "start") == 1 && count(net, dst, src, "end") == 1, tag + ": one start and one end");
            boolean inOrder = true;
            int last = -1;
            for (SimNetwork.VoiceEvent e : net.voiceEvents()) {
                if (e.node != dst || !"frame".equals(e.kind)) continue;
                int s = e.data[0] & 0xFF;
                if (s != last + 1) inOrder = false;
                last = s;
            }
            check(inOrder, tag + ": frames come out in order and intact");
            check(count(net, src, dst, "report") >= 2, tag + ": the sender gets reports (" + count(net, src, dst, "report") + ")");
        }
    }

    static void reportsTeachTheRouteSoFramesStopFlooding() {
        SimConfig c = cfg();
        SimNetwork net = new SimNetwork(c);
        net.enableVoice();
        int src = 2, dst = 6 * 12 + 4;
        VoiceLayer tx = net.voiceLayer(src);
        int call = tx.open(dst, 60);
        sendSpurt(net, tx, call, 100, 60);                          // 6 s of audio
        check(net.node(src).routeTo(dst) >= 0, "the sender learned a route to the receiver from the reports");
        double perPacket = (double) net.metrics.txByType[Packet.TYPE_VOICE] / 100.0;
        check(perPacket < 8.0, "radio transmissions per voice packet stay small, not a flood (" + perPacket + ")");
    }

    static void noVoiceIsEverStoredOrRetried() {
        SimConfig c = cfg();
        c.lossRate = 1.0;                                           // the radio is dead
        SimNetwork net = new SimNetwork(c);
        net.enableVoice();
        int src = 2, dst = 6 * 12 + 4;
        VoiceLayer tx = net.voiceLayer(src);
        int call = tx.open(dst, 60);
        sendSpurt(net, tx, call, 20, 60);
        net.clock.runFor(30_000);
        check(net.node(src).storedCount() == 0, "no voice packet waits in the store");
        check(net.metrics.retries == 0 && net.metrics.unacked == 0, "no retry, no ACK timeout for voice");
        check(net.clock.pending() < 50, "nothing keeps running after the call (" + net.clock.pending() + " timers)");
    }

    /** The destination zone loses every border node: a text message is parked in the store, a voice packet must be dropped instead. */
    static void voiceIsDroppedNotParkedWhenThereIsNoRoute() {
        for (int voice = 0; voice < 2; voice++) {
            SimConfig c = cfg();
            c.nodes = 100;
            c.zoneSize = 20;                                        // 5 zones in a ring: gateways store what they cannot forward
            c.backbone = "chord";
            SimNetwork net = new SimNetwork(c);
            net.enableVoice();
            int dst = -1;
            for (int id = 3 * 20; id < 4 * 20 && dst < 0; id++) {
                boolean inside = true;
                for (int nb : net.linksOf(id)) if (net.zoneOf(nb) != 3) inside = false;
                if (inside) dst = id;
            }
            check(dst >= 0, "found a node of zone 3 without outside links");
            for (int id = 3 * 20; id < 4 * 20; id++) if (id != dst) net.killNode(id);
            net.clock.runFor(100);
            int src = 2;
            if (voice == 1) {
                VoiceLayer tx = net.voiceLayer(src);
                int call = tx.open(dst, 60);
                sendSpurt(net, tx, call, 20, 60);
            } else {
                for (int i = 0; i < 5; i++) net.sendMessage(src, dst, new byte[]{(byte) i}, 100);
                net.clock.runFor(2000);
            }
            int stored = 0;
            for (int id = 0; id < c.nodes; id++) stored += net.node(id).storedCount();
            if (voice == 0) check(stored > 0, "control: a text message is parked in the store (" + stored + ")");
            else check(stored == 0, "voice is never parked in the store (" + stored + ")");
        }
    }

    static void pushToTalkTwoPressesTwoSpurts() {
        SimNetwork net = new SimNetwork(cfg());
        net.enableVoice();
        int src = 2, dst = 3;
        VoiceLayer tx = net.voiceLayer(src);
        int call = tx.open(dst, 60);
        sendSpurt(net, tx, call, 10, 60);
        sendSpurt(net, tx, call, 10, 60);
        check(count(net, dst, src, "start") == 2 && count(net, dst, src, "end") == 2, "two presses are two talk spurts");
        check(count(net, dst, src, "frame") == 20 && count(net, dst, src, "lost") == 0, "all audio of both presses arrives");
    }

    static void redundancyTurnsOnWhenTheReceiverReportsLoss() {
        SimConfig c = cfg();
        c.lossRate = 0.10;
        SimNetwork net = new SimNetwork(c);
        net.enableVoice();
        int src = 2, dst = 6 * 12 + 4;
        VoiceLayer tx = net.voiceLayer(src);
        int call = tx.open(dst, 60);
        sendSpurt(net, tx, call, 200, 60);
        check(tx.redundancy(call), "the sender switched redundancy on after loss reports (loss " + tx.lossPercent(call) + "%)");
        check(net.voiceLayer(dst).incoming(src, call).recovered() > 0, "the receiver recovered frames from the copies");
    }

    static void textKeepsWorkingNextToACall() {
        SimNetwork net = new SimNetwork(cfg());
        net.enableVoice();
        int src = 2, dst = 6 * 12 + 4;
        VoiceLayer tx = net.voiceLayer(src);
        int call = tx.open(dst, 60);
        for (int i = 0; i < 5; i++) net.sendMessage(src, dst, new byte[]{(byte) i}, 100);
        sendSpurt(net, tx, call, 100, 60);
        for (int i = 5; i < 10; i++) net.sendMessage(src, dst, new byte[]{(byte) i}, 100);
        net.clock.runFor(10_000);
        check(net.metrics.delivered == 10, "all 10 text messages delivered during a call (" + net.metrics.delivered + ")");
        check(net.metrics.acked == 10, "all 10 text messages ACKed");
    }

    public static void main(String[] args) {
        jitterBuffer();
        jitterBufferWrapAndRecover();
        jitterAdaptsAfterLateFrames();
        sameZoneAndCrossZone();
        reportsTeachTheRouteSoFramesStopFlooding();
        noVoiceIsEverStoredOrRetried();
        voiceIsDroppedNotParkedWhenThereIsNoRoute();
        pushToTalkTwoPressesTwoSpurts();
        redundancyTurnsOnWhenTheReceiverReportsLoss();
        textKeepsWorkingNextToACall();
        System.out.println("voice tests: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
