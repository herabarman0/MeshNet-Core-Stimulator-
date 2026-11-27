package com.herabarman.meshnet.core;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Sybil & Spam Flood Defense combining Hashcash Proof-of-Work (Keccak-256) with
 * trust-weighted per-peer token-bucket rate limiting.
 *
 * Prevents an attacker from spinning up thousands of fake Sybil identities or saturating
 * the mesh radio with spam floods.
 */
public final class SybilGuard {
    public static final class AdmissionResult {
        public final boolean allowed;
        public final boolean validPow;
        public final int leadingZeroBits;
        public final double remainingTokens;
        public final String reason;

        AdmissionResult(boolean allowed, boolean validPow, int leadingZeroBits, double remainingTokens, String reason) {
            this.allowed = allowed;
            this.validPow = validPow;
            this.leadingZeroBits = leadingZeroBits;
            this.remainingTokens = remainingTokens;
            this.reason = reason;
        }
    }

    private static final class PeerBucket {
        double tokens;
        long lastRefillMs;
        long accepted;
        long blocked;
    }

    private final int requiredPowBits;
    private final double maxBurstTokens;
    private final double refillPerSec;
    private final Map<Integer, PeerBucket> buckets = new HashMap<>();

    public SybilGuard() {
        this(10, 6.0, 2.5);
    }

    public SybilGuard(int requiredPowBits, double maxBurstTokens, double refillPerSec) {
        this.requiredPowBits = Math.max(4, Math.min(24, requiredPowBits));
        this.maxBurstTokens = Math.max(1.0, maxBurstTokens);
        this.refillPerSec = Math.max(0.1, refillPerSec);
    }

    /** Mints a Keccak-256 Proof-of-Work nonce for a node identity or packet payload. */
    public static long mintPow(int nodeId, byte[] bindingData, int difficultyBits) {
        int bits = Math.max(1, Math.min(24, difficultyBits));
        byte[] safeData = bindingData == null ? new byte[0] : bindingData;
        ByteBuffer buf = ByteBuffer.allocate(4 + 8 + safeData.length);
        for (long nonce = 0L; nonce < 10_000_000L; nonce++) {
            buf.clear();
            buf.putInt(nodeId).putLong(nonce).put(safeData);
            byte[] hash = Keccak.sha3_256(buf.array());
            if (leadingZeroBits(hash) >= bits) return nonce;
        }
        return 0L;
    }

    public static int powLeadingZeros(int nodeId, byte[] bindingData, long nonce) {
        byte[] safeData = bindingData == null ? new byte[0] : bindingData;
        byte[] raw = ByteBuffer.allocate(4 + 8 + safeData.length)
                .putInt(nodeId)
                .putLong(nonce)
                .put(safeData)
                .array();
        return leadingZeroBits(Keccak.sha3_256(raw));
    }

    public static boolean verifyPow(int nodeId, byte[] bindingData, long nonce, int difficultyBits) {
        return powLeadingZeros(nodeId, bindingData, nonce) >= difficultyBits;
    }

    /**
     * Checks both cryptographic Proof-of-Work and per-peer token bucket allowance.
     * Higher {@code trustScore} (0.0..1.0) earns faster token refill; unknown/low-trust peers are strictly capped.
     */
    public synchronized AdmissionResult admitPacket(
            int senderId,
            byte[] bindingData,
            long powNonce,
            double trustScore,
            long nowMs) {
        int zeros = powLeadingZeros(senderId, bindingData, powNonce);
        if (zeros < requiredPowBits) {
            PeerBucket b = bucketOf(senderId, nowMs);
            b.blocked++;
            return new AdmissionResult(false, false, zeros, b.tokens,
                    "PoW ব্যর্থ (" + zeros + " < " + requiredPowBits + " বিট)");
        }

        PeerBucket b = bucketOf(senderId, nowMs);
        double dtSec = Math.max(0.0, (nowMs - b.lastRefillMs) / 1000.0);
        double trustMul = 0.4 + 1.2 * Math.max(0.0, Math.min(1.0, trustScore));
        b.tokens = Math.min(maxBurstTokens, b.tokens + dtSec * refillPerSec * trustMul);
        b.lastRefillMs = nowMs;

        if (b.tokens >= 1.0) {
            b.tokens -= 1.0;
            b.accepted++;
            return new AdmissionResult(true, true, zeros, b.tokens, "অনুমোদিত (PoW " + zeros + " বিট)");
        }
        b.blocked++;
        return new AdmissionResult(false, true, zeros, b.tokens, "রেট-লিমিটেড (স্প্যাম ফ্লাডিং ব্লকড)");
    }

    public synchronized AdmissionResult admitRate(int senderId, double trustScore, long nowMs) {
        PeerBucket b = bucketOf(senderId, nowMs);
        double dtSec = Math.max(0.0, (nowMs - b.lastRefillMs) / 1000.0);
        double trustMul = 0.4 + 1.2 * Math.max(0.0, Math.min(1.0, trustScore));
        b.tokens = Math.min(maxBurstTokens, b.tokens + dtSec * refillPerSec * trustMul);
        b.lastRefillMs = nowMs;

        if (b.tokens >= 1.0) {
            b.tokens -= 1.0;
            b.accepted++;
            return new AdmissionResult(true, true, requiredPowBits, b.tokens, "রেট-অনুমোদিত");
        }
        b.blocked++;
        return new AdmissionResult(false, true, requiredPowBits, b.tokens, "রেট-লিমিটেড (স্প্যাম ফ্লাডিং ব্লকড)");
    }

    public synchronized boolean hasAcceptedPeer(int senderId) {
        PeerBucket b = buckets.get(senderId);
        return b != null && b.accepted > 0;
    }

    public synchronized long totalBlocked(int senderId) {
        PeerBucket b = buckets.get(senderId);
        return b == null ? 0L : b.blocked;
    }

    public synchronized long totalAccepted(int senderId) {
        PeerBucket b = buckets.get(senderId);
        return b == null ? 0L : b.accepted;
    }

    private PeerBucket bucketOf(int senderId, long nowMs) {
        PeerBucket b = buckets.get(senderId);
        if (b == null) {
            b = new PeerBucket();
            b.tokens = maxBurstTokens;
            b.lastRefillMs = nowMs;
            buckets.put(senderId, b);
        }
        return b;
    }

    private static int leadingZeroBits(byte[] hash) {
        int count = 0;
        for (byte v : hash) {
            int u = v & 0xFF;
            if (u == 0) {
                count += 8;
            } else {
                count += Integer.numberOfLeadingZeros(u) - 24;
                break;
            }
        }
        return count;
    }
}
