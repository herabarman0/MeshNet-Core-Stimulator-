package com.herabarman.meshnet.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResilienceAndAiTest {
    @Test
    void predictiveAiRouterLearnsRhythmTrajectoryAndPrecomputes() {
        PredictiveAiRouter ai = new PredictiveAiRouter();
        long hour9 = 9L * 3_600_000L;
        long hour22 = 22L * 3_600_000L;
        // Peer 202 regularly seen in hour 9 moving Zone 1 -> Zone 3
        for (int i = 0; i < 6; i++) {
            ai.observeNode(202, 1, hour9 + i * 60_000L);
            ai.observeNode(202, 3, hour9 + (i + 1) * 60_000L);
        }
        ai.observeNode(202, 1, hour9 + 400_000L);
        assertEquals(3, ai.predictNextZone(202));
        assertTrue(ai.onlineLikelihood(202, hour9 + 420_000L) > ai.onlineLikelihood(202, hour22));

        // Relay 105 is in predicted zone 3, healthy & online
        ai.observeNode(105, 3, hour9 + 410_000L);
        ai.observeNode(106, 8, hour22);

        Map<Integer, NeighborInfo> infoMap = new HashMap<>();
        NeighborInfo n105 = new NeighborInfo(105);
        n105.battery = 0.90; n105.lastBeaconMs = hour9;
        NeighborInfo n106 = new NeighborInfo(106);
        n106.battery = 0.15; n106.lastBeaconMs = hour9;
        infoMap.put(105, n105);
        infoMap.put(106, n106);

        RelayFairnessLedger fairness = new RelayFairnessLedger();
        fairness.recordRelayForPeer(105, 2048);

        PredictiveAiRouter.RouteDecision decision = ai.precomputeBestRoute(
                202, Arrays.asList(105, 106), infoMap, fairness, hour9 + 420_000L);
        assertNotNull(decision);
        assertEquals(105, decision.nextHop);
        assertSame(decision, ai.getPrecomputedRoute(202));
    }

    @Test
    void energyAwarePathSelectorSparesLowBatteryRelay() {
        MeshConfig cfg = new MeshConfig();
        cfg.lowBattery = 0.20;
        cfg.energyAware = true;
        cfg.energyHealthy = 0.50;
        cfg.energyWeight = 4.0;

        MultiRouteTable.Path lowPath = new MultiRouteTable.Path(11, 2, 1000L, 60_000L);
        MultiRouteTable.Path healthyPath = new MultiRouteTable.Path(12, 2, 1000L, 60_000L);
        List<MultiRouteTable.Path> paths = Arrays.asList(lowPath, healthyPath);

        final NeighborInfo low = new NeighborInfo(11);
        low.battery = 0.08; low.charging = false; low.lastBeaconMs = 1000L;
        final NeighborInfo healthy = new NeighborInfo(12);
        healthy.battery = 0.85; healthy.charging = true; healthy.lastBeaconMs = 1000L;

        PathSelector.PathDecision decision = PathSelector.chooseSmart(
                paths,
                new PathSelector.Lookup() {
                    @Override public NeighborInfo get(int peer) {
                        return peer == 11 ? low : healthy;
                    }
                },
                cfg,
                new PredictiveAiRouter(),
                new RelayFairnessLedger(),
                99,
                1000L);
        assertEquals(12, decision.nextHop);
        assertTrue(decision.avoidedLowBattery);
    }

    @Test
    void trafficMaskerTlsAndSteganographyRoundTrip() {
        byte[] packet = new Packet(Packet.TYPE_DATA, 16, Priority.URGENT, 555L, 1, 2,
                "Emergency Bypass".getBytes(StandardCharsets.UTF_8)).encode();
        byte[] psk = Keccak.sha3_256("mask-key".getBytes(StandardCharsets.UTF_8));

        byte[] tls = TrafficMasker.maskAsHttpsTls(packet, psk);
        assertEquals(0x17, tls[0] & 0xFF);
        assertEquals(0x03, tls[1] & 0xFF);
        assertEquals(0x03, tls[2] & 0xFF);
        assertArrayEquals(packet, TrafficMasker.unmaskHttpsTls(tls, psk));

        byte[] audioCarrier = new byte[1024];
        for (int i = 0; i < audioCarrier.length; i++) audioCarrier[i] = (byte) (i * 7);
        byte[] stegoAudio = TrafficMasker.embedLsbStego(packet, audioCarrier);
        assertArrayEquals(packet, TrafficMasker.extractLsbStego(stegoAudio));
    }

    @Test
    void relayFairnessRewardsHelpfulNodesAndThrottlesLeeches() {
        RelayFairnessLedger ledger = new RelayFairnessLedger();
        for (int i = 0; i < 12; i++) ledger.recordRelayForPeer(10, 1024);
        for (int i = 0; i < 12; i++) ledger.recordOwnTraffic(20, 2048);

        assertTrue(ledger.fairnessScore(10) > ledger.fairnessScore(20));
        assertTrue(ledger.effectivePriority(10, Priority.NORMAL) > Priority.NORMAL);
        assertEquals(Priority.BULK, ledger.effectivePriority(20, Priority.NORMAL));
    }
}
