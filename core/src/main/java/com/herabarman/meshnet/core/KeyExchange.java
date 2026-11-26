package com.herabarman.meshnet.core;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import javax.crypto.KeyAgreement;

/**
 * X25519 key agreement helper.
 * Supports both JCA KeyPair (JDK 11+ / Android 13+) and universal 32-byte RFC 7748 {@link X25519}
 * keys that work on every Android API level (24+) and embedded JVM.
 */
public final class KeyExchange {
    private KeyExchange() {}

    public static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance("X25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return 32 byte AES-256 key = SHA-256(shared secret) */
    public static byte[] deriveKey(PrivateKey mine, PublicKey theirs) {
        try {
            KeyAgreement ka = KeyAgreement.getInstance("X25519");
            ka.init(mine);
            ka.doPhase(theirs, true);
            return MessageDigest.getInstance("SHA-256").digest(ka.generateSecret());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Universal raw-byte X25519 keypair: { privateKey[32], publicKey[32] }. Works on all platforms. */
    public static byte[][] generateRawKeyPair() {
        byte[] priv = X25519.generatePrivateKey();
        byte[] pub = X25519.publicFromPrivate(priv);
        return new byte[][]{priv, pub};
    }

    /**
     * Universal 32-byte AES-256 key derivation from raw 32-byte X25519 keys.
     * Binds domain separator + shared secret via SHA-256.
     */
    public static byte[] deriveRawKey(byte[] myPrivate32, byte[] theirPublic32) {
        byte[] shared = X25519.scalarMult(myPrivate32, theirPublic32);
        if (X25519.isAllZero(shared)) {
            throw new IllegalArgumentException("invalid peer X25519 key (low-order point)");
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] myPub = X25519.publicFromPrivate(myPrivate32);
            // Order-independent transcript binding of both public keys + shared secret
            int cmp = compareUnsigned(myPub, theirPublic32);
            sha.update(ByteBuffer.wrap(shared));
            sha.update(cmp <= 0 ? myPub : theirPublic32);
            sha.update(cmp <= 0 ? theirPublic32 : myPub);
            return sha.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int compareUnsigned(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int d = (a[i] & 0xff) - (b[i] & 0xff);
            if (d != 0) return d;
        }
        return Integer.compare(a.length, b.length);
    }
}
