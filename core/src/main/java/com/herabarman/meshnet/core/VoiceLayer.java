package com.herabarman.meshnet.core;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Live voice on top of the engine: walkie-talkie (push-to-talk) and, with two streams, a call.
 *
 * The layer moves opaque codec frames and does not care which codec made them (Opus, Codec2, AMR ...). Pack a few
 * codec frames into one packet (for example 3 x 20 ms) so the 30 byte header and the 16 byte crypto tag are
 * shared; frameMs is the playing time of one packet.
 *
 * What is different from a text message (see MeshNode.sendVoice):
 *  - no ACK, no retry, no store-and-forward: a frame is sent once and, if the radio queue holds it too long, dropped;
 *  - the receiver plays frames from a JitterBuffer (adaptive delay, lost frames reported as null for concealment);
 *  - the receiver sends a small report every voiceReportMs. It gives the sender the loss rate (to lower the bitrate)
 *    and, more importantly, teaches the relays the way back, so after the first second the frames are unicast
 *    along one path instead of flooded;
 *  - flags in each packet mark the first and the last packet of a talk spurt (the press and release of the button).
 *
 * Set a call up with a normal message first (it resolves the destination's zone and exchanges keys), then call open().
 * Like ChunkLayer, this layer installs itself as the node's listener and passes everything else on.
 */
public final class VoiceLayer implements MeshNode.Listener {
    public interface Callbacks {
        /** A talk spurt from fromNode begins (the other side pressed the button). */
        void onTalkStart(int fromNode, int callId);

        /** One packet of audio in playout order. data == null means it was lost: conceal it. */
        void onFrame(int fromNode, int callId, byte[] data);

        /** The spurt ended (release marker or silence). */
        void onTalkEnd(int fromNode, int callId);

        /** Sender side: how my stream is doing at the receiver. lossPercent 0..100 over the last report interval. */
        void onReport(int toNode, int callId, int lossPercent);
    }

    static final int KIND_FRAME = 1;
    static final int KIND_REPORT = 2;
    static final int FLAG_FIRST = 0x10;
    static final int FLAG_LAST = 0x20;
    /** The packet carries the previous packet's audio too: curLen(2) cur prev. */
    static final int FLAG_RED = 0x40;
    /** kind+flags(1) callId(2) seq(2) frameMs/5(1) */
    public static final int FRAME_HEADER = 6;

    private static final class Out {
        final int dst;
        final int frameMs;
        int seq;
        long lastSendMs = -1;
        boolean lastWasEnd = true;
        long sent;
        int lossPercent;
        boolean redundant;
        byte[] prev;

        Out(int dst, int frameMs) {
            this.dst = dst;
            this.frameMs = frameMs;
        }
    }

    private static final class In {
        final int from;
        final int callId;
        final JitterBuffer jb;
        boolean spurt;
        boolean ending;
        boolean tickScheduled;
        boolean reportScheduled;
        long lastArrival;
        // report bookkeeping (16 bit sequence numbers, unwrapped)
        boolean haveSeq;
        int highest;
        int countSinceReport;
        int highestAtReport;

        In(int from, int callId, JitterBuffer jb) {
            this.from = from;
            this.callId = callId;
            this.jb = jb;
        }
    }

    private final MeshNode node;
    private final MeshNode.Listener downstream;
    private final MeshConfig cfg;
    private final Clock clock;
    private final Random rng;
    private final Callbacks callbacks;
    private final Map<Integer, Out> outbound = new HashMap<>();
    private final Map<Long, In> inbound = new HashMap<>();
    private long framesSent;
    private long framesReceived;

    public VoiceLayer(MeshNode node, MeshNode.Listener downstream, MeshConfig cfg, Clock clock, Random rng, Callbacks callbacks) {
        this.node = node;
        this.downstream = downstream;
        this.cfg = cfg;
        this.clock = clock;
        this.rng = rng;
        this.callbacks = callbacks;
        node.setListener(this);
    }

    // ---- sending ----------------------------------------------------------------------------

    /**
     * Open an outgoing voice stream to dst.
     * @param frameMs playing time of one packet, 5..1275 ms (both sides learn it from the packets)
     * @return the call id (16 bit)
     */
    public int open(int dst, int frameMs) {
        if (frameMs < 5 || frameMs > 1275) throw new IllegalArgumentException("frameMs");
        int id;
        do { id = rng.nextInt(0x10000); } while (outbound.containsKey(id));
        outbound.put(id, new Out(dst, frameMs));
        return id;
    }

    /** Switch redundancy on or off by hand (with voiceAutoRedundancy it is switched from the loss reports). */
    public void setRedundancy(int callId, boolean on) {
        Out o = outbound.get(callId);
        if (o != null) o.redundant = on;
    }

    public boolean redundancy(int callId) {
        Out o = outbound.get(callId);
        return o != null && o.redundant;
    }

    public void close(int callId) {
        outbound.remove(callId);
    }

    /**
     * Send one packet of audio. The first packet after a pause (or after endTalk) is marked as the start of a spurt.
     * @param frame one or a few codec frames, at most 1100 bytes
     */
    public void send(int callId, byte[] frame) {
        Out o = outbound.get(callId);
        if (o == null) throw new IllegalArgumentException("unknown call");
        long now = clock.nowMs();
        boolean first = o.lastWasEnd || o.lastSendMs < 0 || now - o.lastSendMs > cfg.voiceSpurtTimeoutMs;
        emit(callId, o, frame, first ? FLAG_FIRST : 0, now);
        o.lastWasEnd = false;
    }

    /** The button was released: tell the receiver the spurt is over (a packet with no audio). */
    public void endTalk(int callId) {
        Out o = outbound.get(callId);
        if (o == null || o.lastWasEnd) return;
        emit(callId, o, new byte[0], FLAG_LAST, clock.nowMs());
        o.lastWasEnd = true;
    }

    private void emit(int callId, Out o, byte[] frame, int flags, long now) {
        if (frame.length > 1100) throw new IllegalArgumentException("frame too large");
        byte[] prev = o.prev;
        boolean red = o.redundant && prev != null && prev.length > 0 && frame.length > 0 && (flags & FLAG_FIRST) == 0;
        ByteBuffer b = ByteBuffer.allocate(FRAME_HEADER + (red ? 2 + frame.length + prev.length : frame.length));
        b.put((byte) (KIND_FRAME | flags | (red ? FLAG_RED : 0))).putShort((short) callId).putShort((short) o.seq).put((byte) (o.frameMs / 5));
        if (red) b.putShort((short) frame.length);
        b.put(frame);
        if (red) b.put(prev);
        o.prev = frame.length > 0 ? frame : null;
        o.seq = (o.seq + 1) & 0xFFFF;
        o.lastSendMs = now;
        o.sent++;
        framesSent++;
        node.sendVoice(o.dst, b.array());
    }

    /** Loss rate the receiver last reported for this stream (0 until the first report). */
    public int lossPercent(int callId) {
        Out o = outbound.get(callId);
        return o == null ? 0 : o.lossPercent;
    }

    public long framesSent() { return framesSent; }
    public long framesReceived() { return framesReceived; }

    /** Jitter buffer of an incoming stream, for diagnostics (null if nothing came yet). */
    public JitterBuffer incoming(int fromNode, int callId) {
        In in = inbound.get(key(fromNode, callId));
        return in == null ? null : in.jb;
    }

    // ---- receiving --------------------------------------------------------------------------

    private static long key(int from, int callId) {
        return ((long) from << 16) | (callId & 0xFFFF);
    }

    @Override
    public void onDelivered(MeshNode n, Packet p, byte[] plain, int fromPeer) {
        if (p.type != Packet.TYPE_VOICE) {
            downstream.onDelivered(n, p, plain, fromPeer);
            return;
        }
        if (plain.length < 5) return;
        ByteBuffer b = ByteBuffer.wrap(plain);
        int head = b.get() & 0xFF;
        int kind = head & 0x0F;
        int callId = b.getShort() & 0xFFFF;
        if (kind == KIND_REPORT) {
            if (plain.length == 5) handleReport(p.src, callId, b.get() & 0xFF);
        } else if (kind == KIND_FRAME && plain.length >= FRAME_HEADER) {
            int seq = b.getShort() & 0xFFFF;
            int frameMs = (b.get() & 0xFF) * 5;
            byte[] data;
            byte[] prev = null;
            if ((head & FLAG_RED) != 0) {
                if (plain.length < FRAME_HEADER + 2) return;
                int curLen = b.getShort() & 0xFFFF;
                if (curLen > plain.length - FRAME_HEADER - 2) return;
                data = new byte[curLen];
                b.get(data);
                prev = new byte[plain.length - FRAME_HEADER - 2 - curLen];
                b.get(prev);
            } else {
                data = new byte[plain.length - FRAME_HEADER];
                b.get(data);
            }
            handleFrame(p.src, callId, seq, frameMs, data, prev, (head & FLAG_FIRST) != 0, (head & FLAG_LAST) != 0);
        }
    }

    private void handleReport(int from, int callId, int lossPercent) {
        Out o = outbound.get(callId);
        if (o == null || o.dst != from) return;
        o.lossPercent = Math.min(100, lossPercent);
        if (cfg.voiceAutoRedundancy) {
            if (o.lossPercent >= cfg.voiceRedundancyOnPercent) o.redundant = true;
            else if (o.lossPercent <= cfg.voiceRedundancyOffPercent) o.redundant = false;
        }
        callbacks.onReport(from, callId, o.lossPercent);
    }

    private void handleFrame(int from, int callId, int seq, int frameMs, byte[] data, byte[] prev, boolean first, boolean last) {
        long now = clock.nowMs();
        long k = key(from, callId);
        In in = inbound.get(k);
        if (in == null) {
            if (inbound.size() >= 32) {                         // forget streams that went quiet a minute ago
                java.util.Iterator<In> it = inbound.values().iterator();
                while (it.hasNext()) if (now - it.next().lastArrival > 60_000) it.remove();
            }
            in = new In(from, callId, new JitterBuffer(cfg.voiceMinDelayMs, cfg.voiceMaxDelayMs));
            in.lastArrival = now;
            inbound.put(k, in);
        }
        framesReceived++;
        in.lastArrival = now;
        // report bookkeeping: every packet counts, the end marker too (it uses a sequence number)
        if (!in.haveSeq) {
            in.haveSeq = true;
            in.highest = seq;
            in.highestAtReport = seq - 1;
        } else {
            int ahead = (short) (seq - in.highest);
            if (ahead > 0) in.highest += ahead;
        }
        in.countSinceReport++;
        boolean wasActive = in.jb.active();
        if (last) {
            if (in.jb.nextDueIn(now) < 0) finishSpurt(in);       // nothing left to play
            else {
                in.ending = true;                                // let the buffered audio play out first
                scheduleTick(in);
            }
            return;
        }
        boolean stored = in.jb.push(seq, data, Math.max(5, frameMs), first, now);
        if (prev != null && prev.length > 0) in.jb.pushRecovered(seq - 1, prev, now);   // fills a hole if the last packet was lost
        if (!stored) return;
        if (!in.spurt) {
            in.spurt = true;
            in.ending = false;
            callbacks.onTalkStart(from, callId);
        }
        if (!in.reportScheduled) {
            in.reportScheduled = true;
            final In target = in;
            // the first report goes out quickly: it is what teaches the relays the way back
            clock.schedule(wasActive ? cfg.voiceReportMs : 100, new Runnable() {
                @Override public void run() { report(target); }
            });
        }
        scheduleTick(in);
    }

    private void scheduleTick(final In in) {
        if (in.tickScheduled) return;
        long due = in.jb.nextDueIn(clock.nowMs());
        if (due < 0) return;
        in.tickScheduled = true;
        clock.schedule(due, new Runnable() {
            @Override public void run() {
                in.tickScheduled = false;
                tick(in);
            }
        });
    }

    private void tick(In in) {
        long now = clock.nowMs();
        List<JitterBuffer.Out> due = in.jb.poll(now);
        for (JitterBuffer.Out f : due) callbacks.onFrame(in.from, in.callId, f.data);
        boolean drained = in.jb.nextDueIn(now) < 0;
        if (in.spurt && drained && (in.ending || now - in.lastArrival > cfg.voiceSpurtTimeoutMs)) {
            finishSpurt(in);                                   // release marker, or the sender went quiet and it never came
            return;
        }
        if (in.jb.nextDueIn(now) >= 0) {
            scheduleTick(in);
        } else if (in.spurt) {                                 // nothing due yet: look again soon (also catches silence)
            in.tickScheduled = true;
            final In target = in;
            clock.schedule(Math.max(20, cfg.voiceSpurtTimeoutMs / 4), new Runnable() {
                @Override public void run() {
                    target.tickScheduled = false;
                    tick(target);
                }
            });
        }
    }

    private void finishSpurt(In in) {
        in.ending = false;
        if (in.jb.active()) in.jb.reset();
        if (in.spurt) {
            in.spurt = false;
            callbacks.onTalkEnd(in.from, in.callId);
        }
    }

    private void report(In in) {
        in.reportScheduled = false;
        if (!in.haveSeq) return;
        int expected = in.highest - in.highestAtReport;
        int lossPercent = expected <= 0 ? 0 : Math.max(0, Math.min(100, 100 - 100 * in.countSinceReport / expected));
        in.highestAtReport = in.highest;
        in.countSinceReport = 0;
        ByteBuffer b = ByteBuffer.allocate(5);
        b.put((byte) KIND_REPORT).putShort((short) in.callId).put((byte) lossPercent);
        try {
            node.sendVoice(in.from, b.array());
        } catch (RuntimeException e) {
            // no key for this peer yet: the report is only a hint, skip it
        }
        if (in.spurt) {                                       // keep reporting while the stream is alive
            in.reportScheduled = true;
            final In target = in;
            clock.schedule(cfg.voiceReportMs, new Runnable() {
                @Override public void run() { report(target); }
            });
        }
    }

    // ---- everything else goes to the next layer ---------------------------------------------

    @Override public void onDuplicate(MeshNode n, Packet p) { downstream.onDuplicate(n, p); }
    @Override public void onDropped(MeshNode n, Packet p, String reason) { downstream.onDropped(n, p, reason); }
    @Override public void onFloodSuppressed(MeshNode n, Packet p) { downstream.onFloodSuppressed(n, p); }
    @Override public void onAcked(MeshNode n, long msgId) { downstream.onAcked(n, msgId); }
    @Override public void onRetry(MeshNode n, Packet p) { downstream.onRetry(n, p); }
    @Override public void onShed(MeshNode n, Packet p) { downstream.onShed(n, p); }
}
