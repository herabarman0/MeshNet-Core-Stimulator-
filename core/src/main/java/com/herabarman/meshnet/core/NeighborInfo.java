package com.herabarman.meshnet.core;

/**
 * Everything one node knows about one direct neighbour, from three independent sources:
 *  1. the radio (RSSI samples, and from them the trend, so a link that is fading can be predicted to break),
 *  2. the neighbour's own beacons (battery, load, mobility, and a sequence number whose gaps measure the link's
 *     loss rate, like B.A.T.M.A.N.'s transmit quality),
 *  3. what happened to my own messages that went through it (ACK = success + round-trip time, timeout = failure).
 * Plain data + small arithmetic, no I/O. Engine thread only.
 */
public final class NeighborInfo {
    /** RSSI at or above this is a perfect link, at or below {@link #RSSI_FLOOR} the link is about to drop. */
    public static final double RSSI_GOOD = -55.0;
    public static final double RSSI_FLOOR = -90.0;
    private static final int WINDOW = 16;

    public final int id;

    // ---- advertised state (from beacons) ----
    public double battery = 1.0;
    public boolean charging;
    public int mobility;
    public boolean gateway;
    public double load;
    public long lastBeaconMs = -1;
    private boolean beaconSeen;

    // ---- beacon sequence window ----
    private int lastSeq;
    private int firstSeq;
    private int seen;
    private int window;           // bit i set: beacon (lastSeq - i) arrived

    // ---- RSSI ----
    private double rssi = Double.NaN;       // fast average
    private double rssiSlow;
    private double avgDt = 1.0;
    private double slope;         // dBm per second, negative = fading
    private long rssiAtMs = -1;

    // ---- my own experience (end to end through this neighbour) ----
    private double success = 0.9;
    private double rttMs = Double.NaN;
    private int failStreak;
    private long lastFailMs = -1;

    public NeighborInfo(int id) {
        this.id = id;
    }

    // ---- updates ----

    public void onBeacon(Beacon b, long nowMs) {
        battery = b.batteryFraction();
        charging = b.charging;
        mobility = b.mobility;
        gateway = b.gateway;
        load = b.loadFraction();
        lastBeaconMs = nowMs;
        if (!beaconSeen) {
            beaconSeen = true;
            firstSeq = b.seq;
            lastSeq = b.seq;
            window = 1;
            seen = 1;
            return;
        }
        int diff = (b.seq - lastSeq) & 0xFFFF;
        if (diff == 0) return;                                   // duplicate
        if (diff < 0x8000) {                                     // newer
            window = diff >= WINDOW ? 1 : ((window << diff) | 1);
            window &= (1 << WINDOW) - 1;
            lastSeq = b.seq;
            seen = Math.min(0x7FFF, seen + diff);
        } else {                                                 // late arrival of an older one
            int idx = (lastSeq - b.seq) & 0xFFFF;
            if (idx < WINDOW) window |= 1 << idx;
        }
    }

    /**
     * One RSSI sample in dBm. Two running averages, a fast one (follows the signal) and a slow one (ignores noise).
     * On a steady ramp the slow one lags the fast one by a fixed number of samples, so (fast - slow) / lag is the
     * slope in dBm per second without being thrown off by the +-3 dBm jitter every radio has.
     */
    public void onRssi(double dbm, long nowMs) {
        if (Double.isNaN(rssi)) {
            rssi = dbm;
            rssiSlow = dbm;
            slope = 0;
            rssiAtMs = nowMs;
            return;
        }
        double dt = (nowMs - rssiAtMs) / 1000.0;
        rssi = 0.5 * rssi + 0.5 * dbm;
        rssiSlow = 0.8 * rssiSlow + 0.2 * dbm;
        if (dt < 0.05) return;                                   // same instant: only smooth the level
        avgDt = 0.8 * avgDt + 0.2 * Math.max(0.1, Math.min(10.0, dt));
        slope = (rssi - rssiSlow) / (3.0 * avgDt);
        rssiAtMs = nowMs;
    }

    /** A message that went through this neighbour was acknowledged end to end. */
    public void onSuccess(long rttMs) {
        success = 0.8 * success + 0.2;
        failStreak = 0;
        this.rttMs = Double.isNaN(this.rttMs) ? rttMs : 0.8 * this.rttMs + 0.2 * rttMs;
    }

    /** No ACK for a message that went through this neighbour. */
    public void onFailure(long nowMs) {
        success = 0.8 * success;
        failStreak++;
        lastFailMs = nowMs;
    }

    // ---- derived ----

    public boolean hasBeacon() { return beaconSeen; }
    public boolean hasRssi() { return !Double.isNaN(rssi); }
    public double rssiDbm() { return rssi; }
    public double rssiSlopeDbmPerSec() { return slope; }
    public double successRate() { return success; }
    public double rttMs() { return rttMs; }
    public int failStreak() { return failStreak; }

    /**
     * Fraction of the last beacons that arrived (0..1). 0.9 before there is any information, so a new neighbour
     * is neither trusted blindly nor ignored.
     */
    public double receiveQuality() {
        if (!beaconSeen) return hasRssi() ? rssiQuality() : 0.9;
        int expected = Math.min(WINDOW, ((lastSeq - firstSeq) & 0xFFFF) + 1);
        if (expected < 3) return 0.9;
        return Math.max(0.0, Math.min(1.0, Integer.bitCount(window) / (double) expected));
    }

    /** RSSI mapped to 0..1 (linear between {@link #RSSI_FLOOR} and {@link #RSSI_GOOD}); 1 if unknown. */
    public double rssiQuality() {
        if (Double.isNaN(rssi)) return 1.0;
        return Math.max(0.0, Math.min(1.0, (rssi - RSSI_FLOOR) / (RSSI_GOOD - RSSI_FLOOR)));
    }

    /**
     * Expected transmissions per delivered packet on this link (1 = perfect). Beacon loss and RSSI both count;
     * the worse of the two wins, because either one alone can be misleading (a strong signal with interference
     * loses beacons, a weak signal can still be clean for a moment).
     */
    public double etx() {
        double q = Math.min(receiveQuality(), 0.4 + 0.6 * rssiQuality());
        return 1.0 / Math.max(0.1, q);
    }

    /**
     * Milliseconds until the RSSI trend says the link falls under the floor; Long.MAX_VALUE if it is not fading or
     * there is no RSSI. This is how a moving phone is noticed BEFORE the link actually breaks.
     */
    public long predictedLifetimeMs() {
        if (Double.isNaN(rssi) || slope > -0.8) return Long.MAX_VALUE;   // gentler than this is noise or a slow drift, not a break
        double secs = (rssi - RSSI_FLOOR) / -slope;
        if (secs <= 0) return 0;
        return secs > 1e9 ? Long.MAX_VALUE : (long) (secs * 1000);
    }

    /** True when this link is expected to break within horizonMs. */
    public boolean expiring(long horizonMs) {
        return predictedLifetimeMs() <= horizonMs;
    }

    /**
     * No beacon for more than silenceMs although this neighbour used to send them: the link is probably dead even if
     * the radio never reported it (a phone that walked out of range). False for neighbours that never beaconed.
     */
    public boolean silent(long nowMs, long silenceMs) {
        return beaconSeen && lastBeaconMs >= 0 && nowMs - lastBeaconMs > silenceMs;
    }

    /** The neighbour recently failed several times in a row: avoid it unless nothing else exists. */
    public boolean recentlyBad(long nowMs, long coolMs) {
        return failStreak >= 2 && lastFailMs >= 0 && nowMs - lastFailMs < coolMs;
    }
}
