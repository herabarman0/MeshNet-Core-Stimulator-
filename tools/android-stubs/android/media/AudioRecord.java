package android.media;
public class AudioRecord {
    public static final int STATE_INITIALIZED = 1;
    public AudioRecord(int source, int rate, int channel, int encoding, int bufferBytes) { }
    public static int getMinBufferSize(int rate, int channel, int encoding) { return 0; }
    public int getState() { return 0; }
    public int getAudioSessionId() { return 0; }
    public void startRecording() { }
    public void stop() { }
    public void release() { }
    public int read(short[] audio, int offset, int size) { return 0; }
}
