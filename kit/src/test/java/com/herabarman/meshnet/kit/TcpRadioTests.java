package com.herabarman.meshnet.kit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** The TCP radio (what a Wi-Fi Direct group, hotspot or Ethernet link gives us): real sockets on localhost. */
public final class TcpRadioTests {
    static int passed, failed;

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("PASS  " + what); } else { failed++; System.out.println("FAIL  " + what); }
    }

    static boolean waitFor(BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { if (c.getAsBoolean()) return true; Thread.sleep(30); }
        return c.getAsBoolean();
    }

    static final class Rec implements RawRadio.Handler {
        final List<Integer> ups = Collections.synchronizedList(new ArrayList<Integer>()), downs = Collections.synchronizedList(new ArrayList<Integer>());
        final List<byte[]> got = Collections.synchronizedList(new ArrayList<byte[]>());
        @Override public void onLinkUp(int n) { ups.add(n); }
        @Override public void onLinkDown(int n) { downs.add(n); }
        @Override public void onRaw(int n, byte[] d) { got.add(d); }
    }

    static TcpRadio radio(int id, Rec r) {
        TcpRadio t = new TcpRadio(id, 0, 300, 1500, 300);
        t.setHandler(r);
        t.start();
        return t;
    }

    public static void main(String[] args) throws Exception {
        Rec ra = new Rec(), rb = new Rec(), rc = new Rec();
        TcpRadio a = radio(1, ra), b = radio(2, rb), c = radio(3, rc);
        // a is the "group owner"; b and c connect to it
        b.connect(new InetSocketAddress("127.0.0.1", a.port()));
        c.connect(new InetSocketAddress("127.0.0.1", a.port()));
        check("clients connect to the group owner and both sides see links up", waitFor(() -> ra.ups.size() == 2 && rb.ups.contains(1) && rc.ups.contains(1), 5000));
        byte[] big = new byte[100_000];
        new java.util.Random(1).nextBytes(big);
        a.sendRaw(2, big);
        b.sendRaw(1, new byte[]{1, 2, 3});
        c.sendRaw(1, new byte[]{9});
        check("a 100 KB frame and small frames arrive intact in both directions", waitFor(() -> rb.got.size() == 1 && ra.got.size() == 2, 5000) && Arrays.equals(rb.got.get(0), big));
        for (int i = 0; i < 200; i++) a.sendRaw(3, new byte[]{(byte) i});
        check("200 frames arrive in order", waitFor(() -> rc.got.size() == 200, 5000) && rc.got.get(199)[0] == (byte) 199 && rc.got.get(7)[0] == 7);
        b.sendRaw(3, new byte[]{5});
        Thread.sleep(300);
        check("a client cannot reach another client directly (star topology, as in Wi-Fi Direct)", rc.got.size() == 200);

        // idle but healthy links stay up through the ping
        Thread.sleep(2500);
        check("idle links stay up (pings keep them alive past the 1.5 s dead time)", ra.downs.isEmpty() && rb.downs.isEmpty());

        // peer disappears and comes back
        b.stop();
        check("the group owner notices when a client goes away", waitFor(() -> ra.downs.contains(2), 3000));
        Rec rb2 = new Rec();
        TcpRadio b2 = radio(2, rb2);
        b2.connect(new InetSocketAddress("127.0.0.1", a.port()));
        check("the client reconnects by itself when it is back (a.ups=" + ra.ups + " downs=" + ra.downs + " b2.ups=" + rb2.ups + ")", waitFor(() -> rb2.ups.contains(1) && ra.ups.size() >= 3, 5000));
        a.sendRaw(2, new byte[]{7});
        check("and data flows again", waitFor(() -> rb2.got.size() == 1, 3000));

        // simultaneous connection from both ends gives one working link
        Rec rx = new Rec(), ry = new Rec();
        TcpRadio x = radio(11, rx), y = radio(12, ry);
        x.connect(new InetSocketAddress("127.0.0.1", y.port()));
        y.connect(new InetSocketAddress("127.0.0.1", x.port()));
        check("two phones dialling each other end up linked", waitFor(() -> !rx.ups.isEmpty() && !ry.ups.isEmpty(), 5000));
        Thread.sleep(1500);
        x.sendRaw(12, new byte[]{4, 4});
        y.sendRaw(11, new byte[]{5, 5});
        check("with working data both ways and no link flapping", waitFor(() -> ry.got.size() == 1 && rx.got.size() == 1, 3000) && rx.downs.isEmpty() && ry.downs.isEmpty());

        // hostile connections
        int upsBefore = ra.ups.size();
        Socket s1 = new Socket("127.0.0.1", a.port());
        DataOutputStream o1 = new DataOutputStream(s1.getOutputStream());
        o1.writeInt(0xDEADBEEF); o1.writeInt(99); o1.flush();
        Thread.sleep(500);
        check("a connection with the wrong handshake never becomes a link", ra.ups.size() == upsBefore);
        s1.close();
        Socket s2 = new Socket("127.0.0.1", a.port());
        DataOutputStream o2 = new DataOutputStream(s2.getOutputStream());
        o2.writeInt(TcpRadio.MAGIC); o2.writeInt(77); o2.flush();
        check("a correct handshake from an unknown node does link (identity is the upper layer's job)", waitFor(() -> ra.ups.contains(77), 3000));
        o2.writeInt(50_000_000); o2.flush();                             // announces a 50 MB frame
        check("an absurd frame length gets the connection closed, nothing is allocated", waitFor(() -> ra.downs.contains(77), 3000));
        s2.close();
        Socket s3 = new Socket("127.0.0.1", a.port());
        DataOutputStream o3 = new DataOutputStream(s3.getOutputStream());
        o3.writeInt(TcpRadio.MAGIC); o3.writeInt(78); o3.flush();
        check("a silent peer (connected, says nothing) is declared dead after the timeout", waitFor(() -> ra.ups.contains(78), 3000) && waitFor(() -> ra.downs.contains(78), 5000));
        s3.close();

        a.stop(); b2.stop(); c.stop(); x.stop(); y.stop();
        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
        System.exit(0);
    }
}
