package com.herabarman.meshnet.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs mini-apps on the mesh. Zero config: apps announce themselves to neighbours, so a game or a market finds its
 * players without anyone typing an address. The host sits between the app and the mesh and enforces limits: message size,
 * send rate (token bucket), storage quota, and an app that keeps throwing exceptions is stopped instead of taking the engine with it.
 *
 * Frame on the wire: type(1) [appIdLen(1) appId] body. Types: 0 announce (list of id+version), 1 nearby, 2 flood, 3 direct.
 */
public final class MiniAppHost {
    public static final int MAX_DIRECT = 4096, MAX_NEARBY = 480;
    private static final int T_ANNOUNCE = 0, T_NEARBY = 1, T_FLOOD = 2, T_DIRECT = 3, T_FLOOD_SIGNED = 4;

    /** How the host reaches the mesh (the kit wires this to MeshClient). */
    public interface Net {
        int selfId();
        void sendReliable(int node, byte[] frame);
        void sendNeighbor(int node, byte[] frame);
        Set<Integer> neighbors();
    }

    public static final class Limits {
        public double burst = 20, perSecond = 10;          // messages
        public int storageBytes = 256 * 1024, maxKeys = 256, maxKeyLen = 64;
        public int maxErrors = 5;
        public long announceMs = 30_000;
    }

    private final class Slot implements MiniAppContext {
        final MiniApp app;
        boolean running;
        int errors;
        double tokens;
        long lastRefill;
        final Set<Integer> peers = new HashSet<>();
        final Map<String, byte[]> store = new HashMap<>();
        int storedBytes;
        final DedupCache seen = new DedupCache(512, 120_000);

        Slot(MiniApp app) { this.app = app; tokens = limits.burst; lastRefill = clock.nowMs(); }

        boolean take() {
            long now = clock.nowMs();
            tokens = Math.min(limits.burst, tokens + (now - lastRefill) / 1000.0 * limits.perSecond);
            lastRefill = now;
            if (tokens < 1) { throttled++; return false; }
            tokens -= 1;
            return true;
        }

        @Override public int selfId() { return net.selfId(); }
        @Override public long now() { return clock.nowMs(); }
        @Override public Set<Integer> peersRunning() { return new HashSet<>(peers); }
        @Override public void log(String m) { if (logger != null) logger.log(app.id(), m); }

        @Override public void schedule(long delayMs, final Runnable task) {
            clock.schedule(delayMs, new Runnable() {
                @Override public void run() { if (running) guard(Slot.this, task); }
            });
        }

        @Override public boolean sendTo(int node, byte[] data) {
            if (!running || data.length > MAX_DIRECT || !take()) return false;
            net.sendReliable(node, frame(T_DIRECT, app.id(), data));
            return true;
        }

        @Override public boolean sendNearby(byte[] data) {
            if (!running || data.length > MAX_NEARBY || !take()) return false;
            byte[] f = frame(T_NEARBY, app.id(), data);
            for (int p : peers) net.sendNeighbor(p, f);
            return true;
        }

        @Override public boolean floodNearby(byte[] data, int hops) {
            if (!running || data.length > MAX_NEARBY - 9 || !take()) return false;
            long id = new java.util.Random().nextLong();
            seen.firstSeen(id, clock.nowMs());
            byte[] f;
            if (signer != null) {
                byte[] sig = signer.sign(floodSigned(app.id(), id, net.selfId(), data));
                if (sig.length > 120 || data.length + 9 + 5 + sig.length > MAX_NEARBY) return false;
                f = frame(T_FLOOD_SIGNED, app.id(), signedBody(Math.max(1, Math.min(8, hops)), id, net.selfId(), sig, data));
            } else f = frame(T_FLOOD, app.id(), floodBody(Math.max(1, Math.min(8, hops)), id, data));
            for (int p : peers) net.sendNeighbor(p, f);
            return true;
        }

        @Override public boolean save(String key, byte[] value) {
            if (key == null || key.length() > limits.maxKeyLen || value == null) return false;
            byte[] old = store.get(key);
            int delta = value.length - (old == null ? 0 : old.length);
            if ((old == null && store.size() >= limits.maxKeys) || storedBytes + delta > limits.storageBytes) return false;
            store.put(key, value.clone());
            storedBytes += delta;
            if (storage != null) storage.put("app/" + app.id() + "/" + key, value);
            return true;
        }

        @Override public byte[] load(String key) {
            byte[] v = store.get(key);
            return v == null ? null : v.clone();
        }
    }

    public interface Logger { void log(String appId, String message); }

    private final Net net;
    private final Clock clock;
    private final StorageAdapter storage;
    private final Limits limits;
    private final Map<String, Slot> slots = new HashMap<>();
    private final Map<Integer, Map<String, Integer>> neighborApps = new HashMap<>();    // neighbour -> app id -> version
    private Logger logger;
    private boolean running;
    private RecordAuth signer;
    private KeyLookup keys;
    private boolean requireSigned;
    public int throttled, dropped, crashes, unverified;

    /** Public key of a node, as the signer's scheme encodes it (the kit passes its key directory); null if unknown. */
    public interface KeyLookup { byte[] keyOf(int node); }

    /**
     * Authenticate flooded frames. With a signer, floodNearby() sends frames signed by this phone (signature over app id, flood id,
     * origin and data); a receiver that knows the origin's key checks it, and the app's onNearby() then gets the VERIFIED ORIGIN as
     * "from" (not the last relay). A flooded frame that fails the check is dropped and counted in {@code unverified}.
     * With requireSigned, unsigned flooded frames are dropped too, and so are signed ones whose origin key is not known here.
     * Frames stay readable by anyone in range (they are broadcasts): this is authenticity, not secrecy.
     */
    public void setAuth(RecordAuth signer, KeyLookup keys, boolean requireSigned) { this.signer = signer; this.keys = keys; this.requireSigned = requireSigned; }

    /** Replace a running or stopped app by a new version (e.g. an updated script). The new one starts if the old one was running or the host runs. */
    public void replace(MiniApp app) {
        Slot old = slots.get(app.id());
        boolean was = old != null && old.running;
        if (was) { old.running = false; try { old.app.onStop(); } catch (RuntimeException ignored) { } }   // no announce in between: neighbours never see the app vanish
        register(app);
        if (was || running) startApp(app.id());
    }

    public MiniAppHost(Net net, Clock clock, StorageAdapter storage, Limits limits) {
        this.net = net;
        this.clock = clock;
        this.storage = storage;
        this.limits = limits == null ? new Limits() : limits;
    }

    public void setLogger(Logger l) { this.logger = l; }

    /** Add an app (it does not run until start). */
    public void register(MiniApp app) {
        if (app.id() == null || app.id().length() == 0 || app.id().getBytes(StandardCharsets.UTF_8).length > 48) throw new IllegalArgumentException("bad app id");
        slots.put(app.id(), new Slot(app));
    }

    public List<String> apps() { return new ArrayList<>(slots.keySet()); }

    public boolean isRunning(String id) { Slot s = slots.get(id); return s != null && s.running; }

    public Set<Integer> peersOf(String id) { Slot s = slots.get(id); return s == null ? new HashSet<Integer>() : s.peersRunning(); }

    public void start() {
        if (!running) {
            running = true;
            clock.schedule(limits.announceMs, new Runnable() { @Override public void run() { announceTick(); } });
        }
        for (String id : new ArrayList<>(slots.keySet())) startApp(id);
    }

    public void stop() {
        for (String id : new ArrayList<>(slots.keySet())) stopApp(id);
        running = false;
    }

    public void startApp(final String id) {
        final Slot s = slots.get(id);
        if (s == null || s.running) return;
        s.running = true;
        s.errors = 0;
        if (storage != null) for (String k : storage.keys("app/" + id + "/")) {
            byte[] v = storage.get(k);
            if (v != null) { s.store.put(k.substring(("app/" + id + "/").length()), v); s.storedBytes += v.length; }
        }
        for (Map.Entry<Integer, Map<String, Integer>> e : neighborApps.entrySet())
            if (e.getValue().containsKey(id)) s.peers.add(e.getKey());
        guard(s, new Runnable() { @Override public void run() { s.app.onStart(s); } });
        announceAll();
        for (final int p : new ArrayList<>(s.peers)) guard(s, new Runnable() { @Override public void run() { s.app.onPeer(p, true); } });
    }

    public void stopApp(String id) {
        final Slot s = slots.get(id);
        if (s == null || !s.running) return;
        s.running = false;
        try { s.app.onStop(); } catch (RuntimeException ignored) { }
        s.peers.clear();
        announceAll();
    }

    // ---- from the mesh ---------------------------------------------------------------------

    public void onNeighbor(int node, boolean up) {
        if (!up) {
            neighborApps.remove(node);
            for (final Slot s : slots.values()) if (s.running && s.peers.remove(node)) { final int n = node; guard(s, new Runnable() { @Override public void run() { s.app.onPeer(n, false); } }); }
        } else if (running) {
            sendAnnounce(node);
        }
    }

    /** A frame that arrived by the link-level channel (one hop). */
    public void onNeighborFrame(int from, byte[] f) { handle(from, f, false); }

    /** A frame that arrived by the reliable channel. */
    public void onReliableFrame(int from, byte[] f) { handle(from, f, true); }

    private void handle(final int from, byte[] f, boolean reliable) {
        try {
            ByteBuffer b = ByteBuffer.wrap(f);
            int type = b.get() & 0xFF;
            if (type == T_ANNOUNCE) { if (!reliable) onAnnounce(from, b); return; }
            byte[] idb = new byte[b.get() & 0xFF];
            b.get(idb);
            final Slot s = slots.get(new String(idb, StandardCharsets.UTF_8));
            final byte[] body = new byte[b.remaining()];
            b.get(body);
            if (s == null || !s.running) { dropped++; return; }
            if (type == T_DIRECT && reliable) {
                guard(s, new Runnable() { @Override public void run() { s.app.onMessage(from, body); } });
            } else if (type == T_NEARBY && !reliable) {
                guard(s, new Runnable() { @Override public void run() { s.app.onNearby(from, body); } });
            } else if (type == T_FLOOD && !reliable && body.length >= 9) {
                if (requireSigned) { unverified++; return; }
                ByteBuffer fb = ByteBuffer.wrap(body);
                int hops = fb.get() & 0xFF;
                long id = fb.getLong();
                final byte[] data = new byte[fb.remaining()];
                fb.get(data);
                if (!s.seen.firstSeen(id, clock.nowMs())) return;
                guard(s, new Runnable() { @Override public void run() { s.app.onNearby(from, data); } });
                if (hops > 1 && s.take()) {
                    byte[] out = frame(T_FLOOD, s.app.id(), floodBody(hops - 1, id, data));
                    for (int p : s.peers) if (p != from) net.sendNeighbor(p, out);
                }
            } else if (type == T_FLOOD_SIGNED && !reliable && body.length >= 14) {
                ByteBuffer fb = ByteBuffer.wrap(body);
                int hops = fb.get() & 0xFF;
                long id = fb.getLong();
                final int origin = fb.getInt();
                byte[] sig = new byte[fb.get() & 0xFF];
                fb.get(sig);
                final byte[] data = new byte[fb.remaining()];
                fb.get(data);
                if (s.seen.contains(id, clock.nowMs())) return;             // a duplicate costs nothing; the id is recorded only after the signature checks out
                byte[] key = keys == null || signer == null ? null : keys.keyOf(origin);
                final int whoSent;
                if (key == null) {
                    if (requireSigned) { unverified++; return; }
                    s.seen.mark(id, clock.nowMs());
                    whoSent = from;                                  // cannot check the claimed origin: show the relay, never the claim
                } else if (!signer.verify(key, floodSigned(s.app.id(), id, origin, data), sig)) {
                    unverified++;
                    return;
                } else whoSent = origin;
                s.seen.mark(id, clock.nowMs());
                guard(s, new Runnable() { @Override public void run() { s.app.onNearby(whoSent, data); } });
                if (hops > 1 && s.take()) {
                    byte[] out = frame(T_FLOOD_SIGNED, s.app.id(), signedBody(hops - 1, id, origin, sig, data));
                    for (int p : s.peers) if (p != from) net.sendNeighbor(p, out);
                }
            } else {
                dropped++;
            }
        } catch (RuntimeException malformed) {
            dropped++;
        }
    }

    private void onAnnounce(int from, ByteBuffer b) {
        Map<String, Integer> apps = new HashMap<>();
        int n = b.get() & 0xFF;
        for (int i = 0; i < n; i++) {
            byte[] id = new byte[b.get() & 0xFF];
            b.get(id);
            apps.put(new String(id, StandardCharsets.UTF_8), b.getInt());
        }
        boolean first = !neighborApps.containsKey(from);
        neighborApps.put(from, apps);
        for (final Slot s : slots.values()) {
            if (!s.running) continue;
            boolean has = apps.containsKey(s.app.id());
            final int node = from;
            if (has && s.peers.add(from)) guard(s, new Runnable() { @Override public void run() { s.app.onPeer(node, true); } });
            else if (!has && s.peers.remove(from)) guard(s, new Runnable() { @Override public void run() { s.app.onPeer(node, false); } });
        }
        if (first) sendAnnounce(from);                      // answer a newcomer once, so both sides learn at the same time
    }

    // ---- announcing ----------------------------------------------------------------------

    private void announceTick() {
        if (!running) return;
        announceAll();
        clock.schedule(limits.announceMs, new Runnable() { @Override public void run() { announceTick(); } });
    }

    private void announceAll() {
        for (int p : net.neighbors()) sendAnnounce(p);
    }

    private void sendAnnounce(int node) {
        List<Slot> live = new ArrayList<>();
        for (Slot s : slots.values()) if (s.running) live.add(s);
        int size = 2;
        for (Slot s : live) size += 1 + s.app.id().getBytes(StandardCharsets.UTF_8).length + 4;
        ByteBuffer b = ByteBuffer.allocate(size).put((byte) T_ANNOUNCE).put((byte) live.size());
        for (Slot s : live) { byte[] id = s.app.id().getBytes(StandardCharsets.UTF_8); b.put((byte) id.length).put(id).putInt(s.app.version()); }
        net.sendNeighbor(node, b.array());
    }

    // ---- helpers -------------------------------------------------------------------------

    private static byte[] frame(int type, String appId, byte[] body) {
        byte[] id = appId.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(2 + id.length + body.length).put((byte) type).put((byte) id.length).put(id).put(body).array();
    }

    private static byte[] signedBody(int hops, long id, int origin, byte[] sig, byte[] data) {
        return ByteBuffer.allocate(14 + sig.length + data.length).put((byte) hops).putLong(id).putInt(origin).put((byte) sig.length).put(sig).put(data).array();
    }

    private static byte[] floodSigned(String appId, long id, int origin, byte[] data) {
        byte[] t = "mn-flood-v1".getBytes(StandardCharsets.US_ASCII), a = appId.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(t.length + 1 + a.length + 8 + 4 + data.length).put(t).put((byte) a.length).put(a).putLong(id).putInt(origin).put(data).array();
    }

    private static byte[] floodBody(int hops, long id, byte[] data) {
        return ByteBuffer.allocate(9 + data.length).put((byte) hops).putLong(id).put(data).array();
    }

    /** Run app code; an app that throws too often is stopped. */
    private void guard(final Slot s, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            crashes++;
            if (logger != null) logger.log(s.app.id(), "error: " + e);
            if (++s.errors >= limits.maxErrors) {
                if (logger != null) logger.log(s.app.id(), "stopped after repeated errors");
                stopApp(s.app.id());
            }
        }
    }
}
