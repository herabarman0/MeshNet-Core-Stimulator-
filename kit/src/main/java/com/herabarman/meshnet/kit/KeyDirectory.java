package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.StorageAdapter;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who has which public key. Trust on first use: the first key seen for a node is pinned. A different
 * key later is NOT accepted silently: put() returns false and the listener is told, and the app can
 * show both fingerprints and call replace() if the user agrees.
 */
public final class KeyDirectory {
    public interface ChangeListener {
        void onKeyChanged(int node, byte[] pinned, byte[] offered);
    }

    private final StorageAdapter store;
    private final Map<Integer, byte[]> keys = new ConcurrentHashMap<>();
    private volatile ChangeListener listener;

    public KeyDirectory(StorageAdapter store) { this.store = store; }

    public void setListener(ChangeListener l) { this.listener = l; }

    public byte[] get(int node) {
        byte[] k = keys.get(node);
        if (k == null && store != null) {
            k = store.get("key-" + node);
            if (k != null) keys.put(node, k);
        }
        return k;
    }

    /** @return true if the key is new (now pinned) or equal to the pinned one; false if it differs */
    public boolean put(int node, byte[] raw) {
        byte[] old = get(node);
        if (old == null) {
            keys.put(node, raw.clone());
            if (store != null) store.put("key-" + node, raw.clone());
            return true;
        }
        if (Arrays.equals(old, raw)) return true;
        ChangeListener l = listener;
        if (l != null) l.onKeyChanged(node, old, raw);
        return false;
    }

    /** The user has verified the new key: pin it instead of the old one. */
    public void replace(int node, byte[] raw) {
        keys.put(node, raw.clone());
        if (store != null) store.put("key-" + node, raw.clone());
    }
}
