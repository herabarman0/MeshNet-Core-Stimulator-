package com.herabarman.meshnet.kit;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * The part of STUN (RFC 5389) that hole punching needs: ask a public server "what address and port do you see me
 * at?" (a Binding request) and read the answer (XOR-MAPPED-ADDRESS, or the older MAPPED-ADDRESS). The packet
 * building and parsing are plain functions so they can be checked against known bytes; the asking, with
 * retries, is done by P2pManager on the same UDP socket that will carry the traffic, because a NAT gives every
 * socket its own public port.
 */
public final class StunClient {
    private StunClient() { }

    public static final int MAGIC_COOKIE = 0x2112A442;
    private static final int BINDING_REQUEST = 0x0001;
    private static final int BINDING_RESPONSE = 0x0101;
    private static final int ATTR_MAPPED = 0x0001;
    private static final int ATTR_XOR_MAPPED = 0x0020;

    /** 20-byte Binding request. @param txId 12 bytes */
    public static byte[] bindingRequest(byte[] txId) {
        if (txId.length != 12) throw new IllegalArgumentException("txId");
        ByteBuffer b = ByteBuffer.allocate(20);
        b.putShort((short) BINDING_REQUEST).putShort((short) 0).putInt(MAGIC_COOKIE).put(txId);
        return b.array();
    }

    /** True if this datagram looks like a STUN message (top two bits zero, magic cookie in place). */
    public static boolean looksLikeStun(byte[] d) {
        return d.length >= 20 && (d[0] & 0xC0) == 0 && ByteBuffer.wrap(d, 4, 4).getInt() == MAGIC_COOKIE;
    }

    /** @return the transaction id of a STUN message, or null */
    public static byte[] transactionId(byte[] d) {
        return looksLikeStun(d) ? Arrays.copyOfRange(d, 8, 20) : null;
    }

    /**
     * @return the address the server saw, or null if this is not a successful Binding response for txId
     */
    public static InetSocketAddress parseBindingResponse(byte[] d, byte[] txId) {
        if (!looksLikeStun(d)) return null;
        ByteBuffer b = ByteBuffer.wrap(d);
        int type = b.getShort(0) & 0xFFFF;
        int len = b.getShort(2) & 0xFFFF;
        if (type != BINDING_RESPONSE || 20 + len > d.length) return null;
        if (!Arrays.equals(Arrays.copyOfRange(d, 8, 20), txId)) return null;
        InetSocketAddress mapped = null;
        int pos = 20, end = 20 + len;
        while (pos + 4 <= end) {
            int at = b.getShort(pos) & 0xFFFF;
            int al = b.getShort(pos + 2) & 0xFFFF;
            int v = pos + 4;
            if (v + al > end) return null;
            if (at == ATTR_XOR_MAPPED) {
                InetSocketAddress a = address(d, v, al, true, txId);
                if (a != null) return a;                               // the XOR form wins
            } else if (at == ATTR_MAPPED && mapped == null) {
                mapped = address(d, v, al, false, txId);
            }
            pos = v + ((al + 3) & ~3);
        }
        return mapped;
    }

    private static InetSocketAddress address(byte[] d, int v, int len, boolean xor, byte[] txId) {
        if (len < 8) return null;
        int family = d[v + 1] & 0xFF;
        int port = ((d[v + 2] & 0xFF) << 8) | (d[v + 3] & 0xFF);
        if (xor) port ^= (MAGIC_COOKIE >>> 16);
        byte[] addr;
        if (family == 1 && len >= 8) {
            addr = Arrays.copyOfRange(d, v + 4, v + 8);
            if (xor) {
                byte[] m = ByteBuffer.allocate(4).putInt(MAGIC_COOKIE).array();
                for (int i = 0; i < 4; i++) addr[i] ^= m[i];
            }
        } else if (family == 2 && len >= 20) {
            addr = Arrays.copyOfRange(d, v + 4, v + 20);
            if (xor) {
                byte[] m = new byte[16];
                ByteBuffer.wrap(m).putInt(MAGIC_COOKIE).put(txId);
                for (int i = 0; i < 16; i++) addr[i] ^= m[i];
            }
        } else {
            return null;
        }
        try {
            return new InetSocketAddress(InetAddress.getByAddress(addr), port);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** Builds a Binding success response (for tests and for running your own tiny STUN responder). */
    public static byte[] bindingResponse(byte[] txId, InetSocketAddress seenAs) {
        byte[] ip = seenAs.getAddress().getAddress();
        if (ip.length != 4) throw new IllegalArgumentException("IPv4 only");
        ByteBuffer b = ByteBuffer.allocate(20 + 12);
        b.putShort((short) BINDING_RESPONSE).putShort((short) 12).putInt(MAGIC_COOKIE).put(txId);
        b.putShort((short) ATTR_XOR_MAPPED).putShort((short) 8).put((byte) 0).put((byte) 1);
        b.putShort((short) (seenAs.getPort() ^ (MAGIC_COOKIE >>> 16)));
        byte[] m = ByteBuffer.allocate(4).putInt(MAGIC_COOKIE).array();
        for (int i = 0; i < 4; i++) b.put((byte) (ip[i] ^ m[i]));
        return b.array();
    }
}
