package com.herabarman.meshnet.kit;

/** AdaptiveDuty, SpeedTracker, Presets. Plain main(), run by tools/build-and-test.sh. */
public final class AdaptiveKitTests {
    static int passed, failed;

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("PASS  " + what); } else { failed++; System.out.println("FAIL  " + what); }
    }

    public static void main(String[] args) {
        duty();
        speed();
        presets();
        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void duty() {
        long t = 1_000_000;
        AdaptiveDuty d = new AdaptiveDuty();
        check("charging: scan all the time", d.next(0.5, true, 0, false, 0, t).offMs == 0);
        check("healthy battery, no history: scan all the time (finding neighbours matters first)", d.next(0.9, false, 0, false, 0, t).offMs == 0);

        long prev = 0;
        boolean grows = true;
        long[] offs = new long[8];
        for (int i = 0; i < 8; i++) {
            d.onRoundEnd(0);
            offs[i] = d.next(0.9, false, 0, false, 0, t).offMs;
            if (offs[i] < prev) grows = false;
            prev = offs[i];
        }
        check("empty neighbourhood: the rest doubles every empty round (" + offs[0] / 1000 + "s, " + offs[1] / 1000 + "s, " + offs[2] / 1000 + "s ...)", grows && offs[0] == 15_000 && offs[1] == 30_000 && offs[2] == 60_000);
        check("... and is capped at 5 minutes", offs[7] == 300_000);

        d.onPeerUp();
        check("a neighbour appears: back to continuous scanning", d.next(0.9, false, 1, false, 0, t).offMs == 0 && d.emptyRounds() == 0);

        for (int i = 0; i < 5; i++) d.onRoundEnd(0);
        long rest = d.next(0.9, false, 0, false, 0, t).offMs;
        d.onTraffic(t);
        check("a message to send wakes the radio at once (rest was " + rest / 1000 + "s)", rest > 0 && d.next(0.9, false, 0, false, 0, t + 1000).offMs == 0);
        d.onRoundEnd(0);
        check("... and once it is quiet again, one empty round starts the back-off from the short rest (" + d.next(0.9, false, 0, false, 0, t + 60_000).offMs / 1000 + "s)", d.next(0.9, false, 0, false, 0, t + 60_000).offMs == 15_000);

        AdaptiveDuty b = new AdaptiveDuty();
        long low = b.next(0.25, false, 5, false, 0, t).offMs;
        long crit = b.next(0.10, false, 5, false, 0, t).offMs;
        long ok = b.next(0.80, false, 5, false, 0, t).offMs;
        check("lower battery, longer rest (" + ok / 1000 + "s, " + low / 1000 + "s, " + crit / 1000 + "s)", ok < low && low < crit);
        b.onTraffic(t);
        check("critical battery does not scan continuously even when busy", b.next(0.10, false, 5, false, 0, t + 1000).offMs > 0);
        AdaptiveDuty s = new AdaptiveDuty();
        check("battery saver rests more", s.next(0.8, false, 0, true, 0, t).offMs > s.next(0.8, false, 0, false, 0, t).offMs);
        AdaptiveDuty m = new AdaptiveDuty();
        for (int i = 0; i < 3; i++) m.onRoundEnd(0);
        long still = m.next(0.8, false, 0, false, 0, t).offMs;
        long moving = m.next(0.8, false, 0, false, 1.5, t).offMs;
        check("a moving phone rests less than a still one (" + moving / 1000 + "s vs " + still / 1000 + "s)", moving < still);
        check("well connected phone rests (neighbours find it)", new AdaptiveDuty().next(0.8, false, 4, false, 0, t).offMs == 30_000);
    }

    static void speed() {
        SpeedTracker s = new SpeedTracker();
        s.update(23.8103, 90.4125, 0);
        double v = 0;
        for (int i = 1; i <= 12; i++) v = s.update(23.8103 + i * 0.00009, 90.4125, i * 10_000L);   // 10 m every 10 s = 1 m/s
        check("walking pace is measured (" + String.format("%.2f", v) + " m/s)", v > 0.7 && v < 1.3);
        SpeedTracker still = new SpeedTracker();
        still.update(23.8103, 90.4125, 0);
        double w = 9;
        for (int i = 1; i <= 12; i++) w = still.update(23.8103 + (i % 2) * 0.00001, 90.4125, i * 10_000L);   // 1 m of GPS jitter
        check("GPS jitter is not movement (" + String.format("%.2f", w) + " m/s)", w < 0.3);
        SpeedTracker jump = new SpeedTracker();
        jump.update(23.8, 90.4, 0);
        double j = jump.update(40.0, 100.0, 5_000);
        check("an impossible jump is ignored", j < 1.0);
        check("distance sanity: 0.01 deg of latitude is about 1.1 km", Math.abs(SpeedTracker.distanceM(23.0, 90.0, 23.01, 90.0) - 1112) < 15);
    }

    static void presets() {
        com.herabarman.meshnet.core.MeshConfig c = new com.herabarman.meshnet.core.MeshConfig();
        Presets.mobileAdaptive().tune(c);
        check("mobile preset turns the features on", c.beaconMs == 15_000 && c.adaptiveRouting && c.energyAware && c.congestionControl && c.mobilityAware);
    }
}
