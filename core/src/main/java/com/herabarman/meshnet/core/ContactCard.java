package com.herabarman.meshnet.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Signed QR Contact Card, 60-digit Symmetric Safety Number Verifier, and Key Revocation Registry.
 *
 * Allows two peers meeting offline to exchange a compact {@code meshnet://card?...} URI (via QR code or NFC),
 * cryptographically verify the card's Ed25519/ECDSA signature via {@link RecordAuth}, pin the peer's public keys,
 * and compare deterministic 60-digit Safety Numbers out-of-band to prevent MITM attacks.
 */
public final class ContactCard {
    public static final int VERSION = 1;
    private static final String SCHEME_PREFIX = "meshnet://card?";

    public final int version;
    public final String userId;
    public final int nodeId;
    public final int zone;
    public final byte[] identityPub;
    public final byte[] x25519Pub;
    public final byte[] mlKemEkHash; // optional 16-byte fingerprint of ML-KEM-768 encapsulation key
    public final long issuedAtSec;
    public final byte[] signature;

    public ContactCard(int version, String userId, int nodeId, int zone,
                       byte[] identityPub, byte[] x25519Pub, byte[] mlKemEkHash,
                       long issuedAtSec, byte[] signature) {
        if (userId == null || userId.isEmpty()) throw new IllegalArgumentException("userId");
        if (identityPub == null || identityPub.length == 0) throw new IllegalArgumentException("identityPub");
        if (x25519Pub == null || x25519Pub.length != 32) throw new IllegalArgumentException("x25519Pub");
        this.version = version;
        this.userId = userId;
        this.nodeId = nodeId;
        this.zone = zone;
        this.identityPub = identityPub.clone();
        this.x25519Pub = x25519Pub.clone();
        this.mlKemEkHash = mlKemEkHash == null ? new byte[0] : mlKemEkHash.clone();
        this.issuedAtSec = issuedAtSec;
        this.signature = signature == null ? new byte[0] : signature.clone();
    }

    /** Create and sign a ContactCard for local sharing over QR. */
    public static ContactCard createSigned(
            String userId,
            int nodeId,
            int zone,
            RecordAuth auth,
            byte[] x25519Pub,
            byte[] mlKemEk,
            long nowMs) {
        byte[] idPub = auth.publicKey();
        byte[] kemHash = (mlKemEk != null && mlKemEk.length > 0)
                ? Arrays.copyOf(sha256(mlKemEk), 16)
                : new byte[0];
        long issuedSec = nowMs / 1000L;
        byte[] toSign = canonicalSignBytes(VERSION, userId, nodeId, zone, idPub, x25519Pub, kemHash, issuedSec);
        byte[] sig = auth.sign(toSign);
        return new ContactCard(VERSION, userId, nodeId, zone, idPub, x25519Pub, kemHash, issuedSec, sig);
    }

    /** Verify the embedded cryptographic signature and optional key-binding / revocation status. */
    public boolean verify(RecordAuth verifier, RevocationList revocations) {
        if (version != VERSION || signature.length == 0) return false;
        if (revocations != null && revocations.isRevoked(identityPub)) return false;
        byte[] msg = canonicalSignBytes(version, userId, nodeId, zone, identityPub, x25519Pub, mlKemEkHash, issuedAtSec);
        try {
            return verifier.verify(identityPub, msg, signature);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Verify only signature without revocation list. */
    public boolean verify(RecordAuth verifier) {
        return verify(verifier, null);
    }

    /** Canonical QR URI string. */
    public String encodeUri() {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        StringBuilder sb = new StringBuilder(SCHEME_PREFIX);
        sb.append("v=").append(version)
                .append("&uid=").append(b64.encodeToString(userId.getBytes(StandardCharsets.UTF_8)))
                .append("&nid=").append(nodeId)
                .append("&z=").append(zone)
                .append("&ed=").append(b64.encodeToString(identityPub))
                .append("&x=").append(b64.encodeToString(x25519Pub));
        if (mlKemEkHash.length > 0) {
            sb.append("&kem=").append(b64.encodeToString(mlKemEkHash));
        }
        sb.append("&ts=").append(issuedAtSec)
                .append("&sig=").append(b64.encodeToString(signature));
        return sb.toString();
    }

    /** Parse a QR ContactCard URI, returning null if malformed. */
    public static ContactCard decodeUri(String uri) {
        if (uri == null || !uri.startsWith(SCHEME_PREFIX)) return null;
        try {
            String query = uri.substring(SCHEME_PREFIX.length());
            Map<String, String> params = new HashMap<>();
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0 || eq == pair.length() - 1) continue;
                params.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
            Base64.Decoder dec = Base64.getUrlDecoder();
            int ver = Integer.parseInt(params.getOrDefault("v", "0"));
            if (ver != VERSION) return null;
            String uidB64 = params.get("uid");
            String nidStr = params.get("nid");
            String zStr = params.get("z");
            String edStr = params.get("ed");
            String xStr = params.get("x");
            String tsStr = params.get("ts");
            String sigStr = params.get("sig");
            if (uidB64 == null || nidStr == null || zStr == null || edStr == null || xStr == null || tsStr == null || sigStr == null) {
                return null;
            }
            String userId = new String(dec.decode(uidB64), StandardCharsets.UTF_8);
            int nodeId = Integer.parseInt(nidStr);
            int zone = Integer.parseInt(zStr);
            byte[] edPub = dec.decode(edStr);
            byte[] xPub = dec.decode(xStr);
            byte[] kem = params.containsKey("kem") ? dec.decode(params.get("kem")) : new byte[0];
            long ts = Long.parseLong(tsStr);
            byte[] sig = dec.decode(sigStr);
            if (userId.isEmpty() || edPub.length == 0 || xPub.length != 32 || sig.length == 0) return null;
            return new ContactCard(ver, userId, nodeId, zone, edPub, xPub, kem, ts, sig);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Symmetric 60-digit Safety Number (12 groups of 5 digits) derived from both parties' identity keys,
     * independent of key order so Alice and Bob always see the exact same string.
     */
    public static String safetyNumber(byte[] pubA, byte[] pubB) {
        if (pubA == null || pubB == null || pubA.length == 0 || pubB.length == 0) {
            throw new IllegalArgumentException("keys required");
        }
        byte[] first = compareUnsigned(pubA, pubB) <= 0 ? pubA : pubB;
        byte[] second = first == pubA ? pubB : pubA;

        byte[] seed = new byte[12 + first.length + second.length];
        byte[] tag = "mn-safety-v1".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(tag, 0, seed, 0, tag.length);
        System.arraycopy(first, 0, seed, tag.length, first.length);
        System.arraycopy(second, 0, seed, tag.length + first.length, second.length);

        byte[] digest = Keccak.shake128(seed, 48);
        StringBuilder sb = new StringBuilder(71);
        for (int g = 0; g < 12; g++) {
            int off = g * 4;
            long val = ((digest[off] & 0xFFL) << 24)
                    | ((digest[off + 1] & 0xFFL) << 16)
                    | ((digest[off + 2] & 0xFFL) << 8)
                    | (digest[off + 3] & 0xFFL);
            long group = val % 100_000L;
            if (g > 0) sb.append(' ');
            sb.append(String.format(java.util.Locale.ROOT, "%05d", group));
        }
        return sb.toString();
    }

    public String safetyNumberWith(byte[] peerIdentityPub) {
        return safetyNumber(this.identityPub, peerIdentityPub);
    }

    /** Signed key-revocation list so compromised identity keys are automatically rejected offline. */
    public static final class RevocationList {
        private final Map<String, String> revokedByHex = new HashMap<>();

        public synchronized boolean revoke(byte[] targetPub, String reason, RecordAuth authority, byte[] revocationSig) {
            if (targetPub == null || targetPub.length == 0 || authority == null || revocationSig == null) return false;
            byte[] payload = revokeBytes(targetPub, reason == null ? "compromised" : reason);
            if (!authority.verify(targetPub, payload, revocationSig)) return false;
            revokedByHex.put(hex(sha256(targetPub)), reason == null ? "compromised" : reason);
            return true;
        }

        public static byte[] signRevocation(RecordAuth ownerAuth, String reason) {
            return ownerAuth.sign(revokeBytes(ownerAuth.publicKey(), reason == null ? "compromised" : reason));
        }

        public synchronized boolean isRevoked(byte[] pub) {
            if (pub == null || pub.length == 0) return false;
            return revokedByHex.containsKey(hex(sha256(pub)));
        }

        public synchronized Map<String, String> snapshot() {
            return Collections.unmodifiableMap(new HashMap<>(revokedByHex));
        }

        private static byte[] revokeBytes(byte[] pub, String reason) {
            byte[] tag = "mn-revoke-v1".getBytes(StandardCharsets.US_ASCII);
            byte[] r = reason.getBytes(StandardCharsets.UTF_8);
            return ByteBuffer.allocate(tag.length + 4 + pub.length + r.length)
                    .put(tag).putInt(pub.length).put(pub).put(r).array();
        }
    }

    private static byte[] canonicalSignBytes(
            int version, String userId, int nodeId, int zone,
            byte[] edPub, byte[] xPub, byte[] kemHash, long issuedSec) {
        byte[] tag = "mn-card-v1".getBytes(StandardCharsets.US_ASCII);
        byte[] uid = userId.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(tag.length + 4 + 2 + uid.length + 8 + 2 + edPub.length + 2 + xPub.length + 2 + kemHash.length + 8)
                .put(tag)
                .putInt(version)
                .putShort((short) uid.length).put(uid)
                .putInt(nodeId)
                .putInt(zone)
                .putShort((short) edPub.length).put(edPub)
                .putShort((short) xPub.length).put(xPub)
                .putShort((short) kemHash.length).put(kemHash)
                .putLong(issuedSec)
                .array();
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int compareUnsigned(byte[] a, byte[] b) {
        int min = Math.min(a.length, b.length);
        for (int i = 0; i < min; i++) {
            int d = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (d != 0) return d;
        }
        return Integer.compare(a.length, b.length);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) sb.append(Character.forDigit((v >>> 4) & 0xF, 16)).append(Character.forDigit(v & 0xF, 16));
        return sb.toString();
    }
}
