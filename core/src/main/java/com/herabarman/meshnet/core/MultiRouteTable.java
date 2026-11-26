package com.herabarman.meshnet.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Several ways to reach one destination (or one destination zone), each through a different first hop, like AOMDV:
 * a flood reaches a node over many paths; the first copy makes the route, later copies from OTHER neighbours are
 * kept as alternatives instead of being thrown away. If the best one fails, the next is already known and no
 * new flood is needed. LRU over destinations, per-entry expiry, at most maxPaths first hops per destination.
 */
public final class MultiRouteTable {
    public static final class Path {
        public final int nextHop;
        public int hops;
        public long expiresAtMs;
        public long learnedAtMs;

        public Path(int nextHop, int hops, long learnedAtMs, long expiresAtMs) {
            this.nextHop = nextHop;
            this.hops = hops;
            this.learnedAtMs = learnedAtMs;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private final int maxPaths;
    private final LinkedHashMap<Integer, List<Path>> map;

    public MultiRouteTable(final int maxDestinations, int maxPaths) {
        this.maxPaths = Math.max(1, maxPaths);
        this.map = new LinkedHashMap<Integer, List<Path>>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, List<Path>> eldest) {
                return size() > maxDestinations;
            }
        };
    }

    /**
     * Learn or refresh a path. A known first hop keeps its better hop count; when the table for this destination is
     * full the longest, oldest path is replaced (only if the new one is not longer).
     */
    public void learn(int key, int nextHop, int hops, long nowMs, long ttlMs) {
        List<Path> list = map.get(key);
        if (list == null) {
            list = new ArrayList<>(maxPaths);
            map.put(key, list);
        }
        for (Path p : list) {
            if (p.nextHop == nextHop) {
                p.hops = Math.min(p.hops, hops);
                p.expiresAtMs = nowMs + ttlMs;
                p.learnedAtMs = nowMs;
                return;
            }
        }
        if (list.size() >= maxPaths) {
            Path worst = null;
            for (Path p : list) {
                if (worst == null || p.hops > worst.hops || (p.hops == worst.hops && p.learnedAtMs < worst.learnedAtMs)) worst = p;
            }
            if (worst == null || hops > worst.hops) return;
            list.remove(worst);
        }
        list.add(new Path(nextHop, hops, nowMs, nowMs + ttlMs));
    }

    /** Live paths for a destination (copy, expired ones removed). Empty if none. */
    public List<Path> paths(int key, long nowMs) {
        List<Path> list = map.get(key);
        if (list == null) return new ArrayList<>(0);
        List<Path> out = new ArrayList<>(list.size());
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).expiresAtMs <= nowMs) list.remove(i);
        }
        out.addAll(list);
        if (list.isEmpty()) map.remove(key);
        return out;
    }

    /** Forget one first hop for one destination (it failed). */
    public void removePath(int key, int nextHop) {
        List<Path> list = map.get(key);
        if (list == null) return;
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).nextHop == nextHop) list.remove(i);
        if (list.isEmpty()) map.remove(key);
    }

    /** Forget a neighbour everywhere (it went away). */
    public void removeNextHop(int nextHop) {
        List<Integer> empty = new ArrayList<>();
        for (Map.Entry<Integer, List<Path>> e : map.entrySet()) {
            List<Path> l = e.getValue();
            for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).nextHop == nextHop) l.remove(i);
            if (l.isEmpty()) empty.add(e.getKey());
        }
        for (Integer k : empty) map.remove(k);
    }

    public void clear() {
        map.clear();
    }

    public int destinations() {
        return map.size();
    }
}
