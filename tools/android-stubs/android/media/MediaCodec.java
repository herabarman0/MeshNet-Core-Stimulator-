package android.media;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * STUB with a toy "codec" so AmrNbCodec's own logic (framing, draining, lag) can be run on a normal JVM:
 * a frame is 1 header byte + the high byte of each of the 160 samples. NOT AMR; it only has the same shape
 * (20 ms in, one frame out, decoder takes the same frame back). LAG = how many frames the codec holds back.
 */
public class MediaCodec {
    public static final int CONFIGURE_FLAG_ENCODE = 1, INFO_TRY_AGAIN_LATER = -1, BUFFER_FLAG_CODEC_CONFIG = 2;
    public static int LAG = 0;
    /** test knob: the decoder returns silence, like a codec that does not understand the frames */
    public static boolean DEAF = false;
    public static class BufferInfo { public int offset, size, flags; }
    private final boolean encoder;
    private ByteBuffer in;
    private byte[] out;
    private final ArrayDeque<byte[]> pending = new ArrayDeque<>();
    private final ArrayDeque<byte[]> done = new ArrayDeque<>();
    private MediaCodec(boolean encoder) { this.encoder = encoder; }
    public static MediaCodec createEncoderByType(String type) throws IOException { return new MediaCodec(true); }
    public static MediaCodec createDecoderByType(String type) throws IOException { return new MediaCodec(false); }
    public void configure(MediaFormat format, Object surface, Object crypto, int flags) { }
    public void start() { }
    public void stop() { }
    public void release() { }
    public int dequeueInputBuffer(long timeoutUs) { return 0; }
    public ByteBuffer getInputBuffer(int index) { in = ByteBuffer.allocate(4096); return in; }
    public void queueInputBuffer(int index, int offset, int size, long ptsUs, int flags) {
        byte[] data = new byte[size];
        System.arraycopy(in.array(), offset, data, 0, size);
        byte[] result;
        if (encoder) {
            result = new byte[161];
            result[0] = 0x3C;
            for (int i = 0; i < 160; i++) result[1 + i] = data[i * 2 + 1];      // high byte, little endian
        } else {
            result = new byte[320];
            for (int i = 0; i < 160; i++) { result[i * 2] = 0; result[i * 2 + 1] = DEAF ? 0 : data[1 + i]; }
        }
        pending.add(result);
        while (pending.size() > LAG) done.add(pending.poll());
    }
    public int dequeueOutputBuffer(BufferInfo info, long timeoutUs) {
        if (done.isEmpty()) return INFO_TRY_AGAIN_LATER;
        out = done.poll();
        info.offset = 0;
        info.size = out.length;
        info.flags = 0;
        return 0;
    }
    public ByteBuffer getOutputBuffer(int index) { return ByteBuffer.wrap(out); }
    public void releaseOutputBuffer(int index, boolean render) { }
}
