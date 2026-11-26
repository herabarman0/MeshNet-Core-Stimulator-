package com.herabarman.meshnet.kit;

/** Loudspeaker, as the voice pipeline sees it. android-kit has the AudioTrack version; tests use a fake. */
public interface AudioSink {
    void start(int sampleRate);

    /** Play these samples (may block until there is room). Called from one playback thread. */
    void write(short[] pcm);

    void stop();
}
