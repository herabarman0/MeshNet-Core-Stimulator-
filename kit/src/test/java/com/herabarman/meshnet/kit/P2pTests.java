package com.herabarman.meshnet.kit;

import static com.herabarman.meshnet.kit.KitTests.check;
import static com.herabarman.meshnet.kit.KitTests.waitFor;

import com.herabarman.meshnet.core.Clock;
import com.herabarman.meshnet.core.SystemClock;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * NAT traversal tests. The matrix part runs two P2pManagers behind a model of every kind of NAT on a virtual clock,
 * so the logic is tried against full cone, address-restricted, port-restricted and symmetric NATs without a
 * network. The loopback part runs the same code over real UDP sockets. What no test here can show is how a real
 * carrier's NAT behaves.
 */
public final class P2pTests {
    // ---- a virtual clock ------------------------------------------------------------------------------------------------

    static final class VirtualClock implements Clock {
        private long now;
        private long seq;
        private final PriorityQueue<long[]> q = new PriorityQueue<>((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        private final Map<Long, Runnable> tasks = new HashMap<>();

        @Override public long nowMs() { return now; }
        @Override public void schedule(long delayMs, Runnable task) {
            long id = seq++;
            tasks.put(id, task);
            q.add(new long[]{now + Math.max(0, delayMs), id});
        }
        void runFor(long ms) {
            long end = now + ms;
            while (!q.isEmpty() && q.peek()[0] <= end) {
                long[] e = q.poll();
                now = e[0];
                tasks.remove(e[1]).run();
            }
            now = end;
        }
    }

    // ---- a model of the internet with NATs ------------------------------------------------------------------------------

    enum Nat { OPEN, FULL_CONE, ADDR_RESTRICTED, PORT_RESTRICTED, SYMMETRIC }

    static final class SimInternet {
        final VirtualClock clock;
        final long latencyMs = 20;
        final long natIdleMs = 30_000;                         // a NAT forgets a mapping nobody sent through for this long
        final Random rng = new Random(3);
        double loss = 0;
        int nextPort = 40_000;
        final Map<String, Endpoint> byPublic = new HashMap<>();
        final Map<String, String> restrictTo = new HashMap<>();      // symmetric: public "ip:port" -> the one destination allowed in
        final List<InetSocketAddress> stunServers = new ArrayList<>();
        long datagrams;

        SimInternet(VirtualClock clock) { this.clock = clock; }

        InetSocketAddress stun(String ip, int port) {
            InetSocketAddress a = new InetSocketAddress(ip, port);
            stunServers.add(a);
            return a;
        }

        static String key(InetSocketAddress a) { return a.getAddress().getHostAddress() + ":" + a.getPort(); }

        Endpoint endpoint(Nat nat, String publicIp, String privateIp) { return new Endpoint(nat, publicIp, privateIp); }

        final class Endpoint implements UdpPort {
            final Nat nat;
            final String publicIp;
            final String privateIp;
            final int coneOrOpenPort;
            final Map<String, Long> sentDst = new HashMap<>();   // "ip:port" -> last outbound time
            final Map<String, Long> sentIp = new HashMap<>();
            final Map<String, Integer> symPort = new HashMap<>();
            long lastAnyOut = -1;
            Receiver receiver;
            boolean closed;

            Endpoint(Nat nat, String publicIp, String privateIp) {
                this.nat = nat;
                this.publicIp = publicIp;
                this.privateIp = privateIp;
                this.coneOrOpenPort = nextPort++;
                if (nat != Nat.SYMMETRIC) byPublic.put(publicIp + ":" + coneOrOpenPort, this);
            }

            @Override public void setReceiver(Receiver r) { receiver = r; }

            @Override public List<InetSocketAddress> localCandidates() {
                return nat == Nat.OPEN ? Collections.<InetSocketAddress>emptyList()
                        : Collections.singletonList(new InetSocketAddress(privateIp, coneOrOpenPort));
            }

            @Override public void close() { closed = true; }

            @Override
            public void send(final InetSocketAddress to, final byte[] data) {
                if (closed) return;
                long now = clock.nowMs();
                String dst = key(to);
                int pub = coneOrOpenPort;
                if (nat == Nat.SYMMETRIC) {
                    Integer p = symPort.get(dst);
                    if (p == null) {
                        p = nextPort++;
                        symPort.put(dst, p);
                        byPublic.put(publicIp + ":" + p, this);
                        restrictTo.put(publicIp + ":" + p, dst);
                    }
                    pub = p;
                }
                sentDst.put(dst, now);
                sentIp.put(to.getAddress().getHostAddress(), now);
                lastAnyOut = now;
                final InetSocketAddress from = new InetSocketAddress(publicIp, pub);
                clock.schedule(latencyMs, new Runnable() { @Override public void run() { route(from, to, data); } });
            }

            boolean fresh(Long t) { return t != null && clock.nowMs() - t <= natIdleMs; }

            void inbound(InetSocketAddress src, String viaPublic, byte[] data) {
                if (closed || receiver == null) return;
                boolean ok;
                switch (nat) {
                    case OPEN: ok = true; break;
                    case FULL_CONE: ok = fresh(lastAnyOut); break;
                    case ADDR_RESTRICTED: ok = fresh(sentIp.get(src.getAddress().getHostAddress())); break;
                    case PORT_RESTRICTED: ok = fresh(sentDst.get(key(src))); break;
                    default: ok = key(src).equals(restrictTo.get(viaPublic)) && fresh(sentDst.get(key(src))); break;
                }
                if (ok) receiver.onDatagram(src, data);
            }
        }

        void route(InetSocketAddress src, InetSocketAddress dst, byte[] data) {
            datagrams++;
            if (loss > 0 && rng.nextDouble() < loss) return;
            for (InetSocketAddress s : stunServers) {
                if (s.equals(dst)) {                                       // a STUN server: say what it saw
                    byte[] tx = StunClient.transactionId(data);
                    if (tx == null) return;
                    final byte[] resp = StunClient.bindingResponse(tx, src);
                    final InetSocketAddress back = src;
                    final InetSocketAddress me = s;
                    clock.schedule(latencyMs, new Runnable() { @Override public void run() { route(me, back, resp); } });
                    return;
                }
            }
            Endpoint e = byPublic.get(key(dst));
            if (e != null) e.inbound(src, key(dst), data);
        }
    }

    // ---- STUN -----------------------------------------------------------------------------------------------------------

    static void stun() {
        byte[] tx = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        byte[] req = StunClient.bindingRequest(tx);
        check("stun: a binding request is 20 bytes with the magic cookie", req.length == 20 && StunClient.looksLikeStun(req) && req[0] == 0 && req[1] == 1);
        InetSocketAddress seen = new InetSocketAddress("192.0.2.1", 32853);               // the address used in RFC 5769's examples
        byte[] resp = StunClient.bindingResponse(tx, seen);
        // RFC 5769: XOR-MAPPED-ADDRESS of 192.0.2.1:32853 is family 01, port a147, address e112a643
        boolean bytesMatch = (resp[24] & 0xFF) == 0x00 && (resp[25] & 0xFF) == 0x01 && (resp[26] & 0xFF) == 0xA1 && (resp[27] & 0xFF) == 0x47
                && (resp[28] & 0xFF) == 0xE1 && (resp[29] & 0xFF) == 0x12 && (resp[30] & 0xFF) == 0xA6 && (resp[31] & 0xFF) == 0x43;
        check("stun: the XOR of port and address matches the RFC 5769 example", bytesMatch);
        check("stun: a response is read back to the same address and port", seen.equals(StunClient.parseBindingResponse(resp, tx)));
        byte[] other = tx.clone();
        other[0] = 9;
        check("stun: an answer for another transaction is refused", StunClient.parseBindingResponse(resp, other) == null);
        check("stun: a truncated answer is refused", StunClient.parseBindingResponse(Arrays.copyOf(resp, 25), tx) == null);
        check("stun: a packet that is not STUN is not STUN", !StunClient.looksLikeStun(new byte[40]) && !StunClient.looksLikeStun(new byte[]{(byte) 0xA7, 3, 1}));
        // an older server answers with plain MAPPED-ADDRESS (0x0001): family 01, port 8055 -> 32853, address c0 00 02 01
        byte[] plain = new byte[32];
        System.arraycopy(resp, 0, plain, 0, 32);
        plain[20] = 0x00;
        plain[21] = 0x01;
        plain[26] = (byte) 0x80;
        plain[27] = 0x55;
        plain[28] = (byte) 0xC0;
        plain[29] = 0x00;
        plain[30] = 0x02;
        plain[31] = 0x01;
        check("stun: the older MAPPED-ADDRESS form is read too", seen.equals(StunClient.parseBindingResponse(plain, tx)));
    }

    // ---- the NAT matrix -------------------------------------------------------------------------------------------------

    static final class Pair {
        final VirtualClock clock = new VirtualClock();
        final SimInternet net = new SimInternet(clock);
        final List<byte[]> gotA = new CopyOnWriteArrayList<>(), gotB = new CopyOnWriteArrayList<>();
        P2pManager ma, mb;

        Pair(Nat a, Nat b) { this(a, b, 15_000, 45_000); }

        Pair(Nat a, Nat b, long pingMs, long deadMs) {
            InetSocketAddress s1 = net.stun("192.0.2.10", 3478), s2 = net.stun("192.0.2.11", 3478);
            SimInternet.Endpoint pa = net.endpoint(a, "203.0.113.1", "192.168.1.10");
            SimInternet.Endpoint pb = net.endpoint(b, "198.51.100.2", "10.0.0.7");
            ma = new P2pManager(11, pa, clock, new Random(1), Arrays.asList(s1, s2), (to, payload) -> deliverSignal(to, payload), gotA::add);
            mb = new P2pManager(12, pb, clock, new Random(2), Arrays.asList(s1, s2), (to, payload) -> deliverSignal(to, payload), gotB::add);
            for (P2pManager m : new P2pManager[]{ma, mb}) { m.pingIntervalMs = pingMs; m.deadAfterMs = deadMs; }
            ma.start();
            mb.start();
        }

        void deliverSignal(final int to, final byte[] payload) {          // the server: a relayed packet, a little late
            clock.schedule(30, new Runnable() {
                @Override public void run() { (to == 12 ? mb : ma).onSignal(payload); }
            });
        }
    }

    static boolean expectedToWork(Nat a, Nat b) {
        if (a == Nat.OPEN || b == Nat.OPEN || a == Nat.FULL_CONE || b == Nat.FULL_CONE) return true;
        if (a == Nat.SYMMETRIC && b == Nat.SYMMETRIC) return false;
        if (a == Nat.SYMMETRIC) return b == Nat.ADDR_RESTRICTED;
        if (b == Nat.SYMMETRIC) return a == Nat.ADDR_RESTRICTED;
        return true;                                                      // address- or port-restricted on both sides
    }

    static void matrix() {
        int wrong = 0, pairs = 0;
        StringBuilder report = new StringBuilder();
        for (Nat a : Nat.values()) {
            for (Nat b : Nat.values()) {
                Pair p = new Pair(a, b);
                p.clock.runFor(3_000);                                    // STUN answers are in
                p.ma.ensure(12);
                p.clock.runFor(12_000);
                boolean open = p.ma.isOpen(12) && p.mb.isOpen(11);
                pairs++;
                if (open != expectedToWork(a, b)) {
                    wrong++;
                    report.append(' ').append(a).append('/').append(b).append(open ? " opened" : " failed");
                }
                if (open) {
                    byte[] big = new byte[4000];
                    new Random(5).nextBytes(big);
                    boolean sentAB = p.ma.send(12, big), sentBA = p.mb.send(11, new byte[]{1, 2, 3});
                    p.clock.runFor(500);
                    boolean ok = sentAB && sentBA && p.gotB.size() == 1 && Arrays.equals(p.gotB.get(0), big) && p.gotA.size() == 1 && Arrays.equals(p.gotA.get(0), new byte[]{1, 2, 3});
                    if (!ok) { wrong++; report.append(' ').append(a).append('/').append(b).append(" data"); }
                } else {
                    if (p.ma.send(12, new byte[]{1}) || p.mb.send(11, new byte[]{1})) { wrong++; report.append(' ').append(a).append('/').append(b).append(" sent without a path"); }
                }
            }
        }
        check("nat: all " + pairs + " NAT pairs behave as expected (open path exactly where punching can work)" + report, wrong == 0);
    }

    static void natTypeIsMeasured() {
        for (Nat n : Nat.values()) {
            Pair p = new Pair(n, Nat.FULL_CONE);
            p.clock.runFor(3_000);
            P2pManager.NatType t = p.ma.natType();
            boolean ok = n == Nat.SYMMETRIC ? t == P2pManager.NatType.SYMMETRIC : t == P2pManager.NatType.CONE;
            check("nat: a " + n + " NAT is measured as " + t, ok);
        }
        Pair p = new Pair(Nat.PORT_RESTRICTED, Nat.PORT_RESTRICTED);
        p.clock.runFor(3_000);
        check("nat: the public address STUN reports is the NAT's, not the private one", p.ma.publicCandidates().size() == 1
                && p.ma.publicCandidates().get(0).getAddress().getHostAddress().equals("203.0.113.1"));
    }

    static void failureFallsBackAndBacksOff() {
        Pair p = new Pair(Nat.SYMMETRIC, Nat.SYMMETRIC);
        p.clock.runFor(3_000);
        p.ma.ensure(12);
        p.clock.runFor(10_000);
        check("nat: two symmetric NATs: the attempt fails, nothing breaks", p.ma.stateOf(12) == P2pManager.State.FAILED && !p.ma.send(12, new byte[]{1}));
        p.ma.ensure(12);
        check("nat: it does not retry at once", p.ma.stateOf(12) == P2pManager.State.FAILED);
        p.clock.runFor(61_000);
        p.ma.ensure(12);
        check("nat: it tries again after the wait", p.ma.stateOf(12) == P2pManager.State.PUNCHING);
        p.clock.runFor(10_000);
        long t0 = p.clock.nowMs();
        p.ma.ensure(12);
        p.clock.runFor(61_000);
        p.ma.ensure(12);
        check("nat: the wait doubles after a second failure (still waiting at 61 s)", p.ma.stateOf(12) == P2pManager.State.FAILED);
        p.clock.runFor(70_000);
        p.ma.ensure(12);
        check("nat: ...and the next try comes after about 2 minutes", p.ma.stateOf(12) == P2pManager.State.PUNCHING);
        check("nat: failed paths are counted", p.ma.pathsFailed() >= 2 && p.ma.pathsOpened() == 0 && t0 > 0);
    }

    static void keepAliveHoldsThePathOpen() {
        Pair p = new Pair(Nat.PORT_RESTRICTED, Nat.PORT_RESTRICTED);               // NATs forget idle mappings after 30 s
        p.clock.runFor(3_000);
        p.ma.ensure(12);
        p.clock.runFor(8_000);
        check("nat: path is open", p.ma.isOpen(12));
        p.clock.runFor(200_000);
        check("nat: still open after 200 s of silence (pings keep the NAT mappings alive)", p.ma.isOpen(12) && p.mb.isOpen(11));
        check("nat: and it still carries data", p.ma.send(12, new byte[]{7}) && waitClock(p, 300) && p.gotB.size() == 1);

        Pair q = new Pair(Nat.PORT_RESTRICTED, Nat.PORT_RESTRICTED, 600_000, 45_000);   // no pings
        q.clock.runFor(3_000);
        q.ma.ensure(12);
        q.clock.runFor(8_000);
        q.clock.runFor(60_000);
        check("nat: without pings the NATs close the path, and the manager notices and gives it up", !q.ma.isOpen(12) && q.ma.stateOf(12) == P2pManager.State.FAILED);
    }

    static boolean waitClock(Pair p, long ms) { p.clock.runFor(ms); return true; }

    static void strangersAreIgnored() {
        Pair p = new Pair(Nat.OPEN, Nat.OPEN);
        p.clock.runFor(3_000);
        p.ma.ensure(12);
        p.clock.runFor(5_000);
        check("security: path is open", p.ma.isOpen(12));
        SimInternet.Endpoint evil = p.net.endpoint(Nat.OPEN, "192.0.2.66", "10.9.9.9");
        byte[] forged = new byte[2 + 8 + 4 + 5];
        forged[0] = P2pManager.MAGIC;
        forged[1] = P2pManager.T_DATA;
        new Random(8).nextBytes(forged);
        forged[0] = P2pManager.MAGIC;
        forged[1] = P2pManager.T_DATA;
        evil.send(new InetSocketAddress("203.0.113.1", p.net.nextPort - 3), forged);        // some port; the real one is found below
        for (int port = 40_000; port < p.net.nextPort; port++) evil.send(new InetSocketAddress("203.0.113.1", port), forged);
        for (int port = 40_000; port < p.net.nextPort; port++) evil.send(new InetSocketAddress("198.51.100.2", port), forged);
        p.clock.runFor(500);
        check("security: a datagram with the wrong session id is dropped, on both sides", p.gotA.isEmpty() && p.gotB.isEmpty());
        check("security: and the real path is untouched", p.ma.isOpen(12) && p.ma.send(12, new byte[]{5}));
    }

    // ---- real sockets ---------------------------------------------------------------------------------------------------

    static void realUdpOnLoopback() throws Exception {
        final JavaUdpPort pa = new JavaUdpPort(0, true), pb = new JavaUdpPort(0, true);
        SystemClock clock = new SystemClock();
        final List<byte[]> gotA = new CopyOnWriteArrayList<>(), gotB = new CopyOnWriteArrayList<>();
        final P2pManager[] m = new P2pManager[2];
        final SystemClock c = clock;
        m[0] = new P2pManager(21, pa, clock, new Random(), Collections.<InetSocketAddress>emptyList(),
                (to, payload) -> c.schedule(20, () -> m[1].onSignal(payload)), gotA::add);
        m[1] = new P2pManager(22, pb, clock, new Random(), Collections.<InetSocketAddress>emptyList(),
                (to, payload) -> c.schedule(20, () -> m[0].onSignal(payload)), gotB::add);
        m[0].start();
        m[1].start();
        m[0].ensure(22);
        check("udp: two nodes make a direct path over real sockets", waitFor(() -> m[0].isOpen(22) && m[1].isOpen(21), 5000));
        byte[] big = new byte[5000];
        new Random(4).nextBytes(big);
        check("udp: a 5000 byte packet is split, sent and put back together intact", m[0].send(22, big) && waitFor(() -> gotB.size() == 1 && Arrays.equals(gotB.get(0), big), 3000));
        check("udp: and the other way", m[1].send(21, new byte[]{9, 8, 7}) && waitFor(() -> gotA.size() == 1 && Arrays.equals(gotA.get(0), new byte[]{9, 8, 7}), 3000));
        JavaUdpPort evil = new JavaUdpPort(0, true);
        byte[] forged = new byte[40];
        new Random(2).nextBytes(forged);
        forged[0] = P2pManager.MAGIC;
        forged[1] = P2pManager.T_DATA;
        evil.send(new InetSocketAddress("127.0.0.1", pb.localPort()), forged);
        evil.send(new InetSocketAddress("127.0.0.1", pa.localPort()), forged);
        Thread.sleep(300);
        check("udp: a forged datagram from a stranger is dropped", gotA.size() == 1 && gotB.size() == 1);
        evil.close();
        m[0].stop();
        m[1].stop();
        clock.shutdown();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== NAT traversal (STUN, hole punching) ==");
        stun();
        natTypeIsMeasured();
        matrix();
        failureFallsBackAndBacksOff();
        keepAliveHoldsThePathOpen();
        strangersAreIgnored();
        realUdpOnLoopback();
        System.out.println("\n" + KitTests.pass + " passed, " + KitTests.fail + " failed");
        System.exit(KitTests.fail == 0 ? 0 : 1);
    }
}
