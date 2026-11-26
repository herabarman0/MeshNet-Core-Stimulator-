package com.herabarman.meshnet.core;

/** Pluggable payload encryption. Header stays in clear so relays can route. */
public interface Crypto {
    byte[] encrypt(byte[] plain, int src, int dst, long msgId);

    /** @return plaintext, or null if authentication fails */
    byte[] decrypt(byte[] cipher, int src, int dst, long msgId);

    /**
     * Real-time voice packets (never retried, often lost or reordered). Implementations whose encrypt() keeps
     * per-message state (a ratchet) use a stateless scheme here. The default is the ordinary encrypt().
     */
    default byte[] encryptVoice(byte[] plain, int src, int dst, long msgId) {
        return encrypt(plain, src, dst, msgId);
    }

    /** @return plaintext, or null if authentication fails */
    default byte[] decryptVoice(byte[] cipher, int src, int dst, long msgId) {
        return decrypt(cipher, src, dst, msgId);
    }
}
