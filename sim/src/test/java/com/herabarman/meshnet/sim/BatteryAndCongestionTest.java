package com.herabarman.meshnet.sim;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class BatteryAndCongestionTest {
    private static final byte[] PAYLOAD = {1, 2, 3};

    // ---- battery ------------------------------------------------------------------------------

    private static SimConfig battery(boolean aware) {
        SimConfig c = new SimConfig();
        c.nodes = 300;
        c.zoneSize = 15;
        c.messages = 1500;
        c.seed = 6;
        c.churn = 0;
        c.gatewayFail = 0;
        c.energyPerTx = 0.008;
        c.energyPerRx = 0.002;
        c.batteryAware = aware;
        return c;
    }

    @Test
    void batteryAwareRelayingKeepsMoreNodesAliveAndDeliversMore() {
        Metrics blind = Simulation.run(battery(false));
        Metrics aware = Simulation.run(battery(true));
        assertTrue(aware.depleted < blind.depleted, "depleted aware " + aware.depleted + " vs blind " + blind.depleted);
        assertTrue(aware.deliveryRate() > blind.deliveryRate(), "delivery aware " + aware.deliveryRate() + " vs blind " + blind.deliveryRate());
        assertTrue(aware.leafNodes > 0);
        assertEquals(0, blind.leafNodes);
    }

    @Test
    void aLowBatteryNodeSendsAndReceivesForItselfButDoesNotRelay() {
        SimConfig c = new SimConfig();
        c.nodes = 12;
        c.zoneSize = 12;          // a single zone: only flooding
        c.onlineRatio = 0.0;
        c.jitterMs = 0;
        c.seed = 2;
        c.ackEnabled = false;
        c.routeLearning = false;
        c.counterThreshold = 0;
        SimNetwork normal = new SimNetwork(c);
        int relay = -1;
        for (int i = 3; i < 12 && relay < 0; i++) if (normal.linksOf(i).size() >= 3) relay = i;
        assertTrue(relay >= 0);
        for (int k = 0; k < 6; k++) {
            int src = (relay + 1 + k) % 12 == relay ? 0 : (relay + 1 + k) % 12;
            int dst = (relay + 6 + k) % 12 == relay ? 1 : (relay + 6 + k) % 12;
            if (src != dst) normal.sendMessage(src, dst, PAYLOAD, 150);
            normal.clock.runFor(2_000);
        }
        assertTrue(normal.txOf(relay) > 0, "the node should relay when healthy");

        SimNetwork saving = new SimNetwork(c);
        saving.node(relay).setBattery(0.10);
        assertTrue(saving.node(relay).isLeaf());
        for (int k = 0; k < 6; k++) {
            int src = (relay + 1 + k) % 12 == relay ? 0 : (relay + 1 + k) % 12;
            int dst = (relay + 6 + k) % 12 == relay ? 1 : (relay + 6 + k) % 12;
            if (src != dst) saving.sendMessage(src, dst, PAYLOAD, 150);
            saving.clock.runFor(2_000);
        }
        assertEquals(0, saving.txOf(relay));                       // it forwarded nothing
        assertEquals(saving.metrics.sent, saving.metrics.delivered);   // and nobody lost a message because of it
        // as a destination it still receives
        long before = saving.metrics.delivered;
        saving.sendMessage(0 == relay ? 1 : 0, relay, PAYLOAD, 150);
        saving.clock.runFor(2_000);
        assertEquals(before + 1, saving.metrics.delivered);
    }

    @Test
    void leafStatusSpreadsToNeighboursByHelloAndRecoversWithHysteresis() {
        SimConfig c = new SimConfig();
        c.nodes = 60;
        c.zoneSize = 12;
        c.onlineRatio = 0.0;
        c.jitterMs = 0;
        c.discovery = "on";
        SimNetwork net = new SimNetwork(c);
        net.clock.runFor(5_000);
        int x = 5;
        net.node(x).setBattery(0.15);
        net.clock.runFor(2_000);
        for (int nb : net.linksOf(x)) assertTrue(net.node(nb).isLeafPeer(x), "neighbour " + nb + " was not told");
        net.node(x).setBattery(0.22);          // above the limit but inside the hysteresis band: still a leaf
        assertTrue(net.node(x).isLeaf());
        net.node(x).setBattery(0.30);          // clearly recovered
        assertFalse(net.node(x).isLeaf());
        net.clock.runFor(2_000);
        for (int nb : net.linksOf(x)) assertFalse(net.node(nb).isLeafPeer(x));
    }

    // ---- congestion ---------------------------------------------------------------------------

    private static SimConfig congestion(double pacing, int burst) {
        SimConfig c = new SimConfig();
        c.nodes = 400;
        c.zoneSize = 20;
        c.messages = 200;
        c.seed = 4;
        c.churn = 0;
        c.gatewayFail = 0;
        c.radioCapacityPerSec = 12;
        c.txRatePerSec = pacing;
        c.burstMessages = burst;
        c.burstWindowMs = 6_000;
        c.bulkRatio = 0.5;
        return c;
    }

    @Test
    void pacingProtectsInteractiveTrafficDuringABurst() {
        Metrics raw = Simulation.run(congestion(0, 1500));
        Metrics paced = Simulation.run(congestion(9, 1500));
        assertTrue(raw.classDeliveryRate(1) < 0.85, "the scenario must overload the radio: " + raw.classDeliveryRate(1));
        assertTrue(paced.classDeliveryRate(1) > 0.97, "interactive with pacing: " + paced.classDeliveryRate(1));
        assertTrue(paced.congestionDrops * 10 < raw.congestionDrops, "radio drops " + paced.congestionDrops + " vs " + raw.congestionDrops);
        assertTrue(paced.shed > 0, "bulk floods should have been shed");
        assertTrue(paced.classDeliveryRate(0) < paced.classDeliveryRate(1));   // bulk pays, text does not
    }

    @Test
    void pacingCostsNothingWhileTheRadioIsNotSaturated() {
        Metrics raw = Simulation.run(congestion(0, 0));
        Metrics paced = Simulation.run(congestion(9, 0));
        assertEquals(raw.sent, paced.sent);
        assertEquals(raw.delivered, paced.delivered);
        assertEquals(0, paced.pacerQueued);
        assertEquals(0, paced.shed);
        assertEquals(raw.avgLatencyMs(), paced.avgLatencyMs(), 1.0);
    }
}
