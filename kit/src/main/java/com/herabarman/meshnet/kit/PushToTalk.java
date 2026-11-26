package com.herabarman.meshnet.kit;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Walkie-talkie on top of MeshClient: microphone -> codec -> mesh, and mesh -> codec -> loudspeaker.
 *
 * <pre>
 * PushToTalk ptt = new PushToTalk(client, micSource, speakerSink, new PushToTalk.Events() {...},
 *                                 AdpcmCodec.FACTORY);          // first codec is the one used for sending
 * ptt.start();
 * client.sendText(peer, "calling");                             // first contact: route + keys
 * ptt.press(peer);                                             // button down: starts capturing and sending
 * ptt.release();                                                // button up
 * ptt.stop();
 * </pre>
 *
 * Every packet is: 1 byte codec id + one codec block. Several codecs can be given; the first is used to send,
 * all of them can be received, so two phones with different codecs still hear each other.
 * Threads: one capture thread while the button is down, one playback thread. Events arrive on the engine thread
 * (onTalkStart/onTalkEnd/onQuality) or the playback thread; keep them short.
 */
public final class PushToTalk {
    public interface Events {
        /** Someone started talking to you. */
        void onTalkStart(String fromUser);

        /** They released the button. */
        void onTalkEnd(String fromUser);

        /** How well your voice reaches toUser, 0..100 percent lost. */
        void onQuality(String toUser, int lossPercent);

        /** Microphone or loudspeaker problem; talking is stopped. */
        void onError(String message);
    }

    private static final int PLAY_QUEUE = 40;                   // about 2.4 s of audio at 60 ms blocks

    private final MeshClient client;
    private final AudioSource source;
    private final AudioSink sink;
    private final Events events;
    private final VoiceCodec.Factory[] factories;
    private final VoiceCodec encoder;
    private final Map<String, VoiceCodec> decoders = new HashMap<>();   // engine thread only: "user:codecId"
    private final Map<String, Integer> streams = new HashMap<>();
    private final BlockingQueue<short[]> playQueue = new ArrayBlockingQueue<>(PLAY_QUEUE);
    private volatile boolean running;
    private volatile boolean pressed;
    private Thread capture;
    private Thread player;
    private final Map<String, Integer> lastCodec = new HashMap<>();     // engine thread only: user -> codec id
    private volatile long blocksSent;
    private volatile long blocksPlayed;
    private volatile long blocksConcealed;
    private volatile long blocksDropped;

    public PushToTalk(MeshClient client, AudioSource source, AudioSink sink, Events events, VoiceCodec.Factory... codecs) {
        if (codecs.length == 0) throw new IllegalArgumentException("at least one codec");
        this.client = client;
        this.source = source;
        this.sink = sink;
        this.events = events;
        this.factories = codecs;
        this.encoder = codecs[0].create();
    }

    public long blocksSent() { return blocksSent; }
    public long blocksPlayed() { return blocksPlayed; }
    public long blocksConcealed() { return blocksConcealed; }
    public long blocksDropped() { return blocksDropped; }
    public boolean talking() { return pressed; }

    /** Start listening: registers for incoming voice and starts the loudspeaker. */
    public synchronized void start() {
        if (running) return;
        running = true;
        try {
            sink.start(encoder.sampleRate());
        } catch (RuntimeException e) {
            running = false;
            events.onError("loudspeaker: " + e.getMessage());
            return;
        }
        client.setVoiceListener(new MeshClient.VoiceListener() {
            @Override public void onVoiceStart(String fromUser) { events.onTalkStart(fromUser); }
            @Override public void onVoiceFrame(String fromUser, byte[] data) { received(fromUser, data); }
            @Override public void onVoiceEnd(String fromUser) { events.onTalkEnd(fromUser); }
            @Override public void onVoiceQuality(String toUser, int lossPercent) { events.onQuality(toUser, lossPercent); }
        });
        player = new Thread(new Runnable() {
            @Override public void run() { playLoop(); }
        }, "ptt-play");
        player.setDaemon(true);
        player.start();
    }

    /** Button down: capture the microphone and send to this user until release(). */
    public synchronized void press(final String toUser) {
        if (!running || pressed) return;
        Integer stream = streams.get(toUser);
        if (stream == null) {
            stream = client.voiceOpen(toUser, blockMs());
            streams.put(toUser, stream);
        }
        final int streamId = stream;
        try {
            source.start(encoder.sampleRate());
        } catch (RuntimeException e) {
            events.onError("microphone: " + e.getMessage());
            return;
        }
        pressed = true;
        capture = new Thread(new Runnable() {
            @Override public void run() { captureLoop(streamId); }
        }, "ptt-capture");
        capture.setDaemon(true);
        capture.start();
    }

    private int blockMs() {
        return encoder.blockSamples() * 1000 / encoder.sampleRate();
    }

    private void captureLoop(int streamId) {
        short[] buf = new short[encoder.blockSamples()];
        while (pressed) {
            int n = source.read(buf);
            if (n < 0) break;                                    // stopped
            if (n < buf.length) continue;
            byte[] block = encoder.encode(buf);
            if (block == null) continue;                         // the codec skipped this block: send nothing
            byte[] packet = new byte[1 + block.length];
            packet[0] = (byte) encoder.id();
            System.arraycopy(block, 0, packet, 1, block.length);
            client.voiceSend(streamId, packet);
            blocksSent++;
        }
    }

    /** Button up. */
    public synchronized void release() {
        if (!pressed) return;
        pressed = false;
        source.stop();                                          // wakes a waiting read()
        try {
            if (capture != null) capture.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (Integer s : streams.values()) client.voiceEnd(s);   // only the stream that was open has anything to end
    }

    /** Stop everything and free the microphone and loudspeaker. */
    public synchronized void stop() {
        if (!running) return;
        release();
        running = false;
        client.setVoiceListener(null);
        for (Integer s : streams.values()) client.voiceClose(s);
        streams.clear();
        if (player != null) player.interrupt();
        sink.stop();
    }

    // ---- receiving (engine thread) ----------------------------------------------------------------------------------

    private VoiceCodec decoderFor(String user, int codecId) {
        String key = user + ":" + codecId;
        VoiceCodec d = decoders.get(key);
        if (d == null) {
            for (VoiceCodec.Factory f : factories) if (f.id() == codecId) d = f.create();
            if (d != null) decoders.put(key, d);
        }
        return d;
    }

    private void received(String user, byte[] data) {
        short[] pcm = null;
        if (data == null) {                                      // lost: conceal with the codec this user used last
            Integer last = lastCodec.get(user);
            VoiceCodec d = last == null ? null : decoderFor(user, last);
            if (d != null) {
                pcm = d.conceal();
                blocksConcealed++;
            }
        } else if (data.length > 1) {
            int id = data[0] & 0xFF;
            VoiceCodec d = decoderFor(user, id);
            if (d != null) {
                byte[] block = new byte[data.length - 1];
                System.arraycopy(data, 1, block, 0, block.length);
                pcm = d.decode(block);
                lastCodec.put(user, id);
            }
        }
        if (pcm == null) return;
        if (!playQueue.offer(pcm)) {                             // the speaker is behind: drop the oldest, keep it live
            playQueue.poll();
            blocksDropped++;
            playQueue.offer(pcm);
        }
    }

    private void playLoop() {
        while (running) {
            try {
                short[] pcm = playQueue.poll(200, TimeUnit.MILLISECONDS);
                if (pcm == null) continue;
                sink.write(pcm);
                blocksPlayed++;
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                events.onError("loudspeaker: " + e.getMessage());
                return;
            }
        }
    }
}
