package com.herabarman.meshnet.core;

import static com.herabarman.meshnet.core.TestKit.check;
import static com.herabarman.meshnet.core.TestKit.info;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Beacons, link metrics, multipath routes, energy / congestion / mobility behaviour. Plain main(), no JUnit. */
public final class AdaptiveTests {
    public static void main(String[] args) {
        beaconAndNeighbor();
        routeTableAndSelector();
        loadMeter();
        diamondEnergy();
        diamondFailureFeedback();
        diamondMobilityRssi();
        forwardingPointer();
        relayPolicies();
        gatewayStability();
        defaultsAreUntouched();
        TestKit.summary();
    }

    // ------------------------------------------------------------------------------------------

    static void beaconAndNeighbor() {
        Beacon b = new Beacon(128, true, 2, true, 77, 65535);
        Beacon d = Beacon.decode(b.encode());
        check("beacon round trip", d != null && d.battery == 128 && d.charging && d.mobility == 2 && d.gateway && d.load == 77 && d.seq == 65535);
        check("beacon is 5 bytes", b.encode().length == 5);
        check("HELLO without payload (old node) decodes to null", Beacon.decode(new byte[0]) == null);
        check("mobility classes", Beacon.mobilityClass(0.1) == 0 && Beacon.mobilityClass(1.2) == 1 && Beacon.mobilityClass(5) == 2 && Beacon.mobilityClass(20) == 3);

        NeighborInfo n = new NeighborInfo(9);
        check("unknown neighbour is neither great nor terrible", n.receiveQuality() == 0.9 && n.etx() > 1.0 && n.etx() < 1.3);
        for (int s = 1; s <= 16; s++) n.onBeacon(new Beacon(200, false, 0, false, 0, s), s * 1000L);
        check("all beacons received -> quality 1.0", n.receiveQuality() > 0.99);
        NeighborInfo half = new NeighborInfo(8);
        for (int s = 1; s <= 32; s += 2) half.onBeacon(new Beacon(200, false, 0, false, 0, s), s * 1000L);
        check("every second beacon lost -> quality about 0.5 (" + String.format("%.2f", half.receiveQuality()) + ")", Math.abs(half.receiveQuality() - 0.5) < 0.1);
        check("lossy link costs about 2 transmissions per packet (" + String.format("%.2f", half.etx()) + ")", half.etx() > 1.7 && half.etx() < 2.6);
        NeighborInfo wrap = new NeighborInfo(7);
        for (int s = 65530; s < 65530 + 12; s++) wrap.onBeacon(new Beacon(200, false, 0, false, 0, s & 0xFFFF), s * 1000L);
        check("sequence number wrap-around keeps quality 1.0", wrap.receiveQuality() > 0.99);
        NeighborInfo late = new NeighborInfo(6);
        int[] order = {1, 2, 3, 5, 4, 6, 7, 8};
        for (int s : order) late.onBeacon(new Beacon(200, false, 0, false, 0, s), 1000L * s);
        check("a late (out of order) beacon still counts", late.receiveQuality() > 0.99);
        check("battery and load are taken from the newest beacon", Math.abs(n.battery - 200 / 255.0) < 1e-9);

        NeighborInfo r = new NeighborInfo(5);
        for (int i = 0; i <= 10; i++) r.onRssi(-60 - 2.0 * i, 1000L * i);   // fading 2 dBm per second
        long life = r.predictedLifetimeMs();
        check("fading link: predicted lifetime is finite and short (" + life + " ms)", life > 1000 && life < 15000);
        check("fading link counts as expiring within 15 s", r.expiring(15000));
        NeighborInfo steady = new NeighborInfo(4);
        for (int i = 0; i <= 10; i++) steady.onRssi(-60 + (i % 2), 1000L * i);
        check("steady link is not expiring", steady.predictedLifetimeMs() == Long.MAX_VALUE && !steady.expiring(60000));
        check("strong RSSI beats weak RSSI in etx", steady.etx() < r.etx());

        NeighborInfo f = new NeighborInfo(3);
        f.onFailure(1000); f.onFailure(2000);
        check("two failures in a row: recently bad, success rate drops", f.recentlyBad(3000, 30000) && f.successRate() < 0.7);
        f.onSuccess(120);
        check("a success clears the streak and records the round trip time", f.failStreak() == 0 && f.rttMs() == 120);
        NeighborInfo sil = new NeighborInfo(2);
        sil.onBeacon(new Beacon(200, false, 0, false, 0, 1), 1000);
        check("beacons stopped -> silent; never-beaconed neighbour is not silent", sil.silent(30000, 20000) && !new NeighborInfo(1).silent(30000, 20000) && !sil.silent(5000, 20000));
    }

    static void routeTableAndSelector() {
        MultiRouteTable t = new MultiRouteTable(8, 3);
        t.learn(100, 1, 2, 0, 10_000);
        t.learn(100, 2, 3, 0, 10_000);
        t.learn(100, 3, 4, 0, 10_000);
        check("up to maxPaths alternatives per destination", t.paths(100, 1).size() == 3);
        t.learn(100, 4, 9, 0, 10_000);
        check("a longer path does not push out better ones", t.paths(100, 1).size() == 3 && !hasHop(t.paths(100, 1), 4));
        t.learn(100, 5, 1, 0, 10_000);
        check("a shorter path replaces the longest", t.paths(100, 1).size() == 3 && hasHop(t.paths(100, 1), 5) && !hasHop(t.paths(100, 1), 3));
        t.removePath(100, 5);
        check("removePath drops only that first hop", t.paths(100, 1).size() == 2 && hasHop(t.paths(100, 1), 1));
        t.removeNextHop(1);
        check("removeNextHop drops a neighbour everywhere", !hasHop(t.paths(100, 1), 1));
        check("paths expire", t.paths(100, 20_000).isEmpty());

        MeshConfig cfg = new MeshConfig();
        cfg.energyAware = true;
        check("energy factor: healthy 1.0, charging below 1, weak well above 1",
                PathSelector.energyFactor(0.9, false, cfg) == 1.0 && PathSelector.energyFactor(0.1, true, cfg) < 1.0
                        && PathSelector.energyFactor(0.2, false, cfg) > 3.0);
        check("energy factor grows as the battery falls", PathSelector.energyFactor(0.4, false, cfg) < PathSelector.energyFactor(0.2, false, cfg)
                && PathSelector.energyFactor(0.2, false, cfg) < PathSelector.energyFactor(0.05, false, cfg));

        final NeighborInfo weak = new NeighborInfo(1);
        weak.onBeacon(new Beacon(30, false, 0, false, 0, 1), 0);
        final NeighborInfo strong = new NeighborInfo(2);
        strong.onBeacon(new Beacon(230, false, 0, false, 0, 1), 0);
        PathSelector.Lookup lookup = new PathSelector.Lookup() {
            @Override public NeighborInfo get(int peer) { return peer == 1 ? weak : strong; }
        };
        List<MultiRouteTable.Path> two = new ArrayList<>();
        MultiRouteTable tt = new MultiRouteTable(4, 3);
        tt.learn(1, 1, 2, 0, 1000);
        tt.learn(1, 2, 2, 0, 1000);
        two = tt.paths(1, 1);
        Random rng = new Random(1);
        int pickedWeak = 0;
        for (int i = 0; i < 200; i++) if (PathSelector.choose(two, lookup, cfg, 1, rng) == 1) pickedWeak++;
        check("energy-aware selector avoids the weak relay (picked " + pickedWeak + "/200)", pickedWeak == 0);
        cfg.energyAware = false;
        pickedWeak = 0;
        for (int i = 0; i < 400; i++) if (PathSelector.choose(two, lookup, cfg, 1, rng) == 1) pickedWeak++;
        check("without energy awareness equal paths share the load (" + pickedWeak + "/400)", pickedWeak > 120 && pickedWeak < 280);
        check("selector on an empty list returns -1", PathSelector.choose(new ArrayList<MultiRouteTable.Path>(), lookup, cfg, 1, rng) == -1);
    }

    static boolean hasHop(List<MultiRouteTable.Path> l, int hop) {
        for (MultiRouteTable.Path p : l) if (p.nextHop == hop) return true;
        return false;
    }

    static void loadMeter() {
        LoadMeter m = new LoadMeter(10, 1000);
        for (int s = 0; s < 6; s++) for (int i = 0; i < 20; i++) m.onTransmit(s * 1000L + i * 40);
        double busy = m.load(6000);
        check("20 packets/s on a 10/s radio shows as overloaded (" + String.format("%.2f", busy) + ")", busy > 1.0);
        double idle = m.load(60_000);
        check("load decays when the radio is quiet", idle < 0.05);
        LoadMeter light = new LoadMeter(40, 1000);
        for (int s = 0; s < 6; s++) for (int i = 0; i < 4; i++) light.onTransmit(s * 1000L + i * 200);
        check("4 packets/s on a 40/s radio is light load", light.load(6000) < 0.2);
    }

    // ---- MeshNode level: S - {A, B} - D ----------------------------------------------------------

    static TestKit.Net diamond(MeshConfig cfg, long seed, double batA, double batB) {
        TestKit.Net net = new TestKit.Net(cfg, seed);
        net.add(1, 0, 1.0);          // S
        net.add(2, 0, batA);         // A
        net.add(3, 0, batB);         // B
        net.add(4, 0, 1.0);          // D
        net.link(1, 2); net.link(1, 3); net.link(2, 4); net.link(3, 4);
        return net;
    }

    static MeshConfig adaptiveCfg(boolean energy) {
        MeshConfig c = new MeshConfig();
        c.adaptiveRouting = true;
        c.energyAware = energy;
        c.beaconMs = 5000;
        return c;
    }

    /** Fraction of relayed DATA that went through A, S sending 150 messages to D after both paths were learned. */
    static double shareViaA(MeshConfig cfg, long seed, double batA, double batB) {
        TestKit.Net net = diamond(cfg, seed, batA, batB);
        net.infos.get(2).battery = batA;
        net.infos.get(3).battery = batB;
        net.clock.run(12_000);                                          // beacons: S learns the batteries
        net.nodes.get(4).send(1, new byte[]{1}, 1);                     // D -> S flood teaches S two paths to D
        net.clock.run(2_000);
        for (int i = 0; i < 150; i++) {
            net.nodes.get(1).send(4, new byte[]{2}, 1);
            net.clock.run(200);
        }
        net.clock.run(5_000);
        int viaA = net.dataTx(2), viaB = net.dataTx(3);
        if (net.delivered(4) < 150) info("only " + net.delivered(4) + " of 150 delivered (seed " + seed + ")");
        return viaA + viaB == 0 ? -1 : viaA / (double) (viaA + viaB);
    }

    static void diamondEnergy() {
        MeshNode probe = diamond(adaptiveCfg(true), 1, 0.15, 0.9).nodes.get(1);
        probe.send(4, new byte[]{1}, 1);
        double classicSum = 0, noEnergySum = 0, energyMax = 0;
        int seeds = 8;
        for (long seed = 1; seed <= seeds; seed++) {
            MeshConfig classic = new MeshConfig();
            classicSum += shareViaA(classic, seed, 0.15, 0.9);
            noEnergySum += shareViaA(adaptiveCfg(false), seed, 0.15, 0.9);
            energyMax = Math.max(energyMax, shareViaA(adaptiveCfg(true), seed, 0.15, 0.9));
        }
        info(String.format("share of relayed traffic through the 15%%-battery relay: classic (avg of %d seeds) %.0f%%, adaptive without energy %.0f%%, adaptive + energy (worst seed) %.0f%%",
                seeds, 100 * classicSum / seeds, 100 * noEnergySum / seeds, 100 * energyMax));
        check("energy-aware routing keeps the weak relay almost out of the path (worst seed " + String.format("%.0f", 100 * energyMax) + "%)", energyMax < 0.05);
        check("without energy awareness the weak relay is not protected", noEnergySum / seeds > 0.25);
        // the same with the roles swapped: the strong relay takes the traffic whichever side it is on
        double swapped = shareViaA(adaptiveCfg(true), 3, 0.9, 0.15);
        check("roles swapped: now B is the weak one and A takes the traffic (A share " + String.format("%.0f", 100 * swapped) + "%)", swapped > 0.95);
    }

    static void diamondFailureFeedback() {
        long[] result = new long[4];
        for (int variant = 0; variant < 2; variant++) {
            MeshConfig cfg = variant == 0 ? new MeshConfig() : adaptiveCfg(false);
            TestKit.Net net = diamond(cfg, 5, 0.9, 0.9);
            net.clock.run(12_000);
            net.nodes.get(4).send(1, new byte[]{1}, 1);
            net.clock.run(2_000);
            net.breakSilently(1, 2);                                    // S-A dies without anybody being told
            net.breakSilently(2, 4);
            int before = net.delivered(4);
            for (int i = 0; i < 60; i++) {
                net.nodes.get(1).send(4, new byte[]{3}, 1);
                net.clock.run(1_000);
            }
            net.clock.run(20_000);
            int got = net.delivered(4) - before;
            result[variant * 2] = got;
            result[variant * 2 + 1] = net.retries;
            if (variant == 1) {
                NeighborInfo a = net.nodes.get(1).neighborInfo(2);
                check("the failing neighbour is remembered as unreliable (success rate " + String.format("%.2f", a.successRate()) + ")", a.successRate() < 0.7 || a.silent(net.clock.nowMs(), 20_000));
            }
        }
        info("silent break of one path, 60 messages: classic delivered " + result[0] + " with " + result[1] + " retries; adaptive delivered " + result[2] + " with " + result[3] + " retries");
        check("adaptive routing still delivers everything after a silent path failure", result[2] >= 59);
        check("adaptive routing needs about as many retries as classic (" + result[3] + " vs " + result[1] + ")", result[3] <= result[1] + 2);
    }

    static void diamondMobilityRssi() {
        double[] shares = new double[2];
        for (int variant = 0; variant < 2; variant++) {
            MeshConfig cfg = adaptiveCfg(false);
            cfg.mobilityAware = variant == 1;
            cfg.linkExpiryHorizonMs = 30_000;
            TestKit.Net net = diamond(cfg, 11, 0.9, 0.9);
            net.clock.run(12_000);
            net.nodes.get(4).send(1, new byte[]{1}, 1);
            net.clock.run(2_000);
            MeshNode s = net.nodes.get(1);
            for (int i = 0; i <= 9; i++) {                               // A fades 1.5 dBm per second (still a usable level), B stays strong
                s.onLinkQuality(2, (int) (-52 - 1.5 * i));
                s.onLinkQuality(3, -52);
                net.clock.run(1000);
            }
            NeighborInfo na = s.neighborInfo(2);
            info(String.format("A: rssi %.1f dBm, slope %.2f dBm/s, predicted lifetime %d ms", na.rssiDbm(), na.rssiSlopeDbmPerSec(), na.predictedLifetimeMs()));
            int a0 = net.dataTx(2), b0 = net.dataTx(3);
            for (int i = 0; i < 60; i++) {
                s.send(4, new byte[]{4}, 1);
                net.clock.run(30);
            }
            net.clock.run(3_000);
            int a = net.dataTx(2) - a0, b = net.dataTx(3) - b0;
            shares[variant] = a / (double) Math.max(1, a + b);
        }
        info(String.format("traffic through the fading neighbour: %.0f%% without mobility awareness, %.0f%% with", 100 * shares[0], 100 * shares[1]));
        check("a fading link is avoided BEFORE it breaks when mobility awareness is on", shares[1] < 0.05 && shares[0] > shares[1] + 0.1);
    }

    static void forwardingPointer() {
        int[] delivered = new int[2], retries = new int[2];
        for (int variant = 0; variant < 2; variant++) {
            MeshConfig cfg = new MeshConfig();
            cfg.discovery = true;
            cfg.fullMesh = true;
            cfg.zoneCount = 3;
            cfg.mobilityAware = variant == 1;
            TestKit.Net net = new TestKit.Net(cfg, 21);
            MeshNode s = net.add(1, 0, 1.0), n = net.add(2, 1, 1.0), m = net.add(3, 2, 1.0), x = net.add(4, 1, 1.0);
            net.link(1, 2); net.link(1, 3); net.link(2, 3); net.link(2, 4);
            net.clock.run(5_000);
            s.hintLocation(4, 1, 600_000);                                // S knows X lives in zone 1
            x.setZone(2);                                                 // X crosses into zone 2 and says so ...
            net.clock.run(500);
            net.unlink(2, 4);                                             // ... then leaves N's radio range
            net.link(4, 3);
            net.clock.run(2_000);
            s.send(4, new byte[]{9}, 1);
            net.clock.run(2_500);                                         // shorter than the ACK timeout: first attempt only
            delivered[variant] = net.delivered(4);
            retries[variant] = net.retries;
        }
        info("message to a node that just moved zone, first attempt: without pointer delivered=" + delivered[0] + ", with pointer delivered=" + delivered[1]);
        check("without a forwarding pointer the stale zone is a dead end", delivered[0] == 0);
        check("with a forwarding pointer the old zone's node redirects it, no retry needed", delivered[1] == 1 && retries[1] == 0);
    }

    static void relayPolicies() {
        // chain S - R - D, R is the only way
        MeshConfig cfg = new MeshConfig();
        cfg.energyAware = true;
        TestKit.Net net = new TestKit.Net(cfg, 31);
        MeshNode s = net.add(1, 0, 1.0); MeshNode r = net.add(2, 0, 0.05); net.add(3, 0, 1.0);
        net.link(1, 2); net.link(2, 3);
        s.send(3, new byte[]{1}, 1);
        net.clock.run(20_000);
        check("a nearly empty relay refuses to carry other people's data", net.delivered(3) == 0 && net.dropReasons.containsKey("low-battery"));
        net.infos.get(2).charging = true;
        s.send(3, new byte[]{1}, 1);
        net.clock.run(20_000);
        check("the same relay on the charger carries it", net.delivered(3) >= 1);
        net.infos.get(2).charging = false;
        net.infos.get(2).battery = 0.05;
        int dropsBefore = net.dropReasons.get("low-battery");
        r.sendControl(Packet.TYPE_DHT, 3, 0, new byte[]{1, 2, 3}, 250);   // urgent traffic from the relay itself is its own business
        net.clock.run(1000);
        check("a nearly empty node still sends its own packets", net.dropReasons.get("low-battery") == dropsBefore);

        // congestion: a relay with a tiny capacity sheds low priority traffic, never high priority
        for (int variant = 0; variant < 2; variant++) {
            MeshConfig c2 = new MeshConfig();
            c2.congestionControl = true;
            c2.loadCapacityPerSec = 5;
            c2.ackEnabled = false;
            TestKit.Net n2 = new TestKit.Net(c2, 41);
            MeshNode s2 = n2.add(1, 0, 1.0); n2.add(2, 0, 1.0); n2.add(3, 0, 1.0);
            n2.link(1, 2); n2.link(2, 3);
            for (int i = 0; i < 300; i++) {
                s2.send(3, new byte[]{5}, variant == 0 ? 0 : 100);
                n2.clock.run(25);
            }
            n2.clock.run(5_000);
            int dropped = n2.dropReasons.containsKey("congestion") ? n2.dropReasons.get("congestion") : 0;
            if (variant == 0) check("an overloaded relay sheds low-priority packets (" + dropped + " of 300 dropped)", dropped > 20 && n2.delivered(3) < 300);
            else check("... but never packets of priority 100 (SOS)", dropped == 0 && n2.delivered(3) == 300);
        }
    }

    static void gatewayStability() {
        NodeInfo still = new NodeInfo(1, 0, 0.7, 0.7, true);
        NodeInfo mover = new NodeInfo(2, 0, 0.8, 0.8, true);
        mover.mobility = 3;
        List<NodeInfo> both = Arrays.asList(still, mover);
        check("default election ignores movement (unchanged behaviour)", new GatewayManager().elect(both, 1).get(0) == 2);
        check("with a stability weight the node that stays put wins", new GatewayManager(0.4, 0.3, 0.3, 0.0, 0.4).elect(both, 1).get(0) == 1);
        NodeInfo plugged = new NodeInfo(3, 0, 0.1, 0.7, true);
        plugged.charging = true;
        check("a phone on the charger counts as full battery", new GatewayManager().score(plugged) > new GatewayManager().score(new NodeInfo(4, 0, 0.5, 0.7, true)));
    }

    static void defaultsAreUntouched() {
        MeshConfig c = new MeshConfig();
        check("every new feature is off by default", !c.adaptiveRouting && !c.energyAware && !c.congestionControl && !c.mobilityAware && c.beaconMs == 0);
        TestKit.Net net = new TestKit.Net(new MeshConfig(), 1);
        net.add(1, 0, 1.0); net.add(2, 0, 1.0);
        net.link(1, 2);
        net.clock.run(60_000);
        check("no beacons are sent unless asked for", net.txAll.get(1) == null || net.txAll.get(1) == 0);
    }
}
