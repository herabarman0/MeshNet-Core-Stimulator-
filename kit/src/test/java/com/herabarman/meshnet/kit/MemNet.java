package com.herabarman.meshnet.kit;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** In-memory radio network and in-memory server for tests: no sockets, a few ms of latency per hop. */
final class MemNet {
    final ScheduledExecutorService ex = Executors.newScheduledThreadPool(2, r -> { Thread t = new Thread(r, "memnet"); t.setDaemon(true); return t; });
    final Map<Integer, Radio> radios = new ConcurrentHashMap<>();
    final Set<Long> links = ConcurrentHashMap.newKeySet();
    final Map<Long, Integer> lossPct = new ConcurrentHashMap<>();      // per link: % of frames silently lost
    final java.util.Random rnd = new java.util.Random(5);
    volatile boolean lossy;
    /** Test hook: sees a copy of every frame put on the air. */
    volatile java.util.function.Consumer<byte[]> tap;
    final java.util.concurrent.atomic.AtomicLong frames = new java.util.concurrent.atomic.AtomicLong();

    static long key(int a, int b) { return ((long) Math.min(a, b) << 32) | Math.max(a, b); }

    Radio radio(int node) { return radios.computeIfAbsent(node, n -> new Radio(n)); }

    void link(int a, int b) {
        if (!links.add(key(a, b))) return;
        ex.schedule(() -> { radio(a).up(b); radio(b).up(a); }, 5, TimeUnit.MILLISECONDS);
    }

    void unlink(int a, int b) {
        if (!links.remove(key(a, b))) return;
        radio(a).down(b);
        radio(b).down(a);
    }

    final class Radio implements RawRadio {
        final int node;
        volatile Handler h;
        volatile boolean running;
        Radio(int node) { this.node = node; }
        @Override public void setHandler(Handler handler) { h = handler; }
        @Override public void drop(int peer) { unlink(node, peer); }
        @Override public void start() { running = true; }
        @Override public void stop() { running = false; }
        void up(int peer) { Handler x = h; if (x != null) x.onLinkUp(peer); }
        void down(int peer) { Handler x = h; if (x != null) x.onLinkDown(peer); }
        @Override public void sendRaw(int peer, byte[] data) {
            if (!links.contains(key(node, peer))) return;
            frames.incrementAndGet();
            Integer loss = lossPct.get(key(node, peer));
            if (loss != null && rnd.nextInt(100) < loss) return;
            final byte[] copy = data.clone();
            java.util.function.Consumer<byte[]> watcher = tap;
            if (watcher != null) watcher.accept(copy);
            ex.schedule(() -> {
                try {
                    if (!links.contains(key(node, peer))) return;
                    Radio r = radios.get(peer);
                    Handler x = r == null ? null : r.h;
                    if (x != null) x.onRaw(node, copy);
                } catch (Throwable t) { t.printStackTrace(); }
            }, 2, TimeUnit.MILLISECONDS);
        }
    }

    /** Shared "internet server": presence + relay for online nodes only, no history. */
    final class Hub {
        final Map<Integer, ServerLink.Inbound> online = new ConcurrentHashMap<>();
        final Map<Integer, Integer> zones = new ConcurrentHashMap<>();
        final java.util.concurrent.atomic.AtomicLong relayed = new java.util.concurrent.atomic.AtomicLong();

        Link link() { return new Link(); }

        final class Link implements ServerLink {
            volatile int self = -1;
            volatile boolean internet = true;
            @Override public void start(int selfNode, Inbound in) { self = selfNode; online.put(selfNode, in); }
            @Override public void stop() { online.remove(self); }
            @Override public boolean available() { return internet; }
            @Override public boolean isOnline(int node) { return internet && online.containsKey(node); }
            @Override public int zoneOf(int node) { Integer z = zones.get(node); return z == null ? -1 : z; }
            @Override public void send(int node, byte[] p) {
                final Inbound in = online.get(node);
                if (in == null || !internet) return;
                relayed.incrementAndGet();
                final byte[] c = p.clone();
                ex.schedule(() -> in.onPacket(c), 5, TimeUnit.MILLISECONDS);
            }
            @Override public void publishZone(int zone) { if (self >= 0) zones.put(self, zone); }
            @Override public void refresh(int... nodes) { }
        }
    }
}
