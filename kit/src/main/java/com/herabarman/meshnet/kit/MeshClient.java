package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.ChunkLayer;
import com.herabarman.meshnet.core.Priority;
import com.herabarman.meshnet.core.VoiceLayer;
import com.herabarman.meshnet.core.DeliveryMode;
import com.herabarman.meshnet.core.MeshConfig;
import com.herabarman.meshnet.core.MeshNode;
import com.herabarman.meshnet.core.NodeInfo;
import com.herabarman.meshnet.core.Packet;
import com.herabarman.meshnet.core.ServerBridge;
import com.herabarman.meshnet.core.StorageAdapter;
import com.herabarman.meshnet.core.SystemClock;
import com.herabarman.meshnet.core.ZoneRouter;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The whole mesh behind one object. The app talks in user ids and strings; the core sees only ints and bytes.
 *
 * <pre>
 * MeshClient mesh = MeshClient.builder("70121").radio(radio).storage(storage).listener(l).build();
 * mesh.start();
 * mesh.updateLocation(lat, lon);              // zone is derived from it, nothing to configure
 * long id = mesh.sendText("70122", "hello");  // keys, routing, retries, offline queue: all inside
 * </pre>
 *
 * What it adds around the unchanged core (MeshNode): geohash zones, user id mapping, end-to-end encryption
 * with automatic key exchange, a persistent outbox (retries, offline delivery for 24 h), read receipts,
 * link-level zone handshake and an optional internet helper. All listener callbacks run on the engine
 * thread; post to your UI thread before touching views.
 */
public final class MeshClient {

    public interface Listener {
        /** A text message arrived. (from, localId) is unique: use it to ignore repeats in your database. */
        void onText(String fromUser, long localId, long timestamp, String text);
        void onBytes(String fromUser, long localId, byte[] data);
        /** The destination device confirmed it has the message (one tick). */
        void onDelivered(long localId);
        /** Gave up (24 h expired). */
        void onFailed(long localId, String reason);
        /** The destination read it (blue ticks). */
        void onSeen(long localId);
        /** A large payload sent with sendBlob() arrived completely. */
        default void onBlob(String fromUser, long blobId, byte[] data) {}
        /** Sender side of sendBlob(): chunks confirmed so far. delivered == total means the receiver has all of it. */
        default void onBlobProgress(long blobId, int delivered, int total) {}
        default void onPeers(int count) {}
        default void onZone(int zone, String geohash) {}
        /** A known user presented a different key. Messages to them are held until acceptNewKey(). */
        default void onKeyChanged(String user, String pinnedFingerprint, String offeredFingerprint) {}
        /** Large message (sendLarge), sender side: how many chunks the network has confirmed so far. */
        default void onLargeProgress(long localId, int chunksDone, int chunksTotal) {}
        /**
         * Large message, sender side: a chunk could not be delivered. Nothing is lost: call retryLarge(localId)
         * when the network is back and only the chunks that were never confirmed are sent again.
         */
        default void onLargeFailed(long localId, String reason, int missingChunks) {}
    }

    /**
     * Live voice (walkie-talkie / call). All callbacks arrive on the engine thread: copy the audio out quickly and
     * play it from your own audio thread. See voiceOpen().
     */
    public interface VoiceListener {
        /** The other side pressed the talk button. */
        void onVoiceStart(String fromUser);

        /** One packet of audio, in playout order (a few codec frames). data == null: it was lost, conceal it. */
        void onVoiceFrame(String fromUser, byte[] data);

        /** The other side released the button (or went silent). */
        void onVoiceEnd(String fromUser);

        /** How my voice arrives at toUser (0..100 % lost over the last second). Lower the bitrate if it is high. */
        default void onVoiceQuality(String toUser, int lossPercent) {}
    }

    /** Handler for extension frames (EXT). Runs on the engine thread. */
    public interface ExtHandler { void onExt(int fromNode, String fromUser, byte[] payload); }

    /** Neighbour came or went (radio link with finished zone handshake). Runs on the engine thread. */
    public interface NeighborObserver { void onNeighbor(int node, boolean up); }

    /** Link-level broadcast handler (kind = first payload byte). Runs on the engine thread. */
    public interface BroadcastHandler { void onBroadcast(int fromNode, byte[] data); }

    /**
     * How the core sees zones.
     * FLAT: every connected phone is one routing zone (flood with duplicate suppression + learned routes).
     *       Geohash zones are still computed and published to the server and to the app.
     * GEO_RING: the core's zone routing is driven by the geohash zone (see docs for why this is experimental).
     */
    public enum Routing { FLAT, GEO_RING }

    /** Delivery priority: higher goes first in the outbox and in the mesh's queues. These are the core Priority levels; 200 (URGENT) is also used for control frames. */
    public static final int PRIORITY_BULK = Priority.BULK;
    public static final int PRIORITY_NORMAL = Priority.NORMAL;
    public static final int PRIORITY_HIGH = Priority.URGENT;
    public static final int PRIORITY_SOS = Priority.SOS;
    public static final int MAX_BLOB_BYTES = 2 * 1024 * 1024;
    static final int BULK_WINDOW = 4;       // at most this many file chunks in flight at once: chat is never starved

    public static final long EXPIRY_MS = 24L * 60 * 60 * 1000;
    static final long CONTROL_EXPIRY_MS = 10L * 60 * 1000;
    static final long TICK_MS = 10_000;
    static final long RETRY_BASE_MS = 20_000;
    static final long RETRY_MAX_MS = 15L * 60 * 1000;
    static final long INFLIGHT_MAX_MS = 120_000;

    // ------------------------------------------------------------------ builder

    /** Last-moment changes to the core's tunables (an interface instead of java.util.function for old Android). */
    public interface Tuner { void tune(MeshConfig cfg); }

    public static final class Builder {
        private final String userId;
        private RawRadio radio;
        private ServerLink link;
        private StorageAdapter storage;
        private IdentityKeys identity;
        private Listener listener;
        private int precision = GeoZones.DEFAULT_PRECISION;
        private DeliveryMode mode;
        private Tuner tweak;
        private long retryBaseMs = RETRY_BASE_MS;
        private long tickMs = TICK_MS;
        private long zoneHoldMs = 20_000;
        private Routing routing = Routing.FLAT;
        private double rateBurst = 120, ratePerSec = 60;
        private boolean dtn;
        private boolean mask;
        private BulkLink bulk;
        private int bulkMinBytes = 16 * 1024;
        private long bulkAckMs = 20_000;

        private Builder(String userId) { this.userId = userId; }

        public Builder radio(RawRadio r) { this.radio = r; return this; }
        public Builder server(ServerLink l) { this.link = l; return this; }
        public Builder storage(StorageAdapter s) { this.storage = s; return this; }
        public Builder identity(IdentityKeys k) { this.identity = k; return this; }
        public Builder listener(Listener l) { this.listener = l; return this; }
        /** Geohash precision 1..6 (6 = about 1.2 x 0.6 km cells). */
        public Builder geoPrecision(int p) { this.precision = p; return this; }
        public Builder mode(DeliveryMode m) { this.mode = m; return this; }
        /** Last-moment changes to the core's tunables. */
        public Builder tune(Tuner t) { this.tweak = t; return this; }
        /** Outbox: first retry delay (doubles up to 15 min) and how often the outbox is looked at. */
        public Builder retry(long baseMs, long tickMs) { this.retryBaseMs = baseMs; this.tickMs = tickMs; return this; }
        /** How long the phone must stay in a new cell before its zone changes (default 20 s). */
        /** Per-neighbour limit on incoming mesh packets: burst, then packets per second. */
        public Builder rateLimit(double burst, double perSecond) { this.rateBurst = burst; this.ratePerSec = perSecond; return this; }
        /** Carry messages for others and hand copies to passing phones (see Dtn). */
        public Builder dtn(boolean on) { this.dtn = on; return this; }
        /** Pad every encrypted message to a size bucket so lengths do not leak what is being said. */
        public Builder maskTraffic(boolean on) { this.mask = on; return this; }
        /**
         * A fast side-channel for big payloads to a direct neighbour (Nearby FILE payload, TcpBulk over Wi-Fi Direct / hotspot / LAN).
         * Payloads of at least minBytes use it when the destination is a direct neighbour with a bulk path; they are still end-to-end
         * encrypted, and on any failure the normal chunked transfer takes over.
         */
        public Builder bulk(BulkLink link, int minBytes) { this.bulk = link; this.bulkMinBytes = Math.max(1024, minBytes); return this; }
        /** How long to wait for the receiver's confirmation of a bulk transfer before sending it again through the mesh chunks (default 20 s). */
        public Builder bulkAckTimeout(long ms) { this.bulkAckMs = Math.max(500, ms); return this; }
        public Builder bulk(BulkLink link) { return bulk(link, 16 * 1024); }
        public Builder routing(Routing r) { this.routing = r; return this; }
        public Builder zoneHold(long ms) { this.zoneHoldMs = ms; return this; }

        public MeshClient build() {
            if (radio == null) throw new IllegalStateException("radio is required");
            if (listener == null) throw new IllegalStateException("listener is required");
            return new MeshClient(this);
        }
    }

    public static Builder builder(String userId) { return new Builder(userId); }

    // ------------------------------------------------------------------ state

    private final String selfUser;
    private final int selfId;
    private final int precision;
    private final IdMap ids;
    private final StorageAdapter storage;
    private final Listener listener;
    private final RawRadio radio;
    private final ServerLink link;
    private final SystemClock clock = new SystemClock();
    private final ExecutorService io;
    private final MeshNode node;
    private final NodeInfo info;
    private final FramedTransport transport;
    private final IdentityKeys identity;
    private final KeyDirectory keys;
    private final E2eeCrypto crypto;
    private final Outbox outbox;
    private final ZoneTracker tracker;
    private final DynZones zoneMap;
    private ChunkLayer chunks;
    private final BulkLink bulk;
    private final int bulkMin;
    private final long bulkAckMs;
    private final AtomicLong bulkSentCount = new AtomicLong();
    private final AtomicLong bulkFallbackCount = new AtomicLong();
    private VoiceLayer voice;
    private volatile VoiceListener voiceListener;
    private final SecureRandom rng = new SecureRandom();
    private final boolean flat;
    private final long retryBaseMs;
    private final long tickMs;

    // engine thread only:
    private final Map<Long, Long> inflight = new LinkedHashMap<Long, Long>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Long> e) { return size() > 4096; }
    };
    private final Map<String, Boolean> seenRx = new LinkedHashMap<String, Boolean>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > 4096; }
    };
    private final Map<Integer, Long> keyAskedAt = new java.util.HashMap<>();
    private final Map<Integer, Long> refreshedAt = new java.util.HashMap<>();
    private final Map<Integer, byte[]> offeredKeys = new java.util.HashMap<>();
    /** Large transfers waiting for the receiver's encryption key (first contact). */
    private final Map<Long, PendingLarge> pendingLarge = new LinkedHashMap<>();
    /** Large transfers fully sent: complete for the app only when the receiver's receipt arrives. */
    private final java.util.Set<Long> largeSent = new java.util.HashSet<>();

    /** Large messages are bulk: under congestion text and receipts go first. */
    private static final long LARGE_KEY_WAIT_MS = 120_000;

    private static final class PendingLarge {
        final int dst;
        final byte[] data;
        final long createdMs;
        final int priority;
        PendingLarge(int dst, byte[] data, long createdMs, int priority) { this.dst = dst; this.data = data; this.createdMs = createdMs; this.priority = priority; }
    }

    private final Map<Long, long[]> blobProgress = new java.util.HashMap<>();          // blobId -> {delivered, total}
    private final Map<Long, Long> blobOfChunk = new java.util.HashMap<>();             // chunk localId -> blobId
    private final Map<String, Assembly> assemblies = new LinkedHashMap<>();            // "src:blobId" -> partial blob
    private final Map<Integer, ExtHandler> extHandlers = new java.util.HashMap<>();
    private final Map<Integer, BroadcastHandler> bcastHandlers = new java.util.HashMap<>();
    private final java.util.List<NeighborObserver> observers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<Long, Runnable> extDone = new java.util.HashMap<>();
    private final Reputation reputation = new Reputation();
    private Dtn dtn;
    private volatile double lastLat = Double.NaN, lastLon = Double.NaN;
    private final SpeedTracker speedTracker = new SpeedTracker();
    private final Map<Integer, Long> resetAt = new java.util.HashMap<>();
    private final Map<Integer, Long> resetSentAt = new java.util.HashMap<>();

    private static final class Assembly {
        final byte[][] parts;
        int got;
        int bytes;
        final long started;
        Assembly(int total, long now) { parts = new byte[total][]; started = now; }
    }

    private volatile int zone;
    private volatile boolean started;
    private int tickCount;
    private final AtomicLong sentCount = new AtomicLong();
    private final AtomicLong receivedCount = new AtomicLong();
    private final AtomicLong deliveredCount = new AtomicLong();

    // ------------------------------------------------------------------ construction

    private MeshClient(Builder b) {
        this.selfUser = b.userId;
        this.storage = b.storage;
        this.listener = b.listener;
        this.radio = b.radio;
        this.link = b.link;
        this.precision = b.precision;
        this.ids = new IdMap(storage);
        this.selfId = ids.nodeOf(selfUser);
        this.zone = GeoZones.unlocatedZone(selfId, precision);
        this.flat = b.routing == Routing.FLAT;
        this.retryBaseMs = b.retryBaseMs;
        this.tickMs = b.tickMs;
        this.tracker = new ZoneTracker(precision, b.zoneHoldMs);
        this.zoneMap = new DynZones(selfId, storage);
        this.outbox = new Outbox(storage);
        this.identity = b.identity != null ? b.identity : loadOrCreateIdentity(storage);
        this.keys = new KeyDirectory(storage);
        this.crypto = new E2eeCrypto(selfId, identity, keys, storage);
        this.io = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "mesh-io");
                t.setDaemon(true);
                return t;
            }
        });

        MeshConfig cfg = new MeshConfig();
        cfg.discovery = false;                       // zones come from geohash + link handshake, see docs
        cfg.zoneCount = flat ? 1 : GeoZones.zoneCount(precision);
        cfg.fullMesh = false;
        cfg.deliveryMode = b.mode != null ? b.mode : (link != null ? DeliveryMode.ONLINE_FIRST : DeliveryMode.OFFLINE_ONLY);
        cfg.storeTtlMs = 24L * 60 * 60 * 1000;       // relay phones keep other people's packets for a day
        cfg.storeMaxPackets = 512;
        cfg.retryMs = 10_000;
        cfg.ackTimeoutMs = 4_000;
        cfg.maxRetries = 2;
        cfg.txRatePerSec = 40;                       // pace the radio: receipts and text first, large transfers last
        cfg.txBurst = 20;
        cfg.chunkBytes = 4096;                       // large messages: 4 KB per chunk (a Nearby payload takes 32 KB) ...
        cfg.chunkWindow = 4;                         // ... and 4 chunks in flight
        if (b.tweak != null) b.tweak.tune(cfg);

        int coreZone = flat ? 0 : zone;
        this.info = new NodeInfo(selfId, coreZone, 1.0, 1.0, link != null);
        this.transport = new FramedTransport(radio, clock, coreZone);
        this.zoneMap.flat = flat;
        this.transport.setRateLimit(b.rateBurst, b.ratePerSec);
        this.zoneMap.myZone = coreZone;
        ServerBridge bridge = link != null ? new LinkBridge(link) : null;
        this.node = new MeshNode(info, cfg, transport, clock, zoneMap, crypto, bridge, rng, new RelayOnlyStorage(storage));

        zoneMap.transport = transport;
        transport.setDirectory(zoneMap);
        keys.setListener(new KeyDirectory.ChangeListener() {
            @Override public void onKeyChanged(int n, byte[] pinned, byte[] offered) {
                offeredKeys.put(n, offered);
                listener.onKeyChanged(ids.userOrPlaceholder(n), IdentityKeys.fingerprint(pinned), IdentityKeys.fingerprint(offered));
            }
        });
        crypto.setSessionLostListener(new E2eeCrypto.SessionLostListener() {
            @Override public void onSessionLost(final int n) {
                defer(new Runnable() {
                    @Override public void run() {
                        long now = clock.nowMs();
                        Long last = resetSentAt.get(n);
                        if (last != null && now - last < 30_000) return;
                        resetSentAt.put(n, now);
                        sendControl(n, new byte[]{(byte) Frame.RESET});
                    }
                });
            }
        });
        crypto.setMissingKeyListener(new E2eeCrypto.MissingKeyListener() {
            @Override public void onNeedKey(final int n) {
                defer(new Runnable() { @Override public void run() { requestKey(n); } });
            }
        });
        transport.setPeerListener(new FramedTransport.PeerListener() {
            @Override public void onPeerZone(final int peer, final int z) {
                zoneMap.learn(peer, z);
                notifyNeighbor(peer, true);
                if (!flat) scheduleGossip();
                defer(new Runnable() { @Override public void run() { retryAll(5_000); } });
            }
            @Override public void onPeerCount(int count) { listener.onPeers(count); }
            @Override public void onPeerDown(final int peer) { notifyNeighbor(peer, false); }
        });
        transport.setBroadcastHandler(new FramedTransport.BroadcastHandler() {
            @Override public void onBroadcast(final int from, final byte[] payload) {
                try {
                    clock.post(new Runnable() {
                        @Override public void run() {
                            BroadcastHandler h = bcastHandlers.get(payload[0] & 0xFF);
                            if (h != null) h.onBroadcast(from, java.util.Arrays.copyOfRange(payload, 1, payload.length));
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException stopped) { }
            }
        });
        final MeshNode.Listener base = new MeshNode.Listener() {
            @Override public void onDelivered(MeshNode n, Packet p, byte[] plain, int from) { reputation.onDelivered(p.src, from); handlePlain(p.src, p.srcZone, plain); }
            @Override public void onDuplicate(MeshNode n, Packet p) { }
            @Override public void onDropped(MeshNode n, Packet p, String reason) { handleDropped(p, reason); }
            @Override public void onAcked(MeshNode n, long msgId) { handleAcked(msgId); }
        };
        node.setListener(base);
        // Large messages: the chunk layer sits in front of the listener above and handles only chunk packets.
        this.chunks = new ChunkLayer(node, base, cfg, clock, rng, new ChunkLayer.Callbacks() {
            @Override public void onComplete(int fromNode, long transferId, byte[] data) { largeArrived(fromNode, transferId, data); }
            @Override public void onProgress(long transferId, int done, int total) { listener.onLargeProgress(transferId, done, total); }
            @Override public void onFailed(long transferId, String reason, int missing) { listener.onLargeFailed(transferId, reason, missing); }
        });
        // Live voice sits in front of the chunk layer and handles only voice packets.
        this.voice = new VoiceLayer(node, chunks, cfg, clock, rng, new VoiceLayer.Callbacks() {
            @Override public void onTalkStart(int fromNode, int callId) {
                VoiceListener l = voiceListener;
                if (l != null) l.onVoiceStart(ids.userOrPlaceholder(fromNode));
            }
            @Override public void onFrame(int fromNode, int callId, byte[] data) {
                VoiceListener l = voiceListener;
                if (l != null) l.onVoiceFrame(ids.userOrPlaceholder(fromNode), data);
            }
            @Override public void onTalkEnd(int fromNode, int callId) {
                VoiceListener l = voiceListener;
                if (l != null) l.onVoiceEnd(ids.userOrPlaceholder(fromNode));
            }
            @Override public void onReport(int toNode, int callId, int lossPercent) {
                VoiceListener l = voiceListener;
                if (l != null) l.onVoiceQuality(ids.userOrPlaceholder(toNode), lossPercent);
            }
        });
        this.bulk = b.bulk;
        this.bulkMin = b.bulkMinBytes;
        this.bulkAckMs = b.bulkAckMs;
        if (bulk != null) {
            bulk.setReceiver(new BulkLink.Receiver() {
                @Override public void onReceived(final int fromNode, final byte[] wire) {
                    post(new Runnable() { @Override public void run() { bulkArrived(fromNode, wire); } });
                }
            });
        }
        if (b.dtn) this.dtn = new Dtn(this, rng);
        if (b.mask) crypto.setPadding(true);
    }

    private static IdentityKeys loadOrCreateIdentity(StorageAdapter s) {
        if (s != null) {
            byte[] priv = s.get("identity-priv");
            byte[] pub = s.get("identity-pub");
            if (priv != null && pub != null) {
                try { return IdentityKeys.restore(priv, pub); } catch (IllegalArgumentException ignored) { }
            }
        }
        IdentityKeys k = IdentityKeys.generate();
        if (s != null) {
            s.put("identity-priv", k.privateEncoded());
            s.put("identity-pub", k.publicRaw());
        }
        return k;
    }

    /** Core storage without "out-*": my own unconfirmed sends are the Outbox's job; relay queues ("sf-*") still persist. */
    private static final class RelayOnlyStorage implements StorageAdapter {
        private final StorageAdapter inner;
        RelayOnlyStorage(StorageAdapter inner) { this.inner = inner; }
        @Override public void put(String key, byte[] value) { if (inner != null && !key.startsWith("out-")) inner.put(key, value); }
        @Override public byte[] get(String key) { return inner == null || key.startsWith("out-") ? null : inner.get(key); }
        @Override public void remove(String key) { if (inner != null) inner.remove(key); }
        @Override public List<String> keys(String prefix) {
            if (inner == null || prefix.startsWith("out-")) return Collections.<String>emptyList();
            return inner.keys(prefix);
        }
    }

    /** nodeId -> zone, learned from neighbours, packet headers, the server and a small disk cache. Unknown = "near me". */
    private static final class DynZones implements ZoneRouter.ZoneMap, FramedTransport.Directory {
        private final int self;
        volatile FramedTransport transport;
        private final StorageAdapter store;
        private final Map<Integer, Integer> map = Collections.synchronizedMap(new LinkedHashMap<Integer, Integer>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Integer, Integer> e) { return size() > 8192; }
        });
        volatile int myZone;
        volatile boolean flat;

        DynZones(int self, StorageAdapter store) { this.self = self; this.store = store; }

        void learn(int node, int zone) {
            if (zone < 0 || node == self) return;
            Integer old = map.put(node, zone);
            if (store != null && (old == null || old != zone)) store.put("zone-" + node, ByteBuffer.allocate(4).putInt(zone).array());
        }

        @Override public int[] snapshot(int max, java.util.Set<Integer> direct) {
            java.util.LinkedHashSet<Integer> order = new java.util.LinkedHashSet<>(direct);
            synchronized (map) {
                java.util.ArrayList<Integer> recent = new java.util.ArrayList<>(map.keySet());
                for (int i = recent.size() - 1; i >= 0 && order.size() < max; i--) order.add(recent.get(i));
            }
            int[] out = new int[2 * Math.min(max, order.size() + 1)];
            int k = 0;
            out[k++] = self;
            out[k++] = myZone;
            for (int node : order) {
                if (k + 2 > out.length) break;
                Integer z = map.get(node);
                if (z == null) continue;
                out[k++] = node;
                out[k++] = z;
            }
            return java.util.Arrays.copyOf(out, k);
        }

        @Override public void learnGossip(int fromPeer, int[] pairs) {
            FramedTransport t = transport;
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                int node = pairs[i], z = pairs[i + 1];
                if (node == self || node <= 0 || z < 0) continue;
                if (t != null && t.zoneOfPeer(node) >= 0) continue;   // first-hand knowledge wins
                learn(node, z);
            }
        }

        @Override public int zoneOf(int node) {
            if (node == self || flat) return myZone;
            Integer z = map.get(node);
            if (z == null && store != null) {
                byte[] b = store.get("zone-" + node);
                if (b != null && b.length == 4) { z = ByteBuffer.wrap(b).getInt(); map.put(node, z); }
            }
            return z != null ? z : myZone;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        if (started) return;
        started = true;
        clock.post(new Runnable() {
            @Override public void run() {
                outbox.load();
                node.restore();
                node.start();
                if (dtn != null) dtn.start();
                tick();
                retryAll(0);
            }
        });
        radio.start();
        if (bulk != null) bulk.start();
        if (link != null) {
            link.start(selfId, new ServerLink.Inbound() {
                @Override public void onPacket(final byte[] packet) {
                    clock.post(new Runnable() { @Override public void run() { node.onReceive(MeshNode.SERVER_PEER, packet); } });
                }
            });
            link.publishZone(zone);
        }
    }

    public void stop() {
        started = false;
        if (dtn != null) { try { clock.call(new java.util.concurrent.Callable<Void>() { @Override public Void call() { dtn.stop(); return null; } }); } catch (RuntimeException ignored) { } }
        try { radio.stop(); } catch (RuntimeException ignored) { }
        if (bulk != null) { try { bulk.stop(); } catch (RuntimeException ignored) { } }
        if (link != null) link.stop();
        io.shutdownNow();
        clock.shutdown();
    }

    // ------------------------------------------------------------------ public API

    public String userId() { return selfUser; }
    public int zone() { return zone; }
    public int peerCount() { return transport.peerCount(); }
    public long sentCount() { return sentCount.get(); }

    /**
     * A plain-text health report for field tests and bug reports: neighbours with measured quality, outbox size, traffic counters,
     * bulk-link use. Contains node numbers and counts only, never message content, keys or user names.
     */
    public String diagnostics() {
        return clock.call(new java.util.concurrent.Callable<String>() {
            @Override public String call() {
                StringBuilder sb = new StringBuilder();
                sb.append("node ").append(selfId).append(" | zone ").append(node.zone()).append(" | neighbours ").append(transport.peerCount()).append('\n');
                for (FramedTransport.LinkQuality q : transport.qualities()) sb.append("  ").append(q).append('\n');
                sb.append("outbox ").append(outbox.size()).append(" waiting | sent ").append(sentCount.get())
                        .append(" received ").append(receivedCount.get()).append(" delivered ").append(deliveredCount.get()).append('\n');
                sb.append("bulk: ").append(bulk == null ? "off" : "on").append(", sent ").append(bulkSentCount.get())
                        .append(", fell back ").append(bulkFallbackCount.get()).append('\n');
                return sb.toString();
            }
        });
    }
    /** Mesh packets refused because a neighbour sent too fast. */
    public long rateLimitedCount() { return transport.rateLimitedCount(); }
    /** Measured round trip and loss for each connected neighbour. */
    public java.util.List<FramedTransport.LinkQuality> linkQuality() { return transport.qualities(); }
    public long receivedCount() { return receivedCount.get(); }
    public long deliveredCount() { return deliveredCount.get(); }
    public int pendingCount() { return clock.call(new java.util.concurrent.Callable<Integer>() {
        @Override public Integer call() { return outbox.size(); } }); }

    /** Call with every location fix (GPS or network/cell). Zone changes are applied with a 20 s hold against border flapping. */
    public void updateLocation(double lat, double lon) {
        lastLat = lat;
        lastLon = lon;
        final double mps = speedTracker.update(lat, lon, System.currentTimeMillis());
        clock.post(new Runnable() { @Override public void run() { node.setMobility(mps); } });
        int z = tracker.update(lat, lon, System.currentTimeMillis());
        if (z >= 0) applyZone(z, GeoZones.geohash(lat, lon, precision));
    }

    /** Speed of the phone in the last fixes (m/s), 0 until two fixes are in. Also advertised to neighbours. */
    public double speedMps() { return speedTracker.speed(); }

    /** Battery 0..1 and whether the charger is plugged in; neighbours with energy-aware routing steer around a weak, unplugged phone. */
    public void setPowerState(final double level, final boolean charging) {
        clock.post(new Runnable() { @Override public void run() {
            info.charging = charging;
            node.setBattery(Math.max(0, Math.min(1, level)));
        } });
    }

    /**
     * Battery 0..1. Below 20% the phone stops relaying other people's floods and is not made a gateway (it still
     * sends and receives its own messages); it relays again at 25%. Feed it from the system battery broadcast.
     */
    public void setBattery(final double level) {
        clock.post(new Runnable() { @Override public void run() { node.setBattery(Math.max(0, Math.min(1, level))); } });
    }

    /**
     * Send a text at the normal priority. The levels (core Priority): SOS, URGENT, NORMAL (default for text and
     * bytes), BULK (default for sendLarge) and BACKGROUND. The radio queue sends a higher level before a lower one,
     * so a chat message is never stuck behind a photo.
     */
    public long sendText(String toUser, String text) {
        return sendText(toUser, text, Priority.NORMAL);
    }

    /** @param priority PRIORITY_SOS for emergencies, PRIORITY_HIGH, PRIORITY_NORMAL (chat) or PRIORITY_BULK */
    public long sendText(String toUser, String text, int priority) {
        long localId = newLocalId();
        enqueue(localId, toUser, Frame.text(localId, System.currentTimeMillis(), text), false, checkPriority(priority));
        return localId;
    }

    public long sendBytes(String toUser, byte[] data) {
        return sendBytes(toUser, data, Priority.NORMAL);
    }

    public long sendBytes(String toUser, byte[] data, int priority) {
        long localId = newLocalId();
        enqueue(localId, toUser, Frame.app(localId, data), false, checkPriority(priority));
        return localId;
    }

    /**
     * An emergency text. It goes out at once even when the radio budget is spent, is flooded everywhere without
     * being thinned, travels further (longer TTL), is retried faster and more often, is kept longer if there is no
     * route yet, and also goes through the server when there is one. Use it for alarms, not for anything else:
     * it spends the radio budget of everyone it passes. Who gets it is up to the app (call it once per recipient).
     */
    public long sendSos(String toUser, String text) {
        return sendText(toUser, text, Priority.SOS);
    }

    private static int checkPriority(int priority) {
        if (priority < 0 || priority >= Priority.CONTROL) throw new IllegalArgumentException("priority 0.." + (Priority.CONTROL - 1));
        return priority;
    }

    /**
     * Send a photo, voice note or file (up to 16 MB), end-to-end encrypted like every message. It is cut into
     * 4 KB chunks that travel a few at a time, each confirmed on its own. The receiver gets it through onBytes
     * (same localId) once every chunk is in; you get onDelivered(localId) when the receiver confirms it, and
     * onLargeProgress along the way. If the network drops out, onLargeFailed arrives and retryLarge(localId)
     * sends only the missing chunks. A large transfer is not kept across an app restart.
     *
     * @return the localId
     */
    public long sendLarge(final String toUser, final byte[] data) {
        return sendLarge(toUser, data, Priority.BULK);
    }

    /** Like sendLarge, with a priority: BULK (default) for photos and files, BACKGROUND for sync that can take hours. */
    public long sendLarge(final String toUser, final byte[] data, final int priority) {
        checkPriority(priority);
        if (data.length == 0) throw new IllegalArgumentException("empty");
        final long localId = newLocalId();
        final int dst = ids.nodeOf(toUser);
        sentCount.incrementAndGet();
        clock.post(new Runnable() {
            @Override public void run() {
                if (crypto.hasKey(dst)) {
                    startLarge(localId, dst, data, priority);
                } else {                                        // first contact: get the key, then start
                    pendingLarge.put(localId, new PendingLarge(dst, data, clock.nowMs(), priority));
                    requestKey(dst);
                }
            }
        });
        return localId;
    }

    /**
     * Tell the engine that this app user id exists, so that messages from it are reported with this exact id.
     * The app decides what a user id is (any string); the engine only needs it to turn the node address in an
     * incoming packet back into your string. Ids that are plain numbers (no leading zero, under 10^9) need nothing,
     * they are the address itself; for any other string call this once per contact (sending to someone does it too).
     * Without it a message from an unknown string id is reported as "node-" plus its address in hex.
     */
    public void knowUser(String userId) {
        ids.nodeOf(userId);
    }

    public void setVoiceListener(VoiceListener l) { this.voiceListener = l; }

    /**
     * Start a live voice stream to a user (push-to-talk, or two streams for a call). Voice is end-to-end encrypted
     * like every message but is never acknowledged, retried or stored: a packet that is late is dropped.
     * Call it once per conversation. If you have no key for this user yet it asks for one, and packets sent before
     * the key arrives are dropped, so begin with a normal message (a "ringing" text) first.
     *
     * @param frameMs playing time of one packet (e.g. 60 = three 20 ms codec frames in each packet)
     * @return the stream id for voiceSend / voiceEnd
     */
    public int voiceOpen(final String toUser, final int frameMs) {
        final int dst = ids.nodeOf(toUser);
        return clock.call(new java.util.concurrent.Callable<Integer>() {
            @Override public Integer call() {
                if (!crypto.hasKey(dst)) requestKey(dst);
                return voice.open(dst, frameMs);
            }
        });
    }

    /** Send one packet of audio (the first one after a pause is marked as the start of a talk spurt). */
    public void voiceSend(final int streamId, final byte[] frame) {
        clock.post(new Runnable() {
            @Override public void run() {
                try { voice.send(streamId, frame); } catch (RuntimeException e) { /* no key yet: this packet is dropped */ }
            }
        });
    }

    /** The talk button was released. */
    public void voiceEnd(final int streamId) {
        clock.post(new Runnable() {
            @Override public void run() {
                try { voice.endTalk(streamId); } catch (RuntimeException e) { /* no key: nothing to end */ }
            }
        });
    }

    /**
     * Send a larger payload (up to 2 MB: a photo, a voice note, a file). It is cut into 12 KB chunks that travel as
     * ordinary messages (each is encrypted, retried and confirmed on its own, so a broken link only costs the chunks
     * in flight) and is put together again on the other side: Listener.onBlob(). Progress: onBlobProgress().
     * Bulk traffic goes after chat and SOS, with at most 4 chunks in flight. Not resumable across a restart of the
     * RECEIVER (its partial copy is in memory).
     * @return blobId
     */
    public long sendBlob(String toUser, byte[] data) {
        if (data.length == 0 || data.length > MAX_BLOB_BYTES) throw new IllegalArgumentException("size 1.." + MAX_BLOB_BYTES);
        final long blobId = newLocalId();
        final int total = (data.length + Frame.BLOB_CHUNK - 1) / Frame.BLOB_CHUNK;
        final long[] ids = new long[total];
        final byte[][] frames = new byte[total][];
        for (int i = 0; i < total; i++) {
            ids[i] = newLocalId();
            int off = i * Frame.BLOB_CHUNK;
            frames[i] = Frame.blob(ids[i], blobId, i, total, data, off, Math.min(Frame.BLOB_CHUNK, data.length - off));
        }
        final int dst = ids().nodeOf(toUser);
        sentCount.incrementAndGet();
        clock.post(new Runnable() {
            @Override public void run() {
                blobProgress.put(blobId, new long[]{0, total});
                for (int i = 0; i < total; i++) {
                    blobOfChunk.put(ids[i], blobId);
                    Outbox.Entry e = new Outbox.Entry(ids[i], dst, clock.nowMs(), false, frames[i], PRIORITY_BULK);
                    outbox.add(e);
                    pump(e);
                }
            }
        });
        return blobId;
    }

    private IdMap ids() { return ids; }

    /** Tell the sender that you have read these messages (their localIds). */
    public void markSeen(String fromUser, long... localIds) {
        if (localIds.length == 0) return;
        enqueue(newLocalId(), fromUser, Frame.seen(localIds), true, 200);
    }

    // ------------------------------------------------------------------ extension API

    /** Register a handler for EXT frames of this type (0..255). Do this before start(). */
    public void registerExt(int extType, ExtHandler h) { extHandlers.put(extType, h); }

    /** Register a handler for link-level broadcasts of this kind (first payload byte). */
    public void registerBroadcast(int kind, BroadcastHandler h) { bcastHandlers.put(kind, h); }

    public void addNeighborObserver(NeighborObserver o) { observers.add(o); }

    private void notifyNeighbor(final int node, final boolean up) {
        try {
            clock.post(new Runnable() {
                @Override public void run() { for (NeighborObserver o : observers) o.onNeighbor(node, up); }
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) { }
    }

    /** Reliable, end-to-end encrypted message of your own type to a user. onDelivered (may be null) runs when the receiver confirmed. */
    public long sendExt(String toUser, int extType, byte[] payload, int priority, Runnable onDelivered) {
        return sendExtNode(ids.nodeOf(toUser), extType, payload, priority, onDelivered);
    }

    public long sendExtNode(final int node, int extType, byte[] payload, int priority, final Runnable onDelivered) {
        final long localId = newLocalId();
        final byte[] frame = Frame.ext(localId, extType, payload);
        final int prio = priority;
        clock.post(new Runnable() {
            @Override public void run() {
                if (onDelivered != null) extDone.put(localId, onDelivered);
                Outbox.Entry e = new Outbox.Entry(localId, node, clock.nowMs(), false, frame, prio);
                outbox.add(e);
                pump(e);
            }
        });
        return localId;
    }

    /** Close the stream when the conversation is over. */
    public void voiceClose(final int streamId) {
        clock.post(new Runnable() {
            @Override public void run() { voice.close(streamId); }
        });
    }

    /** After onLargeFailed: send again only the chunks that were never confirmed. */
    public boolean retryLarge(final long localId) {
        return clock.call(new java.util.concurrent.Callable<Boolean>() {
            @Override public Boolean call() { return chunks.retryMissing(localId); }
        });
    }

    /** Engine thread. */
    private void startLarge(final long localId, final int dst, final byte[] data, final int priority) {
        largeSent.add(localId);
        if (bulk != null && data.length >= bulkMin && bulk.canSend(dst)) {
            byte[] enc;
            try { enc = crypto.encrypt(data, selfId, dst, localId); }
            catch (RuntimeException noKey) { chunks.send(dst, data, priority, localId); return; }
            byte[] wire = new byte[8 + enc.length];
            ByteBuffer.wrap(wire).putLong(localId);
            System.arraycopy(enc, 0, wire, 8, enc.length);
            bulkSentCount.incrementAndGet();
            bulk.send(dst, wire, new BulkLink.Done() {
                @Override public void onDone(final boolean ok) {
                    post(new Runnable() { @Override public void run() {
                        if (ok) {
                            listener.onLargeProgress(localId, 1, 1);
                            // Handed over, but not yet confirmed by the receiver (it may have dropped a damaged copy):
                            // if no receipt comes, the normal chunked way takes over. The receiver ignores a second copy.
                            clock.schedule(bulkAckMs, new Runnable() { @Override public void run() {
                                if (largeSent.contains(localId)) {
                                    bulkFallbackCount.incrementAndGet();
                                    chunks.send(dst, data, priority, localId);
                                }
                            } });
                        }
                        else {                                            // the fast path failed: the normal chunked way
                            bulkFallbackCount.incrementAndGet();
                            chunks.send(dst, data, priority, localId);
                        }
                    } });
                }
            });
            return;
        }
        chunks.send(dst, data, priority, localId);
    }

    /** Engine thread. A big payload came in on the BulkLink: localId(8) | end-to-end ciphertext. */
    private void bulkArrived(int fromNode, byte[] wire) {
        if (wire.length < 9) return;
        long tid = ByteBuffer.wrap(wire, 0, 8).getLong();
        byte[] cipher = java.util.Arrays.copyOfRange(wire, 8, wire.length);
        byte[] plain;
        try { plain = crypto.decrypt(cipher, fromNode, selfId, tid); }
        catch (RuntimeException e) { return; }
        if (plain == null) return;                                        // unknown sender key / tampered: dropped, nothing to leak
        largeArrived(fromNode, tid, plain);
    }

    /** How many large payloads went over the BulkLink, and how many of those had to fall back to chunks. */
    public long bulkSent() { return bulkSentCount.get(); }
    public long bulkFallbacks() { return bulkFallbackCount.get(); }

    /** Engine thread. A large message is complete: hand it to the app (once) and confirm it to the sender. */
    private void largeArrived(int fromNode, long transferId, byte[] data) {
        receivedCount.incrementAndGet();
        boolean fresh = seenRx.put(fromNode + ":" + transferId, Boolean.TRUE) == null;
        if (fresh) listener.onBytes(ids.userOrPlaceholder(fromNode), transferId, data);
        receipt(fromNode, transferId);
    }

    /** Link-level broadcast to all direct neighbours (not routed, not encrypted): kind(1 byte) + data. */
    public void broadcast(int kind, byte[] data) {
        byte[] p = new byte[1 + data.length];
        p[0] = (byte) kind;
        System.arraycopy(data, 0, p, 1, data.length);
        transport.broadcast(p);
    }

    public void sendToNeighbor(int node, int kind, byte[] data) {
        byte[] p = new byte[1 + data.length];
        p[0] = (byte) kind;
        System.arraycopy(data, 0, p, 1, data.length);
        transport.linkSend(node, p);
    }

    /** The DTN / data-mule engine (null unless built with dtn(true)). */
    public Dtn dtn() { return dtn; }
    public int selfNode() { return selfId; }
    public IdMap idMap() { return ids; }
    public IdentityKeys identity() { return identity; }
    public KeyDirectory keyDirectory() { return keys; }
    public StorageAdapter storage() { return storage; }
    public long now() { return clock.nowMs(); }
    public java.util.Set<Integer> neighbors() { return transport.neighbors(); }
    public FramedTransport.LinkQuality quality(int node) { return transport.quality(node); }
    /** Fairness score 0..1 of a neighbour from what this phone has seen (see Reputation). */
    public double fairness(int node) { return reputation.score(node); }
    /** My geohash at the zone precision, or null before the first location fix. */
    public String currentGeohash() { return Double.isNaN(lastLat) ? null : GeoZones.geohash(lastLat, lastLon, precision); }
    public void post(Runnable r) { try { clock.post(r); } catch (java.util.concurrent.RejectedExecutionException stopped) { } }
    public void schedule(long ms, Runnable r) { try { clock.schedule(ms, r); } catch (java.util.concurrent.RejectedExecutionException stopped) { } }
    boolean hasKeyFor(int node) { return crypto.hasKey(node); }
    byte[] encryptFor(int dst, byte[] plain) { return crypto.encrypt(plain, selfId, dst, 0); }
    byte[] decryptFrom(int src, byte[] cipher) { return crypto.decrypt(cipher, src, selfId, 0); }

    public String myFingerprint() { return IdentityKeys.fingerprint(identity.publicRaw()); }

    /** Fingerprint of the key pinned for this user, or null if no key exchange happened yet. */
    public String fingerprintOf(String user) {
        byte[] k = keys.get(ids.nodeOf(user));
        return k == null ? null : IdentityKeys.fingerprint(k);
    }

    /** After onKeyChanged: the user compared fingerprints and agrees to trust the new key. */
    public void acceptNewKey(String user) {
        final int n = ids.nodeOf(user);
        clock.post(new Runnable() {
            @Override public void run() {
                byte[] raw = offeredKeys.remove(n);
                if (raw == null) return;
                keys.replace(n, raw);
                crypto.forget(n);
                sendControl(n, Frame.key(Frame.KEY_RES, identity.publicRaw()));
                onKeyArrived(n);
            }
        });
    }

    // ------------------------------------------------------------------ zone

    private void applyZone(final int z, final String geohash) {
        clock.post(new Runnable() {
            @Override public void run() {
                if (z == zone) return;
                zone = z;
                if (!flat) {
                    zoneMap.myZone = z;
                    node.setZone(z);
                    transport.announceZone(z);
                }
                if (link != null) link.publishZone(z);
                listener.onZone(z, geohash);
            }
        });
    }

    // ------------------------------------------------------------------ sending

    private long newLocalId() { return rng.nextLong() & Long.MAX_VALUE; }

    private void enqueue(final long localId, String toUser, final byte[] frame, final boolean control) {
        enqueue(localId, toUser, frame, control, control ? Priority.URGENT : Priority.NORMAL);
    }

    private void enqueue(final long localId, String toUser, final byte[] frame, final boolean control, final int priority) {
        final int dst = ids.nodeOf(toUser);
        sentCount.incrementAndGet();
        clock.post(new Runnable() {
            @Override public void run() {
                Outbox.Entry e = new Outbox.Entry(localId, dst, clock.nowMs(), control, frame, priority);
                outbox.add(e);
                pump(e);
            }
        });
    }

    private void sendControl(int dst, byte[] frame) {
        Outbox.Entry e = new Outbox.Entry(newLocalId(), dst, clock.nowMs(), true, frame, 200);
        outbox.add(e);
        pump(e);
    }

    private void defer(Runnable r) { clock.schedule(1, r); }

    private volatile boolean gossipPending;

    /** Coalesce: many neighbour changes in a moment cause one gossip round. */
    private void scheduleGossip() {
        if (gossipPending) return;
        gossipPending = true;
        clock.schedule(Math.min(300, tickMs), new Runnable() {
            @Override public void run() {
                gossipPending = false;
                transport.gossipNow();
            }
        });
    }

    private long backoff(int attempts) {
        long d = retryBaseMs << Math.min(Math.max(attempts - 1, 0), 6);
        return Math.min(d, RETRY_MAX_MS);
    }

    /** Engine thread. Start one delivery attempt for this entry if nothing is in flight. */
    private void pump(final Outbox.Entry e) {
        if (e.inFlight || outbox.get(e.localId) == null) return;
        long now = clock.nowMs();
        if (now - e.createdMs > (e.control ? CONTROL_EXPIRY_MS : EXPIRY_MS)) {
            outbox.remove(e.localId);
            notifyFailed(e, "expired");
            return;
        }
        if (!Frame.isKeyFrame(e.frame) && !crypto.hasKey(e.dst)) {
            requestKey(e.dst);
            e.nextTryMs = now + 5_000;
            return;
        }
        if (e.priority == PRIORITY_BULK && bulkInFlight() >= BULK_WINDOW) {   // files wait their turn; chat is never starved
            e.nextTryMs = now + 300;
            return;
        }
        e.inFlight = true;
        e.lastSendMs = now;
        if (e.priority >= PRIORITY_SOS && e.priority < 200) {
            // Emergencies do not wait for a server round trip: go out over the mesh now, and if the server turns out
            // to know the recipient, send a second copy that way (the receiver ignores the repeat).
            transmit(e);
            if (link != null && link.available()) {
                prepare(e.dst, new Runnable() {
                    @Override public void run() {
                        if (outbox.get(e.localId) != null && link.isOnline(e.dst)) transmit(e);
                    }
                });
            }
            return;
        }
        prepare(e.dst, new Runnable() { @Override public void run() { transmit(e); } });
    }

    private int bulkInFlight() {
        int n = 0;
        for (Outbox.Entry x : outbox.all()) if (x.priority == PRIORITY_BULK && x.inFlight) n++;
        return n;
    }

    private void notifyDelivered(Outbox.Entry e) {
        if (dtn != null) dtn.onOriginDelivered(e.localId);
        if (e.control) return;
        if (Frame.type(e.frame) == Frame.EXT) {
            Runnable cb = extDone.remove(e.localId);
            if (cb != null) cb.run();
            return;
        }
        deliveredCount.incrementAndGet();
        Long blob = blobOfChunk.remove(e.localId);
        if (blob != null) {
            long[] p = blobProgress.get(blob);
            if (p != null) {
                p[0]++;
                listener.onBlobProgress(blob, (int) p[0], (int) p[1]);
                if (p[0] >= p[1]) blobProgress.remove(blob);
            }
            return;
        }
        listener.onDelivered(e.localId);
    }

    private void notifyFailed(Outbox.Entry e, String reason) {
        if (e.control) return;
        if (Frame.type(e.frame) == Frame.EXT) { extDone.remove(e.localId); return; }
        Long blob = blobOfChunk.remove(e.localId);
        if (blob != null) {
            if (blobProgress.remove(blob) != null) listener.onFailed(blob, reason);
            return;
        }
        listener.onFailed(e.localId, reason);
    }

    /** With internet: ask the server first whether the destination is online and where (off the engine thread). */
    private void prepare(final int dst, final Runnable ready) {
        if (link == null || !link.available()) { ready.run(); return; }
        Long last = refreshedAt.get(dst);
        if (last != null && clock.nowMs() - last < 15_000) { ready.run(); return; }
        io.execute(new Runnable() {
            @Override public void run() {
                try { link.refresh(dst); } catch (RuntimeException ignored) { }
                clock.post(new Runnable() {
                    @Override public void run() {
                        refreshedAt.put(dst, clock.nowMs());
                        int z = link.zoneOf(dst);
                        if (z >= 0) zoneMap.learn(dst, z);
                        ready.run();
                    }
                });
            }
        });
    }

    private void transmit(Outbox.Entry e) {
        if (outbox.get(e.localId) == null) return; // confirmed while waiting for the server
        long now = clock.nowMs();
        try {
            long coreId = node.send(e.dst, e.frame, e.priority);
            inflight.put(coreId, e.localId);
            e.attempts++;
            e.lastSendMs = now;
            e.nextTryMs = now + backoff(e.attempts);
            outbox.save(e);
        } catch (E2eeCrypto.MissingKeyException ex) {
            e.inFlight = false;
            requestKey(e.dst);
        } catch (RuntimeException ex) {
            e.inFlight = false;
            e.nextTryMs = now + backoff(e.attempts + 1);
        }
    }

    private void retryAll(long minAgeMs) {
        long now = clock.nowMs();
        for (Outbox.Entry e : outbox.all()) {
            if (!e.inFlight && now - e.lastSendMs >= minAgeMs) pump(e);
        }
    }

    private void tick() {
        long now = clock.nowMs();
        boolean online = link != null && link.available();
        info.online = online;
        node.setGateway(online && transport.peerCount() > 0 && !node.isLeaf());
        for (java.util.Iterator<Map.Entry<Long, PendingLarge>> it = pendingLarge.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, PendingLarge> w = it.next();
            if (now - w.getValue().createdMs > LARGE_KEY_WAIT_MS) {
                it.remove();
                listener.onFailed(w.getKey(), "no encryption key for the receiver");
            }
        }
        if (!flat && ++tickCount % 3 == 0) transport.gossipNow();
        transport.probeAll();
        for (int peer : transport.neighbors()) transport.setPeerScale(peer, reputation.scale(peer));
        dropWeakLinks(now);
        for (Outbox.Entry e : outbox.all()) {
            if (e.inFlight && now - e.lastSendMs > Math.max(INFLIGHT_MAX_MS, 6 * retryBaseMs / 5)) e.inFlight = false;
            if (!e.inFlight && e.nextTryMs <= now) pump(e);
        }
        clock.schedule(tickMs, new Runnable() { @Override public void run() { tick(); } });
    }

    private final Map<Integer, Long> droppedAt = new java.util.HashMap<>();

    /**
     * Keep the good links: a neighbour that loses 70 % or more of its probes (over at least 10) is disconnected when
     * at least two other neighbours are healthy, so a flaky link cannot drag everything through it. Never drops the
     * only way out. The radio reconnects later on its own schedule.
     */
    private void dropWeakLinks(long now) {
        java.util.List<FramedTransport.LinkQuality> all = transport.qualities();
        int healthy = 0;
        for (FramedTransport.LinkQuality q : all) if (q.samples >= 5 && q.lossPct < 30) healthy++;
        if (healthy < 2) return;
        for (FramedTransport.LinkQuality q : all) {
            if (q.samples >= 10 && q.lossPct >= 70) {
                Long last = droppedAt.get(q.peer);
                if (last != null && now - last < 60_000) continue;
                droppedAt.put(q.peer, now);
                transport.dropPeer(q.peer);
            }
        }
    }

    // ------------------------------------------------------------------ keys

    private void requestKey(int n) {
        long now = clock.nowMs();
        Long last = keyAskedAt.get(n);
        if (last != null && now - last < Math.min(10_000, 5 * retryBaseMs)) return;
        keyAskedAt.put(n, now);
        sendControl(n, Frame.key(Frame.KEY_REQ, identity.publicRaw()));
    }

    private void onKeyArrived(int n) {
        crypto.forget(n);
        for (java.util.Iterator<Map.Entry<Long, PendingLarge>> it = pendingLarge.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, PendingLarge> w = it.next();
            if (w.getValue().dst == n && crypto.hasKey(n)) {
                it.remove();
                startLarge(w.getKey(), n, w.getValue().data, w.getValue().priority);
            }
        }
        for (Outbox.Entry e : outbox.all()) {
            if (e.dst == n && !e.inFlight) { e.nextTryMs = 0; pump(e); }
        }
    }

    // ------------------------------------------------------------------ receiving

    /** A decrypted frame from node src (srcZone may be -1 if unknown). Also used for frames that arrive through DTN custody. */
    void handlePlain(final int src, int srcZone, byte[] plain) {
        receivedCount.incrementAndGet();
        zoneMap.learn(src, srcZone);
        if (!Frame.valid(plain)) return;
        try {
            switch (Frame.type(plain)) {
                case Frame.TEXT:
                case Frame.TEXT_Z: {
                    long id = Frame.localId(plain);
                    boolean fresh = seenRx.put(src + ":" + id, Boolean.TRUE) == null;
                    if (fresh) listener.onText(ids.userOrPlaceholder(src), id, Frame.timestamp(plain), Frame.textOf(plain));
                    receipt(src, id);
                    break;
                }
                case Frame.APP:
                case Frame.APP_Z: {
                    long id = Frame.localId(plain);
                    boolean fresh = seenRx.put(src + ":" + id, Boolean.TRUE) == null;
                    if (fresh) listener.onBytes(ids.userOrPlaceholder(src), id, Frame.appBytes(plain));
                    receipt(src, id);
                    break;
                }
                case Frame.EXT: {
                    long id = Frame.localId(plain);
                    boolean fresh = seenRx.put(src + ":" + id, Boolean.TRUE) == null;
                    if (fresh) {
                        ExtHandler h = extHandlers.get(Frame.extType(plain));
                        if (h != null) h.onExt(src, ids.userOrPlaceholder(src), Frame.extPayload(plain));
                    }
                    receipt(src, id);
                    break;
                }
                case Frame.BLOB: {
                    long id = Frame.localId(plain);
                    boolean fresh = seenRx.put(src + ":" + id, Boolean.TRUE) == null;
                    if (fresh) addChunk(src, plain);
                    receipt(src, id);
                    break;
                }
                case Frame.SEEN:
                    for (long id : Frame.seenIds(plain)) listener.onSeen(id);
                    break;
                case Frame.RCPT:
                    for (long id : Frame.seenIds(plain)) {
                        Outbox.Entry done = outbox.remove(id);
                        if (done != null) notifyDelivered(done);
                        else if (largeSent.remove(id)) {
                            deliveredCount.incrementAndGet();
                            listener.onDelivered(id);
                        }
                    }
                    break;
                case Frame.RESET: {
                    long now = clock.nowMs();
                    Long last = resetAt.get(src);
                    if (last != null && now - last < 30_000) break;          // a neighbour cannot make me restart sessions in a loop
                    resetAt.put(src, now);
                    crypto.resetSession(src);
                    retryAllFor(src);
                    break;
                }
                case Frame.KEY_REQ:
                case Frame.KEY_RES: {
                    final byte[] pub = Frame.keyOf(plain);
                    try { IdentityKeys.publicFromRaw(pub); } catch (java.security.GeneralSecurityException ex) { return; }
                    final boolean isReq = Frame.type(plain) == Frame.KEY_REQ;
                    if (!keys.put(src, pub)) return;       // different key than pinned: the app decides
                    defer(new Runnable() {
                        @Override public void run() {
                            if (isReq) sendControl(src, Frame.key(Frame.KEY_RES, identity.publicRaw()));
                            onKeyArrived(src);
                        }
                    });
                    break;
                }
                default:
            }
        } catch (RuntimeException corrupt) {
            // a frame that authenticated but is malformed (bad compression, odd sizes): drop it, never hurt the app
        }
    }

    private void addChunk(int src, byte[] f) {
        long blobId = Frame.blobId(f);
        int idx = Frame.blobIndex(f), total = Frame.blobTotal(f);
        byte[] data = Frame.blobData(f);
        if (data.length > Frame.BLOB_CHUNK) return;
        String key = src + ":" + blobId;
        Assembly a = assemblies.get(key);
        if (a == null) {
            if ((long) total * Frame.BLOB_CHUNK > MAX_BLOB_BYTES + Frame.BLOB_CHUNK) return;
            while (assemblies.size() >= 4) {                          // memory cap: drop the oldest partial blob
                java.util.Iterator<String> it = assemblies.keySet().iterator();
                it.next();
                it.remove();
            }
            a = new Assembly(total, clock.nowMs());
            assemblies.put(key, a);
        }
        if (a.parts.length != total || a.parts[idx] != null) return;
        a.parts[idx] = data;
        a.got++;
        a.bytes += data.length;
        if (a.got < total) return;
        assemblies.remove(key);
        byte[] all = new byte[a.bytes];
        int pos = 0;
        for (byte[] part : a.parts) { System.arraycopy(part, 0, all, pos, part.length); pos += part.length; }
        listener.onBlob(ids.userOrPlaceholder(src), blobId, all);
    }

    private void retryAllFor(int dst) {
        for (Outbox.Entry e : outbox.all()) if (e.dst == dst && !e.inFlight) { e.nextTryMs = 0; pump(e); }
    }

    /** Tell the sender that this message really arrived and could be read (repeats are re-confirmed too). */
    private void receipt(final int src, final long localId) {
        defer(new Runnable() { @Override public void run() { sendControl(src, Frame.rcpt(localId)); } });
    }

    private void handleDropped(Packet p, String reason) {
        if (p == null || p.src != selfId || p.type != Packet.TYPE_DATA) return;
        Long localId = inflight.remove(p.msgId);
        if (localId == null) return;
        Outbox.Entry e = outbox.get(localId);
        if (e == null) return;
        e.inFlight = false;
        e.nextTryMs = clock.nowMs() + backoff(e.attempts);
        if (dtn != null) dtn.offer(e);
    }

    private void handleAcked(long coreMsgId) {
        Long localId = inflight.remove(coreMsgId);
        if (localId == null) return;
        Outbox.Entry e = outbox.get(localId);
        if (e == null) return;
        if (e.control) { outbox.remove(localId); return; }
        // The core's ACK only proves a packet reached the node; it can also be sent for a packet whose
        // content could not be decrypted. A chat message is complete only when the receiver's RCPT arrives.
        e.inFlight = false;
        e.nextTryMs = clock.nowMs() + Math.max(retryBaseMs, 2_000);
    }
}
