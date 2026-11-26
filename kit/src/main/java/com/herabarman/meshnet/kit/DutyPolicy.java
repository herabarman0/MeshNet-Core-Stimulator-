package com.herabarman.meshnet.kit;

/**
 * How long the radio scans and how long it rests, from battery and surroundings. Scanning is the expensive
 * part of a mesh radio; a phone that already has neighbours or is nearly empty does not need to scan all the time.
 * offMs == 0 means "always scanning".
 */
public final class DutyPolicy {
    public static final class Duty {
        public final long onMs;
        public final long offMs;
        Duty(long on, long off) { onMs = on; offMs = off; }
        @Override public String toString() { return onMs + "ms on / " + offMs + "ms off"; }
    }

    private DutyPolicy() {}

    /**
     * @param battery  0..1
     * @param charging plugged in
     * @param peers    neighbours connected right now
     * @param saver    the user's battery-saver switch
     */
    public static Duty decide(double battery, boolean charging, int peers, boolean saver) {
        if (charging) return new Duty(30_000, 0);                               // plugged in: never rest
        if (battery <= 0.15) return new Duty(10_000, 110_000);                  // nearly empty: a 10 s look every two minutes
        if (battery <= 0.30) return new Duty(20_000, 60_000);
        if (saver) return new Duty(30_000, 30_000);
        if (peers >= 3) return new Duty(15_000, 45_000);                        // well connected: neighbours find me anyway
        return new Duty(30_000, 0);
    }
}
