package com.herabarman.meshnet.kit;

/**
 * Speed in metres per second from a stream of location fixes (haversine distance / time, lightly smoothed, and
 * ignoring jumps that are GPS noise rather than movement). The core turns it into a mobility class and tells its
 * neighbours; the duty cycle uses it too.
 */
public final class SpeedTracker {
    private double lat = Double.NaN, lon, speed;
    private long atMs;

    /** @return the smoothed speed after this fix */
    public synchronized double update(double newLat, double newLon, long nowMs) {
        if (!Double.isNaN(lat)) {
            double dt = (nowMs - atMs) / 1000.0;
            if (dt >= 1.0) {
                double d = distanceM(lat, lon, newLat, newLon);
                double v = d / dt;
                if (v < 70.0) speed = 0.6 * speed + 0.4 * v;           // above ~250 km/h is a bad fix, not a phone
                if (d < 5.0) speed *= 0.5;                              // within GPS noise: standing still
                lat = newLat;
                lon = newLon;
                atMs = nowMs;
            }
            return speed;
        }
        lat = newLat;
        lon = newLon;
        atMs = nowMs;
        return 0;
    }

    public synchronized double speed() { return speed; }

    static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000.0;
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
