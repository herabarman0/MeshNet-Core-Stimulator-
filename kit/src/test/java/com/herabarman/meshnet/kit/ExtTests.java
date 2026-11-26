package com.herabarman.meshnet.kit;

import static com.herabarman.meshnet.kit.KitTests.check;
import static com.herabarman.meshnet.kit.KitTests.sleep;
import static com.herabarman.meshnet.kit.KitTests.waitFor;

import com.herabarman.meshnet.core.MemoryStorage;
import java.util.Arrays;
import java.util.Random;

/** Tests for the extension layers: learner, DTN, alerts, groups, padding, UDP, LoRa. Plain main(), no JUnit. */
public final class ExtTests {
    static final double LAT0 = 23.8000, LON0 = 90.4000;

    /** Runs after build(), before start(): attach Alerts, Groups and other layers that register handlers. */
    interface Pre { void run(MeshClient c); }

    /** A phone with fast timers for tests. */
    static final class N {
        final MeshClient c;
        final KitTests.Probe p = new KitTests.Probe();
        final MemoryStorage store;
        static MemoryStorage nextStorage;      // test hook: reuse a phone's disk after a "restart"

        N(MemNet net, int id, double lat, double lon, boolean dtn, boolean mask, MeshClient.Tuner tune) { this(net, id, lat, lon, dtn, mask, tune, null, null); }

        N(MemNet net, int id, double lat, double lon) { this(net, id, lat, lon, false, false, null, null, null); }

        N(MemNet net, int id, double lat, double lon, IdentityKeys ident, Pre pre) { this(net, id, lat, lon, false, false, null, ident, pre); }

        N(MemNet net, int id, double lat, double lon, boolean dtn, boolean mask, MeshClient.Tuner tune, IdentityKeys ident, Pre pre) {
            store = nextStorage != null ? nextStorage : new MemoryStorage();
            nextStorage = null;
            MeshClient.Builder bld = MeshClient.builder(Integer.toString(id)).radio(net.radio(id)).storage(store).listener(p)
                    .retry(400, 200).zoneHold(100).dtn(dtn).maskTraffic(mask)
                    .tune(cfg -> { cfg.ackTimeoutMs = 700; cfg.retryMs = 300; if (tune != null) tune.tune(cfg); });
            if (ident != null) bld.identity(ident);
            c = bld.build();
            if (pre != null) pre.run(c);
            c.updateLocation(lat, lon);
            c.start();
            waitFor(() -> c.zone() == GeoZones.zoneOf(lat, lon, 6), 2000);
        }
    }

    /** Give every pair the identity keys (they met before, or got them through the server). */
    static void seedKeys(N... ns) {
        for (N a : ns) for (N b : ns) if (a != b) a.c.keyDirectory().put(b.c.selfNode(), b.c.identity().publicRaw());
    }

    static void stop(N... ns) { for (N n : ns) n.c.stop(); }

    // ------------------------------------------------------------------ learner

    static void learner() {
        Prophet pr = new Prophet(1, 0);
        pr.encounter(2, 0);
        double p2 = pr.get(2);
        check("prophet: meeting raises predictability to 0.75", Math.abs(p2 - 0.75) < 1e-9);
        pr.encounter(2, 0);
        check("prophet: meeting again raises it further", pr.get(2) > p2 && pr.get(2) < 1.0);
        pr.age(10 * Prophet.UNIT_MS);
        check("prophet: it fades with time", pr.get(2) < 0.9 * 0.9);
        Prophet pt = new Prophet(1, 0);
        pt.encounter(2, 0);
        java.util.Map<Integer, Double> theirs = new java.util.HashMap<>();
        theirs.put(3, 0.9);
        pt.transitive(2, theirs, 0);
        check("prophet: transitivity gives a smaller value for a contact of a contact", pt.get(3) > 0 && pt.get(3) < pt.get(2));
        check("prophet: table round trip", Math.abs(Prophet.parseTable(pt.table(5)).get(2) - pt.get(2)) < 0.01);

        Rhythm rh = new Rhythm();
        for (int d = 0; d < 20; d++) rh.hit(7, d * 86_400_000L + 9 * 3_600_000L);     // always around 09:00 UTC
        check("rhythm: busiest hour is 1.0", Math.abs(rh.likelihood(7, 9 * 3_600_000L) - 1.0) < 1e-9);
        check("rhythm: other hours are near zero", rh.likelihood(7, 3 * 3_600_000L) < 0.05);

        DtnBrain b = new DtnBrain(new Random(1));
        double[] liar = {1, 1.0, 1.0, 0.1, 1, 0.1, 0.1, 1, 0};     // claims to reach the destination, but has a bad record
        double before = b.predict(liar);
        check("learner: starts willing to trust a strong claim", before > 0.5);
        for (int i = 0; i < 150; i++) b.learn(liar, 0.0);
        check("learner: after repeated failures it distrusts that kind of neighbour", b.predict(liar) < 0.5 && b.predict(liar) < before - 0.3);
        double[] honest = {1, 0.8, 0.6, 0.9, 1, 0.1, 0.1, 1, 0};
        for (int i = 0; i < 150; i++) b.learn(honest, 1.0);
        check("learner: and keeps trusting one that delivers", b.predict(honest) > 0.8);
        check("learner: exploration shrinks as it gains experience", b.exploration() < 0.10);
        check("learner: weights moved away from the priors", Math.abs(b.weights()[1] - 3.0) > 0.1 || Math.abs(b.weights()[3] - 1.5) > 0.1);
    }

    // ------------------------------------------------------------------ alerts

    static final class AN {
        final N n;
        Alerts alerts;
        final java.util.List<Alerts.Alert> got = new java.util.concurrent.CopyOnWriteArrayList<>();

        AN(MemNet net, int id, double lat, double lon, IdentityKeys ident, java.util.List<byte[]> trusted) {
            final AN self = this;
            n = new N(net, id, lat, lon, ident, c -> self.alerts = new Alerts(c, trusted, a -> self.got.add(a)));
        }
    }

    static void alerts() {
        MemNet net = new MemNet();
        IdentityKeys authority = IdentityKeys.generate();
        java.util.List<byte[]> trusted = java.util.Collections.singletonList(authority.publicRaw());
        AN s = new AN(net, 201, LAT0, LON0, authority, trusted);                 // civil defence
        AN r1 = new AN(net, 202, LAT0, LON0 + 0.002, null, trusted);
        AN r2 = new AN(net, 203, LAT0 + 0.001, LON0 + 0.004, null, trusted);
        AN x = new AN(net, 204, LAT0 + 0.002, LON0 + 0.006, null, trusted);
        AN y = new AN(net, 205, LAT0 + 5.0, LON0, null, trusted);                  // far outside the area
        net.link(201, 202); net.link(202, 203); net.link(203, 204); net.link(203, 205);
        waitFor(() -> r2.n.c.peerCount() == 3, 4000);
        String area = GeoZones.geohash(LAT0, LON0, 4);

        s.alerts.send(Alerts.WARNING, "Flood warning: move to high ground", LAT0, LON0, area, 3_600_000);
        check("official alert reaches a phone 3 hops away", waitFor(() -> !x.got.isEmpty(), 6000));
        check("it is marked official", x.got.get(0).official && x.got.get(0).type == Alerts.WARNING && x.got.get(0).text.startsWith("Flood"));
        check("phones in between got it too", waitFor(() -> !r1.got.isEmpty() && !r2.got.isEmpty(), 3000));
        sleep(500);
        check("a phone outside the area is not shown it", y.got.isEmpty());
        check("each phone got it exactly once (no echo storm)", x.got.size() == 1 && r1.got.size() == 1 && r2.got.size() == 1);

        x.alerts.send(Alerts.SOS, "SOS: trapped on roof, 3 people", LAT0 + 0.002, LON0 + 0.006, area, 3_600_000);
        check("a user's SOS reaches the others", waitFor(() -> s.got.size() == 1 && r1.got.size() == 2, 6000));
        check("a user's SOS is NOT marked official", !s.got.get(0).official && s.got.get(0).type == Alerts.SOS);
        x.alerts.send(Alerts.SOS, "SOS again", LAT0, LON0, area, 3_600_000);
        sleep(800);
        check("a second alert from the same key within 30 s is ignored (spam limit)", s.got.size() == 1 && r1.got.size() == 2);

        // forged and malformed alerts are dropped
        IdentityKeys rogue = IdentityKeys.generate();
        byte[] good = Alerts.encode(rogue, 77L, Alerts.WARNING, System.currentTimeMillis() + 60_000, area, LAT0, LON0, "Evacuate now", 5);
        byte[] forged = good.clone();
        forged[20] ^= 0x01;
        s.n.c.post(() -> s.alerts.ingest(forged));
        byte[] expired = Alerts.encode(rogue, 78L, Alerts.INFO, System.currentTimeMillis() - 5_000, area, LAT0, LON0, "old news", 5);
        s.n.c.post(() -> s.alerts.ingest(expired));
        byte[] worldwide = Alerts.encode(rogue, 79L, Alerts.WARNING, System.currentTimeMillis() + 60_000, "", LAT0, LON0, "whole world", 5);
        s.n.c.post(() -> s.alerts.ingest(worldwide));
        sleep(800);
        boolean bad = false;
        for (Alerts.Alert a : s.got) if (a.text.equals("Evacuate now") || a.text.equals("old news") || a.text.equals("whole world")) bad = true;
        check("tampered, expired and unofficial world-wide alerts are refused", !bad);

        // a phone that arrives later is told what is still valid (store-carry)
        AN late = new AN(net, 206, LAT0 + 0.003, LON0 + 0.007, null, trusted);
        net.link(204, 206);
        check("a late joiner receives the alerts that are still valid", waitFor(() -> late.got.size() >= 1, 8000));
        stop(s.n, r1.n, r2.n, x.n, y.n, late.n);
    }

    // ------------------------------------------------------------------ padding and fairness

    static void paddingAndFairness() {
        MemNet net = new MemNet();
        N a = new N(net, 211, LAT0, LON0, false, true, null);
        N b = new N(net, 212, LAT0, LON0, false, true, null);
        net.link(211, 212);
        waitFor(() -> a.c.peerCount() == 1, 2000);
        long id1 = a.c.sendText("212", "hi");
        long id2 = a.c.sendText("212", "a somewhat longer message than the greeting above, still short though");
        check("masked messages are delivered", waitFor(() -> b.p.texts.containsKey(id1) && b.p.texts.containsKey(id2), 12000));
        // sizes seen on the wire: encrypt two different lengths through one crypto pair and compare
        KitTests.check("padding: different message lengths give the same ciphertext length", paddedLengthsEqual());
        stop(a, b);

        // fairness: a neighbour that relays for others scores higher than one that only sends its own
        MemNet n2 = new MemNet();
        N me = new N(n2, 221, LAT0, LON0), relay = new N(n2, 222, LAT0, LON0), selfish = new N(n2, 223, LAT0, LON0), far = new N(n2, 224, LAT0, LON0);
        n2.link(221, 222); n2.link(221, 223); n2.link(222, 224);
        waitFor(() -> me.c.peerCount() == 2, 3000);
        for (int i = 0; i < 5; i++) { far.c.sendText("221", "via relay " + i); selfish.c.sendText("221", "own " + i); }
        check("fairness: all test messages arrive", waitFor(() -> me.p.texts.size() >= 10, 20000));
        check("fairness: the relaying neighbour scores higher than the one that only sends its own",
                me.c.fairness(222) > 0.5 && me.c.fairness(223) < 0.5 && me.c.fairness(222) > me.c.fairness(223));
        stop(me, relay, selfish, far);
    }

    static boolean paddedLengthsEqual() {
        IdentityKeys ka = IdentityKeys.generate(), kb = IdentityKeys.generate();
        KeyDirectory da = new KeyDirectory(null), db = new KeyDirectory(null);
        da.put(2, kb.publicRaw()); db.put(1, ka.publicRaw());
        E2eeCrypto a = new E2eeCrypto(1, ka, da), b = new E2eeCrypto(2, kb, db);
        a.setPadding(true);
        byte[] m1 = Frame.text(1, 2, "hi"), m2 = Frame.text(2, 2, "a much longer message with many more words in it than the first one has");
        byte[] c1 = a.encrypt(m1, 1, 2, 1), c2 = a.encrypt(m2, 1, 2, 2);
        boolean same = c1.length == c2.length;
        boolean ok = Arrays.equals(b.decrypt(c1, 1, 2, 1), m1) && Arrays.equals(b.decrypt(c2, 1, 2, 2), m2);
        byte[] flipped = c1.clone();
        flipped[0] = 2;      // pretend it was not padded
        boolean refused = b.decrypt(flipped, 1, 2, 9) == null;
        return same && ok && refused;
    }

    // ------------------------------------------------------------------ CRDT groups

    static byte[] reactBody(int tAuthor, int tSeq, boolean on, String emoji) {
        byte[] e = emoji.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.nio.ByteBuffer.allocate(9 + e.length).putInt(tAuthor).putInt(tSeq).put((byte) (on ? 1 : 0)).put(e).array();
    }

    static byte[] txt(String t) { return t.getBytes(java.nio.charset.StandardCharsets.UTF_8); }

    static void crdt() {
        IdentityKeys kf = IdentityKeys.generate(), ka = IdentityKeys.generate(), kb = IdentityKeys.generate(), kx = IdentityKeys.generate();
        byte[] gid = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        GroupState.Keys lookup = node -> node == 1 ? kf.publicRaw() : null;
        GroupState w = new GroupState(gid, false, 1, lookup);            // "the world as its authors saw it"
        java.util.List<GroupState.Op> all = new java.util.ArrayList<>();
        java.util.function.Consumer<GroupState.Op> put = op -> { all.add(op); w.ingest(op); };
        java.nio.ByteBuffer ba = java.nio.ByteBuffer.allocate(69).putInt(2).put(ka.publicRaw());
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(69).putInt(3).put(kb.publicRaw());
        put.accept(w.create(kf, 1, GroupState.ADD, 10, ba.array()));
        put.accept(w.create(kf, 1, GroupState.ADD, 11, bb.array()));
        put.accept(w.create(ka, 2, GroupState.MSG, 100, txt("a1 hello")));
        put.accept(w.create(ka, 2, GroupState.MSG, 120, txt("a2 second")));
        put.accept(w.create(kb, 3, GroupState.MSG, 110, txt("b1 from bob")));
        put.accept(w.create(kb, 3, GroupState.MSG, 110, txt("b2 same instant")));      // equal timestamps: order must still be deterministic
        put.accept(w.create(kb, 3, GroupState.REACT, 200, reactBody(2, 1, true, "👍")));
        put.accept(w.create(ka, 2, GroupState.REACT, 150, reactBody(2, 1, true, "👍")));
        put.accept(w.create(kb, 3, GroupState.REACT, 300, reactBody(2, 1, false, "👍")));   // Bob takes it back
        put.accept(w.create(kb, 3, GroupState.REACT, 400, reactBody(2, 1, true, "👍")));    // and again
        // Bob writes b3 while he still believes he is a member (membership version 2) ...
        GroupState.Op late = new GroupState.Op(gid, 3, 6, 2, 500, GroupState.MSG, txt("b3 written before the removal"), null);
        late.sig = kb.sign(late.signedBytes());
        // ... then the founder removes him
        put.accept(w.create(kf, 1, GroupState.REMOVE, 450, java.nio.ByteBuffer.allocate(4).putInt(3).array()));
        put.accept(late);
        // an op written after the removal was known is invalid everywhere
        GroupState.Op after = new GroupState.Op(gid, 3, 7, 3, 600, GroupState.MSG, txt("b4 after removal"), null);
        after.sig = kb.sign(after.signedBytes());
        all.add(after);
        // forged: Alice's op with the text replaced
        GroupState.Op forged = new GroupState.Op(gid, 2, 4, 3, 700, GroupState.MSG, txt("a3 forged"), null);
        forged.sig = kx.sign(forged.signedBytes());                                   // signed by somebody else
        all.add(forged);
        // an unknown author whose key nobody has
        GroupState.Op stranger = new GroupState.Op(gid, 9, 1, 0, 800, GroupState.MSG, txt("from a stranger"), null);
        stranger.sig = kx.sign(stranger.signedBytes());
        all.add(stranger);

        String canon = w.snapshot();
        check("crdt: removed member's earlier message is kept, later one is refused",
                canon.contains("b3 written before the removal") && !canon.contains("b4") && !canon.contains("forged") && !canon.contains("stranger"));
        check("crdt: members after removal are founder and Alice", w.members().equals(new java.util.TreeSet<>(java.util.Arrays.asList(1, 2))));
        check("crdt: last-writer-wins reaction (Bob's latest ON) is shown with Alice's", w.reactionsOf("2:1").get("👍").equals(new java.util.TreeSet<>(java.util.Arrays.asList(2, 3))));
        check("crdt: equal timestamps order deterministically", canon.indexOf("b1 from bob") < canon.indexOf("b2 same instant"));

        boolean allSame = true;
        Random rnd = new Random(42);
        for (int round = 0; round < 60 && allSame; round++) {
            java.util.List<GroupState.Op> feed = new java.util.ArrayList<>(all);
            feed.addAll(all);                                                          // every op arrives twice
            java.util.Collections.shuffle(feed, rnd);
            GroupState r = new GroupState(gid, false, 1, lookup);
            for (GroupState.Op op : feed) r.ingest(GroupState.Op.decode(op.encode()));   // through the wire encoding
            if (!r.snapshot().equals(canon)) allSame = false;
        }
        check("crdt: 60 replicas that got every op twice in random order all end up identical", allSame);

        // open channel: authors identified by key, no membership
        byte[] cid = new byte[]{8, 7, 6, 5, 4, 3, 2, 1};
        GroupState cw = new GroupState(cid, true, 0, n -> null);
        java.util.List<GroupState.Op> call = new java.util.ArrayList<>();
        GroupState.Op c1 = cw.create(ka, 2, GroupState.MSG, 10, txt("first post"));
        cw.ingest(c1); call.add(c1);
        GroupState.Op c2 = cw.create(kb, 3, GroupState.MSG, 20, txt("reply from anyone"));
        cw.ingest(c2); call.add(c2);
        GroupState.Op c3 = cw.create(ka, 2, GroupState.MSG, 30, txt("second post"));
        cw.ingest(c3); call.add(c3);
        GroupState.Op spoof = new GroupState.Op(cid, GroupState.authorOf(ka.publicRaw()), 3, 0, 40, GroupState.MSG, txt("spoof"), kb.publicRaw());
        spoof.sig = kb.sign(spoof.signedBytes());                                      // claims Alice's author id with Bob's key
        call.add(spoof);
        String ccanon = cw.snapshot();
        boolean csame = true;
        for (int round = 0; round < 30 && csame; round++) {
            java.util.List<GroupState.Op> feed = new java.util.ArrayList<>(call);
            java.util.Collections.shuffle(feed, rnd);
            GroupState r = new GroupState(cid, true, 0, n -> null);
            for (GroupState.Op op : feed) r.ingest(op);
            if (!r.snapshot().equals(ccanon)) csame = false;
        }
        check("crdt: open channel converges in any order and refuses an author-id spoof", csame && ccanon.contains("second post") && !ccanon.contains("spoof"));
    }

    // ------------------------------------------------------------------ groups over the mesh

    static final class GN {
        final N n;
        Groups g;
        final java.util.List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        GN(MemNet net, int id, double lat, double lon) {
            final GN self = this;
            n = new N(net, id, lat, lon, null, c -> self.g = new Groups(c, new Groups.Listener() {
                @Override public void onMessage(String gid, String author, long ts, String text, String opId) { self.events.add("msg:" + author + ":" + text); }
                @Override public void onReaction(String gid, String opId, String emoji, String reactor, boolean on) { self.events.add("react:" + reactor + ":" + emoji + ":" + on); }
                @Override public void onMembers(String gid, java.util.Set<String> m) { self.events.add("members:" + m); }
            }, 400));
        }

        java.util.List<String> texts(String gid) {
            java.util.List<String> l = new java.util.ArrayList<>();
            for (Groups.Message m : g.history(gid)) l.add(m.text);
            return l;
        }
    }

    static void groups() {
        MemNet net = new MemNet();
        GN f = new GN(net, 231, LAT0, LON0), a = new GN(net, 232, LAT0, LON0), b = new GN(net, 233, LAT0, LON0);
        seedKeys(f.n, a.n, b.n);
        net.link(231, 232); net.link(232, 233);
        waitFor(() -> a.n.c.peerCount() == 2, 3000);
        String gid = f.g.createGroup("team");
        f.g.addMember(gid, "232");
        f.g.addMember(gid, "233");
        check("members learn the group and its member list (the invite reaches B over 2 hops)",
                waitFor(() -> a.g.members(gid).size() == 3 && b.g.members(gid).size() == 3, 15000));
        a.g.post(gid, "hello from A");
        b.g.post(gid, "hi from B");
        check("everyone sees both messages", waitFor(() -> f.texts(gid).size() == 2 && a.texts(gid).size() == 2 && b.texts(gid).size() == 2, 15000));
        check("all three histories are identical and in the same order", f.texts(gid).equals(a.texts(gid)) && a.texts(gid).equals(b.texts(gid)));
        String firstId = a.g.history(gid).get(0).opId;
        f.g.react(gid, firstId, "👍", true);
        check("a reaction reaches everyone", waitFor(() -> !a.g.history(gid).get(0).reactions.isEmpty() && !b.g.history(gid).get(0).reactions.isEmpty(), 15000));
        check("and shows who reacted", a.g.history(gid).get(0).reactions.get("👍").contains("231"));

        net.unlink(232, 233);                                                         // B goes out of range
        a.g.post(gid, "while B was away 1");
        a.g.post(gid, "while B was away 2");
        a.g.post(gid, "while B was away 3");
        check("A and F have the new messages", waitFor(() -> a.texts(gid).size() == 5 && f.texts(gid).size() == 5, 15000));
        check("B does not have them yet", b.texts(gid).size() == 2);
        net.link(232, 233);
        check("B catches up on its own when it is back (no duplicates)", waitFor(() -> b.texts(gid).size() == 5, 25000));
        sleep(800);
        check("still exactly 5 after more sync rounds", b.texts(gid).size() == 5 && b.texts(gid).equals(a.texts(gid)));

        f.g.removeMember(gid, "233");
        check("removal reaches the others", waitFor(() -> a.g.members(gid).size() == 2 && b.g.members(gid).size() == 2, 15000));
        b.g.post(gid, "should not appear");
        sleep(1500);
        check("a removed member cannot post any more", !a.texts(gid).contains("should not appear") && !f.texts(gid).contains("should not appear"));

        // restart of A: group, members and history come back from disk
        MemoryStorage disk = a.n.store;
        int before = a.texts(gid).size();
        a.n.c.stop();
        N.nextStorage = disk;
        GN a2 = new GN(net, 232, LAT0, LON0);
        check("after a restart the group and its history are read back from storage",
                waitFor(() -> a2.g.groupIds().contains(gid) && a2.texts(gid).size() == before, 3000) && a2.g.members(gid).size() == 2);
        stop(f.n, a2.n, b.n);

        // open channel across a chain of strangers
        MemNet n2 = new MemNet();
        GN x = new GN(n2, 241, LAT0, LON0), y = new GN(n2, 242, LAT0, LON0), z = new GN(n2, 243, LAT0, LON0);
        n2.link(241, 242); n2.link(242, 243);
        waitFor(() -> y.n.c.peerCount() == 2, 3000);
        String ch = x.g.createChannel("news");
        y.g.joinChannel(ch, "news");
        z.g.joinChannel(ch, "news");
        x.g.post(ch, "breaking: bridge closed");
        check("a channel post reaches a neighbour and, through it, a phone two hops away",
                waitFor(() -> y.texts(ch).size() == 1 && z.texts(ch).size() == 1, 10000));
        z.g.post(ch, "reply from Z");
        check("a post from the far end reaches the other end too", waitFor(() -> x.texts(ch).size() == 2 && y.texts(ch).size() == 2, 10000));
        check("channel histories converge to the same order", x.texts(ch).equals(y.texts(ch)) && y.texts(ch).equals(z.texts(ch)));
        // a tampered post must not be accepted
        byte[] forgedBytes = forgeChannelOp(ch);                        // one forged op, used everywhere below (signature length varies)
        GroupState.Op forged = GroupState.Op.decode(forgedBytes);
        byte[] payload = java.nio.ByteBuffer.allocate(1 + 8 + 1 + 2 + forgedBytes.length).put((byte) 3).put(unhexB(ch)).put((byte) 1)
                .putShort((short) forgedBytes.length).put(forgedBytes).array();
        x.n.c.sendToNeighbor(242, Groups.BCAST_CHANNEL, payload);
        sleep(1200);
        check("a channel post with a bad signature is ignored", forged != null && y.texts(ch).size() == 2);
        // a phone that joins later catches up from whoever it meets
        GN late = new GN(n2, 244, LAT0, LON0);
        late.g.joinChannel(ch, "news");
        n2.link(243, 244);
        check("a phone that joins later gets the whole channel history from a passer-by", waitFor(() -> late.texts(ch).size() == 2, 10000));
        stop(x.n, y.n, z.n, late.n);
    }

    static byte[] unhexB(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    /** A well-formed channel op whose signature does not match its content. */
    static byte[] forgeChannelOp(String chId) {
        IdentityKeys k = IdentityKeys.generate();
        GroupState st = new GroupState(unhexB(chId), true, 0, n -> null);
        GroupState.Op op = st.create(k, 1, GroupState.MSG, 999, txt("forged post"));
        byte[] enc = op.encode();
        enc[22] ^= 0x01;                       // change a byte of the timestamp: still parses, signature no longer matches
        return enc;
    }

    // ------------------------------------------------------------------ desktop (UDP) and LoRa

    static MeshClient plain(int id, RawRadio radio, KitTests.Probe p) {
        return MeshClient.builder(Integer.toString(id)).radio(radio).storage(new MemoryStorage()).listener(p)
                .retry(400, 200).tune(cfg -> { cfg.ackTimeoutMs = 900; cfg.retryMs = 400; }).build();
    }

    static void udp() {
        UdpRadio r1 = new UdpRadio(301, 19101, false, 300, 2500), r2 = new UdpRadio(302, 19102, false, 300, 2500);
        r1.addPeer(new java.net.InetSocketAddress("127.0.0.1", 19102));
        r2.addPeer(new java.net.InetSocketAddress("127.0.0.1", 19101));
        KitTests.Probe p1 = new KitTests.Probe(), p2 = new KitTests.Probe();
        MeshClient a = plain(301, r1, p1), b = plain(302, r2, p2);
        a.start(); b.start();
        check("UDP: two desktop nodes find each other", waitFor(() -> a.peerCount() == 1 && b.peerCount() == 1, 5000));
        long id = a.sendText("302", "hello from the desktop");
        check("UDP: text delivered end-to-end encrypted, with confirmation",
                waitFor(() -> "301:hello from the desktop".equals(p2.texts.get(id)) && p1.delivered.contains(id), 12000));
        byte[] file = new byte[150_000];
        new Random(8).nextBytes(file);
        long blob = b.sendBlob("301", file);
        check("UDP: a 150 KB file is cut into datagrams and arrives identical",
                waitFor(() -> p1.blobs.containsKey(blob) && Arrays.equals(p1.blobs.get(blob), file), 30000));
        b.stop();
        r2.stop();
        check("UDP: a node that goes away is noticed", waitFor(() -> a.peerCount() == 0, 8000));
        a.stop();
        r1.stop();
    }

    /** A shared slow medium: frames reach every other modem after their airtime; optional loss; limited frame size. */
    static final class Medium {
        final java.util.List<FakeModem> modems = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile int lossPct;
        final Random rnd = new Random(11);
        final java.util.concurrent.atomic.AtomicLong frames = new java.util.concurrent.atomic.AtomicLong(), bytes = new java.util.concurrent.atomic.AtomicLong();
        int maxFrameSeen;
    }

    static final class FakeModem implements LoraRadio.Modem {
        final Medium m;
        volatile LoraRadio.Receiver rx;
        final int mtu;
        FakeModem(Medium m, int mtu) { this.m = m; this.mtu = mtu; m.modems.add(this); }
        @Override public void send(byte[] f) {
            if (f.length > mtu) throw new IllegalStateException("frame larger than the modem allows: " + f.length);
            m.frames.incrementAndGet();
            m.bytes.addAndGet(f.length);
            m.maxFrameSeen = Math.max(m.maxFrameSeen, f.length);
            for (FakeModem o : m.modems) {
                if (o == this || (m.lossPct > 0 && m.rnd.nextInt(100) < m.lossPct)) continue;
                LoraRadio.Receiver r = o.rx;
                if (r != null) r.onFrame(f.clone());
            }
        }
        @Override public void setReceiver(LoraRadio.Receiver r) { rx = r; }
        @Override public int maxFrame() { return mtu; }
    }

    static LoraRadio.Config fastLora() {
        LoraRadio.Config c = new LoraRadio.Config();
        c.helloMs = 300; c.downMs = 3000; c.bitsPerSecond = 2_000_000; c.dutyFraction = 1.0; c.windowMs = 1000;
        return c;
    }

    static void lora() {
        Medium med = new Medium();
        LoraRadio l1 = new LoraRadio(311, new FakeModem(med, 200), fastLora()), l2 = new LoraRadio(312, new FakeModem(med, 200), fastLora());
        KitTests.Probe p1 = new KitTests.Probe(), p2 = new KitTests.Probe();
        MeshClient a = plain(311, l1, p1), b = plain(312, l2, p2);
        a.start(); b.start();
        check("LoRa: nodes hear each other's HELLO beacons", waitFor(() -> a.peerCount() == 1 && b.peerCount() == 1, 5000));
        long id = a.sendText("312", "hello over lora");
        check("LoRa: text delivered through the 200-byte frames (key exchange included)", waitFor(() -> "311:hello over lora".equals(p2.texts.get(id)) && p1.delivered.contains(id), 15000));
        StringBuilder sb = new StringBuilder();
        Random r = new Random(5);
        for (int i = 0; i < 400; i++) sb.append((char) ('a' + r.nextInt(26)));
        long id2 = a.sendText("312", sb.toString());
        check("LoRa: a 400-character message is fragmented and put together again", waitFor(() -> ("311:" + sb).equals(p2.texts.get(id2)), 15000));
        check("LoRa: no frame ever exceeded the modem's limit", med.maxFrameSeen <= 200);
        med.lossPct = 15;
        long id3 = b.sendText("311", sb.substring(0, 300));
        check("LoRa: still delivered when 15% of the frames are lost on the air", waitFor(() -> p1.texts.containsKey(id3), 40000));
        med.lossPct = 0;
        a.stop(); b.stop(); l1.stop(); l2.stop();

        // duty cycle: 10% of a 1 s window at 50 kbit/s allows about 3 frames of 200 bytes per second
        Medium m2 = new Medium();
        LoraRadio.Config cfg = new LoraRadio.Config();
        cfg.helloMs = 60_000; cfg.downMs = 600_000; cfg.bitsPerSecond = 50_000; cfg.dutyFraction = 0.10; cfg.windowMs = 1000; cfg.maxQueue = 100;
        LoraRadio tx = new LoraRadio(321, new FakeModem(m2, 200), cfg);
        LoraRadio rxr = new LoraRadio(322, new FakeModem(m2, 200), fastLora());
        final java.util.concurrent.atomic.AtomicInteger got = new java.util.concurrent.atomic.AtomicInteger();
        rxr.setHandler(new RawRadio.Handler() {
            @Override public void onLinkUp(int n) { }
            @Override public void onLinkDown(int n) { }
            @Override public void onRaw(int n, byte[] d) { got.incrementAndGet(); }
        });
        final java.util.concurrent.atomic.AtomicBoolean up = new java.util.concurrent.atomic.AtomicBoolean();
        tx.setHandler(new RawRadio.Handler() {
            @Override public void onLinkUp(int n) { up.set(true); }
            @Override public void onLinkDown(int n) { }
            @Override public void onRaw(int n, byte[] d) { }
        });
        tx.start(); rxr.start();
        waitFor(up::get, 5000);
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) tx.sendRaw(322, new byte[150]);
        check("LoRa duty cycle: the radio spreads 10 frames over time instead of bursting", waitFor(() -> got.get() == 10, 20000) && System.currentTimeMillis() - t0 > 2000);
        check("LoRa duty cycle: time on air in the window stays within the budget", tx.airtimeUsedMs() <= 110);
        tx.stop(); rxr.stop();
    }

    // ------------------------------------------------------------------ DTN

    static void mule() {
        MemNet net = new MemNet();
        MeshClient.Tuner noRelayStore = cfg -> cfg.storeTtlMs = 1;     // relays forget packets at once: only DTN custody can carry
        N a = new N(net, 101, LAT0, LON0, true, false, noRelayStore);
        N m = new N(net, 102, LAT0, LON0 + 0.004, true, false, noRelayStore);
        N c = new N(net, 103, LAT0, LON0 + 0.5, true, false, noRelayStore);
        seedKeys(a, c);
        net.link(101, 102);
        waitFor(() -> a.c.peerCount() == 1, 3000);
        long id = a.c.sendText("103", "carried by a mule");
        check("A hands a copy to the passing phone", waitFor(() -> a.c.dtn().stats().handedOver >= 1, 40000));
        net.unlink(101, 102);
        sleep(500);
        net.link(102, 103);
        check("the mule walks to C's area and C receives the message", waitFor(() -> "101:carried by a mule".equals(c.p.texts.get(id)), 40000));
        check("C hands its confirmation to the mule before it leaves", waitFor(() -> c.c.dtn().stats().handedOver >= 1, 40000));
        net.unlink(102, 103);
        net.link(102, 101);
        check("the confirmation comes back to A through the mule", waitFor(() -> a.p.delivered.contains(id), 60000));
        check("A's learner updated from the outcome", waitFor(() -> a.c.dtn().stats().learnedUpdates >= 1, 15000));
        check("A knows it can reach the mule (PRoPHET)", a.c.dtn().predictability(102) > 0.5);
        stop(a, m, c);
    }

    public static void main(String[] args) {
        System.out.println("== extensions: learner ==");
        learner();
        System.out.println("== extensions: CRDT ==");
        crdt();
        System.out.println("== extensions: groups over the mesh ==");
        groups();
        System.out.println("== extensions: alerts ==");
        alerts();
        System.out.println("== extensions: padding, fairness ==");
        paddingAndFairness();
        System.out.println("== extensions: desktop UDP ==");
        udp();
        System.out.println("== extensions: LoRa protocol layer ==");
        lora();
        System.out.println("== extensions: DTN ==");
        mule();
        System.out.println("\n" + KitTests.pass + " passed, " + KitTests.fail + " failed");
        System.exit(KitTests.fail == 0 ? 0 : 1);
    }
}
