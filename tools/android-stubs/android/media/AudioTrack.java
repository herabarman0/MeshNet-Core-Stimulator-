package android.media;
public class AudioTrack {
    public static final int MODE_STREAM = 1, STATE_INITIALIZED = 1;
    public AudioTrack(int stream, int rate, int channel, int encoding, int bufferBytes, int mode) { }
    public static int getMinBufferSize(int rate, int channel, int encoding) { return 0; }
    public int getState() { return 0; }
    public void play() { }
    public void stop() { }
    public void release() { }
    public int write(short[] audio, int offset, int size) { return 0; }
    public static class Builder {
        public Builder setAudioAttributes(AudioAttributes a) { return this; }
        public Builder setAudioFormat(AudioFormat f) { return this; }
        public Builder setBufferSizeInBytes(int b) { return this; }
        public Builder setTransferMode(int m) { return this; }
        public AudioTrack build() { return null; }
    }
}
