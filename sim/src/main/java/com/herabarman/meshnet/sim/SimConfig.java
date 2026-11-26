package com.herabarman.meshnet.sim;

import com.herabarman.meshnet.core.DeliveryMode;

/** All simulation parameters. */
public final class SimConfig implements Cloneable {
    public int nodes = 10_000;
    public int zoneSize = 15;
    public int gatewayCount = 2;
    public int messages = 1_000;
    /** Cap on backbone links per zone (0 = log-scale default). */
    public int maxFanOut = 0;

    public double onlineRatio = 0.2;
    /** Fraction of nodes that go down every churn interval. */
    public double churn = 0.01;
    /** Fraction of gateways killed at the midpoint of the run. */
    public double gatewayFail = 0.05;
    /** Fraction of messages sent inside the sender's own zone. */
    public double localRatio = 0.3;
    public double lossRate = 0.0;

    public long seed = 1;
    public long durationMs = 60_000;
    public long warmupMs = 2_000;
    public long drainMs = 60_000;
    public long churnIntervalMs = 5_000;
    public long electionDelayMs = 2_000;

    public int intraMs = 25;
    public int backboneMs = 60;
    public int serverMs = 80;
    public int jitterMs = 10;

    public boolean encrypt = false;

    // flood optimisation, see MeshConfig
    public int floodJitterMs = 40;
    public int counterThreshold = 3;
    public int floodFanout = 0;

    // route learning + ACK, see MeshConfig
    public boolean routeLearning = true;
    public boolean ackEnabled = true;
    public long ackTimeoutMs = 3_000;
    public int maxRetries = 2;

    /**
     * "full": every zone has direct offline links to every other zone. "chord": ring plus shortcut links
     * (scales to millions of nodes). "auto": full when there are at most fullMeshMaxZones zones, else chord.
     */
    public String backbone = "auto";
    /** Offline links between each pair of zones (spread over different border nodes), full mode only. */
    public int linksPerPair = 2;
    public int fullMeshMaxZones = 64;
    /** OFFLINE_ONLY: no server. OFFLINE_FIRST: the server only helps. ONLINE_FIRST: online senders use the server first. */
    public DeliveryMode mode = DeliveryMode.OFFLINE_FIRST;

    /**
     * "on": nodes learn zones with HELLO, packet headers and the location service, and get no global zone map at all.
     * "off": nodes are told every node's zone (needed to keep 100,000+ node runs fast). "auto": on up to 5,000 nodes.
     */
    public String discovery = "off";
    /** Nodes of the home zone that keep a published location (0 = all of them). */
    public int publishReplicas = 3;

    public boolean discoveryOn() {
        return "on".equals(discovery) || ("auto".equals(discovery) && nodes <= 5_000);
    }

    // ---- congestion, battery, bursts ----
    /** Packets per second a node's radio can really send; more are lost (buffer overflow, collisions). 0 = unlimited. */
    public double radioCapacityPerSec = 0;
    /** Engine-side pacing budget in packets per second (MeshConfig.txRatePerSec). 0 = off. */
    public double txRatePerSec = 0;
    public int txBurst = 20;
    /** Battery drained per packet sent / received (fraction of a full battery). 0 = battery does not drain. */
    public double energyPerTx = 0;
    public double energyPerRx = 0;
    /** Nodes below 20% battery stop relaying and are not elected gateway / border node. */
    public boolean batteryAware = false;
    /** Extra messages sent within burstWindowMs, a share of them bulk (priority 20), the rest interactive (200). */
    public int burstMessages = 0;
    public long burstWindowMs = 10_000;
    public double bulkRatio = 0.5;
    public int chunkBytes = 1024;
    /** Live voice: send each packet's predecessor along with it when the receiver reports loss (MeshConfig.voiceAutoRedundancy). */
    public boolean voiceAutoRedundancy = true;
    /** Guaranteed share of the radio for bulk packets while congested (MeshConfig.bulkMinShare). */
    public double bulkMinShare = 0.1;

    // ---- adaptive features (all off by default; see MeshConfig) ----
    public boolean adaptive = false;
    public boolean energyAware = false;
    public boolean congestion = false;
    public boolean mobilityAware = false;
    public long beaconMs = 0;
    /** Fraction of links that are bad (lossy), chosen by a hash of the two node ids so it is the same every run. */
    public double badLinkRatio = 0;
    public double badLinkLoss = 0.5;
    /** Battery (0..1) a node loses per packet it sends / receives. 0 = batteries never drain. A node at 0 dies. */
    public double txDrain = 0;
    public double rxDrain = 0;
    /** Fraction of nodes that walk into another zone every moveIntervalMs (needs discovery on). */
    public double moveRatio = 0;
    public long moveIntervalMs = 10_000;
    /** Capacity used for load (packets per second) when congestion control is on. */
    public double loadCapacity = 40;

    /** Messages per conversation (a pair that talks back and forth). 1 = every message is a new pair. */
    public int conversationLength = 6;

    public SimConfig copy() {
        try {
            return (SimConfig) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }
}
