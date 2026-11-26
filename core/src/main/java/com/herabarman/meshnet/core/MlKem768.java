package com.herabarman.meshnet.core;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Pure-Java FIPS 203 ML-KEM-768 (Kyber-768) Post-Quantum Key Encapsulation Mechanism.
 * Parameters: n=256, q=3329, k=3, eta1=2, eta2=2, du=10, dv=4.
 * Sizes: ek = 1184 bytes, dk = 2400 bytes, ciphertext = 1088 bytes, sharedSecret = 32 bytes.
 */
public final class MlKem768 {
    public static final int N = 256;
    public static final int Q = 3329;
    public static final int K = 3;
    public static final int ETA1 = 2;
    public static final int ETA2 = 2;
    public static final int DU = 10;
    public static final int DV = 4;

    public static final int EK_BYTES = 1184;          // 384 * 3 + 32
    public static final int DK_BYTES = 2400;          // 384 * 3 + 1184 + 32 + 32
    public static final int CT_BYTES = 1088;          // 320 * 3 + 128
    public static final int SS_BYTES = 32;

    private static final SecureRandom RNG = new SecureRandom();

    /** Standard FIPS 203 zetas table: 17^{bitrev_7(i)} mod 3329. */
    private static final int[] ZETAS = {
        1, 1729, 2580, 3289, 2642, 630, 1897, 848,
        1062, 1919, 193, 797, 2786, 3260, 569, 1746,
        296, 2447, 1339, 1476, 3046, 56, 2240, 1333,
        1426, 2094, 535, 2882, 2393, 2879, 1974, 821,
        289, 331, 3253, 1756, 1197, 2304, 2277, 2055,
        650, 1977, 2513, 632, 2865, 33, 1320, 1915,
        2319, 1435, 807, 452, 1438, 2868, 1534, 2402,
        2647, 2617, 1481, 648, 2474, 3110, 1227, 910,
        17, 2761, 583, 2649, 1637, 723, 2288, 1100,
        1409, 2662, 3281, 233, 756, 2156, 3015, 3050,
        1703, 1651, 2789, 1789, 1847, 952, 1461, 2687,
        839, 2398, 2768, 2403, 753, 1818, 290, 1161,
        1757, 1805, 96, 1881, 2238, 2249, 2469, 1351,
        2599, 1659, 145, 962, 148, 2425, 1088, 1338,
        1537, 738, 2248, 1640, 1473, 1280, 1126, 53,
        1873, 1328, 1362, 1043, 1720, 2945, 2431, 1835
    };

    private static final int[] GAMMAS = buildGammas();

    private static int[] buildGammas() {
        int[] g = new int[128];
        for (int i = 0; i < 128; i++) {
            int br = Integer.reverse(i) >>> 25; // 7-bit bit-reversal
            int pow = (2 * br) + 1;
            g[i] = modPow(17, pow, Q);
        }
        return g;
    }

    private static int[] buildZetas() {
        int[] z = new int[128];
        for (int i = 0; i < 128; i++) {
            int br = Integer.reverse(i) >>> 25;
            z[i] = modPow(17, br, Q);
        }
        return z;
    }

    private static final int[] EXACT_ZETAS = buildZetas();

    private MlKem768() {}

    public static final class KeyPair {
        public final byte[] ek; // encapsulation key (public, 1184B)
        public final byte[] dk; // decapsulation key (private, 2400B)

        KeyPair(byte[] ek, byte[] dk) {
            this.ek = ek;
            this.dk = dk;
        }
    }

    public static final class Encapsulation {
        public final byte[] ciphertext;   // 1088B
        public final byte[] sharedSecret; // 32B

        Encapsulation(byte[] ciphertext, byte[] sharedSecret) {
            this.ciphertext = ciphertext;
            this.sharedSecret = sharedSecret;
        }
    }

    public static KeyPair keygen() {
        byte[] d = new byte[32];
        byte[] z = new byte[32];
        RNG.nextBytes(d);
        RNG.nextBytes(z);
        return keygenDeterministic(d, z);
    }

    /** Deterministic ML-KEM-768 KeyGen for KAT / conformance testing. */
    public static KeyPair keygenDeterministic(byte[] d32, byte[] z32) {
        if (d32.length != 32 || z32.length != 32) throw new IllegalArgumentException("seed length");
        byte[] gIn = new byte[33];
        System.arraycopy(d32, 0, gIn, 0, 32);
        gIn[32] = (byte) K;
        byte[] g = Keccak.sha3_512(gIn);
        byte[] rho = Arrays.copyOfRange(g, 0, 32);
        byte[] sigma = Arrays.copyOfRange(g, 32, 64);

        int[][][] aHat = generateMatrix(rho, false);
        int[][] s = new int[K][];
        int[][] e = new int[K][];
        int nonce = 0;
        for (int i = 0; i < K; i++) s[i] = ntt(samplePolyCbd(prf(sigma, nonce++, ETA1)));
        for (int i = 0; i < K; i++) e[i] = ntt(samplePolyCbd(prf(sigma, nonce++, ETA1)));

        int[][] tHat = new int[K][N];
        for (int i = 0; i < K; i++) {
            int[] acc = new int[N];
            for (int j = 0; j < K; j++) {
                polyAddAcc(acc, multiplyNtts(aHat[i][j], s[j]));
            }
            polyAddAcc(acc, e[i]);
            tHat[i] = acc;
        }

        byte[] ek = new byte[EK_BYTES];
        for (int i = 0; i < K; i++) byteEncode12(tHat[i], ek, i * 384);
        System.arraycopy(rho, 0, ek, K * 384, 32);

        byte[] dkPke = new byte[K * 384];
        for (int i = 0; i < K; i++) byteEncode12(s[i], dkPke, i * 384);

        byte[] hEk = Keccak.sha3_256(ek);
        byte[] dk = new byte[DK_BYTES];
        System.arraycopy(dkPke, 0, dk, 0, dkPke.length);
        System.arraycopy(ek, 0, dk, dkPke.length, EK_BYTES);
        System.arraycopy(hEk, 0, dk, dkPke.length + EK_BYTES, 32);
        System.arraycopy(z32, 0, dk, dkPke.length + EK_BYTES + 32, 32);
        return new KeyPair(ek, dk);
    }

    public static Encapsulation encapsulate(byte[] ek) {
        byte[] m = new byte[32];
        RNG.nextBytes(m);
        return encapsulateDeterministic(ek, m);
    }

    public static Encapsulation encapsulateDeterministic(byte[] ek, byte[] m32) {
        if (ek == null || ek.length != EK_BYTES) throw new IllegalArgumentException("bad ek length");
        if (m32 == null || m32.length != 32) throw new IllegalArgumentException("bad m length");

        byte[] hEk = Keccak.sha3_256(ek);
        byte[] gIn = new byte[64];
        System.arraycopy(m32, 0, gIn, 0, 32);
        System.arraycopy(hEk, 0, gIn, 32, 32);
        byte[] kr = Keccak.sha3_512(gIn);
        byte[] k = Arrays.copyOfRange(kr, 0, 32);
        byte[] r = Arrays.copyOfRange(kr, 32, 64);

        byte[] ct = kPkeEncrypt(ek, m32, r);
        return new Encapsulation(ct, k);
    }

    public static byte[] decapsulate(byte[] dk, byte[] ct) {
        if (dk == null || dk.length != DK_BYTES) throw new IllegalArgumentException("bad dk length");
        if (ct == null || ct.length != CT_BYTES) throw new IllegalArgumentException("bad ct length");

        int dkPkeLen = K * 384;
        byte[] dkPke = Arrays.copyOfRange(dk, 0, dkPkeLen);
        byte[] ek = Arrays.copyOfRange(dk, dkPkeLen, dkPkeLen + EK_BYTES);
        byte[] h = Arrays.copyOfRange(dk, dkPkeLen + EK_BYTES, dkPkeLen + EK_BYTES + 32);
        byte[] z = Arrays.copyOfRange(dk, dkPkeLen + EK_BYTES + 32, DK_BYTES);

        byte[] mPrime = kPkeDecrypt(dkPke, ct);
        byte[] gIn = new byte[64];
        System.arraycopy(mPrime, 0, gIn, 0, 32);
        System.arraycopy(h, 0, gIn, 32, 32);
        byte[] kr = Keccak.sha3_512(gIn);
        byte[] kPrime = Arrays.copyOfRange(kr, 0, 32);
        byte[] rPrime = Arrays.copyOfRange(kr, 32, 64);

        // Implicit rejection secret J(z || ct) = SHAKE256(z || ct, 32)
        byte[] jIn = new byte[32 + CT_BYTES];
        System.arraycopy(z, 0, jIn, 0, 32);
        System.arraycopy(ct, 0, jIn, 32, CT_BYTES);
        byte[] kBar = Keccak.shake256(jIn, 32);

        byte[] cPrime = kPkeEncrypt(ek, mPrime, rPrime);
        int diff = constantTimeDiff(ct, cPrime);
        // If diff == 0 return kPrime, else return kBar (constant-time select)
        int mask = (diff | -diff) >>> 31; // 1 if mismatch, 0 if match
        byte[] out = new byte[SS_BYTES];
        int keepPrime = mask - 1;         // 0xFFFFFFFF if match, 0 if mismatch
        int keepBar = ~keepPrime;
        for (int i = 0; i < SS_BYTES; i++) {
            out[i] = (byte) ((kPrime[i] & keepPrime) | (kBar[i] & keepBar));
        }
        return out;
    }

    // ---- K-PKE ----------------------------------------------------------------------------------

    private static byte[] kPkeEncrypt(byte[] ek, byte[] m32, byte[] r32) {
        int[][] tHat = new int[K][];
        for (int i = 0; i < K; i++) tHat[i] = byteDecode12(ek, i * 384);
        byte[] rho = Arrays.copyOfRange(ek, K * 384, EK_BYTES);

        int[][][] aHatT = generateMatrix(rho, true);
        int[][] rVec = new int[K][];
        int[][] e1 = new int[K][];
        int nonce = 0;
        for (int i = 0; i < K; i++) rVec[i] = ntt(samplePolyCbd(prf(r32, nonce++, ETA1)));
        for (int i = 0; i < K; i++) e1[i] = samplePolyCbd(prf(r32, nonce++, ETA2));
        int[] e2 = samplePolyCbd(prf(r32, nonce, ETA2));

        int[][] u = new int[K][];
        for (int i = 0; i < K; i++) {
            int[] acc = new int[N];
            for (int j = 0; j < K; j++) polyAddAcc(acc, multiplyNtts(aHatT[i][j], rVec[j]));
            u[i] = intt(acc);
            polyAddAcc(u[i], e1[i]);
        }

        int[] vHat = new int[N];
        for (int i = 0; i < K; i++) polyAddAcc(vHat, multiplyNtts(tHat[i], rVec[i]));
        int[] v = intt(vHat);
        polyAddAcc(v, e2);
        int[] mu = decompressPoly(byteDecode1(m32), 1);
        polyAddAcc(v, mu);

        byte[] ct = new byte[CT_BYTES];
        int uPolyBytes = (N * DU) / 8; // 320
        for (int i = 0; i < K; i++) {
            byteEncode(compressPoly(u[i], DU), DU, ct, i * uPolyBytes);
        }
        byteEncode(compressPoly(v, DV), DV, ct, K * uPolyBytes);
        return ct;
    }

    private static byte[] kPkeDecrypt(byte[] dkPke, byte[] ct) {
        int uPolyBytes = (N * DU) / 8;
        int[][] u = new int[K][];
        for (int i = 0; i < K; i++) {
            u[i] = decompressPoly(byteDecode(ct, i * uPolyBytes, DU), DU);
        }
        int[] v = decompressPoly(byteDecode(ct, K * uPolyBytes, DV), DV);

        int[][] sHat = new int[K][];
        for (int i = 0; i < K; i++) sHat[i] = byteDecode12(dkPke, i * 384);

        int[] acc = new int[N];
        for (int i = 0; i < K; i++) polyAddAcc(acc, multiplyNtts(sHat[i], ntt(u[i])));
        int[] sTu = intt(acc);

        int[] w = new int[N];
        for (int i = 0; i < N; i++) {
            int diff = (v[i] - sTu[i]) % Q;
            if (diff < 0) diff += Q;
            w[i] = diff;
        }
        return byteEncode1(compressPoly(w, 1));
    }

    // ---- Sampling & NTT -------------------------------------------------------------------------

    private static int[][][] generateMatrix(byte[] rho, boolean transpose) {
        int[][][] a = new int[K][K][];
        byte[] seed = new byte[34];
        System.arraycopy(rho, 0, seed, 0, 32);
        for (int i = 0; i < K; i++) {
            for (int j = 0; j < K; j++) {
                seed[32] = (byte) (transpose ? i : j);
                seed[33] = (byte) (transpose ? j : i);
                a[i][j] = sampleNtt(seed);
            }
        }
        return a;
    }

    private static int[] sampleNtt(byte[] seed34) {
        Keccak.Xof xof = new Keccak.Xof(168, seed34);
        int[] out = new int[N];
        int j = 0;
        byte[] buf = new byte[3];
        while (j < N) {
            xof.squeeze(buf, 0, 3);
            int b0 = buf[0] & 0xff, b1 = buf[1] & 0xff, b2 = buf[2] & 0xff;
            int d1 = b0 + 256 * (b1 & 0x0f);
            int d2 = (b1 >>> 4) + 16 * b2;
            if (d1 < Q) out[j++] = d1;
            if (d2 < Q && j < N) out[j++] = d2;
        }
        return out;
    }

    private static byte[] prf(byte[] sigma32, int nonce, int eta) {
        byte[] in = new byte[33];
        System.arraycopy(sigma32, 0, in, 0, 32);
        in[32] = (byte) nonce;
        return Keccak.shake256(in, 64 * eta);
    }

    private static int[] samplePolyCbd(byte[] bytes) {
        int[] f = new int[N];
        // eta = 2: 128 bytes -> 4 bits per coeff
        for (int i = 0; i < N; i++) {
            int bitOff = i * 4;
            int x = bit(bytes, bitOff) + bit(bytes, bitOff + 1);
            int y = bit(bytes, bitOff + 2) + bit(bytes, bitOff + 3);
            int val = x - y;
            if (val < 0) val += Q;
            f[i] = val;
        }
        return f;
    }

    private static int bit(byte[] arr, int idx) {
        return (arr[idx >>> 3] >>> (idx & 7)) & 1;
    }

    private static int[] ntt(int[] fIn) {
        int[] f = fIn.clone();
        int i = 1;
        for (int len = 128; len >= 2; len >>>= 1) {
            for (int start = 0; start < N; start += 2 * len) {
                int zeta = EXACT_ZETAS[i++];
                for (int j = start; j < start + len; j++) {
                    int t = (zeta * f[j + len]) % Q;
                    int fjl = f[j] - t;
                    if (fjl < 0) fjl += Q;
                    f[j + len] = fjl;
                    f[j] = (f[j] + t) % Q;
                }
            }
        }
        return f;
    }

    private static int[] intt(int[] fIn) {
        int[] f = fIn.clone();
        for (int len = 2; len <= 128; len <<= 1) {
            int base = N / (2 * len);
            for (int start = 0; start < N; start += 2 * len) {
                int zeta = EXACT_ZETAS[base + (start / (2 * len))];
                int invZeta = modPow(zeta, Q - 2, Q);
                for (int j = start; j < start + len; j++) {
                    int t = f[j];
                    f[j] = (t + f[j + len]) % Q;
                    int diff = t - f[j + len];
                    if (diff < 0) diff += Q;
                    f[j + len] = (invZeta * diff) % Q;
                }
            }
        }
        final int inv128 = 3303; // 128^-1 mod 3329
        for (int j = 0; j < N; j++) {
            f[j] = (f[j] * inv128) % Q;
        }
        return f;
    }

    private static int[] multiplyNtts(int[] f, int[] g) {
        int[] h = new int[N];
        for (int i = 0; i < 128; i++) {
            int idx0 = 2 * i;
            int idx1 = idx0 + 1;
            int a0 = f[idx0], a1 = f[idx1];
            int b0 = g[idx0], b1 = g[idx1];
            int gamma = GAMMAS[i];
            h[idx0] = ((a0 * b0) + (((a1 * b1) % Q) * gamma)) % Q;
            h[idx1] = ((a0 * b1) + (a1 * b0)) % Q;
        }
        return h;
    }

    private static void polyAddAcc(int[] acc, int[] b) {
        for (int i = 0; i < N; i++) {
            acc[i] = (acc[i] + b[i]) % Q;
        }
    }

    // ---- Compress & ByteEncode ------------------------------------------------------------------

    private static int[] compressPoly(int[] p, int d) {
        int[] out = new int[N];
        int mask = (1 << d) - 1;
        for (int i = 0; i < N; i++) {
            out[i] = (int) (((((long) p[i] << d) + (Q / 2)) / Q) & mask);
        }
        return out;
    }

    private static int[] decompressPoly(int[] p, int d) {
        int[] out = new int[N];
        int half = 1 << (d - 1);
        for (int i = 0; i < N; i++) {
            out[i] = (int) ((((long) p[i] * Q) + half) >>> d);
        }
        return out;
    }

    private static byte[] byteEncode1(int[] f) {
        byte[] out = new byte[32];
        for (int i = 0; i < N; i++) {
            if ((f[i] & 1) != 0) out[i >>> 3] |= (byte) (1 << (i & 7));
        }
        return out;
    }

    private static int[] byteDecode1(byte[] b) {
        int[] out = new int[N];
        for (int i = 0; i < N; i++) out[i] = (b[i >>> 3] >>> (i & 7)) & 1;
        return out;
    }

    private static void byteEncode12(int[] f, byte[] out, int off) {
        for (int i = 0; i < N; i += 2) {
            int a = f[i], b = f[i + 1];
            int p = off + (i / 2) * 3;
            out[p] = (byte) a;
            out[p + 1] = (byte) ((a >>> 8) | ((b & 0x0f) << 4));
            out[p + 2] = (byte) (b >>> 4);
        }
    }

    private static int[] byteDecode12(byte[] in, int off) {
        int[] f = new int[N];
        for (int i = 0; i < N; i += 2) {
            int p = off + (i / 2) * 3;
            int b0 = in[p] & 0xff, b1 = in[p + 1] & 0xff, b2 = in[p + 2] & 0xff;
            f[i] = b0 | ((b1 & 0x0f) << 8);
            f[i + 1] = (b1 >>> 4) | (b2 << 4);
        }
        return f;
    }

    private static void byteEncode(int[] f, int d, byte[] out, int off) {
        int bitPos = 0;
        for (int i = 0; i < N; i++) {
            int v = f[i];
            for (int k = 0; k < d; k++) {
                if (((v >>> k) & 1) != 0) {
                    out[off + (bitPos >>> 3)] |= (byte) (1 << (bitPos & 7));
                }
                bitPos++;
            }
        }
    }

    private static int[] byteDecode(byte[] in, int off, int d) {
        int[] f = new int[N];
        int bitPos = 0;
        for (int i = 0; i < N; i++) {
            int v = 0;
            for (int k = 0; k < d; k++) {
                int b = (in[off + (bitPos >>> 3)] >>> (bitPos & 7)) & 1;
                v |= (b << k);
                bitPos++;
            }
            f[i] = v;
        }
        return f;
    }

    private static int modPow(int base, int exp, int mod) {
        int res = 1;
        int b = base % mod;
        int e = exp;
        while (e > 0) {
            if ((e & 1) != 0) res = (res * b) % mod;
            b = (b * b) % mod;
            e >>>= 1;
        }
        return res;
    }

    private static int constantTimeDiff(byte[] a, byte[] b) {
        int d = 0;
        for (int i = 0; i < a.length; i++) d |= (a[i] ^ b[i]) & 0xff;
        return d;
    }
}
