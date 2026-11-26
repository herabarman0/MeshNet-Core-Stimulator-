package com.herabarman.meshnet.kit;

/**
 * Turns a stream of location fixes into zone changes without flapping on a cell border:
 * the first fix is taken at once, later cells only after the phone has stayed in the new cell
 * for holdMs.
 */
public final class ZoneTracker {
    private final int precision;
    private final long holdMs;
    private int current = -1;
    private int candidate = -1;
    private long candidateSince;

    public ZoneTracker(int precision, long holdMs) {
        this.precision = precision;
        this.holdMs = holdMs;
    }

    public synchronized int current() { return current; }

    /** @return the new zone if it changed, otherwise -1 */
    public synchronized int update(double lat, double lon, long nowMs) {
        int z = GeoZones.zoneOf(lat, lon, precision);
        if (current < 0) { current = z; return z; }
        if (z == current) { candidate = -1; return -1; }
        if (z != candidate) { candidate = z; candidateSince = nowMs; return -1; }
        if (nowMs - candidateSince >= holdMs) { current = z; candidate = -1; return z; }
        return -1;
    }
}
