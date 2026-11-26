package com.herabarman.meshnet.core;

import java.nio.ByteBuffer;

/**
 * The small status a node puts in its periodic HELLO beacon (the HELLO payload, 5 bytes):
 * battery(1) flags(1) load(1) seq(2).
 *
 * battery 0..255 (255 = 100 %), flags: bit0 charging, bit1-2 mobility class (0 still, 1 walking, 2 bike / slow
 * vehicle, 3 fast), bit3 gateway. load 0..255 (how busy the radio is). seq counts beacons, so the receiver can
 * count how many it missed (that is the B.A.T.M.A.N. way to measure link quality without sending extra probes).
 * An old node that sends a HELLO with an empty payload simply has no beacon: {@link #decode} returns null.
 */
public final class Beacon {
    public static final int SIZE = 5;

    public final int battery;
    public final boolean charging;
    public final int mobility;
    public final boolean gateway;
    public final int load;
    public final int seq;

    public Beacon(int battery, boolean charging, int mobility, boolean gateway, int load, int seq) {
        this.battery = clamp(battery, 0, 255);
        this.charging = charging;
        this.mobility = clamp(mobility, 0, 3);
        this.gateway = gateway;
        this.load = clamp(load, 0, 255);
        this.seq = seq & 0xFFFF;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public double batteryFraction() { return battery / 255.0; }
    public double loadFraction() { return load / 255.0; }

    public byte[] encode() {
        int flags = (charging ? 1 : 0) | (mobility << 1) | (gateway ? 8 : 0);
        return ByteBuffer.allocate(SIZE).put((byte) battery).put((byte) flags).put((byte) load)
                .putShort((short) seq).array();
    }

    /** @return the beacon, or null if the payload is too short (a HELLO from a node without beacons) */
    public static Beacon decode(byte[] payload) {
        if (payload == null || payload.length < SIZE) return null;
        ByteBuffer b = ByteBuffer.wrap(payload);
        int battery = b.get() & 0xFF;
        int flags = b.get() & 0xFF;
        int load = b.get() & 0xFF;
        int seq = b.getShort() & 0xFFFF;
        return new Beacon(battery, (flags & 1) != 0, (flags >> 1) & 3, (flags & 8) != 0, load, seq);
    }

    /** Speed in metres per second to mobility class. */
    public static int mobilityClass(double speedMps) {
        if (!(speedMps >= 0.5)) return 0;
        if (speedMps < 2.5) return 1;
        if (speedMps < 8.0) return 2;
        return 3;
    }
}
