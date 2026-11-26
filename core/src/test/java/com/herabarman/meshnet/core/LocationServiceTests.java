package com.herabarman.meshnet.core;

import static com.herabarman.meshnet.core.TestKit.check;
import static com.herabarman.meshnet.core.TestKit.info;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Gossip directory and Kademlia DHT on an in-memory network. Plain main(), no JUnit. */
public final class LocationServiceTests {
    public static void main(String[] args) {
        gossip();
        dht();
        TestKit.summary();
    }

    // ---- gossip ------------------------------------------------------------------------------

    static final class GNet {
        final TestKit.Clk clock = new TestKit.Clk();
        final Random rng;
        final int n;
        final List<Set<Integer>> adj = new ArrayList<>();
        final GossipDirectory[] g;
        double loss;
        long messages;

        GNet(int n, int chords, long seed, GossipDirectory.Config cfg) {
            this.n = n;
            this.rng = new Random(seed);
            for (int i = 0; i < n; i++) adj.add(new HashSet<Integer>());
            for (int i = 0; i < n; i++) connect(i, (i + 1) % n);
            for (int c = 0; c < chords; c++) connect(rng.nextInt(n), rng.nextInt(n));
            g = new GossipDirectory[n];
            for (int i = 0; i < n; i++) {
                final int me = i;
                g[i] = new GossipDirectory(i, cfg, clock, new Random(seed * 1000 + i), new GossipDirectory.Link() {
                    @Override public void send(final int peer, final byte[] m) {
                        messages++;
                        if (loss > 0 && rng.nextDouble() < loss) return;
                        clock.schedule(30, new Runnable() { @Override public void run() { g[peer].onMessage(me, m); } });
                    }
                    @Override public List<Integer> peers() { return new ArrayList<>(adj.get(me)); }
                });
            }
        }

        void connect(int a, int b) { if (a != b) { adj.get(a).add(b); adj.get(b).add(a); } }

        int[] hopsFrom(int src) {
            int[] d = new int[n];
            java.util.Arrays.fill(d, -1);
            java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
            d[src] = 0;
            q.add(src);
            while (!q.isEmpty()) {
                int x = q.poll();
                for (int y : adj.get(x)) if (d[y] < 0) { d[y] = d[x] + 1; q.add(y); }
            }
            return d;
        }
    }

    static void gossip() {
        GossipDirectory.Config cfg = new GossipDirectory.Config();
        cfg.defaultRadius = 3;
        GNet net = new GNet(80, 30, 7, cfg);
        for (int i = 0; i < net.n; i++) { net.g[i].start(); net.g[i].publishLocation(i / 10); }
        net.clock.run(90_000);
        int inRadius = 0, known = 0, outside = 0;
        for (int a = 0; a < net.n; a++) {
            int[] d = net.hopsFrom(a);
            for (int b = 0; b < net.n; b++) {
                if (a == b) continue;
                boolean has = net.g[a].zoneOf(b) == b / 10;
                if (d[b] <= cfg.defaultRadius) { inRadius++; if (has) known++; }
                else if (has) outside++;
            }
        }
        info(String.format("gossip, 80 nodes, radius 3: %d of %d in-radius locations known (%.1f%%), %d beyond the radius, %d messages total",
                known, inRadius, 100.0 * known / inRadius, outside, net.messages));
        check("gossip spreads each location to (almost) every node within its radius", known >= 0.97 * inRadius);
        check("gossip never carries a record beyond its radius", outside == 0);
        int maxSize = 0;
        for (GossipDirectory d : net.g) maxSize = Math.max(maxSize, d.size());
        check("no node needs the whole table (largest table " + maxSize + " of 80)", maxSize < 80);

        // a node moves: the new zone replaces the old one around it
        net.g[5].publishLocation(77);
        net.clock.run(90_000);
        int[] d5 = net.hopsFrom(5);
        int near = 0, updated = 0;
        for (int a = 0; a < net.n; a++) if (a != 5 && d5[a] <= cfg.defaultRadius) { near++; if (net.g[a].zoneOf(5) == 77) updated++; }
        check("a changed location (higher version) replaces the old one everywhere in range (" + updated + "/" + near + ")", updated >= 0.97 * near);

        // gateway directory
        net.g[1].publishGateway(7, 100);
        net.g[2].publishGateway(7, 200);
        net.g[3].publishGateway(7, 150);
        net.clock.run(45_000);
        int[] d2 = net.hopsFrom(2);
        int probe = -1;
        for (int a = 0; a < net.n; a++) if (a != 1 && a != 2 && a != 3 && net.hopsFrom(a)[1] <= 3 && net.hopsFrom(a)[2] <= 3 && net.hopsFrom(a)[3] <= 3) { probe = a; break; }
        List<GossipDirectory.Record> gws = probe < 0 ? new ArrayList<GossipDirectory.Record>() : net.g[probe].gatewaysOf(7);
        check("a node learns the gateways of a zone from gossip, best first", probe >= 0 && gws.size() == 3 && gws.get(0).owner == 2 && gws.get(1).owner == 3 && gws.get(2).owner == 1);

        // loss: pushes get lost, digests repair
        GossipDirectory.Config lc = new GossipDirectory.Config();
        lc.defaultRadius = 3;
        GNet lossy = new GNet(80, 30, 9, lc);
        lossy.loss = 0.4;
        for (int i = 0; i < lossy.n; i++) { lossy.g[i].start(); lossy.g[i].publishLocation(i / 10); }
        lossy.clock.run(300_000);
        int in2 = 0, kn2 = 0;
        for (int a = 0; a < lossy.n; a++) {
            int[] d = lossy.hopsFrom(a);
            for (int b = 0; b < lossy.n; b++) if (a != b && d[b] <= lc.defaultRadius) { in2++; if (lossy.g[a].zoneOf(b) == b / 10) kn2++; }
        }
        info(String.format("with 40%% of all messages lost: %.1f%% of in-radius locations known", 100.0 * kn2 / in2));
        check("40% message loss: gossip still converges (>= 90%)", kn2 >= 0.9 * in2);

        // expiry, version rule, memory cap, garbage
        GossipDirectory.Config ec = new GossipDirectory.Config();
        GNet small = new GNet(6, 0, 3, ec);
        for (int i = 0; i < 6; i++) small.g[i].start();
        small.g[0].publish(GossipDirectory.NS_LOCATION, 0, 4, 20_000, 3);
        small.clock.run(15_000);
        check("a short-lived record reaches the neighbour", small.g[1].zoneOf(0) == 4);
        small.clock.run(40_000);
        check("and disappears once its time is up", small.g[1].zoneOf(0) == -1);

        GossipDirectory.Config cap = new GossipDirectory.Config();
        cap.maxRecords = 20;
        cap.defaultRadius = 6;
        GNet big = new GNet(40, 0, 4, cap);
        for (int i = 0; i < 40; i++) { big.g[i].start(); big.g[i].publishLocation(i); }
        big.clock.run(120_000);
        int worst = 0;
        for (GossipDirectory d : big.g) worst = Math.max(worst, d.size());
        check("table size stays capped (largest " + worst + ", cap 20)", worst <= 21);

        GossipDirectory victim = small.g[2];
        Random fz = new Random(1);
        boolean threw = false;
        try {
            for (int i = 0; i < 2000; i++) { byte[] junk = new byte[fz.nextInt(80)]; fz.nextBytes(junk); if (junk.length > 0) junk[0] = (byte) (1 + fz.nextInt(3)); victim.onMessage(1, junk); }
            victim.onMessage(1, null);
        } catch (RuntimeException e) { threw = true; }
        check("2000 garbage messages do not hurt the engine", !threw);
        GossipDirectory a = new GossipDirectory(1, new GossipDirectory.Config(), small.clock, new Random(1), new GossipDirectory.Link() {
            @Override public void send(int p, byte[] m) { }
            @Override public List<Integer> peers() { return new ArrayList<>(); }
        });
        a.publishLocation(3);
        byte[] fake = new byte[2 + 20];
        fake[0] = 1; fake[1] = 1; fake[2] = 1;                 // PUSH, one record, ns 1
        java.nio.ByteBuffer.wrap(fake).position(3);
        java.nio.ByteBuffer fb = java.nio.ByteBuffer.wrap(fake);
        fb.position(3); fb.putInt(1).putInt(1).putInt(99).putInt(Integer.MAX_VALUE).putShort((short) 1000).put((byte) 2);   // claims to be me, zone 99
        a.onMessage(2, fake);
        check("nobody can overwrite a node's own record by gossip", a.zoneOf(1) == 3);
    }

    // ---- DHT ---------------------------------------------------------------------------------

    static final class DNet {
        final TestKit.Clk clock = new TestKit.Clk();
        final Random rng;
        final int n;
        final DhtNode[] d;
        final boolean[] alive;
        double loss;
        long messages;

        DNet(int n, long seed, final DhtNode.Config cfg) {
            this.n = n;
            this.rng = new Random(seed);
            d = new DhtNode[n];
            alive = new boolean[n];
            for (int i = 0; i < n; i++) {
                final int me = i;
                alive[i] = true;
                d[i] = new DhtNode(i + 1000, i % 50, cfg, clock, new Random(seed * 7919 + i), new DhtNode.Net() {
                    @Override public void send(int nodeId, int zone, final byte[] m) {
                        messages++;
                        final int to = nodeId - 1000;
                        if (to < 0 || to >= DNet.this.n) return;
                        if (loss > 0 && rng.nextDouble() < loss) return;
                        clock.schedule(30 + rng.nextInt(40), new Runnable() {
                            @Override public void run() { if (alive[to] && alive[me]) d[to].onMessage(me + 1000, m); }
                        });
                    }
                });
            }
        }

        void joinAll() {
            for (int i = 1; i < n; i++) {
                final int me = i;
                final int seed = rng.nextInt(i);
                clock.schedule(i * 150L, new Runnable() {
                    @Override public void run() {
                        d[me].bootstrap(seed + 1000, seed % 50);
                        d[me].start();
                    }
                });
            }
            d[0].start();
            clock.run(n * 150L + 40_000);
            for (int i = 0; i < n; i++) d[i].publishLocation();
            clock.run(60_000);
        }

        int[] lookups(int count, boolean onlyAlive, int[] expectedZone) {
            final int[] ok = {0}, wrong = {0};
            final int[] done = {0};
            for (int q = 0; q < count; q++) {
                int from, target;
                do { from = rng.nextInt(n); } while (!alive[from]);
                do { target = rng.nextInt(n); } while (target == from || (onlyAlive && !alive[target]));
                final int t = target;
                final int expect = expectedZone != null ? expectedZone[t] : t % 50;
                d[from].lookupLocation(t + 1000, new DhtNode.LocationCallback() {
                    @Override public void onResult(int id, int zone) {
                        done[0]++;
                        if (zone == expect) ok[0]++; else if (zone >= 0) wrong[0]++;
                    }
                });
                clock.run(200);
            }
            clock.run(30_000);
            return new int[]{ok[0], wrong[0], done[0]};
        }
    }

    static void dht() {
        DhtNode.Config cfg = new DhtNode.Config();
        DNet net = new DNet(300, 5, cfg);
        net.joinAll();
        double avgContacts = 0;
        for (DhtNode x : net.d) avgContacts += x.contactCount();
        avgContacts /= net.n;
        long sent0 = 0;
        for (DhtNode x : net.d) sent0 += x.rpcsSent;
        int[] r = net.lookups(200, true, null);
        long sent1 = 0;
        for (DhtNode x : net.d) sent1 += x.rpcsSent;
        info(String.format("DHT, 300 nodes: each node knows %.0f contacts (not 300); 200 lookups: %d correct, %d wrong, %d answered; %.1f messages per lookup",
                avgContacts, r[0], r[1], r[2], (sent1 - sent0) / 200.0));
        check("every node knows only a slice of the network", avgContacts < 0.5 * net.n);
        check("lookups find the right zone (>= 98%)", r[0] >= 196 && r[1] == 0);
        check("every lookup ends (found or not), none hangs", r[2] == 200);
        check("a lookup costs far less than asking everybody (" + (sent1 - sent0) / 200 + " messages vs 300 nodes)", (sent1 - sent0) / 200.0 < 60);

        // a stranger id that nobody published
        final int[] strangerZone = {Integer.MIN_VALUE};
        net.d[7].lookupLocation(999_999, new DhtNode.LocationCallback() { @Override public void onResult(int id, int zone) { strangerZone[0] = zone; } });
        net.clock.run(30_000);
        check("an unknown node is reported as not found", strangerZone[0] == -1);

        // movement: 30 nodes change zone; lookups must return the NEW zone
        int[] zone = new int[net.n];
        for (int i = 0; i < net.n; i++) zone[i] = i % 50;
        for (int i = 0; i < 30; i++) { int who = 10 + i * 5; zone[who] = 200 + i; net.d[who].setZone(200 + i); }
        net.clock.run(20_000);
        int ok = 0, stale = 0, total = 0;
        for (int i = 0; i < 30; i++) {
            final int who = 10 + i * 5;
            final int from = (who + 111) % net.n;
            final int expect = zone[who];
            net.d[from].lookupLocation(who + 1000, new DhtNode.LocationCallback() {
                @Override public void onResult(int id, int z) { }
            });
        }
        net.clock.run(30_000);
        final int[] cnt = {0, 0};
        for (int i = 0; i < 30; i++) {
            final int who = 10 + i * 5;
            final int from = (who + 111) % net.n;
            final int expect = zone[who];
            net.d[from].lookupLocation(who + 1000, new DhtNode.LocationCallback() {
                @Override public void onResult(int id, int z) { if (z == expect) cnt[0]++; else cnt[1]++; }
            });
        }
        net.clock.run(30_000);
        info("moved nodes found at their new zone: " + cnt[0] + " of 30 (stale answers: " + cnt[1] + ")");
        check("a node that moved is found at its NEW zone (>= 95%)", cnt[0] >= 29 && cnt[1] <= 1);

        // churn: 30% of the nodes die without a word
        DNet churn = new DNet(300, 6, cfg);
        churn.joinAll();
        for (int i = 0; i < churn.n; i++) if (churn.rng.nextDouble() < 0.30) churn.alive[i] = false;
        int[] rc = churn.lookups(150, true, null);
        info("30% of the nodes dead: " + rc[0] + " of 150 lookups correct, " + rc[1] + " wrong");
        check("with 30% of the nodes dead lookups still succeed (>= 93%)", rc[0] >= 140 && rc[1] == 0);

        // loss
        DNet lossy = new DNet(200, 8, cfg);
        lossy.joinAll();
        lossy.loss = 0.2;
        int[] rl = lossy.lookups(100, true, null);
        info("20% packet loss: " + rl[0] + " of 100 lookups correct");
        check("with 20% packet loss lookups still succeed (>= 90%)", rl[0] >= 90 && rl[1] == 0);

        // storage rules
        DhtNode.Config sc = new DhtNode.Config();
        DNet two = new DNet(2, 1, sc);
        two.d[1].bootstrap(1000, 0);
        two.clock.run(5_000);
        two.d[0].storeValue(12345, new byte[]{1, 1}, 50, 3_600_000);
        two.clock.run(5_000);
        two.d[0].storeValue(12345, new byte[]{9, 9}, 40, 3_600_000);   // older version must not win
        two.clock.run(5_000);
        final byte[][] got = new byte[1][];
        two.d[1].findValue(12345, new DhtNode.ValueCallback() { @Override public void onResult(int k, byte[] v) { got[0] = v; } });
        two.clock.run(10_000);
        check("the newest version of a value wins, an older store is ignored", got[0] != null && got[0][0] == 1);
        two.d[0].stop();
        two.d[1].stop();
        two.clock.run(3 * 3_600_000L);
        got[0] = new byte[]{7};
        two.d[1].findValue(12345, new DhtNode.ValueCallback() { @Override public void onResult(int k, byte[] v) { got[0] = v; } });
        two.clock.run(30_000);
        check("values expire when nobody republishes them", got[0] == null);

        boolean threw = false;
        Random fz = new Random(2);
        try {
            for (int i = 0; i < 3000; i++) { byte[] junk = new byte[fz.nextInt(60)]; fz.nextBytes(junk); if (junk.length > 0) junk[0] = (byte) (1 + fz.nextInt(6)); two.d[0].onMessage(1001, junk); }
            two.d[0].onMessage(1001, null);
        } catch (RuntimeException e) { threw = true; }
        check("3000 garbage datagrams do not hurt the engine", !threw);
        // an answer from somebody I never asked is ignored
        DNet liar = new DNet(3, 2, new DhtNode.Config());
        liar.d[0].bootstrap(1001, 1);
        java.nio.ByteBuffer forged = java.nio.ByteBuffer.allocate(9 + 1 + 8).put((byte) 2).putInt(12345).putInt(0).put((byte) 1).putInt(1002).putInt(5);
        liar.d[0].onMessage(1002, forged.array());
        check("an unsolicited reply does not poison the routing table", liar.d[0].closest(DhtNode.dhtId(1002), 5, null).stream().noneMatch(c -> c.nodeId == 1002 && c.zone == 5));
    }
}
