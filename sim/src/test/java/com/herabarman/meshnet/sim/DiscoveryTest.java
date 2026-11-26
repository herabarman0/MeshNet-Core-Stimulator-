package com.herabarman.meshnet.sim;

import static org.junit.jupiter.api.Assertions.*;

import com.herabarman.meshnet.core.LocationDirectory;
import com.herabarman.meshnet.core.Packet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DiscoveryTest {
    private static final byte[] PAYLOAD = {1, 2, 3};

    /** 10 zones x 12 nodes, full zone mesh, no internet, and NO global zone map handed to any node. */
    private static SimConfig cfg() {
        SimConfig c = new SimConfig();
        c.nodes = 120;
        c.zoneSize = 12;
        c.onlineRatio = 0.0;
        c.jitterMs = 0;
        c.seed = 4;
        c.discovery = "on";
        return c;
    }

    private static SimNetwork warm(SimConfig c) {
        SimNetwork net = new SimNetwork(c);
        net.clock.runFor(10_000);          // HELLOs done, every node has published where it lives
        return net;
    }

    private static int plainNode(SimNetwork net, int zone, int zoneSize) {
        for (int id = zone * zoneSize; id < (zone + 1) * zoneSize; id++) {
            if (!net.node(id).isGateway()) return id;
        }
        return zone * zoneSize;
    }

    @Test
    void everyNodeLearnsItsNeighboursZonesFromHelloAlone() {
        SimNetwork net = warm(cfg());
        assertTrue(net.isDiscovery());
        for (int id = 0; id < net.n; id++) {
            int expectedBackbone = 0;
            int expectedZone = 0;
            for (int nb : net.linksOf(id)) {
                if (net.zoneOf(nb) == net.zoneOf(id)) expectedZone++;
                else expectedBackbone++;
            }
            assertEquals(expectedZone, net.node(id).zonePeerCount(), "zone peers of " + id);
            assertEquals(expectedBackbone, net.node(id).backbonePeerCount(), "backbone peers of " + id);
        }
    }

    @Test
    void messagesCrossZonesWithoutAnyNodeHavingAGlobalZoneMap() {
        SimConfig c = cfg();
        SimNetwork net = warm(c);          // a node touching the (null) zone map would throw here
        for (int z = 1; z < net.zoneCount; z++) {
            net.sendMessage(plainNode(net, 0, c.zoneSize), plainNode(net, z, c.zoneSize), PAYLOAD, 1);
            net.clock.runFor(10_000);
        }
        assertEquals(net.zoneCount - 1, net.metrics.delivered);
        assertEquals(0, net.metrics.viaServer);
    }

    @Test
    void publishedLocationsAreKeptByAFewNodesOfTheHomeZone() {
        SimConfig c = cfg();
        SimNetwork net = warm(c);
        int checked = 0, wellKept = 0;
        for (int target = 0; target < net.n; target += 7) {
            int home = LocationDirectory.homeZone(target, net.zoneCount);
            if (home == net.zoneOf(target)) continue;     // its own neighbours know it anyway
            int keepers = 0;
            for (int id = home * c.zoneSize; id < (home + 1) * c.zoneSize; id++) {
                if (net.node(id).knownZoneOf(target) == net.zoneOf(target)) keepers++;
            }
            assertTrue(keepers >= 1, "nobody in home zone " + home + " knows node " + target);
            // several border nodes can each start a copy walk, so a handful, but never the whole zone
            assertTrue(keepers <= c.zoneSize * 2 / 3, "too many copies: " + keepers);
            if (keepers >= 2) wellKept++;
            checked++;
        }
        assertTrue(checked > 8);
        assertTrue(wellKept * 10 >= checked * 7, "only " + wellKept + " of " + checked + " have 2+ copies");
    }

    @Test
    void publishCanStillFloodTheWholeHomeZone() {
        SimConfig c = cfg();
        c.publishReplicas = 0;
        SimNetwork net = warm(c);
        int target = 13;
        int home = LocationDirectory.homeZone(target, net.zoneCount);
        if (home == net.zoneOf(target)) target = 14;
        home = LocationDirectory.homeZone(target, net.zoneCount);
        int keepers = 0;
        for (int id = home * c.zoneSize; id < (home + 1) * c.zoneSize; id++) {
            if (net.node(id).knownZoneOf(target) == net.zoneOf(target)) keepers++;
        }
        assertTrue(keepers >= c.zoneSize * 3 / 4, "only " + keepers + " keepers");
    }

    /** A destination in another zone whose location the source has never heard of. */
    private static int[] strangers(SimNetwork net, int zoneSize) {
        int src = plainNode(net, 0, zoneSize);
        for (int z = 5; z < net.zoneCount; z++) {
            int dst = plainNode(net, z, zoneSize);
            if (net.node(src).knownZoneOf(dst) < 0 && LocationDirectory.homeZone(dst, net.zoneCount) != 0) {
                return new int[]{src, dst};
            }
        }
        throw new IllegalStateException("no stranger found");
    }

    @Test
    void firstMessageToAStrangerTriggersALookupThenArrives() {
        SimConfig c = cfg();
        SimNetwork net = warm(c);
        int[] pair = strangers(net, c.zoneSize);
        assertEquals(-1, net.node(pair[0]).knownZoneOf(pair[1]));
        long lookupsBefore = net.metrics.txByType[Packet.TYPE_LOOKUP];
        net.sendMessage(pair[0], pair[1], PAYLOAD, 1);
        net.clock.runFor(10_000);
        assertEquals(1, net.metrics.delivered);
        assertTrue(net.metrics.txByType[Packet.TYPE_LOOKUP] > lookupsBefore, "no lookup was sent");
        assertTrue(net.metrics.txByType[Packet.TYPE_LOOKUP_REPLY] > 0, "no lookup reply");
        assertEquals(net.zoneOf(pair[1]), net.node(pair[0]).knownZoneOf(pair[1]));   // now it knows
    }

    @Test
    void theNextMessageAndTheReplyNeedNoLookupAtAll() {
        SimConfig c = cfg();
        SimNetwork net = warm(c);
        int[] pair = strangers(net, c.zoneSize);
        net.sendMessage(pair[0], pair[1], PAYLOAD, 1);
        net.clock.runFor(10_000);
        long lookups = net.metrics.txByType[Packet.TYPE_LOOKUP];
        net.sendMessage(pair[0], pair[1], PAYLOAD, 1);     // location cached
        net.sendMessage(pair[1], pair[0], PAYLOAD, 1);     // the receiver learned the sender's zone from the header
        net.clock.runFor(10_000);
        assertEquals(3, net.metrics.delivered);
        assertEquals(lookups, net.metrics.txByType[Packet.TYPE_LOOKUP]);
    }

    @Test
    void aNodeNobodyKnowsFailsCleanlyInsteadOfHanging() {
        SimConfig c = cfg();
        SimNetwork probe = warm(c.copy());
        int src = plainNode(probe, 0, c.zoneSize);
        int dst = -1;
        for (int z = 5; z < probe.zoneCount && dst < 0; z++) {
            int cand = plainNode(probe, z, c.zoneSize);
            int home = LocationDirectory.homeZone(cand, probe.zoneCount);
            // its home zone is neither its own zone (whose neighbours heard its HELLO) nor the sender's zone
            if (home != z && home != 0 && probe.node(src).knownZoneOf(cand) < 0) dst = cand;
        }
        assertTrue(dst >= 0);
        SimNetwork net = new SimNetwork(c);
        net.killNode(dst);                                   // dies before it can publish
        net.clock.runFor(10_000);
        assertEquals(-1, net.node(src).knownZoneOf(dst));
        net.sendMessage(src, dst, PAYLOAD, 1);
        net.clock.runFor(60_000);
        assertEquals(0, net.metrics.delivered);
        assertTrue(net.metrics.dropped >= 1, "the sender was never told (unknown-destination)");
        assertTrue(net.metrics.txByType[Packet.TYPE_LOOKUP] >= 3, "the lookup should have been retried");
    }

    @Test
    void aDeadDestinationWhoseLocationIsKnownEndsAsNoAck() {
        SimConfig c = cfg();
        SimNetwork net = warm(c);
        int src = plainNode(net, 0, c.zoneSize);
        int dst = plainNode(net, 6, c.zoneSize);
        net.killNode(dst);                                   // died after publishing: its location is still known
        net.sendMessage(src, dst, PAYLOAD, 1);
        net.clock.runFor(60_000);
        assertEquals(0, net.metrics.delivered);
        assertTrue(net.metrics.unacked >= 1, "the sender was never told (no-ack)");
    }

    @Test
    void discoveryTrafficIsAModestShareOfAWarmUp() {
        SimConfig c = cfg();
        SimNetwork net = warm(c);
        long perNode = net.metrics.controlTransmissions() / net.n;
        assertTrue(perNode < 150, "control transmissions per node: " + perNode);
        assertTrue(net.metrics.txByType[Packet.TYPE_HELLO] > 0);
        assertTrue(net.metrics.txByType[Packet.TYPE_PUBLISH] > 0);
    }

    @Test
    void ringBackboneWorksWithDiscoveryToo() {
        SimConfig c = new SimConfig();
        c.nodes = 600;
        c.zoneSize = 12;           // 50 zones
        c.backbone = "chord";
        c.onlineRatio = 0.0;
        c.jitterMs = 0;
        c.discovery = "on";
        SimNetwork net = warm(c);
        for (int z = 10; z < 50; z += 8) {
            net.sendMessage(plainNode(net, 0, c.zoneSize), plainNode(net, z, c.zoneSize), PAYLOAD, 1);
            net.clock.runFor(15_000);
        }
        assertEquals(5, net.metrics.delivered);
    }
}
