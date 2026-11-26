package com.herabarman.meshnet.kit;

/**
 * IMA ADPCM, 4 bits per sample, 8 kHz mono, 60 ms per block: 32 kbit/s of audio, 243 bytes per packet.
 * Pure Java and works on every phone, so it is the codec that is always there. Voice quality is telephone
 * level. The AMR-NB codec in android-kit needs about a seventh of the bandwidth; use that where it is available.
 * Every block starts with the predictor and step index, so blocks are independent of each other.
 */
public final class AdpcmCodec implements VoiceCodec {
    public static final int ID = 1;
    public static final int RATE = 8000;
    public static final int BLOCK_MS = 60;
    public static final int BLOCK = RATE * BLOCK_MS / 1000;      // 480 samples
    public static final int BYTES = 3 + BLOCK / 2;               // predictor(2) index(1) + 479 nibbles (240 bytes)

    public static final Factory FACTORY = new Factory() {
        @Override public int id() { return ID; }
        @Override public VoiceCodec create() { return new AdpcmCodec(); }
    };

    private static final int[] STEP = {
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97,
            107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
            876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428,
            4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350,
            22385, 24623, 27086, 29794, 32767};
    private static final int[] INDEX_ADJUST = {-1, -1, -1, -1, 2, 4, 6, 8};

    private short[] last;
    private int lossRun;

    @Override public int id() { return ID; }
    @Override public int sampleRate() { return RATE; }
    @Override public int blockSamples() { return BLOCK; }

    @Override
    public byte[] encode(short[] pcm) {
        if (pcm.length != BLOCK) throw new IllegalArgumentException("block must be " + BLOCK + " samples");
        byte[] out = new byte[BYTES];
        int predictor = pcm[0];
        int index = 0;
        out[0] = (byte) predictor;
        out[1] = (byte) (predictor >> 8);
        // choose the starting step from the first difference, so the first samples are not coarse
        int d0 = Math.abs(pcm[1] - pcm[0]);
        while (index < 88 && STEP[index] * 2 < d0) index++;
        out[2] = (byte) index;
        int pos = 3;
        boolean high = false;
        for (int i = 1; i < BLOCK; i++) {
            int step = STEP[index];
            int diff = pcm[i] - predictor;
            int nibble = 0;
            if (diff < 0) { nibble = 8; diff = -diff; }
            int delta = step >> 3;
            if (diff >= step) { nibble |= 4; diff -= step; delta += step; }
            if (diff >= (step >> 1)) { nibble |= 2; diff -= step >> 1; delta += step >> 1; }
            if (diff >= (step >> 2)) { nibble |= 1; delta += step >> 2; }
            predictor += (nibble & 8) != 0 ? -delta : delta;
            if (predictor > 32767) predictor = 32767;
            if (predictor < -32768) predictor = -32768;
            index += INDEX_ADJUST[nibble & 7];
            if (index < 0) index = 0;
            if (index > 88) index = 88;
            if (!high) { out[pos] = (byte) nibble; high = true; }
            else { out[pos] |= (byte) (nibble << 4); pos++; high = false; }
        }
        return out;
    }

    @Override
    public short[] decode(byte[] block) {
        if (block == null || block.length != BYTES) return null;
        int predictor = (short) ((block[0] & 0xFF) | (block[1] << 8));
        int index = block[2] & 0xFF;
        if (index > 88) return null;
        short[] pcm = new short[BLOCK];
        pcm[0] = (short) predictor;
        int pos = 3;
        boolean high = false;
        for (int i = 1; i < BLOCK; i++) {
            int nibble = high ? (block[pos++] >> 4) & 0x0F : block[pos] & 0x0F;
            high = !high;
            int step = STEP[index];
            int delta = step >> 3;
            if ((nibble & 4) != 0) delta += step;
            if ((nibble & 2) != 0) delta += step >> 1;
            if ((nibble & 1) != 0) delta += step >> 2;
            predictor += (nibble & 8) != 0 ? -delta : delta;
            if (predictor > 32767) predictor = 32767;
            if (predictor < -32768) predictor = -32768;
            index += INDEX_ADJUST[nibble & 7];
            if (index < 0) index = 0;
            if (index > 88) index = 88;
            pcm[i] = (short) predictor;
        }
        last = pcm;
        lossRun = 0;
        return pcm;
    }

    @Override
    public short[] conceal() {
        short[] out = new short[BLOCK];
        if (last != null && lossRun < 3) {                       // repeat the last block, quieter each time, then silence
            int gain = 256 >> (lossRun + 1);
            for (int i = 0; i < BLOCK; i++) out[i] = (short) ((last[i] * gain) >> 8);
        }
        lossRun++;
        return out;
    }
}
