package com.herabarman.meshnet.kit;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Payload layout inside one mesh packet (after encryption is removed). First byte = type.
 * Binary only, no JSON: a text message costs 17 bytes plus the text, and the mesh adds the 30 byte
 * header and 17 bytes of encryption on top.
 *
 *  TEXT    0x10 localId(8) timestamp(8) utf8...
 *  SEEN    0x11 count(1) localId(8)*count        (read receipts, sent to the original sender)
 *  RCPT    0x12 count(1) localId(8)*count        ("I have it": the only thing that completes a message)
 *  KEY_REQ 0x20 publicKey(65)                    (my key, please send yours)
 *  KEY_RES 0x21 publicKey(65)
 *  APP     0x80 localId(8) bytes...              (anything your app wants to carry)
 *  TEXT_Z 0x13 / APP_Z 0x81                      same layouts, body deflated (used when it is 200+ bytes and smaller)
 *  EXT     0x90 localId(8) extType(1) payload...   (see MeshClient.registerExt)
 *  BLOB    0x30 localId(8) blobId(8) index(2) total(2) bytes (one chunk of a large payload, see MeshClient.sendBlob)
 */
public final class Frame {
    private Frame() {}

    public static final int TEXT = 0x10;
    public static final int SEEN = 0x11;
    public static final int RCPT = 0x12;
    public static final int KEY_REQ = 0x20;
    public static final int KEY_RES = 0x21;
    /** "I lost our session, start a new one". Clear, carries nothing. */
    public static final int RESET = 0x22;
    public static final int TEXT_Z = 0x13;
    public static final int APP = 0x80;
    public static final int APP_Z = 0x81;
    public static final int BLOB = 0x30;
    /** Extension frame: lets DTN, groups and your own features ride on the same encrypted, retried, confirmed channel. */
    public static final int EXT = 0x90;

    /** Bodies shorter than this are never compressed (it would only add bytes). */
    public static final int COMPRESS_MIN = 200;
    /** Largest size a compressed body may inflate to: protects against decompression bombs. */
    public static final int MAX_INFLATED = 256 * 1024;
    public static final int BLOB_CHUNK = 12 * 1024;

    /** Largest app payload that still fits one Nearby bytes payload (32 KB) with headers to spare. */
    public static final int MAX_BODY = 30_000;

    public static int type(byte[] f) { return f.length == 0 ? -1 : f[0] & 0xFF; }

    /** Key and reset frames are the only ones sent unencrypted (public keys / no content). */
    public static boolean isKeyFrame(byte[] f) {
        int t = type(f);
        return t == KEY_REQ || t == KEY_RES || t == RESET;
    }

    public static byte[] text(long localId, long ts, String s) {
        byte[] t = s.getBytes(StandardCharsets.UTF_8);
        int type = TEXT;
        if (t.length >= COMPRESS_MIN) {
            byte[] z = deflate(t);
            if (z.length < t.length) { t = z; type = TEXT_Z; }
        }
        if (t.length > MAX_BODY) throw new IllegalArgumentException("text too long");
        return ByteBuffer.allocate(17 + t.length).put((byte) type).putLong(localId).putLong(ts).put(t).array();
    }

    public static byte[] app(long localId, byte[] data) {
        int type = APP;
        if (data.length >= COMPRESS_MIN) {
            byte[] z = deflate(data);
            if (z.length < data.length) { data = z; type = APP_Z; }
        }
        if (data.length > MAX_BODY) throw new IllegalArgumentException("payload too long");
        return ByteBuffer.allocate(9 + data.length).put((byte) type).putLong(localId).put(data).array();
    }

    public static byte[] ext(long localId, int extType, byte[] payload) {
        if (payload.length > MAX_BODY) throw new IllegalArgumentException("payload too long");
        return ByteBuffer.allocate(10 + payload.length).put((byte) EXT).putLong(localId).put((byte) extType).put(payload).array();
    }

    public static int extType(byte[] f) { return f[9] & 0xFF; }
    public static byte[] extPayload(byte[] f) { return java.util.Arrays.copyOfRange(f, 10, f.length); }

    public static byte[] blob(long localId, long blobId, int index, int total, byte[] data, int off, int len) {
        return ByteBuffer.allocate(21 + len).put((byte) BLOB).putLong(localId).putLong(blobId).putShort((short) index)
                .putShort((short) total).put(data, off, len).array();
    }

    public static long blobId(byte[] f) { return ByteBuffer.wrap(f, 9, 8).getLong(); }
    public static int blobIndex(byte[] f) { return ByteBuffer.wrap(f, 17, 2).getShort() & 0xFFFF; }
    public static int blobTotal(byte[] f) { return ByteBuffer.wrap(f, 19, 2).getShort() & 0xFFFF; }
    public static byte[] blobData(byte[] f) { return java.util.Arrays.copyOfRange(f, 21, f.length); }

    static byte[] deflate(byte[] in) {
        java.util.zip.Deflater d = new java.util.zip.Deflater(6, true);
        try {
            d.setInput(in);
            d.finish();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(in.length / 2 + 16);
            byte[] buf = new byte[1024];
            while (!d.finished()) out.write(buf, 0, d.deflate(buf));
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();   // raw deflate has no checksum of its own: a flipped bit would decode to silent garbage
            crc.update(in, 0, in.length);
            long c = crc.getValue();
            for (int i = 3; i >= 0; i--) out.write((int) (c >>> (8 * i)) & 0xFF);
            return out.toByteArray();
        } finally {
            d.end();
        }
    }

    /** @throws IllegalArgumentException if the data is corrupt or inflates beyond MAX_INFLATED */
    static byte[] inflate(byte[] in, int off, int len) {
        java.util.zip.Inflater inf = new java.util.zip.Inflater(true);
        try {
            if (len < 4) throw new IllegalArgumentException("truncated");
            len -= 4;                                           // the last 4 bytes are the CRC32 of the original
            inf.setInput(in, off, len);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(len * 3);
            byte[] buf = new byte[2048];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) throw new IllegalArgumentException("truncated");
                out.write(buf, 0, n);
                if (out.size() > MAX_INFLATED) throw new IllegalArgumentException("inflates too large");
            }
            byte[] res = out.toByteArray();
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(res, 0, res.length);
            long want = 0;
            for (int i = 0; i < 4; i++) want = (want << 8) | (in[off + len + i] & 0xFF);
            if (crc.getValue() != want) throw new IllegalArgumentException("checksum mismatch");
            return res;
        } catch (java.util.zip.DataFormatException e) {
            throw new IllegalArgumentException("corrupt", e);
        } finally {
            inf.end();
        }
    }

    public static byte[] seen(long[] ids) {
        int n = Math.min(ids.length, 255);
        ByteBuffer b = ByteBuffer.allocate(2 + 8 * n).put((byte) SEEN).put((byte) n);
        for (int i = 0; i < n; i++) b.putLong(ids[i]);
        return b.array();
    }

    public static byte[] rcpt(long id) {
        return ByteBuffer.allocate(10).put((byte) RCPT).put((byte) 1).putLong(id).array();
    }

    public static byte[] key(int type, byte[] pub) {
        return ByteBuffer.allocate(1 + pub.length).put((byte) type).put(pub).array();
    }

    public static long localId(byte[] f) { return ByteBuffer.wrap(f, 1, 8).getLong(); }
    public static long timestamp(byte[] f) { return ByteBuffer.wrap(f, 9, 8).getLong(); }
    public static String textOf(byte[] f) {
        if (type(f) == TEXT_Z) return new String(inflate(f, 17, f.length - 17), StandardCharsets.UTF_8);
        return new String(f, 17, f.length - 17, StandardCharsets.UTF_8);
    }
    public static byte[] appBytes(byte[] f) {
        if (type(f) == APP_Z) return inflate(f, 9, f.length - 9);
        return java.util.Arrays.copyOfRange(f, 9, f.length);
    }
    public static byte[] keyOf(byte[] f) { return java.util.Arrays.copyOfRange(f, 1, f.length); }

    public static long[] seenIds(byte[] f) {
        int n = f[1] & 0xFF;
        if (f.length < 2 + 8 * n) throw new IllegalArgumentException("short");
        long[] ids = new long[n];
        ByteBuffer b = ByteBuffer.wrap(f, 2, 8 * n);
        for (int i = 0; i < n; i++) ids[i] = b.getLong();
        return ids;
    }

    /** Structural check so a malformed frame from the network is dropped, never thrown at the app. */
    public static boolean valid(byte[] f) {
        switch (type(f)) {
            case TEXT:
            case TEXT_Z: return f.length >= 17;
            case APP:
            case APP_Z: return f.length >= 9;
            case EXT: return f.length >= 10;
            case BLOB: return f.length >= 22 && blobTotal(f) > 0 && blobTotal(f) <= 400 && blobIndex(f) < blobTotal(f);
            case SEEN:
            case RCPT: return f.length >= 2 && f.length >= 2 + 8 * (f[1] & 0xFF);
            case RESET: return f.length == 1;
            case KEY_REQ:
            case KEY_RES: return f.length == 66 && f[1] == 0x04;
            default: return false;
        }
    }
}
