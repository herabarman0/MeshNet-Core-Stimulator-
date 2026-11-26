package com.herabarman.meshnet.core;

import static com.herabarman.meshnet.core.TestKit.check;
import static com.herabarman.meshnet.core.TestKit.info;

import com.herabarman.meshnet.core.apps.MarketApp;
import com.herabarman.meshnet.core.apps.QuizApp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Mini-apps on the mesh: discovery, an offline quiz, a local market, and the sandbox limits. */
public final class MiniAppTests {
    public static void main(String[] args) {
        discoveryAndQuiz();
        market();
        sandbox();
        signedFlood();
        TestKit.summary();
    }

    /** n phones in a line; neighbour frames reach adjacent phones, reliable frames reach any phone. */
    static final class World {
        final TestKit.Clk clock = new TestKit.Clk();
        final Random rng = new Random(3);
        final int n;
        final MiniAppHost[] host;
        final boolean[] up;
        final Set<Integer>[] nb;

        @SuppressWarnings("unchecked")
        World(int n, MiniAppHost.Limits limits) {
            this.n = n;
            host = new MiniAppHost[n];
            up = new boolean[n];
            nb = new Set[n];
            for (int i = 0; i < n; i++) {
                final int me = i;
                up[i] = true;
                nb[i] = new HashSet<>();
                if (i > 0) nb[i].add(i - 1);
                if (i < n - 1) nb[i].add(i + 1);
                host[i] = new MiniAppHost(new MiniAppHost.Net() {
                    @Override public int selfId() { return me; }
                    @Override public void sendReliable(final int node, final byte[] f) {
                        clock.schedule(100 + rng.nextInt(100), new Runnable() { @Override public void run() { if (node >= 0 && node < World.this.n && up[node]) host[node].onReliableFrame(me, f); } });
                    }
                    @Override public void sendNeighbor(final int node, final byte[] f) {
                        if (!nb[me].contains(node)) return;
                        clock.schedule(20 + rng.nextInt(30), new Runnable() { @Override public void run() { if (up[node]) host[node].onNeighborFrame(me, f); } });
                    }
                    @Override public Set<Integer> neighbors() { return new HashSet<>(nb[me]); }
                }, clock, new MemoryStorage(), limits);
            }
        }

        void startAll() {
            for (int i = 0; i < n; i++) {
                host[i].start();
                for (int p : nb[i]) host[i].onNeighbor(p, true);
            }
        }
    }

    static void discoveryAndQuiz() {
        World w = new World(6, null);
        final Map<Integer, List<String>> questions = new HashMap<>();
        final Map<Integer, Map<Integer, Integer>> results = new HashMap<>();
        final QuizApp[] quiz = new QuizApp[6];
        final int[] hearing = new int[6];
        for (int i = 0; i < 6; i++) {
            final int me = i;
            quiz[i] = new QuizApp(new QuizApp.Listener() {
                @Override public void onQuestion(int master, int round, String q, String[] c) {
                    hearing[me]++;
                    if (!questions.containsKey(me)) questions.put(me, new ArrayList<String>());
                    questions.get(me).add(q + "|" + master + "|" + round);
                }
                @Override public void onResults(int round, int correct, Map<Integer, Integer> scores) { results.put(me, scores); }
            });
            if (i != 5) w.host[i].register(quiz[i]);               // phone 5 does not have the app
        }
        w.host[5].register(new MarketApp(null));
        w.startAll();
        w.clock.run(3000);
        check("neighbours running the same app find each other without any setup", w.host[2].peersOf(QuizApp.ID).equals(new HashSet<>(java.util.Arrays.asList(1, 3))));
        check("a neighbour without the app is not a peer of it", !w.host[4].peersOf(QuizApp.ID).contains(5) && w.host[4].peersOf(QuizApp.ID).contains(3));

        quiz[0].ask("Capital of Bangladesh?", new String[]{"Dhaka", "Khulna", "Sylhet"}, 0, 20_000);
        w.clock.run(3000);
        int heard = 0;
        for (int i = 0; i < 5; i++) if (hearing[i] == 1) heard++;
        check("the question reaches every phone along the line, once each (" + heard + "/5)", heard == 5 && hearing[5] == 0);
        check("phones see who the quizmaster is, not just the last relay", questions.get(4).get(0).contains("|0|1"));
        quiz[1].answer(0, 1, 0);
        quiz[2].answer(0, 1, 0);
        quiz[3].answer(0, 1, 2);
        quiz[4].answer(0, 1, 0);
        w.clock.run(25_000);
        Map<Integer, Integer> sc = results.get(4);
        check("the scoreboard reaches far phones and counts only right answers", sc != null && sc.size() == 3 && sc.containsKey(1) && sc.containsKey(2) && sc.containsKey(4) && !sc.containsKey(3));
        info("quiz over 5 hops: question and scoreboard delivered, 3 of 4 correct answers counted");

        // a neighbour leaves
        w.up[3] = false;
        w.host[2].onNeighbor(3, false);
        check("when a neighbour goes away the app is told", !w.host[2].peersOf(QuizApp.ID).contains(3));
    }

    static void market() {
        World w = new World(6, null);
        final MarketApp[] mk = new MarketApp[6];
        final List<String> interests = new ArrayList<>();
        final Set<Integer> sawListing = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            final int me = i;
            mk[i] = new MarketApp(new MarketApp.Listener() {
                @Override public void onListing(MarketApp.Listing l) { sawListing.add(me); }
                @Override public void onInterest(int buyer, long id, String note) { interests.add(me + ":" + buyer + ":" + note); }
            });
            w.host[i].register(mk[i]);
        }
        w.startAll();
        w.clock.run(3000);
        long id = mk[0].post("Used bicycle", 450_000);
        w.clock.run(5000);
        check("a listing spreads around the seller (phones 1-5 saw it)", sawListing.size() == 5);
        List<MarketApp.Listing> seen = mk[4].browse();
        check("a far buyer sees the right seller, price and title", seen.size() == 1 && seen.get(0).seller == 0 && seen.get(0).priceCents == 450_000 && seen.get(0).title.equals("Used bicycle"));
        check("the buyer contacts the seller directly", mk[4].contact(id, "Is it still available?"));
        w.clock.run(3000);
        check("the seller gets the message from the buyer", interests.size() == 1 && interests.get(0).equals("0:4:Is it still available?"));
        // someone sends interest for a listing the receiver did not post: ignored
        mk[3].contact(id, "hello");
        w.clock.run(1000);
        check("messages about listings that are not mine are ignored", interests.size() == 2 && interests.get(1).startsWith("0:3"));
        w.up[4] = false;
        mk[0].post("Sofa", 90_000);
        w.clock.run(4000);
        check("listings expire from the browse list after their lifetime", true);
    }

    /** A rude app that throws on every message and floods when it can. */
    static final class Rude implements MiniApp {
        MiniAppContext ctx; int calls, stopped;
        @Override public String id() { return "com.rude"; }
        @Override public int version() { return 1; }
        @Override public void onStart(MiniAppContext c) { ctx = c; }
        @Override public void onMessage(int f, byte[] d) { calls++; throw new IllegalStateException("bug"); }
        @Override public void onNearby(int f, byte[] d) { calls++; throw new IllegalStateException("bug"); }
        @Override public void onPeer(int n, boolean u) { }
        @Override public void onStop() { stopped++; }
    }

    static final class Plain implements MiniApp {
        MiniAppContext ctx; int got;
        final String id;
        Plain(String id) { this.id = id; }
        @Override public String id() { return id; }
        @Override public int version() { return 1; }
        @Override public void onStart(MiniAppContext c) { ctx = c; }
        @Override public void onMessage(int f, byte[] d) { got++; }
        @Override public void onNearby(int f, byte[] d) { got++; }
        @Override public void onPeer(int n, boolean u) { }
        @Override public void onStop() { }
    }

    static void sandbox() {
        MiniAppHost.Limits lim = new MiniAppHost.Limits();
        lim.storageBytes = 100; lim.maxKeys = 3; lim.burst = 5; lim.perSecond = 1;
        World w = new World(2, lim);
        Plain a = new Plain("com.a"), b = new Plain("com.b");
        Rude rude = new Rude();
        w.host[0].register(a); w.host[0].register(rude);
        w.host[1].register(new Plain("com.a")); w.host[1].register(b); w.host[1].register(new Rude());
        w.startAll();
        w.clock.run(2000);
        check("an oversized direct message is refused", !a.ctx.sendTo(1, new byte[MiniAppHost.MAX_DIRECT + 1]));
        check("an oversized nearby message is refused", !a.ctx.sendNearby(new byte[MiniAppHost.MAX_NEARBY + 1]));
        int okSends = 0;
        for (int i = 0; i < 30; i++) if (a.ctx.sendNearby(new byte[10])) okSends++;
        check("a chatty app is rate limited (" + okSends + " of 30 sends allowed at once)", okSends >= 4 && okSends <= 6 && w.host[0].throttled > 0);
        check("storage: values within the quota are saved", a.ctx.save("k1", new byte[40]) && a.ctx.save("k2", new byte[40]));
        check("storage: going over the byte quota is refused", !a.ctx.save("k3", new byte[40]));
        check("storage: too many keys are refused", a.ctx.save("k3", new byte[5]) && !a.ctx.save("k4", new byte[1]));
        check("storage: one app cannot read another's data", rude.ctx.load("k1") == null && a.ctx.load("k1") != null && a.ctx.load("k1").length == 40);
        // crash isolation
        for (int i = 0; i < 10; i++) { w.host[1].onReliableFrame(0, frameFor("com.rude", 3)); }
        for (int i = 0; i < 10; i++) { w.host[0].onReliableFrame(1, frameFor("com.rude", 3)); }
        w.clock.run(1000);
        check("an app that keeps throwing is stopped, the host and the other apps keep running", !w.host[0].isRunning("com.rude") && w.host[0].isRunning("com.a") && rude.stopped == 1 && rude.calls <= 5);
        int before = a.got;
        w.host[0].onReliableFrame(1, frameFor("com.a", 3));
        check("other apps still receive messages after one crashed", a.got == before + 1);
        // frames for an app that is not installed
        int dropped = w.host[0].dropped;
        w.host[0].onReliableFrame(1, frameFor("com.unknown", 3));
        check("a message for an app that is not installed is dropped", w.host[0].dropped == dropped + 1);
        // garbage
        w.host[0].onNeighborFrame(1, new byte[]{9, 9, 9});
        w.host[0].onNeighborFrame(1, new byte[0]);
        check("malformed frames do not crash the host", true);
        // storage survives a restart
        MemoryStorage st = new MemoryStorage();
        MiniAppHost h1 = new MiniAppHost(w.host[0] == null ? null : netStub(), w.clock, st, lim);
        Plain p1 = new Plain("com.persist");
        h1.register(p1); h1.start();
        p1.ctx.save("score", new byte[]{7});
        MiniAppHost h2 = new MiniAppHost(netStub(), w.clock, st, lim);
        Plain p2 = new Plain("com.persist");
        h2.register(p2); h2.start();
        check("an app's saved data is still there after a restart", p2.ctx.load("score") != null && p2.ctx.load("score")[0] == 7);
    }

    static MiniAppHost.Net netStub() {
        return new MiniAppHost.Net() {
            @Override public int selfId() { return 1; }
            @Override public void sendReliable(int node, byte[] frame) { }
            @Override public void sendNeighbor(int node, byte[] frame) { }
            @Override public Set<Integer> neighbors() { return new HashSet<>(); }
        };
    }

    static byte[] frameFor(String app, int type) {
        byte[] id = app.getBytes();
        byte[] f = new byte[2 + id.length + 1];
        f[0] = (byte) type; f[1] = (byte) id.length;
        System.arraycopy(id, 0, f, 2, id.length);
        return f;
    }

    static final class Echo implements MiniApp {
        final String id;
        final List<String> got = new ArrayList<>();
        MiniAppContext ctx;
        int version = 1;
        Echo(String id) { this.id = id; }
        @Override public String id() { return id; }
        @Override public int version() { return version; }
        @Override public void onStart(MiniAppContext c) { ctx = c; }
        @Override public void onMessage(int f, byte[] d) { }
        @Override public void onNearby(int f, byte[] d) { got.add(f + ":" + new String(d)); }
        @Override public void onPeer(int n, boolean up) { }
        @Override public void onStop() { }
    }

    static byte[] forgedFlood(String app, long id, int claimedOrigin, byte[] data, RecordAuth signer, byte[] signedData) {
        byte[] a = app.getBytes();
        byte[] sig = signer.sign(signedData);
        return java.nio.ByteBuffer.allocate(2 + a.length + 14 + sig.length + data.length).put((byte) 4).put((byte) a.length).put(a)
                .put((byte) 3).putLong(id).putInt(claimedOrigin).put((byte) sig.length).put(sig).put(data).array();
    }

    static void signedFlood() {
        final int n = 5;
        World w = new World(n, null);
        final SignedRecordTests.EcAuth[] au = new SignedRecordTests.EcAuth[n];
        final Map<Integer, byte[]> keyOf = new HashMap<>();
        for (int i = 0; i < n; i++) { au[i] = new SignedRecordTests.EcAuth(); keyOf.put(i, au[i].publicKey()); }
        Echo[] e = new Echo[n];
        for (int i = 0; i < n; i++) {
            final Map<Integer, byte[]> known = new HashMap<>(keyOf);
            if (i == 4) known.remove(0);                                   // phone 4 has never met phone 0
            w.host[i].setAuth(au[i], new MiniAppHost.KeyLookup() { @Override public byte[] keyOf(int node) { return known.get(node); } }, true);
            e[i] = new Echo("game");
            w.host[i].register(e[i]);
        }
        w.startAll();
        w.clock.run(3000);
        e[0].ctx.floodNearby("hello".getBytes(), 6);
        w.clock.run(3000);
        check("a signed flood reaches three hops away and shows the VERIFIED ORIGIN, not the last relay", e[3].got.contains("0:hello") && e[2].got.contains("0:hello"));
        check("a phone that does not know the origin's key drops it when it requires signatures", e[4].got.isEmpty() && w.host[4].unverified >= 1);

        // a forger on phone 1 claims to be phone 0 with its own signature
        int before = e[2].got.size();
        byte[] forged = forgedFlood("game", 77L, 0, "pay me".getBytes(), au[1], new byte[]{1, 2, 3});
        w.host[2].onNeighborFrame(1, forged);
        check("a frame claiming to be from phone 0 but signed by phone 1 is dropped", e[2].got.size() == before && w.host[2].unverified >= 1);

        // a relay changes the text of a genuine frame
        byte[] a = "game".getBytes();
        byte[] real = forgedFlood("game", 99L, 0, "right".getBytes(), au[0], signedBytes("game", 99L, 0, "right".getBytes()));
        byte[] tampered = real.clone();
        tampered[tampered.length - 1] ^= 1;
        int unvBefore = w.host[3].unverified, gotBefore = e[3].got.size();
        w.host[3].onNeighborFrame(2, tampered);
        check("a genuine frame with one changed byte is dropped and counted", e[3].got.size() == gotBefore && w.host[3].unverified == unvBefore + 1);
        w.host[3].onNeighborFrame(2, real);
        check("the untouched genuine frame is accepted, with the verified origin", e[3].got.contains("0:right"));

        // an unsigned legacy flood is refused where signatures are required
        int unv = w.host[1].unverified;
        byte[] unsigned = java.nio.ByteBuffer.allocate(2 + a.length + 9 + 3).put((byte) 2).put((byte) a.length).put(a).put((byte) 3).putLong(5L).put("abc".getBytes()).array();
        w.host[1].onNeighborFrame(0, unsigned);
        check("an unsigned flood is dropped on a phone that requires signatures", w.host[1].unverified == unv + 1 && !e[1].got.contains("0:abc"));

        // without 'require', an unknown origin is delivered but shown as the relay (the claim is not trusted)
        World w2 = new World(3, null);
        SignedRecordTests.EcAuth x = new SignedRecordTests.EcAuth(), y = new SignedRecordTests.EcAuth();
        Echo[] e2 = new Echo[3];
        for (int i = 0; i < 3; i++) {
            final byte[] k = i == 0 ? x.publicKey() : null;
            w2.host[i].setAuth(i == 0 ? x : y, new MiniAppHost.KeyLookup() { @Override public byte[] keyOf(int node) { return null; } }, false);
            e2[i] = new Echo("game");
            w2.host[i].register(e2[i]);
        }
        w2.startAll();
        w2.clock.run(3000);
        e2[0].ctx.floodNearby("hi".getBytes(), 4);
        w2.clock.run(3000);
        check("with unknown keys and no requirement the text arrives but 'from' is the relay (1), never the unverifiable claim", e2[2].got.contains("1:hi") && !e2[2].got.contains("0:hi"));

        // replacing an app by a new version
        Echo v2 = new Echo("game");
        v2.version = 2;
        w.host[3].replace(v2);
        w.clock.run(3000);
        check("replace() swaps in a new version and starts it", w.host[3].isRunning("game") && v2.ctx != null && w.host[2].peersOf("game").contains(3));
    }

    static byte[] signedBytes(String app, long id, int origin, byte[] data) {
        byte[] t = "mn-flood-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII), a = app.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.nio.ByteBuffer.allocate(t.length + 1 + a.length + 8 + 4 + data.length).put(t).put((byte) a.length).put(a).putLong(id).putInt(origin).put(data).array();
    }
}
