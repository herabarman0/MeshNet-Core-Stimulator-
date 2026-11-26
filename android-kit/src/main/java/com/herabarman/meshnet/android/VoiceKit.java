package com.herabarman.meshnet.android;

import android.Manifest;
import android.os.Handler;
import android.os.Looper;
import com.herabarman.meshnet.kit.AdpcmCodec;
import com.herabarman.meshnet.kit.MeshClient;
import com.herabarman.meshnet.kit.PushToTalk;
import com.herabarman.meshnet.kit.VoiceCodec;
import com.herabarman.meshnet.kit.VoiceSelfTest;

/**
 * Push-to-talk in three lines:
 *
 * <pre>
 * // after MeshNet.start(...) and after the user granted VoiceKit.permissions()
 * PushToTalk ptt = VoiceKit.pushToTalk(mesh.client(), events);
 * ptt.start();
 * mesh.sendText(peer, "calling");        // first contact: finds the route and exchanges keys
 * ptt.press(peer);                       // button down
 * ptt.release();                         // button up
 * </pre>
 *
 * Events come on the main thread. The codec is AMR-NB when the phone has it (about 44 bytes per 60 ms), else ADPCM
 * (244 bytes); incoming voice in either codec is played. Keep the app alive with a foreground service of type
 * "microphone" (FOREGROUND_SERVICE_MICROPHONE) if people should be able to talk with the screen off.
 */
public final class VoiceKit {
    private VoiceKit() { }

    /** Ask for these at runtime before the first press. */
    public static String[] permissions() {
        return new String[]{Manifest.permission.RECORD_AUDIO};
    }

    public static PushToTalk pushToTalk(MeshClient client, PushToTalk.Events events) {
        final Handler ui = new Handler(Looper.getMainLooper());
        final PushToTalk.Events to = events;
        PushToTalk.Events onMain = new PushToTalk.Events() {
            @Override public void onTalkStart(final String u) { ui.post(new Runnable() { @Override public void run() { to.onTalkStart(u); } }); }
            @Override public void onTalkEnd(final String u) { ui.post(new Runnable() { @Override public void run() { to.onTalkEnd(u); } }); }
            @Override public void onQuality(final String u, final int loss) { ui.post(new Runnable() { @Override public void run() { to.onQuality(u, loss); } }); }
            @Override public void onError(final String m) { ui.post(new Runnable() { @Override public void run() { to.onError(m); } }); }
        };
        return new PushToTalk(client, new AudioRecordSource(), new AudioTrackSink(), onMain, codecs());
    }

    /** AMR-NB only if the phone proved it works (a tone survives it); else ADPCM. Incoming voice in either is played. */
    private static VoiceCodec.Factory[] codecs() {
        return AmrNbCodec.works()
                ? new VoiceCodec.Factory[]{AmrNbCodec.FACTORY, AdpcmCodec.FACTORY}
                : new VoiceCodec.Factory[]{AdpcmCodec.FACTORY};
    }

    /**
     * Check this phone: microphone (opens, right speed, not silent), loudspeaker (plays a beep) and each codec.
     * Call it off the main thread (about a second), after RECORD_AUDIO was granted, and show report.toString()
     * to whoever is testing. Needed because only a phone can tell whether the audio side works.
     */
    public static VoiceSelfTest.Report selfTest() {
        return VoiceSelfTest.run(new AudioRecordSource(), new AudioTrackSink(), codecs());
    }
}
