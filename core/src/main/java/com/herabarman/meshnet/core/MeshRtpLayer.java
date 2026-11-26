package com.herabarman.meshnet.core;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Real-Time Mesh RTP (Audio + Video over Multi-hop Mesh):
 * Extends {@link VoiceLayer} semantics to handle full-duplex real-time audio streams AND
 * fragmented live video frames (keyframes / delta frames) with adaptive bitrate control,
 * jitter-buffered audio, and packet loss feedback reports.
 *
 * Wire payload inside Packet.TYPE_VOICE:
 *   kind(1) streamId(2) seq(2) timestampMs(4) [VIDEO: frameSeq(2) fragIdx(1) fragTotal(1) flags(1)] data(...)
 */
public final class MeshRtpLayer implements MeshNode.Listener {
    public static final int PT_AUDIO = 0x10;
    public static final int PT_VIDEO_KEY = 0x11;
    public static final int PT_VIDEO_DELTA = 0x12;
    public static final int PT_RTP_REPORT = 0x13;
    public static final int PT_CALL_SIGNAL = 0x14;

    public static final int SIG_RING = 1;
    public static final int SIG_ACCEPT = 2;
    public static final int SIG_HANGUP = 3;

    public static final int AUDIO_HDR = 9;          // kind(1) streamId(2) seq(2) tsMs(4)
    public static final int VIDEO_HDR = 13;         // + frameSeq(2) fragIdx(1) fragTotal(1)
    public static final int MAX_FRAG_BYTES = 920;

    public interface Callbacks {
        void onCallSignal(int fromNode, int callId, int signal, boolean withVideo);
        void onAudioFrame(int fromNode, int callId, int seq, long timestampMs, byte[] audioBytes);
        void onVideoFrame(int fromNode, int callId, int frameSeq, boolean keyframe, long timestampMs, byte[] videoBytes);
        void onQualityReport(int peerNode, int callId, int lossPercent, int suggestedVideoKbps);
    }

    private static final class VideoAssembly {
        final boolean keyframe;
        final long timestampMs;
        final int total;
        final byte[][] frags;
        int got;

        VideoAssembly(boolean keyframe, long timestampMs, int total) {
            this.keyframe = keyframe;
            this.timestampMs = timestampMs;
            this.total = total;
            this.frags = new byte[total][];
        }
    }

    private static final class RxStats {
        int lastAudioSeq = -1;
        int audioExpected;
        int audioReceived;
        int videoFramesCompleted;
        final Map<Integer, VideoAssembly> videoAssemblies = new HashMap<>();
    }

    private final MeshNode node;
    private final MeshNode.Listener downstream;
    private final Clock clock;
    private final Callbacks callbacks;
    private int audioSeq;
    private int videoFrameSeq;
    private int targetVideoKbps = 180;
    private final Map<Long, RxStats> rxMap = new HashMap<>();

    public long audioPacketsSent;
    public long videoPacketsSent;
    public long videoFramesDelivered;
    public long reportsSent;

    public MeshRtpLayer(MeshNode node, MeshNode.Listener downstream, Clock clock, Callbacks callbacks) {
        this.node = node;
        this.downstream = downstream;
        this.clock = clock;
        this.callbacks = callbacks;
        node.setListener(this);
    }

    public int suggestedVideoKbps() { return targetVideoKbps; }

    public void sendCallSignal(int dst, int callId, int signal, boolean withVideo) {
        ByteBuffer b = ByteBuffer.allocate(5);
        b.put((byte) PT_CALL_SIGNAL).putShort((short) callId).put((byte) signal).put((byte) (withVideo ? 1 : 0));
        node.sendVoice(dst, b.array());
    }

    /** Send a single real-time audio RTP packet (e.g. 20-60 ms AMR-NB / ADPCM / Opus frame). */
    public void sendAudio(int dst, int callId, byte[] codecFrame) {
        if (codecFrame.length > 1050) throw new IllegalArgumentException("audio frame too large");
        int seq = audioSeq++ & 0xFFFF;
        long ts = clock.nowMs() & 0xFFFFFFFFL;
        ByteBuffer b = ByteBuffer.allocate(AUDIO_HDR + codecFrame.length);
        b.put((byte) PT_AUDIO).putShort((short) callId).putShort((short) seq).putInt((int) ts).put(codecFrame);
        audioPacketsSent++;
        node.sendVoice(dst, b.array());
    }

    /** Fragment and send a real-time video frame across the mesh as RTP fragments. */
    public int sendVideoFrame(int dst, int callId, byte[] encodedFrame, boolean keyframe) {
        if (encodedFrame == null || encodedFrame.length == 0) return 0;
        int total = (encodedFrame.length + MAX_FRAG_BYTES - 1) / MAX_FRAG_BYTES;
        if (total > 120) throw new IllegalArgumentException("video frame exceeds 120 RTP fragments");
        int fSeq = videoFrameSeq++ & 0xFFFF;
        long ts = clock.nowMs() & 0xFFFFFFFFL;
        int pt = keyframe ? PT_VIDEO_KEY : PT_VIDEO_DELTA;
        for (int idx = 0; idx < total; idx++) {
            int off = idx * MAX_FRAG_BYTES;
            int len = Math.min(MAX_FRAG_BYTES, encodedFrame.length - off);
            int seq = audioSeq++ & 0xFFFF;
            ByteBuffer b = ByteBuffer.allocate(VIDEO_HDR + len);
            b.put((byte) pt)
                    .putShort((short) callId)
                    .putShort((short) seq)
                    .putInt((int) ts)
                    .putShort((short) fSeq)
                    .put((byte) idx)
                    .put((byte) total)
                    .put(encodedFrame, off, len);
            videoPacketsSent++;
            node.sendVoice(dst, b.array());
        }
        return total;
    }

    /** Report observed packet loss back to sender so bitrate adapts in real time. */
    public void sendQualityReport(int peer, int callId) {
        RxStats st = rxMap.get(key(peer, callId));
        int lossPct = 0;
        if (st != null && st.audioExpected > 0) {
            int lost = Math.max(0, st.audioExpected - st.audioReceived);
            lossPct = Math.min(100, (lost * 100) / st.audioExpected);
            st.audioExpected = 0;
            st.audioReceived = 0;
        }
        int bitrate = adaptBitrate(lossPct);
        ByteBuffer b = ByteBuffer.allocate(6);
        b.put((byte) PT_RTP_REPORT).putShort((short) callId).put((byte) lossPct).putShort((short) bitrate);
        reportsSent++;
        node.sendVoice(peer, b.array());
    }

    private static int adaptBitrate(int lossPct) {
        if (lossPct >= 25) return 48;
        if (lossPct >= 12) return 96;
        if (lossPct >= 5) return 140;
        return 240;
    }

    @Override
    public void onDelivered(MeshNode n, Packet p, byte[] plain, int fromPeer) {
        if (p.type != Packet.TYPE_VOICE || plain.length < 4) {
            downstream.onDelivered(n, p, plain, fromPeer);
            return;
        }
        int pt = plain[0] & 0xFF;
        if (pt < PT_AUDIO || pt > PT_CALL_SIGNAL) {
            // Pass standard VoiceLayer PTT frames downstream cleanly
            downstream.onDelivered(n, p, plain, fromPeer);
            return;
        }
        ByteBuffer b = ByteBuffer.wrap(plain);
        b.get(); // pt
        int callId = b.getShort() & 0xFFFF;
        if (pt == PT_CALL_SIGNAL && plain.length >= 5) {
            int sig = b.get() & 0xFF;
            boolean vid = (b.get() & 0xFF) != 0;
            if (callbacks != null) callbacks.onCallSignal(p.src, callId, sig, vid);
            return;
        }
        if (pt == PT_RTP_REPORT && plain.length >= 6) {
            int loss = b.get() & 0xFF;
            int kbps = b.getShort() & 0xFFFF;
            targetVideoKbps = kbps;
            if (callbacks != null) callbacks.onQualityReport(p.src, callId, loss, kbps);
            return;
        }
        if (pt == PT_AUDIO && plain.length >= AUDIO_HDR) {
            int seq = b.getShort() & 0xFFFF;
            long ts = b.getInt() & 0xFFFFFFFFL;
            byte[] data = new byte[plain.length - AUDIO_HDR];
            b.get(data);
            RxStats st = statsOf(p.src, callId);
            if (st.lastAudioSeq >= 0) {
                int delta = (seq - st.lastAudioSeq) & 0xFFFF;
                if (delta > 0 && delta < 1000) st.audioExpected += delta;
            } else {
                st.audioExpected++;
            }
            st.lastAudioSeq = seq;
            st.audioReceived++;
            if (callbacks != null) callbacks.onAudioFrame(p.src, callId, seq, ts, data);
            return;
        }
        if ((pt == PT_VIDEO_KEY || pt == PT_VIDEO_DELTA) && plain.length >= VIDEO_HDR) {
            b.getShort(); // packet seq
            long ts = b.getInt() & 0xFFFFFFFFL;
            int frameSeq = b.getShort() & 0xFFFF;
            int fragIdx = b.get() & 0xFF;
            int fragTotal = b.get() & 0xFF;
            if (fragTotal <= 0 || fragIdx >= fragTotal) return;
            byte[] frag = new byte[plain.length - VIDEO_HDR];
            b.get(frag);
            RxStats st = statsOf(p.src, callId);
            VideoAssembly va = st.videoAssemblies.get(frameSeq);
            if (va == null || va.total != fragTotal) {
                if (st.videoAssemblies.size() > 8) st.videoAssemblies.clear();
                va = new VideoAssembly(pt == PT_VIDEO_KEY, ts, fragTotal);
                st.videoAssemblies.put(frameSeq, va);
            }
            if (va.frags[fragIdx] == null) {
                va.frags[fragIdx] = frag;
                va.got++;
            }
            if (va.got == va.total) {
                st.videoAssemblies.remove(frameSeq);
                int totalLen = 0;
                for (byte[] f : va.frags) totalLen += f.length;
                byte[] full = new byte[totalLen];
                int pos = 0;
                for (byte[] f : va.frags) {
                    System.arraycopy(f, 0, full, pos, f.length);
                    pos += f.length;
                }
                st.videoFramesCompleted++;
                videoFramesDelivered++;
                if (callbacks != null) callbacks.onVideoFrame(p.src, callId, frameSeq, va.keyframe, va.timestampMs, full);
            }
        }
    }

    private RxStats statsOf(int from, int callId) {
        long k = key(from, callId);
        RxStats s = rxMap.get(k);
        if (s == null) {
            s = new RxStats();
            rxMap.put(k, s);
        }
        return s;
    }

    private static long key(int nodeId, int callId) {
        return ((long) nodeId << 16) | (callId & 0xFFFFL);
    }

    @Override public void onDuplicate(MeshNode n, Packet p) { downstream.onDuplicate(n, p); }
    @Override public void onDropped(MeshNode n, Packet p, String reason) { downstream.onDropped(n, p, reason); }
    @Override public void onFloodSuppressed(MeshNode n, Packet p) { downstream.onFloodSuppressed(n, p); }
    @Override public void onAcked(MeshNode n, long msgId) { downstream.onAcked(n, msgId); }
    @Override public void onRetry(MeshNode n, Packet p) { downstream.onRetry(n, p); }
    @Override public void onShed(MeshNode n, Packet p) { downstream.onShed(n, p); }
}
