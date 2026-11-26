package com.herabarman.meshnet.sim;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class LargeTransferTest {
    private static SimConfig cfg() {
        SimConfig c = new SimConfig();
        c.nodes = 120;
        c.zoneSize = 12;       // 10 zones, full mesh
        c.onlineRatio = 0.0;
        c.jitterMs = 0;
        c.seed = 8;
        c.chunkBytes = 1024;
        return c;
    }

    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static int plainNode(SimNetwork net, int zone, int zoneSize) {
        for (int id = zone * zoneSize; id < (zone + 1) * zoneSize; id++) if (!net.node(id).isGateway()) return id;
        return zone * zoneSize;
    }

    @Test
    void aTenKilobyteMessageArrivesIntactAcrossZones() {
        SimConfig c = cfg();
        SimNetwork net = new SimNetwork(c);
        byte[] data = randomBytes(10_000, 1);
        int src = plainNode(net, 0, c.zoneSize), dst = plainNode(net, 6, c.zoneSize);
        long id = net.sendLarge(src, dst, data, 100);
        net.clock.runFor(60_000);
        assertArrayEquals(data, net.completedTransfer(id));
        assertEquals(dst, (int) net.completedAtNode(id));
        assertEquals(0, net.chunkLayer(src).activeTransfers());     // every chunk confirmed
        assertEquals(10, net.chunkLayer(src).chunksSent());
    }

    @Test
    void worksWithDiscoveryAndNoGlobalZoneMapToo() {
        SimConfig c = cfg();
        c.discovery = "on";
        SimNetwork net = new SimNetwork(c);
        net.clock.runFor(10_000);
        byte[] data = randomBytes(8_000, 2);
        int src = plainNode(net, 1, c.zoneSize), dst = plainNode(net, 8, c.zoneSize);
        long id = net.sendLarge(src, dst, data, 100);
        net.clock.runFor(90_000);
        assertArrayEquals(data, net.completedTransfer(id));
    }

    @Test
    void theWindowKeepsOnlyAFewChunksInFlight() {
        SimConfig c = cfg();
        SimNetwork net = new SimNetwork(c);
        int src = plainNode(net, 0, c.zoneSize), dst = plainNode(net, 5, c.zoneSize);
        net.sendLarge(src, dst, randomBytes(50_000, 3), 100);        // 49 chunks
        assertEquals(6, net.chunkLayer(src).chunksSent());           // chunkWindow, not 49 at once
        net.clock.runFor(200_000);
        assertEquals(49, net.chunkLayer(src).chunksSent());
    }

    @Test
    void lostPacketsAreRecoveredChunkByChunk() {
        SimConfig c = cfg();
        c.lossRate = 0.10;
        SimNetwork net = new SimNetwork(c);
        byte[] data = randomBytes(12_000, 4);
        int src = plainNode(net, 0, c.zoneSize), dst = plainNode(net, 4, c.zoneSize);
        long id = net.sendLarge(src, dst, data, 100);
        net.clock.runFor(180_000);
        assertArrayEquals(data, net.completedTransfer(id));
    }

    @Test
    void afterAFailureOnlyTheMissingChunksAreSentAgain() {
        SimConfig c = cfg();
        SimNetwork net = new SimNetwork(c);
        byte[] data = randomBytes(20_000, 5);                        // 20 chunks
        int src = plainNode(net, 0, c.zoneSize), dst = plainNode(net, 7, c.zoneSize);
        long id = net.sendLarge(src, dst, data, 100);
        int confirmed = 0;
        for (int step = 0; step < 100 && confirmed < 5; step++) {    // let a few chunks get confirmed ...
            net.clock.runFor(25);
            confirmed = 20 - net.chunkLayer(src).missingChunks(id);
        }
        assertTrue(confirmed >= 5 && confirmed < 20, "confirmed " + confirmed);
        net.cfg.lossRate = 1.0;                                      // ... then the radio goes dead
        net.clock.runFor(120_000);
        assertNotNull(net.failedTransfer(id));                       // the layer reported the failure
        int missing = net.chunkLayer(src).missingChunks(id);
        assertTrue(missing > 0 && missing < 20, "missing " + missing + " confirmed before " + confirmed);
        int sentBefore = net.chunkLayer(src).chunksSent();
        net.cfg.lossRate = 0.0;
        assertTrue(net.chunkLayer(src).retryMissing(id));
        net.clock.runFor(120_000);
        assertArrayEquals(data, net.completedTransfer(id));
        int resent = net.chunkLayer(src).chunksSent() - sentBefore;
        assertTrue(resent <= missing, "re-sent " + resent + " chunks but only " + missing + " were missing");
        assertTrue(resent < 20);
    }
}
