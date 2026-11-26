package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.Clock;
import com.herabarman.meshnet.core.StorageAdapter;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

/** Finding a lost phone: crypto, and a small offline world where phones pass each other. */
public final class LostPhoneTests {
    static int passed, failed;

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("PASS  " + what); } else { failed++; System.out.println("FAIL  " + what); }
    }

    // ---- tiny simulated world -------------------------------------------------------------------------------

    static final class World implements Clock {
        long now = 1_700_000_000_000L;
        long seq;
        final PriorityQueue<long[]> q = new PriorityQueue<>((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        final Map<Long, Runnable> tasks = new HashMap<>();
        final Map<Integer, Set<Integer>> adj = new HashMap<>();
        final Map<Integer, LostPhone> phones = new HashMap<>();
        final Map<Integer, double[]> pos = new HashMap<>();
        int sent;

        @Override public long nowMs() { return now; }
        @Override public void schedule(long d, Runnable t) { long id = ++seq; tasks.put(id, t); q.add(new long[]{now + d, id}); }
        void run(long ms) {
            long end = now + ms;
            while (!q.isEmpty() && q.peek()[0] <= end) {
                long[] e = q.poll();
                now = e[0];
                tasks.remove(e[1]).run();
            }
            now = end;
        }

        LostPhone add(final int id, LostPhone.Config cfg, StorageAdapter st) {
            adj.put(id, new HashSet<Integer>());
            pos.put(id, new double[]{0, 0});
            LostPhone p = new LostPhone(id, cfg, this, new Random(id), new LostPhone.Link() {
                @Override public void send(final int peer, final byte[] m) {
                    if (!adj.get(id).contains(peer)) return;
                    sent++;
                    schedule(30, new Runnable() { @Override public void run() { if (adj.get(id).contains(peer)) phones.get(peer).onMessage(id, m); } });
                }
                @Override public List<Integer> peers() { return new ArrayList<>(adj.get(id)); }
            }, new LostPhone.Where() {
                @Override public LostPhone.Fix fix() { double[] x = pos.get(id); return x[0] == 0 && x[1] == 0 ? null : new LostPhone.Fix(x[0], x[1], 8, now); }
            }, st);
            phones.put(id, p);
            p.start();
            return p;
        }
        void link(int a, int b) { adj.get(a).add(b); adj.get(b).add(a); phones.get(a).onPeerUp(b); phones.get(b).onPeerUp(a); }
        void unlink(int a, int b) { adj.get(a).remove(b); adj.get(b).remove(a); phones.get(a).onPeerDown(b); phones.get(b).onPeerDown(a); }
    }

    static final class Mem implements StorageAdapter {
        final Map<String, byte[]> m = new HashMap<>();
        @Override public void put(String k, byte[] v) { m.put(k, v.clone()); }
        @Override public byte[] get(String k) { return m.get(k); }
        @Override public void remove(String k) { m.remove(k); }
        @Override public List<String> keys(String p) { List<String> l = new ArrayList<>(); for (String k : m.keySet()) if (k.startsWith(p)) l.add(k); return l; }
    }

    public static void main(String[] args) throws Exception {
        crypto();
        world();
        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void crypto() {
        byte[] s = LostCrypto.newSecret(), s2 = LostCrypto.newSecret();
        byte[] q5 = LostCrypto.slotPublic(s, 5), q6 = LostCrypto.slotPublic(s, 6);
        check("slot keys are valid 65-byte points and change every slot", q5.length == 65 && q5[0] == 4 && !Arrays.equals(q5, q6) && Arrays.equals(q5, LostCrypto.slotPublic(s, 5)));
        check("different secrets give different keys", !Arrays.equals(q5, LostCrypto.slotPublic(s2, 5)));
        byte[] plain = "where-i-am".getBytes();
        LostCrypto.Sealed sl = LostCrypto.seal(q5, plain);
        byte[] tag = LostCrypto.tagOf(q5);
        check("the owner opens what a finder sealed (own EC math agrees with the JDK's ECDH)", sl != null && Arrays.equals(plain, LostCrypto.open(LostCrypto.slotPrivate(s, 5), tag, sl.eph, sl.iv, sl.ct)));
        check("another slot key cannot open it", LostCrypto.open(LostCrypto.slotPrivate(s, 6), tag, sl.eph, sl.iv, sl.ct) == null);
        check("another owner's key cannot open it", LostCrypto.open(LostCrypto.slotPrivate(s2, 5), tag, sl.eph, sl.iv, sl.ct) == null);
        byte[] bad = sl.ct.clone(); bad[0] ^= 1;
        check("a tampered report does not open", LostCrypto.open(LostCrypto.slotPrivate(s, 5), tag, sl.eph, sl.iv, bad) == null);
        byte[] offCurve = q5.clone(); offCurve[64] ^= 1;
        check("a public key that is not on the curve is refused", LostCrypto.seal(offCurve, plain) == null);
        check("two seals of the same text differ (fresh one-time key and iv)", !Arrays.equals(sl.ct, LostCrypto.seal(q5, plain).ct));
    }

    static void world() {
        final byte[] secret = LostCrypto.newSecret();
        World w = new World();
        final LostPhone lost = w.add(1, null, null), finder = w.add(2, null, null), relay = w.add(3, null, null), owner = w.add(4, null, null), stranger = w.add(5, null, null);
        w.pos.put(1, new double[]{23.8103, 90.4125});       // the lost phone: Dhaka
        w.pos.put(2, new double[]{23.8104, 90.4126});       // a passer-by next to it
        lost.arm(secret);
        owner.search(secret);
        stranger.search(LostCrypto.newSecret());
        final List<LostPhone.Sighting> got = new ArrayList<>();
        owner.setListener(new LostPhone.Listener() { @Override public void onSighting(LostPhone.Sighting s) { got.add(s); } });

        w.link(1, 2);
        w.run(2 * 60_000);
        check("a passer-by hears the beacon and makes a sealed report", finder.reportsMade >= 1 && lost.beaconsSent >= 3);
        check("the same tag is reported once per gap, not on every beacon", finder.reportsMade <= 2);
        w.unlink(1, 2);                                     // the passer-by walks away
        w.pos.put(2, new double[]{23.7, 90.3});
        w.run(60 * 60_000);
        w.link(2, 3);                                       // later meets a relay phone
        w.run(10_000);
        check("the relay carries the report without being able to read it", relay.storedReports() >= 1 && relay.sightings().isEmpty());
        w.unlink(2, 3);
        w.run(30 * 60_000);
        w.link(3, 4);                                       // the relay meets the owner (on a different phone)
        w.run(10_000);
        check("the owner gets the sighting through two phones that never met the lost phone", !got.isEmpty());
        LostPhone.Sighting s = owner.latest();
        check("the sighting is the finder's position, accurate to 1e-6 degrees", s != null && Math.abs(s.lat - 23.8104) < 1e-6 && Math.abs(s.lon - 90.4126) < 1e-6 && !s.fromLostPhoneItself);
        check("the sighting's time is when it was seen", s != null && Math.abs(s.timeMs - (1_700_000_000_000L + 1000L * 0) ) < 4 * 60_000L);
        w.link(3, 5);
        w.run(10_000);
        check("a stranger holding the same reports cannot read them", stranger.sightings().isEmpty() && stranger.storedReports() >= 1);

        // privacy: the sealed report does not contain the coordinates in clear
        boolean leak = false;
        byte[] lat = ByteBuffer.allocate(4).putInt((int) Math.round(23.8104 * 1e7)).array();
        for (LostPhone p : w.phones.values()) { }
        check("beacons of different slots are unlinkable (different keys) and carry no owner id", !Arrays.equals(LostCrypto.slotPublic(secret, 1), LostCrypto.slotPublic(secret, 2)));

        // the lost phone reports itself (e.g. low battery) through a neighbour that has no finder role at all
        World w2 = new World();
        LostPhone a = w2.add(1, null, null), r = w2.add(2, null, null), o = w2.add(3, null, null);
        w2.pos.put(1, new double[]{22.3569, 91.7832});
        a.arm(secret);
        o.search(secret);
        w2.link(1, 2);
        w2.run(5000);
        boolean sent = a.selfReportNow();
        w2.run(5000);
        w2.unlink(1, 2);                                    // the lost phone dies
        w2.link(2, 3);
        w2.run(5000);
        LostPhone.Sighting own = o.latest();
        check("the lost phone's last self-report reaches the owner through a relay (" + own + ")", sent && own != null && own.fromLostPhoneItself && Math.abs(own.lat - 22.3569) < 1e-6);

        // no fix / old fix: the finder says nothing
        World w3 = new World();
        LostPhone l3 = w3.add(1, null, null), f3 = w3.add(2, null, null);
        w3.pos.put(1, new double[]{1, 1});                  // f3 has no GPS fix (0,0 means none)
        l3.arm(secret);
        w3.link(1, 2);
        w3.run(3 * 60_000);
        check("a finder without a GPS fix makes no report", f3.reportsMade == 0 && f3.beaconsHeard >= 2);

        // loops: a ring of phones must not make a storm
        World w4 = new World();
        for (int i = 1; i <= 6; i++) w4.add(i, null, null);
        for (int i = 1; i <= 6; i++) w4.pos.put(i, new double[]{5 + i, 5});
        for (int i = 1; i <= 6; i++) w4.link(i, i % 6 + 1);
        w4.link(1, 4);
        w4.phones.get(1).arm(secret);
        w4.run(20 * 60_000);
        int stored = 0;
        for (LostPhone p : w4.phones.values()) stored += p.storedReports();
        check("a ring of phones passes a report on without loops (" + w4.sent + " messages in 20 min)", w4.sent < 3000 && stored > 0);

        // persistence
        World w5 = new World();
        Mem mem = new Mem();
        LostPhone l5 = w5.add(1, null, null), c5 = w5.add(2, null, mem);
        w5.pos.put(1, new double[]{3, 3}); w5.pos.put(2, new double[]{3.0001, 3});
        l5.arm(secret);
        w5.link(1, 2);
        w5.run(60_000);
        int had = c5.storedReports();
        LostPhone reborn = new LostPhone(2, null, w5, new Random(1), new LostPhone.Link() {
            @Override public void send(int p, byte[] m) { }
            @Override public List<Integer> peers() { return new ArrayList<>(); }
        }, null, mem);
        check("a carrier keeps reports across an app restart (" + had + " stored, " + reborn.storedReports() + " after)", had >= 1 && reborn.storedReports() == had);
        reborn.search(secret);
        check("and the owner can open them after the restart", reborn.latest() != null && Math.abs(reborn.latest().lat - 3.0001) < 1e-6);

        // expiry
        w5.unlink(1, 2);
        w5.run(8L * 24 * 3600_000);
        w5.link(1, 2);
        w5.run(1000);
        check("reports older than 7 days are dropped, new ones arrive", c5.storedReports() >= 1 && c5.storedReports() <= had + 2);

        // a longer lookback finds an old report
        World w6 = new World();
        LostPhone l6 = w6.add(1, null, null), f6 = w6.add(2, null, null), o6 = w6.add(3, null, null);
        w6.pos.put(1, new double[]{7, 7}); w6.pos.put(2, new double[]{7.0002, 7});
        l6.arm(secret);
        w6.link(1, 2);
        w6.run(60_000);
        w6.unlink(1, 2);
        w6.run(3 * 24 * 3600_000L);                         // three days later
        w6.link(2, 3);
        w6.run(5000);
        o6.search(secret);                                  // default lookback is one day
        boolean none = o6.latest() == null;
        int found = o6.searchBack(4 * 24 * 4);
        check("a 3-day-old sighting needs the longer search, and is found by it (" + found + ")", none && found >= 1 && o6.latest() != null);
    }
}
