package com.herabarman.meshnet.core;

import java.nio.ByteBuffer;

/**
 * Compact binary packet. Header is 30 bytes:
 * version(1) typeAndAttempt(1) ttl(1) priority(1) msgId(8) src(4) dst(4) srcZone(4) dstZone(4) payloadLen(2), then payload.
 * The second byte holds the type in its low 4 bits and the retry attempt (0..15) in its high 4 bits.
 * msgId is a random 64-bit id used for deduplication.
 * srcZone / dstZone travel with the packet, so relays never have to look anything up and the receiver can
 * answer (ACK) without a lookup. dstZone = -1 means "sender has not resolved it yet" (never on the wire after resolution).
 * A dst with the top bit set is a zone address: the packet is for every node of that zone.
 */
public final class Packet {
    public static final int VERSION = 2;
    public static final int HEADER_SIZE = 30;
    public static final int MAX_PAYLOAD = 0xFFFF;
    public static final int TYPE_DATA = 1;
    public static final int TYPE_ACK = 2;
    /** Neighbour greeting, never forwarded. */
    public static final int TYPE_HELLO = 3;
    /** "I am here" sent to my home zone (location service). */
    public static final int TYPE_PUBLISH = 4;
    /** "Which zone is node X in?" sent to X's home zone. */
    public static final int TYPE_LOOKUP = 5;
    public static final int TYPE_LOOKUP_REPLY = 6;
    /** One piece of a large message (see ChunkLayer). Routed, ACKed and retried exactly like DATA. */
    public static final int TYPE_CHUNK = 7;
    /** One real-time voice packet (see VoiceLayer): encrypted, never ACKed, never retried, never stored, short TTL. */
    public static final int TYPE_VOICE = 8;
    /** Neighbour-to-neighbour gossip of directory records (see GossipDirectory). Never ACKed. */
    public static final int TYPE_GOSSIP = 9;
    /** Distributed hash table messages (see DhtNode). Never ACKed; the DHT has its own timeouts. */
    public static final int TYPE_DHT = 10;
    public static final int UNKNOWN_ZONE = -1;

    public final int type;
    /** 0 = first send, 1.. = fallback retransmission (always flooded). */
    public final int attempt;
    public final int ttl;
    public final int priority;
    public final long msgId;
    public final int src;
    public final int dst;
    public final int srcZone;
    public final int dstZone;
    public final byte[] payload;

    public Packet(int type, int ttl, int priority, long msgId, int src, int dst, byte[] payload) {
        this(type, ttl, priority, msgId, src, dst, payload, 0, 0, 0);
    }

    public Packet(int type, int ttl, int priority, long msgId, int src, int dst, byte[] payload, int attempt) {
        this(type, ttl, priority, msgId, src, dst, payload, attempt, 0, 0);
    }

    public Packet(int type, int ttl, int priority, long msgId, int src, int dst, byte[] payload,
                  int attempt, int srcZone, int dstZone) {
        if (type < 0 || type > 15) throw new IllegalArgumentException("type");
        if (attempt < 0 || attempt > 15) throw new IllegalArgumentException("attempt");
        if (ttl < 0 || ttl > 255) throw new IllegalArgumentException("ttl");
        if (priority < 0 || priority > 255) throw new IllegalArgumentException("priority");
        if (payload == null || payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("payload");
        this.type = type;
        this.attempt = attempt;
        this.ttl = ttl;
        this.priority = priority;
        this.msgId = msgId;
        this.src = src;
        this.dst = dst;
        this.srcZone = srcZone;
        this.dstZone = dstZone;
        this.payload = payload;
    }

    /** The address that means "every node of this zone". */
    public static int zoneAddress(int zone) {
        return 0x80000000 | zone;
    }

    public static boolean isZoneAddress(int dst) {
        return dst < 0;
    }

    public Packet withTtl(int newTtl) {
        return new Packet(type, newTtl, priority, msgId, src, dst, payload, attempt, srcZone, dstZone);
    }

    public Packet withAttempt(int newAttempt) {
        return new Packet(type, ttl, priority, msgId, src, dst, payload, newAttempt, srcZone, dstZone);
    }

    public Packet withDstZone(int newDstZone) {
        return new Packet(type, ttl, priority, msgId, src, dst, payload, attempt, srcZone, newDstZone);
    }

    public byte[] encode() {
        ByteBuffer b = ByteBuffer.allocate(HEADER_SIZE + payload.length);
        b.put((byte) VERSION).put((byte) ((attempt << 4) | type)).put((byte) ttl).put((byte) priority);
        b.putLong(msgId).putInt(src).putInt(dst).putInt(srcZone).putInt(dstZone).putShort((short) payload.length);
        b.put(payload);
        return b.array();
    }

    /** @throws IllegalArgumentException if the bytes are not a valid packet */
    public static Packet decode(byte[] data) {
        if (data == null || data.length < HEADER_SIZE) throw new IllegalArgumentException("short packet");
        ByteBuffer b = ByteBuffer.wrap(data);
        int ver = b.get() & 0xFF;
        if (ver != VERSION) throw new IllegalArgumentException("bad version " + ver);
        int typeAndAttempt = b.get() & 0xFF;
        int type = typeAndAttempt & 0x0F;
        int attempt = typeAndAttempt >>> 4;
        int ttl = b.get() & 0xFF;
        int prio = b.get() & 0xFF;
        long id = b.getLong();
        int src = b.getInt();
        int dst = b.getInt();
        int srcZone = b.getInt();
        int dstZone = b.getInt();
        int len = b.getShort() & 0xFFFF;
        if (data.length != HEADER_SIZE + len) throw new IllegalArgumentException("length mismatch");
        byte[] payload = new byte[len];
        b.get(payload);
        return new Packet(type, ttl, prio, id, src, dst, payload, attempt, srcZone, dstZone);
    }
}
