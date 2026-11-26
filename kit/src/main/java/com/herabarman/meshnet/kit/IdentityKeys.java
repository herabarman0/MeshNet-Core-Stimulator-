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
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import javax.crypto.KeyAgreement;

/**
 * One EC P-256 identity per user (same curve as the app's E2ee class, so it works from Android 5).
 * The public key travels as 65 raw bytes (0x04 || X || Y). The private key is stored as PKCS#8 by the
 * caller, in app-private storage.
 */
public final class IdentityKeys {
    private final PrivateKey priv;
    private final byte[] pubRaw;

    private IdentityKeys(PrivateKey priv, byte[] pubRaw) {
        this.priv = priv;
        this.pubRaw = pubRaw;
    }

    private static ECParameterSpec p256() throws GeneralSecurityException {
        AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
        ap.init(new ECGenParameterSpec("secp256r1"));
        return ap.getParameterSpec(ECParameterSpec.class);
    }

    public static IdentityKeys generate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair kp = g.generateKeyPair();
            return new IdentityKeys(kp.getPrivate(), raw((ECPublicKey) kp.getPublic()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static IdentityKeys restore(byte[] pkcs8, byte[] publicRaw) {
        try {
            PrivateKey p = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            publicFromRaw(publicRaw); // validates the point
            return new IdentityKeys(p, publicRaw.clone());
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("bad key material", e);
        }
    }

    public byte[] privateEncoded() { return priv.getEncoded(); }
    public byte[] publicRaw() { return pubRaw.clone(); }

    private static byte[] raw(ECPublicKey k) {
        byte[] out = new byte[65];
        out[0] = 0x04;
        copyFixed(k.getW().getAffineX(), out, 1);
        copyFixed(k.getW().getAffineY(), out, 33);
        return out;
    }

    private static void copyFixed(BigInteger v, byte[] out, int off) {
        byte[] b = v.toByteArray();
        int start = b.length > 32 ? b.length - 32 : 0;
        int len = b.length - start;
        System.arraycopy(b, start, out, off + 32 - len, len);
    }

    static PublicKey publicFromRaw(byte[] raw) throws GeneralSecurityException {
        if (raw.length != 65 || raw[0] != 0x04) throw new GeneralSecurityException("bad public key");
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(raw, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(raw, 33, 65));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), p256()));
    }

    /** ECDH shared secret with a peer's raw public key (the JDK/Android provider checks the point is on the curve). */
    byte[] sharedSecret(byte[] peerRaw) {
        try {
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(priv);
            ka.doPhase(publicFromRaw(peerRaw), true);
            return ka.generateSecret();
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("bad peer key", e);
        }
    }

    private static final BigInteger P = new BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16);
    private static final BigInteger B = new BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16);

    /** 65-byte uncompressed point -> 33-byte compressed point (02/03 || X). */
    static byte[] compress(byte[] raw) {
        byte[] c = new byte[33];
        c[0] = (byte) (((raw[64] & 1) == 0) ? 0x02 : 0x03);
        System.arraycopy(raw, 1, c, 1, 32);
        return c;
    }

    /** @return the 65-byte point, or null if c is not a valid compressed P-256 point */
    static byte[] decompress(byte[] c) {
        if (c.length != 33 || (c[0] != 0x02 && c[0] != 0x03)) return null;
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(c, 1, 33));
        if (x.compareTo(P) >= 0) return null;
        BigInteger rhs = x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(B).mod(P);
        BigInteger y = rhs.modPow(P.add(BigInteger.ONE).shiftRight(2), P);
        if (!y.multiply(y).mod(P).equals(rhs)) return null;            // x is not on the curve
        if (y.testBit(0) != (c[0] == 0x03)) y = P.subtract(y);
        byte[] raw = new byte[65];
        raw[0] = 0x04;
        copyFixed(x, raw, 1);
        copyFixed(y, raw, 33);
        return raw;
    }

    /** ECDSA-SHA256 signature (DER) with the identity key. Used for group ops and broadcast alerts. */
    public byte[] sign(byte[] data) {
        try {
            java.security.Signature sg = java.security.Signature.getInstance("SHA256withECDSA");
            sg.initSign(priv);
            sg.update(data);
            return sg.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verify(byte[] publicRaw, byte[] data, byte[] sig) {
        try {
            java.security.Signature sg = java.security.Signature.getInstance("SHA256withECDSA");
            sg.initVerify(publicFromRaw(publicRaw));
            sg.update(data);
            return sg.verify(sig);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    /** Short human-comparable fingerprint, e.g. "A1B2 C3D4 E5F6 0718". */
    public static String fingerprint(byte[] publicRaw) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(publicRaw);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                if (i > 0 && i % 2 == 0) sb.append(' ');
                sb.append(String.format("%02X", d[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
