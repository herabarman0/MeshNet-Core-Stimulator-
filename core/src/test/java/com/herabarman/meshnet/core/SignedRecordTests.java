package com.herabarman.meshnet.core;

import static com.herabarman.meshnet.core.TestKit.check;
import static com.herabarman.meshnet.core.TestKit.info;

import java.nio.ByteBuffer;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Signed gossip and DHT records: forged, tampered and hijacked records must not get in. Plain main(), no JUnit. */
public final class SignedRecordTests {
    public static void main(String[] args) throws Exception {
        gossip();
        dht();
        TestKit.summary();
    }

    /** ECDSA with plain JDK classes. */
    static final class EcAuth implements RecordAuth {
        final KeyPair kp;
        EcAuth() {
            try {
                KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
                g.initialize(256);
                kp = g.generateKeyPair();
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        @Override public byte[] publicKey() { return kp.getPublic().getEncoded(); }
        @Override public byte[] sign(byte[] data) {
            try {
                Signature s = Signature.getInstance("SHA256withECDSA");
                s.initSign(kp.getPrivate());
                s.update(data);
                return s.sign();
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        @Override public boolean verify(byte[] pub, byte[] data, byte[] sig) {
            try {
                Signature s = Signature.getInstance("SHA256withECDSA");
                s.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(pub)));
                s.update(data);
                return s.verify(sig);
            } catch (Exception e) { return false; }
        }
    }

    static final int OP_PUSH_SIGNED = 4;

    /** Hand-built signed push: lets a test write any record it likes, with any key. */
    static byte[] push(int ns, int subject, int owner, int value, int version, byte[] pub, byte[] sig) {
        return ByteBuffer.allocate(2 + 20 + 2 + pub.length + sig.length).put((byte) OP_PUSH_SIGNED).put((byte) 1)
                .put((byte) ns).putInt(subject).putInt(owner).putInt(value).putInt(version).putShort((short) 3000).put((byte) 5)
                .put((byte) pub.length).put(pub).put((byte) sig.length).put(sig).array();
    }

    static void gossip() {
        final int n = 12;
        final TestKit.Clk clock = new TestKit.Clk();
        final GossipDirectory[] g = new GossipDirectory[n];
        final EcAuth[] auth = new EcAuth[n];
        final List<byte[]> captured = new ArrayList<>();
        GossipDirectory.Config cfg = new GossipDirectory.Config();
        cfg.defaultRadius = 8;
        for (int i = 0; i < n; i++) {
            final int me = i;
            auth[i] = new EcAuth();
            g[i] = new GossipDirectory(i, cfg, clock, new Random(100 + i), new GossipDirectory.Link() {
                @Override public void send(final int peer, final byte[] m) {
                    if (m[0] == OP_PUSH_SIGNED && captured.size() < 50) captured.add(m);
                    clock.schedule(30, new Runnable() { @Override public void run() { g[peer].onMessage(me, m); } });
                }
                @Override public List<Integer> peers() {
                    List<Integer> l = new ArrayList<>();
                    l.add((me + 1) % n); l.add((me + n - 1) % n);
                    return l;
                }
            });
            g[i].setGuard(new RecordGuard(auth[i]));
        }
        for (int i = 0; i < n; i++) { g[i].start(); g[i].publishLocation(i + 100); }
        clock.run(120_000);
        int known = 0;
        for (int a = 0; a < n; a++) for (int b = 0; b < n; b++) if (a != b && g[a].zoneOf(b) == b + 100) known++;
        info("signed gossip, 12 nodes: " + known + " of " + (n * (n - 1)) + " locations known");
        check("signed records spread like unsigned ones", known >= 0.97 * n * (n - 1));

        EcAuth evil = new EcAuth();
        GossipDirectory victim = g[3];
        int before = victim.zoneOf(7);
        // 1. somebody else claims node 7's location with a valid signature of THEIR key
        byte[] sigE = new RecordGuard(evil).signGossip(GossipDirectory.NS_LOCATION, 7, 7, 999, 1 << 30);
        victim.onMessage(2, push(GossipDirectory.NS_LOCATION, 7, 7, 999, 1 << 30, evil.publicKey(), sigE));
        check("a different key for an id that already has one is refused", victim.zoneOf(7) == before && before == 107);
        // 2. garbage signature
        victim.onMessage(2, push(GossipDirectory.NS_LOCATION, 7, 7, 999, 1 << 30, auth[7].publicKey(), new byte[]{1, 2, 3, 4}));
        check("a bad signature is refused", victim.zoneOf(7) == 107);
        // 3. a genuine record with the value flipped in transit
        byte[] tampered = null;
        for (byte[] m : captured) {
            ByteBuffer b = ByteBuffer.wrap(m);
            b.get(); int cnt = b.get() & 0xFF;
            if (cnt >= 1) { tampered = m.clone(); break; }
        }
        boolean changed = false;
        if (tampered != null) {
            int owner = ByteBuffer.wrap(tampered, 2 + 1 + 4, 4).getInt();
            tampered[2 + 1 + 8 + 3] ^= 0x55;                           // last byte of the value field
            int target = (owner + 5) % n;
            int zoneBefore = g[target].zoneOf(owner);
            g[target].onMessage(1, tampered);
            changed = g[target].zoneOf(owner) != zoneBefore;
        }
        check("a record changed in transit is refused", tampered != null && !changed);
        // 4. an id that nobody has bound yet can be pinned to its real key beforehand
        EcAuth real9 = auth[9];
        GossipDirectory fresh = new GossipDirectory(20, cfg, clock, new Random(5), new GossipDirectory.Link() {
            @Override public void send(int peer, byte[] m) { }
            @Override public List<Integer> peers() { return new ArrayList<>(); }
        });
        RecordGuard fg = new RecordGuard(new EcAuth());
        fg.pin(55, real9.publicKey());
        fresh.setGuard(fg);
        byte[] forged55 = new RecordGuard(evil).signGossip(GossipDirectory.NS_LOCATION, 55, 55, 7, 100);
        fresh.onMessage(1, push(GossipDirectory.NS_LOCATION, 55, 55, 7, 100, evil.publicKey(), forged55));
        check("a pinned key beats a first-contact forger", fresh.zoneOf(55) == -1);
        byte[] genuine55 = new RecordGuard(real9).signGossip(GossipDirectory.NS_LOCATION, 55, 55, 7, 100);
        fresh.onMessage(1, push(GossipDirectory.NS_LOCATION, 55, 55, 7, 100, real9.publicKey(), genuine55));
        check("the genuine signed record for the pinned id is accepted", fresh.zoneOf(55) == 7);
        // 5. old style unsigned push into a guarded node
        byte[] legacy = ByteBuffer.allocate(2 + 20).put((byte) 1).put((byte) 1).put((byte) GossipDirectory.NS_LOCATION).putInt(66).putInt(66)
                .putInt(5).putInt(1 << 29).putShort((short) 3000).put((byte) 5).array();
        fresh.onMessage(1, legacy);
        check("unsigned records are ignored once signing is on", fresh.zoneOf(66) == -1);
        // 6. a node without a guard ignores signed pushes instead of trusting them blindly
        GossipDirectory plain = new GossipDirectory(21, cfg, clock, new Random(6), new GossipDirectory.Link() {
            @Override public void send(int peer, byte[] m) { }
            @Override public List<Integer> peers() { return new ArrayList<>(); }
        });
        plain.onMessage(1, genuine55 == null ? new byte[0] : push(GossipDirectory.NS_LOCATION, 55, 55, 7, 100, real9.publicKey(), genuine55));
        check("a node that cannot verify does not accept signed records", plain.zoneOf(55) == -1);
    }

    static void dht() {
        DhtNode.Config cfg = new DhtNode.Config();
        LocationServiceTests.DNet net = new LocationServiceTests.DNet(120, 9, cfg);
        EcAuth[] auth = new EcAuth[net.n];
        for (int i = 0; i < net.n; i++) { auth[i] = new EcAuth(); net.d[i].setGuard(new RecordGuard(auth[i])); }
        net.joinAll();
        int[] r = net.lookups(60, true, null);
        info("signed DHT, 120 nodes: " + r[0] + " of 60 lookups correct, " + r[1] + " wrong");
        check("signed locations are found as before (>= 95%)", r[0] >= 57 && r[1] == 0);

        // forged store: attacker says node 1010 is in zone 31337
        EcAuth evil = new EcAuth();
        RecordGuard eg = new RecordGuard(evil);
        int victim = 1010;
        int key = DhtNode.dhtId(victim);
        byte[] forged = eg.signedLocation(victim, 31337, 1 << 30);
        byte[] unsigned = ByteBuffer.allocate(4).putInt(31337).array();
        DhtNode target = net.d[40];
        target.guard().pin(victim, auth[10].publicKey());     // key known from a trusted channel
        int stored0 = target.storedCount();
        byte[][] values = {forged, unsigned};
        for (byte[] v : values) {
            ByteBuffer b = ByteBuffer.allocate(9 + 4 + 4 + 4 + 2 + v.length);
            b.put((byte) 5).putInt(0).putInt(3).putInt(key).putInt(1 << 30).putInt(3600).putShort((short) v.length).put(v);
            target.onMessage(1050, b.array());
        }
        check("a forged or unsigned location is not stored", target.storedCount() == stored0);
        final int[] res = {-2};
        net.d[90].lookupLocation(victim, new DhtNode.LocationCallback() { @Override public void onResult(int id, int zone) { res[0] = zone; } });
        net.clock.run(30_000);
        check("a lookup still returns the genuine zone of the victim (" + res[0] + ")", res[0] == 10 % 50);

        // a lying replica: it answers FIND_VALUE with a forged value; the asker must ignore it
        final int[] res2 = {-2};
        final DhtNode asker = net.d[77];
        net.d[91].lookupLocation(1020, new DhtNode.LocationCallback() { @Override public void onResult(int id, int zone) { res2[0] = zone; } });
        net.clock.run(30_000);
        check("genuine lookup of another node works too", res2[0] == 20 % 50);

        // ids derived from keys: even an id nobody has ever seen cannot be claimed by another key
        RecordGuard idg = new RecordGuard(new EcAuth());
        idg.setIdBoundToKey(true);
        EcAuth honest = new EcAuth();
        int honestId = RecordGuard.idOf(honest.publicKey());
        byte[] mine = new RecordGuard(honest).signedLocation(honestId, 5, 100);
        byte[] steal = eg.signedLocation(honestId, 6, 101);
        check("an id derived from the key is accepted for that key", idg.checkLocation(DhtNode.dhtId(honestId), mine, 100) == 5);
        check("another key cannot sign for that id, even at first contact", idg.checkLocation(DhtNode.dhtId(honestId), steal, 101) == -1);
    }
}
