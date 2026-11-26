package com.herabarman.meshnet.kit;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Public Signal Double Ratchet facade over {@link Ratchet} providing per-message Forward Secrecy,
 * out-of-order skipped message decryption, and Post-Compromise Security via continuous P-256 DH ratcheting.
 */
public final class DoubleRatchetSession {
    private static final byte[] INFO = "meshnet-public-dr-v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SALT = "meshnet-public-dr-salt".getBytes(StandardCharsets.US_ASCII);

    private final Ratchet ratchet;

    private DoubleRatchetSession(Ratchet ratchet) {
        this.ratchet = ratchet;
    }

    public static DoubleRatchetSession createInitiator(IdentityKeys myKeys, byte[] peerPubRaw) {
        byte[] rk0 = Ratchet.hkdf(SALT, myKeys.sharedSecret(peerPubRaw), INFO, 32);
        return new DoubleRatchetSession(Ratchet.alice(rk0, peerPubRaw));
    }

    public static DoubleRatchetSession createResponder(IdentityKeys myKeys, byte[] peerPubRaw) {
        byte[] rk0 = Ratchet.hkdf(SALT, myKeys.sharedSecret(peerPubRaw), INFO, 32);
        return new DoubleRatchetSession(Ratchet.bob(rk0, myKeys));
    }

    public byte[] encrypt(byte[] plaintext, byte[] associatedData) {
        return ratchet.encrypt(plaintext, associatedData);
    }

    public byte[] decrypt(byte[] ciphertext, byte[] associatedData) {
        return ratchet.decrypt(ciphertext, associatedData);
    }

    public int sendingCounter() { return ratchet.ns; }
    public int receivingCounter() { return ratchet.nr; }
    public int skippedKeyCount() { return ratchet.skipped.size(); }
    public boolean hasReceived() { return ratchet.received; }
    public String currentDhFingerprint() {
        return Ratchet.hex(Arrays.copyOf(IdentityKeys.compress(ratchet.dhs.publicRaw()), 6));
    }
    public String rootKeyPrefixHex() {
        return Ratchet.hex(Arrays.copyOf(ratchet.rk, 6));
    }

    public byte[] serialize() { return ratchet.serialize(); }

    public static DoubleRatchetSession deserialize(byte[] bytes) {
        Ratchet r = Ratchet.deserialize(bytes);
        return r == null ? null : new DoubleRatchetSession(r);
    }
}
