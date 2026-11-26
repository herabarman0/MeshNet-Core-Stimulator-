package com.herabarman.meshnet.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Describes one release of an app, signed by the publisher. A phone only accepts a manifest whose publisher key it
 * trusts (pinned in the app), whose appId is its own and whose versionCode is higher than what it runs: so
 * an update can neither be forged nor be used to roll somebody back to an old, vulnerable version.
 */
public final class OtaManifest {
    public final String appId;
    public final int versionCode;
    public final String versionName;
    public final int size;
    public final int chunkSize;
    public final byte[] root;          // Merkle root of the chunks
    public final byte[] publisherKey;
    public final byte[] signature;

    public OtaManifest(String appId, int versionCode, String versionName, int size, int chunkSize, byte[] root,
                       byte[] publisherKey, byte[] signature) {
        this.appId = appId;
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.size = size;
        this.chunkSize = chunkSize;
        this.root = root;
        this.publisherKey = publisherKey;
        this.signature = signature;
    }

    public int chunkCount() { return Math.max(1, (size + chunkSize - 1) / chunkSize); }

    /** Build and sign a manifest for a release file. */
    public static OtaManifest create(String appId, int versionCode, String versionName, byte[] file, int chunkSize, RecordAuth publisher) {
        byte[] root = new MerkleTree(file, chunkSize).root();
        byte[] signed = signedBytes(appId, versionCode, versionName, file.length, chunkSize, root);
        return new OtaManifest(appId, versionCode, versionName, file.length, chunkSize, root, publisher.publicKey(), publisher.sign(signed));
    }

    private static byte[] signedBytes(String appId, int versionCode, String versionName, int size, int chunkSize, byte[] root) {
        byte[] tag = "mn-ota-v1".getBytes(StandardCharsets.US_ASCII);
        byte[] a = appId.getBytes(StandardCharsets.UTF_8), v = versionName.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(tag.length + 1 + a.length + 4 + 1 + v.length + 4 + 4 + root.length)
                .put(tag).put((byte) a.length).put(a).putInt(versionCode).put((byte) v.length).put(v).putInt(size).putInt(chunkSize).put(root).array();
    }

    /** Signature valid under the carried publisher key (whether that key is TRUSTED is the caller's decision). */
    public boolean signatureValid(RecordAuth verifier) {
        try {
            return verifier.verify(publisherKey, signedBytes(appId, versionCode, versionName, size, chunkSize, root), signature);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public byte[] encode() {
        byte[] a = appId.getBytes(StandardCharsets.UTF_8), v = versionName.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(1 + a.length + 4 + 1 + v.length + 4 + 4 + root.length + 1 + publisherKey.length + 1 + signature.length)
                .put((byte) a.length).put(a).putInt(versionCode).put((byte) v.length).put(v).putInt(size).putInt(chunkSize).put(root)
                .put((byte) publisherKey.length).put(publisherKey).put((byte) signature.length).put(signature).array();
    }

    /** @return null if malformed or absurd (zero or huge sizes). */
    public static OtaManifest decode(byte[] m) {
        try {
            ByteBuffer b = ByteBuffer.wrap(m);
            byte[] a = new byte[b.get() & 0xFF];
            b.get(a);
            int versionCode = b.getInt();
            byte[] v = new byte[b.get() & 0xFF];
            b.get(v);
            int size = b.getInt(), chunkSize = b.getInt();
            byte[] root = new byte[MerkleTree.HASH];
            b.get(root);
            byte[] pub = new byte[b.get() & 0xFF];
            b.get(pub);
            byte[] sig = new byte[b.get() & 0xFF];
            b.get(sig);
            if (b.hasRemaining() || size <= 0 || size > 256 * 1024 * 1024 || chunkSize < 64 || chunkSize > 32 * 1024) return null;
            return new OtaManifest(new String(a, StandardCharsets.UTF_8), versionCode, new String(v, StandardCharsets.UTF_8), size, chunkSize, root, pub, sig);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public boolean sameRelease(OtaManifest o) {
        return o != null && appId.equals(o.appId) && versionCode == o.versionCode && java.util.Arrays.equals(root, o.root);
    }
}
