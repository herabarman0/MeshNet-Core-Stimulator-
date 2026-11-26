package com.herabarman.meshnet.core;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Steganography & Censorship-Resistant Traffic Masking (DPI Evasion).
 * Masks encrypted MeshNet packets as benign standard traffic when firewalls, deep-packet
 * inspection (DPI), or protocol throttlers attempt to block mesh traffic:
 *   1. HTTPS/TLS 1.3 Application Data Mimicry (0x17 0x03 0x03 <len> + Keccak SHAKE128 keystream + random padding)
 *   2. Voice Audio Steganography (embeds payload into 2 LSBs of 16-bit PCM audio carrier frames)
 *   3. Image Pixel Steganography (embeds payload into 2 LSBs of RGB raster carrier bytes)
 */
public final class TrafficMasker {
    private static final SecureRandom RNG = new SecureRandom();
    private static final byte[] DOMAIN = "meshnet-dpi-cloak-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final int MAGIC_STEGO = 0x4D535447; // "MSTG"

    private TrafficMasker() {}

    /**
     * Disguise a MeshNet frame inside a TLS 1.3 Application Data record (record type 0x17, TLS 1.2 compat 0x0303).
     * Shared psk (32B, or null to use default domain mask) prevents DPI signature detection across relays.
     */
    public static byte[] maskAsHttpsTls(byte[] packet, byte[] sharedMaskKey) {
        if (packet == null) throw new IllegalArgumentException("null packet");
        int padLen = 8 + (RNG.nextInt(24)); // randomize record length to defeat traffic-size fingerprinting
        int bodyLen = 16 + 2 + packet.length + padLen + 8; // nonce(16) + len(2) + data + pad + tag(8)
        byte[] nonce = new byte[16];
        RNG.nextBytes(nonce);

        byte[] inner = new byte[2 + packet.length + padLen];
        inner[0] = (byte) ((packet.length >>> 8) & 0xFF);
        inner[1] = (byte) (packet.length & 0xFF);
        System.arraycopy(packet, 0, inner, 2, packet.length);
        byte[] pad = new byte[padLen];
        RNG.nextBytes(pad);
        System.arraycopy(pad, 0, inner, 2 + packet.length, padLen);

        byte[] stream = keystream(sharedMaskKey, nonce, inner.length);
        for (int i = 0; i < inner.length; i++) inner[i] ^= stream[i];
        byte[] tag = authTag(sharedMaskKey, nonce, inner);

        ByteBuffer out = ByteBuffer.allocate(5 + bodyLen);
        out.put((byte) 0x17).put((byte) 0x03).put((byte) 0x03).putShort((short) bodyLen);
        out.put(nonce).put(inner).put(tag, 0, 8);
        return out.array();
    }

    /** Extract and verify a MeshNet frame from a disguised TLS 1.3 Application Data record. */
    public static byte[] unmaskHttpsTls(byte[] tlsRecord, byte[] sharedMaskKey) {
        if (tlsRecord == null || tlsRecord.length < 5 + 16 + 2 + 8) return null;
        if ((tlsRecord[0] & 0xFF) != 0x17 || (tlsRecord[1] & 0xFF) != 0x03 || (tlsRecord[2] & 0xFF) != 0x03) {
            return null;
        }
        int recLen = ((tlsRecord[3] & 0xFF) << 8) | (tlsRecord[4] & 0xFF);
        if (recLen + 5 != tlsRecord.length || recLen < 16 + 2 + 8) return null;
        byte[] nonce = Arrays.copyOfRange(tlsRecord, 5, 21);
        int innerLen = recLen - 16 - 8;
        byte[] inner = Arrays.copyOfRange(tlsRecord, 21, 21 + innerLen);
        byte[] tag = Arrays.copyOfRange(tlsRecord, 21 + innerLen, tlsRecord.length);
        byte[] expected = Arrays.copyOf(authTag(sharedMaskKey, nonce, inner), 8);
        if (!Arrays.equals(tag, expected)) return null;

        byte[] stream = keystream(sharedMaskKey, nonce, inner.length);
        for (int i = 0; i < inner.length; i++) inner[i] ^= stream[i];
        int pktLen = ((inner[0] & 0xFF) << 8) | (inner[1] & 0xFF);
        if (pktLen < 0 || 2 + pktLen > inner.length) return null;
        return Arrays.copyOfRange(inner, 2, 2 + pktLen);
    }

    /**
     * Embed a MeshNet packet into the 2 least-significant bits of a carrier byte buffer (PCM audio or RGB image).
     * Carrier must have at least (8 + packet.length) * 4 bytes.
     */
    public static byte[] embedLsbStego(byte[] packet, byte[] carrier) {
        if (packet == null || carrier == null) throw new IllegalArgumentException("null input");
        byte[] envelope = ByteBuffer.allocate(12 + packet.length)
                .putInt(MAGIC_STEGO)
                .putInt(packet.length)
                .put(Keccak.sha3_256(packet), 0, 4)
                .put(packet)
                .array();
        int needed = envelope.length * 4;
        if (carrier.length < needed) {
            throw new IllegalArgumentException("carrier too small: need " + needed + " bytes, have " + carrier.length);
        }
        byte[] stego = carrier.clone();
        int idx = 0;
        for (byte b : envelope) {
            int val = b & 0xFF;
            for (int shift = 6; shift >= 0; shift -= 2) {
                int twoBits = (val >>> shift) & 0x03;
                stego[idx] = (byte) ((stego[idx] & 0xFC) | twoBits);
                idx++;
            }
        }
        return stego;
    }

    /** Extract a hidden MeshNet packet from a steganographic PCM audio or RGB image buffer. */
    public static byte[] extractLsbStego(byte[] stego) {
        if (stego == null || stego.length < 12 * 4) return null;
        byte[] hdr = readLsbBytes(stego, 0, 12);
        ByteBuffer hb = ByteBuffer.wrap(hdr);
        if (hb.getInt() != MAGIC_STEGO) return null;
        int len = hb.getInt();
        byte[] check = new byte[4];
        hb.get(check);
        if (len < 0 || (12 + len) * 4 > stego.length) return null;
        byte[] payload = readLsbBytes(stego, 12, len);
        byte[] actual = Arrays.copyOf(Keccak.sha3_256(payload), 4);
        return Arrays.equals(check, actual) ? payload : null;
    }

    private static byte[] readLsbBytes(byte[] stego, int byteOffset, int byteCount) {
        byte[] out = new byte[byteCount];
        int carrierIdx = byteOffset * 4;
        for (int i = 0; i < byteCount; i++) {
            int b = 0;
            for (int k = 0; k < 4; k++) {
                b = (b << 2) | (stego[carrierIdx++] & 0x03);
            }
            out[i] = (byte) b;
        }
        return out;
    }

    private static byte[] keystream(byte[] key, byte[] nonce, int len) {
        byte[] k = key == null ? DOMAIN : key;
        byte[] seed = new byte[k.length + nonce.length];
        System.arraycopy(k, 0, seed, 0, k.length);
        System.arraycopy(nonce, 0, seed, k.length, nonce.length);
        return Keccak.shake128(seed, len);
    }

    private static byte[] authTag(byte[] key, byte[] nonce, byte[] inner) {
        byte[] k = key == null ? DOMAIN : key;
        byte[] buf = new byte[k.length + nonce.length + inner.length];
        System.arraycopy(k, 0, buf, 0, k.length);
        System.arraycopy(nonce, 0, buf, k.length, nonce.length);
        System.arraycopy(inner, 0, buf, k.length + nonce.length, inner.length);
        return Keccak.sha3_256(buf);
    }
}
