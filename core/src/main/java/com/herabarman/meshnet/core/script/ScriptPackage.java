package com.herabarman.meshnet.core.script;

import com.herabarman.meshnet.core.RecordAuth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A signed script mini-app: only packages signed by a trusted publisher, with a higher version than installed, get installed. */
public final class ScriptPackage {
    public final String id, source;
    public final int version;
    public final byte[] publisherKey, signature;

    public ScriptPackage(String id, int version, String source, byte[] key, byte[] sig) {
        this.id = id; this.version = version; this.source = source; this.publisherKey = key; this.signature = sig;
    }

    private static byte[] signedBytes(String id, int version, String source) {
        byte[] t = "mn-script-v1".getBytes(StandardCharsets.US_ASCII), a = id.getBytes(StandardCharsets.UTF_8), s = source.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(t.length + 1 + a.length + 4 + 4 + s.length).put(t).put((byte) a.length).put(a).putInt(version).putInt(s.length).put(s).array();
    }

    public static ScriptPackage create(String id, int version, String source, RecordAuth publisher) {
        return new ScriptPackage(id, version, source, publisher.publicKey(), publisher.sign(signedBytes(id, version, source)));
    }

    public byte[] encode() {
        byte[] a = id.getBytes(StandardCharsets.UTF_8), s = source.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(1 + a.length + 4 + 4 + s.length + 2 + publisherKey.length + 2 + signature.length)
                .put((byte) a.length).put(a).putInt(version).putInt(s.length).put(s)
                .putShort((short) publisherKey.length).put(publisherKey).putShort((short) signature.length).put(signature).array();
    }

    /** @return null if malformed. */
    public static ScriptPackage decode(byte[] m) {
        try {
            ByteBuffer b = ByteBuffer.wrap(m);
            byte[] a = new byte[b.get() & 0xFF]; b.get(a);
            int version = b.getInt(), n = b.getInt();
            if (n < 0 || n > 64 * 1024) return null;
            byte[] s = new byte[n]; b.get(s);
            byte[] k = new byte[b.getShort() & 0xFFFF]; b.get(k);
            byte[] g = new byte[b.getShort() & 0xFFFF]; b.get(g);
            return new ScriptPackage(new String(a, StandardCharsets.UTF_8), version, new String(s, StandardCharsets.UTF_8), k, g);
        } catch (RuntimeException e) { return null; }
    }

    public enum Result { OK, UNTRUSTED, BAD_SIGNATURE, OLD_VERSION, BAD_SCRIPT }

    /** Verifies packages and tracks installed versions (rollback protection). */
    public static final class Verifier {
        private final RecordAuth auth;
        private final List<byte[]> trusted;
        private final Map<String, Integer> installed = new HashMap<>();

        public Verifier(RecordAuth auth, List<byte[]> trustedPublishers) { this.auth = auth; this.trusted = trustedPublishers; }

        public Result check(ScriptPackage p) {
            boolean ok = false;
            for (byte[] t : trusted) if (Arrays.equals(t, p.publisherKey)) ok = true;
            if (!ok) return Result.UNTRUSTED;
            boolean sig;
            try { sig = auth.verify(p.publisherKey, signedBytes(p.id, p.version, p.source), p.signature); } catch (RuntimeException e) { sig = false; }
            if (!sig) return Result.BAD_SIGNATURE;
            Integer cur = installed.get(p.id);
            if (cur != null && p.version <= cur) return Result.OLD_VERSION;
            return Result.OK;
        }

        /** Check, compile, and hand back the app (null result means rejected). Records the version on success. */
        public ScriptApp install(ScriptPackage p, MeshScript.Limits limits, Result[] out) {
            Result r = check(p);
            ScriptApp app = null;
            if (r == Result.OK) {
                try { app = new ScriptApp(p.id, p.version, p.source, limits); } catch (MeshScript.ScriptError e) { r = Result.BAD_SCRIPT; }
            }
            if (out != null && out.length > 0) out[0] = r;
            if (app != null) installed.put(p.id, p.version);
            return app;
        }

        public int installedVersion(String id) { Integer v = installed.get(id); return v == null ? 0 : v; }
    }
}
