package com.herabarman.meshnet.kit;

import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** MultiChannelRadio over three in-memory radios (real threads and clock): data flows, a jammed channel is left, data keeps flowing. */
public final class MultiChannelTests {
    static int passed, failed;

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("PASS  " + what); } else { failed++; System.out.println("FAIL  " + what); }
    }

    /** One end of a fake radio pair; jam[0] = true drops everything in both directions. */
    static final class Fake implements RawRadio {
        final int me;
        Fake other;
        Handler h;
        volatile boolean[] jam;
        Fake(int me, boolean[] jam) { this.me = me; this.jam = jam; }
        @Override public void sendRaw(int peer, byte[] d) {
            if (jam[0] || other == null || other.h == null) return;
            other.h.onRaw(me, d.clone());
        }
        @Override public void setHandler(Handler handler) { h = handler; }
    }

    public static void main(String[] args) throws Exception {
        final boolean[][] jam = {new boolean[1], new boolean[1], new boolean[1]};
        Fake[][] r = new Fake[2][3];
        LinkedHashMap<Integer, RawRadio> ra = new LinkedHashMap<>(), rb = new LinkedHashMap<>();
        for (int c = 0; c < 3; c++) {
            r[0][c] = new Fake(1, jam[c]); r[1][c] = new Fake(2, jam[c]);
            r[0][c].other = r[1][c]; r[1][c].other = r[0][c];
            ra.put(c, r[0][c]); rb.put(c, r[1][c]);
        }
        double[] cap = {0.2, 0.7, 1.0};
        MultiChannelRadio a = new MultiChannelRadio(1, ra, cap, null), b = new MultiChannelRadio(2, rb, cap, null);
        final AtomicInteger got = new AtomicInteger(), upA = new AtomicInteger();
        a.setHandler(new RawRadio.Handler() {
            @Override public void onLinkUp(int p) { upA.incrementAndGet(); }
            @Override public void onLinkDown(int p) { }
            @Override public void onRaw(int p, byte[] d) { }
        });
        b.setHandler(new RawRadio.Handler() {
            @Override public void onLinkUp(int p) { }
            @Override public void onLinkDown(int p) { }
            @Override public void onRaw(int p, byte[] d) { if (d.length == 3 && d[0] == 7) got.incrementAndGet(); }
        });
        a.start(); b.start();
        for (int c = 0; c < 3; c++) { r[0][c].h.onLinkUp(2); r[1][c].h.onLinkUp(1); }
        Thread.sleep(300);
        check("the upper layer sees ONE link up even though three radios connect", upA.get() == 1);
        int before = a.channelOf(2);
        check("the link starts on the best channel (5 GHz)", before == 2 && b.channelOf(1) == 2);
        for (int i = 0; i < 20; i++) { a.sendRaw(2, new byte[]{7, 1, 2}); Thread.sleep(20); }
        check("data crosses the link", got.get() == 20);

        jam[2][0] = true;                                       // jam the 5 GHz channel
        Thread.sleep(13000);
        int now = a.channelOf(2);
        check("jammed channel is left within 13 seconds (no RSSI or noise reports: the slow path) (now on " + now + ")", now != 2 && now == b.channelOf(1));
        got.set(0);
        for (int i = 0; i < 20; i++) { a.sendRaw(2, new byte[]{7, 1, 2}); Thread.sleep(20); }
        check("data keeps flowing on the new channel", got.get() == 20);
        jam[2][0] = false;
        a.stop(); b.stop();
        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
