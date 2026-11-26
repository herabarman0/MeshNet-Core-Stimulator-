package com.herabarman.meshnet.core;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Merkle tree over the chunks of a file, so a receiver can check EACH chunk the moment it arrives (and so a
 * neighbour that sends garbage is caught at once) with nothing but the 16-byte root from a signed manifest.
 *
 * Nodes are SHA-256 truncated to 16 bytes (128-bit security against second preimages). Leaves and inner nodes use
 * different prefixes, and the leaf also covers its index, so a chunk cannot be moved to another position. A level with an
 * odd number of nodes passes its last node up unchanged.
 */
public final class MerkleTree {
    public static final int HASH = 16;

    private final List<byte[][]> levels = new ArrayList<>();   // levels.get(0) = leaves, last = {root}
    public final int chunkCount;

    public MerkleTree(byte[] data, int chunkSize) {
        chunkCount = Math.max(1, (data.length + chunkSize - 1) / chunkSize);
        byte[][] leaves = new byte[chunkCount][];
        for (int i = 0; i < chunkCount; i++) {
            int from = i * chunkSize, to = Math.min(data.length, from + chunkSize);
            leaves[i] = leaf(i, data, from, to - from);
        }
        levels.add(leaves);
        byte[][] cur = leaves;
        while (cur.length > 1) {
            byte[][] next = new byte[(cur.length + 1) / 2][];
            for (int i = 0; i < next.length; i++) next[i] = 2 * i + 1 < cur.length ? node(cur[2 * i], cur[2 * i + 1]) : cur[2 * i];
            levels.add(next);
            cur = next;
        }
    }

    public byte[] root() { return levels.get(levels.size() - 1)[0].clone(); }

    /** Sibling hashes from the leaf up, concatenated (a missing sibling, at the odd end of a level, is simply skipped). */
    public byte[] proof(int index) {
        List<byte[]> out = new ArrayList<>();
        int i = index;
        for (int lv = 0; lv < levels.size() - 1; lv++) {
            byte[][] level = levels.get(lv);
            int sib = i ^ 1;
            if (sib < level.length) out.add(level[sib]);
            i >>= 1;
        }
        byte[] p = new byte[out.size() * HASH];
        for (int k = 0; k < out.size(); k++) System.arraycopy(out.get(k), 0, p, k * HASH, HASH);
        return p;
    }

    /** True if chunk (data[off, off+len)) at position index of chunkCount chunks belongs to the tree with this root. */
    public static boolean verify(byte[] root, int index, int chunkCount, byte[] data, int off, int len, byte[] proof) {
        if (index < 0 || index >= chunkCount || root == null || root.length != HASH || proof.length % HASH != 0) return false;
        byte[] h = leaf(index, data, off, len);
        int i = index, width = chunkCount, used = 0;
        while (width > 1) {
            int sib = i ^ 1;
            if (sib < width) {
                if ((used + 1) * HASH > proof.length) return false;
                byte[] s = Arrays.copyOfRange(proof, used * HASH, (used + 1) * HASH);
                used++;
                h = (i & 1) == 0 ? node(h, s) : node(s, h);
            }
            i >>= 1;
            width = (width + 1) / 2;
        }
        return used * HASH == proof.length && Arrays.equals(h, root);
    }

    private static byte[] leaf(int index, byte[] data, int off, int len) {
        MessageDigest md = sha();
        md.update((byte) 0);
        md.update(ByteBuffer.allocate(4).putInt(index).array());
        md.update(data, off, len);
        return Arrays.copyOf(md.digest(), HASH);
    }

    private static byte[] node(byte[] l, byte[] r) {
        MessageDigest md = sha();
        md.update((byte) 1);
        md.update(l);
        md.update(r);
        return Arrays.copyOf(md.digest(), HASH);
    }

    private static MessageDigest sha() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
