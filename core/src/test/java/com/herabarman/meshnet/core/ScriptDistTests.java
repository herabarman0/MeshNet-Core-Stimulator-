package com.herabarman.meshnet.core;

import static com.herabarman.meshnet.core.TestKit.check;
import static com.herabarman.meshnet.core.TestKit.info;

import com.herabarman.meshnet.core.script.ScriptApp;
import com.herabarman.meshnet.core.script.ScriptDistributor;
import com.herabarman.meshnet.core.script.ScriptPackage;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Script mini-apps travelling phone to phone with no server: install, upgrade, hostile publishers, restart. */
public final class ScriptDistTests {
    static final String V1 = "function onStart() { mesh.log(\"v1\"); }";
    static String big(int ver) {
        StringBuilder b = new StringBuilder("function onStart() { mesh.log(\"v" + ver + "\"); }\n");
        for (int i = 0; i < 120; i++) b.append("// padding line ").append(i).append(" of the quiz question bank, so the package spans many chunks\n");
        return b.toString();
    }

    static final class Net {
        final TestKit.Clk clock = new TestKit.Clk();
        final Random rng = new Random(5);
        final ScriptDistributor[] d;
        final List<List<Integer>> adj = new ArrayList<>();
        final List<String>[] installed;
        final MemoryStorage[] store;
        double loss;

        @SuppressWarnings("unchecked")
        Net(int n, final SignedRecordTests.EcAuth pub, final List<byte[]> trusted) {
            d = new ScriptDistributor[n];
            installed = new List[n];
            store = new MemoryStorage[n];
            for (int i = 0; i < n; i++) adj.add(new ArrayList<Integer>());
            for (int i = 0; i + 1 < n; i++) { adj.get(i).add(i + 1); adj.get(i + 1).add(i); }
            for (int i = 0; i < n; i++) { installed[i] = new ArrayList<>(); store[i] = new MemoryStorage(); make(i, pub, trusted); }
        }

        void make(final int me, SignedRecordTests.EcAuth pub, List<byte[]> trusted) {
            ScriptDistributor.Config c = new ScriptDistributor.Config();
            c.trustedPublishers = trusted;
            d[me] = new ScriptDistributor(me, c, clock, new Random(90 + me), new ScriptDistributor.Link() {
                @Override public void send(final int peer, final byte[] m) {
                    if (loss > 0 && rng.nextDouble() < loss) return;
                    clock.schedule(20 + rng.nextInt(30), new Runnable() { @Override public void run() { if (adj.get(me).contains(peer)) d[peer].onMessage(me, m); } });
                }
                @Override public List<Integer> peers() { return adj.get(me); }
            }, pub, store[me]);
            d[me].setListener(new ScriptDistributor.Listener() {
                @Override public void onInstalled(ScriptApp app, ScriptPackage pkg) { installed[me].add(pkg.id + "@" + pkg.version); }
                @Override public void onProgress(String id, int have, int total) { }
                @Override public void onRejected(String id, String why) { installed[me].add("rejected:" + why); }
            });
        }

        void run(long ms) { clock.run(ms); }
    }

    public static void main(String[] args) {
        SignedRecordTests.EcAuth pub = new SignedRecordTests.EcAuth(), evil = new SignedRecordTests.EcAuth();
        List<byte[]> trusted = new ArrayList<>();
        trusted.add(pub.publicKey());
        Net net = new Net(6, pub, trusted);
        net.loss = 0.1;
        for (ScriptDistributor x : net.d) x.start();

        String src = big(1);
        ScriptPackage p1 = ScriptPackage.create("com.example.quiz", 1, src, pub);
        OtaManifest m1 = OtaManifest.create("com.example.quiz", 1, "v1", p1.encode(), 512, pub);
        check("the publisher's phone installs its own package", net.d[0].publish(p1, m1) != null && net.d[0].installedVersion("com.example.quiz") == 1);
        for (int i = 0; i < 6; i++) net.d[i].onPeerUp(i == 0 ? 1 : i - 1);
        net.run(10 * 60_000);
        int ok = 0;
        for (int i = 1; i < 6; i++) if (net.installed[i].contains("com.example.quiz@1")) ok++;
        info("script app (" + p1.encode().length / 1024 + " KB) over 6 phones in a line, 10% loss: installed on " + ok + " of 5");
        check("a new script app reaches every phone in a line with no server", ok == 5);
        check("phones that never saw the publisher serve it onward", net.d[4].installedVersion("com.example.quiz") == 1);

        // upgrade
        ScriptPackage p2 = ScriptPackage.create("com.example.quiz", 2, big(2), pub);
        OtaManifest m2 = OtaManifest.create("com.example.quiz", 2, "v2", p2.encode(), 512, pub);
        net.d[0].publish(p2, m2);
        net.run(10 * 60_000);
        ok = 0;
        for (int i = 1; i < 6; i++) if (net.installed[i].contains("com.example.quiz@2")) ok++;
        check("a new version replaces the old one everywhere (" + ok + " of 5)", ok == 5);

        // hostile publisher: a package signed by someone nobody trusts never gets installed or relayed
        ScriptPackage bad = ScriptPackage.create("com.evil.app", 1, "function onStart() { }", evil);
        OtaManifest badM = OtaManifest.create("com.evil.app", 1, "v1", bad.encode(), 512, evil);
        net.d[0].publish(bad, badM);
        check("the publisher's own phone refuses to publish an untrusted package", net.d[0].installedVersion("com.evil.app") == 0 && net.installed[0].contains("rejected:UNTRUSTED"));
        Net n2 = new Net(3, pub, trusted);                          // phone 0 here trusts the evil key (a compromised phone), phones 1 and 2 do not
        List<byte[]> weak = new ArrayList<>();
        weak.add(evil.publicKey());
        weak.add(pub.publicKey());
        ScriptDistributor.Config c0 = new ScriptDistributor.Config();
        c0.trustedPublishers = weak;
        n2.d[0] = new ScriptDistributor(0, c0, n2.clock, new Random(1), new ScriptDistributor.Link() {
            @Override public void send(final int peer, final byte[] m) { n2Send(n2Holder[0], peer, m); }
            @Override public List<Integer> peers() { return n2Holder[0].adj.get(0); }
        }, pub, new MemoryStorage());
        n2Holder[0] = n2;
        for (ScriptDistributor x : n2.d) x.start();
        n2.d[0].publish(bad, badM);
        n2.d[0].onPeerUp(1);
        n2.run(2 * 60_000);
        check("a package from an untrusted publisher is not installed by phones that do not trust it", n2.d[1].installedVersion("com.evil.app") == 0 && n2.d[2].installedVersion("com.evil.app") == 0);

        // downgrade: an old signed package offered again is ignored
        int before = net.installed[3].size();
        net.d[0].onPeerUp(1);
        net.run(60_000);
        check("an old release is never installed again", net.d[3].installedVersion("com.example.quiz") == 2 && net.installed[3].size() == before);

        // restart: a phone restores installed apps and keeps serving them
        Net n3 = new Net(2, pub, trusted);
        for (ScriptDistributor x : n3.d) x.start();
        n3.d[0].publish(p1, m1);
        n3.d[0].onPeerUp(1);
        n3.run(5 * 60_000);
        check("second phone installed", n3.d[1].installedVersion("com.example.quiz") == 1);
        n3.make(1, pub, trusted);                                   // app restart: new objects, same storage
        List<ScriptApp> back = n3.d[1].restore();
        n3.d[1].start();
        check("after a restart the installed script app is restored from storage, re-verified", back.size() == 1 && back.get(0).id().equals("com.example.quiz") && n3.d[1].installedVersion("com.example.quiz") == 1);
        Net n4 = new Net(3, pub, trusted);                          // restored phone 1 serves a brand-new neighbour
        n4.store[1] = n3.store[1];
        n4.make(1, pub, trusted);
        n4.d[1].restore();
        for (ScriptDistributor x : n4.d) x.start();
        n4.adj.get(0).clear(); n4.adj.get(1).clear(); n4.adj.get(2).clear();
        n4.adj.get(1).add(2); n4.adj.get(2).add(1);
        n4.d[1].onPeerUp(2); n4.d[2].onPeerUp(1);
        n4.run(5 * 60_000);
        check("a restored phone serves the app to a newcomer", n4.d[2].installedVersion("com.example.quiz") == 1);

        // hostile flood of unknown app ids is capped
        Net n5 = new Net(2, pub, trusted);
        n5.d[1].start();
        for (int i = 0; i < 100; i++) {
            ScriptPackage px = ScriptPackage.create("app." + i, 1, "var a = 1;", pub);
            OtaManifest mx = OtaManifest.create("app." + i, 1, "v1", px.encode(), 512, pub);
            byte[] id = ("app." + i).getBytes();
            byte[] offer = new byte[1 + id.length + 1 + 2 + mx.encode().length + 1 + 0];
            offer[0] = (byte) id.length;
            System.arraycopy(id, 0, offer, 1, id.length);
            int o = 1 + id.length;
            offer[o++] = 1;
            offer[o++] = (byte) (mx.encode().length >> 8); offer[o++] = (byte) mx.encode().length;
            System.arraycopy(mx.encode(), 0, offer, o, mx.encode().length);
            n5.d[1].onMessage(0, offer);
        }
        check("offers for a hundred app ids create at most 16 swarms (" + n5.d[1].known().size() + ")", n5.d[1].known().size() <= 16);
        TestKit.summary();
    }

    static final Net[] n2Holder = new Net[1];

    static void n2Send(final Net n, final int peer, final byte[] m) {
        n.clock.schedule(25, new Runnable() { @Override public void run() { if (n.adj.get(0).contains(peer)) n.d[peer].onMessage(0, m); } });
    }
}
