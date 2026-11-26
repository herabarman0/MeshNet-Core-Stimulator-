package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.Clock;
import com.herabarman.meshnet.core.StorageAdapter;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Finding a phone that is lost, offline: no internet, no SIM needed, only other phones running the app.
 *
 * Before the phone is lost, the owner "arms" it with a secret (kept elsewhere: written down, or on another phone):
 * <ul>
 * <li>the armed phone sends a small beacon to every neighbour, carrying a public key that changes every 15 minutes
 *     (nobody can link beacons of different slots, nobody learns who the owner is);</li>
 * <li>any phone that receives a beacon encrypts "I saw it here, at this time" (its own GPS fix) to that key and
 *     passes the sealed report on from phone to phone (carried up to 7 days, saved to disk);</li>
 * <li>the owner, on any phone that has the secret, opens the reports that match one of the keys derived from it. Nobody
 *     else can read a sighting, and relays cannot tell who it is for;</li>
 * <li>the armed phone also reports its OWN last GPS fix this way ({@link #selfReportNow}, call it at low battery too).</li>
 * </ul>
 * Honest limits: it only works while some phone with the app comes within radio range of the lost phone, and while the
 * lost phone has charge, a running app and its radios on. A thief who switches the phone off or uninstalls the app defeats it.
 * Reports are only as good as the finder's GPS fix: the sighting says "a phone with the app was here when it heard the beacon".
 * Engine thread only (the kit wires it onto the MeshClient thread).
 */
public final class LostPhone {
    public static final int OP_BEACON = 1, OP_REPORT = 2;
    public static final int REPORT_LEN = 1 + 1 + LostCrypto.TAG + LostCrypto.POINT + LostCrypto.IV + 15 + 16;

    public static final class Config {
        public long slotMs = 15 * 60_000L;
        public long beaconMs = 30_000;
        public long selfReportMs = 10 * 60_000L;
        public long sightingGapMs = 5 * 60_000L;          // a finder reports one tag at most this often
        public long ttlMs = 7L * 24 * 3600_000;
        public int maxReports = 500, maxHops = 10, pushOnPeerUp = 10;
        public long maxFixAgeMs = 10 * 60_000L;            // a finder without a fresh fix says nothing
        public int lookbackSlots = 96;                      // what the owner checks as reports arrive (a day); searchBack() goes further
    }

    public interface Link {
        void send(int peer, byte[] message);
        List<Integer> peers();
    }

    public static final class Fix {
        public final double lat, lon;
        public final int accuracyM;
        public final long timeMs;
        public Fix(double lat, double lon, int accuracyM, long timeMs) { this.lat = lat; this.lon = lon; this.accuracyM = accuracyM; this.timeMs = timeMs; }
    }

    public interface Where { Fix fix(); }

    public static final class Sighting {
        public final double lat, lon;
        public final int accuracyM;
        public final long timeMs;
        public final boolean fromLostPhoneItself;
        public final long slot;
        Sighting(double lat, double lon, int acc, long t, boolean self, long slot) { this.lat = lat; this.lon = lon; accuracyM = acc; timeMs = t; fromLostPhoneItself = self; this.slot = slot; }
        @Override public String toString() { return String.format("%.5f,%.5f +-%dm @%d%s", lat, lon, accuracyM, timeMs, fromLostPhoneItself ? " (own)" : ""); }
    }

    public interface Listener { void onSighting(Sighting s); }

    private static final class Report {
        final byte[] body;          // op, hops, tag, eph, iv, ct (hops byte is excluded from the id)
        final long id, storedAt;
        Report(byte[] b, long at) { body = b; storedAt = at; id = idOf(b); }
        static long idOf(byte[] b) {
            byte[] c = b.clone();
            c[1] = 0;
            return ByteBuffer.wrap(LostCrypto.sha256(c)).getLong();
        }
    }

    private final int self;
    private final Config cfg;
    private final Clock clock;
    private final Random rng;
    private final Link link;
    private final Where where;
    private final StorageAdapter store;
    private Listener listener;
    private boolean running;

    private byte[] armedSecret, ownerSecret;
    private final Map<Long, Report> reports = new LinkedHashMap<>();
    private final Map<Integer, LinkedHashSet<Long>> sentTo = new HashMap<>();
    private final Map<Long, Long> lastSighting = new HashMap<>();                  // tag -> time we last reported it
    private final Map<Long, Long> tagToSlot = new HashMap<>();                     // owner side
    private final Map<Long, BigInteger> slotKeys = new HashMap<>();
    private final Map<Long, Boolean> opened = new HashMap<>();                     // report id -> done
    private final List<Sighting> sightings = new ArrayList<>();
    public int beaconsSent, beaconsHeard, reportsMade, reportsRelayed, reportsDropped, sightingsFound;

    public LostPhone(int self, Config cfg, Clock clock, Random rng, Link link, Where where, StorageAdapter store) {
        this.self = self; this.cfg = cfg == null ? new Config() : cfg; this.clock = clock; this.rng = rng; this.link = link; this.where = where; this.store = store;
        if (store != null) load();
    }

    public void setListener(Listener l) { this.listener = l; }

    // ---- control -----------------------------------------------------------------------------------------------

    /** Start beaconing (this phone may get lost). The secret must be kept by the owner somewhere else too. */
    public void arm(byte[] secret) { armedSecret = secret.clone(); }

    public void disarm() { armedSecret = null; }

    public boolean armed() { return armedSecret != null; }

    /** Look for sightings of a phone armed with this secret (on this or any other phone). */
    public void search(byte[] secret) {
        ownerSecret = secret.clone();
        tagToSlot.clear(); slotKeys.clear();
        sightings.clear(); opened.clear();
        rescan(cfg.lookbackSlots);
    }

    public void stopSearch() { ownerSecret = null; }

    /** Go further back than the automatic lookback; @return new sightings found. Costs about 15 ms per slot on a PC, several times that on a phone. */
    public int searchBack(int slots) {
        if (ownerSecret == null) return 0;
        int before = sightings.size();
        rescan(slots);
        return sightings.size() - before;
    }

    public List<Sighting> sightings() {
        List<Sighting> l = new ArrayList<>(sightings);
        Collections.sort(l, new Comparator<Sighting>() { @Override public int compare(Sighting a, Sighting b) { return Long.compare(b.timeMs, a.timeMs); } });
        return l;
    }

    /** The most recent sighting, or null. */
    public Sighting latest() { List<Sighting> l = sightings(); return l.isEmpty() ? null : l.get(0); }

    public int storedReports() { return reports.size(); }

    public void start() {
        if (running) return;
        running = true;
        clock.schedule(cfg.beaconMs, new Runnable() { @Override public void run() { beaconTick(); } });
        clock.schedule(cfg.selfReportMs, new Runnable() { @Override public void run() { selfTick(); } });
    }

    public void stop() { running = false; }

    // ---- sending -------------------------------------------------------------------------------------------------

    private long slotNow() { return clock.nowMs() / cfg.slotMs; }

    private void beaconTick() {
        if (!running) return;
        if (armedSecret != null) {
            byte[] m = ByteBuffer.allocate(1 + LostCrypto.POINT).put((byte) OP_BEACON).put(LostCrypto.slotPublic(armedSecret, slotNow())).array();
            for (int p : link.peers()) { link.send(p, m); beaconsSent++; }
        }
        clock.schedule(cfg.beaconMs, new Runnable() { @Override public void run() { beaconTick(); } });
    }

    private void selfTick() {
        if (!running) return;
        if (armedSecret != null && !link.peers().isEmpty()) selfReportNow();
        clock.schedule(cfg.selfReportMs, new Runnable() { @Override public void run() { selfTick(); } });
    }

    /** The armed phone seals its own last fix to its own key and hands it to neighbours (call at low battery too). */
    public boolean selfReportNow() {
        if (armedSecret == null || where == null) return false;
        Fix f = where.fix();
        if (f == null) return false;
        return makeReport(LostCrypto.slotPublic(armedSecret, slotNow()), f, true);
    }

    private boolean makeReport(byte[] slotPub, Fix f, boolean own) {
        ByteBuffer p = ByteBuffer.allocate(15)
                .putInt((int) Math.round(f.lat * 1e7)).putInt((int) Math.round(f.lon * 1e7)).putShort((short) Math.min(32767, Math.max(0, f.accuracyM)))
                .putInt((int) (f.timeMs / 1000)).put((byte) (own ? 1 : 0));
        LostCrypto.Sealed s = LostCrypto.seal(slotPub, p.array());
        if (s == null) return false;
        byte[] body = ByteBuffer.allocate(REPORT_LEN).put((byte) OP_REPORT).put((byte) 0).put(LostCrypto.tagOf(slotPub)).put(s.eph).put(s.iv).put(s.ct).array();
        reportsMade++;
        accept(-1, body);
        return true;
    }

    // ---- receiving -------------------------------------------------------------------------------------------------

    public void onPeerUp(int peer) {
        List<Report> newest = new ArrayList<>(reports.values());
        Collections.reverse(newest);
        int n = 0;
        for (Report r : newest) {
            if (n >= cfg.pushOnPeerUp) break;
            if (markSent(peer, r.id)) { send(peer, r, 1); n++; }
        }
    }

    public void onPeerDown(int peer) { sentTo.remove(peer); }

    public void onMessage(int peer, byte[] m) {
        if (m == null || m.length < 1) return;
        if (m[0] == OP_BEACON && m.length == 1 + LostCrypto.POINT) heardBeacon(Arrays.copyOfRange(m, 1, m.length));
        else if (m[0] == OP_REPORT && m.length == REPORT_LEN) accept(peer, m);
    }

    private void heardBeacon(byte[] pub) {
        beaconsHeard++;
        if (where == null) return;
        long tag = ByteBuffer.wrap(LostCrypto.tagOf(pub)).getLong(), now = clock.nowMs();
        Long last = lastSighting.get(tag);
        if (last != null && now - last < cfg.sightingGapMs) return;
        Fix f = where.fix();
        if (f == null || now - f.timeMs > cfg.maxFixAgeMs) return;
        if (makeReport(pub, f, false)) {
            lastSighting.put(tag, now);
            if (lastSighting.size() > 2000) lastSighting.clear();
        }
    }

    private void accept(int from, byte[] body) {
        long now = clock.nowMs();
        Report r = new Report(body.clone(), now);
        if (reports.containsKey(r.id)) return;
        if (body[1] > cfg.maxHops) { reportsDropped++; return; }
        expire(now);
        while (reports.size() >= cfg.maxReports) { Long oldest = reports.keySet().iterator().next(); drop(oldest); }
        reports.put(r.id, r);
        persist(r);
        if (ownerSecret != null) tryOpen(r);
        int hops = body[1] + 1;
        if (hops > cfg.maxHops) return;
        for (int p : link.peers()) {
            if (p == from) continue;
            if (markSent(p, r.id)) { send(p, r, hops); reportsRelayed++; }
        }
    }

    private boolean markSent(int peer, long id) {
        LinkedHashSet<Long> s = sentTo.get(peer);
        if (s == null) { s = new LinkedHashSet<>(); sentTo.put(peer, s); }
        if (!s.add(id)) return false;
        if (s.size() > 400) { java.util.Iterator<Long> it = s.iterator(); it.next(); it.remove(); }
        return true;
    }

    private void send(int peer, Report r, int hops) {
        byte[] m = r.body.clone();
        m[1] = (byte) hops;
        link.send(peer, m);
    }

    private void expire(long now) {
        List<Long> dead = new ArrayList<>();
        for (Report r : reports.values()) if (now - r.storedAt > cfg.ttlMs) dead.add(r.id);
        for (Long id : dead) drop(id);
    }

    private void drop(long id) {
        reports.remove(id);
        if (store != null) store.remove("lost.r." + Long.toHexString(id));
    }

    // ---- owner side ----------------------------------------------------------------------------------------------------

    private void index(long from, long to) {
        for (long s = from; s <= to; s++) {
            if (slotKeys.containsKey(s)) continue;
            BigInteger d = LostCrypto.slotPrivate(ownerSecret, s);
            slotKeys.put(s, d);
            tagToSlot.put(ByteBuffer.wrap(LostCrypto.tagOf(LostCrypto.slotPublic(ownerSecret, s))).getLong(), s);
        }
    }

    private void rescan(int lookback) {
        long now = slotNow();
        index(now - lookback, now + 1);
        for (Report r : new ArrayList<>(reports.values())) tryOpen(r);
    }

    private void tryOpen(Report r) {
        if (opened.containsKey(r.id)) return;
        long slotNowIdx = slotNow();
        index(slotNowIdx - 1, slotNowIdx + 1);
        long tag = ByteBuffer.wrap(r.body, 2, LostCrypto.TAG).getLong();
        Long slot = tagToSlot.get(tag);
        if (slot == null) return;
        byte[] tagB = Arrays.copyOfRange(r.body, 2, 2 + LostCrypto.TAG);
        int o = 2 + LostCrypto.TAG;
        byte[] eph = Arrays.copyOfRange(r.body, o, o + LostCrypto.POINT); o += LostCrypto.POINT;
        byte[] iv = Arrays.copyOfRange(r.body, o, o + LostCrypto.IV); o += LostCrypto.IV;
        byte[] ct = Arrays.copyOfRange(r.body, o, r.body.length);
        byte[] plain = LostCrypto.open(slotKeys.get(slot), tagB, eph, iv, ct);
        if (plain == null || plain.length != 15) return;
        opened.put(r.id, true);
        ByteBuffer b = ByteBuffer.wrap(plain);
        Sighting s = new Sighting(b.getInt() / 1e7, b.getInt() / 1e7, b.getShort() & 0xFFFF, (b.getInt() & 0xFFFFFFFFL) * 1000, b.get() == 1, slot);
        sightings.add(s);
        sightingsFound++;
        if (listener != null) listener.onSighting(s);
    }

    // ---- persistence ------------------------------------------------------------------------------------------------------

    private void persist(Report r) {
        if (store == null) return;
        store.put("lost.r." + Long.toHexString(r.id), ByteBuffer.allocate(8 + r.body.length).putLong(r.storedAt).put(r.body).array());
    }

    private void load() {
        long now = clock.nowMs();
        for (String k : store.keys("lost.r.")) {
            byte[] v = store.get(k);
            if (v == null || v.length != 8 + REPORT_LEN) { store.remove(k); continue; }
            long at = ByteBuffer.wrap(v).getLong();
            if (now - at > cfg.ttlMs) { store.remove(k); continue; }
            Report r = new Report(Arrays.copyOfRange(v, 8, v.length), at);
            reports.put(r.id, r);
        }
    }
}
