package com.herabarman.meshnet.kit;

import static com.herabarman.meshnet.kit.KitTests.check;
import static com.herabarman.meshnet.kit.KitTests.waitFor;

import com.herabarman.meshnet.core.MemoryStorage;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Big payloads to a direct neighbour over a BulkLink (TcpBulk on loopback, and fake links for failure cases). */
public final class BulkTests {
    /** A BulkLink between two in-process clients that can lose, corrupt or record what it carries. */
    static final class FakeBulk implements BulkLink {
        final java.util.Map<Integer, FakeBulk> peers = new ConcurrentHashMap<>();
        final int self;
        volatile Receiver rx;
        volatile boolean fail, corrupt;
        final AtomicInteger sent = new AtomicInteger();
        volatile byte[] lastWire;
        FakeBulk(int self) { this.self = self; }
        public boolean canSend(int node) { return peers.containsKey(node); }
        public void setReceiver(Receiver r) { rx = r; }
        public void send(int node, byte[] data, Done cb) {
            sent.incrementAndGet();
            lastWire = data.clone();
            if (fail) { cb.onDone(false); return; }
            byte[] d = data.clone();
            if (corrupt) d[d.length - 1] ^= 1;
            FakeBulk p = peers.get(node);
            p.rx.onReceived(self, d);
            cb.onDone(true);
        }
    }

    static long ackMs = 20_000;

    static MeshClient client(MemNet net, KitTests.Probe p, int id, BulkLink bulk, int minBytes) {
        MeshClient.Builder b = MeshClient.builder(Integer.toString(id)).radio(net.radio(id)).storage(new MemoryStorage()).listener(p)
                .retry(400, 200).zoneHold(100).tune(cfg -> { cfg.ackTimeoutMs = 700; cfg.retryMs = 300; });
        if (bulk != null) b.bulk(bulk, minBytes).bulkAckTimeout(ackMs);
        MeshClient c = b.build();
        c.updateLocation(KitTests.LAT0, KitTests.LON0);
        c.start();
        return c;
    }

    static void tcpLoopback() throws Exception {
        MemNet net = new MemNet();
        KitTests.Probe pa = new KitTests.Probe(), pb = new KitTests.Probe();
        TcpBulk ta = new TcpBulk(1, 0), tb = new TcpBulk(2, 0);
        MeshClient a = client(net, pa, 1, ta, 16 * 1024), b = client(net, pb, 2, tb, 16 * 1024);
        net.link(1, 2);
        check("bulk: neighbours up", waitFor(() -> a.peerCount() == 1 && b.peerCount() == 1, 3000));
        ta.setPeer(2, new InetSocketAddress("127.0.0.1", tb.port()));
        tb.setPeer(1, new InetSocketAddress("127.0.0.1", ta.port()));
        byte[] data = KitTests.randomBytes(2_000_000, 11);
        long f0 = net.frames.get();
        long t0 = System.nanoTime();
        long id = a.sendLarge("2", data);
        check("bulk: 2 MB arrives byte-exact over TCP (with key exchange)", waitFor(() -> pb.blobs.containsKey(id) && Arrays.equals(pb.blobs.get(id), data), 30000));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check("bulk: the sender is told it was delivered", waitFor(() -> pa.delivered.contains(id), 10000));
        check("bulk: it really used the bulk path, no fallback", a.bulkSent() == 1 && a.bulkFallbacks() == 0);
        check("bulk: the mesh radio carried only a handful of small frames (" + (net.frames.get() - f0) + ", chunking would need 500+)", net.frames.get() - f0 < 60);
        System.out.println("      (2 MB in " + ms + " ms over loopback)");
        check("bulk: received exactly once", pb.texts.get(id).equals("1:#2000000"));
        // below the threshold the normal path is used
        long small = a.sendLarge("2", KitTests.randomBytes(8000, 3));
        check("bulk: a small payload still goes through the chunk path", waitFor(() -> pb.blobs.containsKey(small), 20000) && a.bulkSent() == 1);
        KitTests.stop(new KitTests.Peer[0]);
        a.stop(); b.stop();
    }

    static void failureFallsBack() {
        MemNet net = new MemNet();
        KitTests.Probe pa = new KitTests.Probe(), pb = new KitTests.Probe();
        FakeBulk fa = new FakeBulk(1), fb = new FakeBulk(2);
        fa.peers.put(2, fb); fb.peers.put(1, fa);
        MeshClient a = client(net, pa, 1, fa, 16 * 1024), b = client(net, pb, 2, fb, 16 * 1024);
        net.link(1, 2);
        waitFor(() -> a.peerCount() == 1 && b.peerCount() == 1, 3000);
        fa.fail = true;
        byte[] data = KitTests.randomBytes(60_000, 5);
        long id = a.sendLarge("2", data);
        check("bulk: when the fast link fails the chunked path delivers anyway", waitFor(() -> pb.blobs.containsKey(id) && Arrays.equals(pb.blobs.get(id), data), 40000));
        check("bulk: the fallback is counted", a.bulkFallbacks() == 1 && fa.sent.get() == 1);
        check("bulk: and the sender is told it was delivered", waitFor(() -> pa.delivered.contains(id), 20000));
        a.stop(); b.stop();
    }

    static void notEncryptedNotAccepted() {
        MemNet net = new MemNet();
        KitTests.Probe pa = new KitTests.Probe(), pb = new KitTests.Probe();
        FakeBulk fa = new FakeBulk(1), fb = new FakeBulk(2);
        fa.peers.put(2, fb); fb.peers.put(1, fa);
        MeshClient a = client(net, pa, 1, fa, 16 * 1024), b = client(net, pb, 2, fb, 16 * 1024);
        net.link(1, 2);
        waitFor(() -> a.peerCount() == 1 && b.peerCount() == 1, 3000);
        byte[] data = new byte[40_000];
        byte[] marker = "TOP-SECRET-MARKER".getBytes();
        for (int i = 0; i + marker.length < data.length; i += 1000) System.arraycopy(marker, 0, data, i, marker.length);
        long id = a.sendLarge("2", data);
        check("bulk: delivered", waitFor(() -> pb.blobs.containsKey(id), 30000));
        String wire = new String(fa.lastWire, java.nio.charset.StandardCharsets.ISO_8859_1);
        check("bulk: the bytes on the fast link are end-to-end encrypted (no plaintext)", !wire.contains("TOP-SECRET-MARKER"));
        check("bulk: and no bigger than the data plus a small header", fa.lastWire.length < data.length + 200);
        fa.corrupt = true;
        long bad = a.sendLarge("2", KitTests.randomBytes(40_000, 9));
        KitTests.sleep(1500);
        check("bulk: a tampered payload is dropped by the receiver at first", !pb.blobs.containsKey(bad));
        fa.corrupt = false;
        check("bulk: no receipt, so the sender sends it again through the mesh and it arrives intact",
                waitFor(() -> pb.blobs.containsKey(bad) && pb.blobs.get(bad).length == 40_000, 60000) && a.bulkFallbacks() >= 1);
        // wrong sender claim: B receives wire pretending to come from an unknown node 77
        fb.rx.onReceived(77, fa.lastWire);
        KitTests.sleep(300);
        check("bulk: a payload attributed to the wrong sender is dropped", pb.blobs.size() == 2);
        a.stop(); b.stop();
    }

    static void onlyDirectNeighbours() {
        MemNet net = new MemNet();
        KitTests.Probe pa = new KitTests.Probe(), pb = new KitTests.Probe(), pc = new KitTests.Probe();
        FakeBulk fa = new FakeBulk(1), fb = new FakeBulk(2), fc = new FakeBulk(3);
        fa.peers.put(2, fb); fb.peers.put(1, fa);                         // no bulk path A-C
        MeshClient a = client(net, pa, 1, fa, 16 * 1024), b = client(net, pb, 2, fb, 16 * 1024), c = client(net, pc, 3, fc, 16 * 1024);
        net.link(1, 2); net.link(2, 3);
        waitFor(() -> a.peerCount() == 1 && b.peerCount() == 2, 3000);
        byte[] data = KitTests.randomBytes(40_000, 6);
        long id = a.sendLarge("3", data);
        check("bulk: two hops (no direct bulk path) uses the mesh chunks", waitFor(() -> pc.blobs.containsKey(id) && Arrays.equals(pc.blobs.get(id), data), 60000));
        check("bulk: nothing was sent over the bulk link", fa.sent.get() == 0 && a.bulkSent() == 0);
        a.stop(); b.stop(); c.stop();
    }

    static void tcpRefusesGarbage() throws Exception {
        TcpBulk t = new TcpBulk(5, 0);
        final AtomicInteger got = new AtomicInteger();
        t.setReceiver((from, d) -> got.incrementAndGet());
        t.start();
        try {
            for (int variant = 0; variant < 3; variant++) {
                try (java.net.Socket s = new java.net.Socket("127.0.0.1", t.port())) {
                    java.io.DataOutputStream o = new java.io.DataOutputStream(s.getOutputStream());
                    if (variant == 0) { o.writeInt(0xDEADBEEF); o.writeInt(1); o.writeInt(10); o.write(new byte[10]); }
                    else if (variant == 1) { o.writeInt(TcpBulk.MAGIC); o.writeInt(1); o.writeInt(Integer.MAX_VALUE); }
                    else { o.writeInt(TcpBulk.MAGIC); o.writeInt(1); o.writeInt(-5); }
                    o.flush();
                    s.setSoTimeout(1500);
                    int r; try { r = s.getInputStream().read(); } catch (java.io.IOException e) { r = -1; }
                    check("bulk: garbage variant " + variant + " is refused without an ack", r != 1);
                }
            }
            KitTests.sleep(200);
            check("bulk: nothing garbage reached the receiver", got.get() == 0);
            final boolean[] ok = {true};
            t.send(99, new byte[10], okv -> ok[0] = okv);
            check("bulk: sending to an unknown peer fails fast", !ok[0]);
        } finally { t.stop(); }
    }

    static void compositeAndFollow() throws Exception {
        // composite: the first link fails, the second one carries it
        FakeBulk bad = new FakeBulk(1), good = new FakeBulk(1), peer = new FakeBulk(2);
        bad.peers.put(2, peer); good.peers.put(2, peer); bad.fail = true;
        CompositeBulk comp = new CompositeBulk(bad, good);
        final byte[][] got = {null};
        peer.setReceiver((from, d) -> got[0] = d);
        final boolean[] done = {false};
        comp.send(2, new byte[]{1, 2, 3}, ok -> done[0] = ok);
        check("composite: when the first link fails the second carries it", done[0] && got[0] != null && got[0].length == 3 && bad.sent.get() == 1 && good.sent.get() == 1);
        check("composite: no path anywhere -> canSend is false", !comp.canSend(9));
        // follow(): TcpBulk finds neighbours through a TcpRadio's links
        TcpRadio ra = new TcpRadio(1, 0), rb = new TcpRadio(2, 0);
        TcpBulk ba = new TcpBulk(1, 0), bb = new TcpBulk(2, 0);
        ra.setHandler(new RawRadio.Handler() { public void onLinkUp(int n) { } public void onLinkDown(int n) { } public void onRaw(int n, byte[] d) { } });
        rb.setHandler(new RawRadio.Handler() { public void onLinkUp(int n) { } public void onLinkDown(int n) { } public void onRaw(int n, byte[] d) { } });
        ra.start(); rb.start(); ba.start(); bb.start();
        try {
            ba.follow(ra, bb.port());
            ra.connect(new InetSocketAddress("127.0.0.1", rb.port()));
            check("follow: a live TCP link makes the neighbour reachable for bulk", waitFor(() -> ba.canSend(2), 8000));
            final byte[][] rx = {null};
            bb.setReceiver((from, d) -> rx[0] = d);
            final boolean[] ok = {false};
            byte[] payload = KitTests.randomBytes(300_000, 2);
            ba.send(2, payload, k -> ok[0] = k);
            check("follow: the bytes arrive over the bulk socket", waitFor(() -> ok[0] && rx[0] != null && Arrays.equals(rx[0], payload), 10000));
        } finally { ra.stop(); rb.stop(); ba.stop(); bb.stop(); }
    }

    public static void main(String[] args) throws Exception {
        compositeAndFollow();
        ackMs = 3000;
        tcpLoopback();
        failureFallsBack();
        notEncryptedNotAccepted();
        onlyDirectNeighbours();
        tcpRefusesGarbage();
        KitTests.finish();
    }
}
