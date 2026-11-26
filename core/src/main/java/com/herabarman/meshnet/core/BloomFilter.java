package com.herabarman.meshnet.core;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Compact wire-serializable Bloom filter for DTN/gossip anti-entropy summaries.
 * Allows two peers to exchange a summary of hundreds of known packet/record IDs in a single
 * small control frame instead of enumerating raw 64-bit IDs.
 */
public final class BloomFilter {
    private final byte[] bits;
    private final int numBits;
    private final int hashes;

    public BloomFilter(int byteCount, int hashes) {
        if (byteCount < 4 || byteCount > 4096) throw new IllegalArgumentException("byteCount");
        if (hashes < 1 || hashes > 8) throw new IllegalArgumentException("hashes");
        this.bits = new byte[byteCount];
        this.numBits = byteCount * 8;
        this.hashes = hashes;
    }

    private BloomFilter(byte[] bits, int hashes) {
        this.bits = bits;
        this.numBits = bits.length * 8;
        this.hashes = hashes;
    }

    /** Creates a filter sized for {@code expectedItems} at roughly 1% false-positive rate. */
    public static BloomFilter forCapacity(int expectedItems) {
        int n = Math.max(8, expectedItems);
        int bytes = Math.max(8, Math.min(4096, (int) Math.ceil((n * 10.0) / 8.0)));
        return new BloomFilter(bytes, 4);
    }

    public void add(long id) {
        long h1 = mix64(id);
        long h2 = mix64(id ^ 0x9E3779B97F4A7C15L) | 1L;
        for (int i = 0; i < hashes; i++) {
            int bit = (int) (Long.remainderUnsigned(h1 + i * h2, numBits));
            bits[bit >>> 3] |= (byte) (1 << (bit & 7));
        }
    }

    public boolean mightContain(long id) {
        long h1 = mix64(id);
        long h2 = mix64(id ^ 0x9E3779B97F4A7C15L) | 1L;
        for (int i = 0; i < hashes; i++) {
            int bit = (int) (Long.remainderUnsigned(h1 + i * h2, numBits));
            if ((bits[bit >>> 3] & (1 << (bit & 7))) == 0) return false;
        }
        return true;
    }

    public void merge(BloomFilter other) {
        if (other.bits.length != bits.length || other.hashes != hashes) {
            throw new IllegalArgumentException("incompatible bloom filters");
        }
        for (int i = 0; i < bits.length; i++) bits[i] |= other.bits[i];
    }

    public int setBitCount() {
        int c = 0;
        for (byte b : bits) c += Integer.bitCount(b & 0xff);
        return c;
    }

    public int byteSize() {
        return bits.length;
    }

    public byte[] toBytes() {
        ByteBuffer b = ByteBuffer.allocate(3 + bits.length);
        b.put((byte) hashes).putShort((short) bits.length).put(bits);
        return b.array();
    }

    public static BloomFilter fromBytes(byte[] raw) {
        if (raw == null || raw.length < 7) throw new IllegalArgumentException("short bloom");
        ByteBuffer b = ByteBuffer.wrap(raw);
        int hashes = b.get() & 0xff;
        int len = b.getShort() & 0xffff;
        if (hashes < 1 || hashes > 8 || raw.length != 3 + len) throw new IllegalArgumentException("bad bloom");
        byte[] bits = Arrays.copyOfRange(raw, 3, raw.length);
        return new BloomFilter(bits, hashes);
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
