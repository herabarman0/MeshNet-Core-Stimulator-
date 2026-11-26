package com.herabarman.meshnet.core;

/**
 * How busy this node's radio is, 0..1: an exponentially smoothed count of transmissions per second compared
 * with what the radio can carry. Cheap (one add per transmission). Used for two things: it goes into my beacon
 * so neighbours can steer around a congested relay, and it decides when I start shedding low-priority relay traffic.
 */
public final class LoadMeter {
    private final double capacityPerSec;
    private final long windowMs;
    private long windowStart = -1;
    private int count;
    private double rate;          // smoothed packets per second

    public LoadMeter(double capacityPerSec, long windowMs) {
        this.capacityPerSec = Math.max(0.1, capacityPerSec);
        this.windowMs = Math.max(100, windowMs);
    }

    public void onTransmit(long nowMs) {
        roll(nowMs);
        count++;
    }

    private void roll(long nowMs) {
        if (windowStart < 0) {
            windowStart = nowMs;
            return;
        }
        while (nowMs - windowStart >= windowMs) {
            double inst = count * 1000.0 / windowMs;
            rate = 0.6 * rate + 0.4 * inst;
            count = 0;
            windowStart += windowMs;
            if (nowMs - windowStart > 20 * windowMs) {          // long silence: decay in one step
                rate = 0;
                windowStart = nowMs;
            }
        }
    }

    /** 0 = idle, 1 = at capacity (can go above 1 briefly; callers clamp). */
    public double load(long nowMs) {
        roll(nowMs);
        double partial = count * 1000.0 / windowMs;
        return Math.min(2.0, Math.max(rate, 0.5 * rate + 0.5 * partial) / capacityPerSec);
    }
}
