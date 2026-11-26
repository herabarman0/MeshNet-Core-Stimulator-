package com.herabarman.meshnet.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PacketTest {
    @Test
    void roundTrip() {
        Packet p = new Packet(Packet.TYPE_DATA, 30, 7, 0x1122334455667788L, 42, 99, new byte[]{1, 2, 3});
        byte[] bytes = p.encode();
        assertEquals(Packet.HEADER_SIZE + 3, bytes.length);
        Packet q = Packet.decode(bytes);
        assertEquals(30, q.ttl);
        assertEquals(7, q.priority);
        assertEquals(0x1122334455667788L, q.msgId);
        assertEquals(42, q.src);
        assertEquals(99, q.dst);
        assertArrayEquals(new byte[]{1, 2, 3}, q.payload);
    }

    @Test
    void headerIsCompact() {
        assertEquals(30, Packet.HEADER_SIZE);
        assertEquals(30, new Packet(1, 1, 0, 1L, 1, 2, new byte[0]).encode().length);
    }

    @Test
    void rejectsMalformed() {
        assertThrows(IllegalArgumentException.class, () -> Packet.decode(new byte[5]));
        byte[] good = new Packet(1, 5, 0, 1L, 1, 2, new byte[]{9}).encode();
        byte[] truncated = java.util.Arrays.copyOf(good, good.length - 1);
        assertThrows(IllegalArgumentException.class, () -> Packet.decode(truncated));
        good[0] = 99; // bad version
        assertThrows(IllegalArgumentException.class, () -> Packet.decode(good));
    }

    @Test
    void withTtlKeepsEverythingElse() {
        Packet p = new Packet(1, 10, 3, 5L, 1, 2, new byte[]{1});
        Packet q = p.withTtl(9);
        assertEquals(9, q.ttl);
        assertEquals(5L, q.msgId);
        assertEquals(3, q.priority);
    }

    @Test
    void ackTypeAndAttemptShareOneByte() {
        Packet p = new Packet(Packet.TYPE_ACK, 5, 255, 77L, 1, 2, new byte[0], 3);
        byte[] bytes = p.encode();
        assertEquals(Packet.HEADER_SIZE, bytes.length);
        Packet q = Packet.decode(bytes);
        assertEquals(Packet.TYPE_ACK, q.type);
        assertEquals(3, q.attempt);
        assertEquals(255, q.priority);
        assertEquals(4, q.withAttempt(4).attempt);
        assertEquals(3, q.withTtl(2).attempt);
        assertThrows(IllegalArgumentException.class, () -> new Packet(1, 5, 0, 1L, 1, 2, new byte[0], 16));
    }

    @Test
    void zonesTravelWithThePacket() {
        Packet p = new Packet(Packet.TYPE_DATA, 9, 1, 5L, 10, 20, new byte[]{1}, 2, 7, 33);
        Packet q = Packet.decode(p.encode());
        assertEquals(7, q.srcZone);
        assertEquals(33, q.dstZone);
        assertEquals(2, q.attempt);
        assertEquals(44, q.withDstZone(44).dstZone);
        assertEquals(7, q.withDstZone(44).srcZone);
        assertEquals(Packet.UNKNOWN_ZONE, new Packet(1, 1, 0, 1L, 1, 2, new byte[0], 0, 3, Packet.UNKNOWN_ZONE).dstZone);
    }

    @Test
    void zoneAddressesAreNegativeAndRoundTrip() {
        int a = Packet.zoneAddress(1234);
        assertTrue(Packet.isZoneAddress(a));
        assertEquals(1234, a & 0x7FFFFFFF);
        assertFalse(Packet.isZoneAddress(0));
        assertFalse(Packet.isZoneAddress(99999));
        Packet p = new Packet(Packet.TYPE_PUBLISH, 5, 1, 9L, 3, a, new byte[0], 0, 1, 1234);
        assertEquals(a, Packet.decode(p.encode()).dst);
    }
}
