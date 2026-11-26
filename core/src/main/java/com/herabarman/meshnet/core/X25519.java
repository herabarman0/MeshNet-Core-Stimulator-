package com.herabarman.meshnet.core;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Random;

/**
 * Pure-Java RFC 7748 X25519 (Curve25519 Montgomery ladder over F_{2^255 - 19}).
 * Zero external dependencies and works identically on Java 8+, Android API 24+, and embedded JVMs
 * where JCA "X25519" is unavailable.
 */
public final class X25519 {
    public static final int KEY_BYTES = 32;
    private static final byte[] BASEPOINT = new byte[32];

    static {
        BASEPOINT[0] = 9;
    }

    private X25519() {}

    /** Generates a 32-byte clamped X25519 private key. */
    public static byte[] generatePrivateKey() {
        return generatePrivateKey(new SecureRandom());
    }

    public static byte[] generatePrivateKey(Random rng) {
        byte[] k = new byte[KEY_BYTES];
        rng.nextBytes(k);
        clamp(k);
        return k;
    }

    /** Derives the 32-byte public key from a 32-byte private key. */
    public static byte[] publicFromPrivate(byte[] privateKey) {
        return scalarMult(privateKey, BASEPOINT);
    }

    /** Computes RFC 7748 X25519(scalar, uCoordinate). Checks for all-zero low-order output. */
    public static byte[] scalarMult(byte[] scalar, byte[] uCoordinate) {
        if (scalar == null || scalar.length != KEY_BYTES) {
            throw new IllegalArgumentException("scalar must be 32 bytes");
        }
        if (uCoordinate == null || uCoordinate.length != KEY_BYTES) {
            throw new IllegalArgumentException("uCoordinate must be 32 bytes");
        }
        byte[] z = scalar.clone();
        clamp(z);

        long[] x = new long[16];
        unpack25519(x, uCoordinate);

        long[] a = new long[16], b = new long[16], c = new long[16], d = new long[16];
        long[] e = new long[16], f = new long[16];
        a[0] = 1;
        d[0] = 1;
        System.arraycopy(x, 0, b, 0, 16);

        long[] c121665 = new long[16];
        c121665[0] = 0xdb41L;
        c121665[1] = 1L;

        int swap = 0;
        for (int i = 254; i >= 0; i--) {
            int r = ((z[i >>> 3] & 0xff) >>> (i & 7)) & 1;
            swap ^= r;
            sel25519(a, b, swap);
            sel25519(c, d, swap);
            swap = r;

            A(e, a, c);
            Z(a, a, c);
            A(c, b, d);
            Z(b, b, d);
            S(d, e);
            S(f, a);
            M(a, c, a);
            M(c, b, e);
            A(e, a, c);
            Z(a, a, c);
            S(b, a);
            Z(c, d, f);
            M(a, c, c121665);
            A(a, a, d);
            M(c, c, a);
            M(a, d, f);
            M(d, b, x);
            S(b, e);
        }
        sel25519(a, b, swap);
        sel25519(c, d, swap);

        inv25519(c, c);
        M(a, a, c);

        byte[] out = new byte[KEY_BYTES];
        pack25519(out, a);
        return out;
    }

    /** Returns true if the shared secret is all zeros (small-subgroup / invalid peer key). */
    public static boolean isAllZero(byte[] bytes) {
        int acc = 0;
        for (byte b : bytes) acc |= (b & 0xff);
        return acc == 0;
    }

    private static void clamp(byte[] k) {
        k[0] &= (byte) 248;
        k[31] &= (byte) 127;
        k[31] |= (byte) 64;
    }

    private static void car25519(long[] o) {
        for (int i = 0; i < 16; i++) {
            o[i] += 65536L;
            long c = o[i] >> 16;
            if (i < 15) {
                o[i + 1] += c - 1L;
            } else {
                o[0] += 38L * (c - 1L);
            }
            o[i] -= c << 16;
        }
    }

    private static void sel25519(long[] p, long[] q, int b) {
        long c = ~(b - 1L);
        for (int i = 0; i < 16; i++) {
            long t = c & (p[i] ^ q[i]);
            p[i] ^= t;
            q[i] ^= t;
        }
    }

    private static void unpack25519(long[] o, byte[] n) {
        for (int i = 0; i < 16; i++) {
            o[i] = (n[2 * i] & 0xffL) | ((n[2 * i + 1] & 0xffL) << 8);
        }
        o[15] &= 0x7fffL;
    }

    private static void pack25519(byte[] o, long[] n) {
        long[] t = Arrays.copyOf(n, 16);
        long[] m = new long[16];
        car25519(t);
        car25519(t);
        car25519(t);
        for (int j = 0; j < 2; j++) {
            m[0] = t[0] - 0xffedL;
            for (int i = 1; i < 15; i++) {
                m[i] = t[i] - 0xffffL - ((m[i - 1] >> 16) & 1L);
                m[i - 1] &= 0xffffL;
            }
            m[15] = t[15] - 0x7fffL - ((m[14] >> 16) & 1L);
            long b = (m[15] >> 16) & 1L;
            m[14] &= 0xffffL;
            sel25519(t, m, 1 - (int) b);
        }
        for (int i = 0; i < 16; i++) {
            o[2 * i] = (byte) (t[i] & 0xffL);
            o[2 * i + 1] = (byte) ((t[i] >> 8) & 0xffL);
        }
    }

    private static void A(long[] o, long[] a, long[] b) {
        for (int i = 0; i < 16; i++) o[i] = a[i] + b[i];
    }

    private static void Z(long[] o, long[] a, long[] b) {
        for (int i = 0; i < 16; i++) o[i] = a[i] - b[i];
    }

    private static void M(long[] o, long[] a, long[] b) {
        long[] t = new long[31];
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                t[i + j] += a[i] * b[j];
            }
        }
        for (int i = 0; i < 15; i++) {
            t[i] += 38L * t[i + 16];
        }
        System.arraycopy(t, 0, o, 0, 16);
        car25519(o);
        car25519(o);
    }

    private static void S(long[] o, long[] a) {
        M(o, a, a);
    }

    private static void inv25519(long[] o, long[] i) {
        long[] c = Arrays.copyOf(i, 16);
        for (int a = 253; a >= 0; a--) {
            S(c, c);
            if (a != 2 && a != 4) {
                M(c, c, i);
            }
        }
        System.arraycopy(c, 0, o, 0, 16);
    }
}
