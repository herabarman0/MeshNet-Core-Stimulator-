package com.herabarman.meshnet.core;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * High-speed LZ4-style payload compressor designed for low-bandwidth transports (BLE, LoRa, HF).
 * Runs BEFORE encryption (compress-then-encrypt) with a 1-byte mode header + 4-byte original length
 * when compressed, or automatic raw bypass if input is incompressible so size never balloons.
 */
public final class PayloadCompressor {
    private static final byte FLAG_RAW = 0x00;
    private static final byte FLAG_LZ4 = 0x01;
    private static final int HASH_BITS = 12;
    private static final int HASH_SIZE = 1 << HASH_BITS;
    private static final int MIN_MATCH = 4;
    private static final int MAX_OFFSET = 0xFFFF;

    private PayloadCompressor() {}

    /** Compresses {@code input} using a fast single-pass LZ4 match table; falls back to raw if not smaller. */
    public static byte[] compress(byte[] input) {
        if (input == null) return new byte[]{FLAG_RAW};
        if (input.length < 16) return rawWrap(input);

        byte[] lz4 = compressLz4Block(input);
        if (1 + 4 + lz4.length >= 1 + input.length) {
            return rawWrap(input);
        }
        byte[] out = new byte[5 + lz4.length];
        out[0] = FLAG_LZ4;
        out[1] = (byte) (input.length >>> 24);
        out[2] = (byte) (input.length >>> 16);
        out[3] = (byte) (input.length >>> 8);
        out[4] = (byte) input.length;
        System.arraycopy(lz4, 0, out, 5, lz4.length);
        return out;
    }

    /** Decompresses bytes created by {@link #compress(byte[])}, or returns {@code null} on malformed data. */
    public static byte[] decompress(byte[] packed) {
        if (packed == null || packed.length == 0) return null;
        byte flag = packed[0];
        if (flag == FLAG_RAW) {
            return Arrays.copyOfRange(packed, 1, packed.length);
        }
        if (flag != FLAG_LZ4 || packed.length < 5) return null;
        int origLen = ((packed[1] & 0xFF) << 24)
                | ((packed[2] & 0xFF) << 16)
                | ((packed[3] & 0xFF) << 8)
                | (packed[4] & 0xFF);
        if (origLen < 0 || origLen > 8 * 1024 * 1024) return null;
        return decompressLz4Block(packed, 5, packed.length, origLen);
    }

    private static byte[] rawWrap(byte[] input) {
        byte[] out = new byte[1 + input.length];
        out[0] = FLAG_RAW;
        System.arraycopy(input, 0, out, 1, input.length);
        return out;
    }

    private static byte[] compressLz4Block(byte[] src) {
        int n = src.length;
        int[] table = new int[HASH_SIZE];
        Arrays.fill(table, -1);

        ByteArrayOutputStream out = new ByteArrayOutputStream(n);
        int anchor = 0;
        int ip = 0;
        int limit = n - MIN_MATCH;

        while (ip <= limit) {
            int seq = read32(src, ip);
            int h = ((seq * 0x9E3779B1) >>> (32 - HASH_BITS)) & (HASH_SIZE - 1);
            int matchPos = table[h];
            table[h] = ip;

            if (matchPos >= 0 && (ip - matchPos) <= MAX_OFFSET && read32(src, matchPos) == seq) {
                int matchLen = MIN_MATCH;
                while (ip + matchLen < n && src[matchPos + matchLen] == src[ip + matchLen]) {
                    matchLen++;
                }
                int litLen = ip - anchor;
                int tokenLit = Math.min(15, litLen);
                int tokenMatch = Math.min(15, matchLen - MIN_MATCH);
                out.write((tokenLit << 4) | tokenMatch);

                if (litLen >= 15) writeExtraLen(out, litLen - 15);
                if (litLen > 0) out.write(src, anchor, litLen);

                int offset = ip - matchPos;
                out.write(offset & 0xFF);
                out.write((offset >>> 8) & 0xFF);

                if (matchLen - MIN_MATCH >= 15) writeExtraLen(out, matchLen - MIN_MATCH - 15);

                ip += matchLen;
                anchor = ip;
            } else {
                ip++;
            }
        }

        int tailLit = n - anchor;
        out.write(Math.min(15, tailLit) << 4);
        if (tailLit >= 15) writeExtraLen(out, tailLit - 15);
        if (tailLit > 0) out.write(src, anchor, tailLit);
        return out.toByteArray();
    }

    private static byte[] decompressLz4Block(byte[] src, int start, int end, int origLen) {
        byte[] dst = new byte[origLen];
        int ip = start;
        int op = 0;

        while (ip < end) {
            int token = src[ip++] & 0xFF;
            int litLen = token >>> 4;
            if (litLen == 15) {
                int add;
                do {
                    if (ip >= end) return null;
                    add = src[ip++] & 0xFF;
                    litLen += add;
                } while (add == 255);
            }
            if (ip + litLen > end || op + litLen > origLen) return null;
            System.arraycopy(src, ip, dst, op, litLen);
            ip += litLen;
            op += litLen;

            if (ip >= end) break; // last sequence has literals only

            if (ip + 2 > end) return null;
            int offset = (src[ip++] & 0xFF) | ((src[ip++] & 0xFF) << 8);
            if (offset == 0 || offset > op) return null;

            int matchLen = (token & 0x0F) + MIN_MATCH;
            if ((token & 0x0F) == 15) {
                int add;
                do {
                    if (ip >= end) return null;
                    add = src[ip++] & 0xFF;
                    matchLen += add;
                } while (add == 255);
            }
            if (op + matchLen > origLen) return null;
            for (int i = 0; i < matchLen; i++) {
                dst[op + i] = dst[op - offset + i];
            }
            op += matchLen;
        }
        return op == origLen ? dst : null;
    }

    private static void writeExtraLen(ByteArrayOutputStream out, int len) {
        int rem = len;
        while (rem >= 255) {
            out.write(255);
            rem -= 255;
        }
        out.write(rem);
    }

    private static int read32(byte[] a, int i) {
        return (a[i] & 0xFF)
                | ((a[i + 1] & 0xFF) << 8)
                | ((a[i + 2] & 0xFF) << 16)
                | ((a[i + 3] & 0xFF) << 24);
    }
}
