package com.herabarman.meshnet.core;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Directory records that spread by gossip between direct neighbours, with no server and no global table.
 * A record says "owner O says: about subject S, the value is V" in a namespace:
 *   NS_LOCATION: subject = a node, owner = that node, value = the zone it is in
 *   NS_GATEWAY:  subject = a zone, owner = a gateway / border node of that zone, value = its score 0..255
 * so "who are the gateways of zone Z" is every live NS_GATEWAY record with subject Z, and no node ever needs the
 * whole picture. Rules that keep it cheap and correct:
 *  - Only the owner raises a record's version (seconds since epoch, or +1); the highest version wins, so there is
 *    nothing to merge and it needs no clock agreement between nodes.
 *  - A record has a radius: how many hops it may be passed on. It spreads through the neighbourhood and stops;
 *    reaching further is the DHT's job. Memory is capped (maxRecords) and records expire (ttl travels as seconds left).
 *  - New or changed records are pushed for a few rounds (rumour mongering, fast). A random sample is also exchanged as
 *    a digest every few rounds, and whoever is behind asks for the newer ones (anti-entropy, repairs what the pushes missed).
 * Not authenticated: any node can claim any record. Sign records with the identity key before trusting them for
 * anything that matters (see the kit's IdentityKeys). Engine thread only.
 */
public final class GossipDirectory {
    public static final int NS_LOCATION = 1;
    public static final int NS_GATEWAY = 2;

    private static final int OP_PUSH = 1;
    private static final int OP_DIGEST = 2;
    private static final int OP_PUSH_SIGNED = 4;      // 3 is WANT
    private static final int OP_WANT = 3;

    /** How a gossip message reaches a direct neighbour, and who the neighbours are. */
    public interface Link {
        void send(int peer, byte[] message);
        List<Integer> peers();
    }

    public interface Listener {
        /** A record was learned or replaced by a newer version. */
        void onRecord(Record r);
    }

    public static final class Config {
        public long gossipMs = 5_000;
        public int fanout = 2;
        public int maxPerMessage = 8;
        public int hotRounds = 4;
        public int digestEvery = 3;
        public int digestSize = 32;
        public int maxRecords = 512;
        public int defaultRadius = 6;
        public long defaultTtlMs = 2 * 3_600_000L;
    }

    public static final class Record {
        public final int ns;
        public final int subject;
        public final int owner;
        public int value;
        public int version;
        public long expiresAtMs;
        public int radius;
        int hot;
        byte[] pub, sig;                  // the owner's signature, when signing is on

        Record(int ns, int subject, int owner, int value, int version, long expiresAtMs, int radius) {
            this.ns = ns;
            this.subject = subject;
            this.owner = owner;
            this.value = value;
            this.version = version;
            this.expiresAtMs = expiresAtMs;
            this.radius = radius;
        }

        @Override
        public String toString() {
            return "Record{ns=" + ns + " subject=" + subject + " owner=" + owner + " value=" + value + " v=" + version + " r=" + radius + "}";
        }
    }

    private static final class Key {
        final int ns, subject, owner;

        Key(int ns, int subject, int owner) {
            this.ns = ns;
            this.subject = subject;
            this.owner = owner;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return k.ns == ns && k.subject == subject && k.owner == owner;
        }

        @Override
        public int hashCode() {
            return (ns * 31 + subject) * 31 + owner;
        }
    }

    private final int self;
    private final Config cfg;
    private final Clock clock;
    private final Random rng;
    private final Link link;
    private final Map<Key, Record> records = new LinkedHashMap<>();
    private Listener listener;
    private RecordGuard guard;
    private boolean running;
    private int round;

    public GossipDirectory(int self, Config cfg, Clock clock, Random rng, Link link) {
        this.self = self;
        this.cfg = cfg;
        this.clock = clock;
        this.rng = rng;
        this.link = link;
    }

    public void setListener(Listener l) { this.listener = l; }

    /**
     * Turn on signed records: my own are signed, every record from others is verified and unsigned or forged ones are dropped.
     * Do this before publishing anything. Signed pushes use their own message type, so nodes without a guard ignore them.
     */
    public void setGuard(RecordGuard g) { this.guard = g; }

    public RecordGuard guard() { return guard; }


    /** Start the periodic rounds. Safe to call twice. */
    public void start() {
        if (running) return;
        running = true;
        schedule();
    }

    public void stop() { running = false; }

    private void schedule() {
        long delay = cfg.gossipMs - cfg.gossipMs / 5 + rng.nextInt((int) Math.max(1, cfg.gossipMs * 2 / 5));
        clock.schedule(delay, new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                gossipRound();
                schedule();
            }
        });
    }

    // ---- own records and queries ------------------------------------------------------------

    /** Publish or refresh a record that I own (owner = me). A changed value gets a higher version at once. */
    public void publish(int ns, int subject, int value, long ttlMs, int radius) {
        long now = clock.nowMs();
        Key k = new Key(ns, subject, self);
        Record old = records.get(k);
        int version = (int) (now / 1000);
        if (old != null && version <= old.version) version = old.version + 1;
        Record r = new Record(ns, subject, self, value, version, now + ttlMs, radius);
        r.hot = cfg.hotRounds;
        if (guard != null) {
            r.pub = guard.publicKey();
            r.sig = guard.signGossip(ns, subject, self, value, version);
        }
        records.put(k, r);
        trim();
    }

    public void publishLocation(int zone) { publish(NS_LOCATION, self, zone, cfg.defaultTtlMs, cfg.defaultRadius); }

    /** I am a gateway / border node of my zone, with this score (0..255). */
    public void publishGateway(int zone, int score) { publish(NS_GATEWAY, zone, score, cfg.defaultTtlMs, cfg.defaultRadius); }

    /** I stop being a gateway: remove my record now (it expires elsewhere on its own). */
    public void withdraw(int ns, int subject) { records.remove(new Key(ns, subject, self)); }

    /** The zone of a node as far as gossip knows, or -1. */
    public int zoneOf(int nodeId) {
        Record r = records.get(new Key(NS_LOCATION, nodeId, nodeId));
        if (r == null || r.expiresAtMs <= clock.nowMs()) return -1;
        return r.value;
    }

    /** Live gateway records of a zone, best score first. */
    public List<Record> gatewaysOf(int zone) {
        long now = clock.nowMs();
        List<Record> out = new ArrayList<>();
        for (Record r : records.values()) {
            if (r.ns == NS_GATEWAY && r.subject == zone && r.expiresAtMs > now) out.add(r);
        }
        Collections.sort(out, new java.util.Comparator<Record>() {
            @Override public int compare(Record a, Record b) {
                int c = Integer.compare(b.value, a.value);
                return c != 0 ? c : Integer.compare(a.owner, b.owner);
            }
        });
        return out;
    }

    public int size() { return records.size(); }

    // ---- rounds -----------------------------------------------------------------------------

    private void gossipRound() {
        expire();
        round++;
        List<Integer> peers = link.peers();
        if (peers.isEmpty()) return;
        List<Integer> targets = new ArrayList<>(peers);
        Collections.shuffle(targets, rng);
        if (targets.size() > cfg.fanout) targets = targets.subList(0, cfg.fanout);
        List<Record> hot = new ArrayList<>();
        for (Record r : records.values()) if (r.hot > 0 && r.radius > 0) hot.add(r);
        Collections.shuffle(hot, rng);
        if (hot.size() > cfg.maxPerMessage) hot = hot.subList(0, cfg.maxPerMessage);
        for (Record r : hot) r.hot--;
        boolean digest = cfg.digestEvery > 0 && round % cfg.digestEvery == 0;
        for (int peer : targets) {
            if (!hot.isEmpty()) link.send(peer, encodePush(hot));
            if (digest) {
                byte[] d = encodeDigest();
                if (d != null) link.send(peer, d);
            }
        }
    }

    private void expire() {
        long now = clock.nowMs();
        for (Iterator<Record> it = records.values().iterator(); it.hasNext(); ) {
            Record r = it.next();
            if (r.expiresAtMs <= now && r.owner != self) it.remove();
        }
    }

    private void trim() {
        if (records.size() <= cfg.maxRecords) return;
        expire();
        while (records.size() > cfg.maxRecords) {
            Key victim = null;
            long soonest = Long.MAX_VALUE;
            for (Map.Entry<Key, Record> e : records.entrySet()) {
                if (e.getValue().owner == self) continue;
                if (e.getValue().expiresAtMs < soonest) {
                    soonest = e.getValue().expiresAtMs;
                    victim = e.getKey();
                }
            }
            if (victim == null) return;
            records.remove(victim);
        }
    }

    // ---- wire format ------------------------------------------------------------------------

    private byte[] encodePush(List<Record> list) {
        long now = clock.nowMs();
        if (guard != null) return encodeSigned(list, now);
        ByteBuffer b = ByteBuffer.allocate(2 + 20 * list.size());
        b.put((byte) OP_PUSH).put((byte) list.size());
        for (Record r : list) {
            long secs = Math.max(0, Math.min(0xFFFF, (r.expiresAtMs - now) / 1000));
            b.put((byte) r.ns).putInt(r.subject).putInt(r.owner).putInt(r.value).putInt(r.version)
                    .putShort((short) secs).put((byte) Math.max(0, r.radius - 1));
        }
        return b.array();
    }

    /** Signed records carry the owner's key and signature, so a message holds fewer of them (soft cap about 1000 bytes). */
    private byte[] encodeSigned(List<Record> list, long now) {
        List<Record> use = new ArrayList<>();
        int size = 2;
        for (Record r : list) {
            if (r.pub == null || r.sig == null) continue;
            int n = 20 + 2 + r.pub.length + r.sig.length;
            if (!use.isEmpty() && size + n > 1000) break;
            use.add(r);
            size += n;
        }
        ByteBuffer b = ByteBuffer.allocate(size);
        b.put((byte) OP_PUSH_SIGNED).put((byte) use.size());
        for (Record r : use) {
            long secs = Math.max(0, Math.min(0xFFFF, (r.expiresAtMs - now) / 1000));
            b.put((byte) r.ns).putInt(r.subject).putInt(r.owner).putInt(r.value).putInt(r.version)
                    .putShort((short) secs).put((byte) Math.max(0, r.radius - 1))
                    .put((byte) r.pub.length).put(r.pub).put((byte) r.sig.length).put(r.sig);
        }
        return b.array();
    }

    private byte[] encodeDigest() {
        List<Record> all = new ArrayList<>();
        long now = clock.nowMs();
        for (Record r : records.values()) if (r.expiresAtMs > now) all.add(r);   // edge records (radius 0) are listed too, flagged, so a neighbour can see they are stale
        if (all.isEmpty()) return null;
        Collections.shuffle(all, rng);
        if (all.size() > cfg.digestSize) all = all.subList(0, cfg.digestSize);
        ByteBuffer b = ByteBuffer.allocate(2 + 13 * all.size());
        b.put((byte) OP_DIGEST).put((byte) all.size());
        for (Record r : all) b.put((byte) (r.ns | (r.radius == 0 ? 0x80 : 0))).putInt(r.subject).putInt(r.owner).putInt(r.version);
        return b.array();
    }

    /** Feed an incoming gossip message from a direct neighbour. Malformed input is ignored. */
    public void onMessage(int fromPeer, byte[] m) {
        if (m == null || m.length < 2) return;
        try {
            ByteBuffer b = ByteBuffer.wrap(m);
            int op = b.get() & 0xFF;
            int count = b.get() & 0xFF;
            if (op == OP_PUSH_SIGNED) {
                if (guard == null) return;                              // cannot verify: ignore rather than trust
                for (int i = 0; i < count; i++) {
                    int ns = b.get() & 0xFF, subject = b.getInt(), owner = b.getInt(), value = b.getInt(), version = b.getInt();
                    int secs = b.getShort() & 0xFFFF, radius = b.get() & 0xFF;
                    byte[] pub = new byte[b.get() & 0xFF];
                    b.get(pub);
                    byte[] sig = new byte[b.get() & 0xFF];
                    b.get(sig);
                    if (owner == self) continue;
                    Record old = records.get(new Key(ns, subject, owner));
                    boolean known = old != null && old.sig != null && old.version == version && old.value == value;
                    if (!known && !guard.checkGossip(ns, subject, owner, value, version, pub, sig)) continue;   // forged: drop it
                    accept(ns, subject, owner, value, version, Math.min(secs, (int) (cfg.defaultTtlMs / 1000)), radius, pub, sig);
                }
            } else if (op == OP_PUSH) {
                if (guard != null) return;                                  // with a guard only signed records count
                if (m.length != 2 + 20 * count) return;
                for (int i = 0; i < count; i++) {
                    int ns = b.get() & 0xFF, subject = b.getInt(), owner = b.getInt(), value = b.getInt(), version = b.getInt();
                    int secs = b.getShort() & 0xFFFF, radius = b.get() & 0xFF;
                    accept(ns, subject, owner, value, version, secs, radius, null, null);
                }
            } else if (op == OP_DIGEST) {
                if (m.length != 2 + 13 * count) return;
                List<Record> newer = new ArrayList<>();
                ByteBuffer want = ByteBuffer.allocate(2 + 9 * count);
                int wants = 0;
                for (int i = 0; i < count; i++) {
                    int nsRaw = b.get() & 0xFF, subject = b.getInt(), owner = b.getInt(), version = b.getInt();
                    int ns = nsRaw & 0x7F;
                    boolean edge = (nsRaw & 0x80) != 0;                       // they hold it at radius 0: cannot pass it on
                    Record mine = records.get(new Key(ns, subject, owner));
                    if (mine == null || mine.expiresAtMs <= clock.nowMs() || mine.version < version) {
                        if (edge) continue;
                        want.put((byte) ns).putInt(subject).putInt(owner);   // they have something newer or I lack it
                        wants++;
                    } else if (mine.version > version && mine.radius > 0 && newer.size() < cfg.maxPerMessage) {
                        newer.add(mine);                                       // I have something newer: send it
                    }
                }
                if (!newer.isEmpty()) link.send(fromPeer, encodePush(newer));
                if (wants > 0) {
                    byte[] w = new byte[2 + 9 * wants];
                    System.arraycopy(want.array(), 0, w, 2, 9 * wants);
                    w[0] = (byte) OP_WANT;
                    w[1] = (byte) wants;
                    link.send(fromPeer, w);
                }
            } else if (op == OP_WANT) {
                if (m.length != 2 + 9 * count) return;
                List<Record> out = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    int ns = b.get() & 0xFF, subject = b.getInt(), owner = b.getInt();
                    Record r = records.get(new Key(ns, subject, owner));
                    if (r != null && r.expiresAtMs > clock.nowMs() && r.radius > 0 && out.size() < cfg.maxPerMessage) out.add(r);
                }
                if (!out.isEmpty()) link.send(fromPeer, encodePush(out));
            }
        } catch (RuntimeException e) {
            // a broken message from a neighbour must never hurt the engine
        }
    }

    private void accept(int ns, int subject, int owner, int value, int version, int ttlSecs, int radius, byte[] pub, byte[] sig) {
        if (owner == self) return;                       // nobody else gets to tell me about my own records
        long now = clock.nowMs();
        Key k = new Key(ns, subject, owner);
        Record old = records.get(k);
        if (old != null && old.expiresAtMs > now) {
            if (version < old.version) return;
            if (version == old.version) {                // same record again: only a shorter path to the origin matters
                if (radius > old.radius) old.radius = radius;
                return;
            }
        }
        Record r = new Record(ns, subject, owner, value, version, now + ttlSecs * 1000L, radius);
        r.hot = radius > 0 ? cfg.hotRounds : 0;
        r.pub = pub;
        r.sig = sig;
        records.put(k, r);
        trim();
        if (listener != null) listener.onRecord(r);
    }
}
