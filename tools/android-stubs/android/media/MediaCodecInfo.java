package android.media;
public class MediaCodecInfo {
    private final boolean encoder;
    public MediaCodecInfo() { this(false); }
    public MediaCodecInfo(boolean encoder) { this.encoder = encoder; }
    public String[] getSupportedTypes() { return new String[]{"audio/3gpp"}; }
    public boolean isEncoder() { return encoder; }
}
