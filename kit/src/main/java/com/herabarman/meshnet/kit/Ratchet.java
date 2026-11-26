package com.herabarman.meshnet.kit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Double Ratchet (the Signal construction) over P-256 + HKDF-SHA256 + AES-256-GCM, one session per peer.
 *
 * Start without any server or prekeys: both phones already hold each other's identity key, so the first
 * root key is HKDF(ECDH(identity, identity)). The sender ("Alice") makes a fresh ratchet key and does a DH
 * against the peer's identity key; the receiver ("Bob") treats his identity key as his first ratchet key.
 * Every reply then moves both sides to fresh ephemeral keys, which gives forward secrecy and healing after
 * a key compromise from the first round trip on. Until the first reply arrives the first flight is only as
 * forward-secret as the receiver's identity key (the same limit as Signal's X3DH without a one-time prekey).
 *
 * Message = header(38) + AES-GCM ciphertext (16 byte tag):
 *   flags(1, bit0 = BOOT: sent before anything was received) | my ratchet key, compressed(33) | pn(2) | n(2)
 * The header is the GCM associated data together with the caller's data (src and dst node ids).
 *
 * decrypt() never changes the session unless the message authenticates, so garbage from the network cannot
 * damage a session. Lost and reordered messages are handled with a bounded store of skipped message keys.
 * Not thread-safe: E2eeCrypto only calls it on the engine thread.
 */
final class Ratchet {
    static final int HEADER = 38;
    static final int FLAG_BOOT = 1;
    static final int MAX_SKIP = 200;
    static final int MAX_SKIPPED_TOTAL = 400;
    private static final byte[] ZERO32 = new byte[32];

    byte[] rk;
    IdentityKeys dhs;
    byte[] dhr;                 // 65 raw or null
    byte[] cks, ckr;            // 32 or null
    int ns, nr, pn;
    boolean initiator;          // I started this session as Alice
    boolean received;           // at least one message from the peer authenticated
    final LinkedHashMap<String, byte[]> skipped = new LinkedHashMap<>();   // "hex(dh33):n" -> message key

    private Ratchet() {}

    // ------------------------------------------------------------------ setup

    static Ratchet alice(byte[] rk0, byte[] peerIdentityRaw) {
        Ratchet r = new Ratchet();
        r.initiator = true;
        r.dhs = IdentityKeys.generate();
        r.dhr = peerIdentityRaw.clone();
        byte[][] k = kdfRk(rk0, r.dhs.sharedSecret(r.dhr));
        r.rk = k[0];
        r.cks = k[1];
        return r;
    }

    static Ratchet bob(byte[] rk0, IdentityKeys myIdentity) {
        Ratchet r = new Ratchet();
        r.dhs = myIdentity;
        r.rk = rk0.clone();
        return r;
    }

    Ratchet copy() {
        Ratchet c = new Ratchet();
        c.rk = rk.clone();
        c.dhs = dhs;                                  // immutable
        c.dhr = dhr == null ? null : dhr.clone();
        c.cks = cks == null ? null : cks.clone();
        c.ckr = ckr == null ? null : ckr.clone();
        c.ns = ns; c.nr = nr; c.pn = pn;
        c.initiator = initiator; c.received = received;
        for (Map.Entry<String, byte[]> e : skipped.entrySet()) c.skipped.put(e.getKey(), e.getValue());
        return c;
    }

    private void adopt(Ratchet o) {
        rk = o.rk; dhs = o.dhs; dhr = o.dhr; cks = o.cks; ckr = o.ckr;
        ns = o.ns; nr = o.nr; pn = o.pn; initiator = o.initiator; received = o.received;
        skipped.clear();
        skipped.putAll(o.skipped);
    }

    // ------------------------------------------------------------------ KDFs

    private static byte[] hmac(byte[] key, byte[]... parts) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key.length == 0 ? ZERO32 : key, "HmacSHA256"));
            for (byte[] p : parts) m.update(p);
            return m.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int len) {
        byte[] prk = hmac(salt, ikm);
        byte[] out = new byte[len];
        byte[] t = new byte[0];
        int pos = 0;
        for (int i = 1; pos < len; i++) {
            t = hmac(prk, t, info, new byte[]{(byte) i});
            int n = Math.min(t.length, len - pos);
            System.arraycopy(t, 0, out, pos, n);
            pos += n;
        }
        return out;
    }

    private static byte[][] kdfRk(byte[] rk, byte[] dhOut) {
        byte[] o = hkdf(rk, dhOut, "mesh-dr-rk".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 64);
        return new byte[][]{Arrays.copyOfRange(o, 0, 32), Arrays.copyOfRange(o, 32, 64)};
    }

    private static byte[][] kdfCk(byte[] ck) {
        return new byte[][]{hmac(ck, new byte[]{1}), hmac(ck, new byte[]{2})};   // {message key, next chain key}
    }

    private static byte[] seal(boolean enc, byte[] mk, byte[] aad, byte[] in, int off, int len) throws GeneralSecurityException {
        byte[] kiv = hkdf(ZERO32, mk, "mesh-dr-mk".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 44);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(enc ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(kiv, 0, 32, "AES"),
                new GCMParameterSpec(128, kiv, 32, 12));
        c.updateAAD(aad);
        return c.doFinal(in, off, len);
    }

    // ------------------------------------------------------------------ messages

    byte[] encrypt(byte[] plain, byte[] ad) {
        if (cks == null) throw new IllegalStateException("no sending chain");
        byte[][] k = kdfCk(cks);
        cks = k[1];
        byte[] hdr = ByteBuffer.allocate(HEADER).put((byte) (received ? 0 : FLAG_BOOT)).put(IdentityKeys.compress(dhs.publicRaw()))
                .putShort((short) pn).putShort((short) ns).array();
        ns++;
        try {
            byte[] ct = seal(true, k[0], concat(hdr, ad), plain, 0, plain.length);
            return concat(hdr, ct);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean isBoot(byte[] msg) { return msg.length > 0 && (msg[0] & FLAG_BOOT) != 0; }

    static byte[] headerDh(byte[] msg) { return Arrays.copyOfRange(msg, 1, 34); }

    /** @return the plaintext, or null (and this session untouched) if the message does not authenticate */
    byte[] decrypt(byte[] msg, byte[] ad) {
        if (msg.length < HEADER + 16) return null;
        byte[] hdr = Arrays.copyOf(msg, HEADER);
        byte[] dh33 = headerDh(msg);
        ByteBuffer hb = ByteBuffer.wrap(hdr, 34, 4);
        int msgPn = hb.getShort() & 0xFFFF;
        int n = hb.getShort() & 0xFFFF;
        Ratchet r = copy();
        byte[] mk = r.skipped.remove(hex(dh33) + ":" + n);
        if (mk == null) {
            if (r.dhr == null || !Arrays.equals(IdentityKeys.compress(r.dhr), dh33)) {
                byte[] newDhr = IdentityKeys.decompress(dh33);
                if (newDhr == null || !r.skipUntil(msgPn)) return null;
                r.dhRatchet(newDhr);
            }
            if (!r.skipUntil(n) || r.ckr == null || n < r.nr) return null;
            byte[][] k = kdfCk(r.ckr);
            mk = k[0];
            r.ckr = k[1];
            r.nr = n + 1;
        }
        try {
            byte[] plain = seal(false, mk, concat(hdr, ad), msg, HEADER, msg.length - HEADER);
            r.received = true;
            adopt(r);
            return plain;
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    private boolean skipUntil(int until) {
        if (ckr == null) return true;                 // nothing to skip in a chain that does not exist yet
        if (until < nr) return true;
        if (until - nr > MAX_SKIP) return false;
        String prefix = hex(IdentityKeys.compress(dhr)) + ":";
        while (nr < until) {
            byte[][] k = kdfCk(ckr);
            ckr = k[1];
            skipped.put(prefix + nr, k[0]);
            nr++;
        }
        Iterator<String> it = skipped.keySet().iterator();
        while (skipped.size() > MAX_SKIPPED_TOTAL && it.hasNext()) { it.next(); it.remove(); }
        return true;
    }

    private void dhRatchet(byte[] newDhr) {
        pn = ns;
        ns = 0;
        nr = 0;
        dhr = newDhr;
        byte[][] k = kdfRk(rk, dhs.sharedSecret(dhr));
        rk = k[0];
        ckr = k[1];
        dhs = IdentityKeys.generate();
        k = kdfRk(rk, dhs.sharedSecret(dhr));
        rk = k[0];
        cks = k[1];
    }

    // ------------------------------------------------------------------ storage

    byte[] serialize() {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bo);
            o.writeByte(1);
            o.writeByte((initiator ? 1 : 0) | (received ? 2 : 0));
            o.write(rk);
            byte[] priv = dhs.privateEncoded();
            o.writeShort(priv.length);
            o.write(priv);
            o.write(dhs.publicRaw());
            o.writeByte(dhr == null ? 0 : 1);
            if (dhr != null) o.write(dhr);
            o.writeByte(cks == null ? 0 : 1);
            if (cks != null) o.write(cks);
            o.writeByte(ckr == null ? 0 : 1);
            if (ckr != null) o.write(ckr);
            o.writeInt(ns); o.writeInt(nr); o.writeInt(pn);
            o.writeShort(skipped.size());
            for (Map.Entry<String, byte[]> e : skipped.entrySet()) {
                o.writeUTF(e.getKey());
                o.write(e.getValue());
            }
            return bo.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return the session, or null if the stored bytes are not a valid session */
    static Ratchet deserialize(byte[] b) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
            if (in.readByte() != 1) return null;
            int fl = in.readByte();
            Ratchet r = new Ratchet();
            r.initiator = (fl & 1) != 0;
            r.received = (fl & 2) != 0;
            r.rk = new byte[32]; in.readFully(r.rk);
            byte[] priv = new byte[in.readUnsignedShort()]; in.readFully(priv);
            byte[] pub = new byte[65]; in.readFully(pub);
            r.dhs = IdentityKeys.restore(priv, pub);
            if (in.readByte() != 0) { r.dhr = new byte[65]; in.readFully(r.dhr); }
            if (in.readByte() != 0) { r.cks = new byte[32]; in.readFully(r.cks); }
            if (in.readByte() != 0) { r.ckr = new byte[32]; in.readFully(r.ckr); }
            r.ns = in.readInt(); r.nr = in.readInt(); r.pn = in.readInt();
            int cnt = in.readUnsignedShort();
            for (int i = 0; i < cnt; i++) {
                String k = in.readUTF();
                byte[] mk = new byte[32];
                in.readFully(mk);
                r.skipped.put(k, mk);
            }
            return r;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ helpers

    static byte[] concat(byte[] a, byte[] b) {
        byte[] o = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return sb.toString();
    }

    List<String> skippedKeys() { return new ArrayList<>(skipped.keySet()); }
}
