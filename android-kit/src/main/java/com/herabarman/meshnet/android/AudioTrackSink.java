package com.herabarman.meshnet.android;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import com.herabarman.meshnet.kit.AudioSink;

/**
 * The loudspeaker for push-to-talk: 16 bit mono PCM through AudioTrack in streaming mode, as a voice call
 * (so the volume keys control call volume). To send it to the loudspeaker instead of the earpiece, the app can
 * call AudioManager.setSpeakerphoneOn(true) while it is in MODE_IN_COMMUNICATION.
 */
public final class AudioTrackSink implements AudioSink {
    private AudioTrack track;

    @Override
    @SuppressWarnings("deprecation")
    public synchronized void start(int sampleRate) {
        if (track != null) return;
        int min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IllegalStateException("this phone cannot play at " + sampleRate + " Hz");
        int size = Math.max(min, sampleRate / 2);                 // about 0.25 s of 16 bit samples
        AudioTrack t;
        if (Build.VERSION.SDK_INT >= 23) {
            t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(size)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } else {
            t = new AudioTrack(AudioManager.STREAM_VOICE_CALL, sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, size, AudioTrack.MODE_STREAM);
        }
        if (t.getState() != AudioTrack.STATE_INITIALIZED) {
            t.release();
            throw new IllegalStateException("loudspeaker not available");
        }
        t.play();
        track = t;
    }

    @Override
    public void write(short[] pcm) {
        AudioTrack t;
        synchronized (this) {
            t = track;
        }
        if (t == null) return;
        int off = 0;
        while (off < pcm.length) {                                // blocks while the track's buffer is full: that is the pacing
            int n = t.write(pcm, off, pcm.length - off);
            if (n <= 0) return;                                   // stopped or error
            off += n;
        }
    }

    @Override
    public synchronized void stop() {
        if (track == null) return;
        try {
            track.stop();
        } catch (IllegalStateException ignored) {
        }
        track.release();
        track = null;
    }
}
