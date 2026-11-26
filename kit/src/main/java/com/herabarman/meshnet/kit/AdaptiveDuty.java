package com.herabarman.meshnet.kit;

/**
 * Adaptive duty cycling for the scanning radio: scanning for Bluetooth / Wi-Fi peers is what drains a phone, so the
 * time spent resting (offMs) grows when scanning is not paying off and shrinks when it is.
 *
 *   rest = base(battery) , doubled for every scan round that found nobody (an empty neighbourhood), up to a cap,
 *          halved while the phone is moving (new neighbours are likely), x2 in battery-saver mode,
 *          and none at all while there is traffic to send or a charger is plugged in.
 *
 * Starts again from the short rest the moment a neighbour shows up, a message is waiting, or the phone starts to move,
 * so power saving costs at most one rest period of discovery delay, and never delays a message that is waiting to be sent.
 * Pure arithmetic, no Android calls: NearbyRadio feeds it and acts on the answer. Engine thread only.
 */
public final class AdaptiveDuty {
    public static final class Config {
        public long onMs = 15_000;
        /** Rest after one empty round when the battery is healthy. */
        public long emptyBaseMs = 15_000;
        public long maxOffMs = 300_000;
        /** Fixed rests by battery tier (applied even when neighbours exist, the floor of the policy). */
        public long lowBatteryOffMs = 60_000;      // <= 30 %
        public long criticalOffMs = 120_000;       // <= 15 %
        /** With this many neighbours already, a rest of denseOffMs is enough: they find me anyway. */
        public int denseNeighbours = 3;
        public long denseOffMs = 30_000;
        /** Traffic within this window counts as "busy": scan continuously. */
        public long busyWindowMs = 20_000;
        /** At or above this speed (m/s) the neighbourhood changes quickly. */
        public double movingMps = 1.0;
    }

    private final Config cfg;
    private int emptyRounds;
    private long lastTrafficMs = Long.MIN_VALUE / 2;

    public AdaptiveDuty() { this(new Config()); }

    public AdaptiveDuty(Config cfg) { this.cfg = cfg; }

    /** A packet was sent or received (anything that means the phone is in use). */
    public synchronized void onTraffic(long nowMs) {
        lastTrafficMs = nowMs;
        emptyRounds = 0;
    }

    /** A scan round just ended: how many neighbours are connected now? */
    public synchronized void onRoundEnd(int peers) {
        if (peers > 0) emptyRounds = 0;
        else if (emptyRounds < 16) emptyRounds++;
    }

    /** A new neighbour connected. */
    public synchronized void onPeerUp() { emptyRounds = 0; }

    public synchronized int emptyRounds() { return emptyRounds; }

    /**
     * @param battery  0..1
     * @param charging plugged in
     * @param peers    neighbours connected right now
     * @param saver    the user's battery-saver switch
     * @param speedMps current speed, 0 if unknown
     */
    public synchronized DutyPolicy.Duty next(double battery, boolean charging, int peers, boolean saver, double speedMps, long nowMs) {
        boolean moving = speedMps >= cfg.movingMps;
        if (charging) return new DutyPolicy.Duty(cfg.onMs, 0);
        boolean busy = nowMs - lastTrafficMs < cfg.busyWindowMs;
        if (busy && battery > 0.15) return new DutyPolicy.Duty(cfg.onMs, 0);
        long off;
        if (battery <= 0.15) off = cfg.criticalOffMs;
        else if (battery <= 0.30) off = cfg.lowBatteryOffMs;
        else if (peers >= cfg.denseNeighbours) off = cfg.denseOffMs;
        else off = 0;
        if (peers == 0 && emptyRounds > 0) {                      // nobody around: back off exponentially
            long backoff = cfg.emptyBaseMs << Math.min(emptyRounds - 1, 10);
            off = Math.max(off, Math.min(cfg.maxOffMs, backoff));
        }
        if (saver) off = off == 0 ? cfg.emptyBaseMs : off * 2;
        if (moving && battery > 0.15) off /= 2;
        off = Math.min(off, cfg.maxOffMs);
        return new DutyPolicy.Duty(cfg.onMs, off);
    }
}
