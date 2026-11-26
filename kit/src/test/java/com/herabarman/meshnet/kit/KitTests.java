package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.MemoryStorage;
import com.herabarman.meshnet.core.Priority;
import com.herabarman.meshnet.core.StorageAdapter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Plain-Java test runner (no JUnit needed): java -cp ... com.herabarman.meshnet.kit.KitTests */
public final class KitTests {
    public static int pass, fail;

    public static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("PASS  " + name); }
        else { fail++; System.out.println("FAIL  " + name); }
    }

    public static boolean waitFor(BooleanSupplier c, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) return true;
            try { Thread.sleep(20); } catch (InterruptedException e) { return false; }
        }
        return c.getAsBoolean();
    }

    // ------------------------------------------------------------------ unit tests

    static void geo() {
        check("geohash known value (ezs42)", GeoZones.geohash(42.6, -5.6, 5).equals("ezs42"));
        check("geohash known value (u4pruy)", GeoZones.geohash(57.64911, 10.40744, 6).equals("u4pruy"));
        check("zoneCount(6) = 2^30", GeoZones.zoneCount(6) == (1 << 30));
        // Hilbert: bijection and neighbours on the curve are neighbours on the grid
        int order = 4, n = 1 << order;
        int[] xs = new int[n * n], ys = new int[n * n];
        Arrays.fill(xs, -1);
        boolean bij = true;
        for (int x = 0; x < n; x++) for (int y = 0; y < n; y++) {
            int d = GeoZones.hilbert(order, x, y);
            if (d < 0 || d >= n * n || xs[d] != -1) bij = false; else { xs[d] = x; ys[d] = y; }
        }
        check("hilbert is a bijection", bij);
        boolean adj = true;
        for (int d = 0; d + 1 < n * n; d++) if (Math.abs(xs[d] - xs[d + 1]) + Math.abs(ys[d] - ys[d + 1]) != 1) adj = false;
        check("hilbert: consecutive zones are touching cells", adj);
        int z1 = GeoZones.zoneOf(23.8103, 90.4125, 6);
        check("same point, same zone", z1 == GeoZones.zoneOf(23.8103, 90.4125, 6));
        check("zone is in range", z1 >= 0 && z1 < GeoZones.zoneCount(6));
        check("100 m away is the same or a close zone", Math.abs(z1 - GeoZones.zoneOf(23.8112, 90.4125, 6)) < 100_000);
        ZoneTracker t = new ZoneTracker(6, 1000);
        int first = t.update(23.8103, 90.4125, 0);
        check("tracker: first fix is taken at once", first == z1);
        check("tracker: no change inside the cell", t.update(23.8103, 90.4125, 10) == -1);
        int far = GeoZones.zoneOf(23.9, 90.5, 6);
        check("tracker: new cell needs a hold time", t.update(23.9, 90.5, 100) == -1 && t.update(23.9, 90.5, 600) == -1);
        check("tracker: changes after the hold", t.update(23.9, 90.5, 1200) == far);
        check("tracker: border flap is ignored", t.update(23.8103, 90.4125, 1300) == -1 && t.update(23.9, 90.5, 1400) == -1);
    }

    static void ids() {
        MemoryStorage st = new MemoryStorage();
        IdMap m = new IdMap(st);
        check("numeric id is the node id", m.nodeOf("70121") == 70121 && "70121".equals(m.userOf(70121)));
        int a = m.nodeOf("070121"), b = m.nodeOf("MESHab12cd");
        check("leading zero / MESH ids get hash ids >= 2^30", a >= IdMap.HASH_BASE && b >= IdMap.HASH_BASE && a != b);
        check("hash ids map back", "070121".equals(m.userOf(a)) && "MESHab12cd".equals(m.userOf(b)));
        IdMap m2 = new IdMap(st);
        check("hash ids survive a restart", "MESHab12cd".equals(m2.userOf(b)));
        check("node id is 31 bit (top bit free for zone addresses)", a > 0 && b > 0);
    }

    static void frames() {
        byte[] t = Frame.text(7L, 99L, "হ্যালো");
        check("text frame round trip", Frame.valid(t) && Frame.localId(t) == 7L && Frame.timestamp(t) == 99L && "হ্যালো".equals(Frame.textOf(t)));
        byte[] s = Frame.seen(new long[]{1, 2, 3});
        check("seen frame round trip", Arrays.equals(Frame.seenIds(s), new long[]{1, 2, 3}));
        check("short frame is rejected", !Frame.valid(new byte[]{(byte) Frame.TEXT, 1, 2}) && !Frame.valid(new byte[0]));
        byte[] pub = IdentityKeys.generate().publicRaw();
        check("key frame is 66 bytes", Frame.key(Frame.KEY_REQ, pub).length == 66 && Frame.valid(Frame.key(Frame.KEY_RES, pub)));
    }

    static void voiceCrypto() {   // real-time voice: static-key AES-GCM, 17 bytes overhead (text uses the Double Ratchet, see crypto())
        IdentityKeys ka = IdentityKeys.generate(), kb = IdentityKeys.generate(), ke = IdentityKeys.generate();
        KeyDirectory da = new KeyDirectory(null), db = new KeyDirectory(null), de = new KeyDirectory(null);
        da.put(2, kb.publicRaw());
        db.put(1, ka.publicRaw());
        de.put(1, ka.publicRaw());
        de.put(2, kb.publicRaw());
        E2eeCrypto ca = new E2eeCrypto(1, ka, da), cb = new E2eeCrypto(2, kb, db), cev = new E2eeCrypto(3, ke, de);
        byte[] plain = Frame.text(1, 2, "secret");
        byte[] c = ca.encryptVoice(plain, 1, 2, 42L);
        check("encryption adds exactly 17 bytes", c.length == plain.length + 17);
        check("receiver decrypts", Arrays.equals(cb.decryptVoice(c, 1, 2, 42L), plain));
        check("ciphertext does not contain the text", new String(c, StandardCharsets.ISO_8859_1).indexOf("secret") < 0);
        byte[] bad = c.clone(); bad[bad.length - 1] ^= 1;
        check("tampered packet is rejected", cb.decryptVoice(bad, 1, 2, 42L) == null);
        check("wrong destination is rejected (relay redirect)", cb.decryptVoice(c, 1, 9, 42L) == null);
        check("wrong sender is rejected (re-attribution)", cb.decryptVoice(c, 7, 2, 42L) == null);
        check("wrong message id is rejected", cb.decryptVoice(c, 1, 2, 43L) == null);
        check("a third party cannot decrypt", cev.decryptVoice(c, 1, 2, 42L) == null);
        byte[] clearChat = new byte[1 + plain.length];
        System.arraycopy(plain, 0, clearChat, 1, plain.length);
        check("downgrade: clear chat message is refused", cb.decryptVoice(clearChat, 1, 2, 42L) == null);
        byte[] kf = Frame.key(Frame.KEY_REQ, ka.publicRaw());
        check("key frames pass in clear", Arrays.equals(cb.decrypt(ca.encrypt(kf, 1, 2, 5L), 1, 2, 5L), kf));
        boolean threw = false;
        try { new E2eeCrypto(1, ka, new KeyDirectory(null)).encryptVoice(plain, 1, 2, 1L); } catch (E2eeCrypto.MissingKeyException e) { threw = true; }
        check("missing key is reported, not silently sent in clear", threw);
        KeyDirectory d = new KeyDirectory(null);
        final int[] changed = {0};
        d.setListener((n, o, w) -> changed[0]++);
        d.put(5, ka.publicRaw());
        check("TOFU: same key accepted, other key refused + reported", d.put(5, ka.publicRaw()) && !d.put(5, kb.publicRaw()) && changed[0] == 1);
        IdentityKeys re = IdentityKeys.restore(ka.privateEncoded(), ka.publicRaw());
        check("identity key survives save/restore", Arrays.equals(re.publicRaw(), ka.publicRaw())
                && Arrays.equals(re.sharedSecret(kb.publicRaw()), ka.sharedSecret(kb.publicRaw())));
    }

    static E2eeCrypto[] pair(StorageAdapter sa, StorageAdapter sb, IdentityKeys[] keysOut) {
        IdentityKeys ka = IdentityKeys.generate(), kb = IdentityKeys.generate();
        KeyDirectory da = new KeyDirectory(null), db = new KeyDirectory(null);
        da.put(2, kb.publicRaw());
        db.put(1, ka.publicRaw());
        if (keysOut != null) { keysOut[0] = ka; keysOut[1] = kb; }
        return new E2eeCrypto[]{new E2eeCrypto(1, ka, da, sa), new E2eeCrypto(2, kb, db, sb)};
    }

    static byte[] msg(String s) { return Frame.text(1, 2, s); }

    static void crypto() {
        IdentityKeys[] k = new IdentityKeys[2];
        E2eeCrypto[] p = pair(null, null, k);
        E2eeCrypto a = p[0], b = p[1];
        byte[] m1 = msg("secret one");
        byte[] c1 = a.encrypt(m1, 1, 2, 11L);
        check("ratchet overhead is exactly 55 bytes", c1.length == m1.length + 55);
        check("receiver decrypts the first message (starts the session)", Arrays.equals(b.decrypt(c1, 1, 2, 11L), m1));
        check("ciphertext does not contain the text", new String(c1, StandardCharsets.ISO_8859_1).indexOf("secret") < 0);

        // ping-pong: every reply moves both sides to a fresh ratchet key
        byte[] dhA1 = Arrays.copyOfRange(c1, 2, 35);
        byte[] r1 = msg("reply");
        byte[] cr = b.encrypt(r1, 2, 1, 12L);
        check("reply decrypts", Arrays.equals(a.decrypt(cr, 2, 1, 12L), r1));
        byte[] c2 = a.encrypt(msg("second"), 1, 2, 13L);
        byte[] dhA2 = Arrays.copyOfRange(c2, 2, 35);
        check("sender's ratchet key changed after the reply (forward secrecy / healing)", !Arrays.equals(dhA1, dhA2));
        check("second message decrypts", Arrays.equals(b.decrypt(c2, 1, 2, 13L), msg("second")));
        boolean allOk = true;
        for (int i = 0; i < 20; i++) {
            byte[] x = msg("a" + i), y = msg("b" + i);
            allOk &= Arrays.equals(b.decrypt(a.encrypt(x, 1, 2, 100 + i), 1, 2, 100 + i), x);
            allOk &= Arrays.equals(a.decrypt(b.encrypt(y, 2, 1, 200 + i), 2, 1, 200 + i), y);
        }
        check("20 round trips in both directions", allOk);

        // one-way burst, then out of order, then loss
        byte[][] burst = new byte[6][];
        for (int i = 0; i < 6; i++) burst[i] = a.encrypt(msg("burst" + i), 1, 2, 300 + i);
        boolean ooo = Arrays.equals(b.decrypt(burst[3], 1, 2, 303), msg("burst3"))
                && Arrays.equals(b.decrypt(burst[0], 1, 2, 300), msg("burst0"))
                && Arrays.equals(b.decrypt(burst[5], 1, 2, 305), msg("burst5"))
                && Arrays.equals(b.decrypt(burst[1], 1, 2, 301), msg("burst1"));
        check("out-of-order and lost messages (burst 3,0,5,1; 2 and 4 lost)", ooo);
        check("a message that arrives late (4) still works", Arrays.equals(b.decrypt(burst[4], 1, 2, 304), msg("burst4")));

        // retries: same plaintext -> same ciphertext, no ratchet step burned; replay -> same plaintext
        byte[] same = msg("retry me");
        byte[] e1 = a.encrypt(same, 1, 2, 400), e2 = a.encrypt(same, 1, 2, 401);
        check("re-sending the same frame reuses the ciphertext", Arrays.equals(e1, e2));
        check("first copy decrypts", Arrays.equals(b.decrypt(e1, 1, 2, 400), same));
        check("a repeated copy (lost receipt) is answered again, not rejected", Arrays.equals(b.decrypt(e2, 1, 2, 401), same));
        b.clearCaches();
        check("forward secrecy: after the key is used and the cache is gone, an old ciphertext cannot be decrypted again",
                b.decrypt(e1, 1, 2, 402) == null);

        // attacks
        byte[] fresh = a.encrypt(msg("fresh"), 1, 2, 500);
        byte[] bad = fresh.clone(); bad[bad.length - 1] ^= 1;
        check("tampered message rejected", b.decrypt(bad, 1, 2, 500) == null);
        byte[] bad2 = fresh.clone(); bad2[40] ^= 1;
        check("tampered header rejected", b.decrypt(bad2, 1, 2, 500) == null);
        check("wrong destination rejected (relay redirect)", b.decrypt(fresh, 1, 9, 500) == null);
        check("wrong claimed sender rejected", b.decrypt(fresh, 7, 2, 500) == null);
        check("garbage did not damage the session: the real message still decrypts", Arrays.equals(b.decrypt(fresh, 1, 2, 500), msg("fresh")));
        byte[] clearChat = new byte[1 + fresh.length];
        System.arraycopy(msg("x"), 0, clearChat, 1, msg("x").length);
        check("downgrade: clear chat message refused", b.decrypt(clearChat, 1, 2, 5) == null);
        byte[] kf = Frame.key(Frame.KEY_REQ, k[0].publicRaw());
        check("key frames pass in clear", Arrays.equals(b.decrypt(a.encrypt(kf, 1, 2, 6), 1, 2, 6), kf));
        boolean threw = false;
        try { new E2eeCrypto(1, k[0], new KeyDirectory(null)).encrypt(msg("x"), 1, 2, 1L); } catch (E2eeCrypto.MissingKeyException e) { threw = true; }
        check("missing key is reported, never sent in clear", threw);
        IdentityKeys ke = IdentityKeys.generate();
        KeyDirectory de = new KeyDirectory(null);
        de.put(1, k[0].publicRaw()); de.put(2, k[1].publicRaw());
        E2eeCrypto eve = new E2eeCrypto(3, ke, de);
        check("a third party cannot read a session", eve.decrypt(fresh, 1, 2, 500) == null);

        // restart: sessions survive in storage
        StorageAdapter sa = new MemoryStorage(), sb = new MemoryStorage();
        IdentityKeys[] k2 = new IdentityKeys[2];
        E2eeCrypto[] q = pair(sa, sb, k2);
        q[1].decrypt(q[0].encrypt(msg("hi"), 1, 2, 1), 1, 2, 1);
        q[0].decrypt(q[1].encrypt(msg("yo"), 2, 1, 2), 2, 1, 2);
        KeyDirectory da2 = new KeyDirectory(sa), db2 = new KeyDirectory(sb);
        da2.put(2, k2[1].publicRaw()); db2.put(1, k2[0].publicRaw());
        E2eeCrypto a2 = new E2eeCrypto(1, k2[0], da2, sa), b2 = new E2eeCrypto(2, k2[1], db2, sb);
        check("session survives a restart (state read back from storage)",
                Arrays.equals(b2.decrypt(a2.encrypt(msg("after restart"), 1, 2, 3), 1, 2, 3), msg("after restart"))
                        && Arrays.equals(a2.decrypt(b2.encrypt(msg("and back"), 2, 1, 4), 2, 1, 4), msg("and back")));

        // both start at the same moment: lower node id (1) wins
        E2eeCrypto[] w = pair(null, null, null);
        byte[] fromA = w[0].encrypt(msg("A first"), 1, 2, 1), fromB = w[1].encrypt(msg("B first"), 2, 1, 1);
        check("simultaneous start: the winner (lower id) ignores the other's first flight", w[0].decrypt(fromB, 2, 1, 1) == null);
        check("simultaneous start: the loser adopts the winner's session", Arrays.equals(w[1].decrypt(fromA, 1, 2, 1), msg("A first")));
        byte[] resend = w[1].encrypt(msg("B first"), 2, 1, 2);
        check("the loser's message, sent again, is not the old ciphertext", !Arrays.equals(resend, fromB));
        check("and the winner reads it", Arrays.equals(w[0].decrypt(resend, 2, 1, 2), msg("B first")));

        // replay of an old first flight after the session exists
        E2eeCrypto[] v = pair(null, null, null);
        byte[] first = v[0].encrypt(msg("one"), 1, 2, 1);
        v[1].decrypt(first, 1, 2, 1);
        v[0].decrypt(v[1].encrypt(msg("two"), 2, 1, 2), 2, 1, 2);
        v[1].clearCaches();
        check("replayed first flight is refused", v[1].decrypt(first, 1, 2, 9) == null);

        // lost session on the receiving side -> the sender is told
        final int[] lostCalls = {0};
        E2eeCrypto[] u = pair(null, null, null);
        u[1].setSessionLostListener(n -> lostCalls[0]++);
        u[1].decrypt(u[0].encrypt(msg("one"), 1, 2, 1), 1, 2, 1);
        u[0].decrypt(u[1].encrypt(msg("two"), 2, 1, 2), 2, 1, 2);
        byte[] later = u[0].encrypt(msg("three"), 1, 2, 3);
        u[1].resetSession(1);                                   // B "lost" its state
        check("non-first message without a session cannot be read", u[1].decrypt(later, 1, 2, 3) == null);
        check("and triggers the session-lost signal", lostCalls[0] == 1);
        u[0].resetSession(2);                                   // A reacts to RESET
        byte[] restart = u[0].encrypt(msg("three"), 1, 2, 4);
        check("after the reset a new session starts and works", Arrays.equals(u[1].decrypt(restart, 1, 2, 4), msg("three")));

        // key directory and identity
        KeyDirectory d = new KeyDirectory(null);
        final int[] changed = {0};
        d.setListener((n, o, w2) -> changed[0]++);
        d.put(5, k[0].publicRaw());
        check("TOFU: same key accepted, other key refused + reported", d.put(5, k[0].publicRaw()) && !d.put(5, k[1].publicRaw()) && changed[0] == 1);
        IdentityKeys re = IdentityKeys.restore(k[0].privateEncoded(), k[0].publicRaw());
        check("identity key survives save/restore", Arrays.equals(re.publicRaw(), k[0].publicRaw())
                && Arrays.equals(re.sharedSecret(k[1].publicRaw()), k[0].sharedSecret(k[1].publicRaw())));
        byte[] pub = IdentityKeys.generate().publicRaw();
        check("point compression round trip", Arrays.equals(IdentityKeys.decompress(IdentityKeys.compress(pub)), pub));
        check("compression refuses a malformed point", IdentityKeys.decompress(new byte[33]) == null);
    }

    static void compression() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) sb.append("আমি বাংলায় একটা লম্বা মেসেজ লিখছি ");
        String longBn = sb.toString();
        byte[] f = Frame.text(1, 2, longBn);
        check("long Bengali text is compressed (smaller than plain UTF-8)", Frame.type(f) == Frame.TEXT_Z && f.length < longBn.getBytes(StandardCharsets.UTF_8).length / 2);
        check("compressed text round trips", longBn.equals(Frame.textOf(f)) && Frame.valid(f));
        byte[] shortF = Frame.text(1, 2, "ছোট");
        check("short text is not compressed", Frame.type(shortF) == Frame.TEXT);
        byte[] rnd = new byte[1000];
        new Random(1).nextBytes(rnd);
        check("incompressible bytes stay as they are", Frame.type(Frame.app(1, rnd)) == Frame.APP);
        byte[] zeros = new byte[20000];
        check("compressible bytes shrink and return exactly", Arrays.equals(Frame.appBytes(Frame.app(1, zeros)), zeros) && Frame.app(1, zeros).length < 200);
        byte[] bomb = Frame.app(1, new byte[Frame.MAX_INFLATED * 3]);
        boolean refused = false;
        try { Frame.appBytes(bomb); } catch (IllegalArgumentException e) { refused = true; }
        check("decompression bomb is refused", refused);
        byte[] corrupt = f.clone();
        corrupt[corrupt.length - 3] ^= 0x7f;
        boolean bad = false;
        try { Frame.textOf(corrupt); } catch (IllegalArgumentException e) { bad = true; }
        check("corrupt compressed data is refused, not crashed on", bad);
    }

    // ------------------------------------------------------------------ network tests

    public static final class Probe implements MeshClient.Listener {
        public final Map<Long, String> texts = new ConcurrentHashMap<>();   // localId -> "from:text"
        public final Set<Long> delivered = ConcurrentHashMap.newKeySet();
        final Set<Long> seen = ConcurrentHashMap.newKeySet();
        final Set<Long> failed = ConcurrentHashMap.newKeySet();
        final Map<Long, byte[]> blobs = new ConcurrentHashMap<>();            // localId -> received bytes
        final Map<Long, Integer> progress = new ConcurrentHashMap<>();        // localId -> chunks confirmed
        final Map<Long, Integer> largeFailed = new ConcurrentHashMap<>();     // localId -> chunks missing
        final List<String> keyChanges = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> zones = new java.util.concurrent.CopyOnWriteArrayList<>();
        public final List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        public final Map<String, Long> textTime = new ConcurrentHashMap<>();
        public final Map<Long, Long> blobTime = new ConcurrentHashMap<>();
        public final Map<Long, Integer> blobProgress = new ConcurrentHashMap<>();
        @Override public void onBlob(String f, long id, byte[] d) { blobs.put(id, d); blobTime.put(id, System.nanoTime()); }
        @Override public void onBlobProgress(long id, int delivered, int total) { blobProgress.put(id, delivered * 1000 + total); }
        @Override public void onText(String f, long id, long ts, String t) { texts.put(id, f + ":" + t); order.add(t); textTime.put(t, System.nanoTime()); }
        @Override public void onBytes(String f, long id, byte[] d) { texts.put(id, f + ":#" + d.length); blobs.put(id, d); }
        @Override public void onLargeProgress(long id, int done, int total) { progress.put(id, done); }
        @Override public void onLargeFailed(long id, String reason, int missing) { largeFailed.put(id, missing); }
        @Override public void onDelivered(long id) { delivered.add(id); }
        @Override public void onFailed(long id, String r) { failed.add(id); }
        @Override public void onSeen(long id) { seen.add(id); }
        @Override public void onKeyChanged(String u, String a, String b) { keyChanges.add(u); }
        @Override public void onZone(int z, String g) { zones.add(g); }
    }

    public static final class Peer {
        final MeshClient c;
        final Probe p = new Probe();
        final StorageAdapter st = new MemoryStorage();
        Peer(MemNet net, MemNet.Hub hub, int id, double lat, double lon, boolean withServer) { this(net, hub, id, lat, lon, withServer, new MemoryStorage()); }
        public static MeshClient.Routing ROUTING = MeshClient.Routing.FLAT;
        Peer(MemNet net, MemNet.Hub hub, int id, double lat, double lon, boolean withServer, StorageAdapter storage) {
            MeshClient.Builder b = MeshClient.builder(Integer.toString(id))
                    .radio(net.radio(id)).storage(storage).listener(p)
                    .retry(400, 200).zoneHold(100).routing(Peer.ROUTING)
                    .tune(cfg -> { cfg.ackTimeoutMs = 700; cfg.retryMs = 300; });
            if (withServer) b.server(hub.link());
            c = b.build();
            c.updateLocation(lat, lon);
            c.start();
            waitFor(() -> c.zone() == GeoZones.zoneOf(lat, lon, 6), 2000);
        }
    }

    // Cell centres at precision 6: lat step 0.005493 deg, lon step 0.010986 deg
    static final double LAT0 = 23.8000, LON0 = 90.4000;

    static void chainAcrossZones() {
        MemNet net = new MemNet();
        Peer a = new Peer(net, null, 1, LAT0, LON0, false);
        Peer b = new Peer(net, null, 2, LAT0, LON0 + 0.004, false);
        Peer c = new Peer(net, null, 3, LAT0, LON0 + 0.03, false);   // other zone, only B is in radio range
        check("zones: A and B share a zone, C differs", a.c.zone() == b.c.zone() && b.c.zone() != c.c.zone());
        net.link(1, 2);
        net.link(2, 3);
        check("A has 1 neighbour, B has 2", waitFor(() -> a.c.peerCount() == 1 && b.c.peerCount() == 2, 3000));
        long id = a.c.sendText("3", "hello across zones");
        check("A -> C: first contact (automatic key exchange) delivered",
                waitFor(() -> "1:hello across zones".equals(c.p.texts.get(id)), 12000));
        check("A gets the delivery confirmation", waitFor(() -> a.p.delivered.contains(id), 8000));
        check("both sides pinned each other's key", a.c.fingerprintOf("3") != null && c.c.fingerprintOf("1") != null);
        c.c.markSeen("1", id);
        check("read receipt reaches A", waitFor(() -> a.p.seen.contains(id), 8000));
        long id2 = c.c.sendText("1", "reply");
        check("C -> A works the other way", waitFor(() -> "3:reply".equals(a.p.texts.get(id2)), 8000));
        long id3 = a.c.sendBytes("3", new byte[2000]);
        check("binary payload (2000 bytes)", waitFor(() -> "1:#2000".equals(c.p.texts.get(id3)), 8000));
        stop(a, b, c);
    }

    static void offlineThenOnline() {
        MemNet net = new MemNet();
        Peer a = new Peer(net, null, 11, LAT0, LON0, false);
        Peer b = new Peer(net, null, 12, LAT0, LON0 + 0.03, false);
        long id = a.c.sendText("12", "waits for you");   // no link at all right now
        sleep(1200);
        check("nothing delivered while unreachable, still pending", b.p.texts.isEmpty() && a.c.pendingCount() >= 1);
        net.link(11, 12);
        check("delivered after the link appears", waitFor(() -> "11:waits for you".equals(b.p.texts.get(id)), 20000));
        check("delivery confirmed and outbox emptied", waitFor(() -> a.p.delivered.contains(id) && a.c.pendingCount() == 0, 10000));
        stop(a, b);
    }

    static void restartKeepsOutbox() {
        MemNet net = new MemNet();
        StorageAdapter disk = new MemoryStorage();
        Peer a = new Peer(net, null, 21, LAT0, LON0, false, disk);
        long id = a.c.sendText("22", "survives restart");
        sleep(300);
        a.c.stop();
        MemNet net2 = new MemNet();
        Peer a2 = new Peer(net2, null, 21, LAT0, LON0, false, disk);
        Peer b = new Peer(net2, null, 22, LAT0, LON0, false);
        check("outbox reloaded after restart", waitFor(() -> a2.c.pendingCount() >= 1, 3000));
        net2.link(21, 22);
        check("message delivered after restart", waitFor(() -> "21:survives restart".equals(b.p.texts.get(id)), 20000));
        stop(a2, b);
    }

    static void viaServer() {
        MemNet net = new MemNet();
        MemNet.Hub hub = net.new Hub();
        Peer a = new Peer(net, hub, 31, LAT0, LON0, true);
        Peer b = new Peer(net, hub, 32, LAT0 + 0.5, LON0 + 0.5, true);   // far away, no radio link
        long id = a.c.sendText("32", "through the server");
        check("online recipient gets it through the server", waitFor(() -> "31:through the server".equals(b.p.texts.get(id)), 12000));
        check("sender sees the confirmation", waitFor(() -> a.p.delivered.contains(id), 8000));
        check("the server relayed packets (and kept no history: it has no store)", hub.relayed.get() > 0);
        stop(a, b);
    }

    static void keyChangeIsNotSilent() {
        MemNet net = new MemNet();
        Peer a = new Peer(net, null, 41, LAT0, LON0, false);
        Peer b = new Peer(net, null, 42, LAT0, LON0, false);
        net.link(41, 42);
        long id = a.c.sendText("42", "first");
        check("first message delivered", waitFor(() -> b.p.texts.containsKey(id), 12000));
        b.c.stop();
        // someone else (or a reinstall) shows up as node 42 with a fresh identity
        Peer evil = new Peer(net, null, 42, LAT0, LON0, false);
        net.unlink(41, 42);
        net.link(41, 42);
        long id2 = a.c.sendText("42", "second");
        check("A is warned that 42 presented a different key", waitFor(() -> !a.p.keyChanges.isEmpty(), 15000));
        sleep(1500);
        check("nothing is delivered to the unverified key", evil.p.texts.isEmpty());
        a.c.acceptNewKey("42");
        check("after the user accepts, delivery continues", waitFor(() -> evil.p.texts.containsKey(id2), 20000));
        stop(a, evil);
    }

    static void movingChangesZone() {
        MemNet net = new MemNet();
        Peer a = new Peer(net, null, 51, LAT0, LON0, false);
        Peer b = new Peer(net, null, 52, LAT0, LON0, false);
        net.link(51, 52);
        long id = a.c.sendText("52", "same zone");
        check("same zone delivery", waitFor(() -> b.p.texts.containsKey(id), 12000));
        int before = b.c.zone();
        b.c.updateLocation(LAT0, LON0 + 0.05);
        sleep(200);
        b.c.updateLocation(LAT0, LON0 + 0.05);
        check("B moved to another zone", waitFor(() -> b.c.zone() != before, 3000));
        long id2 = a.c.sendText("52", "after the move");
        check("still reachable after B changed zone (link handshake re-files B as backbone peer)",
                waitFor(() -> b.p.texts.containsKey(id2), 15000));
        stop(a, b);
    }

    /** 6 x 5 grid of phones, each talking to its 4 neighbours only. Random pairs send; count what arrives. */
    public static void gridDelivery() {
        MemNet net = new MemNet();
        int cols = 6, rows = 5;
        Peer[][] g = new Peer[rows][cols];
        List<Peer> all = new ArrayList<>();
        Set<Integer> zones = new HashSet<>();
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            int id = 1000 + r * cols + c;
            g[r][c] = new Peer(net, null, id, LAT0 + r * 0.0022, LON0 + c * 0.0042, false);
            all.add(g[r][c]);
            zones.add(g[r][c].c.zone());
        }
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            if (c + 1 < cols) net.link(1000 + r * cols + c, 1000 + r * cols + c + 1);
            if (r + 1 < rows) net.link(1000 + r * cols + c, 1000 + (r + 1) * cols + c);
        }
        sleep(800);
        Random rnd = new Random(7);
        long start = System.currentTimeMillis();
        int total = 40;
        long[] ids = new long[total];
        Peer[] dst = new Peer[total];
        Peer[] srcOf = new Peer[total];
        for (int i = 0; i < total; i++) {
            Peer s = all.get(rnd.nextInt(all.size()));
            Peer d;
            do { d = all.get(rnd.nextInt(all.size())); } while (d == s);
            dst[i] = d;
            srcOf[i] = s;
            ids[i] = s.c.sendText(d.c.userId(), "m" + i);
            sleep(40);
        }
        final int[] got = {0};
        waitFor(() -> {
            int n = 0;
            for (int i = 0; i < total; i++) if (dst[i].p.texts.containsKey(ids[i])) n++;
            got[0] = n;
            return n == total;
        }, 60000);
        long ms = System.currentTimeMillis() - start;
        int sameOk = 0, sameAll = 0, crossOk = 0, crossAll = 0;
        for (int i = 0; i < total; i++) {
            boolean ok = dst[i].p.texts.containsKey(ids[i]);
            boolean same = srcOf[i].c.zone() == dst[i].c.zone();
            if (same) { sameAll++; if (ok) sameOk++; } else { crossAll++; if (ok) crossOk++; }
        }
        System.out.println("INFO  grid split: same-zone " + sameOk + "/" + sameAll + ", cross-zone " + crossOk + "/" + crossAll);
        System.out.println("INFO  grid: " + all.size() + " phones in " + zones.size() + " zones, " + got[0] + "/" + total
                + " delivered, last one after about " + ms + " ms (incl. key exchange), radio frames: " + net.frames.get());
        check("grid mesh: at least 95% delivered across zones", got[0] >= total * 95 / 100);
        stop(all.toArray(new Peer[0]));
    }

    static void duty() {
        check("duty: plugged in never rests", DutyPolicy.decide(0.9, true, 0, false).offMs == 0 && DutyPolicy.decide(0.05, true, 5, true).offMs == 0);
        check("duty: nearly empty scans rarely (<10% duty)", DutyPolicy.decide(0.10, false, 0, false).onMs * 10 < DutyPolicy.decide(0.10, false, 0, false).offMs + DutyPolicy.decide(0.10, false, 0, false).onMs);
        check("duty: low battery rests longer than saver mode", DutyPolicy.decide(0.25, false, 0, false).offMs > DutyPolicy.decide(0.8, false, 0, true).offMs);
        check("duty: well-connected phone scans less than a lonely one", DutyPolicy.decide(0.8, false, 4, false).offMs > DutyPolicy.decide(0.8, false, 0, false).offMs);
        check("duty: healthy and alone scans all the time", DutyPolicy.decide(0.8, false, 0, false).offMs == 0);
    }

    static void priorityAndBlob() {
        MemNet net = new MemNet();
        Peer a = new Peer(net, null, 61, LAT0, LON0, false);
        Peer b = new Peer(net, null, 62, LAT0, LON0, false);
        byte[] file = new byte[40_000];
        new Random(3).nextBytes(file);
        long blob = a.c.sendBlob("62", file);
        a.c.sendText("62", "normal one");
        a.c.sendText("62", "normal two");
        a.c.sendText("62", "SOS help", MeshClient.PRIORITY_SOS);
        sleep(800);
        net.link(61, 62);
        check("all text arrives", waitFor(() -> b.p.order.size() >= 3, 15000));
        check("SOS is delivered before normal chat", "SOS help".equals(b.p.order.get(0)));
        check("40 KB blob arrives complete and identical", waitFor(() -> b.p.blobs.containsKey(blob), 30000) && Arrays.equals(b.p.blobs.get(blob), file));
        check("chat was not stuck behind the file (both chats arrived before the file finished)",
                b.p.textTime.get("normal one") < b.p.blobTime.get(blob) && b.p.textTime.get("normal two") < b.p.blobTime.get(blob));
        check("sender sees progress up to all chunks", waitFor(() -> { Integer pr = a.p.blobProgress.get(blob); return pr != null && pr / 1000 == pr % 1000; }, 10000));
        stop(a, b);

        // 100 KB across a chain with a zone border, then a lossy link
        MemNet net2 = new MemNet();
        Peer x = new Peer(net2, null, 71, LAT0, LON0, false);
        Peer y = new Peer(net2, null, 72, LAT0, LON0 + 0.004, false);
        Peer z = new Peer(net2, null, 73, LAT0, LON0 + 0.03, false);
        net2.link(71, 72); net2.link(72, 73);
        byte[] big = new byte[100_000];
        new Random(4).nextBytes(big);
        long id = x.c.sendBlob("73", big);
        check("100 KB blob over 2 hops arrives identical", waitFor(() -> z.p.blobs.containsKey(id), 40000) && Arrays.equals(z.p.blobs.get(id), big));
        stop(x, y, z);

        MemNet net3 = new MemNet();
        Peer p = new Peer(net3, null, 74, LAT0, LON0, false);
        Peer q = new Peer(net3, null, 75, LAT0, LON0, false);
        net3.link(74, 75);
        waitFor(() -> p.c.peerCount() == 1, 2000);
        net3.lossPct.put(MemNet.key(74, 75), 20);
        byte[] lossy = new byte[60_000];
        new Random(5).nextBytes(lossy);
        long lid = p.c.sendBlob("75", lossy);
        check("60 KB blob over a link that loses 20% of frames still arrives identical",
                waitFor(() -> q.p.blobs.containsKey(lid), 90000) && Arrays.equals(q.p.blobs.get(lid), lossy));
        stop(p, q);
    }

    static void linkQualityAndLimits() {
        MemNet net = new MemNet();
        Peer a = new Peer(net, null, 81, LAT0, LON0, false);
        Peer b = new Peer(net, null, 82, LAT0, LON0, false);
        Peer c = new Peer(net, null, 83, LAT0, LON0, false);
        Peer d = new Peer(net, null, 84, LAT0, LON0, false);
        net.link(81, 82); net.link(81, 83); net.link(81, 84);
        check("A has 3 neighbours", waitFor(() -> a.c.peerCount() == 3, 3000));
        check("round trip time is measured", waitFor(() -> {
            for (FramedTransport.LinkQuality q : a.c.linkQuality()) if (q.peer == 82 && q.rttMs >= 0 && q.rttMs < 200 && q.samples >= 3) return true;
            return false; }, 6000));
        net.lossPct.put(MemNet.key(81, 84), 100);
        check("a link that loses everything is dropped while two healthy ones exist", waitFor(() -> a.c.peerCount() == 2, 15000));
        boolean kept = false;
        for (FramedTransport.LinkQuality q : a.c.linkQuality()) if (q.peer == 82 || q.peer == 83) kept = true;
        check("the healthy links stay", kept);
        stop(a, b, c, d);

        MemNet n2 = new MemNet();
        Peer v = new Peer(n2, null, 91, LAT0, LON0, false);   // victim
        Peer evil = new Peer(n2, null, 92, LAT0, LON0, false);
        Peer ok = new Peer(n2, null, 93, LAT0, LON0, false);
        n2.link(91, 92); n2.link(91, 93);
        waitFor(() -> v.c.peerCount() == 2, 3000);
        byte[] junk = new byte[40];
        junk[0] = 2;
        for (int i = 0; i < 3000; i++) n2.radio(92).sendRaw(91, junk);
        check("flood from one neighbour is refused (rate limit, at least 300 refused before the cut-off)", waitFor(() -> v.c.rateLimitedCount() >= 300, 8000));
        check("the flooding neighbour is disconnected", waitFor(() -> v.c.peerCount() == 1, 8000));
        long id = ok.c.sendText("91", "still reachable");
        check("an honest neighbour is not affected by the flood", waitFor(() -> v.p.texts.containsKey(id), 15000));
        stop(v, evil, ok);
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }


    // ------------------------------------------------------------------ large messages, battery

    static byte[] randomBytes(int n, long seed) { byte[] b = new byte[n]; new Random(seed).nextBytes(b); return b; }

    static final class VoiceProbe implements MeshClient.VoiceListener {
        final java.util.concurrent.CopyOnWriteArrayList<byte[]> frames = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger starts = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger ends = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger lost = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger quality = new java.util.concurrent.atomic.AtomicInteger(-1);
        volatile String from;
        @Override public void onVoiceStart(String f) { from = f; starts.incrementAndGet(); }
        @Override public void onVoiceFrame(String f, byte[] d) { if (d == null) lost.incrementAndGet(); else frames.add(d); }
        @Override public void onVoiceEnd(String f) { ends.incrementAndGet(); }
        @Override public void onVoiceQuality(String t, int loss) { quality.set(loss); }
    }

    /** Push-to-talk across two hops and a zone border, end-to-end encrypted, after a normal first message set the keys up. */
    static void voicePushToTalk() {
        MemNet net = new MemNet();
        Peer a = new Peer(net, null, 51, LAT0, LON0, false);
        Peer b = new Peer(net, null, 52, LAT0, LON0 + 0.004, false);
        Peer c = new Peer(net, null, 53, LAT0, LON0 + 0.03, false);   // another zone, only B is in radio range
        VoiceProbe rx = new VoiceProbe(), tx = new VoiceProbe();
        c.c.setVoiceListener(rx);
        a.c.setVoiceListener(tx);
        net.link(51, 52);
        net.link(52, 53);
        check("voice: chain is up", waitFor(() -> a.c.peerCount() == 1 && b.c.peerCount() == 2, 3000));
        long ring = a.c.sendText("53", "calling you");                  // sets the route and the keys up, like a ringing message
        check("voice: the ringing message arrives", waitFor(() -> c.p.texts.containsKey(ring), 12000));
        check("voice: keys are pinned both ways", a.c.fingerprintOf("53") != null && c.c.fingerprintOf("51") != null);
        int stream = a.c.voiceOpen("53", 60);
        for (int i = 0; i < 30; i++) {
            byte[] f = new byte[24];
            f[0] = (byte) i;
            a.c.voiceSend(stream, f);
            try { Thread.sleep(60); } catch (InterruptedException e) { return; }
        }
        a.c.voiceEnd(stream);
        check("voice: the receiver hears the talk start and end once", waitFor(() -> rx.starts.get() == 1 && rx.ends.get() == 1, 5000));
        check("voice: all 30 packets arrive, in order and intact",
                waitFor(() -> rx.frames.size() == 30, 5000) && inOrder(rx.frames));
        check("voice: nothing lost and the sender is identified", rx.lost.get() == 0 && "51".equals(rx.from));
        check("voice: the sender gets quality reports", waitFor(() -> tx.quality.get() >= 0, 5000));
        long ring2 = c.c.sendText("51", "heard you");                  // text still works right after a call
        check("voice: text works after a call", waitFor(() -> a.p.texts.containsKey(ring2), 8000));
        stop(a, b, c);
    }

    // ---- audio pipeline: codec, microphone/loudspeaker fakes, push-to-talk ----------------------------------------------

    /** A 440 Hz tone in real time, 8 kHz, phase continuous across blocks. */
    static final class ToneSource implements AudioSource {
        private volatile boolean on;
        private long sample;
        private long nextDue;
        @Override public void start(int rate) { on = true; nextDue = System.currentTimeMillis(); }
        @Override public int read(short[] buf) {
            nextDue += buf.length * 1000L / 8000;
            long wait = nextDue - System.currentTimeMillis();
            try { if (wait > 0) Thread.sleep(wait); } catch (InterruptedException e) { return -1; }
            if (!on) return -1;
            for (int i = 0; i < buf.length; i++) buf[i] = (short) (8000 * Math.sin(2 * Math.PI * 440 * (sample++) / 8000.0));
            return buf.length;
        }
        @Override public void stop() { on = false; }
    }

    static final class CollectSink implements AudioSink {
        final java.util.concurrent.CopyOnWriteArrayList<short[]> blocks = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public void start(int rate) { }
        @Override public void write(short[] pcm) { blocks.add(pcm); }
        @Override public void stop() { }
    }

    static final class PttEvents implements PushToTalk.Events {
        final java.util.concurrent.atomic.AtomicInteger starts = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger ends = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.CopyOnWriteArrayList<String> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile String from;
        @Override public void onTalkStart(String u) { from = u; starts.incrementAndGet(); }
        @Override public void onTalkEnd(String u) { ends.incrementAndGet(); }
        @Override public void onQuality(String u, int loss) { }
        @Override public void onError(String m) { errors.add(m); }
    }

    /** How much of a block is the 440 Hz tone: fit a*sin + b*cos, return the signal-to-error ratio in dB. */
    static double toneSnrDb(short[] y) {
        double ss = 0, cc = 0, sc = 0, ys = 0, yc = 0, yy = 0;
        for (int n = 0; n < y.length; n++) {
            double s = Math.sin(2 * Math.PI * 440 * n / 8000.0), c = Math.cos(2 * Math.PI * 440 * n / 8000.0);
            ss += s * s; cc += c * c; sc += s * c; ys += y[n] * s; yc += y[n] * c; yy += (double) y[n] * y[n];
        }
        double det = ss * cc - sc * sc, a = (ys * cc - yc * sc) / det, b = (yc * ss - ys * sc) / det;
        double err = 0;
        for (int n = 0; n < y.length; n++) {
            double fit = a * Math.sin(2 * Math.PI * 440 * n / 8000.0) + b * Math.cos(2 * Math.PI * 440 * n / 8000.0);
            err += (y[n] - fit) * (y[n] - fit);
        }
        return err == 0 ? 99 : 10 * Math.log10(yy / err);
    }

    static void adpcmCodec() {
        AdpcmCodec enc = new AdpcmCodec(), dec = new AdpcmCodec();
        short[] pcm = new short[AdpcmCodec.BLOCK];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (8000 * Math.sin(2 * Math.PI * 440 * i / 8000.0));
        byte[] b = enc.encode(pcm);
        check("adpcm: a 60 ms block is 243 bytes (about 32 kbit/s)", b.length == 243 && AdpcmCodec.BYTES == 243);
        short[] back = dec.decode(b);
        check("adpcm: a tone comes back clean (" + (int) toneSnrDb(back) + " dB)", back != null && toneSnrDb(back) > 20);
        short[] other = new AdpcmCodec().decode(b);
        check("adpcm: a block decodes the same on its own (no state from earlier blocks)", Arrays.equals(back, other));
        check("adpcm: garbage is rejected, not played", dec.decode(new byte[10]) == null && dec.decode(null) == null);
        short[] c1 = dec.conceal(), c2 = dec.conceal(), c3 = dec.conceal(), c4 = dec.conceal();
        check("adpcm: a lost block repeats the last one quieter, then goes silent",
                energy(c1) > 0 && energy(c1) < energy(back) && energy(c2) < energy(c1) && energy(c3) < energy(c2) && energy(c4) == 0);
        check("adpcm: the next good block restarts concealment", dec.decode(b) != null && energy(dec.conceal()) > 0);
        short[] loud = new short[AdpcmCodec.BLOCK];
        for (int i = 0; i < loud.length; i++) loud[i] = (short) (i % 2 == 0 ? 32767 : -32768);
        check("adpcm: full-scale square noise does not overflow or crash", new AdpcmCodec().decode(new AdpcmCodec().encode(loud)) != null);
    }

    static double energy(short[] x) { double e = 0; for (short v : x) e += (double) v * v; return e; }

    // ---- priority levels and app-chosen ids -------------------------------------------------------------------------------

    static void priorityLevels() {
        // the Outbox keeps the level across a restart, and an entry written by the old format still loads
        StorageAdapter st = new MemoryStorage();
        Outbox ob = new Outbox(st);
        ob.add(new Outbox.Entry(1L, 70001, 5L, false, new byte[]{9, 9}, Priority.SOS));
        ob.add(new Outbox.Entry(2L, 70001, 5L, false, new byte[]{8}, Priority.BULK));
        ob.add(new Outbox.Entry(3L, 70001, 5L, true, new byte[]{7}));
        Outbox again = new Outbox(st);
        again.load();
        check("priority: an SOS keeps its level after a restart", again.get(1L) != null && again.get(1L).priority == Priority.SOS);
        check("priority: a file keeps its level after a restart", again.get(2L) != null && again.get(2L).priority == Priority.BULK);
        check("priority: key frames and receipts are urgent", again.get(3L) != null && again.get(3L).priority == Priority.URGENT && again.get(3L).control);
        java.nio.ByteBuffer old = java.nio.ByteBuffer.allocate(8 + 4 + 8 + 1 + 2 + 3);      // the format before priorities existed
        old.putLong(4L).putInt(70001).putLong(5L).put((byte) 0).putShort((short) 1).put(new byte[]{1, 2, 3});
        st.put("ob-" + String.format("%016x", 4L), old.array());
        Outbox legacy = new Outbox(st);
        legacy.load();
        check("priority: an entry from before priorities loads as normal text, intact",
                legacy.get(4L) != null && legacy.get(4L).priority == Priority.NORMAL && legacy.get(4L).attempts == 1 && legacy.get(4L).frame.length == 3);

        MemNet net = new MemNet();
        Peer a = new Peer(net, null, 81, LAT0, LON0, false);
        Peer b = new Peer(net, null, 82, LAT0, LON0 + 0.004, false);
        net.link(81, 82);
        check("priority: pair is up", waitFor(() -> a.c.peerCount() == 1 && b.c.peerCount() == 1, 3000));
        long sos = a.c.sendSos("82", "help, I am at the bridge");
        check("priority: an SOS text arrives", waitFor(() -> b.p.texts.containsKey(sos), 8000));
        long urgent = a.c.sendText("82", "meeting moved", Priority.URGENT);
        long bg = a.c.sendBytes("82", new byte[]{1, 2, 3}, Priority.BACKGROUND);
        check("priority: urgent text and a background bytes message both arrive", waitFor(() -> b.p.texts.containsKey(urgent) && b.p.blobs.containsKey(bg), 8000));
        boolean threw = false;
        try { a.c.sendText("82", "x", 255); } catch (IllegalArgumentException e) { threw = true; }
        check("priority: 255 is the engine's own level, the app cannot use it", threw);
        stop(a, b);
    }

    /** The app decides who a user is: any string, nothing is issued by the engine. */
    static void appChosenIds() {
        MemNet net = new MemNet();
        IdMap map = new IdMap(null);
        String alice = "alice@example", bob = "Bob Builder";
        Probe pa = new Probe(), pb = new Probe();
        MeshClient ca = MeshClient.builder(alice).radio(net.radio(map.nodeOf(alice))).storage(new MemoryStorage()).listener(pa)
                .retry(400, 200).zoneHold(100).routing(MeshClient.Routing.FLAT).tune(cfg -> { cfg.ackTimeoutMs = 700; cfg.retryMs = 300; }).build();
        MeshClient cb = MeshClient.builder(bob).radio(net.radio(map.nodeOf(bob))).storage(new MemoryStorage()).listener(pb)
                .retry(400, 200).zoneHold(100).routing(MeshClient.Routing.FLAT).tune(cfg -> { cfg.ackTimeoutMs = 700; cfg.retryMs = 300; }).build();
        ca.updateLocation(LAT0, LON0);
        cb.updateLocation(LAT0, LON0 + 0.004);
        ca.start();
        cb.start();
        net.link(map.nodeOf(alice), map.nodeOf(bob));
        check("ids: two apps with arbitrary string ids see each other", waitFor(() -> ca.peerCount() == 1 && cb.peerCount() == 1, 3000));
        cb.knowUser(alice);                                           // Bob's app has Alice in its contacts
        long id = ca.sendText(bob, "hello Bob");
        check("ids: a text between arbitrary ids arrives", waitFor(() -> pb.texts.containsKey(id), 10000));
        check("ids: the receiver sees the sender's id back exactly as the app wrote it", pb.texts.get(id) != null && pb.texts.get(id).startsWith(alice + ":"));
        ca.stop();
        cb.stop();
    }

    static void voiceSelfTest() {
        VoiceSelfTest.Report ok = VoiceSelfTest.run(new ToneSource(), new CollectSink(), AdpcmCodec.FACTORY);
        check("selftest: a working microphone, loudspeaker and codec pass\n" + ok, ok.allOk() && ok.items.size() == 3);
        AudioSource silent = new AudioSource() {
            volatile boolean on;
            @Override public void start(int r) { on = true; }
            @Override public int read(short[] b) {
                try { Thread.sleep(60); } catch (InterruptedException e) { return -1; }
                return on ? b.length : -1;
            }
            @Override public void stop() { on = false; }
        };
        VoiceSelfTest.Report r1 = VoiceSelfTest.run(silent, new CollectSink(), AdpcmCodec.FACTORY);
        check("selftest: a microphone that only gives zeros fails (muted / blocked)", !r1.allOk() && !r1.items.get(0).ok && r1.items.get(1).ok);
        AudioSource denied = new AudioSource() {
            @Override public void start(int r) { throw new IllegalStateException("permission denied"); }
            @Override public int read(short[] b) { return -1; }
            @Override public void stop() { }
        };
        VoiceSelfTest.Report r2 = VoiceSelfTest.run(denied, new CollectSink(), AdpcmCodec.FACTORY);
        check("selftest: a microphone that cannot open fails and says why", !r2.items.get(0).ok && r2.items.get(0).detail.contains("permission denied"));
        AudioSink broken = new AudioSink() {
            @Override public void start(int r) { }
            @Override public void write(short[] p) { throw new IllegalStateException("audio track dead"); }
            @Override public void stop() { }
        };
        VoiceSelfTest.Report r3 = VoiceSelfTest.run(new ToneSource(), broken, AdpcmCodec.FACTORY);
        check("selftest: a loudspeaker that throws fails", !r3.items.get(1).ok && r3.items.get(1).detail.contains("audio track dead"));
        VoiceCodec.Factory mute = new VoiceCodec.Factory() {
            @Override public int id() { return 9; }
            @Override public VoiceCodec create() {
                return new VoiceCodec() {
                    @Override public int id() { return 9; }
                    @Override public int sampleRate() { return 8000; }
                    @Override public int blockSamples() { return 480; }
                    @Override public byte[] encode(short[] pcm) { return new byte[4]; }
                    @Override public short[] decode(byte[] b) { return new short[480]; }
                    @Override public short[] conceal() { return new short[480]; }
                };
            }
        };
        check("selftest: a codec that returns silence fails", !VoiceSelfTest.codecWorks(mute) && VoiceSelfTest.codecWorks(AdpcmCodec.FACTORY));
        VoiceCodec.Factory noisy = new VoiceCodec.Factory() {
            @Override public int id() { return 8; }
            @Override public VoiceCodec create() {
                return new VoiceCodec() {
                    final java.util.Random rnd = new java.util.Random(1);
                    @Override public int id() { return 8; }
                    @Override public int sampleRate() { return 8000; }
                    @Override public int blockSamples() { return 480; }
                    @Override public byte[] encode(short[] pcm) { return new byte[4]; }
                    @Override public short[] decode(byte[] b) { short[] o = new short[480]; for (int i = 0; i < o.length; i++) o[i] = (short) (rnd.nextInt(16000) - 8000); return o; }
                    @Override public short[] conceal() { return new short[480]; }
                };
            }
        };
        check("selftest: a codec that returns noise instead of the tone fails", !VoiceSelfTest.codecWorks(noisy));
    }

    /** Microphone tone -> codec -> encrypted mesh, 2 hops and a zone border -> codec -> loudspeaker. */
    static void pushToTalkAudio() {
        MemNet net = new MemNet();
        Peer a = new Peer(net, null, 61, LAT0, LON0, false);
        Peer b = new Peer(net, null, 62, LAT0, LON0 + 0.004, false);
        Peer c = new Peer(net, null, 63, LAT0, LON0 + 0.03, false);
        net.link(61, 62);
        net.link(62, 63);
        check("ptt: chain is up", waitFor(() -> a.c.peerCount() == 1 && b.c.peerCount() == 2, 3000));
        CollectSink speakerC = new CollectSink();
        PttEvents evA = new PttEvents(), evC = new PttEvents();
        PushToTalk pa = new PushToTalk(a.c, new ToneSource(), new CollectSink(), evA, AdpcmCodec.FACTORY);
        PushToTalk pc = new PushToTalk(c.c, new ToneSource(), speakerC, evC, AdpcmCodec.FACTORY);
        pa.start();
        pc.start();
        long ring = a.c.sendText("63", "calling you");
        check("ptt: ringing message arrives (route and keys)", waitFor(() -> c.p.texts.containsKey(ring), 12000));
        pa.press("63");
        try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
        pa.release();
        check("ptt: C hears the start and the end of the talk", waitFor(() -> evC.starts.get() == 1 && evC.ends.get() == 1, 5000));
        check("ptt: it is A talking", "61".equals(evC.from));
        long sent = pa.blocksSent();
        check("ptt: about 1.5 s of audio was sent (" + sent + " blocks of 60 ms)", sent >= 20 && sent <= 27);
        check("ptt: every block reached the loudspeaker", waitFor(() -> speakerC.blocks.size() >= sent - 1, 5000));
        int good = 0;
        for (short[] blk : speakerC.blocks) if (toneSnrDb(blk) > 18) good++;
        check("ptt: the played audio is the 440 Hz tone (" + good + " of " + speakerC.blocks.size() + " blocks clean)", good == speakerC.blocks.size() && good > 0);
        check("ptt: nothing concealed or dropped on a clean link", pc.blocksConcealed() == 0 && pc.blocksDropped() == 0);
        check("ptt: no errors", evA.errors.isEmpty() && evC.errors.isEmpty());
        pa.press("63");                                              // a second press is a second talk
        try { Thread.sleep(400); } catch (InterruptedException e) { return; }
        pa.release();
        check("ptt: a second press is a second talk", waitFor(() -> evC.starts.get() == 2 && evC.ends.get() == 2, 5000));
        pa.stop();
        pc.stop();
        stop(a, b, c);
    }

    static boolean inOrder(List<byte[]> frames) {
        for (int i = 0; i < frames.size(); i++) if ((frames.get(i)[0] & 0xFF) != i) return false;
        return true;
    }

    static void largeMessage() {
        MemNet net = new MemNet();
        Peer a = new Peer(net, null, 21, LAT0, LON0, false);
        Peer b = new Peer(net, null, 22, LAT0, LON0 + 0.004, false);
        Peer c = new Peer(net, null, 23, LAT0, LON0 + 0.03, false);   // another zone, only B is in radio range
        net.link(21, 22);
        net.link(22, 23);
        check("large: chain is up", waitFor(() -> a.c.peerCount() == 1 && b.c.peerCount() == 2, 3000));
        byte[] photo = randomBytes(200_000, 7);                         // 49 chunks of 4 KB
        long id = a.c.sendLarge("23", photo);
        check("large: 200 KB arrives byte-exact across two hops and a zone border (with key exchange)",
                waitFor(() -> c.p.blobs.containsKey(id) && Arrays.equals(c.p.blobs.get(id), photo), 60000));
        check("large: sender is told it was delivered", waitFor(() -> a.p.delivered.contains(id), 20000));
        check("large: progress reached all 49 chunks", waitFor(() -> Integer.valueOf(49).equals(a.p.progress.get(id)), 5000));
        check("large: the receiver got it exactly once", c.p.texts.get(id).equals("21:#200000") && a.p.largeFailed.isEmpty());
        long id2 = c.c.sendLarge("21", randomBytes(30_000, 8));
        check("large: and the other way round", waitFor(() -> a.p.blobs.containsKey(id2) && a.p.blobs.get(id2).length == 30_000, 30000));
        stop(a, b, c);
    }

    static void largeMessageRecoversAfterTheLinkDrops() {
        MemNet net = new MemNet();
        Peer a = new Peer(net, null, 31, LAT0, LON0, false);
        Peer b = new Peer(net, null, 32, LAT0, LON0 + 0.004, false);
        net.link(31, 32);
        check("recover: linked", waitFor(() -> a.c.peerCount() == 1, 3000));
        a.c.sendText("32", "warm up");                                  // exchange keys first
        check("recover: keys exchanged", waitFor(() -> a.c.fingerprintOf("32") != null && b.c.fingerprintOf("31") != null, 8000));
        byte[] data = randomBytes(120_000, 9);                          // 30 chunks
        long id = a.c.sendLarge("32", data);
        check("recover: transfer is under way", waitFor(() -> a.p.progress.getOrDefault(id, 0) >= 3, 10000));
        net.unlink(31, 32);                                             // the radio link is gone mid-transfer
        check("recover: the app is told (not silently stuck)", waitFor(() -> a.p.largeFailed.containsKey(id), 30000));
        int missing = a.p.largeFailed.get(id);
        check("recover: only part of it is missing", missing > 0 && missing < 30);
        net.link(31, 32);
        check("recover: link is back", waitFor(() -> a.c.peerCount() == 1, 3000));
        check("recover: retryLarge accepted", a.c.retryLarge(id));
        check("recover: completes and is byte-exact", waitFor(() -> b.p.blobs.containsKey(id) && Arrays.equals(b.p.blobs.get(id), data), 60000));
        check("recover: sender gets the confirmation", waitFor(() -> a.p.delivered.contains(id), 20000));
        stop(a, b);
    }

    static void largeMessageIsEncryptedOnTheWire() {
        MemNet net = new MemNet();
        final java.util.concurrent.atomic.AtomicBoolean leaked = new java.util.concurrent.atomic.AtomicBoolean();
        final byte[] marker = "TOP-SECRET-PLAINTEXT-MARKER-0123456789".getBytes(StandardCharsets.US_ASCII);
        net.tap = frame -> { if (indexOf(frame, marker) >= 0) leaked.set(true); };
        Peer a = new Peer(net, null, 41, LAT0, LON0, false);
        Peer b = new Peer(net, null, 42, LAT0, LON0 + 0.004, false);
        net.link(41, 42);
        check("wire: linked", waitFor(() -> a.c.peerCount() == 1, 3000));
        byte[] data = new byte[40_000];
        for (int i = 0; i + marker.length <= data.length; i += marker.length) System.arraycopy(marker, 0, data, i, marker.length);
        long id = a.c.sendLarge("42", data);
        check("wire: arrived", waitFor(() -> b.p.blobs.containsKey(id) && Arrays.equals(b.p.blobs.get(id), data), 30000));
        check("wire: not one radio frame contained the plain text", !leaked.get());
        stop(a, b);
    }

    static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    static void lowBatteryPhoneStopsRelaying() {
        MemNet net = new MemNet();
        Peer a = new Peer(net, null, 51, LAT0, LON0, false);
        Peer b = new Peer(net, null, 52, LAT0, LON0 + 0.004, false);   // the only phone between A and C
        Peer c = new Peer(net, null, 53, LAT0, LON0 + 0.008, false);
        net.link(51, 52);
        net.link(52, 53);
        check("battery: chain is up", waitFor(() -> a.c.peerCount() == 1 && b.c.peerCount() == 2, 3000));
        b.c.setBattery(0.10);
        long own = a.c.sendText("52", "for the relay itself");
        check("battery: a low phone still receives its own messages", waitFor(() -> "51:for the relay itself".equals(b.p.texts.get(own)), 10000));
        long blocked = a.c.sendText("53", "through the low phone");
        check("battery: but it no longer relays other people's traffic",
                !waitFor(() -> c.p.texts.containsKey(blocked), 5000));
        b.c.setBattery(0.90);
        check("battery: relaying resumes when the battery has recovered (nothing was lost)",
                waitFor(() -> "51:through the low phone".equals(c.p.texts.get(blocked)), 40000));
        stop(a, b, c);
    }

    static void stop(Peer... ps) { for (Peer p : ps) p.c.stop(); }

    public static void main(String[] args) {
        System.out.println("== unit ==");
        geo(); ids(); frames(); compression(); crypto(); voiceCrypto(); duty(); adpcmCodec();
        if (args.length > 0 && args[0].equals("unit")) { finish(); return; }
        System.out.println("== network (in-memory radios) ==");
        chainAcrossZones();
        offlineThenOnline();
        restartKeepsOutbox();
        viaServer();
        keyChangeIsNotSilent();
        movingChangesZone();
        priorityAndBlob();
        linkQualityAndLimits();
        gridDelivery();
        System.out.println("== voice, large messages and battery ==");
        voicePushToTalk();
        pushToTalkAudio();
        voiceSelfTest();
        priorityLevels();
        appChosenIds();
        largeMessage();
        largeMessageRecoversAfterTheLinkDrops();
        largeMessageIsEncryptedOnTheWire();
        lowBatteryPhoneStopsRelaying();
        finish();
    }

    static void finish() {
        System.out.println("\n" + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
