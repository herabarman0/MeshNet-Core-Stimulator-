package com.herabarman.meshnet.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one node knows about where other nodes live: nodeId -> zone, learned from neighbours,
 * from packet headers and from the location service. LRU with per-entry expiry.
 */
public final class LocationDirectory {
    private static final class Entry {
        final int zone;
        final long expiresAtMs;

        Entry(int zone, long expiresAtMs) {
            this.zone = zone;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private final LinkedHashMap<Integer, Entry> map;

    public LocationDirectory(final int maxEntries) {
        this.map = new LinkedHashMap<Integer, Entry>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Entry> eldest) {
                return size() > maxEntries;
            }
        };
    }

    /** Remember where a node is. A shorter ttl never shortens an entry that is already known to be valid for longer. */
    public void learn(int nodeId, int zone, long nowMs, long ttlMs) {
        Entry old = map.get(nodeId);
        long expires = nowMs + ttlMs;
        if (old != null && old.zone == zone && old.expiresAtMs > expires) expires = old.expiresAtMs;
        map.put(nodeId, new Entry(zone, expires));
    }

    /** @return the zone, or Packet.UNKNOWN_ZONE */
    public int zoneOf(int nodeId, long nowMs) {
        Entry e = map.get(nodeId);
        if (e == null) return Packet.UNKNOWN_ZONE;
        if (e.expiresAtMs <= nowMs) {
            map.remove(nodeId);
            return Packet.UNKNOWN_ZONE;
        }
        return e.zone;
    }

    public void forget(int nodeId) {
        map.remove(nodeId);
    }

    public int size() {
        return map.size();
    }

    /** The zone that stores where this node is. Same answer on every node, no coordination needed. */
    public static int homeZone(int nodeId, int zoneCount) {
        int h = nodeId;
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return (int) ((h & 0xFFFFFFFFL) % Math.max(1, zoneCount));
    }
}
