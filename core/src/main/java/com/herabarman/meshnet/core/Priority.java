package com.herabarman.meshnet.core;

/**
 * The priority levels of the radio queue. A higher number leaves first; a packet is never held up by one below it.
 * Apps use SOS, URGENT, NORMAL, BULK and BACKGROUND; the others are the engine's own.
 *
 * <pre>
 *  255 CONTROL     ACK, HELLO: tiny, and everything else depends on them (engine only)
 *  254 SOS         emergency: own lane (see below), flooded everywhere, never shed, retried faster, kept longer
 *  250 VOICE       live audio, dropped if it waits more than ~200 ms (VoiceLayer)
 *  200 URGENT      time-sensitive text, key exchange, read receipts
 *  150 NORMAL      chat text (the default)
 *   50 BULK        photos, files (below 100: shed first when the radio is saturated, may not use the token reserve)
 *   10 BACKGROUND  sync, status, anything that can take hours
 * </pre>
 *
 * SOS lane: an SOS packet is sent at once even when the radio budget (token bucket) is spent, because an alarm that
 * waits for a free slot is not an alarm. It is also flooded without suppression and without delay at every relay,
 * with a longer TTL.
 * Starvation: strict priority would let a steady stream of text hold back a file for ever; the Pacer gives BULK and
 * BACKGROUND a small guaranteed share of the radio (MeshConfig.bulkMinShare) so they always make progress.
 */
public final class Priority {
    public static final int CONTROL = 255;
    public static final int SOS = 254;
    public static final int VOICE = 250;
    public static final int URGENT = 200;
    public static final int NORMAL = 150;
    public static final int BULK = 50;
    public static final int BACKGROUND = 10;

    private Priority() { }

    /** A short name for logs and the dashboard. */
    public static String name(int p) {
        if (p >= CONTROL) return "control";
        if (p >= SOS) return "sos";
        if (p >= VOICE) return "voice";
        if (p >= URGENT) return "urgent";
        if (p >= NORMAL) return "normal";
        if (p >= 100) return "low";
        if (p >= BULK) return "bulk";
        return "background";
    }
}
