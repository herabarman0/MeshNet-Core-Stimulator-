package com.herabarman.meshnet.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

/** Tiny deterministic test harness (no JUnit): a virtual clock, a lossy in-memory radio network, a check() counter. */
final class TestKit {
    private TestKit() {}

    static int passed, failed;

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("PASS  " + what); }
        else { failed++; System.out.println("FAIL  " + what); }
    }

    static void info(String s) { System.out.println("INFO  " + s); }

    static void summary() {
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static final class Clk implements Clock {
        private long now;
        private long seq;
        private final PriorityQueue<long[]> q = new PriorityQueue<>((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        private final Map<Long, Runnable> tasks = new HashMap<>();
        @Override public long nowMs() { return now; }
        @Override public void schedule(long delayMs, Runnable task) {
            long id = ++seq;
            tasks.put(id, task);
            q.add(new long[]{now + Math.max(0, delayMs), id});
        }
        void run(long ms) {
            long end = now + ms;
            while (!q.isEmpty() && q.peek()[0] <= end) {
                long[] e = q.poll();
                now = Math.max(now, e[0]);
                Runnable r = tasks.remove(e[1]);
                if (r != null) r.run();
            }
            now = end;
        }
    }

    /** Hand-wired radio network of MeshNodes. */
    static final class Net {
        final Clk clock = new Clk();
        final Random rng;
        final Map<Integer, MeshNode> nodes = new HashMap<>();
        final Map<Integer, NodeInfo> infos = new HashMap<>();
        final Map<Integer, Set<Integer>> links = new HashMap<>();
        final Map<Long, Double> loss = new HashMap<>();
        final Map<Integer, Integer> txData = new HashMap<>();     // DATA packets sent per node
        final Map<Integer, Integer> txAll = new HashMap<>();
        final Map<Integer, Integer> zoneOf = new HashMap<>();
        long latency = 20;
        final MeshConfig cfg;
        final boolean discovery;
        final Map<Integer, List<byte[]>> got = new HashMap<>();    // delivered payloads per node
        int retries, dropped;
        final Map<String, Integer> dropReasons = new HashMap<>();

        Net(MeshConfig cfg, long seed) {
            this.cfg = cfg;
            this.rng = new Random(seed);
            this.discovery = cfg.discovery;
        }

        MeshNode add(final int id, int zone, double battery) {
            NodeInfo info = new NodeInfo(id, zone, battery, 1.0, false);
            infos.put(id, info);
            zoneOf.put(id, zone);
            links.put(id, new HashSet<Integer>());
            Transport t = new Transport() {
                @Override public void send(int peer, byte[] data) { transmit(id, peer, data); }
                @Override public void setListener(Listener l) { }
            };
            ZoneRouter.ZoneMap zm = discovery ? null : new ZoneRouter.ZoneMap() {
                @Override public int zoneOf(int n) { return Net.this.zoneOf.containsKey(n) ? Net.this.zoneOf.get(n) : 0; }
            };
            MeshNode n = new MeshNode(info, cfg, t, clock, zm, new NoopCrypto(), null, new Random(rng.nextLong()));
            n.setListener(new MeshNode.Listener() {
                @Override public void onDelivered(MeshNode node, Packet p, byte[] plain, int fromPeer) {
                    List<byte[]> l = got.get(id);
                    if (l == null) { l = new ArrayList<>(); got.put(id, l); }
                    l.add(plain);
                }
                @Override public void onDuplicate(MeshNode node, Packet p) { }
                @Override public void onDropped(MeshNode node, Packet p, String reason) {
                    dropped++;
                    Integer c = dropReasons.get(reason);
                    dropReasons.put(reason, c == null ? 1 : c + 1);
                }
                @Override public void onRetry(MeshNode node, Packet p) { retries++; }
            });
            nodes.put(id, n);
            return n;
        }

        static long key(int a, int b) { return ((long) Math.min(a, b) << 32) | (Math.max(a, b) & 0xFFFFFFFFL); }

        void link(int a, int b) {
            links.get(a).add(b);
            links.get(b).add(a);
            nodes.get(a).onPeerUp(b);
            nodes.get(b).onPeerUp(a);
        }

        void unlink(int a, int b) {
            links.get(a).remove(b);
            links.get(b).remove(a);
            nodes.get(a).onPeerDown(b);
            nodes.get(b).onPeerDown(a);
        }

        /** Silent break: the link stops carrying packets but nobody is told (a phone that walked out of range unnoticed). */
        void breakSilently(int a, int b) {
            links.get(a).remove(b);
            links.get(b).remove(a);
        }

        void setLoss(int a, int b, double p) { loss.put(key(a, b), p); }

        void transmit(final int from, final int to, final byte[] data) {
            Integer c = txAll.get(from);
            txAll.put(from, c == null ? 1 : c + 1);
            if ((data[1] & 0x0F) == Packet.TYPE_DATA) {
                Integer d = txData.get(from);
                txData.put(from, d == null ? 1 : d + 1);
            }
            if (!links.get(from).contains(to)) return;
            Double p = loss.get(key(from, to));
            if (p != null && rng.nextDouble() < p) return;
            clock.schedule(latency + rng.nextInt(5), new Runnable() {
                @Override public void run() {
                    if (links.get(from).contains(to)) nodes.get(to).onReceive(from, data);
                }
            });
        }

        int delivered(int node) { List<byte[]> l = got.get(node); return l == null ? 0 : l.size(); }
        int dataTx(int node) { Integer d = txData.get(node); return d == null ? 0 : d; }
    }
}
