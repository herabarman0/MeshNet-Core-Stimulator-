package com.herabarman.meshnet.core;

import static com.herabarman.meshnet.core.TestKit.check;
import static com.herabarman.meshnet.core.TestKit.info;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Offline updates: Merkle proofs, signed manifests, spreading over a multi-hop network, hostile neighbours. */
public final class OtaTests {
    public static void main(String[] args) {
        merkle();
        manifest();
        swarm();
        hostile();
        policyAndUpgrade();
        resume();
        TestKit.summary();
    }

    static byte[] randomFile(int n, long seed) { byte[] b = new byte[n]; new Random(seed).nextBytes(b); return b; }

    static void merkle() {
        boolean allOk = true, tamperCaught = true, moveCaught = true;
        int[] sizes = {1, 63, 64, 65, 1000, 1024 * 7, 1024 * 8 + 1, 100_000};
        for (int size : sizes) {
            byte[] f = randomFile(size, size);
            int cs = 64;
            MerkleTree t = new MerkleTree(f, cs);
            for (int i = 0; i < t.chunkCount; i += Math.max(1, t.chunkCount / 40)) {
                int from = i * cs, len = Math.min(cs, size - from);
                byte[] p = t.proof(i);
                if (!MerkleTree.verify(t.root(), i, t.chunkCount, f, from, len, p)) allOk = false;
                byte[] bad = f.clone();
                bad[from] ^= 1;
                if (MerkleTree.verify(t.root(), i, t.chunkCount, bad, from, len, p)) tamperCaught = false;
                if (t.chunkCount > 1) {
                    int j = (i + 1) % t.chunkCount, jf = j * cs, jl = Math.min(cs, size - jf);
                    if (MerkleTree.verify(t.root(), j, t.chunkCount, f, from, len, p) && jl == len) moveCaught = false;   // right data, wrong slot
                }
            }
        }
        check("every chunk of files of many sizes verifies against the root", allOk);
        check("a chunk with one flipped bit is rejected", tamperCaught);
        check("a chunk presented at the wrong position is rejected", moveCaught);
    }

    static OtaManifest release(SignedRecordTests.EcAuth pub, String app, int ver, byte[] file, int cs) {
        return OtaManifest.create(app, ver, "v" + ver, file, cs, pub);
    }

    static void manifest() {
        SignedRecordTests.EcAuth pub = new SignedRecordTests.EcAuth(), evil = new SignedRecordTests.EcAuth();
        byte[] f = randomFile(5000, 1);
        OtaManifest m = release(pub, "com.example.chat", 5, f, 512);
        OtaManifest back = OtaManifest.decode(m.encode());
        check("a manifest survives encode/decode with a valid signature", back != null && back.signatureValid(pub) && back.versionCode == 5 && back.chunkCount() == 10);
        OtaManifest forgedVer = new OtaManifest(m.appId, 99, m.versionName, m.size, m.chunkSize, m.root, m.publisherKey, m.signature);
        check("changing the version number breaks the signature", !forgedVer.signatureValid(pub));
        byte[] root2 = m.root.clone(); root2[0] ^= 1;
        check("changing the content root breaks the signature", !new OtaManifest(m.appId, 5, m.versionName, m.size, m.chunkSize, root2, m.publisherKey, m.signature).signatureValid(pub));
        check("garbage does not decode", OtaManifest.decode(new byte[]{1, 2, 3}) == null);

        // what a phone accepts
        OtaSwarm.Config cfg = new OtaSwarm.Config();
        cfg.appId = "com.example.chat"; cfg.installedVersion = 5; cfg.trustedPublishers.add(pub.publicKey());
        TestKit.Clk clock = new TestKit.Clk();
        OtaSwarm s = new OtaSwarm(1, cfg, clock, new Random(1), new OtaSwarm.Link() {
            @Override public void send(int peer, byte[] m2) { }
            @Override public List<Integer> peers() { return new ArrayList<>(); }
        }, pub);
        check("the release that is already installed is not taken (no rollback, no loop)", !s.seed(m, f));
        check("an older release is not taken (rollback protection)", !s.seed(release(pub, "com.example.chat", 4, f, 512), f));
        check("a release signed by an unknown publisher is not taken", !s.seed(release(evil, "com.example.chat", 6, f, 512), f));
        check("a release of another app is not taken", !s.seed(release(pub, "com.other", 6, f, 512), f));
        check("a newer genuine release is taken", s.seed(release(pub, "com.example.chat", 6, f, 512), f));
        byte[] wrong = f.clone(); wrong[100] ^= 1;
        OtaSwarm s2 = new OtaSwarm(2, cfg, clock, new Random(1), new OtaSwarm.Link() {
            @Override public void send(int peer, byte[] m2) { }
            @Override public List<Integer> peers() { return new ArrayList<>(); }
        }, pub);
        check("a file that does not match the signed root is not seeded", !s2.seed(release(pub, "com.example.chat", 6, f, 512), wrong));
    }

    /** n phones; adj lists; optional hostile phone ids that answer every request with garbage. */
    static final class Net {
        final TestKit.Clk clock = new TestKit.Clk();
        final Random rng = new Random(7);
        final int n;
        final OtaSwarm[] sw;
        final List<List<Integer>> adj = new ArrayList<>();
        double loss;
        final boolean[] hostile;
        final byte[][] done;
        int messages;

        Net(int n, OtaSwarm.Config base, SignedRecordTests.EcAuth verifier, int[] hostileIds) {
            this.n = n;
            sw = new OtaSwarm[n];
            hostile = new boolean[n];
            done = new byte[n][];
            for (int h : hostileIds) hostile[h] = true;
            for (int i = 0; i < n; i++) adj.add(new ArrayList<Integer>());
            for (int i = 0; i + 1 < n; i++) { adj.get(i).add(i + 1); adj.get(i + 1).add(i); }
            for (int i = 0; i < n; i++) {
                final int me = i;
                OtaSwarm.Config c = new OtaSwarm.Config();
                c.appId = base.appId; c.installedVersion = base.installedVersion; c.trustedPublishers = base.trustedPublishers;
                sw[i] = new OtaSwarm(i, c, clock, new Random(50 + i), new OtaSwarm.Link() {
                    @Override public void send(final int peer, final byte[] m) {
                        messages++;
                        if (loss > 0 && rng.nextDouble() < loss) return;
                        clock.schedule(20 + rng.nextInt(40), new Runnable() { @Override public void run() { deliver(me, peer, m); } });
                    }
                    @Override public List<Integer> peers() { return adj.get(me); }
                }, verifier);
                sw[i].setListener(new OtaSwarm.Listener() {
                    @Override public void onAvailable(OtaManifest m) { }
                    @Override public void onProgress(OtaManifest m, int have, int total) { }
                    @Override public void onComplete(OtaManifest m, byte[] file) { done[me] = file; }
                });
            }
        }

        void deliver(int from, int to, byte[] m) {
            if (hostile[to]) {                                   // a hostile phone: offers nothing real, answers requests with junk
                if ((m[0] & 0xFF) == 2) {
                    ByteBuffer b = ByteBuffer.wrap(m, 1, m.length - 1);
                    byte[] root = new byte[MerkleTree.HASH]; b.get(root);
                    int cnt = b.get() & 0xFF;
                    for (int i = 0; i < cnt; i++) {
                        int idx = b.getInt();
                        byte[] junk = new byte[512]; rng.nextBytes(junk);
                        byte[] resp = ByteBuffer.allocate(1 + 16 + 4 + 2 + 512 + 1 + 32).put((byte) 3).put(root).putInt(idx).putShort((short) 512).put(junk).put((byte) 32).put(new byte[32]).array();
                        sw[to].onMessage(from, new byte[0]);   // no-op, keeps the node object untouched
                        final byte[] r2 = resp;
                        final int f = from, t = to;
                        clock.schedule(10, new Runnable() { @Override public void run() { sw[f].onMessage(t, r2); } });
                    }
                    return;
                }
            }
            sw[to].onMessage(from, m);
        }
    }

    static void swarm() {
        SignedRecordTests.EcAuth pub = new SignedRecordTests.EcAuth();
        OtaSwarm.Config base = new OtaSwarm.Config();
        base.appId = "com.example.chat"; base.installedVersion = 1; base.trustedPublishers.add(pub.publicKey());
        byte[] apk = randomFile(150_000, 99);
        OtaManifest m = release(pub, base.appId, 2, apk, 1024);
        Net net = new Net(8, base, pub, new int[0]);
        net.loss = 0.15;
        for (OtaSwarm s : net.sw) s.start();
        net.sw[0].seed(m, apk);
        long t = 0;
        while (t < 30 * 60_000L) {
            net.clock.run(5_000);
            t += 5_000;
            boolean all = true;
            for (int i = 1; i < net.n; i++) if (net.done[i] == null) all = false;
            if (all) break;
        }
        int ok = 0;
        for (int i = 1; i < net.n; i++) if (net.done[i] != null && Arrays.equals(net.done[i], apk)) ok++;
        long served = 0;
        for (int i = 0; i < net.n; i++) served += net.sw[i].chunksServed;
        int middleServed = 0;
        for (int i = 1; i < net.n - 1; i++) middleServed += net.sw[i].chunksServed > 0 ? 1 : 0;
        info(String.format("OTA, 8 phones in a line, 15%% loss, 150 KB: %d of 7 updated after %d s; %d chunks sent in total, %d relay phones served others",
                ok, t / 1000, served, middleServed));
        check("every phone in the line ends up with the exact new file", ok == 7);
        check("the update spread phone to phone (relay phones served chunks, not only the source)", middleServed >= 5);
    }

    static void hostile() {
        SignedRecordTests.EcAuth pub = new SignedRecordTests.EcAuth(), evil = new SignedRecordTests.EcAuth();
        OtaSwarm.Config base = new OtaSwarm.Config();
        base.appId = "app"; base.installedVersion = 1; base.trustedPublishers.add(pub.publicKey());
        byte[] apk = randomFile(60_000, 5);
        OtaManifest m = release(pub, "app", 2, apk, 512);
        // 0 = source, 1 = hostile, 2 = victim; victim has two neighbours: hostile 1 and honest 3; 3 gets it from 0 via 4
        Net net = new Net(5, base, pub, new int[]{1});
        net.adj.get(0).clear(); net.adj.get(1).clear(); net.adj.get(2).clear(); net.adj.get(3).clear(); net.adj.get(4).clear();
        int[][] edges = {{0, 4}, {4, 3}, {3, 2}, {1, 2}, {1, 0}};
        for (int[] e : edges) { net.adj.get(e[0]).add(e[1]); net.adj.get(e[1]).add(e[0]); }
        for (OtaSwarm s : net.sw) s.start();
        // the hostile phone claims to be a complete holder of the genuine release (it just replays the source's offer)
        net.sw[0].seed(m, apk);
        for (int i = 0; i < 40; i++) { net.clock.run(5_000); }
        check("a phone fed junk chunks by a neighbour still gets the right file from the honest ones", net.done[2] != null && Arrays.equals(net.done[2], apk));
        check("junk chunks were detected and counted (" + net.sw[2].badChunks + ")", net.sw[2].badChunks > 0);

        // forged offer: untrusted publisher
        TestKit.Clk clock = new TestKit.Clk();
        final int[] avail = {0};
        OtaSwarm victim = new OtaSwarm(9, base, clock, new Random(3), new OtaSwarm.Link() {
            @Override public void send(int peer, byte[] x) { }
            @Override public List<Integer> peers() { return new ArrayList<>(); }
        }, pub);
        victim.setListener(new OtaSwarm.Listener() {
            @Override public void onAvailable(OtaManifest mm) { avail[0]++; }
            @Override public void onProgress(OtaManifest mm, int h, int t) { }
            @Override public void onComplete(OtaManifest mm, byte[] f) { avail[0] += 100; }
        });
        OtaManifest forged = release(evil, "app", 50, apk, 512);
        byte[] mb = forged.encode();
        byte[] offer = ByteBuffer.allocate(1 + 2 + mb.length + 1).put((byte) 1).putShort((short) mb.length).put(mb).put((byte) 0).array();
        victim.onMessage(4, offer);
        check("an offer signed by an unknown publisher is ignored", avail[0] == 0 && victim.target() == null && victim.manifestsRejected == 1);
        byte[] gm = m.encode();
        byte[] goodOffer = ByteBuffer.allocate(1 + 2 + gm.length + 1).put((byte) 1).putShort((short) gm.length).put(gm).put((byte) 0).array();
        victim.onMessage(5, goodOffer);
        check("a genuine offer announces the update", avail[0] == 1 && victim.target() != null);
    }

    static void policyAndUpgrade() {
        SignedRecordTests.EcAuth pub = new SignedRecordTests.EcAuth();
        OtaSwarm.Config base = new OtaSwarm.Config();
        base.appId = "app"; base.installedVersion = 1; base.trustedPublishers.add(pub.publicKey());
        byte[] v2 = randomFile(30_000, 8), v3 = randomFile(30_000, 9);
        Net net = new Net(3, base, pub, new int[0]);
        final boolean[] allow = {false};
        net.sw[2].setPolicy(new OtaSwarm.Policy() { @Override public boolean mayDownload() { return allow[0]; } });
        for (OtaSwarm s : net.sw) s.start();
        net.sw[0].seed(release(pub, "app", 2, v2, 512), v2);
        net.clock.run(60_000);
        check("a phone with a low battery (policy says no) does not download", net.sw[2].haveChunks() == 0 && net.done[2] == null);
        check("the phone that may download does", net.done[1] != null);
        allow[0] = true;
        net.clock.run(60_000);
        check("once allowed it gets the update", net.done[2] != null && Arrays.equals(net.done[2], v2));
        net.sw[0].seed(release(pub, "app", 3, v3, 512), v3);
        net.clock.run(120_000);
        check("a newer release replaces the older one everywhere", net.sw[2].target().versionCode == 3 && Arrays.equals(net.sw[2].file(), v3) && Arrays.equals(net.sw[1].file(), v3));
    }

    static OtaSwarm freshSwarm(final Net net, final int me, OtaSwarm.Config base, SignedRecordTests.EcAuth pub, StorageAdapter st) {
        OtaSwarm.Config c = new OtaSwarm.Config();
        c.appId = base.appId; c.installedVersion = base.installedVersion; c.trustedPublishers = base.trustedPublishers;
        OtaSwarm s = new OtaSwarm(me, c, net.clock, new Random(500 + me), new OtaSwarm.Link() {
            @Override public void send(final int peer, final byte[] m) {
                net.clock.schedule(20, new Runnable() { @Override public void run() { net.deliver(me, peer, m); } });
            }
            @Override public List<Integer> peers() { return net.adj.get(me); }
        }, pub);
        s.setListener(new OtaSwarm.Listener() {
            @Override public void onAvailable(OtaManifest m) { }
            @Override public void onProgress(OtaManifest m, int have, int total) { }
            @Override public void onComplete(OtaManifest m, byte[] file) { net.done[me] = file; }
        });
        s.setStorage(st);
        return s;
    }

    /** A download interrupted by an app restart or reboot continues where it stopped. */
    static void resume() {
        SignedRecordTests.EcAuth pub = new SignedRecordTests.EcAuth();
        OtaSwarm.Config base = new OtaSwarm.Config();
        base.appId = "com.example.chat"; base.installedVersion = 1; base.trustedPublishers.add(pub.publicKey());
        byte[] apk = randomFile(200_000, 31);
        OtaManifest m = release(pub, base.appId, 2, apk, 1024);
        int total = m.chunkCount();
        Net net = new Net(2, base, pub, new int[0]);
        MemoryStorage mem = new MemoryStorage();
        net.sw[1] = freshSwarm(net, 1, base, pub, mem);
        for (OtaSwarm s : net.sw) s.start();
        net.sw[0].seed(m, apk);
        for (int i = 0; i < 400 && net.sw[1].haveChunks() < total * 4 / 10; i++) net.clock.run(100);
        int before = net.sw[1].haveChunks();
        check("the download is part-way when the phone 'restarts' (" + before + "/" + total + " chunks)", before >= total * 4 / 10 && before < total);
        net.sw[1].stop();
        long servedBefore = net.sw[0].chunksServed;
        // corrupt one saved chunk: it must be thrown away on restore, not trusted
        String victimKey = mem.keys("ota.c.").get(0);
        byte[] bad = mem.get(victimKey);
        bad[bad.length - 1] ^= 1;
        mem.put(victimKey, bad);
        OtaSwarm reborn = freshSwarm(net, 1, base, pub, mem);
        net.sw[1] = reborn;
        check("after the restart the saved download is found again", reborn.resume() && reborn.target() != null && reborn.target().versionCode == 2);
        check("verified chunks are restored, the damaged one is dropped (" + reborn.haveChunks() + " of " + before + ")", reborn.haveChunks() == before - 1 && reborn.chunksRestored == before - 1);
        reborn.start();
        for (int i = 0; i < 600 && net.done[1] == null; i++) net.clock.run(100);
        check("it finishes with the exact file", net.done[1] != null && Arrays.equals(net.done[1], apk));
        long served = net.sw[0].chunksServed - servedBefore;
        check("only the missing part was fetched again (" + served + " chunks, not " + total + ")", served <= total - before + 5);
        check("saved pieces are removed once the release is complete", mem.keys("ota.").isEmpty());

        // a saved download of a release that is no longer acceptable (the app was upgraded meanwhile) is discarded
        MemoryStorage mem2 = new MemoryStorage();
        mem2.put("ota.m." + base.appId, m.encode());
        mem2.put("ota.c." + base.appId + ".0", new byte[]{0, 1, 2});
        OtaSwarm.Config upgraded = new OtaSwarm.Config();
        upgraded.appId = base.appId; upgraded.installedVersion = 2; upgraded.trustedPublishers = base.trustedPublishers;
        Net n2 = new Net(2, upgraded, pub, new int[0]);
        OtaSwarm late = freshSwarm(n2, 1, upgraded, pub, mem2);
        check("a saved download for a version already installed is discarded", !late.resume() && mem2.keys("ota.").isEmpty());
    }
}
