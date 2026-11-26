package com.herabarman.meshnet.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Multi-hop Onion Routing & Metadata Protection:
 * Wraps payloads in layered ephemeral X25519 + Keccak-SHAKE128 encrypted envelopes so that every
 * intermediate relay node can only peel its own layer to discover the immediate next hop, without
 * learning the original sender, the remaining path length, or the final destination.
 *
 * Layer wire format (per hop):
 *   ephemX25519Pub(32) | mac16(16) | xorStream( flags(1) + nextHop(4) + innerLen(2) + innerBody(...) )
 */
public final class OnionRouter {
    private static final int KEY_BYTES = 32;
    private static final int MAC_BYTES = 16;
    private static final int LAYER_HEADER = 7; // flags(1) + nextHop(4) + innerLen(2)
    private static final int FLAG_FINAL = 1;
    private static final byte[] MAC_DOMAIN = "meshnet-onion-mac-v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STREAM_DOMAIN = "meshnet-onion-enc-v1".getBytes(StandardCharsets.US_ASCII);

    private OnionRouter() {}

    public static final class PeelResult {
        public final boolean isFinal;
        public final int nextHop;
        public final byte[] payloadOrInner;

        public PeelResult(boolean isFinal, int nextHop, byte[] payloadOrInner) {
            this.isFinal = isFinal;
            this.nextHop = nextHop;
            this.payloadOrInner = payloadOrInner;
        }
    }

    /**
     * Wraps {@code payload} in layered onion envelopes for {@code routeNodes} ending at {@code routeNodes[n - 1]}.
     * @param routeNodes ordered list of relay node IDs followed by the final destination ID (length >= 1)
     * @param nodePubKeys matching X25519 32-byte public keys for each node in {@code routeNodes}
     */
    public static byte[] wrap(int[] routeNodes, byte[][] nodePubKeys, byte[] payload) {
        if (routeNodes == null || nodePubKeys == null || routeNodes.length == 0 || routeNodes.length != nodePubKeys.length) {
            throw new IllegalArgumentException("routeNodes and nodePubKeys must match and be non-empty");
        }
        byte[] current = payload.clone();
        for (int i = routeNodes.length - 1; i >= 0; i--) {
            boolean isFinal = (i == routeNodes.length - 1);
            int nextHop = isFinal ? routeNodes[i] : routeNodes[i + 1];
            current = wrapLayer(nodePubKeys[i], isFinal, nextHop, current);
        }
        return current;
    }

    private static byte[] wrapLayer(byte[] hopPub, boolean isFinal, int nextHop, byte[] inner) {
        if (hopPub == null || hopPub.length != KEY_BYTES) {
            throw new IllegalArgumentException("invalid hop X25519 pubkey");
        }
        byte[][] ephem = KeyExchange.generateRawKeyPair();
        byte[] ephemPriv = ephem[0];
        byte[] ephemPub = ephem[1];
        byte[] shared = KeyExchange.deriveRawKey(ephemPriv, hopPub);

        byte[] plain = ByteBuffer.allocate(LAYER_HEADER + inner.length)
                .put((byte) (isFinal ? FLAG_FINAL : 0))
                .putInt(nextHop)
                .putShort((short) inner.length)
                .put(inner)
                .array();

        byte[] cipher = xorWithStream(shared, ephemPub, plain);
        byte[] mac = computeMac(shared, ephemPub, cipher);

        return ByteBuffer.allocate(KEY_BYTES + MAC_BYTES + cipher.length)
                .put(ephemPub)
                .put(mac)
                .put(cipher)
                .array();
    }

    /**
     * Peels one onion layer using this node's X25519 private key.
     * @return decoded layer with {@code isFinal}, {@code nextHop}, and inner payload/envelope, or {@code null} on auth failure.
     */
    public static PeelResult peel(byte[] myX25519Priv, byte[] onionEnvelope) {
        if (myX25519Priv == null || myX25519Priv.length != KEY_BYTES) return null;
        if (onionEnvelope == null || onionEnvelope.length < KEY_BYTES + MAC_BYTES + LAYER_HEADER) return null;

        byte[] ephemPub = Arrays.copyOfRange(onionEnvelope, 0, KEY_BYTES);
        byte[] mac = Arrays.copyOfRange(onionEnvelope, KEY_BYTES, KEY_BYTES + MAC_BYTES);
        byte[] cipher = Arrays.copyOfRange(onionEnvelope, KEY_BYTES + MAC_BYTES, onionEnvelope.length);

        byte[] shared = KeyExchange.deriveRawKey(myX25519Priv, ephemPub);
        byte[] expectedMac = computeMac(shared, ephemPub, cipher);
        if (!constantTimeEquals(mac, expectedMac)) return null;

        byte[] plain = xorWithStream(shared, ephemPub, cipher);
        ByteBuffer b = ByteBuffer.wrap(plain);
        boolean isFinal = (b.get() & FLAG_FINAL) != 0;
        int nextHop = b.getInt();
        int innerLen = b.getShort() & 0xFFFF;
        if (innerLen > b.remaining()) return null;
        byte[] inner = new byte[innerLen];
        b.get(inner);
        return new PeelResult(isFinal, nextHop, inner);
    }

    private static byte[] xorWithStream(byte[] shared, byte[] ephemPub, byte[] data) {
        byte[] seed = new byte[STREAM_DOMAIN.length + shared.length + ephemPub.length];
        System.arraycopy(STREAM_DOMAIN, 0, seed, 0, STREAM_DOMAIN.length);
        System.arraycopy(shared, 0, seed, STREAM_DOMAIN.length, shared.length);
        System.arraycopy(ephemPub, 0, seed, STREAM_DOMAIN.length + shared.length, ephemPub.length);
        byte[] stream = Keccak.shake128(seed, data.length);
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ stream[i]);
        }
        return out;
    }

    private static byte[] computeMac(byte[] shared, byte[] ephemPub, byte[] cipher) {
        byte[] input = new byte[MAC_DOMAIN.length + shared.length + ephemPub.length + cipher.length];
        int pos = 0;
        System.arraycopy(MAC_DOMAIN, 0, input, pos, MAC_DOMAIN.length); pos += MAC_DOMAIN.length;
        System.arraycopy(shared, 0, input, pos, shared.length); pos += shared.length;
        System.arraycopy(ephemPub, 0, input, pos, ephemPub.length); pos += ephemPub.length;
        System.arraycopy(cipher, 0, input, pos, cipher.length);
        byte[] full = Keccak.sha3_256(input);
        return Arrays.copyOf(full, MAC_BYTES);
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= (a[i] ^ b[i]);
        return diff == 0;
    }
}
