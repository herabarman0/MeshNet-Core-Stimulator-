package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.StorageAdapter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * App user ids (strings) <-> the engine's 31-bit node ids. The core never sees an app id.
 *
 *  - "70121" style ids (digits, no leading zero, below 10^9) are the node id itself, no table needed.
 *  - Anything else ("070121", "MESHab12cd") gets a hash in [2^30, 2^31) and is remembered in both
 *    directions (memory + StorageAdapter), so an incoming node id can be turned back into the app id.
 * Two different strings with the same hash are refused (IllegalArgumentException): with 30 bits this
 * needs about 30,000 such ids before it becomes likely. Server-issued numeric ids never collide.
 */
public final class IdMap {
    public static final int HASH_BASE = 0x40000000;
    private final StorageAdapter store;
    private final Map<Integer, String> reverse = new ConcurrentHashMap<>();

    public IdMap(StorageAdapter store) { this.store = store; }

    private static boolean plainNumber(String s) {
        int n = s.length();
        if (n == 0 || n > 9 || s.charAt(0) < '1' || s.charAt(0) > '9') return false;
        for (int i = 1; i < n; i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        return true;
    }

    public int nodeOf(String userId) {
        if (userId == null || userId.isEmpty()) throw new IllegalArgumentException("empty user id");
        if (plainNumber(userId)) return Integer.parseInt(userId);
        int h = HASH_BASE | (hash(userId) & 0x3FFFFFFF);
        String old = reverse.get(h);
        if (old == null && store != null) {
            byte[] b = store.get("id-" + h);
            if (b != null) { old = new String(b, StandardCharsets.UTF_8); reverse.put(h, old); }
        }
        if (old != null && !old.equals(userId)) throw new IllegalArgumentException("node id collision: " + old + " / " + userId);
        if (old == null) {
            reverse.put(h, userId);
            if (store != null) store.put("id-" + h, userId.getBytes(StandardCharsets.UTF_8));
        }
        return h;
    }

    /** @return the app id, or null if this node id was never seen as a string id */
    public String userOf(int node) {
        if (node > 0 && node < HASH_BASE) return Integer.toString(node);
        String s = reverse.get(node);
        if (s == null && store != null) {
            byte[] b = store.get("id-" + node);
            if (b != null) { s = new String(b, StandardCharsets.UTF_8); reverse.put(node, s); }
        }
        return s;
    }

    /** Like userOf but never null (for logs and for strangers). */
    public String userOrPlaceholder(int node) {
        String s = userOf(node);
        return s != null ? s : "node-" + Integer.toHexString(node);
    }

    private static int hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return ((d[0] & 0xFF) << 24) | ((d[1] & 0xFF) << 16) | ((d[2] & 0xFF) << 8) | (d[3] & 0xFF);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
