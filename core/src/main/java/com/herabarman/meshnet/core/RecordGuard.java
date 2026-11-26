package com.herabarman.meshnet.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Checks that a location record really comes from the node it names.
 *
 * Every record is signed by its owner over (kind, ns, subject, owner, value, version). The signature travels with the
 * record, so relays pass it on unchanged and anybody can check it. The owner's public key travels too and is bound to
 * the owner id the first time a valid signature is seen (trust on first use): afterwards the same id with a different
 * key is refused, so nobody can take over an id they have already been seen with. Keys known in advance (from a key
 * exchange, a QR code, a server) can be pinned with {@link #pin}, which closes the first-contact gap for them.
 *
 * What this does NOT stop: the very first claim for an id nobody has pinned (the first signer wins), a node
 * replaying an old but genuine record until it expires (the version stops it from beating a newer one), and a
 * node that simply refuses to relay. Expiry and radius are not signed because relays change them; a receiver
 * caps the lifetime itself.
 */
public final class RecordGuard {
    private final RecordAuth auth;
    private final int maxKeys;
    private final Map<Integer, byte[]> keys;
    private boolean idBoundToKey;
    public int rejected;

    public RecordGuard(RecordAuth auth) { this(auth, 8192); }

    public RecordGuard(RecordAuth auth, final int maxKeys) {
        this.auth = auth;
        this.maxKeys = maxKeys;
        this.keys = new LinkedHashMap<Integer, byte[]>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> e) { return size() > RecordGuard.this.maxKeys; }
        };
    }

    public byte[] publicKey() { return auth.publicKey(); }

    /**
     * Closes the first-contact gap completely: when node ids are derived from the public key with {@link #idOf},
     * turn this on and a record is only accepted if its owner id really is the hash of the key that signed it,
     * so nobody can sign for an id that is not theirs, even one nobody has seen before.
     */
    public void setIdBoundToKey(boolean v) { this.idBoundToKey = v; }

    /** The id a node should use so that it is bound to its key: the first 4 bytes of SHA-256(publicKey). */
    public static int idOf(byte[] publicKey) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(publicKey);
            return ByteBuffer.wrap(h).getInt();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Pin a key for an id that was learned over a trusted channel. Replaces whatever was bound before. */
    public void pin(int owner, byte[] publicKey) { keys.put(owner, publicKey.clone()); }

    public boolean knows(int owner) { return keys.containsKey(owner); }

    // ---- gossip records --------------------------------------------------------------------

    private static byte[] gossipBytes(int ns, int subject, int owner, int value, int version) {
        byte[] tag = "mn-gossip-v1".getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(tag.length + 1 + 16).put(tag).put((byte) ns).putInt(subject).putInt(owner).putInt(value).putInt(version).array();
    }

    public byte[] signGossip(int ns, int subject, int owner, int value, int version) {
        return auth.sign(gossipBytes(ns, subject, owner, value, version));
    }

    public boolean checkGossip(int ns, int subject, int owner, int value, int version, byte[] pub, byte[] sig) {
        return check(owner, pub, gossipBytes(ns, subject, owner, value, version), sig);
    }

    // ---- DHT location values ---------------------------------------------------------------
    // value = owner(4) zone(4) pubLen(1) pub sigLen(1) sig, stored under dhtId(owner)

    private static byte[] locationBytes(int owner, int zone, int version) {
        byte[] tag = "mn-dht-loc-v1".getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(tag.length + 12).put(tag).putInt(owner).putInt(zone).putInt(version).array();
    }

    public byte[] signedLocation(int owner, int zone, int version) {
        byte[] pub = auth.publicKey();
        byte[] sig = auth.sign(locationBytes(owner, zone, version));
        return ByteBuffer.allocate(4 + 4 + 1 + pub.length + 1 + sig.length).putInt(owner).putInt(zone)
                .put((byte) pub.length).put(pub).put((byte) sig.length).put(sig).array();
    }

    /** @return the zone if value is a genuine location of the node whose hash is key, else -1 */
    public int checkLocation(int key, byte[] value, int version) {
        try {
            ByteBuffer b = ByteBuffer.wrap(value);
            int owner = b.getInt(), zone = b.getInt();
            if (zone < 0 || DhtNode.dhtId(owner) != key) return reject();
            byte[] pub = new byte[b.get() & 0xFF];
            b.get(pub);
            byte[] sig = new byte[b.get() & 0xFF];
            b.get(sig);
            if (b.hasRemaining()) return reject();
            return check(owner, pub, locationBytes(owner, zone, version), sig) ? zone : -1;
        } catch (RuntimeException e) {
            return reject();
        }
    }

    private int reject() {
        rejected++;
        return -1;
    }

    private boolean check(int owner, byte[] pub, byte[] data, byte[] sig) {
        if (pub == null || sig == null || pub.length == 0 || sig.length == 0) {
            rejected++;
            return false;
        }
        if (idBoundToKey && idOf(pub) != owner) {                   // the id is not the hash of this key: not theirs to sign for
            rejected++;
            return false;
        }
        byte[] bound = keys.get(owner);
        if (bound != null && !Arrays.equals(bound, pub)) {          // this id already has a different key
            rejected++;
            return false;
        }
        boolean ok;
        try {
            ok = auth.verify(pub, data, sig);
        } catch (RuntimeException e) {
            ok = false;
        }
        if (!ok) {
            rejected++;
            return false;
        }
        if (bound == null) keys.put(owner, pub.clone());
        return true;
    }
}
