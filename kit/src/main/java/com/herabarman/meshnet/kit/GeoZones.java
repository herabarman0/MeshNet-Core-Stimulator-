package com.herabarman.meshnet.kit;

/**
 * Zero-config zones from a GPS / network location.
 *
 * The world is cut into geohash cells (precision 1..6, precision 6 is about 1.2 km x 0.6 km).
 * The cell's column/row are put on a Hilbert curve, so cells that are close on the map get close
 * zone numbers. The core routes on a ring of zone numbers, so a short ring distance then means a
 * short real distance, and greedy routing mostly walks toward the destination.
 *
 * zoneCount(precision) is 4^order and always fits an int for precision <= 6 (2^30 at precision 6).
 * Nothing else is needed: no zone table, no manual numbering.
 */
public final class GeoZones {
    private GeoZones() {}

    public static final int MAX_PRECISION = 6;
    public static final int DEFAULT_PRECISION = 6;
    private static final String B32 = "0123456789bcdefghjkmnpqrstuvwxyz";

    public static int lonBits(int precision) { return (5 * precision + 1) / 2; }
    public static int latBits(int precision) { return (5 * precision) / 2; }

    /** Size of the zone number space: pass it to MeshConfig.zoneCount. */
    public static int zoneCount(int precision) {
        check(precision);
        return 1 << (2 * lonBits(precision));
    }

    private static void check(int precision) {
        if (precision < 1 || precision > MAX_PRECISION) throw new IllegalArgumentException("precision 1.." + MAX_PRECISION);
    }

    private static int cell(double value, double min, double span, int bits) {
        int cells = 1 << bits;
        int c = (int) Math.floor((value - min) / span * cells);
        return Math.max(0, Math.min(cells - 1, c));
    }

    /** The zone number of the geohash cell that contains this point. */
    public static int zoneOf(double lat, double lon, int precision) {
        check(precision);
        int order = lonBits(precision);
        int x = cell(lon, -180.0, 360.0, lonBits(precision));
        int y = cell(lat, -90.0, 180.0, latBits(precision));
        return hilbert(order, x, y);
    }

    /** Standard geohash string (precision 1..12), for display and debugging. */
    public static String geohash(double lat, double lon, int precision) {
        if (precision < 1 || precision > 12) throw new IllegalArgumentException("precision");
        double latMin = -90, latMax = 90, lonMin = -180, lonMax = 180;
        StringBuilder sb = new StringBuilder(precision);
        boolean evenBit = true;
        int bit = 0, ch = 0;
        while (sb.length() < precision) {
            if (evenBit) {
                double mid = (lonMin + lonMax) / 2;
                if (lon >= mid) { ch = (ch << 1) | 1; lonMin = mid; } else { ch <<= 1; lonMax = mid; }
            } else {
                double mid = (latMin + latMax) / 2;
                if (lat >= mid) { ch = (ch << 1) | 1; latMin = mid; } else { ch <<= 1; latMax = mid; }
            }
            evenBit = !evenBit;
            if (++bit == 5) { sb.append(B32.charAt(ch)); bit = 0; ch = 0; }
        }
        return sb.toString();
    }

    /** Hilbert curve index of cell (x, y) on a 2^order x 2^order grid. */
    static int hilbert(int order, int x, int y) {
        int n = 1 << order;
        long d = 0;
        for (int s = n / 2; s > 0; s /= 2) {
            int rx = (x & s) > 0 ? 1 : 0;
            int ry = (y & s) > 0 ? 1 : 0;
            d += (long) s * s * ((3 * rx) ^ ry);
            if (ry == 0) {
                if (rx == 1) { x = n - 1 - x; y = n - 1 - y; }
                int t = x; x = y; y = t;
            }
        }
        return (int) d;
    }

    /** Zone used before the first location fix: stable per node, so the node still has a place on the ring. */
    public static int unlocatedZone(int nodeId, int precision) {
        int h = nodeId * 0x9E3779B1;
        h ^= h >>> 15;
        return (h & 0x7FFFFFFF) % zoneCount(precision);
    }
}
