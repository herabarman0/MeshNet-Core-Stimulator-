package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.Crypto;
import com.herabarman.meshnet.core.HybridKeyExchange;
import com.herabarman.meshnet.core.StorageAdapter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * End-to-end encryption for the core's Crypto interface: a Double Ratchet session per peer (see Ratchet),
 * keyed from the two identity keys that MeshClient exchanges and pins (TOFU).
 *
 * Wire: mode(1) + body. mode 0 = clear, accepted ONLY for key/reset frames (they carry no secrets), so a chat
 * message can never be downgraded to clear text. mode 2 = Double Ratchet message (55 bytes of overhead).
 *
 * Two details that make it work on a lossy mesh where the same message is sent more than once:
 *  - the same plaintext frame sent again to the same peer returns the SAME ciphertext (cache), so retries do not
 *    burn ratchet steps and the receiver's skipped-key store stays small;
 *  - a ciphertext that was already decrypted returns the same plaintext again (cache), so a retry after a lost
 *    receipt is answered normally instead of failing because its message key was already used.
 *
 * Simultaneous start: if both phones begin a session at once, the one with the LOWER node id wins; the other
 * drops its session and adopts the winner's. Messages the loser had already sent are lost on the wire but stay in
 * its Outbox and are re-encrypted and sent again. Called on the engine thread only (except hasKey/forget, which
 * MeshClient also calls on it).
 */
public final class E2eeCrypto implements Crypto {
    /** Thrown by encrypt() when the peer's key is not known yet (MeshClient never lets this happen). */
    public static final class MissingKeyException extends RuntimeException {
        public final int node;
        MissingKeyException(int node) { super("no key for node " + node); this.node = node; }
    }

    public interface MissingKeyListener { void onNeedKey(int node); }

    /** Called when a message from a node cannot be read because I have no session for it (so it should start a new one). */
    public interface SessionLostListener { void onSessionLost(int node); }

    private static final int MODE_CLEAR = 0;
    private static final int MODE_RATCHET = 2;
    private static final int MODE_RATCHET_PADDED = 3;
    private volatile boolean padding;
    private static final int CACHE = 256;

    private final IdentityKeys me;
    private final KeyDirectory directory;
    private final int selfId;
    private final StorageAdapter store;
    private final Map<Integer, Ratchet> sessions = new HashMap<>();
    private final Map<Integer, List<String>> bootSeen = new HashMap<>();
    private final Map<java.nio.ByteBuffer, byte[]> encCache = lru();
    private final Map<java.nio.ByteBuffer, byte[]> decCache = lru();
    private final HybridKeyExchange.HybridIdentity hybridIdentity;
    private final Map<Integer, byte[]> pqcSecrets = new HashMap<>();
    private volatile MissingKeyListener missing;
    private volatile SessionLostListener lost;

    public E2eeCrypto(int selfId, IdentityKeys me, KeyDirectory directory) { this(selfId, me, directory, null); }

    public E2eeCrypto(int selfId, IdentityKeys me, KeyDirectory directory, StorageAdapter store) {
        this.selfId = selfId;
        this.me = me;
        this.directory = directory;
        this.store = store;
        this.hybridIdentity = HybridKeyExchange.generateIdentity();
    }

    private static <V> Map<java.nio.ByteBuffer, V> lru() {
        return new LinkedHashMap<java.nio.ByteBuffer, V>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<java.nio.ByteBuffer, V> e) { return size() > CACHE; }
        };
    }

    /** Pad plaintexts to size buckets (128, 256, ... bytes) so a relay or a jammer cannot tell a greeting from a long message. */
    public void setPadding(boolean on) { this.padding = on; }

    static byte[] pad(byte[] plain) {
        int need = plain.length + 2, bucket = 128;
        while (bucket < need && bucket < 32768) bucket *= 2;
        if (bucket < need) bucket = (need + 4095) / 4096 * 4096;
        byte[] out = new byte[bucket];
        out[0] = (byte) (plain.length >> 8);
        out[1] = (byte) plain.length;
        System.arraycopy(plain, 0, out, 2, plain.length);
        return out;
    }

    static byte[] unpad(byte[] p) {
        if (p == null || p.length < 2) return null;
        int len = ((p[0] & 0xFF) << 8) | (p[1] & 0xFF);
        return len > p.length - 2 ? null : Arrays.copyOfRange(p, 2, 2 + len);
    }

    public void setMissingKeyListener(MissingKeyListener l) { this.missing = l; }
    public void setSessionLostListener(SessionLostListener l) { this.lost = l; }

    public boolean hasKey(int node) { return directory.get(node) != null; }

    /** The peer's key changed (or was removed): drop the session, a new one starts from the new key. */
    public void forget(int node) {
        voiceKeys.remove(node);
        sessions.remove(node);
        bootSeen.remove(node);
        dropCaches(node);
        if (store != null) { store.remove("dr-" + node); store.remove("drb-" + node); }
    }

    /** I lost a session at the other end ("RESET" received): start over on my side too. */
    public void resetSession(int node) {
        sessions.remove(node);
        dropCaches(node);
        if (store != null) store.remove("dr-" + node);
    }

    /** True if a ratchet session with this node exists (tests and diagnostics). */
    public boolean hasSession(int node) { return session(node) != null; }

    void clearCaches() { encCache.clear(); decCache.clear(); }

    private void dropCaches(int node) {
        byte[] tag = ByteBuffer.allocate(4).putInt(node).array();
        for (java.util.Iterator<java.nio.ByteBuffer> it = encCache.keySet().iterator(); it.hasNext();) {
            if (startsWith(it.next(), tag)) it.remove();
        }
        for (java.util.Iterator<java.nio.ByteBuffer> it = decCache.keySet().iterator(); it.hasNext();) {
            if (startsWith(it.next(), tag)) it.remove();
        }
    }

    private static boolean startsWith(java.nio.ByteBuffer b, byte[] tag) {
        for (int i = 0; i < tag.length; i++) if (b.get(i) != tag[i]) return false;
        return true;
    }

    // ------------------------------------------------------------------ sessions

    private Ratchet session(int node) {
        Ratchet r = sessions.get(node);
        if (r == null && store != null) {
            byte[] b = store.get("dr-" + node);
            if (b != null) {
                r = Ratchet.deserialize(b);
                if (r != null) sessions.put(node, r);
            }
        }
        return r;
    }

    private void persist(int node, Ratchet r) {
        sessions.put(node, r);
        if (store != null) store.put("dr-" + node, r.serialize());
    }

    public byte[] hybridX25519Pub() { return hybridIdentity.x25519Pub.clone(); }
    public byte[] mlKemEncapsulationKey() { return hybridIdentity.mlKemEk.clone(); }
    public boolean hasPqcSession(int node) { return pqcSecrets.containsKey(node); }

    /** Initiator side: encapsulate ML-KEM-768 + X25519 against peer's keys and mix into future Double Ratchet root key. */
    public HybridKeyExchange.InitiatorOffer initiatePqcSession(int peerNode, byte[] peerX25519Pub, byte[] peerMlKemEk) {
        HybridKeyExchange.InitiatorOffer offer = HybridKeyExchange.initiate(hybridIdentity, peerX25519Pub, peerMlKemEk);
        pqcSecrets.put(peerNode, offer.sessionKey);
        resetSession(peerNode);
        return offer;
    }

    /** Responder side: decapsulate peer's ML-KEM-768 ciphertext and mix into Double Ratchet root key. */
    public byte[] acceptPqcOffer(int peerNode, byte[] initiatorX25519Pub, byte[] mlKemCiphertext) {
        byte[] key = HybridKeyExchange.respond(hybridIdentity, initiatorX25519Pub, mlKemCiphertext);
        pqcSecrets.put(peerNode, key);
        resetSession(peerNode);
        return key;
    }

    private byte[] rk0(int node, byte[] peerRaw) {
        byte[] info = Ratchet.concat("mesh-dr-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                ByteBuffer.allocate(8).putInt(Math.min(selfId, node)).putInt(Math.max(selfId, node)).array());
        byte[] classical = Ratchet.hkdf("mesh-dr-salt".getBytes(java.nio.charset.StandardCharsets.US_ASCII), me.sharedSecret(peerRaw), info, 32);
        byte[] pq = pqcSecrets.get(node);
        if (pq == null) return classical;
        return Ratchet.hkdf(pq, classical, "mesh-dr-pq-mlkem768-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 32);
    }

    private static byte[] ad(int src, int dst, int mode) { return ByteBuffer.allocate(9).putInt(src).putInt(dst).put((byte) mode).array(); }

    private static java.nio.ByteBuffer key(int node, byte[] data) {
        return ByteBuffer.wrap(Ratchet.concat(ByteBuffer.allocate(4).putInt(node).array(), data));
    }

    // ------------------------------------------------------------------ Crypto

    @Override
    public byte[] encrypt(byte[] plain, int src, int dst, long msgId) {
        if (Frame.isKeyFrame(plain)) {
            byte[] out = new byte[1 + plain.length];
            System.arraycopy(plain, 0, out, 1, plain.length);
            return out;
        }
        byte[] peer = directory.get(dst);
        if (peer == null) throw new MissingKeyException(dst);
        java.nio.ByteBuffer ck = key(dst, plain);
        byte[] cached = encCache.get(ck);
        if (cached != null) return cached;
        Ratchet r = session(dst);
        if (r == null) r = Ratchet.alice(rk0(dst, peer), peer);
        int mode = padding ? MODE_RATCHET_PADDED : MODE_RATCHET;
        byte[] body = r.encrypt(padding ? pad(plain) : plain, ad(src, dst, mode));
        persist(dst, r);
        byte[] out = new byte[1 + body.length];
        out[0] = (byte) mode;
        System.arraycopy(body, 0, out, 1, body.length);
        encCache.put(ck, out);
        return out;
    }

    @Override
    public byte[] decrypt(byte[] cipher, int src, int dst, long msgId) {
        if (cipher.length < 2) return null;
        int mode = cipher[0];
        if (mode == MODE_CLEAR) {
            byte[] inner = Arrays.copyOfRange(cipher, 1, cipher.length);
            return Frame.isKeyFrame(inner) ? inner : null;
        }
        if (mode != MODE_RATCHET && mode != MODE_RATCHET_PADDED) return null;
        java.nio.ByteBuffer ck = key(src, cipher);
        byte[] again = decCache.get(ck);
        if (again != null) return again;
        byte[] peer = directory.get(src);
        if (peer == null) {
            MissingKeyListener l = missing;
            if (l != null) l.onNeedKey(src);
            return null;
        }
        byte[] msg = Arrays.copyOfRange(cipher, 1, cipher.length);
        byte[] ad = ad(src, dst, mode);
        Ratchet cur = session(src);
        byte[] plain = null;
        if (cur != null) plain = tryDecrypt(cur, msg, ad, src);
        if (plain == null && Ratchet.isBoot(msg) && msg.length >= Ratchet.HEADER) {
            plain = tryNewSession(cur, msg, ad, src, peer);
        } else if (plain == null && cur == null) {
            SessionLostListener l = lost;
            if (l != null) l.onSessionLost(src);
        }
        if (plain != null && mode == MODE_RATCHET_PADDED) plain = unpad(plain);
        if (plain != null) decCache.put(ck, plain);
        return plain;
    }

    private byte[] tryDecrypt(Ratchet cur, byte[] msg, byte[] ad, int src) {
        byte[] plain = cur.decrypt(msg, ad);
        if (plain != null) persist(src, cur);
        return plain;
    }

    /** The message says "first flight of a new session": adopt it if it authenticates and the tie-break allows. */
    private byte[] tryNewSession(Ratchet cur, byte[] msg, byte[] ad, int src, byte[] peer) {
        if (cur != null && cur.initiator && !cur.received && selfId < src) return null;   // I started first and my id is lower: I win
        String dh = Ratchet.hex(Ratchet.headerDh(msg));
        List<String> seen = bootList(src);
        if (seen.contains(dh)) return null;                                               // replay of an old first flight
        Ratchet bob = Ratchet.bob(rk0(src, peer), me);
        byte[] plain = bob.decrypt(msg, ad);
        if (plain == null) return null;
        seen.add(dh);
        while (seen.size() > 8) seen.remove(0);
        if (store != null) store.put("drb-" + src, Ratchet.concat(new byte[0], join(seen)));
        dropCaches(src);                                                                  // my old ciphertexts are void now
        persist(src, bob);
        return plain;
    }

    private List<String> bootList(int node) {
        List<String> l = bootSeen.get(node);
        if (l == null) {
            l = new ArrayList<>();
            byte[] b = store == null ? null : store.get("drb-" + node);
            if (b != null) for (int i = 0; i + 33 <= b.length; i += 33) l.add(Ratchet.hex(Arrays.copyOfRange(b, i, i + 33)));
            bootSeen.put(node, l);
        }
        return l;
    }

    private static byte[] join(List<String> hexes) {
        byte[] out = new byte[33 * hexes.size()];
        for (int i = 0; i < hexes.size(); i++) {
            String h = hexes.get(i);
            for (int j = 0; j < 33; j++) out[33 * i + j] = (byte) Integer.parseInt(h.substring(2 * j, 2 * j + 2), 16);
        }
        return out;
    }

    // ------------------------------------------------------------------ voice (static key, no ratchet)

    private static final byte[] VOICE_LABEL = "meshnet-core-e2ee-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final int MODE_VOICE = 1;
    private final Map<Integer, javax.crypto.spec.SecretKeySpec> voiceKeys = new java.util.concurrent.ConcurrentHashMap<>();

    private javax.crypto.spec.SecretKeySpec voiceKey(int node) {
        javax.crypto.spec.SecretKeySpec k = voiceKeys.get(node);
        if (k != null) return k;
        byte[] raw = directory.get(node);
        if (raw == null) return null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(me.sharedSecret(raw));
            md.update(VOICE_LABEL);
            md.update(ByteBuffer.allocate(8).putInt(Math.min(selfId, node)).putInt(Math.max(selfId, node)).array());
            k = new javax.crypto.spec.SecretKeySpec(md.digest(), "AES");
            voiceKeys.put(node, k);
            return k;
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] voiceNonce(int src, long msgId) {
        return ByteBuffer.allocate(12).putLong(msgId).putInt(src).array();
    }

    private static byte[] voiceAad(int src, int dst) {
        return ByteBuffer.allocate(9).put((byte) MODE_VOICE).putInt(src).putInt(dst).array();
    }

    /**
     * Real-time voice is never ratcheted: packets are lost, reordered and never retried, and a ratchet step per
     * 20 ms frame would be wasteful. It uses a static ECDH-derived AES-256-GCM key with a 17-byte overhead
     * (nonce = msgId + src, never sent; AAD = mode, src, dst), exactly as before. Same authenticity, but no
     * forward secrecy for the voice stream (text and files do have it, through the Double Ratchet).
     */
    @Override
    public byte[] encryptVoice(byte[] plain, int src, int dst, long msgId) {
        javax.crypto.spec.SecretKeySpec key = voiceKey(dst);
        if (key == null) throw new MissingKeyException(dst);
        try {
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(128, voiceNonce(src, msgId)));
            c.updateAAD(voiceAad(src, dst));
            byte[] body = c.doFinal(plain);
            byte[] out = new byte[1 + body.length];
            out[0] = MODE_VOICE;
            System.arraycopy(body, 0, out, 1, body.length);
            return out;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public byte[] decryptVoice(byte[] cipher, int src, int dst, long msgId) {
        if (cipher.length < 2 || cipher[0] != MODE_VOICE) return null;
        javax.crypto.spec.SecretKeySpec key = voiceKey(src);
        if (key == null) {
            MissingKeyListener l = missing;
            if (l != null) l.onNeedKey(src);
            return null;
        }
        try {
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            c.init(javax.crypto.Cipher.DECRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(128, voiceNonce(src, msgId)));
            c.updateAAD(voiceAad(src, dst));
            return c.doFinal(cipher, 1, cipher.length - 1);
        } catch (java.security.GeneralSecurityException e) {
            return null;
        }
    }
}
