package com.herabarman.meshnet.kit;

/** Classic token bucket: up to `burst` events at once, refilled at `perSecond`. Not thread-safe by itself. */
final class TokenBucket {
    private double burst;
    private double perMs;
    private final double baseBurst;
    private final double basePerMs;
    private double tokens;
    private long last;

    TokenBucket(double burst, double perSecond, long nowMs) {
        this.burst = burst;
        this.perMs = perSecond / 1000.0;
        this.baseBurst = burst;
        this.basePerMs = this.perMs;
        this.tokens = burst;
        this.last = nowMs;
    }

    /** Multiply the configured rate and burst (fairness: contributors get more, free riders less). */
    void setScale(double f) { burst = baseBurst * f; perMs = basePerMs * f; if (tokens > burst) tokens = burst; }

    boolean tryTake(long nowMs) {
        tokens = Math.min(burst, tokens + (nowMs - last) * perMs);
        last = nowMs;
        if (tokens < 1) return false;
        tokens -= 1;
        return true;
    }
}
