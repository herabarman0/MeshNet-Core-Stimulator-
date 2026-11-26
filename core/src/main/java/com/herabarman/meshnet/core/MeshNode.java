package com.herabarman.meshnet.core;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * One node of the mesh. Pure logic: no sockets, no Android, no threads.
 *
 * Routing rules:
 *  1. The server may carry the packet, depending on {@link DeliveryMode}.
 *  2. Destination in my zone: unicast if a learned route exists, otherwise flood inside the zone.
 *  3. Destination in another zone: use a direct link to that zone if I have one (or, in ring mode, the greedy
 *     closer peer); otherwise hand the packet to my zone (learned route toward a border node, else flood).
 *  4. No route right now: store the packet and retry later (or let the server help).
 *
 * Route learning: the neighbour a packet first arrived from is the way back to its source.
 * ACK + fallback: the destination answers with a tiny ACK; no ACK in time means the sender drops its learned
 * route and re-sends by flooding (attempt 1, 2, ...).
 *
 * Discovery ({@link MeshConfig#discovery}): the node needs no global knowledge. Neighbours greet each other
 * (HELLO) to learn which zone a peer is in; packet headers carry source and destination zone; a distributed
 * location service (PUBLISH to the home zone, LOOKUP to the home zone) resolves "which zone is node X in?".
 */
public final class MeshNode implements Transport.Listener {
    /** fromPeer value for packets that arrived through the server. */
    public static final int SERVER_PEER = -1;
    /** "from" value for packets this node originates (or re-routes itself). */
    private static final int LOCAL = -2;
    /** Route cache keys for "the way toward zone z" are ZONE_KEY_BASE - z (always negative). */
    private static final int ZONE_KEY_BASE = -10;
    public static final long ACK_MASK = 0x5851F42D4C957F2DL;
    private static final long ATTEMPT_MIX = 0x9E3779B97F4A7C15L;

    public interface Listener {
        void onDelivered(MeshNode node, Packet packet, byte[] plain, int fromPeer);
        void onDuplicate(MeshNode node, Packet packet);
        void onDropped(MeshNode node, Packet packet, String reason);
        /** A relay decided not to forward a flooded packet (counter-based suppression). */
        default void onFloodSuppressed(MeshNode node, Packet packet) {}
        /** The destination confirmed one of my messages. */
        default void onAcked(MeshNode node, long msgId) {}
        /** No ACK in time: I am re-sending by flood. */
        default void onRetry(MeshNode node, Packet packet) {}
        /** Congestion: a bulk packet was not relayed so that interactive traffic keeps moving. */
        default void onShed(MeshNode node, Packet packet) {}
    }

    /** Receives packets of the control types (gossip, DHT) addressed to this node. Runs on the engine thread. */
    public interface ControlHandler {
        void onControl(MeshNode node, Packet packet, int fromPeer);
    }

    /** A relayed packet waiting for its forward timer. */
    private static final class Pending {
        final Packet packet;
        final List<Integer> heardFrom = new ArrayList<>(4);

        Pending(Packet packet) {
            this.packet = packet;
        }
    }

    /** A message I sent and have not seen an ACK for yet. */
    private static final class Outgoing {
        final Packet packet;
        int attempt;
        long sentAtMs;
        int firstHop = -1;

        Outgoing(Packet packet) {
            this.packet = packet;
        }
    }

    private final int id;
    private final NodeInfo info;
    private final MeshConfig cfg;
    private final Transport transport;
    private final Clock clock;
    private final ZoneRouter.ZoneMap zones; // only used when discovery is off; may be null otherwise
    private final Crypto crypto;
    private final ServerBridge server;
    private final Random rng;
    private final DedupCache dedup;
    private final DedupCache voiceDedup;
    private final Map<Integer, long[]> sosWindow = new HashMap<>();
    private final LoadMeter load;
    private final Map<Integer, NeighborInfo> neighbors = new HashMap<>();
    private final StoreAndForward store;
    private final StorageAdapter storage;
    private final List<Integer> zonePeers = new ArrayList<>(6);
    private final List<Integer> backbonePeers = new ArrayList<>(2);
    private final List<Integer> knownPeers = new ArrayList<>(6);
    private final Map<Integer, Integer> peerZone = new HashMap<>();
    private final Set<Integer> greeted = new HashSet<>();
    private final ZoneRouter.ZoneMap peerZones = new ZoneRouter.ZoneMap() {
        @Override
        public int zoneOf(int peer) {
            Integer z = peerZone.get(peer);
            return z == null ? Packet.UNKNOWN_ZONE : z;
        }
    };
    private final LocationDirectory directory;
    private final Pacer pacer;
    private final Set<Integer> leafPeers = new HashSet<>();
    private boolean leaf;
    private Listener listener;
    private ControlHandler controlHandler;
    private MultiRouteTable multi;
    private Map<Integer, long[]> pointers;     // node -> {oldZone, newZone, expiresAtMs}
    /** Per neighbour: {beacons sent so far (the sequence number), battery %, load %, flags, time of the last one}. */
    private final Map<Integer, long[]> beaconState = new HashMap<>();
    private boolean beaconScheduled;
    private int lastHop = -1;                  // first hop of the most recent unicast decision, -1 = flood / none
    private int mobilityClass;
    private boolean gateway;
    private boolean retryScheduled;
    private boolean helloRetryScheduled;
    private boolean started;
    private Map<Long, Pending> pending;
    private Map<Long, Outgoing> outgoing;
    private Map<Integer, List<Packet>> awaiting;
    private Map<Integer, Integer> lookupAttempts;
    private RouteCache routes;
    private DedupCache delivered;
    private java.util.LinkedHashMap<Long, Integer> ackHops;

    public MeshNode(NodeInfo info, MeshConfig cfg, Transport transport, Clock clock,
                    ZoneRouter.ZoneMap zones, Crypto crypto, ServerBridge server, Random rng) {
        this(info, cfg, transport, clock, zones, crypto, server, rng, null);
    }

    /**
     * @param zones   nodeId -> zone; required when cfg.discovery is false, ignored (may be null) when it is true
     * @param storage where queued and unconfirmed messages are kept across restarts (null = RAM only)
     */
    public MeshNode(NodeInfo info, MeshConfig cfg, Transport transport, Clock clock,
                    ZoneRouter.ZoneMap zones, Crypto crypto, ServerBridge server, Random rng,
                    StorageAdapter storage) {
        if (!cfg.discovery && zones == null) throw new IllegalArgumentException("zones is required when discovery is off");
        this.storage = storage;
        this.id = info.id;
        this.info = info;
        this.cfg = cfg;
        this.transport = transport;
        this.clock = clock;
        this.zones = zones;
        this.crypto = crypto;
        this.server = server;
        this.rng = rng;
        this.dedup = new DedupCache(cfg.dedupMaxEntries, cfg.dedupTtlMs);
        this.voiceDedup = new DedupCache(cfg.voiceDedupMax, cfg.voiceDedupTtlMs);
        this.load = new LoadMeter(cfg.loadCapacityPerSec, 1000);
        this.store = new StoreAndForward(cfg.storeMaxPackets, storage);
        this.directory = cfg.discovery ? new LocationDirectory(cfg.directoryMaxEntries) : null;
        this.pacer = new Pacer(clock, new Pacer.Sink() {
            @Override
            public void transmit(int peer, byte[] data) {
                MeshNode.this.tx(peer, data);
            }
        }, cfg.txRatePerSec, cfg.txBurst, cfg.txQueueMax, cfg.bulkPriorityBelow, cfg.bulkReserve,
                cfg.txMaxWaitMs, cfg.maxJitterFactor);
        pacer.setSosLane(cfg.sosPriorityFrom, cfg.sosBurst);
        pacer.setBulkMinShare(cfg.bulkMinShare);
        transport.setListener(this);
    }

    public int id() { return id; }
    public int zone() { return info.zone; }
    public boolean isGateway() { return gateway; }
    public void setGateway(boolean gateway) { this.gateway = gateway; }
    public void setListener(Listener listener) { this.listener = listener; }
    public int storedCount() { return store.size(); }
    public int zonePeerCount() { return zonePeers.size(); }
    public boolean isLeaf() { return leaf; }
    /** Did this neighbour tell me (HELLO) that it is saving battery? */
    public boolean isLeafPeer(int peer) { return leafPeers.contains(peer); }
    public Pacer pacer() { return pacer; }

    /**
     * Tell the engine the battery level (0..1). Below lowBattery the node becomes a leaf: it still sends and
     * receives for itself, but no longer relays other nodes' floods, and its neighbours (told by HELLO) stop
     * routing through it. It relays again at lowBattery + batteryHysteresis.
     */
    public void setBattery(double level) {
        info.battery = level;
        boolean shouldBeLeaf = leaf ? level < cfg.lowBattery + cfg.batteryHysteresis : level < cfg.lowBattery;
        if (shouldBeLeaf == leaf) return;
        leaf = shouldBeLeaf;
        if (cfg.discovery) for (int peer : new ArrayList<>(knownPeers)) sendHello(peer);
    }

    private void out(int peer, byte[] data, int priority) {
        pacer.send(peer, data, priority);
    }
    public int backbonePeerCount() { return backbonePeers.size(); }
    public void setControlHandler(ControlHandler h) { this.controlHandler = h; }

    /** Tell a discovery-mode node where somebody lives, from an outside source (gossip, DHT). Wakes messages waiting for a lookup. */
    public void hintLocation(int nodeId, int zone, long ttlMs) {
        if (!cfg.discovery || zone < 0 || nodeId == id) return;
        learnLocation(nodeId, zone, ttlMs);
    }
    /** Direct neighbours right now (copy). */
    public List<Integer> peers() { return new ArrayList<>(knownPeers); }
    /** Zone a direct neighbour told me it is in, or -1. */
    public int peerZoneOf(int peer) { Integer z = peerZone.get(peer); return z == null ? Packet.UNKNOWN_ZONE : z; }
    /** What I know about a direct neighbour (battery, link quality, ...), or null. For diagnostics and tests. */
    public NeighborInfo neighborInfo(int peer) { return neighbors.get(peer); }
    /** My own radio load, 0..1+. */
    public double currentLoad() { return load.load(clock.nowMs()); }
    public int mobilityClass() { return mobilityClass; }

    /**
     * Tell the node how fast its phone is moving (metres per second, from GPS or the step sensor). It goes out in the
     * next beacons; neighbours then avoid routing through a fast mover, and its own learned routes live shorter.
     */
    public void setMobility(double speedMps) {
        int c = Beacon.mobilityClass(speedMps);
        mobilityClass = c;
        info.mobility = c;
    }

    /** Candidate next hops toward dst (or toward its zone) with their hop counts, best first by cost. For diagnostics. */
    public List<Integer> pathsTo(int dst) {
        List<Integer> out = new ArrayList<>();
        int z = resolveZone(dst);
        if (z < 0 || multi == null) return out;
        List<MultiRouteTable.Path> ps = multi.paths(z == info.zone ? dst : ZONE_KEY_BASE - z, clock.nowMs());
        final long now = clock.nowMs();
        ps.sort(new java.util.Comparator<MultiRouteTable.Path>() {
            @Override public int compare(MultiRouteTable.Path a, MultiRouteTable.Path b) {
                return Double.compare(PathSelector.cost(a, neighbors.get(a.nextHop), cfg, now), PathSelector.cost(b, neighbors.get(b.nextHop), cfg, now));
            }
        });
        for (MultiRouteTable.Path p : ps) out.add(p.nextHop);
        return out;
    }

    /**
     * Send a control packet (type Packet.TYPE_GOSSIP / TYPE_DHT ...) to a node whose zone the caller already knows.
     * Routed like data, but best effort: no ACK, no retry, nothing stored. The receiver gets it in its ControlHandler.
     */
    public void sendControl(int type, int dst, int dstZone, byte[] payload, int priority) {
        Packet p = new Packet(type, cfg.defaultTtl, priority, rng.nextLong(), id, dst, payload, 0, info.zone, dstZone);
        dedup.firstSeen(dedupKey(p), clock.nowMs());
        route(p, LOCAL);
    }

    /** Send a control packet to a direct neighbour only (one hop, ttl 1). Used by gossip. */
    public void sendToPeer(int peer, int type, byte[] payload, int priority) {
        if (!knownPeers.contains(peer)) return;
        Packet p = new Packet(type, 1, priority, rng.nextLong(), id, peer, payload, 0, info.zone, peerZoneOf(peer));
        tx(peer, p.encode());
    }

    private void tx(int peer, byte[] data) {
        load.onTransmit(clock.nowMs());
        transport.send(peer, data);
    }

    private NeighborInfo neighbor(int peer) {
        NeighborInfo n = neighbors.get(peer);
        if (n == null) {
            n = new NeighborInfo(peer);
            neighbors.put(peer, n);
        }
        return n;
    }

    /** What this node itself knows about where nodeId lives (never asks the zone map in discovery mode); -1 if unknown. */
    public int knownZoneOf(int nodeId) {
        return resolveZone(nodeId);
    }

    /** Learned next hop toward dst (or toward dst's zone if it is another zone), -1 if none. For diagnostics. */
    public int routeTo(int dst) {
        int z = resolveZone(dst);
        if (z < 0 || routes == null) return -1;
        return routes.get(z == info.zone ? dst : ZONE_KEY_BASE - z, clock.nowMs());
    }

    private int resolveZone(int nodeId) {
        if (nodeId == id) return info.zone;
        if (Packet.isZoneAddress(nodeId)) return nodeId & 0x7FFFFFFF;
        if (cfg.discovery) return directory.zoneOf(nodeId, clock.nowMs());
        return zones.zoneOf(nodeId);
    }

    private int routeKeyFor(Packet p) {
        return p.dstZone == info.zone ? p.dst : ZONE_KEY_BASE - p.dstZone;
    }

    private static long dedupKey(Packet p) {
        return p.msgId + p.attempt * ATTEMPT_MIX;
    }

    /** Voice has its own small, short-lived filter: a call must not push text ids out of the main one. */
    private DedupCache cacheFor(Packet p) {
        return p.type == Packet.TYPE_VOICE ? voiceDedup : dedup;
    }

    /** Hand a packet to the radio queue. Voice gets a short patience: a late voice frame is dropped, not sent. */
    private void outPkt(int peer, byte[] bytes, Packet p) {
        if (p.type == Packet.TYPE_VOICE) pacer.send(peer, bytes, p.priority, cfg.voiceMaxWaitMs);
        else pacer.send(peer, bytes, p.priority);
    }

    // ---- start-up and persistence -----------------------------------------------------------

    /** Begin the background work of discovery (publish my location now and then). Safe to call more than once. */
    public void start() {
        if (started) return;
        started = true;
        if (!cfg.discovery) return;
        clock.schedule(cfg.publishDelayMs + rng.nextInt((int) Math.max(1, cfg.publishJitterMs)), new Runnable() {
            @Override
            public void run() {
                publishLocation();
            }
        });
    }

    private static String outboxKey(long ackId) {
        return "out-" + String.format("%016x", ackId);
    }

    private void saveOutgoing(long ackId, Outgoing o) {
        if (storage == null) return;
        byte[] pk = o.packet.encode();
        byte[] raw = new byte[1 + pk.length];
        raw[0] = (byte) o.attempt;
        System.arraycopy(pk, 0, raw, 1, pk.length);
        storage.put(outboxKey(ackId), raw);
    }

    private void forgetOutgoing(long ackId) {
        if (storage != null) storage.remove(outboxKey(ackId));
    }

    /**
     * Call once after construction when a StorageAdapter is used. Brings back the queued packets and
     * the messages that were sent but never confirmed; the latter are re-sent at once as the next attempt.
     * @return number of restored queued packets + unconfirmed messages
     */
    public int restore() {
        if (storage == null) return 0;
        int count = store.restore(clock.nowMs());
        if (store.size() > 0) scheduleRetry();
        if (cfg.ackEnabled) {
            for (String key : storage.keys("out-")) {
                byte[] raw = storage.get(key);
                try {
                    if (raw == null || raw.length < 1 + Packet.HEADER_SIZE) throw new IllegalArgumentException("short");
                    byte[] pk = new byte[raw.length - 1];
                    System.arraycopy(raw, 1, pk, 0, pk.length);
                    Packet p = Packet.decode(pk);
                    if (p.src != id) throw new IllegalArgumentException("not mine");
                    Outgoing o = new Outgoing(p);
                    o.attempt = raw[0] & 0xFF;
                    if (outgoing == null) outgoing = new HashMap<>();
                    final long ackId = p.msgId ^ ACK_MASK;
                    outgoing.put(ackId, o);
                    clock.schedule(1, new Runnable() {
                        @Override public void run() { onAckTimeout(ackId); }
                    });
                    count++;
                } catch (IllegalArgumentException e) {
                    storage.remove(key);
                }
            }
        }
        return count;
    }

    // ---- sending ----------------------------------------------------------------------------

    /** Encrypt and send a message. Returns the message id. */
    public long send(int dst, byte[] plain, int priority) {
        return send(Packet.TYPE_DATA, dst, plain, priority);
    }

    /** Send one chunk of a large message (used by ChunkLayer). Same delivery guarantees as send(). */
    public long sendChunk(int dst, byte[] plain, int priority) {
        return send(Packet.TYPE_CHUNK, dst, plain, priority);
    }

    /**
     * Send one real-time voice packet (used by VoiceLayer). Encrypted like every message, but: no ACK, no retry,
     * never stored for later, short TTL, top radio priority, and the radio queue drops it if it would go out late.
     * Without a learned route it is flooded; the receiver's reports teach the relays the way, after which it is unicast.
     */
    public void sendVoice(int dst, byte[] plain) {
        long msgId = rng.nextLong();
        byte[] body = crypto.encryptVoice(plain, id, dst, msgId);
        Packet p = new Packet(Packet.TYPE_VOICE, cfg.voiceTtl, cfg.voicePriority, msgId, id, dst, body, 0,
                info.zone, resolveZone(dst));
        voiceDedup.firstSeen(dedupKey(p), clock.nowMs());
        route(p, LOCAL);
    }

    private long send(int type, int dst, byte[] plain, int priority) {
        long msgId = rng.nextLong();
        byte[] body = crypto.encrypt(plain, id, dst, msgId);
        Packet p = new Packet(type, isSos(priority) ? Math.max(cfg.defaultTtl, cfg.sosTtl) : cfg.defaultTtl, priority,
                msgId, id, dst, body, 0, info.zone, resolveZone(dst));
        dedup.firstSeen(dedupKey(p), clock.nowMs()); // so echoes of my own packet are dropped
        if (cfg.ackEnabled) {
            if (outgoing == null) outgoing = new HashMap<>();
            long ackId = msgId ^ ACK_MASK;
            Outgoing o = new Outgoing(p);
            o.sentAtMs = clock.nowMs();
            outgoing.put(ackId, o);
            saveOutgoing(ackId, o);
            scheduleAckTimeout(ackId, 0);
            route(p, LOCAL);
            o.firstHop = lastHop;
            return msgId;
        }
        route(p, LOCAL);
        return msgId;
    }

    /** Per-sender allowance of SOS-level packets (a sliding minute). */
    private boolean sosAllowed(int src) {
        long now = clock.nowMs();
        long[] w = sosWindow.get(src);
        if (w == null) {
            if (sosWindow.size() >= 256) sosWindow.clear();             // bounded memory: forgetting is harmless
            w = new long[]{now, 0};
            sosWindow.put(src, w);
        }
        if (now - w[0] >= 60_000) {
            w[0] = now;
            w[1] = 0;
        }
        return ++w[1] <= cfg.sosPerSourcePerMin;
    }

    private boolean isSos(int priority) {
        return priority >= cfg.sosPriorityFrom && priority < Priority.CONTROL;
    }

    private void scheduleAckTimeout(final long ackId, int attempt) {
        Outgoing o = outgoing == null ? null : outgoing.get(ackId);
        long base = o != null && isSos(o.packet.priority) ? Math.max(1, cfg.ackTimeoutMs / Math.max(1, cfg.sosRetryFactor)) : cfg.ackTimeoutMs;
        clock.schedule(base << attempt, new Runnable() {
            @Override
            public void run() {
                onAckTimeout(ackId);
            }
        });
    }

    private void onAckTimeout(long ackId) {
        Outgoing o = outgoing == null ? null : outgoing.get(ackId);
        if (o == null) return; // ACK arrived
        if (o.attempt >= cfg.maxRetries + (isSos(o.packet.priority) ? cfg.sosExtraRetries : 0)) {
            outgoing.remove(ackId);
            forgetOutgoing(ackId);
            if (listener != null) listener.onDropped(this, o.packet, "no-ack");
            return;
        }
        o.attempt++;
        saveOutgoing(ackId, o);
        if (routes != null && o.packet.dstZone >= 0) {
            routes.remove(o.packet.dstZone == info.zone ? o.packet.dst : ZONE_KEY_BASE - o.packet.dstZone); // that route did not work
        }
        if (o.firstHop >= 0) {                      // I know which neighbour carried it: it gets the blame, only it
            NeighborInfo nb = neighbors.get(o.firstHop);
            if (nb != null) nb.onFailure(clock.nowMs());
            if (multi != null && o.packet.dstZone >= 0) {
                multi.removePath(o.packet.dstZone == info.zone ? o.packet.dst : ZONE_KEY_BASE - o.packet.dstZone, o.firstHop);
            }
        }
        Packet retry = o.packet.withAttempt(o.attempt);
        if (cfg.mobilityAware && cfg.discovery && !Packet.isZoneAddress(retry.dst)) {
            // The destination may have moved to another zone: forget what I knew and ask its home zone again.
            directory.forget(retry.dst);
            retry = retry.withDstZone(Packet.UNKNOWN_ZONE);
        }
        dedup.firstSeen(dedupKey(retry), clock.nowMs());
        if (listener != null) listener.onRetry(this, retry);
        route(retry, LOCAL);
        o.firstHop = lastHop;
        scheduleAckTimeout(ackId, o.attempt);
    }

    private void sendAck(Packet data) {
        Packet ack = new Packet(Packet.TYPE_ACK, cfg.defaultTtl, 255, data.msgId ^ ACK_MASK,
                id, data.src, new byte[0], data.attempt, info.zone, data.srcZone);
        dedup.firstSeen(dedupKey(ack), clock.nowMs());
        lastHop = -1;
        route(ack, LOCAL);
        if (cfg.adaptiveRouting && lastHop >= 0) {
            if (ackHops == null) ackHops = new java.util.LinkedHashMap<Long, Integer>(16, 0.75f, false) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<Long, Integer> e) { return size() > 64; }
            };
            ackHops.put(data.msgId, lastHop);
        }
    }

    /** A repeat of something I already ACKed means that ACK never arrived: blame the neighbour it was sent through. */
    private void ackLost(Packet data) {
        if (ackHops == null || data.attempt == 0) return;
        Integer hop = ackHops.remove(data.msgId);
        if (hop == null) return;
        NeighborInfo nb = neighbors.get(hop);
        if (nb != null) nb.onFailure(clock.nowMs());
        if (multi != null && data.srcZone >= 0) {
            multi.removePath(data.srcZone == info.zone ? data.src : ZONE_KEY_BASE - data.srcZone, hop);
        }
    }

    // ---- receiving --------------------------------------------------------------------------

    @Override
    public void onReceive(int fromPeer, byte[] data) {
        Packet p;
        try {
            p = Packet.decode(data);
        } catch (IllegalArgumentException e) {
            if (listener != null) listener.onDropped(this, null, "malformed");
            return;
        }
        if (p.type == Packet.TYPE_HELLO) {
            handleHello(fromPeer, p);
            return;
        }
        if (isSos(p.priority) && p.src != id && !sosAllowed(p.src)) {
            p = new Packet(p.type, p.ttl, Priority.URGENT, p.msgId, p.src, p.dst, p.payload, p.attempt, p.srcZone, p.dstZone);   // too many alarms from one sender: still delivered, no emergency lane
        }
        if (!cacheFor(p).firstSeen(dedupKey(p), clock.nowMs())) {
            // A neighbour that sends me a packet obviously has it: remember that while my forward timer runs.
            Pending waiting = pending == null ? null : pending.get(dedupKey(p));
            if (waiting != null && fromPeer >= 0 && !waiting.heardFrom.contains(fromPeer)) {
                waiting.heardFrom.add(fromPeer);
            }
            if (cfg.adaptiveRouting) learnRoute(p, fromPeer);   // a later copy over another neighbour = an alternative path
            if (listener != null) listener.onDuplicate(this, p);
            return;
        }
        if (cfg.discovery && p.src >= 0 && p.srcZone >= 0 && p.src != id) {
            learnLocation(p.src, p.srcZone, cfg.locationPassiveTtlMs); // every packet teaches where its sender lives
        }
        learnRoute(p, fromPeer);
        if (Packet.isZoneAddress(p.dst)) {
            if (p.dstZone == info.zone) {
                handleZoneAddressed(p, fromPeer);
                return;
            }
        } else if (p.dst == id) {
            handleLocal(p, fromPeer);
            return;
        }
        if (p.ttl <= 1) {
            if (listener != null) listener.onDropped(this, p, "ttl");
            return;
        }
        String refuse = relayRefusal(p, fromPeer);
        if (refuse != null) {
            if (listener != null) listener.onDropped(this, p, refuse);
            return;
        }
        route(p.withTtl(p.ttl - 1), fromPeer);
    }

    /** Energy and congestion policy for packets I only relay. null = relay it. */
    private String relayRefusal(Packet p, int fromPeer) {
        if (cfg.energyAware && !info.charging && info.battery < cfg.energyRelayCutoff && p.priority < 200) {
            return "low-battery";                    // protect my own last percent; ACKs and urgent control traffic still pass
        }
        if (cfg.congestionControl && p.priority < 64 && !Packet.isZoneAddress(p.dst)) {
            double l = load.load(clock.nowMs());
            if (l > cfg.shedStartLoad) {
                double drop = Math.min(1.0, (l - cfg.shedStartLoad) / Math.max(0.01, 1.0 - cfg.shedStartLoad));
                if (rng.nextDouble() < drop) return "congestion";
            }
        }
        return null;
    }

    private void handleLocal(Packet p, int fromPeer) {
        if (p.type == Packet.TYPE_ACK) {
            Outgoing o = outgoing == null ? null : outgoing.remove(p.msgId);
            if (o != null) forgetOutgoing(p.msgId);
            if (o != null && o.firstHop >= 0) {
                NeighborInfo nb = neighbors.get(o.firstHop);
                if (nb != null) nb.onSuccess(Math.max(0, clock.nowMs() - o.sentAtMs));
            }
            if (o != null && listener != null) listener.onAcked(this, o.packet.msgId);
            return;
        }
        if (p.type == Packet.TYPE_LOOKUP_REPLY) {
            if (cfg.discovery && p.payload.length == 8) {
                ByteBuffer b = ByteBuffer.wrap(p.payload);
                learnLocation(b.getInt(), b.getInt(), cfg.locationTtlMs);
            }
            return;
        }
        if (p.type == Packet.TYPE_VOICE) {              // no ACK, no repeat filter: a late copy is useless, not harmful
            byte[] plain = crypto.decryptVoice(p.payload, p.src, p.dst, p.msgId);
            if (plain == null) {
                if (listener != null) listener.onDropped(this, p, "auth");
                return;
            }
            if (listener != null) listener.onDelivered(this, p, plain, fromPeer);
            return;
        }
        if (p.type == Packet.TYPE_GOSSIP || p.type == Packet.TYPE_DHT) {
            if (controlHandler != null) controlHandler.onControl(this, p, fromPeer);
            return;                                  // best effort: never ACKed
        }
        if (p.type != Packet.TYPE_DATA && p.type != Packet.TYPE_CHUNK) return;
        if (delivered == null) delivered = new DedupCache(cfg.dedupMaxEntries, cfg.dedupTtlMs * 4);
        final boolean firstCopy = delivered.firstSeen(p.msgId, clock.nowMs());
        if (firstCopy) {
            byte[] plain = crypto.decrypt(p.payload, p.src, p.dst, p.msgId);
            if (plain == null) {
                if (listener != null) listener.onDropped(this, p, "auth");
                return; // never confirm something that failed authentication
            }
            if (listener != null) listener.onDelivered(this, p, plain, fromPeer);
        } else if (listener != null) {
            listener.onDuplicate(this, p); // a fallback retransmission of something I already have
        }
        if (!firstCopy) ackLost(p);
        if (cfg.ackEnabled) sendAck(p); // also for repeats: the earlier ACK may have been lost
    }

    /** A packet for every node of my zone (PUBLISH, LOOKUP): act on it, then keep it flowing through the zone. */
    private void handleZoneAddressed(Packet p, int from) {
        if (cfg.discovery && p.type == Packet.TYPE_PUBLISH) {
            learnLocation(p.src, p.srcZone, cfg.locationTtlMs);
            if (p.payload.length == 1) {            // limited copies: pass it on to one random neighbour
                int remaining = p.payload[0] & 0xFF;
                if (remaining > 1 && p.ttl > 1 && !zonePeers.isEmpty()) {
                    int peer = zonePeers.get(rng.nextInt(zonePeers.size()));
                    if (peer == from && zonePeers.size() > 1) peer = zonePeers.get((zonePeers.indexOf(peer) + 1) % zonePeers.size());
                    out(peer, new Packet(p.type, p.ttl - 1, p.priority, p.msgId, p.src, p.dst,
                            new byte[]{(byte) (remaining - 1)}, p.attempt, p.srcZone, p.dstZone).encode(), p.priority);
                }
                return;
            }
            // no copy limit: everyone in the home zone keeps a copy (falls through to the flood below)
        } else if (cfg.discovery && p.type == Packet.TYPE_LOOKUP && p.payload.length == 4) {
            int target = ByteBuffer.wrap(p.payload).getInt();
            int zone = resolveZone(target);
            if (zone >= 0) {
                ByteBuffer reply = ByteBuffer.allocate(8).putInt(target).putInt(zone);
                Packet r = new Packet(Packet.TYPE_LOOKUP_REPLY, cfg.defaultTtl, 200, rng.nextLong(), id, p.src,
                        reply.array(), 0, info.zone, p.srcZone);
                dedup.firstSeen(dedupKey(r), clock.nowMs());
                route(r, LOCAL);
                return; // answered: no need to flood further
            }
        }
        if (p.ttl > 1) floodZone(p.withTtl(p.ttl - 1), from);
    }

    /** The first copy arrives over the fastest path, so its sender-side neighbour is the way back. */
    private void learnRoute(Packet p, int from) {
        if (!cfg.routeLearning || from < 0 || p.src == id || p.srcZone < 0) return;
        if (!zonePeers.contains(from) || leafPeers.contains(from)) return;
        int key = p.srcZone == info.zone ? p.src : ZONE_KEY_BASE - p.srcZone;
        long now = clock.nowMs();
        if (cfg.adaptiveRouting) {
            if (multi == null) multi = new MultiRouteTable(cfg.routeCacheMax, cfg.maxPathsPerDest);
            multi.learn(key, from, Math.max(1, cfg.defaultTtl - p.ttl + 1), now, routeTtlFor(from));
        }
        if (routes == null) routes = new RouteCache(cfg.routeCacheMax, cfg.routeTtlMs);
        if (routes.get(key, now) < 0 || !cfg.adaptiveRouting) routes.put(key, from, now); // diagnostics: first path wins
    }

    /** Routes through or from a mover are short-lived: the longer a neighbour keeps walking, the sooner its routes go stale. */
    private long routeTtlFor(int from) {
        if (!cfg.mobilityAware) return cfg.routeTtlMs;
        NeighborInfo n = neighbors.get(from);
        int m = Math.max(mobilityClass, n == null ? 0 : n.mobility);
        return Math.max(10_000L, cfg.routeTtlMs / (1 + m));
    }

    // ---- neighbours and discovery -----------------------------------------------------------

    @Override
    public void onPeerUp(int peerId) {
        if (!knownPeers.contains(peerId)) knownPeers.add(peerId);
        if (cfg.discovery) {
            sendHello(peerId);
            scheduleHelloRetry();
        } else {
            classify(peerId, zones.zoneOf(peerId));
            if (cfg.beaconMs > 0) sendHello(peerId);   // without discovery a HELLO only carries the beacon
        }
        if (cfg.beaconMs > 0) scheduleBeacon();
    }

    @Override
    public void onLinkQuality(int peerId, int rssiDbm) {
        if (!knownPeers.contains(peerId)) return;
        neighbor(peerId).onRssi(rssiDbm, clock.nowMs());
    }

    @Override
    public void onPeerDown(int peerId) {
        neighbors.remove(peerId);
        beaconState.remove(peerId);
        if (multi != null) multi.removeNextHop(peerId);
        knownPeers.remove(Integer.valueOf(peerId));
        peerZone.remove(peerId);
        leafPeers.remove(peerId);
        greeted.remove(peerId);
        zonePeers.remove(Integer.valueOf(peerId));
        backbonePeers.remove(Integer.valueOf(peerId));
    }

    /** Put a peer into the zone list or the backbone list, now that its zone is known. */
    private void classify(int peer, int zone) {
        peerZone.put(peer, zone);
        zonePeers.remove(Integer.valueOf(peer));
        backbonePeers.remove(Integer.valueOf(peer));
        (zone == info.zone ? zonePeers : backbonePeers).add(peer);
    }

    private void sendHello(int peer) {
        greeted.add(peer);
        Packet h = new Packet(Packet.TYPE_HELLO, 1, 255, rng.nextLong(), id, peer, helloPayload(peer), 0,
                info.zone, Packet.UNKNOWN_ZONE);
        out(peer, h.encode(), 255);
    }

    /**
     * HELLO payload. Without beacons it is the one-byte "I am saving battery" flag (bit0). With beacons it is the
     * 5-byte {@link Beacon}, and the saving-battery flag travels in bit4 of the beacon's flags byte. A receiver
     * tells the two apart by length.
     */
    private byte[] helloPayload(int peer) {
        if (cfg.beaconMs > 0) {
            byte[] b = makeBeacon(peer, false).encode();
            if (leaf) b[1] |= 0x10;
            return b;
        }
        return new byte[]{(byte) (leaf ? 1 : 0)};
    }

    private int flagsNow() {
        return (info.charging ? 1 : 0) | (mobilityClass << 1) | (gateway ? 8 : 0);
    }

    /**
     * The beacon for one neighbour. The sequence number counts the beacons sent to THAT neighbour, so a gap at the
     * receiver means a lost beacon even when beacons are skipped because nothing changed. fresh = this is a new beacon
     * (new sequence number) and not a repeat of the last one.
     */
    private Beacon makeBeacon(int peer, boolean fresh) {
        long now = clock.nowMs();
        int battery = (int) Math.round(Math.max(0, Math.min(1, info.battery)) * 255);
        int ld = (int) Math.round(Math.min(1.0, load.load(now)) * 255);
        long[] st = beaconState.get(peer);
        if (st == null) {
            st = new long[5];
            beaconState.put(peer, st);
            fresh = true;
        }
        if (fresh) {
            st[0] = (st[0] + 1) & 0xFFFF;
            st[1] = battery;
            st[2] = ld;
            st[3] = flagsNow();
            st[4] = now;
        }
        return new Beacon(battery, info.charging, mobilityClass, gateway, ld, (int) st[0]);
    }

    /** Has anything a neighbour cares about changed since the last beacon I sent it? */
    private boolean beaconWorthSending(long[] st, long now) {
        if (st == null) return true;
        int battery = (int) Math.round(Math.max(0, Math.min(1, info.battery)) * 255);
        int ld = (int) Math.round(Math.min(1.0, load.load(now)) * 255);
        return Math.abs(battery - st[1]) >= 13          // battery moved by 5 points
                || Math.abs(ld - st[2]) >= 51           // load moved by 20 points
                || flagsNow() != st[3]                  // charging, mobility class or gateway role changed
                || now - st[4] >= 6 * cfg.beaconMs;     // otherwise a keep-alive every six periods
    }

    /**
     * Beacon round, every beaconMs: a neighbour gets a beacon only if something changed or six periods passed since
     * its last one, so a quiet neighbourhood costs about one tiny packet per neighbour per 6 x beaconMs.
     */
    private void scheduleBeacon() {
        if (beaconScheduled || cfg.beaconMs <= 0) return;
        beaconScheduled = true;
        long delay = cfg.beaconMs - cfg.beaconMs / 10 + rng.nextInt((int) Math.max(1, cfg.beaconMs / 5));
        clock.schedule(delay, new Runnable() {
            @Override
            public void run() {
                beaconScheduled = false;
                if (knownPeers.isEmpty()) return;     // restarts with the next peer-up
                long now = clock.nowMs();
                for (int peer : new ArrayList<>(knownPeers)) {
                    if (!beaconWorthSending(beaconState.get(peer), now)) continue;
                    makeBeacon(peer, true);
                    sendHello(peer);
                }
                scheduleBeacon();
            }
        });
    }

    private void handleHello(int from, Packet p) {
        if (from < 0) return;
        Beacon beacon = Beacon.decode(p.payload);
        if (beacon != null && knownPeers.contains(from)) neighbor(from).onBeacon(beacon, clock.nowMs());
        if (!cfg.discovery) return;
        if (!knownPeers.contains(from)) knownPeers.add(from);
        Integer oldZone = peerZone.get(from);
        if (cfg.mobilityAware && oldZone != null && oldZone != p.srcZone && p.srcZone >= 0) {
            // A neighbour moved to another zone: packets still addressed to its old zone are redirected for a while.
            if (pointers == null) pointers = new HashMap<>();
            pointers.put(from, new long[]{oldZone, p.srcZone, clock.nowMs() + cfg.pointerTtlMs});
            if (routes != null) routes.remove(from);
        }
        classify(from, p.srcZone);
        boolean peerLeaf = p.payload.length >= Beacon.SIZE ? (p.payload[1] & 0x10) != 0
                : p.payload.length > 0 && (p.payload[0] & 1) != 0;
        if (peerLeaf) leafPeers.add(from);   // it is saving battery: do not route through it
        else leafPeers.remove(from);
        learnLocation(from, p.srcZone, cfg.locationPassiveTtlMs);
        if (!greeted.contains(from)) sendHello(from); // they have not heard from me yet
    }

    private void scheduleHelloRetry() {
        if (helloRetryScheduled) return;
        helloRetryScheduled = true;
        clock.schedule(cfg.helloRetryMs, new Runnable() {
            @Override
            public void run() {
                helloRetryScheduled = false;
                boolean again = false;
                for (int peer : new ArrayList<>(knownPeers)) {
                    if (!peerZone.containsKey(peer)) {
                        sendHello(peer);
                        again = true;
                    }
                }
                if (again) scheduleHelloRetry();
            }
        });
    }

    /** I moved to another zone: tell the neighbours and the location service. */
    public void setZone(int newZone) {
        if (newZone == info.zone) return;
        info.zone = newZone;
        routes = null;
        for (Integer peer : new ArrayList<>(peerZone.keySet())) classify(peer, peerZone.get(peer));
        if (cfg.discovery) {
            for (int peer : new ArrayList<>(knownPeers)) sendHello(peer);
            publishLocation();
        }
    }

    // ---- location service -------------------------------------------------------------------

    private void learnLocation(int nodeId, int zone, long ttlMs) {
        directory.learn(nodeId, zone, clock.nowMs(), ttlMs);
        if (awaiting != null && awaiting.containsKey(nodeId)) releaseAwaiting(nodeId, zone);
    }

    /** Tell my home zone where I am. Repeats every publishEveryMs. */
    private void publishLocation() {
        int home = LocationDirectory.homeZone(id, cfg.zoneCount);
        byte[] copies = cfg.publishReplicas > 0 ? new byte[]{(byte) cfg.publishReplicas} : new byte[0];
        Packet p = new Packet(Packet.TYPE_PUBLISH, cfg.defaultTtl, 150, rng.nextLong(), id,
                Packet.zoneAddress(home), copies, 0, info.zone, home);
        dedup.firstSeen(dedupKey(p), clock.nowMs());
        if (home == info.zone && cfg.publishReplicas > 0) {
            if (!zonePeers.isEmpty()) out(zonePeers.get(rng.nextInt(zonePeers.size())), p.encode(), p.priority);
        } else {
            route(p, LOCAL);
        }
        clock.schedule(cfg.publishEveryMs, new Runnable() {
            @Override
            public void run() {
                publishLocation();
            }
        });
    }

    /** I do not know the destination's zone: keep the packet and ask its home zone. */
    private void awaitLookup(Packet p) {
        if (!cfg.discovery) {
            if (listener != null) listener.onDropped(this, p, "unknown-zone");
            return;
        }
        if (awaiting == null) {
            awaiting = new HashMap<>();
            lookupAttempts = new HashMap<>();
        }
        List<Packet> list = awaiting.get(p.dst);
        if (list == null) {
            list = new ArrayList<>();
            awaiting.put(p.dst, list);
        }
        if (list.size() >= cfg.awaitingMax) {
            if (listener != null) listener.onDropped(this, p, "lookup-queue-full");
            return;
        }
        list.add(p);
        if (!lookupAttempts.containsKey(p.dst)) {
            lookupAttempts.put(p.dst, 0);
            sendLookup(p.dst);
        }
    }

    private void sendLookup(final int target) {
        final int attempt = lookupAttempts.get(target);
        int home = LocationDirectory.homeZone(target, cfg.zoneCount);
        Packet lk = new Packet(Packet.TYPE_LOOKUP, cfg.defaultTtl, 200, rng.nextLong(), id,
                Packet.zoneAddress(home), ByteBuffer.allocate(4).putInt(target).array(), 0, info.zone, home);
        dedup.firstSeen(dedupKey(lk), clock.nowMs());
        route(lk, LOCAL);
        clock.schedule(cfg.lookupTimeoutMs << attempt, new Runnable() {
            @Override
            public void run() {
                onLookupTimeout(target, attempt);
            }
        });
    }

    private void onLookupTimeout(int target, int attempt) {
        if (awaiting == null || !awaiting.containsKey(target)) return; // answered meanwhile
        Integer current = lookupAttempts.get(target);
        if (current == null || current != attempt) return;
        if (attempt >= cfg.lookupRetries) {
            List<Packet> list = awaiting.remove(target);
            lookupAttempts.remove(target);
            if (listener != null) for (Packet p : list) listener.onDropped(this, p, "unknown-destination");
            return;
        }
        lookupAttempts.put(target, attempt + 1);
        sendLookup(target);
    }

    private void releaseAwaiting(int target, int zone) {
        List<Packet> list = awaiting.remove(target);
        lookupAttempts.remove(target);
        if (list == null) return;
        for (Packet p : list) route(p.withDstZone(zone), LOCAL);
    }

    // ---- routing ----------------------------------------------------------------------------

    private void route(Packet p, int from) {
        lastHop = -1;
        if (p.dstZone < 0) {                       // the sender could not resolve the destination yet
            int z = resolveZone(p.dst);
            if (z < 0) {
                if (p.type == Packet.TYPE_VOICE) {      // never park voice: set the call up with a normal message first
                    if (listener != null) listener.onDropped(this, p, "voice-unknown-zone");
                    return;
                }
                awaitLookup(p);
                return;
            }
            p = p.withDstZone(z);
        }
        if (pointers != null && !Packet.isZoneAddress(p.dst) && p.dst != id) {
            long[] ptr = pointers.get(p.dst);
            if (ptr != null) {
                if (ptr[2] <= clock.nowMs()) pointers.remove(p.dst);
                else if (p.dstZone == (int) ptr[0]) p = p.withDstZone((int) ptr[1]);   // it moved: follow it
            }
        }
        boolean serverOk = cfg.deliveryMode != DeliveryMode.OFFLINE_ONLY && !Packet.isZoneAddress(p.dst)
                && server != null && server.available() && server.isReachable(p.dst);
        boolean border = !backbonePeers.isEmpty();
        boolean mayUseServer = serverOk && (p.src == id || border || gateway);
        // ONLINE_FIRST goes straight to the server; otherwise the server only helps after an ACK timeout (attempt > 0).
        if (mayUseServer && (cfg.deliveryMode == DeliveryMode.ONLINE_FIRST || p.attempt > 0)) {
            server.send(p.dst, p.encode());
            return;
        }
        if (mayUseServer && isSos(p.priority)) {
            server.send(p.dst, p.encode());       // an alarm goes both ways at once: through the server and through the mesh below
        }
        int dstZone = p.dstZone;
        if (dstZone == info.zone) {
            forwardInZone(p, from);
            return;
        }
        int hop = backboneHop(dstZone);
        if (hop >= 0) {
            lastHop = hop;
            outPkt(hop, p.encode(), p);
            return;
        }
        boolean fromBackbone = from >= 0 && !zonePeers.contains(from);
        boolean ownsBackbone = cfg.fullMesh ? (border && fromBackbone) : (border || gateway);
        if (ownsBackbone) {
            if (mayUseServer) {            // no mesh route right now: the server helps
                server.send(p.dst, p.encode());
            } else if (p.type == Packet.TYPE_VOICE) {
                if (listener != null) listener.onDropped(this, p, "voice-no-route");   // stale audio is never stored
            } else {
                storeForLater(p, clock.nowMs() + (isSos(p.priority) ? cfg.sosStoreTtlMs : cfg.storeTtlMs));
            }
        } else {
            forwardInZone(p, from);        // hand it to a border node of my zone
        }
    }

    /** A direct link to the destination zone if I have one, else (ring mode) the greedy closer peer. */
    private int backboneHop(int dstZone) {
        if (cfg.adaptiveRouting) {                    // several direct links to that zone: pick by measured quality, not by chance
            long now = clock.nowMs();
            List<MultiRouteTable.Path> direct = new ArrayList<>(4);
            for (int peer : backbonePeers) {
                Integer z = peerZone.get(peer);
                if (z == null || z != dstZone) continue;
                NeighborInfo nb = neighbors.get(peer);
                if (cfg.beaconMs > 0 && nb != null && nb.silent(now, 9 * cfg.beaconMs)) continue;
                direct.add(new MultiRouteTable.Path(peer, 1, now, now));
            }
            if (!direct.isEmpty()) {
                return PathSelector.choose(direct, new PathSelector.Lookup() {
                    @Override public NeighborInfo get(int peer) { return neighbors.get(peer); }
                }, cfg, now, rng);
            }
        }
        int chosen = -1;
        int seen = 0;
        int chosenLeaf = -1;
        int seenLeaf = 0;
        for (int peer : backbonePeers) {
            Integer z = peerZone.get(peer);
            if (z == null || z != dstZone) continue;
            if (leafPeers.contains(peer)) {
                if (rng.nextInt(++seenLeaf) == 0) chosenLeaf = peer;
            } else if (rng.nextInt(++seen) == 0) {
                chosen = peer;                                                       // random among direct links
            }
        }
        if (chosen < 0) chosen = chosenLeaf;                                         // a low-battery link beats no link
        if (chosen >= 0 || cfg.fullMesh || backbonePeers.isEmpty()) return chosen;
        return ZoneRouter.chooseNextHop(info.zone, dstZone, cfg.zoneCount, backbonePeers, peerZones, peer -> true);
    }

    /** Unicast along a learned route if there is a live one, otherwise flood. Retransmissions always flood. */
    private void forwardInZone(Packet p, int from) {
        if (cfg.adaptiveRouting && cfg.routeLearning && p.attempt == 0 && multi != null) {
            int next = pickPath(routeKeyFor(p), from);
            if (next >= 0) {
                lastHop = next;
                tx(next, p.encode());
                return;
            }
        } else if (cfg.routeLearning && p.attempt == 0 && routes != null) {
            int next = routes.get(routeKeyFor(p), clock.nowMs());
            if (next >= 0 && next != from && zonePeers.contains(next) && !leafPeers.contains(next)) {
                lastHop = next;
                outPkt(next, p.encode(), p);
                return;
            }
        }
        floodZone(p, from);
    }

    /** Best live first hop among the known paths (never back where it came from), or -1. */
    private int pickPath(int key, int from) {
        long now = clock.nowMs();
        List<MultiRouteTable.Path> usable = new ArrayList<>(4);
        for (MultiRouteTable.Path path : multi.paths(key, now)) {
            if (path.nextHop == from || !zonePeers.contains(path.nextHop)) continue;
            NeighborInfo nb = neighbors.get(path.nextHop);
            if (cfg.beaconMs > 0 && nb != null && nb.silent(now, 9 * cfg.beaconMs)) continue;   // beacons stopped: link is dead
            usable.add(path);
        }
        return PathSelector.choose(usable, new PathSelector.Lookup() {
            @Override public NeighborInfo get(int peer) { return neighbors.get(peer); }
        }, cfg, now, rng);
    }

    /**
     * Flood inside the zone. The originator sends at once. Relays wait a random time first and then
     * skip neighbours that already sent them the packet (sender suppression), and, if enough
     * neighbours did, skip forwarding altogether (counter-based suppression).
     */
    private void floodZone(Packet p, int from) {
        if (leaf && from != LOCAL) return;           // low battery: I only send and receive for myself
        if (cfg.floodJitterMs <= 0 || from == LOCAL || p.type == Packet.TYPE_VOICE || isSos(p.priority)) {   // voice and SOS never wait at a relay
            byte[] bytes = p.encode();
            for (int peer : zonePeers) {
                if (peer != from) outPkt(peer, bytes, p);
            }
            return;
        }
        final Pending pd = new Pending(p);
        if (from >= 0) pd.heardFrom.add(from);
        if (pending == null) pending = new HashMap<>();
        pending.put(dedupKey(p), pd);
        int wait = rng.nextInt((int) (cfg.floodJitterMs * pacer.jitterFactor()) + 1);
        if (cfg.energyAware && !info.charging && info.battery < cfg.energyHealthy) {
            // a weak battery waits a little longer (at most twice), so strong ones relay first and it can often stay silent
            double weak = (cfg.energyHealthy - Math.max(0.0, info.battery)) / cfg.energyHealthy;
            wait = (int) Math.round(wait * (1.0 + weak));
        }
        clock.schedule(wait, new Runnable() {
            @Override
            public void run() {
                fireFlood(pd);
            }
        });
    }

    private void fireFlood(Pending pd) {
        pending.remove(dedupKey(pd.packet));
        // In ring mode a gateway is the only way out of the zone, so it never stays silent.
        // In full-mesh mode most nodes are border nodes with several parallel links, so none is critical.
        boolean critical = (gateway && !cfg.fullMesh) || isSos(pd.packet.priority);   // an alarm is never held back or thinned
        if (!critical && pacer.congested() && pd.packet.priority < cfg.bulkPriorityBelow) {
            if (listener != null) listener.onShed(this, pd.packet);   // the radio is saturated: bulk waits for the sender's retry
            return;
        }
        int counter = cfg.counterThreshold;
        int fanout = cfg.floodFanout;
        if (pacer.congested()) {      // backpressure: fewer copies while the radio is saturated
            counter = counter > 0 ? Math.min(counter, cfg.congestedCounter) : cfg.congestedCounter;
            fanout = fanout > 0 ? Math.min(fanout, cfg.congestedFanout) : cfg.congestedFanout;
        }
        if (cfg.energyAware && !info.charging && counter > 1) {   // a weak battery stays silent more readily; a very weak one the most
            if (info.battery < cfg.energyHealthy / 2) counter = Math.max(1, counter - 2);
            else if (info.battery < cfg.energyHealthy) counter = counter - 1;
        }
        if (!critical && counter > 0 && pd.heardFrom.size() >= counter) {
            if (listener != null) listener.onFloodSuppressed(this, pd.packet);
            return;
        }
        List<Integer> targets = new ArrayList<>(zonePeers.size());
        for (int peer : zonePeers) {
            if (!pd.heardFrom.contains(peer)) targets.add(peer);
        }
        if (!critical && fanout > 0 && targets.size() > fanout) {
            Collections.shuffle(targets, rng);
            targets = targets.subList(0, fanout);
        }
        if (targets.isEmpty()) return;
        byte[] bytes = pd.packet.encode();
        for (int peer : targets) outPkt(peer, bytes, pd.packet);
    }

    private void storeForLater(Packet p, long expiresAtMs) {
        if (store.enqueue(p, expiresAtMs, clock.nowMs())) {
            scheduleRetry();
        } else if (listener != null) {
            listener.onDropped(this, p, "store-full");
        }
    }

    private void scheduleRetry() {
        if (retryScheduled) return;
        retryScheduled = true;
        clock.schedule(cfg.retryMs, new Runnable() {
            @Override
            public void run() {
                retryScheduled = false;
                for (StoreAndForward.Item item : store.takeAll(clock.nowMs())) {
                    int dstZone = item.packet.dstZone;
                    if (dstZone >= 0 && dstZone != info.zone && (!backbonePeers.isEmpty() || gateway)) {
                        int hop = backboneHop(dstZone);
                        if (hop >= 0) {
                            out(hop, item.packet.encode(), item.packet.priority);
                            store.done(item);
                        } else {
                            store.requeue(item);
                        }
                    } else {
                        route(item.packet, LOCAL); // role changed: normal routing decides
                        store.done(item);
                    }
                }
                if (store.size() > 0) scheduleRetry();
            }
        });
    }
}
