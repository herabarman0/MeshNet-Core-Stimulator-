package com.herabarman.meshnet.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

public class WorldClassEngineTest {

    @Test
    public void onionRoutingProtectsMetadataAcrossThreeHops() {
        byte[][] relay1 = KeyExchange.generateRawKeyPair();
        byte[][] relay2 = KeyExchange.generateRawKeyPair();
        byte[][] dst = KeyExchange.generateRawKeyPair();

        byte[] secretMsg = "Confidential MeshNet E2EE SOS Payload".getBytes(StandardCharsets.UTF_8);
        byte[] onion = OnionRouter.wrap(
                new int[]{102, 103, 202},
                new byte[][]{relay1[1], relay2[1], dst[1]},
                secretMsg
        );

        // Relay 102 only learns nextHop=103, cannot unwrap inner layers
        OnionRouter.PeelResult hop1 = OnionRouter.peel(relay1[0], onion);
        assertNotNull(hop1);
        assertFalse(hop1.isFinal);
        assertEquals(103, hop1.nextHop);
        assertNull(OnionRouter.peel(relay1[0], hop1.payloadOrInner)); // blind to inner hop!

        // Relay 103 only learns nextHop=202
        OnionRouter.PeelResult hop2 = OnionRouter.peel(relay2[0], hop1.payloadOrInner);
        assertNotNull(hop2);
        assertFalse(hop2.isFinal);
        assertEquals(202, hop2.nextHop);

        // Final destination 202 peels final layer
        OnionRouter.PeelResult hop3 = OnionRouter.peel(dst[0], hop2.payloadOrInner);
        assertNotNull(hop3);
        assertTrue(hop3.isFinal);
        assertArrayEquals(secretMsg, hop3.payloadOrInner);
    }

    @Test
    public void sybilGuardBlocksBadPowAndSpamFlood() {
        SybilGuard guard = new SybilGuard(8, 3.0, 1.0);
        byte[] payload = "hello-mesh".getBytes(StandardCharsets.UTF_8);

        // Bad PoW rejected
        SybilGuard.AdmissionResult bad = guard.admitPacket(999, payload, 123L, 0.2, 10_000L);
        assertFalse(bad.allowed);
        assertFalse(bad.validPow);

        // Valid PoW admitted up to burst, then rate-limited
        long validNonce = SybilGuard.mintPow(101, payload, 8);
        assertTrue(SybilGuard.verifyPow(101, payload, validNonce, 8));
        for (int i = 0; i < 3; i++) {
            assertTrue(guard.admitPacket(101, payload, validNonce, 0.9, 10_000L).allowed);
        }
        SybilGuard.AdmissionResult flooded = guard.admitPacket(101, payload, validNonce, 0.9, 10_000L);
        assertFalse(flooded.allowed);
        assertTrue(flooded.validPow);
    }

    @Test
    public void payloadCompressorShrinksStructuredDataAndPassesRandomRaw() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("{\"node\":101,\"zone\":4,\"status\":\"OK\",\"battery\":88}\n");
        }
        byte[] rawJson = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] packed = PayloadCompressor.compress(rawJson);
        assertTrue(packed.length * 3 < rawJson.length,
                "Expected >3x compression, got " + packed.length + " vs " + rawJson.length);
        assertArrayEquals(rawJson, PayloadCompressor.decompress(packed));

        byte[] tiny = "hi".getBytes(StandardCharsets.UTF_8);
        byte[] packedTiny = PayloadCompressor.compress(tiny);
        assertArrayEquals(tiny, PayloadCompressor.decompress(packedTiny));
    }

    @Test
    public void adaptiveBatmanAomdvPicksStrongestLowestLossRoute() {
        long now = 50_000L;
        MeshConfig cfg = new MeshConfig();
        NeighborInfo weakNoisy = new NeighborInfo(104);
        weakNoisy.onRssi(-87.0, now);
        weakNoisy.onFailure(now - 500);
        weakNoisy.onFailure(now - 200);
        weakNoisy.onSuccess(420);

        NeighborInfo strongClean = new NeighborInfo(105);
        strongClean.onRssi(-56.0, now);
        strongClean.onSuccess(18);
        strongClean.onSuccess(20);

        List<MultiRouteTable.Path> paths = List.of(
                new MultiRouteTable.Path(104, 1, now, now + 60_000L),
                new MultiRouteTable.Path(105, 2, now, now + 60_000L)
        );
        PathSelector.AdaptiveLinkReport rep = PathSelector.chooseAdaptiveBatmanAomdv(
                paths,
                id -> id == 104 ? weakNoisy : strongClean,
                cfg,
                now
        );
        assertEquals(105, rep.nextHop);
        assertTrue(rep.batmanTq > 0.7);
    }
}
