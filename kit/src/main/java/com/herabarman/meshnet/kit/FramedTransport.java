package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.Packet;
import com.herabarman.meshnet.core.SystemClock;
import com.herabarman.meshnet.core.Transport;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adapts a RawRadio to the core's Transport and does the link-level zone handshake.
 *
 * When a link comes up, both sides send ZONE(my zone). The core is told about the peer (onPeerUp) only
 * after its zone is known, so a neighbour is filed as "same zone" or "backbone" correctly from the first
 * packet. When my zone changes I send ZONE again; the core re-files me on the other side.
 *
 * Link frames (first byte): 0x00 ping (ignored), 0x01 ZONE + int, 0x03 GOSSIP (a few "node -> zone" pairs, so
 * a phone learns where far-away contacts live without any location server). Core packets start with Packet.VERSION.
 * All calls into the core are moved onto the engine thread.
 */
public final class FramedTransport implements Transport, RawRadio.Handler {
    static final int PING = 0;
    static final int ZONE = 1;
    static final int PROBE = 4;     // link quality probe: PROBE seq(4) is answered with PONG seq(4)
    static final int PONG = 5;
    static final int GOSSIP = 3;
    static final int BCAST = 6;     // link-level broadcast: BCAST kind(1) payload; only to direct neighbours, never relayed by the core   // must differ from Packet.VERSION (2), which is the first byte of every core packet
    static final int GOSSIP_MAX = 40;

    /** Address book the transport gossips from and into. pairs = node0, zone0, node1, zone1, ... */
    /** Handler for link-level broadcast frames (alerts, public-channel gossip). Called on the radio's thread. */
    public interface BroadcastHandler { void onBroadcast(int fromPeer, byte[] payload); }

    public interface Directory {
        int[] snapshot(int maxEntries, java.util.Set<Integer> directPeers);
        void learnGossip(int fromPeer, int[] pairs);
    }

    public interface PeerListener {
        void onPeerZone(int peer, int zone);
        void onPeerCount(int count);
        void onPeerDown(int peer);
    }

    private final RawRadio radio;
    private final SystemClock clock;
    private final Set<Integer> links = java.util.Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
    private final Set<Integer> up = java.util.Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
    private final Map<Integer, Integer> peerZones = new ConcurrentHashMap<>();
    private volatile Transport.Listener core;
    private volatile PeerListener peerListener;
    private volatile Directory directory;
    private volatile BroadcastHandler broadcastHandler;
    private final Map<Integer, Double> scales = new ConcurrentHashMap<>();
    private volatile int myZone;
    private volatile double rateBurst = 120;
    private volatile double ratePerSec = 60;
    private final Map<Integer, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final Map<Integer, int[]> abuse = new ConcurrentHashMap<>();        // peer -> {dropsInWindow, windowStartSec}
    private final java.util.concurrent.atomic.AtomicLong rateLimited = new java.util.concurrent.atomic.AtomicLong();
    private final Map<Integer, Probe> probes = new ConcurrentHashMap<>();
    private int probeSeq;

    /** Round-trip time and loss on one link, measured with small probes. */
    public static final class LinkQuality {
        public final int peer;
        public final long rttMs;        // smoothed, -1 if no answer yet
        public final int lossPct;       // over the last (up to) 20 probes
        public final int samples;
        LinkQuality(int peer, long rtt, int loss, int samples) { this.peer = peer; this.rttMs = rtt; this.lossPct = loss; this.samples = samples; }
        @Override public String toString() { return "peer " + peer + ": rtt " + rttMs + " ms, loss " + lossPct + "% (" + samples + " probes)"; }
    }

    private static final class Probe {
        int pendingSeq = -1;
        long sentNanos;
        double rttEma = -1;
        long history;          // bit i = probe i lost
        int count;             // probes recorded (max 20 counted)
    }

    public FramedTransport(RawRadio radio, SystemClock clock, int myZone) {
        this.radio = radio;
        this.clock = clock;
        this.myZone = myZone;
        radio.setHandler(this);
    }

    public void setPeerListener(PeerListener l) { this.peerListener = l; }

    public void setDirectory(Directory d) { this.directory = d; }

    public void setBroadcastHandler(BroadcastHandler h) { this.broadcastHandler = h; }

    /** Neighbours whose zone handshake is done (a copy). */
    public java.util.Set<Integer> neighbors() { return new java.util.HashSet<>(up); }

    /** Link-level broadcast to every neighbour: payload = kind(1) + data. Not routed, not retried, not encrypted. */
    public void broadcast(byte[] payload) {
        byte[] f = new byte[1 + payload.length];
        f[0] = (byte) BCAST;
        System.arraycopy(payload, 0, f, 1, payload.length);
        for (int peer : up) radio.sendRaw(peer, f);
    }

    /** Same, to one neighbour. */
    public void linkSend(int peer, byte[] payload) {
        if (!up.contains(peer)) return;
        byte[] f = new byte[1 + payload.length];
        f[0] = (byte) BCAST;
        System.arraycopy(payload, 0, f, 1, payload.length);
        radio.sendRaw(peer, f);
    }

    /** Scale one neighbour's rate limit (0.5 = half, 1.5 = one and a half times); used by the fairness score. */
    public void setPeerScale(int peer, double scale) {
        scales.put(peer, scale);
        TokenBucket tb = buckets.get(peer);
        if (tb != null) synchronized (tb) { tb.setScale(scale); }
    }

    /** Per neighbour: at most `burst` core packets at once, then `perSecond`. Excess is dropped; sustained abuse disconnects. */
    public void setRateLimit(double burst, double perSecond) { this.rateBurst = burst; this.ratePerSec = perSecond; }

    public long rateLimitedCount() { return rateLimited.get(); }

    /** Send one probe to every neighbour; an unanswered earlier probe is counted as lost. Call about every 10 s. */
    public void probeAll() {
        for (int peer : up) {
            Probe p = probes.get(peer);
            if (p == null) { p = new Probe(); probes.put(peer, p); }
            synchronized (p) {
                if (p.pendingSeq >= 0) record(p, true);
                p.pendingSeq = ++probeSeq;
                p.sentNanos = System.nanoTime();
                radio.sendRaw(peer, ByteBuffer.allocate(5).put((byte) PROBE).putInt(p.pendingSeq).array());
            }
        }
    }

    private static void record(Probe p, boolean lost) {
        p.history = (p.history << 1) | (lost ? 1 : 0);
        if (p.count < 20) p.count++;
        p.pendingSeq = -1;
    }

    public LinkQuality quality(int peer) {
        Probe p = probes.get(peer);
        if (p == null) return new LinkQuality(peer, -1, 0, 0);
        synchronized (p) {
            int lost = Long.bitCount(p.history & ((1L << p.count) - 1));
            return new LinkQuality(peer, p.rttEma < 0 ? -1 : Math.round(p.rttEma), p.count == 0 ? 0 : lost * 100 / p.count, p.count);
        }
    }

    public java.util.List<LinkQuality> qualities() {
        java.util.List<LinkQuality> l = new java.util.ArrayList<>();
        for (int peer : up) l.add(quality(peer));
        return l;
    }

    public void dropPeer(int peer) { radio.drop(peer); }

    public int peerCount() { return up.size(); }

    public int zoneOfPeer(int peer) {
        Integer z = peerZones.get(peer);
        return z == null ? Packet.UNKNOWN_ZONE : z;
    }

    // ---- Transport (core side) ----

    @Override
    public void send(int peerId, byte[] data) {
        if (links.contains(peerId)) radio.sendRaw(peerId, data);
    }

    @Override
    public void setListener(Transport.Listener listener) { this.core = listener; }

    // ---- zone announcements ----

    public void announceZone(int zone) {
        myZone = zone;
        for (int peer : links) sendZone(peer);
    }

    private void sendZone(int peer) {
        radio.sendRaw(peer, ByteBuffer.allocate(5).put((byte) ZONE).putInt(myZone).array());
    }

    /** Send my address book to every neighbour (on new neighbours and now and then). */
    public void gossipNow() {
        Directory d = directory;
        if (d == null) return;
        int[] pairs = d.snapshot(GOSSIP_MAX, up);
        if (pairs.length == 0) return;
        ByteBuffer b = ByteBuffer.allocate(2 + 4 * pairs.length).put((byte) GOSSIP).put((byte) (pairs.length / 2));
        for (int v : pairs) b.putInt(v);
        byte[] frame = b.array();
        for (int peer : up) radio.sendRaw(peer, frame);
    }

    public void sendPing(int peer) {
        if (links.contains(peer)) radio.sendRaw(peer, new byte[]{PING});
    }

    public Set<Integer> linkedPeers() { return links; }

    // ---- RawRadio.Handler (radio side) ----

    @Override
    public void onLinkUp(int peer) {
        links.add(peer);
        sendZone(peer);
    }

    @Override
    public void onLinkDown(final int peer) {
        links.remove(peer);
        peerZones.remove(peer);
        buckets.remove(peer);
        probes.remove(peer);
        abuse.remove(peer);
        final boolean was = up.remove(peer);
        if (was) {
            post(new Runnable() {
                @Override public void run() {
                    Transport.Listener l = core;
                    if (l != null) l.onPeerDown(peer);
                }
            });
        }
        PeerListener pl = peerListener;
        if (was && pl != null) pl.onPeerDown(peer);
        notifyCount();
    }

    @Override
    public void onRaw(final int peer, final byte[] data) {
        if (data == null || data.length == 0) return;
        int b0 = data[0] & 0xFF;
        if (b0 == PING) return;
        if (b0 == ZONE) {
            if (data.length != 5) return;
            final int zone = ByteBuffer.wrap(data, 1, 4).getInt();
            if (zone < 0) return;
            links.add(peer);
            peerZones.put(peer, zone);
            up.add(peer);
            PeerListener pl = peerListener;
            if (pl != null) pl.onPeerZone(peer, zone);
            post(new Runnable() {
                @Override public void run() {
                    Transport.Listener l = core;
                    if (l != null) l.onPeerUp(peer); // repeated calls re-file the peer under its new zone
                }
            });
            notifyCount();
            return;
        }
        if (b0 == PROBE && data.length == 5) {
            if (up.contains(peer)) radio.sendRaw(peer, new byte[]{(byte) PONG, data[1], data[2], data[3], data[4]});
            return;
        }
        if (b0 == PONG && data.length == 5) {
            Probe p = probes.get(peer);
            if (p == null) return;
            synchronized (p) {
                if (p.pendingSeq != ByteBuffer.wrap(data, 1, 4).getInt()) return;
                double rtt = (System.nanoTime() - p.sentNanos) / 1_000_000.0;
                p.rttEma = p.rttEma < 0 ? rtt : 0.7 * p.rttEma + 0.3 * rtt;
                record(p, false);
            }
            return;
        }
        if (b0 == BCAST) {
            BroadcastHandler bh = broadcastHandler;
            if (bh == null || data.length < 2 || !up.contains(peer)) return;
            long now = System.currentTimeMillis();
            TokenBucket tb = buckets.get(peer);
            if (tb == null) { tb = new TokenBucket(rateBurst, ratePerSec, now); buckets.put(peer, tb); }
            boolean ok;
            synchronized (tb) { ok = tb.tryTake(now); }
            if (!ok) { rateLimited.incrementAndGet(); return; }
            bh.onBroadcast(peer, java.util.Arrays.copyOfRange(data, 1, data.length));
            return;
        }
        if (b0 == GOSSIP) {
            Directory d = directory;
            if (d == null || data.length < 2 || !up.contains(peer)) return;
            int n = data[1] & 0xFF;
            if (n > GOSSIP_MAX || data.length != 2 + 8 * n) return;
            int[] pairs = new int[2 * n];
            ByteBuffer b = ByteBuffer.wrap(data, 2, 8 * n);
            for (int i = 0; i < pairs.length; i++) pairs[i] = b.getInt();
            d.learnGossip(peer, pairs);
            return;
        }
        if (b0 == Packet.VERSION && up.contains(peer)) {
            long now = System.currentTimeMillis();
            TokenBucket tb = buckets.get(peer);
            if (tb == null) {
                tb = new TokenBucket(rateBurst, ratePerSec, now);
                Double sc = scales.get(peer);
                if (sc != null) tb.setScale(sc);
                buckets.put(peer, tb);
            }
            boolean ok;
            synchronized (tb) { ok = tb.tryTake(now); }
            if (!ok) {
                rateLimited.incrementAndGet();
                int[] a = abuse.get(peer);
                int sec = (int) (now / 10_000);
                if (a == null || a[1] != sec) { a = new int[]{0, sec}; abuse.put(peer, a); }
                if (++a[0] == 300) radio.drop(peer);          // 300 refused packets within 10 s: this neighbour is flooding
                return;
            }
            post(new Runnable() {
                @Override public void run() {
                    Transport.Listener l = core;
                    if (l != null) l.onReceive(peer, data);
                }
            });
        }
    }

    /** Radio callbacks can still arrive for a moment after stop(): ignore them instead of crashing the radio thread. */
    private void post(Runnable r) {
        try {
            clock.post(r);
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            // engine is shut down
        }
    }

    private void notifyCount() {
        PeerListener pl = peerListener;
        if (pl != null) pl.onPeerCount(up.size());
    }
}
