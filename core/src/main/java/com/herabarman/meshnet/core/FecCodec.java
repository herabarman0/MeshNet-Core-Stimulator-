package com.herabarman.meshnet.core;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Systematic GF(2^8) Reed-Solomon Erasure Coding for lossy mesh/LoRa/UDP radios.
 * Splits a payload into {@code dataShards} equal chunks and produces {@code parityShards}
 * recovery chunks so ANY {@code parityShards} lost packets can be reconstructed without waiting
 * for round-trip ARQ retries.
 *
 * Primitive polynomial: x^8 + x^4 + x^3 + x^2 + 1 (0x11d).
 */
public final class FecCodec {
    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11d;
        }
        for (int i = 255; i < 512; i++) {
            EXP[i] = EXP[i - 255];
        }
    }

    private FecCodec() {}

    public static final class EncodedBlock {
        public final int dataShards;
        public final int parityShards;
        public final int originalLength;
        public final int shardLength;
        public final byte[][] shards;

        EncodedBlock(int dataShards, int parityShards, int originalLength, int shardLength, byte[][] shards) {
            this.dataShards = dataShards;
            this.parityShards = parityShards;
            this.originalLength = originalLength;
            this.shardLength = shardLength;
            this.shards = shards;
        }
    }

    /** Encodes {@code payload} into {@code dataShards + parityShards} shards. */
    public static EncodedBlock encode(byte[] payload, int dataShards, int parityShards) {
        if (payload == null) throw new IllegalArgumentException("payload");
        if (dataShards < 1 || dataShards > 32) throw new IllegalArgumentException("dataShards");
        if (parityShards < 0 || parityShards > 16 || dataShards + parityShards > 48) {
            throw new IllegalArgumentException("parityShards");
        }
        int totalLen = 4 + payload.length;
        int shardLen = Math.max(1, (totalLen + dataShards - 1) / dataShards);
        byte[] framed = ByteBuffer.allocate(dataShards * shardLen).putInt(payload.length).put(payload).array();

        int totalShards = dataShards + parityShards;
        byte[][] shards = new byte[totalShards][shardLen];
        for (int i = 0; i < dataShards; i++) {
            System.arraycopy(framed, i * shardLen, shards[i], 0, shardLen);
        }
        // Cauchy-style invertible matrix rows: M[r][c] = 1 / (x_r ^ y_c), x_r = r, y_c = parityShards + c
        for (int r = 0; r < parityShards; r++) {
            byte[] out = shards[dataShards + r];
            for (int c = 0; c < dataShards; c++) {
                int coeff = gfInv(r ^ (parityShards + c));
                byte[] src = shards[c];
                for (int k = 0; k < shardLen; k++) {
                    out[k] ^= (byte) gfMul(coeff, src[k] & 0xff);
                }
            }
        }
        return new EncodedBlock(dataShards, parityShards, payload.length, shardLen, shards);
    }

    /**
     * Reconstructs the original payload from any {@code dataShards} present shards.
     * Missing shards must be {@code null} in {@code receivedShards}.
     */
    public static byte[] decode(byte[][] receivedShards, int dataShards, int parityShards) {
        if (receivedShards == null || receivedShards.length != dataShards + parityShards) {
            throw new IllegalArgumentException("shard array length mismatch");
        }
        int shardLen = -1;
        List<Integer> present = new ArrayList<>(dataShards);
        for (int i = 0; i < receivedShards.length && present.size() < dataShards; i++) {
            if (receivedShards[i] != null) {
                if (shardLen < 0) shardLen = receivedShards[i].length;
                else if (receivedShards[i].length != shardLen) throw new IllegalArgumentException("inconsistent shard len");
                present.add(i);
            }
        }
        if (present.size() < dataShards || shardLen <= 0) {
            return null;
        }

        // Fast path: all data shards arrived intact
        boolean allDataPresent = true;
        for (int i = 0; i < dataShards; i++) {
            if (receivedShards[i] == null) {
                allDataPresent = false;
                break;
            }
        }
        byte[][] recovered = new byte[dataShards][shardLen];
        if (allDataPresent) {
            for (int i = 0; i < dataShards; i++) recovered[i] = receivedShards[i];
        } else {
            int[][] mat = new int[dataShards][dataShards];
            for (int row = 0; row < dataShards; row++) {
                int idx = present.get(row);
                if (idx < dataShards) {
                    mat[row][idx] = 1;
                } else {
                    int r = idx - dataShards;
                    for (int col = 0; col < dataShards; col++) {
                        mat[row][col] = gfInv(r ^ (parityShards + col));
                    }
                }
            }
            int[][] inv = invertMatrix(mat);
            for (int row = 0; row < dataShards; row++) {
                byte[] dst = recovered[row];
                for (int col = 0; col < dataShards; col++) {
                    int coeff = inv[row][col];
                    if (coeff == 0) continue;
                    byte[] src = receivedShards[present.get(col)];
                    for (int k = 0; k < shardLen; k++) {
                        dst[k] ^= (byte) gfMul(coeff, src[k] & 0xff);
                    }
                }
            }
        }

        byte[] framed = new byte[dataShards * shardLen];
        for (int i = 0; i < dataShards; i++) {
            System.arraycopy(recovered[i], 0, framed, i * shardLen, shardLen);
        }
        int origLen = ByteBuffer.wrap(framed).getInt();
        if (origLen < 0 || 4 + origLen > framed.length) {
            return null;
        }
        return Arrays.copyOfRange(framed, 4, 4 + origLen);
    }

    private static int gfMul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return EXP[LOG[a] + LOG[b]];
    }

    private static int gfInv(int a) {
        if (a == 0) throw new IllegalArgumentException("divide by zero in GF(256)");
        return EXP[255 - LOG[a]];
    }

    private static int[][] invertMatrix(int[][] m) {
        int n = m.length;
        int[][] aug = new int[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(m[i], 0, aug[i], 0, n);
            aug[i][n + i] = 1;
        }
        for (int col = 0; col < n; col++) {
            int pivot = -1;
            for (int row = col; row < n; row++) {
                if (aug[row][col] != 0) {
                    pivot = row;
                    break;
                }
            }
            if (pivot < 0) throw new IllegalStateException("singular FEC matrix");
            if (pivot != col) {
                int[] tmp = aug[col];
                aug[col] = aug[pivot];
                aug[pivot] = tmp;
            }
            int invPivot = gfInv(aug[col][col]);
            for (int j = 0; j < 2 * n; j++) aug[col][j] = gfMul(aug[col][j], invPivot);
            for (int row = 0; row < n; row++) {
                if (row == col || aug[row][col] == 0) continue;
                int factor = aug[row][col];
                for (int j = 0; j < 2 * n; j++) {
                    aug[row][j] ^= gfMul(factor, aug[col][j]);
                }
            }
        }
        int[][] out = new int[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(aug[i], n, out[i], 0, n);
        return out;
    }
}
