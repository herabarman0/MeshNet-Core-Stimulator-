package com.herabarman.meshnet.kit;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MultiChannelRadio over REAL sockets: each channel is a separate UdpRadio (own UDP port), the way a Wi-Fi radio and a LoRa/BLE radio
 * would be separate. A "jammer" drops everything on one channel's ports; the link must move to the other radio and data must go on.
 */
public final class UdpChannelTests {
    static int passed, failed;

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("PASS  " + what); } else { failed++; System.out.println("FAIL  " + what); }
    }

    /** Wraps a radio and drops all traffic in both directions while jammed. */
    static final class Jammable implements RawRadio {
        final RawRadio inner;
        volatile boolean jammed;
        Handler h;
        Jammable(RawRadio r) {
            inner = r;
            r.setHandler(new Handler() {
                @Override public void onLinkUp(int p) { if (h != null) h.onLinkUp(p); }
                @Override public void onLinkDown(int p) { if (h != null) h.onLinkDown(p); }
                @Override public void onRaw(int p, byte[] d) { if (!jammed && h != null) h.onRaw(p, d); }
            });
        }
        @Override public void sendRaw(int p, byte[] d) { if (!jammed) inner.sendRaw(p, d); }
        @Override public void setHandler(Handler x) { h = x; }
        @Override public void start() { inner.start(); }
        @Override public void stop() { inner.stop(); }
    }

    public static void main(String[] args) throws Exception {
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        int base = 46000 + (int) (System.nanoTime() % 1000);
        UdpRadio a0 = new UdpRadio(1, base, false, 300, 2000), a1 = new UdpRadio(1, base + 1, false, 300, 2000);
        UdpRadio b0 = new UdpRadio(2, base + 2, false, 300, 2000), b1 = new UdpRadio(2, base + 3, false, 300, 2000);
        a0.addPeer(new InetSocketAddress(lo, base + 2)); b0.addPeer(new InetSocketAddress(lo, base));
        a1.addPeer(new InetSocketAddress(lo, base + 3)); b1.addPeer(new InetSocketAddress(lo, base + 1));
        Jammable ja0 = new Jammable(a0), ja1 = new Jammable(a1), jb0 = new Jammable(b0), jb1 = new Jammable(b1);

        RawRadio a = new MultiChannelBuilder(1).add(0, ja0, 0.3).add(1, ja1, 1.0).build();
        RawRadio b = new MultiChannelBuilder(2).add(0, jb0, 0.3).add(1, jb1, 1.0).build();
        check("two radios become one MultiChannelRadio", a instanceof MultiChannelRadio && b instanceof MultiChannelRadio);
        check("a single radio is returned as it is", new MultiChannelBuilder(1).add(0, ja0, 1).build() == ja0);

        final AtomicInteger got = new AtomicInteger(), ups = new AtomicInteger();
        a.setHandler(new RawRadio.Handler() {
            @Override public void onLinkUp(int p) { ups.incrementAndGet(); }
            @Override public void onLinkDown(int p) { }
            @Override public void onRaw(int p, byte[] d) { }
        });
        b.setHandler(new RawRadio.Handler() {
            @Override public void onLinkUp(int p) { }
            @Override public void onLinkDown(int p) { }
            @Override public void onRaw(int p, byte[] d) { if (d.length == 3 && d[0] == 9) got.incrementAndGet(); }
        });
        a.start(); b.start();
        Thread.sleep(2500);
        check("the upper layer sees one link up over real UDP sockets", ups.get() == 1);
        MultiChannelRadio ma = (MultiChannelRadio) a, mb = (MultiChannelRadio) b;
        check("link starts on the faster channel 1", ma.channelOf(2) == 1 && mb.channelOf(1) == 1);
        for (int i = 0; i < 30; i++) { a.sendRaw(2, new byte[]{9, 1, 2}); Thread.sleep(15); }
        Thread.sleep(300);
        check("data crosses on channel 1 (" + got.get() + "/30)", got.get() >= 29);

        ja1.jammed = true; jb1.jammed = true;
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 20000 && (ma.channelOf(2) == 1 || mb.channelOf(1) == 1)) Thread.sleep(200);
        long took = System.currentTimeMillis() - t0;
        check("jammed Wi-Fi channel is left, both ends agree (now " + ma.channelOf(2) + "/" + mb.channelOf(1) + ", " + took + " ms)", ma.channelOf(2) == 0 && mb.channelOf(1) == 0);
        got.set(0);
        for (int i = 0; i < 30; i++) { a.sendRaw(2, new byte[]{9, 1, 2}); Thread.sleep(15); }
        Thread.sleep(300);
        check("data keeps flowing over the other radio (" + got.get() + "/30)", got.get() >= 29);
        a.stop(); b.stop();
        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
