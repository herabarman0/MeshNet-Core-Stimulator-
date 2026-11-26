package android.media;
public class MediaCodecList {
    public static final int REGULAR_CODECS = 0;
    public MediaCodecList(int kind) { }
    public MediaCodecInfo[] getCodecInfos() {
        MediaCodecInfo enc = new MediaCodecInfo(true), dec = new MediaCodecInfo(false);
        return new MediaCodecInfo[]{enc, dec};
    }
}
