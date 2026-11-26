package com.herabarman.meshnet.android;

import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import com.herabarman.meshnet.kit.VoiceCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * AMR-NB through the phone's own MediaCodec: 4.75 kbit/s, 8 kHz, three 20 ms frames per 60 ms packet, about 44
 * bytes per packet instead of the 244 of ADPCM. Android requires AMR-NB on phones, so it should be on every device.
 * Not run on a phone yet (see android-kit/README.md): use {@link #works()}, which tests it on the phone, and fall back to AdpcmCodec.
 *
 * Packet block: 1 byte frame count, then per frame 1 byte length + the frame exactly as MediaCodec produced it.
 * MediaCodec's AMR output is the same form its decoder takes in (that is how .3gp files play), so the frames are
 * passed through untouched. One instance is either an encoder or a decoder, created on first use.
 * Not thread-safe: PushToTalk calls encode from the capture thread and decode from the engine thread, on
 * separate instances.
 */
public final class AmrNbCodec implements VoiceCodec {
    public static final int ID = 2;
    public static final int RATE = 8000;
    static final int FRAME = 160;                                // 20 ms
    static final int FRAMES = 3;
    static final String MIME = "audio/3gpp";
    private static final long TIMEOUT_US = 20_000;
    private static final int MAX_WAIT_LOOPS = 8;

    public static final Factory FACTORY = new Factory() {
        @Override public int id() { return ID; }
        @Override public VoiceCodec create() { return new AmrNbCodec(); }
    };

    private MediaCodec enc;
    private final java.util.ArrayDeque<byte[]> ready = new java.util.ArrayDeque<>();   // encoded frames not yet sent
    private MediaCodec dec;
    private long pts;
    private short[] pend = new short[4 * FRAME * FRAMES];   // decoded samples not yet handed out
    private int pendLen;
    private short[] last;
    private int lossRun;

    /** True if this phone lists an AMR-NB encoder and decoder. */
    public static boolean isAvailable() {
        try {
            boolean encoder = false, decoder = false;
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (android.media.MediaCodecInfo info : list.getCodecInfos()) {
                for (String type : info.getSupportedTypes()) {
                    if (type.equalsIgnoreCase(MIME)) {
                        if (info.isEncoder()) encoder = true; else decoder = true;
                    }
                }
            }
            return encoder && decoder;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static volatile Boolean works;

    /**
     * Runs the codec on this phone: a tone goes in, the same tone must come out. This is the check that stands in
     * for "tested on a phone": if the phone's MediaCodec behaves differently from what this class assumes, it
     * answers false and the app uses ADPCM. Takes about 100 ms the first time, then it is remembered.
     */
    public static boolean works() {
        Boolean w = works;
        if (w == null) {
            w = isAvailable() && com.herabarman.meshnet.kit.VoiceSelfTest.codecWorks(FACTORY);
            works = w;
        }
        return w;
    }

    @Override public int id() { return ID; }
    @Override public int sampleRate() { return RATE; }
    @Override public int blockSamples() { return FRAME * FRAMES; }

    private MediaCodec openEncoder() throws IOException {
        MediaCodec c = MediaCodec.createEncoderByType(MIME);
        MediaFormat f = MediaFormat.createAudioFormat(MIME, RATE, 1);
        f.setInteger(MediaFormat.KEY_BIT_RATE, 4750);
        c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        c.start();
        return c;
    }

    private MediaCodec openDecoder() throws IOException {
        MediaCodec c = MediaCodec.createDecoderByType(MIME);
        c.configure(MediaFormat.createAudioFormat(MIME, RATE, 1), null, null, 0);
        c.start();
        return c;
    }

    /**
     * @return null if fewer than three frames are ready. Some codecs hold a frame back; the frames then stay queued
     *         and the next block carries them, so nothing is lost (the audio is just one frame later).
     */
    @Override
    public byte[] encode(short[] pcm) {
        if (pcm.length != blockSamples()) throw new IllegalArgumentException("block must be " + blockSamples() + " samples");
        try {
            if (enc == null) enc = openEncoder();
            for (int f = 0; f < FRAMES; f++) {
                feed(enc, pcm, f * FRAME);
                drainEncoder();
            }
            for (int wait = 0; ready.size() < FRAMES && wait < MAX_WAIT_LOOPS; wait++) drainEncoder();
            while (ready.size() > 2 * FRAMES) ready.poll();      // far behind: drop the oldest, stay live
            if (ready.size() < FRAMES) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream(48);
            out.write(FRAMES);
            for (int f = 0; f < FRAMES; f++) {
                byte[] frame = ready.poll();
                out.write(frame.length);
                out.write(frame, 0, frame.length);
            }
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            release(enc);
            enc = null;
            ready.clear();
            return null;
        }
    }

    private void feed(MediaCodec c, short[] pcm, int offset) {
        int idx = c.dequeueInputBuffer(TIMEOUT_US);
        if (idx < 0) throw new IllegalStateException("codec input busy");
        ByteBuffer in = c.getInputBuffer(idx);
        in.clear();
        in.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < FRAME; i++) in.putShort(pcm[offset + i]);
        c.queueInputBuffer(idx, 0, FRAME * 2, pts, 0);
        pts += 20_000;
    }

    /** Moves whatever frames the codec has finished into the ready queue. */
    private void drainEncoder() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean first = true;
        while (true) {
            int idx = enc.dequeueOutputBuffer(info, first ? TIMEOUT_US : 0);
            first = false;
            if (idx < 0) {
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return;
                continue;                                        // format / buffers changed: ask again
            }
            if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                ByteBuffer b = enc.getOutputBuffer(idx);
                byte[] frame = new byte[info.size];
                b.position(info.offset);
                b.get(frame);
                ready.add(frame);
            }
            enc.releaseOutputBuffer(idx, false);
        }
    }

    /**
     * @return null if the codec has not produced a whole block yet (a decoder that holds frames back gives the
     *         first block one call late; the samples stay queued, nothing is lost) or if the input is invalid
     */
    @Override
    public short[] decode(byte[] block) {
        if (block == null || block.length < 2 || (block[0] & 0xFF) != FRAMES) return null;
        try {
            if (dec == null) dec = openDecoder();
            int pos = 1;
            for (int f = 0; f < FRAMES; f++) {
                if (pos >= block.length) return null;
                int len = block[pos++] & 0xFF;
                if (len == 0 || pos + len > block.length) return null;
                int idx = dec.dequeueInputBuffer(TIMEOUT_US);
                if (idx < 0) return null;
                ByteBuffer in = dec.getInputBuffer(idx);
                in.clear();
                in.put(block, pos, len);
                dec.queueInputBuffer(idx, 0, len, pts, 0);
                pts += 20_000;
                pos += len;
                drainDecoder();
            }
            for (int wait = 0; pendLen < blockSamples() && wait < MAX_WAIT_LOOPS; wait++) drainDecoder();
            if (pendLen < blockSamples()) return null;
            short[] pcm = new short[blockSamples()];
            System.arraycopy(pend, 0, pcm, 0, pcm.length);
            pendLen -= pcm.length;
            System.arraycopy(pend, pcm.length, pend, 0, pendLen);
            last = pcm;
            lossRun = 0;
            return pcm;
        } catch (IOException | RuntimeException e) {
            release(dec);
            dec = null;
            pendLen = 0;
            return null;
        }
    }

    /** Appends whatever samples the decoder has finished to pend. */
    private void drainDecoder() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean first = true;
        while (true) {
            int idx = dec.dequeueOutputBuffer(info, first ? TIMEOUT_US : 0);
            first = false;
            if (idx < 0) {
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return;
                continue;
            }
            ByteBuffer b = dec.getOutputBuffer(idx);
            b.position(info.offset);
            b.order(ByteOrder.LITTLE_ENDIAN);
            int n = info.size / 2;
            if (pendLen + n > pend.length) {                     // far behind: drop the oldest samples, stay live
                int drop = pendLen + n - pend.length;
                System.arraycopy(pend, drop, pend, 0, pendLen - drop);
                pendLen -= drop;
            }
            for (int i = 0; i < n; i++) pend[pendLen++] = b.getShort();
            dec.releaseOutputBuffer(idx, false);
        }
    }

    @Override
    public short[] conceal() {
        short[] out = new short[blockSamples()];
        if (last != null && lossRun < 3) {                       // same rule as AdpcmCodec: quieter repeat, then silence
            int gain = 256 >> (lossRun + 1);
            for (int i = 0; i < out.length; i++) out[i] = (short) ((last[i] * gain) >> 8);
        }
        lossRun++;
        return out;
    }

    private static void release(MediaCodec c) {
        if (c == null) return;
        try { c.stop(); } catch (RuntimeException ignored) { }
        try { c.release(); } catch (RuntimeException ignored) { }
    }
}
