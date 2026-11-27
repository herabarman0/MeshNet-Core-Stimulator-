package com.herabarman.meshnet.core;

/** Tunables for one node. */
public final class MeshConfig {
    public int defaultTtl = 32;
    public long dedupTtlMs = 120_000;
    public int dedupMaxEntries = 512;
    public int storeMaxPackets = 128;
    public long storeTtlMs = 300_000;
    public long retryMs = 5_000;
    /** Number of zones in the whole network (backbone ring size). */
    public int zoneCount = 1;

    // ---- flood optimisation (inside a zone) ----
    /**
     * Relayed packets wait a random 0..floodJitterMs before being forwarded, so the node can learn
     * which neighbours already have the packet and skip them. 0 = classic immediate flooding.
     * Set it above the typical link latency (about 1.5x) or the delay learns nothing.
     */
    public int floodJitterMs = 40;
    /**
     * If this many distinct neighbours already sent me the packet while I waited, I do not
     * forward it at all. 0 = off. Gateways never suppress.
     */
    public int counterThreshold = 3;
    /** Forward to at most this many neighbours (random pick). 0 = all eligible. Gateways ignore it. */
    public int floodFanout = 0;

    // ---- reverse-path route learning + end-to-end ACK ----
    /**
     * Nodes remember "to reach X, send to peer P" from the packets they relay (P = the neighbour the
     * first copy came from). Later packets for X go as unicast instead of being flooded.
     */
    public boolean routeLearning = true;
    public long routeTtlMs = 120_000;
    public int routeCacheMax = 64;
    /**
     * Destination sends a tiny ACK back. No ACK after ackTimeoutMs (doubling each time) means the
     * sender forgets the learned route and re-sends by flooding, up to maxRetries times.
     */
    public boolean ackEnabled = true;
    public long ackTimeoutMs = 3_000;
    public int maxRetries = 2;

    // ---- zone-to-zone mesh and the role of the server ----
    /**
     * True when every zone has direct offline links to every other zone (spread over several border
     * nodes). A missing direct link to the destination zone then means "ask another node of my zone".
     * False = ring with shortcut links, forwarded greedily by gateways.
     */
    public boolean fullMesh = false;
    /** Who carries what: the mesh, the server, or both. See {@link DeliveryMode}. */
    public DeliveryMode deliveryMode = DeliveryMode.OFFLINE_FIRST;

    // ---- discovery: nodes learn who and where everybody is, without any global knowledge ----
    /**
     * true: no node needs a global "node -> zone" function. Neighbours greet each other (HELLO), packet headers
     * carry both zones, and a distributed location service answers "which zone is node X in?".
     * false: the ZoneMap passed to the node is trusted (simulation at huge scale, or fixed zone numbering).
     */
    public boolean discovery = false;
    /** How long a passively learned location (from a packet header or a HELLO) stays valid. */
    public long locationPassiveTtlMs = 600_000;
    /** How long a location published to its home zone stays valid. */
    public long locationTtlMs = 7_200_000;
    /** A node re-publishes its location this often (should be well below locationTtlMs). */
    public long publishEveryMs = 1_800_000;
    /** First publish happens after publishDelayMs + random(publishJitterMs), so HELLOs have finished. */
    /**
     * A published location is kept by this many nodes of the home zone (a short random walk), not by all of them.
     * Publishing then costs a few transmissions; a lookup that misses floods the home zone until a keeper answers.
     * 0 = flood the whole home zone on every publish.
     */
    public int publishReplicas = 3;
    public long publishDelayMs = 500;
    public long publishJitterMs = 1_500;
    /** Wait this long for a lookup reply (doubling each retry) ... */
    public long lookupTimeoutMs = 1_500;
    /** ... and ask this many more times before giving up with "unknown-destination". */
    public int lookupRetries = 2;
    /** Messages waiting for one lookup. */
    public int awaitingMax = 32;
    /** Re-send HELLO to neighbours whose zone is still unknown this often. */
    public long helloRetryMs = 3_000;
    public int directoryMaxEntries = 4096;

    // ---- congestion control (Pacer) ----
    /**
     * Radio budget in packets per second (token bucket). 0 = unlimited, no pacing. Over budget, packets wait in a
     * priority queue: text before images, ACKs and control before everything. A phone link usually carries a few tens.
     */
    public double txRatePerSec = 0;
    /** Packets that may go out back to back. */
    public int txBurst = 20;
    public int txQueueMax = 64;
    /** Packets with priority below this are bulk (images, status): they may only use tokens above the reserve. */
    public int bulkPriorityBelow = 100;
    /** Fraction of the burst that is always kept free for non-bulk packets. */
    public double bulkReserve = 0.3;
    /** A queued packet older than this is dropped (the sender's ACK retry takes over). */
    public long txMaxWaitMs = 20_000;
    /** Under congestion the flood jitter grows up to this many times (AIMD), and bulk floods are shed. */
    public double maxJitterFactor = 4.0;
    /**
     * While the Pacer reports congestion, relays flood more gently: stay silent after hearing the packet from this
     * many neighbours, and forward to at most this many. (1/1 breaks delivery, 2/2 is the measured sweet spot.)
     */
    public int congestedCounter = 2;
    public int congestedFanout = 2;

    // ---- battery ----
    /** Below this level a node becomes a leaf: it sends and receives for itself but no longer relays others' floods. */
    public double lowBattery = 0.20;
    /** It relays again once the level is lowBattery + this (no flapping around the limit). */
    public double batteryHysteresis = 0.05;

    // ---- large messages (ChunkLayer) ----
    /** Bytes of user data per chunk. Choose it to fit the link: BLE a few hundred, Wi-Fi Direct several KB. */
    public int chunkBytes = 1024;
    /** Unconfirmed chunks in flight per transfer. */
    public int chunkWindow = 6;
    public int maxTransferBytes = 16 * 1024 * 1024;
    /** Transfers being reassembled at once, and the memory they may hold. */
    public int maxAssemblies = 16;
    public long maxReassemblyBytes = 8L * 1024 * 1024;
    /** An incomplete transfer with no new chunk for this long is dropped. */
    public long reassemblyTimeoutMs = 120_000;

    // ---- priority levels (see Priority) ----
    /** Packets with at least this priority are emergencies: own lane, flooded everywhere, never shed. */
    public int sosPriorityFrom = Priority.SOS;
    /** An SOS may spend this many tokens beyond an empty bucket (it never waits for the radio budget). */
    public int sosBurst = 10;
    /** SOS lives longer in the network than normal packets. */
    public int sosTtl = 64;
    /** SOS retries come this many times faster (ACK timeout divided by it) and this many more times. */
    public int sosRetryFactor = 3;
    public int sosExtraRetries = 3;
    /** SOS is kept in the store for this long when no route exists (normal: storeTtlMs). */
    public long sosStoreTtlMs = 3_600_000;
    /**
     * Anyone can write SOS into a packet, so a relay trusts it only so far: from one sender it passes this many SOS
     * packets a minute at SOS level; the rest are handled as URGENT (still delivered, but with no emergency lane).
     * A person pressing the alarm button sends a handful, so the limit does not touch real use.
     */
    public int sosPerSourcePerMin = 30;
    /** While congested, at least this share of the radio goes to bulk packets, so files and sync never starve (0 = strict priority). */
    public double bulkMinShare = 0.1;

    // ---- real-time voice (VoiceLayer) ----
    /** Voice packets live for at most this many hops: audio older than a few hundred ms is useless anyway. */
    public int voiceTtl = 8;
    /** Above text and control, just below ACK/HELLO (255): a voice frame leaves the radio queue before anything else. */
    public int voicePriority = 250;
    /** A voice packet that waits longer than this in the radio queue is dropped, not sent late. */
    public long voiceMaxWaitMs = 200;
    /** Voice packets get their own small duplicate filter so a call never pushes text ids out of the main one. */
    public int voiceDedupMax = 256;
    public long voiceDedupTtlMs = 4_000;
    /** Receiver sends a small report this often (it also teaches the relays the way back, so frames stop being flooded). */
    public long voiceReportMs = 1_000;
    /** Playout delay window of the jitter buffer (adapts between the two). */
    public int voiceMinDelayMs = 80;
    public int voiceMaxDelayMs = 600;
    /** A talk spurt with no packet for this long is treated as ended (the end marker may have been lost). */
    public long voiceSpurtTimeoutMs = 1_000;
    /**
     * Redundancy: every packet also carries a copy of the previous one, so a single lost packet is recovered from the
     * next (loss p becomes about p squared) at twice the bandwidth. With auto on, the sender switches it on when the
     * receiver's report shows 5% loss or more and off again at 1% or less.
     */
    public boolean voiceAutoRedundancy = true;
    public int voiceRedundancyOnPercent = 5;
    public int voiceRedundancyOffPercent = 1;
    // ---- adaptive routing: several paths, chosen by measured link quality (all off by default) ----
    /**
     * Keep up to maxPathsPerDest alternative first hops per destination (later copies of a flood become alternatives,
     * like AOMDV) and pick among them by cost: hops, link quality (beacon loss + RSSI), what really happened to my
     * earlier messages through that neighbour, its load, its mobility. A path that fails is dropped alone, the others stay.
     */
    public boolean adaptiveRouting = false;
    public int maxPathsPerDest = 3;
    /** Paths costing at most this factor more than the best are all used, at random (spreads load and battery drain). */
    public double pathTolerance = 1.15;
    /** Weight of a neighbour's advertised load (0..1) in the path cost. */
    public double loadWeight = 1.0;
    /** Weight of a neighbour's mobility class (0..3) in the path cost. */
    public double mobilityWeight = 0.5;
    /**
     * Send a HELLO beacon (battery, load, mobility, sequence number) to every neighbour this often. 0 = no beacons.
     * Beacons are what makes loss measurement and energy-aware routing possible. 10 000 - 30 000 is reasonable.
     */
    public long beaconMs = 0;

    // ---- energy awareness (off by default) ----
    /**
     * Prefer neighbours with more battery, relay less when my own battery is low, and refuse to relay other people's
     * traffic when it is nearly empty. Needs beacons (beaconMs > 0) to know the neighbours' batteries.
     */
    public boolean energyAware = false;
    /** At or above this battery level (0..1) a node is "healthy" and has no extra cost. */
    public double energyHealthy = 0.5;
    /** Cost multiplier at 0 % battery is 1 + energyWeight; it falls off quadratically up to energyHealthy. */
    public double energyWeight = 8.0;
    /** Below this battery (and not charging) the node stops relaying others' packets, except ACKs and priority >= 200. */
    public double energyRelayCutoff = 0.08;

    // ---- congestion control (off by default) ----
    public boolean congestionControl = false;
    /** Transmissions per second this radio can carry; load = rate / capacity. */
    public double loadCapacityPerSec = 40;
    /** Above this load, relayed low-priority (priority < 64) packets are dropped with growing probability. */
    public double shedStartLoad = 0.8;

    // ---- mobility (off by default) ----
    /**
     * Treat movement as normal: learned routes live shorter when I or the neighbour move, a link whose RSSI trend says
     * it will break soon is avoided before it breaks, and when a neighbour announces a new zone I keep a short-lived
     * forwarding pointer so packets still addressed to its old zone are redirected.
     */
    public boolean mobilityAware = false;
    /** An RSSI trend that predicts a break within this time makes the link expensive. */
    public long linkExpiryHorizonMs = 8_000;
    /** How long a forwarding pointer ("X moved from zone A to zone B") lives. */
    public long pointerTtlMs = 60_000;

    // ---- advanced engine pipeline (opt-in flags so legacy protocols stay wire-compatible) ----
    /** Consult PredictiveAiRouter when choosing relay paths and update it on ACK/timeout outcomes. */
    public boolean predictiveAiRouting = false;
    /** Compress payloads with LZ4 (compress-then-encrypt) before encryption when payload >= 16 bytes. */
    public boolean compressPayloads = false;
    /** Enforce Hashcash PoW on HELLO beacons + trust-weighted per-peer flood rate limiting via SybilGuard. */
    public boolean sybilGuardEnabled = false;
    public int sybilPowBits = 6;
    public double sybilBurst = 24.0;
    public double sybilRefillPerSec = 8.0;
    /** Coalesce small outbound frames into multi-frame 5-lane priority bursts via TurboScheduler. */
    public boolean turboCoalescing = false;
    public int turboMtuBytes = 4096;
    public long turboFlushMs = 5;
    /** Wrap wire bytes in TLS 1.3 Application Data mimicry via TrafficMasker (defeats DPI). */
    public boolean trafficMasking = false;
    public byte[] trafficMaskKey = null;
    /** Require signed directory records in GossipDirectory / DHT when a RecordGuard is present. */
    public boolean requireSignedGossip = false;

    /** Convenience preset enabling the full resilience, AI routing, compression, and anti-Sybil stack. */
    public static MeshConfig worldClassDefaults() {
        MeshConfig c = new MeshConfig();
        c.discovery = true;
        c.adaptiveRouting = true;
        c.energyAware = true;
        c.congestionControl = true;
        c.mobilityAware = true;
        c.predictiveAiRouting = true;
        c.compressPayloads = true;
        c.sybilGuardEnabled = true;
        c.turboCoalescing = true;
        c.requireSignedGossip = true;
        c.beaconMs = 15_000;
        return c;
    }
}
