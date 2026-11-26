package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.Priority;
import com.herabarman.meshnet.core.StorageAdapter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Messages I sent that nobody has confirmed yet. Kept (and written through StorageAdapter) until the
 * destination's ACK arrives or the message expires, so "offline now, delivered later" works across
 * restarts. Not thread-safe: MeshClient only touches it on the engine thread.
 */
final class Outbox {
    static final class Entry {
        final long localId;
        final int dst;
        final long createdMs;
        final boolean control;     // key frames / read receipts: no delivery callbacks
        final int priority;        // radio priority (core Priority levels)
        final byte[] frame;
        int attempts;
        long nextTryMs;
        boolean inFlight;
        long lastSendMs;

        Entry(long localId, int dst, long createdMs, boolean control, byte[] frame) {
            this(localId, dst, createdMs, control, frame, control ? Priority.URGENT : Priority.NORMAL);
        }

        Entry(long localId, int dst, long createdMs, boolean control, byte[] frame, int priority) {
            this.priority = priority;
            this.localId = localId;
            this.dst = dst;
            this.createdMs = createdMs;
            this.control = control;
            this.frame = frame;
        }
    }

    private final StorageAdapter store;
    private final Map<Long, Entry> entries = new LinkedHashMap<>();

    Outbox(StorageAdapter store) { this.store = store; }

    private static String key(long id) { return "ob-" + String.format("%016x", id); }

    void load() {
        if (store == null) return;
        for (String k : store.keys("ob-")) {
            byte[] raw = store.get(k);
            try {
                ByteBuffer b = ByteBuffer.wrap(raw);
                long id = b.getLong();
                int dst = b.getInt();
                long created = b.getLong();
                int flags = b.get() & 0xFF;                 // bit 0: control; bit 1: a priority byte follows (older entries: none)
                boolean control = (flags & 1) != 0;
                int attempts = b.getShort() & 0xFFFF;
                int priority = control ? Priority.URGENT : Priority.NORMAL;
                if ((flags & 2) != 0) priority = b.get() & 0xFF;
                byte[] frame = new byte[b.remaining()];
                b.get(frame);
                Entry e = new Entry(id, dst, created, control, frame, priority);
                e.attempts = attempts;
                entries.put(id, e);
            } catch (RuntimeException ex) {
                store.remove(k);
            }
        }
    }

    void add(Entry e) {
        entries.put(e.localId, e);
        save(e);
    }

    void save(Entry e) {
        if (store == null) return;
        ByteBuffer b = ByteBuffer.allocate(8 + 4 + 8 + 1 + 2 + 1 + e.frame.length);
        b.putLong(e.localId).putInt(e.dst).putLong(e.createdMs).put((byte) ((e.control ? 1 : 0) | 2))
                .putShort((short) Math.min(e.attempts, 0xFFFF)).put((byte) e.priority).put(e.frame);
        store.put(key(e.localId), b.array());
    }

    Entry get(long id) { return entries.get(id); }

    Entry remove(long id) {
        Entry e = entries.remove(id);
        if (e != null && store != null) store.remove(key(id));
        return e;
    }

    /** Most urgent first (priority, then oldest first), so a queue that wakes up sends SOS before chat before files. */
    List<Entry> all() {
        List<Entry> l = new ArrayList<>(entries.values());
        java.util.Collections.sort(l, new java.util.Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                if (a.priority != b.priority) return b.priority - a.priority;
                return Long.compare(a.createdMs, b.createdMs);
            }
        });
        return l;
    }

    int size() { return entries.size(); }
}
