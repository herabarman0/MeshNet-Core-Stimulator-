package com.herabarman.meshnet.sim;

import com.herabarman.meshnet.core.AesGcmCrypto;
import com.herabarman.meshnet.core.ChunkLayer;
import com.herabarman.meshnet.core.VoiceLayer;
import com.herabarman.meshnet.core.Crypto;
import com.herabarman.meshnet.core.GatewayManager;
import com.herabarman.meshnet.core.MeshConfig;
import com.herabarman.meshnet.core.MeshNode;
import com.herabarman.meshnet.core.NoopCrypto;
import com.herabarman.meshnet.core.NodeInfo;
import com.herabarman.meshnet.core.Packet;
import com.herabarman.meshnet.core.ServerBridge;
import com.herabarman.meshnet.core.Transport;
import com.herabarman.meshnet.core.ZoneRouter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The simulated world: nodes, links, zones, gateways, server, failures.
 *
 * Simplification: the "gateway directory" (which nodes are gateways of zone X) is read from
 * global state instead of a real gossip/DHT. Everything else runs through the real core engine.
 */
public final class SimNetwork implements ZoneRouter.ZoneMap {
    /** Observer for visualisation. All methods run on the simulation thread. */
    public interface Tracer {
        /** A packet is on the wire (viaServer: it goes node -> server -> node). */
        void onTransmit(long timeMs, int from, int to, byte[] data, boolean viaServer);
        default void onSent(long msgId, int src, int dst) {}
        default void onDelivered(long msgId, int dst, long latencyMs, int hops, boolean viaServer) {}
        default void onAcked(long msgId) {}
        default void onRetry(long msgId) {}
    }

    public final SimConfig cfg;
    public final SimClock clock = new SimClock();
    public final Metrics metrics = new Metrics();
    public final int n;
    public final int zoneCount;

    private final MeshConfig meshCfg = new MeshConfig();
    private final Random rng;
    private final GatewayManager gm;
    private final NodeInfo[] infos;
    private final MeshNode[] nodes;
    private final HashSet<Integer>[] links;
    private final List<List<Integer>> gateways = new ArrayList<>();
    private final boolean[] electionPending;
    private final boolean fullMesh;
    private final boolean discovery;
    private final int[] bbDegree;
    private final Set<Integer> repairPending = new HashSet<>();
    private final int[] zoneArr;
    private final boolean[] roaming;
    private final Map<Long, Long> sentAt = new HashMap<>();
    private Tracer tracer;
    private final double[] capTokens;
    private final long[] capStamp;
    private final int[] nodeTx;
    private final Map<Long, Integer> classOf = new HashMap<>();
    private MeshNode.Listener nodeHooks;
    private ChunkLayer[] chunkLayers;
    private VoiceLayer[] voiceLayers;
    private final List<VoiceEvent> voiceEvents = new ArrayList<>();
    private final Map<Long, byte[]> completedTransfers = new HashMap<>();
    private final Map<Long, Integer> completedAt = new HashMap<>();
    private final Map<Long, String> failedTransfers = new HashMap<>();

    @SuppressWarnings("unchecked")
    public SimNetwork(SimConfig cfg) {
        if (cfg.zoneSize < 2) throw new IllegalArgumentException("zoneSize must be >= 2");
        this.cfg = cfg;
        this.n = cfg.nodes;
        this.zoneCount = Math.max(1, n / cfg.zoneSize);
        this.rng = new Random(cfg.seed);
        String mode = cfg.backbone == null ? "auto" : cfg.backbone;
        this.fullMesh = zoneCount > 1 && ("full".equals(mode) || ("auto".equals(mode) && zoneCount <= cfg.fullMeshMaxZones));
        this.bbDegree = new int[n];
        this.gm = new GatewayManager(0.4, 0.3, 0.3, cfg.batteryAware ? 0.20 : 0.0);
        this.capTokens = cfg.radioCapacityPerSec > 0 ? new double[cfg.nodes] : null;
        this.capStamp = cfg.radioCapacityPerSec > 0 ? new long[cfg.nodes] : null;
        this.nodeTx = new int[cfg.nodes];
        if (capTokens != null) java.util.Arrays.fill(capTokens, cfg.radioCapacityPerSec);
        this.meshCfg.txRatePerSec = cfg.txRatePerSec;
        this.meshCfg.txBurst = cfg.txBurst;
        this.meshCfg.chunkBytes = cfg.chunkBytes;
        this.meshCfg.voiceAutoRedundancy = cfg.voiceAutoRedundancy;
        this.meshCfg.bulkMinShare = cfg.bulkMinShare;
        this.zoneArr = new int[n];
        this.roaming = new boolean[n];
        for (int i = 0; i < n; i++) zoneArr[i] = Math.min(i / cfg.zoneSize, zoneCount - 1);
        this.discovery = cfg.discoveryOn();
        this.meshCfg.discovery = discovery;
        this.meshCfg.publishReplicas = cfg.publishReplicas;
        this.meshCfg.fullMesh = fullMesh;
        this.meshCfg.deliveryMode = cfg.mode;
        this.meshCfg.zoneCount = zoneCount;
        this.meshCfg.floodJitterMs = cfg.floodJitterMs;
        this.meshCfg.counterThreshold = cfg.counterThreshold;
        this.meshCfg.floodFanout = cfg.floodFanout;
        this.meshCfg.adaptiveRouting = cfg.adaptive;
        this.meshCfg.energyAware = cfg.energyAware;
        this.meshCfg.congestionControl = cfg.congestion;
        this.meshCfg.mobilityAware = cfg.mobilityAware;
        this.meshCfg.beaconMs = cfg.beaconMs;
        this.meshCfg.loadCapacityPerSec = cfg.loadCapacity;
        this.meshCfg.routeLearning = cfg.routeLearning;
        this.meshCfg.ackEnabled = cfg.ackEnabled;
        this.meshCfg.ackTimeoutMs = cfg.ackTimeoutMs;
        this.meshCfg.maxRetries = cfg.maxRetries;
        this.infos = new NodeInfo[n];
        this.nodes = new MeshNode[n];
        this.links = (HashSet<Integer>[]) new HashSet[n];
        this.electionPending = new boolean[zoneCount];

        Crypto crypto = new NoopCrypto();
        if (cfg.encrypt) {
            byte[] key = new byte[32];
            rng.nextBytes(key);
            crypto = new AesGcmCrypto(key);
        }
        MeshNode.Listener hooks = new MeshNode.Listener() {
            @Override
            public void onDelivered(MeshNode node, Packet p, byte[] plain, int fromPeer) {
                Long t0 = sentAt.remove(p.msgId);
                if (t0 == null) return;
                metrics.delivered++;
                Integer cls = classOf.remove(p.msgId);
                if (cls != null) metrics.deliveredByClass[cls]++;
                metrics.hopsSum += meshCfg.defaultTtl - p.ttl + 1;
                long lat = clock.nowMs() - t0;
                metrics.latencySum += lat;
                metrics.latencies.add(lat);
                if (fromPeer == MeshNode.SERVER_PEER) metrics.viaServer++;
                if (tracer != null) tracer.onDelivered(p.msgId, node.id(), lat,
                        meshCfg.defaultTtl - p.ttl + 1, fromPeer == MeshNode.SERVER_PEER);
            }

            @Override
            public void onDuplicate(MeshNode node, Packet p) {
                metrics.duplicates++;
            }

            @Override
            public void onDropped(MeshNode node, Packet p, String reason) {
                if ("no-ack".equals(reason)) metrics.unacked++;
                else metrics.dropped++;
            }

            @Override
            public void onAcked(MeshNode node, long msgId) {
                metrics.acked++;
                if (tracer != null) tracer.onAcked(msgId);
            }

            @Override
            public void onShed(MeshNode node, Packet p) {
                metrics.shed++;
            }

            @Override
            public void onRetry(MeshNode node, Packet p) {
                metrics.retries++;
                if (tracer != null) tracer.onRetry(p.msgId);
            }

            @Override
            public void onFloodSuppressed(MeshNode node, Packet p) {
                metrics.suppressed++;
            }
        };
        this.nodeHooks = hooks;
        for (int i = 0; i < n; i++) {
            int z = zoneOf(i);
            infos[i] = new NodeInfo(i, z, 0.2 + 0.8 * rng.nextDouble(), 0.2 + 0.8 * rng.nextDouble(),
                    rng.nextDouble() < cfg.onlineRatio);
            links[i] = new HashSet<>(8);
            final int id = i;
            Transport t = new Transport() {
                @Override public void send(int peerId, byte[] data) { transmit(id, peerId, data); }
                @Override public void setListener(Listener l) { /* node registers itself; routing is direct */ }
            };
            ServerBridge bridge = new ServerBridge() {
                @Override public boolean available() { return infos[id].alive && infos[id].online; }
                @Override public boolean isReachable(int nodeId) { return infos[nodeId].alive && infos[nodeId].online; }
                @Override public void send(int dstNode, byte[] data) { serverSend(id, dstNode, data); }
            };
            nodes[i] = new MeshNode(infos[i], meshCfg, t, clock, discovery ? null : this, crypto, bridge, rng);
            nodes[i].setListener(hooks);
        }
        for (int z = 0; z < zoneCount; z++) gateways.add(new ArrayList<Integer>());
        buildIntraZoneLinks();
        if (fullMesh) {
            buildFullMesh();
        } else {
            electInitialGateways();
            for (int z = 0; z < zoneCount; z++) rebuildBackboneFor(z);
        }
        if (discovery) for (MeshNode node : nodes) node.start();
        if (cfg.batteryAware) for (int i = 0; i < n; i++) nodes[i].setBattery(infos[i].battery);
    }

    public boolean isDiscovery() { return discovery; }

    public boolean isFullMesh() { return fullMesh; }

    // ---- addressing -------------------------------------------------------------------------

    @Override
    public int zoneOf(int id) {
        return zoneArr[id];
    }

    private int zoneStart(int z) { return z * cfg.zoneSize; }
    private int zoneEnd(int z) { return z == zoneCount - 1 ? n : (z + 1) * cfg.zoneSize; }

    public NodeInfo info(int id) { return infos[id]; }
    public MeshNode node(int id) { return nodes[id]; }
    /** Gateways (ring mode) or border nodes, i.e. nodes with offline links to other zones (full mesh). */
    public List<Integer> gatewaysOf(int zone) {
        if (!fullMesh) return new ArrayList<>(gateways.get(zone));
        List<Integer> out = new ArrayList<>();
        for (int i = zoneStart(zone); i < zoneEnd(zone); i++) if (infos[i].alive && bbDegree[i] > 0) out.add(i);
        return out;
    }

    // ---- topology ---------------------------------------------------------------------------

    private void connect(int a, int b) {
        if (a == b) return;
        boolean added = links[a].add(b);
        links[b].add(a);
        if (added && zoneOf(a) != zoneOf(b)) {
            bbDegree[a]++;
            bbDegree[b]++;
            if (fullMesh) {
                nodes[a].setGateway(true);
                nodes[b].setGateway(true);
            }
        }
        nodes[a].onPeerUp(b);
        nodes[b].onPeerUp(a);
    }

    private void disconnect(int a, int b) {
        boolean had = links[a].remove(b);
        links[b].remove(a);
        if (had && zoneOf(a) != zoneOf(b)) {
            bbDegree[a]--;
            bbDegree[b]--;
            if (fullMesh) {
                nodes[a].setGateway(bbDegree[a] > 0);
                nodes[b].setGateway(bbDegree[b] > 0);
            }
        }
        nodes[a].onPeerDown(b);
        nodes[b].onPeerDown(a);
    }

    // ---- full zone-to-zone mesh -------------------------------------------------------------

    /** Every pair of zones gets linksPerPair offline links, each between different border nodes. */
    private void buildFullMesh() {
        for (int a = 0; a < zoneCount; a++) {
            for (int b = a + 1; b < zoneCount; b++) linkPair(a, b, cfg.linksPerPair);
        }
    }

    private void linkPair(int a, int b, int count) {
        for (int j = 0; j < count; j++) {
            int na = pickBorder(a);
            int nb = pickBorder(b);
            if (na < 0 || nb < 0) return;
            connect(na, nb);
        }
    }

    /** Alive member with the fewest offline links to other zones (best score on ties), so the load is spread. */
    private int pickBorder(int zone) {
        int best = -1;      // best among nodes with enough battery
        int fallback = -1;  // best among all alive nodes, used only if everybody is low
        for (int i = zoneStart(zone); i < zoneEnd(zone); i++) {
            if (!infos[i].alive || roaming[i]) continue;    // a node that is walking away is not a border node
            if (fallback < 0 || betterBorder(i, fallback)) fallback = i;
            if (cfg.batteryAware && infos[i].battery < 0.20) continue;
            if (best < 0 || betterBorder(i, best)) best = i;
        }
        return best >= 0 ? best : fallback;
    }

    private boolean betterBorder(int a, int b) {
        return bbDegree[a] < bbDegree[b] || (bbDegree[a] == bbDegree[b] && gm.score(infos[a]) > gm.score(infos[b]));
    }

    private int liveLinks(int a, int b) {
        int count = 0;
        for (int i = zoneStart(a); i < zoneEnd(a); i++) {
            if (!infos[i].alive) continue;
            for (int nb : links[i]) if (zoneOf(nb) == b && infos[nb].alive) count++;
        }
        return count;
    }

    private void scheduleRepair(int a, int b) {
        final int lo = Math.min(a, b);
        final int hi = Math.max(a, b);
        final int key = lo * zoneCount + hi;
        if (!repairPending.add(key)) return;
        clock.schedule(cfg.electionDelayMs, new Runnable() {
            @Override
            public void run() {
                repairPending.remove(key);
                int need = cfg.linksPerPair - liveLinks(lo, hi);
                if (need > 0) linkPair(lo, hi, need);
            }
        });
    }

    /** Ring through the zone plus one random chord per node: about 3-5 links per node. */
    private void buildIntraZoneLinks() {
        for (int z = 0; z < zoneCount; z++) {
            int start = zoneStart(z);
            int size = zoneEnd(z) - start;
            for (int i = 0; i < size; i++) connect(start + i, start + (i + 1) % size);
            if (size >= 4) {
                for (int i = 0; i < size; i++) connect(start + i, start + rng.nextInt(size));
            }
        }
    }

    private List<NodeInfo> members(int z) {
        List<NodeInfo> out = new ArrayList<>();
        for (int i = zoneStart(z); i < zoneEnd(z); i++) if (!roaming[i]) out.add(infos[i]);
        return out;
    }

    private void electInitialGateways() {
        for (int z = 0; z < zoneCount; z++) {
            List<Integer> elected = gm.elect(members(z), cfg.gatewayCount);
            gateways.set(z, elected);
            for (int g : elected) nodes[g].setGateway(true);
        }
    }

    /** Connect this zone's gateways to the current gateways of its backbone neighbour zones. */
    private void rebuildBackboneFor(int z) {
        List<Integer> mine = gateways.get(z);
        if (mine.isEmpty()) return;
        for (int nb : ZoneRouter.backboneNeighbors(z, zoneCount, cfg.maxFanOut)) {
            List<Integer> theirs = gateways.get(nb);
            if (theirs.isEmpty()) continue;
            for (int i = 0; i < mine.size(); i++) connect(mine.get(i), theirs.get(i % theirs.size()));
            for (int j = 0; j < theirs.size(); j++) connect(theirs.get(j), mine.get(j % mine.size()));
        }
    }

    private void removeBackboneLinks(int g) {
        int z = zoneOf(g);
        for (int nb : new ArrayList<>(links[g])) {
            if (zoneOf(nb) != z) disconnect(g, nb);
        }
    }

    // ---- failures and election --------------------------------------------------------------

    public void killNode(int id) {
        if (!infos[id].alive) return;
        infos[id].alive = false;
        for (int nb : links[id]) nodes[nb].onPeerDown(id);
        if (fullMesh) {
            for (int nb : links[id]) if (zoneOf(nb) != zoneOf(id)) scheduleRepair(zoneOf(id), zoneOf(nb));
        } else if (nodes[id].isGateway()) {
            scheduleElection(zoneOf(id));
        }
    }

    public void reviveNode(int id) {
        if (infos[id].alive) return;
        infos[id].alive = true;
        for (int nb : links[id]) {
            if (infos[nb].alive) {
                nodes[id].onPeerUp(nb);
                nodes[nb].onPeerUp(id);
            } else {
                nodes[id].onPeerDown(nb);
            }
        }
        if (fullMesh) return;
        int z = zoneOf(id);
        int aliveGateways = 0;
        for (int g : gateways.get(z)) if (infos[g].alive) aliveGateways++;
        if (aliveGateways < cfg.gatewayCount) scheduleElection(z);
    }

    private void scheduleElection(final int z) {
        if (electionPending[z]) return;
        electionPending[z] = true;
        clock.schedule(cfg.electionDelayMs, new Runnable() {
            @Override
            public void run() {
                electionPending[z] = false;
                runElection(z);
            }
        });
    }

    private void runElection(int z) {
        List<Integer> old = gateways.get(z);
        List<Integer> fresh = gm.reelect(members(z), old, cfg.gatewayCount);
        for (int g : old) {
            if (!fresh.contains(g)) {
                nodes[g].setGateway(false);
                removeBackboneLinks(g);
            }
        }
        for (int g : fresh) nodes[g].setGateway(true);
        gateways.set(z, fresh);
        rebuildBackboneFor(z);
    }

    // ---- delivery ---------------------------------------------------------------------------

    private void transmit(final int from, final int to, final byte[] data) {
        metrics.transmissions++;
        metrics.txByType[data[1] & 0x0F]++;
        if (!infos[from].alive || !infos[to].alive || !links[from].contains(to)) return;
        if (capTokens != null && !spendCapacity(from, data)) return;     // radio saturated: the packet is lost
        nodeTx[from]++;
        if (cfg.energyPerTx > 0) {
            drain(from, cfg.energyPerTx);
            if (!infos[from].alive) return;
        }
        if (cfg.txDrain > 0 && drain(from, cfg.txDrain)) return;             // sending costs energy even if the packet is lost
        if (cfg.lossRate > 0 && rng.nextDouble() < cfg.lossRate) return;
        if (cfg.badLinkRatio > 0 && isBadLink(from, to) && rng.nextDouble() < cfg.badLinkLoss) return;
        if (tracer != null) tracer.onTransmit(clock.nowMs(), from, to, data, false);
        long delay = (zoneOf(from) == zoneOf(to) ? cfg.intraMs : cfg.backboneMs) + rng.nextInt(cfg.jitterMs + 1);
        clock.schedule(delay, new Runnable() {
            @Override
            public void run() {
                if (infos[to].alive) {
                    if (cfg.energyPerRx > 0) {
                        drain(to, cfg.energyPerRx);
                        if (!infos[to].alive) return;
                    }
                    metrics.receptions++;
                    if (cfg.rxDrain > 0 && drain(to, cfg.rxDrain)) return;
                    if (cfg.adaptive) nodes[to].onLinkQuality(from, rssiOf(from, to));
                    nodes[to].onReceive(from, data);
                }
            }
        });
    }

    private boolean spendCapacity(int node, byte[] data) {
        long now = clock.nowMs();
        double tokens = Math.min(cfg.radioCapacityPerSec,
                capTokens[node] + (now - capStamp[node]) * cfg.radioCapacityPerSec / 1000.0);
        capStamp[node] = now;
        if (tokens < 1) {
            capTokens[node] = tokens;
            metrics.congestionDrops++;
            metrics.congestionDropsByClass[(data[3] & 0xFF) >= 100 ? 1 : 0]++;
            return false;
        }
        capTokens[node] = tokens - 1;
        return true;
    }

    /** Same answer every run: about badLinkRatio of all links are bad. */
    private boolean isBadLink(int a, int b) {
        long h = (Math.min(a, b) * 73856093L) ^ (Math.max(a, b) * 19349663L) ^ (cfg.seed * 83492791L);
        h ^= h >>> 17; h *= 0x9E3779B97F4A7C15L; h ^= h >>> 29;
        return ((h & 0xFFFFF) / (double) 0x100000) < cfg.badLinkRatio;
    }

    /** What a radio would report: strong on good links, weak on bad ones (with a little fixed spread per link). */
    private int rssiOf(int a, int b) {
        long h = (Math.min(a, b) * 2654435761L) ^ (Math.max(a, b) * 40503L);
        int spread = (int) (h & 7) - 4;
        return (isBadLink(a, b) ? -84 : -58) + spread;
    }

    /** @return true if the node ran out of battery just now (it dies and the packet is lost) */
    private boolean drain(int id, double amount) {
        if (!infos[id].alive) return true;
        infos[id].battery -= amount;
        if (infos[id].battery <= 0) {
            infos[id].battery = 0;
            metrics.depleted++;
            killNode(id);
            return true;
        }
        if (cfg.batteryAware) nodes[id].setBattery(infos[id].battery);   // a low node stops relaying
        return false;
    }

    /** Remaining battery: {average, minimum, count below 10 percent, count dead of depletion}. */
    public double[] batteryStats() {
        double sum = 0, min = 1;
        int low = 0, dead = 0;
        for (NodeInfo i : infos) {
            sum += i.battery;
            min = Math.min(min, i.battery);
            if (i.battery < 0.10) low++;
            if (i.battery <= 0) dead++;
        }
        return new double[]{sum / n, min, low, dead};
    }

    /**
     * A phone walks into another zone: it announces the new zone while still in range of its old neighbours, then
     * leaves them and links up with a few phones of the new zone. Needs discovery mode. Moving nodes are not used
     * as gateways or border nodes.
     */
    public void moveNode(final int id, final int newZone) {
        if (!discovery) throw new IllegalStateException("moveNode needs discovery on");
        if (!infos[id].alive || zoneArr[id] == newZone || newZone < 0 || newZone >= zoneCount) return;
        metrics.moves++;
        roaming[id] = true;
        infos[id].mobility = 3;
        nodes[id].setMobility(3.0);
        zoneArr[id] = newZone;
        infos[id].zone = newZone;
        nodes[id].setZone(newZone);                     // HELLO to the old neighbours, PUBLISH to the home zone
        clock.schedule(400, new Runnable() {
            @Override
            public void run() {
                if (!infos[id].alive) return;
                for (int nb : new ArrayList<>(links[id])) disconnect(id, nb);
                int want = 3;
                for (int tries = 0; tries < 40 && want > 0; tries++) {
                    int c = zoneStart(newZone) + rng.nextInt(zoneEnd(newZone) - zoneStart(newZone));
                    if (c != id && infos[c].alive && zoneArr[c] == newZone && !links[id].contains(c)) {
                        connect(id, c);
                        want--;
                    }
                }
            }
        });
    }

    private void serverSend(int from, final int dst, final byte[] data) {
        metrics.transmissions++;
        metrics.txByType[data[1] & 0x0F]++;
        if (tracer != null) tracer.onTransmit(clock.nowMs(), from, dst, data, true);
        clock.schedule(cfg.serverMs, new Runnable() {
            @Override
            public void run() {
                if (infos[dst].alive && infos[dst].online) {
                    metrics.receptions++;
                    nodes[dst].onReceive(MeshNode.SERVER_PEER, data);
                }
            }
        });
    }

    /** Send a message and track it in the metrics. */
    public long sendMessage(int src, int dst, byte[] payload, int priority) {
        long id = nodes[src].send(dst, payload, priority);
        sentAt.put(id, clock.nowMs());
        int cls = priority >= 100 ? 1 : 0;
        classOf.put(id, cls);
        metrics.sentByClass[cls]++;
        metrics.sent++;
        if (tracer != null) tracer.onSent(id, src, dst);
        return id;
    }

    public void setTracer(Tracer tracer) { this.tracer = tracer; }

    /** Packets this node has put on the air so far. */
    public int txOf(int node) { return nodeTx[node]; }

    /** Install the large-message layer on every node (small networks only). */
    public void enableChunking() {
        if (chunkLayers != null) return;
        chunkLayers = new ChunkLayer[n];
        for (int i = 0; i < n; i++) {
            final int me = i;
            chunkLayers[i] = new ChunkLayer(nodes[i], voiceLayers != null ? voiceLayers[i] : nodeHooks, meshCfg, clock, rng, new ChunkLayer.Callbacks() {
                @Override public void onComplete(int fromNode, long transferId, byte[] data) {
                    completedTransfers.put(transferId, data);
                    completedAt.put(transferId, me);
                    metrics.largeCompleted++;
                }
                @Override public void onProgress(long transferId, int done, int total) { }
                @Override public void onFailed(long transferId, String reason, int missing) {
                    failedTransfers.put(transferId, reason + ":" + missing);
                }
            });
        }
    }

    public ChunkLayer chunkLayer(int id) { return chunkLayers[id]; }

    /** What a node's voice layer reported, with the simulated time. kind: start, frame, lost, end, report. */
    public static final class VoiceEvent {
        public final long timeMs;
        public final int node;
        public final int from;
        public final int callId;
        public final String kind;
        public final byte[] data;
        public final int value;

        VoiceEvent(long timeMs, int node, int from, int callId, String kind, byte[] data, int value) {
            this.timeMs = timeMs;
            this.node = node;
            this.from = from;
            this.callId = callId;
            this.kind = kind;
            this.data = data;
            this.value = value;
        }
    }

    /** Install the live-voice layer on every node (small networks only). Call it before enableChunking(). */
    public void enableVoice() {
        if (voiceLayers != null) return;
        voiceLayers = new VoiceLayer[n];
        for (int i = 0; i < n; i++) {
            final int me = i;
            voiceLayers[i] = new VoiceLayer(nodes[i], chunkLayers != null ? chunkLayers[i] : nodeHooks, meshCfg, clock, rng,
                    new VoiceLayer.Callbacks() {
                        @Override public void onTalkStart(int fromNode, int callId) {
                            voiceEvents.add(new VoiceEvent(clock.nowMs(), me, fromNode, callId, "start", null, 0));
                        }
                        @Override public void onFrame(int fromNode, int callId, byte[] data) {
                            voiceEvents.add(new VoiceEvent(clock.nowMs(), me, fromNode, callId, data == null ? "lost" : "frame", data, 0));
                        }
                        @Override public void onTalkEnd(int fromNode, int callId) {
                            voiceEvents.add(new VoiceEvent(clock.nowMs(), me, fromNode, callId, "end", null, 0));
                        }
                        @Override public void onReport(int toNode, int callId, int lossPercent) {
                            voiceEvents.add(new VoiceEvent(clock.nowMs(), me, toNode, callId, "report", null, lossPercent));
                        }
                    });
        }
    }

    public VoiceLayer voiceLayer(int id) { return voiceLayers[id]; }
    public List<VoiceEvent> voiceEvents() { return voiceEvents; }

    public long sendLarge(int src, int dst, byte[] data, int priority) {
        enableChunking();
        metrics.largeStarted++;
        return chunkLayers[src].send(dst, data, priority);
    }

    public byte[] completedTransfer(long transferId) { return completedTransfers.get(transferId); }
    public Integer completedAtNode(long transferId) { return completedAt.get(transferId); }
    public String failedTransfer(long transferId) { return failedTransfers.get(transferId); }

    /** Fill in the end-of-run statistics (load spread, pacer queues, leaf nodes). */
    public void finalizeMetrics() {
        long total = 0;
        int max = 0;
        for (int x : nodeTx) {
            total += x;
            max = Math.max(max, x);
        }
        metrics.maxNodeTx = max;
        metrics.meanNodeTx = n == 0 ? 0 : (double) total / n;
        int[] sorted = nodeTx.clone();
        java.util.Arrays.sort(sorted);
        int top = Math.max(1, n / 100);
        long topSum = 0;
        for (int i = 0; i < top; i++) topSum += sorted[n - 1 - i];
        metrics.top1PctTxShare = total == 0 ? 0 : (double) topSum / total;
        long leaves = 0, queued = 0, dropped = 0, expired = 0;
        for (MeshNode node : nodes) {
            if (node.isLeaf()) leaves++;
            queued += node.pacer().wasQueued();
            dropped += node.pacer().dropped();
            expired += node.pacer().expired();
        }
        metrics.leafNodes = leaves;
        metrics.pacerQueued = queued;
        metrics.pacerDropped = dropped;
        metrics.pacerExpired = expired;
    }

    /** Every link once, as {a, b} with a < b. */
    public List<int[]> edges() {
        List<int[]> out = new ArrayList<>();
        for (int a = 0; a < n; a++) {
            for (int b : links[a]) if (a < b) out.add(new int[]{a, b});
        }
        return out;
    }

    public List<Integer> allGateways() {
        List<Integer> out = new ArrayList<>();
        if (fullMesh) {
            for (int z = 0; z < zoneCount; z++) out.addAll(gatewaysOf(z));
        } else {
            for (List<Integer> g : gateways) out.addAll(g);
        }
        return out;
    }

    public Set<Integer> linksOf(int id) { return new HashSet<>(links[id]); }
}
