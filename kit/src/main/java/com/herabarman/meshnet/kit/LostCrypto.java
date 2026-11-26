package com.herabarman.meshnet.kit;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.interfaces.ECPublicKey;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Crypto for finding a lost phone. One owner secret S gives a fresh P-256 key pair for every time slot (15 min):
 * d = HMAC(S, slot) mod n, Q = d*G. Whoever holds S can compute every slot's private key; nobody else can link two slots.
 * A finder encrypts what it saw to Q (ECDH with a one-time key + AES-GCM), so only the owner can read it, and it cannot
 * tell who the owner is. P-256 point multiplication is done here with BigInteger because the JDK offers no way to
 * derive a public key from a private scalar.
 */
public final class LostCrypto {
    private LostCrypto() { }

    private static final ECParameterSpec SPEC;
    private static final BigInteger P, A, N;
    private static final ECPoint G;
    static {
        try {
            AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
            ap.init(new ECGenParameterSpec("secp256r1"));
            SPEC = ap.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
        P = ((java.security.spec.ECFieldFp) SPEC.getCurve().getField()).getP();
        A = SPEC.getCurve().getA();
        N = SPEC.getOrder();
        G = SPEC.getGenerator();
    }

    public static final int POINT = 65, TAG = 8, IV = 12;

    // ---- elliptic curve (affine; null is the point at infinity) ----------------------------------------

    private static ECPoint add(ECPoint p, ECPoint q) {
        if (p == null) return q;
        if (q == null) return p;
        BigInteger l;
        if (p.getAffineX().equals(q.getAffineX())) {
            if (!p.getAffineY().equals(q.getAffineY()) || p.getAffineY().signum() == 0) return null;
            l = p.getAffineX().pow(2).multiply(BigInteger.valueOf(3)).add(A).multiply(p.getAffineY().shiftLeft(1).modInverse(P)).mod(P);
        } else {
            l = q.getAffineY().subtract(p.getAffineY()).multiply(q.getAffineX().subtract(p.getAffineX()).mod(P).modInverse(P)).mod(P);
        }
        BigInteger x = l.pow(2).subtract(p.getAffineX()).subtract(q.getAffineX()).mod(P);
        BigInteger y = l.multiply(p.getAffineX().subtract(x)).subtract(p.getAffineY()).mod(P);
        return new ECPoint(x, y);
    }

    static ECPoint multiplyG(BigInteger k) {
        ECPoint r = null, a = G;
        for (int i = 0; i < k.bitLength(); i++) {
            if (k.testBit(i)) r = add(r, a);
            a = add(a, a);
        }
        return r;
    }

    // ---- keys ---------------------------------------------------------------------------------------------

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data);
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) d.update(p);
            return d.digest();
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    public static byte[] newSecret() { byte[] s = new byte[32]; new SecureRandom().nextBytes(s); return s; }

    /** Private scalar of a slot. */
    public static BigInteger slotPrivate(byte[] secret, long slot) {
        byte[] h = hmac(secret, ("mn-lost-slot-" + slot).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return new BigInteger(1, h).mod(N.subtract(BigInteger.ONE)).add(BigInteger.ONE);
    }

    /** Public key of a slot, 65 bytes (04 || x || y). */
    public static byte[] slotPublic(byte[] secret, long slot) { return encode(multiplyG(slotPrivate(secret, slot))); }

    public static byte[] tagOf(byte[] pub) { return java.util.Arrays.copyOf(sha256("mn-lost-tag".getBytes(java.nio.charset.StandardCharsets.US_ASCII), pub), TAG); }

    static byte[] encode(ECPoint p) {
        byte[] out = new byte[POINT];
        out[0] = 4;
        copy32(p.getAffineX(), out, 1);
        copy32(p.getAffineY(), out, 33);
        return out;
    }

    private static void copy32(BigInteger v, byte[] out, int off) {
        byte[] b = v.toByteArray();
        int len = Math.min(32, b.length);
        System.arraycopy(b, b.length - len, out, off + 32 - len, len);
    }

    private static PublicKey publicKey(byte[] pub) throws GeneralSecurityException {
        if (pub.length != POINT || pub[0] != 4) throw new GeneralSecurityException("bad point");
        BigInteger x = new BigInteger(1, java.util.Arrays.copyOfRange(pub, 1, 33)), y = new BigInteger(1, java.util.Arrays.copyOfRange(pub, 33, 65));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), SPEC));   // validates the point is on the curve
    }

    private static byte[] aesKey(byte[] shared, byte[] tag) { return java.util.Arrays.copyOf(sha256(shared, tag, "mn-lost-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII)), 16); }

    // ---- sealing -----------------------------------------------------------------------------------------------

    /** Result of {@link #seal}: one-time public key, iv, ciphertext. */
    public static final class Sealed {
        public final byte[] eph, iv, ct;
        Sealed(byte[] e, byte[] i, byte[] c) { eph = e; iv = i; ct = c; }
    }

    /** Encrypt plain to the slot public key; @return null if the key is not a valid point. */
    public static Sealed seal(byte[] slotPub, byte[] plain) {
        try {
            PublicKey q = publicKey(slotPub);
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair e = g.generateKeyPair();
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(e.getPrivate());
            ka.doPhase(q, true);
            byte[] tag = tagOf(slotPub), iv = new byte[IV];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey(ka.generateSecret(), tag), "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(tag);
            return new Sealed(encode(((ECPublicKey) e.getPublic()).getW()), iv, c.doFinal(plain));
        } catch (GeneralSecurityException | RuntimeException ex) { return null; }
    }

    /** @return the plaintext, or null if it does not open under this slot key. */
    public static byte[] open(BigInteger slotPrivate, byte[] tag, byte[] eph, byte[] iv, byte[] ct) {
        try {
            PrivateKey d = KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(slotPrivate, SPEC));
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(d);
            ka.doPhase(publicKey(eph), true);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey(ka.generateSecret(), tag), "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(tag);
            return c.doFinal(ct);
        } catch (GeneralSecurityException | RuntimeException ex) { return null; }
    }
}
