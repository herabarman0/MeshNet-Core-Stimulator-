package com.herabarman.meshnet.kit;

/** Microphone, as the voice pipeline sees it. android-kit has the AudioRecord version; tests use a fake. */
public interface AudioSource {
    /** Open the microphone. @throws RuntimeException if it cannot be opened (no permission, busy) */
    void start(int sampleRate);

    /** Block until buf is full with live samples. @return buf.length, or -1 if the source was stopped */
    int read(short[] buf);

    /** Release the microphone. A read() that is waiting must return -1. */
    void stop();
}
