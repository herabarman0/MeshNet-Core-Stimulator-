package com.herabarman.meshnet.core;

import static com.herabarman.meshnet.core.TestKit.check;
import static com.herabarman.meshnet.core.TestKit.info;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Anti-jamming: per-link channel switching, recovery, rendezvous after a wideband jam, authenticated switch requests. */
public final class JamTests {
    public static void main(String[] args) {
        narrowband();
        falsePositive();
        regional();
        widebandAndRendezvous();
        authentication();
        TestKit.summary();
    }

    static final int[] CH = {0, 1, 2};                       // 0 = BLE, 1 = Wi-Fi 2.4 GHz, 2 = Wi-Fi 5 GHz
    static final double[] CAP = {0.2, 0.7, 1.0};

    /** Interface the test uses to say what is jammed. */
    interface Jam { boolean jammed(int channel, int a, int b, long now); }

    static final class World {
        final TestKit.Clk clock = new TestKit.Clk();
        final Random rng = new Random(11);
        final int n;
        final ChannelPlanner[] pl;
        final List<int[]> edges = new ArrayList<>();
        Jam jam = new Jam() { @Override public boolean jammed(int c, int a, int b, long t) { return false; } };
        double baseLoss = 0.03, jamPass = 0.05, rssi = -60;
        boolean noiseFloor = true;
        long delivered, sent;
        final List<ChannelPlanner.ControlAuth> auths = new ArrayList<>();
        final boolean usePlanner;
        final int fixedChannel;

        World(int n, boolean usePlanner, int fixedChannel, ChannelPlanner.ControlAuth auth) {
            this.n = n;
            this.usePlanner = usePlanner;
            this.fixedChannel = fixedChannel;
            pl = new ChannelPlanner[n];
            for (int i = 0; i < n; i++) {
                final int me = i;
                pl[i] = new ChannelPlanner(i, CH, CAP, new ChannelPlanner.Config(), clock, new Random(40 + i), new ChannelPlanner.Link() {
                    @Override public void sendOn(final int ch, final int peer, final byte[] m) {
                        if (!ok(ch, me, peer)) return;
                        clock.schedule(10, new Runnable() { @Override public void run() { pl[peer].onControl(me, ch, m); } });
                    }
                });
                if (auth != null) pl[i].setAuth(auth);
            }
        }

        boolean ok(int ch, int a, int b) {
            boolean j = jam.jammed(ch, a, b, clock.nowMs());
            return rng.nextDouble() < (j ? jamPass : 1 - baseLoss);
        }

        void connect(int a, int b, int initial) {
            edges.add(new int[]{a, b});
            pl[a].addPeer(b, CH, initial);
            pl[b].addPeer(a, CH, initial);
        }

        void startAll() { for (ChannelPlanner p : pl) p.start(); }

        /** Every 100 ms each end sends one packet over each of its links, on the channel its planner (or the fixed one) says. */
        void traffic(final long untilMs, final long[] okAt) {
            clock.schedule(100, new Runnable() {
                @Override public void run() {
                    for (int[] e : edges) for (int dir = 0; dir < 2; dir++) {
                        final int a = dir == 0 ? e[0] : e[1], b = dir == 0 ? e[1] : e[0];
                        final int ch = usePlanner ? pl[a].channelFor(b) : fixedChannel;
                        final boolean good = ok(ch, a, b);
                        sent++;
                        if (good) delivered++;
                        if (okAt != null && e[0] == 0 && dir == 0) okAt[(int) (clock.nowMs() / 1000)] += good ? 1 : 0;
                        if (usePlanner) {
                            if (jam.jammed(ch, a, b, clock.nowMs()) && noiseFloor) pl[a].onNoiseFloor(ch, -70);
                            else if (noiseFloor) pl[a].onNoiseFloor(ch, -95);
                            clock.schedule(30, new Runnable() { @Override public void run() {
                                pl[a].onTx(b, ch, good);
                                if (good) { pl[a].onRssi(b, ch, rssi); pl[b].onReceive(a, ch); }
                            } });
                        }
                    }
                    if (clock.nowMs() < untilMs) clock.schedule(100, this);
                }
            });
        }
    }

    static void narrowband() {
        // jam channel 2 (the best one) from 30 s to 150 s; link starts there
        long[] okBase = new long[400], okPlan = new long[400];
        World base = new World(2, false, 2, null), plan = new World(2, true, 2, null);
        Jam j = new Jam() { @Override public boolean jammed(int c, int a, int b, long t) { return c == 2 && t >= 30_000 && t < 150_000; } };
        base.jam = j; plan.jam = j;
        base.connect(0, 1, 2); plan.connect(0, 1, 2);
        plan.startAll();
        base.traffic(300_000, okBase); plan.traffic(300_000, okPlan);
        final List<String> log = new ArrayList<>();
        plan.pl[0].setListener(new ChannelPlanner.Listener() {
            @Override public void onLinkChannel(int p, int f, int t, String r) { log.add(f + "->" + t + " @" + plan.clock.nowMs() / 1000 + "s"); }
            @Override public void onLost(int p) { log.add("lost"); }
            @Override public void onFound(int p, int c) { }
        });
        base.clock.run(300_000);
        plan.clock.run(300_000);
        long b = 0, p = 0;
        for (int s = 35; s < 150; s++) { b += okBase[s]; p += okPlan[s]; }
        int recover = -1;
        for (int s = 30; s < 150 && recover < 0; s++) if (okPlan[s] >= 7 && okPlan[s + 1] >= 7 && okPlan[s + 2] >= 7) recover = s - 30;
        info(String.format("jam on the best channel for 120 s: delivery during the jam %.0f%% fixed vs %.0f%% with channel switching, back online %d s after the jam began, switches %s",
                100.0 * b / 1150, 100.0 * p / 1150, recover, log));
        check("without switching the jam kills the link (<15% delivered)", b / 1150.0 < 0.15);
        check("with switching the link is back within 10 s", recover >= 0 && recover <= 10);
        check("with switching most traffic during the jam gets through (>= 85%)", p / 1150.0 >= 0.85);
        check("both ends agree on the channel", plan.pl[0].channelFor(1) == plan.pl[1].channelFor(0));
        // after the jam ends the best channel comes back
        check("after the jam ends the link returns to the best channel (" + plan.pl[0].channelFor(1) + ")", plan.pl[0].channelFor(1) == 2 && plan.pl[1].channelFor(0) == 2);
    }

    static void falsePositive() {
        World w = new World(2, true, 1, null);
        w.baseLoss = 0.38; w.rssi = -93;          // a far, weak neighbour: lossy but not jammed
        w.connect(0, 1, 1);
        w.startAll();
        w.traffic(600_000, null);
        w.clock.run(600_000);
        check("a weak, lossy, far-away link is NOT mistaken for a jammer (switches: " + (w.pl[0].switches + w.pl[1].switches) + ")", w.pl[0].switches + w.pl[1].switches == 0);
        World q = new World(2, true, 2, null);
        q.connect(0, 1, 2);
        q.startAll();
        q.traffic(600_000, null);
        q.clock.run(600_000);
        check("a healthy link never switches", q.pl[0].switches + q.pl[1].switches == 0);
    }

    static void regional() {
        // a line of 5 phones; a jammer sits near phones 2-3 and hits Wi-Fi 5 GHz (channel 2) on every link that touches them
        World w = new World(5, true, 2, null);
        w.jam = new Jam() { @Override public boolean jammed(int c, int a, int b, long t) {
            return c == 2 && t >= 20_000 && ((a >= 1 && a <= 3) || (b >= 1 && b <= 3)) && !(a == 0 && b == 1) && !(a == 1 && b == 0); } };
        for (int i = 0; i < 4; i++) w.connect(i, i + 1, 2);
        w.startAll();
        w.traffic(180_000, null);
        w.clock.run(180_000);
        boolean consistent = true;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) { int c1 = w.pl[i].channelFor(i + 1), c2 = w.pl[i + 1].channelFor(i); if (c1 != c2) consistent = false; sb.append(i).append('-').append(i + 1).append("=ch").append(c1).append(' '); }
        info("regional jammer, link channels now: " + sb);
        check("both ends of every link agree on its channel", consistent);
        check("links inside the jammed area moved off the jammed channel", w.pl[1].channelFor(2) != 2 && w.pl[2].channelFor(3) != 2 && w.pl[3].channelFor(4) != 2);
        check("a link outside the jammed area stayed where it was (the mesh moves only where it must)", w.pl[0].channelFor(1) == 2);
        check("overall delivery stays high (" + (100 * w.delivered / w.sent) + "%)", w.delivered > 0.9 * w.sent);
    }

    static void widebandAndRendezvous() {
        World w = new World(2, true, 1, null);
        w.jamPass = 0;
        w.jam = new Jam() { @Override public boolean jammed(int c, int a, int b, long t) { return t >= 40_000 && t < 100_000; } };   // every channel, 60 s
        w.connect(0, 1, 1);
        w.startAll();
        final long[] events = {-1, -1};
        w.pl[0].setListener(new ChannelPlanner.Listener() {
            @Override public void onLinkChannel(int p, int f, int t, String r) { }
            @Override public void onLost(int p) { if (events[0] < 0) events[0] = w.clock.nowMs(); }
            @Override public void onFound(int p, int c) { if (events[1] < 0 && w.clock.nowMs() > 100_000) events[1] = w.clock.nowMs(); }
        });
        w.traffic(200_000, null);
        w.clock.run(200_000);
        info("wideband jam 40-100 s: link declared lost at " + events[0] / 1000 + " s, found again at " + events[1] / 1000 + " s");
        check("a link that is dead on every channel is declared lost", events[0] > 40_000 && events[0] < 70_000);
        check("after the jam ends the two phones find each other again within 5 s", events[1] >= 100_000 && events[1] <= 105_000);
        check("and both ends are on the same channel again", w.pl[0].channelFor(1) == w.pl[1].channelFor(0));
    }

    static ChannelPlanner.ControlAuth hmac(final byte[] key) {
        return new ChannelPlanner.ControlAuth() {
            byte[] mac(byte[] d) {
                try { Mac m = Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(key, "HmacSHA256")); return java.util.Arrays.copyOf(m.doFinal(d), 8); }
                catch (Exception e) { throw new IllegalStateException(e); }
            }
            @Override public byte[] tag(byte[] d) { return mac(d); }
            @Override public boolean check(byte[] d, byte[] t) { return java.security.MessageDigest.isEqual(mac(d), t); }
        };
    }

    static void authentication() {
        ChannelPlanner.ControlAuth good = hmac("mesh-network-key".getBytes());
        World w = new World(2, true, 2, good);
        w.connect(0, 1, 2);
        // an attacker (no key) tells phone 1 "move to channel 0" (the one it can jam)
        byte[] forged = ByteBuffer.allocate(16).put((byte) 1).putInt(5).put((byte) 0).put((byte) 1).put(new byte[8]).array();
        w.pl[1].onControl(0, 2, forged);
        check("a forged switch request without the network key is rejected", w.pl[1].channelFor(0) == 2 && w.pl[1].rejected == 1);
        // a request from a phone with the right key is honoured
        World w2 = new World(2, true, 2, good);
        w2.connect(0, 1, 2);
        w2.startAll();
        w2.jam = new Jam() { @Override public boolean jammed(int c, int a, int b, long t) { return c == 2; } };
        w2.traffic(60_000, null);
        w2.clock.run(60_000);
        check("genuine, authenticated switching still works", w2.pl[0].channelFor(1) != 2 && w2.pl[0].channelFor(1) == w2.pl[1].channelFor(0));
        // a different network key
        World w3 = new World(2, true, 2, null);
        w3.pl[0].setAuth(good); w3.pl[1].setAuth(hmac("other-key-0000000".getBytes()));
        w3.connect(0, 1, 2); w3.startAll();
        w3.jam = new Jam() { @Override public boolean jammed(int c, int a, int b, long t) { return c == 2; } };
        w3.traffic(60_000, null);
        w3.clock.run(60_000);
        check("phones with different keys never agree to a switch (nobody can be herded)", w3.pl[0].switches == 0 && w3.pl[1].switches == 0);
    }
}
