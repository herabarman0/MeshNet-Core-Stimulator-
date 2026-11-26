package com.herabarman.meshnet.kit;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emergency broadcast without internet: SOS, warnings, shared map pins. An alert is signed, limited to a
 * geohash area, and spreads from phone to phone (link-level broadcast, no keys needed, nothing to look up).
 * Phones that are not in the area (or within one cell of it) do not pass it on; phones that meet later get the
 * alerts that are still valid, so it also crosses gaps on foot.
 *
 * Trust: every alert carries the author's public key and a signature, so it cannot be altered on the way.
 * Alerts signed by a key in `trustedAuthorities` (the app ships the civil-defence keys) are OFFICIAL and may cover any
 * area, even the whole world (empty area). Anyone else can send an alert (an SOS) but it is marked unofficial,
 * must name an area of at least 4 characters (about 40 x 20 km) and is limited to one new alert per 30 s per key.
 * What this does NOT stop: someone creating many throw-away keys to spam unofficial alerts (a phone only shows
 * them when it is inside the area and the app can hide unofficial ones); and a jammer, which no software stops.
 *
 * Wire (link broadcast kind 0x01): ver(1) id(8) type(1) expirySec(4) areaLen(1) area lat(f32) lon(f32)
 * textLen(1) text pub(65) sigLen(1) sig(DER) ttl(1). The signature covers everything before sigLen; ttl is not signed.
 */
public final class Alerts {
    public static final int KIND = 0x01;
    public static final int SOS = 1, WARNING = 2, PIN = 3, INFO = 4;
    static final int MAX_TEXT = 100, MAX_STORED = 64, START_TTL = 12;
    static final long MAX_LIFETIME_MS = 7L * 24 * 3600 * 1000, AUTHOR_GAP_MS = 30_000;

    public static final class Alert {
        public final long id;
        public final int type;
        public final boolean official;
        public final String authorFingerprint;
        public final long expiresAtMs;
        public final String area;
        public final double lat, lon;
        public final String text;
        Alert(long id, int type, boolean official, String fp, long exp, String area, double lat, double lon, String text) {
            this.id = id; this.type = type; this.official = official; this.authorFingerprint = fp;
            this.expiresAtMs = exp; this.area = area; this.lat = lat; this.lon = lon; this.text = text;
        }
    }

    public interface Listener { void onAlert(Alert a); }

    private static final class Parsed {
        long id; int type; long expiryMs; String area; double lat, lon; String text; byte[] pub, sig, signed; int ttl; byte[] raw;
    }

    private final MeshClient c;
    private final List<byte[]> trusted;
    private final Listener listener;
    private final Map<Long, Parsed> store = new LinkedHashMap<>();
    private final Map<String, Long> lastFromAuthor = new HashMap<>();
    private final SecureRandom rnd = new SecureRandom();

    /** Create before MeshClient.start(). */
    public Alerts(MeshClient c, List<byte[]> trustedAuthorities, Listener listener) {
        this.c = c;
        this.trusted = new ArrayList<>(trustedAuthorities);
        this.listener = listener;
        c.registerBroadcast(KIND, new MeshClient.BroadcastHandler() {
            @Override public void onBroadcast(int from, byte[] data) { ingest(data); }
        });
        c.addNeighborObserver(new MeshClient.NeighborObserver() {
            @Override public void onNeighbor(int node, boolean up) { if (up) catchUp(node); }
        });
    }

    /**
     * Send an alert. area = geohash prefix ("wh0r" ...) that it applies to; empty only for official keys.
     * @return the alert id
     */
    public long send(final int type, String text, double lat, double lon, String area, long ttlMs) {
        final long id = rnd.nextLong() & Long.MAX_VALUE;
        final byte[] data = encode(c.identity(), id, type, System.currentTimeMillis() + ttlMs, area, lat, lon, text, START_TTL);
        c.post(new Runnable() {
            @Override public void run() {
                Parsed p = parse(data);
                if (p == null) return;
                store.put(id, p);
                c.broadcast(KIND, data);
            }
        });
        return id;
    }

    // ------------------------------------------------------------------ wire

    public static byte[] encode(IdentityKeys key, long id, int type, long expiryMs, String area, double lat, double lon, String text, int ttl) {
        byte[] a = area.getBytes(StandardCharsets.US_ASCII);
        byte[] t = text.getBytes(StandardCharsets.UTF_8);
        if (a.length > 12 || t.length > MAX_TEXT) throw new IllegalArgumentException("area <= 12 chars, text <= 100 bytes");
        byte[] pub = key.publicRaw();
        ByteBuffer b = ByteBuffer.allocate(1 + 8 + 1 + 4 + 1 + a.length + 8 + 1 + t.length + 65);
        b.put((byte) 1).putLong(id).put((byte) type).putInt((int) (expiryMs / 1000)).put((byte) a.length).put(a)
                .putFloat((float) lat).putFloat((float) lon).put((byte) t.length).put(t).put(pub);
        byte[] signed = b.array();
        byte[] sig = key.sign(signed);
        ByteBuffer o = ByteBuffer.allocate(signed.length + 1 + sig.length + 1);
        o.put(signed).put((byte) sig.length).put(sig).put((byte) ttl);
        return o.array();
    }

    public static Alert decodeVerified(byte[] wire, List<byte[]> trustedAuthorities) {
        Parsed p = parse(wire);
        if (p == null || !IdentityKeys.verify(p.pub, p.signed, p.sig)) return null;
        boolean official = false;
        if (trustedAuthorities != null) {
            for (byte[] k : trustedAuthorities) if (Arrays.equals(k, p.pub)) { official = true; break; }
        }
        return new Alert(p.id, p.type, official, IdentityKeys.fingerprint(p.pub), p.expiryMs, p.area, p.lat, p.lon, p.text);
    }

    private static Parsed parse(byte[] d) {
        try {
            ByteBuffer b = ByteBuffer.wrap(d);
            if (b.get() != 1) return null;
            Parsed p = new Parsed();
            p.id = b.getLong();
            p.type = b.get() & 0xFF;
            p.expiryMs = (b.getInt() & 0xFFFFFFFFL) * 1000L;
            byte[] a = new byte[b.get() & 0xFF];
            b.get(a);
            p.area = new String(a, StandardCharsets.US_ASCII);
            p.lat = b.getFloat();
            p.lon = b.getFloat();
            byte[] t = new byte[b.get() & 0xFF];
            b.get(t);
            p.text = new String(t, StandardCharsets.UTF_8);
            p.pub = new byte[65];
            b.get(p.pub);
            int signedLen = b.position();
            p.signed = Arrays.copyOf(d, signedLen);
            p.sig = new byte[b.get() & 0xFF];
            b.get(p.sig);
            p.ttl = b.get() & 0xFF;
            if (b.hasRemaining() || p.type < 1 || p.type > 4) return null;
            p.raw = d;
            return p;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ receive / forward (engine thread)

    void ingest(byte[] data) {
        Parsed p = parse(data);
        if (p == null) return;
        long now = c.now();
        if (p.expiryMs <= now || p.expiryMs > now + MAX_LIFETIME_MS || store.containsKey(p.id)) return;
        if (!IdentityKeys.verify(p.pub, p.signed, p.sig)) return;
        boolean official = false;
        for (byte[] k : trusted) if (Arrays.equals(k, p.pub)) official = true;
        String fp = IdentityKeys.fingerprint(p.pub);
        if (!official) {
            if (p.area.length() < 4) return;                                     // unofficial alerts need a bounded area
            Long last = lastFromAuthor.get(fp);
            if (last != null && now - last < AUTHOR_GAP_MS) return;              // one new alert per author every 30 s
            lastFromAuthor.put(fp, now);
        }
        store.put(p.id, p);
        while (store.size() > MAX_STORED) store.remove(store.keySet().iterator().next());
        String gh = c.currentGeohash();
        boolean inside = gh != null && (p.area.isEmpty() ? official : gh.startsWith(p.area));
        if (inside) listener.onAlert(new Alert(p.id, p.type, official, fp, p.expiryMs, p.area, p.lat, p.lon, p.text));
        boolean near = gh != null && p.area.length() > 1 && gh.startsWith(p.area.substring(0, p.area.length() - 1));
        if (p.ttl > 0 && (inside || near)) {
            byte[] fwd = data.clone();
            fwd[fwd.length - 1] = (byte) (p.ttl - 1);
            c.broadcast(KIND, fwd);
        }
    }

    /** A phone I just met gets the alerts that are still valid (they may have been out of range when they were sent). */
    private void catchUp(int node) {
        long now = c.now();
        int sent = 0;
        List<Parsed> all = new ArrayList<>(store.values());
        for (int i = all.size() - 1; i >= 0 && sent < 20; i--) {
            Parsed p = all.get(i);
            if (p.expiryMs <= now || p.ttl <= 0) continue;
            byte[] fwd = p.raw.clone();
            fwd[fwd.length - 1] = (byte) (p.ttl - 1);
            c.sendToNeighbor(node, KIND, fwd);
            sent++;
        }
    }
}
