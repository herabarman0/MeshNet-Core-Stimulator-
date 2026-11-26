package com.herabarman.meshnet.core;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Receiver-side playout buffer for one talk spurt of a voice stream. Pure logic, no threads, no clock of its own.
 *
 * Packets arrive late, early, twice or never. The buffer holds each one until its playout time, which is fixed by
 * the first packet of the spurt: playout(seq) = firstArrival + delay + (seq - firstSeq) * frameMs.
 *  - a packet that arrives after its playout time is late: it is dropped and counted (it would only break the audio);
 *  - a gap is reported once as a lost frame (data == null) so the decoder can conceal it;
 *  - the delay is chosen per spurt from a running estimate of the arrival jitter (RFC 3550 style), so a calm
 *    link gets low latency and a bursty one gets a larger buffer. It never changes in the middle of a spurt.
 * Sequence numbers are 16 bit and wrap.
 */
public final class JitterBuffer {
    /** One frame leaving the buffer, in order. data == null means the frame was lost. */
    public static final class Out {
        public final int seq;
        public final byte[] data;

        Out(int seq, byte[] data) {
            this.seq = seq;
            this.data = data;
        }
    }

    private final int minDelayMs;
    private final int maxDelayMs;
    private final TreeMap<Integer, byte[]> frames = new TreeMap<>();
    private int frameMs = 60;
    private boolean active;
    private long baseArrival;
    private int baseSeq;
    private int delayMs;
    private int nextExt;            // next sequence number to play (extended, no wrap)
    private int highestExt;         // highest sequence number seen (extended)
    private int lastExt;            // last extended number, to unwrap the next 16-bit number
    private long lastArrival;
    private double jitterMs;        // smoothed mean deviation of the inter-arrival time
    private long received, late, duplicates, lost, played, recovered;
    private int lastDelay;
    private boolean fresh;         // no packet of this spurt seen yet (its arrival says nothing about jitter)

    public JitterBuffer(int minDelayMs, int maxDelayMs) {
        this.minDelayMs = minDelayMs;
        this.maxDelayMs = Math.max(minDelayMs, maxDelayMs);
        this.delayMs = minDelayMs;
    }

    public boolean active() { return active; }
    public int delayMs() { return lastDelay; }
    public long received() { return received; }
    public long late() { return late; }
    public long duplicates() { return duplicates; }
    public long lost() { return lost; }
    public long played() { return played; }
    public double jitterMs() { return jitterMs; }
    /** Frames waiting for playout. */
    public int buffered() { return frames.size(); }

    /** Begin a new talk spurt (called with the first packet, or after silence). */
    private void begin(int seq, int frameMs, long nowMs) {
        active = true;
        this.frameMs = Math.max(5, frameMs);
        baseArrival = nowMs;
        baseSeq = seq;
        nextExt = seq;
        highestExt = seq - 1;
        lastExt = seq;
        lastArrival = nowMs;
        fresh = true;
        frames.clear();
        delayMs = (int) Math.max(minDelayMs, Math.min(maxDelayMs, minDelayMs + 3 * jitterMs));
        lastDelay = delayMs;
    }

    /** Stop the spurt; whatever is still buffered is discarded. */
    public void reset() {
        active = false;
        frames.clear();
    }

    private long playoutAt(int ext) {
        return baseArrival + delayMs + (long) (ext - baseSeq) * frameMs;
    }

    private int unwrap(int seq16) {
        return lastExt + (short) (seq16 - lastExt);
    }

    /**
     * @param first true when the sender marked this packet as the start of a spurt
     * @return false if the packet was dropped (late or a duplicate)
     */
    public boolean push(int seq16, byte[] data, int frameMs, boolean first, long nowMs) {
        seq16 &= 0xFFFF;
        if (!active || (first && playedAll())) {               // a new spurt: first ever, after silence, or a new press
            begin(seq16, frameMs, nowMs);
        }
        int ext = unwrap(seq16);
        received++;
        // jitter: how much did the gap between arrivals differ from the gap between the packets' send times?
        if (fresh) {
            fresh = false;
        } else if (ext > lastExt) {
            double d = Math.abs((nowMs - lastArrival) - (double) (ext - lastExt) * this.frameMs);
            jitterMs += (d - jitterMs) / 16.0;
            lastArrival = nowMs;
        }
        if (ext > lastExt) lastExt = ext;
        if (ext < nextExt) {                                    // its turn has passed
            late++;
            return false;
        }
        if (frames.containsKey(ext)) {
            duplicates++;
            return false;
        }
        if (nowMs > playoutAt(ext)) {                           // arrived after its own playout time
            late++;
            jitterMs += Math.min(100.0, nowMs - playoutAt(ext)) / 4.0;   // next spurt gets a bigger buffer
            return false;
        }
        frames.put(ext, data);
        if (ext > highestExt) highestExt = ext;
        return true;
    }

    /**
     * A copy of the previous packet that rode along with a newer one (redundancy). It is kept only if it fills a hole
     * that is still in time; otherwise it is ignored silently (it is not a late or duplicate packet of its own).
     */
    public boolean pushRecovered(int seq16, byte[] data, long nowMs) {
        if (!active) return false;
        int ext = unwrap(seq16 & 0xFFFF);
        if (ext < nextExt || ext > highestExt + 1 || frames.containsKey(ext) || nowMs > playoutAt(ext)) return false;
        frames.put(ext, data);
        if (ext > highestExt) highestExt = ext;
        recovered++;
        return true;
    }

    public long recovered() { return recovered; }

    private boolean playedAll() {
        return nextExt > highestExt;
    }

    /** Frames whose playout time has come, in order. Missing ones (with a later one known) come out as lost. */
    public List<Out> poll(long nowMs) {
        List<Out> out = new ArrayList<>(2);
        if (!active) return out;
        while (nextExt <= highestExt && playoutAt(nextExt) <= nowMs) {
            byte[] d = frames.remove(nextExt);
            if (d == null) lost++;
            else played++;
            out.add(new Out(nextExt & 0xFFFF, d));
            nextExt++;
        }
        return out;
    }

    /** Milliseconds until the next frame is due, or -1 if nothing is waiting. */
    public long nextDueIn(long nowMs) {
        if (!active || nextExt > highestExt) return -1;
        return Math.max(0, playoutAt(nextExt) - nowMs);
    }
}
