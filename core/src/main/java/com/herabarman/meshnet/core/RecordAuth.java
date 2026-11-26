package com.herabarman.meshnet.core;

/**
 * Signing and verification of location records, so that a node can only publish where IT is, never where somebody
 * else is. The core has no crypto of its own for this: the app plugs in a signature scheme (the kit ships one on
 * top of its ECDSA identity keys).
 */
public interface RecordAuth {
    /** My public key, in whatever encoding {@link #verify} accepts. */
    byte[] publicKey();

    /** Sign data with my private key. */
    byte[] sign(byte[] data);

    /** @return true only if signature is a valid signature of data under publicKey. Must not throw. */
    boolean verify(byte[] publicKey, byte[] data, byte[] signature);
}
