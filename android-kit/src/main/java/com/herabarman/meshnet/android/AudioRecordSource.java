package com.herabarman.meshnet.android;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import com.herabarman.meshnet.kit.AudioSource;

/**
 * The microphone for push-to-talk: 16 bit mono PCM through AudioRecord, tuned for speech
 * (VOICE_COMMUNICATION turns on the phone's own echo cancellation and noise filter where it has them).
 * Needs the RECORD_AUDIO permission (ask for it at runtime before the first press) and, if the app records
 * while it is in the background, a foreground service of type "microphone".
 */
public final class AudioRecordSource implements AudioSource {
    private final Object lock = new Object();
    private AudioRecord rec;
    private AcousticEchoCanceler echo;
    private NoiseSuppressor noise;
    private volatile boolean on;

    @Override
    public void start(int sampleRate) {
        synchronized (lock) {
            if (rec != null) return;
            int min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IllegalStateException("this phone cannot record at " + sampleRate + " Hz");
            AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, sampleRate));   // >= 0.5 s
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                throw new IllegalStateException("microphone not available (RECORD_AUDIO permission? another app using it?)");
            }
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    echo = AcousticEchoCanceler.create(r.getAudioSessionId());
                    if (echo != null) echo.setEnabled(true);
                }
                if (NoiseSuppressor.isAvailable()) {
                    noise = NoiseSuppressor.create(r.getAudioSessionId());
                    if (noise != null) noise.setEnabled(true);
                }
            } catch (RuntimeException ignored) {
                // the effects are a bonus; record without them
            }
            r.startRecording();
            rec = r;
            on = true;
        }
    }

    @Override
    public int read(short[] buf) {
        int off = 0;
        while (off < buf.length) {
            synchronized (lock) {
                if (rec == null || !on) return -1;
                int n = rec.read(buf, off, buf.length - off);
                if (n < 0) return -1;
                off += n;
            }
        }
        return off;
    }

    @Override
    public void stop() {
        AudioRecord r = rec;
        on = false;
        if (r == null) return;
        try {
            r.stop();                                           // wakes a read() that is waiting, then it lets go of the lock
        } catch (IllegalStateException ignored) {
        }
        synchronized (lock) {
            if (echo != null) { echo.release(); echo = null; }
            if (noise != null) { noise.release(); noise = null; }
            r.release();
            rec = null;
        }
    }
}
