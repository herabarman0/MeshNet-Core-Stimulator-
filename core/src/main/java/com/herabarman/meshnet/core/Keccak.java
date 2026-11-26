package com.herabarman.meshnet.core;

import java.util.Arrays;

/**
 * Pure-Java FIPS 202 Keccak-f[1600] sponge implementing SHA3-256, SHA3-512, SHAKE128, and SHAKE256.
 * Works identically on Android API 24+, Java 11, and desktop JVMs without provider differences.
 */
public final class Keccak {
    private static final long[] RC = {
        0x0000000000000001L, 0x0000000000008082L, 0x800000000000808aL, 0x8000000080008000L,
        0x000000000000808bL, 0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L,
        0x000000000000008aL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
        0x000000008000808bL, 0x800000000000008bL, 0x8000000000008089L, 0x8000000000008003L,
        0x8000000000008002L, 0x8000000000000080L, 0x000000000000800aL, 0x800000008000000aL,
        0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L
    };

    private static final int[] RHO = {
        0, 1, 62, 28, 27,
        36, 44, 6, 55, 20,
        3, 10, 43, 25, 39,
        41, 45, 15, 21, 8,
        18, 2, 61, 56, 14
    };

    private static final int[] PI = {
        0, 10, 20, 5, 15,
        16, 1, 11, 21, 6,
        7, 17, 2, 12, 22,
        23, 8, 18, 3, 13,
        14, 24, 9, 19, 4
    };

    private Keccak() {}

    public static byte[] sha3_256(byte[] input) {
        return sponge(136, (byte) 0x06, input, 32);
    }

    public static byte[] sha3_512(byte[] input) {
        return sponge(72, (byte) 0x06, input, 64);
    }

    public static byte[] shake128(byte[] input, int outLen) {
        return sponge(168, (byte) 0x1f, input, outLen);
    }

    public static byte[] shake256(byte[] input, int outLen) {
        return sponge(136, (byte) 0x1f, input, outLen);
    }

    /** Stateful XOF reader for SHAKE128/SHAKE256 incremental squeezing. */
    public static final class Xof {
        private final long[] state = new long[25];
        private final int rate;
        private int offset;

        public Xof(int rate, byte[] seed) {
            this.rate = rate;
            byte[] padded = new byte[((seed.length + rate) / rate) * rate];
            System.arraycopy(seed, 0, padded, 0, seed.length);
            padded[seed.length] ^= (byte) 0x1f;
            padded[padded.length - 1] ^= (byte) 0x80;
            for (int off = 0; off < padded.length; off += rate) {
                absorbBlock(state, padded, off, rate);
                permute(state);
            }
            this.offset = 0;
        }

        public void squeeze(byte[] out, int off, int len) {
            int pos = 0;
            while (pos < len) {
                if (offset == rate) {
                    permute(state);
                    offset = 0;
                }
                int take = Math.min(len - pos, rate - offset);
                for (int i = 0; i < take; i++) {
                    int bIdx = offset + i;
                    long lane = state[bIdx >>> 3];
                    out[off + pos + i] = (byte) (lane >>> ((bIdx & 7) * 8));
                }
                offset += take;
                pos += take;
            }
        }
    }

    private static byte[] sponge(int rate, byte domain, byte[] input, int outLen) {
        long[] state = new long[25];
        int blocks = ((input.length + 1) + rate - 1) / rate;
        if (blocks == 0) blocks = 1;
        byte[] padded = new byte[blocks * rate];
        System.arraycopy(input, 0, padded, 0, input.length);
        padded[input.length] ^= domain;
        padded[padded.length - 1] ^= (byte) 0x80;

        for (int off = 0; off < padded.length; off += rate) {
            absorbBlock(state, padded, off, rate);
            permute(state);
        }

        byte[] out = new byte[outLen];
        int pos = 0;
        while (pos < outLen) {
            int take = Math.min(outLen - pos, rate);
            for (int i = 0; i < take; i++) {
                long lane = state[i >>> 3];
                out[pos + i] = (byte) (lane >>> ((i & 7) * 8));
            }
            pos += take;
            if (pos < outLen) {
                permute(state);
            }
        }
        Arrays.fill(padded, (byte) 0);
        return out;
    }

    private static void absorbBlock(long[] state, byte[] data, int off, int rate) {
        int lanes = rate >>> 3;
        for (int i = 0; i < lanes; i++) {
            int b = off + (i << 3);
            long v = ((long) data[b] & 0xffL)
                    | (((long) data[b + 1] & 0xffL) << 8)
                    | (((long) data[b + 2] & 0xffL) << 16)
                    | (((long) data[b + 3] & 0xffL) << 24)
                    | (((long) data[b + 4] & 0xffL) << 32)
                    | (((long) data[b + 5] & 0xffL) << 40)
                    | (((long) data[b + 6] & 0xffL) << 48)
                    | (((long) data[b + 7] & 0xffL) << 56);
            state[i] ^= v;
        }
    }

    private static void permute(long[] a) {
        long[] c = new long[5];
        long[] d = new long[5];
        long[] b = new long[25];
        for (int round = 0; round < 24; round++) {
            for (int x = 0; x < 5; x++) {
                c[x] = a[x] ^ a[x + 5] ^ a[x + 10] ^ a[x + 15] ^ a[x + 20];
            }
            for (int x = 0; x < 5; x++) {
                d[x] = c[(x + 4) % 5] ^ Long.rotateLeft(c[(x + 1) % 5], 1);
            }
            for (int i = 0; i < 25; i++) {
                a[i] ^= d[i % 5];
            }
            for (int i = 0; i < 25; i++) {
                b[PI[i]] = Long.rotateLeft(a[i], RHO[i]);
            }
            for (int y = 0; y < 25; y += 5) {
                for (int x = 0; x < 5; x++) {
                    a[y + x] = b[y + x] ^ ((~b[y + ((x + 1) % 5)]) & b[y + ((x + 2) % 5)]);
                }
            }
            a[0] ^= RC[round];
        }
    }
}
