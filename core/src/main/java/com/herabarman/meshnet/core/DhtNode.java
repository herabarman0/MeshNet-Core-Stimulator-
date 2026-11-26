package com.herabarman.meshnet.core;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A Kademlia distributed hash table for finding nodes in a big mesh without a central table. Every node is a
 * participant; the value stored for a node is "where I am" (its zone), kept by the k nodes whose ids are closest to
 * hash(nodeId). Finding a node costs about log2(N) lookup rounds however big the network is, and no node holds more
 * than a small slice. It complements the gossip directory: gossip answers "who is near me" instantly; the DHT answers
 * "where is this far-away node" at logarithmic cost.
 *
 * What it does: k-bucket routing table (XOR distance on a hashed 32-bit id), iterative lookup with alpha parallel
 * requests and per-request timeouts, STORE to the k closest, FIND_VALUE that asks the nearest holders and returns the
 * newest version (so a node that moved is found at its new zone even if some replicas are stale), republishing,
 * value expiry, failure counting with replacement of dead contacts.
 * Contacts are (nodeId, zone): the zone is what the mesh needs to route a message to that node, so the DHT never needs
 * the very location service it provides. Messages are datagrams (best effort): the caller supplies {@link Net}.
 * Engine thread only.
 */
public final class DhtNode {
    /** Send one best-effort message to a node (zone = where the mesh should look for it). */
    public interface Net {
        void send(int nodeId, int zone, byte[] message);
    }

    public interface LocationCallback {
        /** zone = -1 when nobody knew the node. */
        void onResult(int nodeId, int zone);
    }

    public interface ValueCallback {
        /** value = null when not found. */
        void onResult(int key, byte[] value);
    }

    public static final class Config {
        public int k = 8;
        public int alpha = 3;
        public long rpcTimeoutMs = 2_500;
        public long lookupMaxMs = 20_000;
        /** A value found on this many nodes ends the lookup early (2 guards against one stale replica). */
        public int valueQuorum = 2;
        public long valueTtlMs = 2 * 3_600_000L;
        public long republishMs = 3_600_000L;
        public int maxStored = 256;
        public int replacementsPerBucket = 3;
        public int maxFailures = 2;
    }

    private static final int OP_FIND_NODE = 1, OP_NODES = 2, OP_FIND_VALUE = 3, OP_VALUE = 4, OP_STORE = 5;
    private static final int HEADER = 1 + 4 + 4;          // op, rpcId, sender zone

    /** Mixes a node id (or any key) into a uniformly spread 32-bit DHT id, so sequential user ids do not cluster. */
    public static int dhtId(int x) {
        x ^= x >>> 16;
        x *= 0x85EBCA6B;
        x ^= x >>> 13;
        x *= 0xC2B2AE35;
        x ^= x >>> 16;
        return x;
    }

    /** A DHT peer. */
    public static final class Contact {
        public final int nodeId;
        public int zone;
        final int dhtId;
        int failures;

        Contact(int nodeId, int zone) {
            this.nodeId = nodeId;
            this.zone = zone;
            this.dhtId = dhtId(nodeId);
        }
    }

    private static final class StoredValue {
        final byte[] value;
        final int version;
        final long expiresAtMs;

        StoredValue(byte[] value, int version, long expiresAtMs) {
            this.value = value;
            this.version = version;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private final int self;
    private final int selfDht;
    private final Config cfg;
    private final Clock clock;
    private final Random rng;
    private final Net net;
    private int zone;
    private boolean running;
    private int publishedVersion;
    private RecordGuard guard;

    @SuppressWarnings("unchecked")
    private final List<Contact>[] buckets = (List<Contact>[]) new List[32];
    @SuppressWarnings("unchecked")
    private final List<Contact>[] replacements = (List<Contact>[]) new List[32];
    private final Map<Integer, StoredValue> store = new LinkedHashMap<>();
    private final Map<Integer, Lookup> lookups = new HashMap<>();     // rpcId -> lookup waiting for it
    private final Map<Integer, Contact> inflight = new HashMap<>();   // rpcId -> who I asked

    public long lookupsStarted, rpcsSent, rpcsTimedOut, storesAccepted;

    public DhtNode(int selfId, int selfZone, Config cfg, Clock clock, Random rng, Net net) {
        this.self = selfId;
        this.selfDht = dhtId(selfId);
        this.zone = selfZone;
        this.cfg = cfg;
        this.clock = clock;
        this.rng = rng;
        this.net = net;
    }

    public int zone() { return zone; }

    /**
     * Turn on signed locations: published locations are signed, and every location value that is stored here, or that
     * comes back from a lookup, is verified. Values under keys that are not a node's location hash are not
     * covered (they are generic application data) and are refused while a guard is set, because they cannot be checked.
     */
    public void setGuard(RecordGuard g) { this.guard = g; }

    public RecordGuard guard() { return guard; }

    // ---- routing table ----------------------------------------------------------------------

    private int bucketOf(int otherDht) {
        int d = selfDht ^ otherDht;
        return d == 0 ? 0 : 31 - Integer.numberOfLeadingZeros(d);
    }

    /** I heard about this node (any packet from it, a neighbour list, a gossip record). */
    public void addContact(int nodeId, int nodeZone) {
        if (nodeId == self || nodeZone < 0) return;
        Contact fresh = new Contact(nodeId, nodeZone);
        int bi = bucketOf(fresh.dhtId);
        List<Contact> b = buckets[bi];
        if (b == null) {
            b = new ArrayList<>(cfg.k);
            buckets[bi] = b;
        }
        for (int i = 0; i < b.size(); i++) {
            Contact c = b.get(i);
            if (c.nodeId == nodeId) {                  // known: now the most recently seen, with its newest zone
                c.zone = nodeZone;
                c.failures = 0;
                b.remove(i);
                b.add(c);
                return;
            }
        }
        if (b.size() < cfg.k) {
            b.add(fresh);
            return;
        }
        Contact oldest = b.get(0);                     // full: a contact that already failed makes room, otherwise keep the old one
        if (oldest.failures > 0) {
            b.remove(0);
            b.add(fresh);
            return;
        }
        List<Contact> rep = replacements[bi];
        if (rep == null) {
            rep = new ArrayList<>(cfg.replacementsPerBucket);
            replacements[bi] = rep;
        }
        for (Iterator<Contact> it = rep.iterator(); it.hasNext(); ) if (it.next().nodeId == nodeId) it.remove();
        rep.add(fresh);
        if (rep.size() > cfg.replacementsPerBucket) rep.remove(0);
    }

    private void contactFailed(Contact c) {
        c.failures++;
        if (c.failures < cfg.maxFailures) return;
        int bi = bucketOf(c.dhtId);
        List<Contact> b = buckets[bi];
        if (b == null || !b.remove(c)) return;
        List<Contact> rep = replacements[bi];
        if (rep != null && !rep.isEmpty()) b.add(rep.remove(rep.size() - 1));
    }

    public int contactCount() {
        int n = 0;
        for (List<Contact> b : buckets) if (b != null) n += b.size();
        return n;
    }

    /** The n known contacts closest to a DHT id (XOR distance), nearest first. */
    public List<Contact> closest(int targetDht, int n, Set<Integer> exclude) {
        List<Contact> all = new ArrayList<>();
        for (List<Contact> b : buckets) if (b != null) all.addAll(b);
        final int t = targetDht;
        Collections.sort(all, new Comparator<Contact>() {
            @Override public int compare(Contact a, Contact c) { return Integer.compareUnsigned(a.dhtId ^ t, c.dhtId ^ t); }
        });
        List<Contact> out = new ArrayList<>(n);
        for (Contact c : all) {
            if (exclude != null && exclude.contains(c.nodeId)) continue;
            out.add(c);
            if (out.size() >= n) break;
        }
        return out;
    }

    // ---- life cycle -------------------------------------------------------------------------

    /** Start periodic republishing of my location. */
    public void start() {
        if (running) return;
        running = true;
        schedulePeriodic();
    }

    public void stop() { running = false; }

    private void schedulePeriodic() {
        long delay = cfg.republishMs - cfg.republishMs / 10 + rng.nextInt((int) Math.max(1, cfg.republishMs / 5));
        clock.schedule(delay, new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                publishLocation();
                expireStore();
                schedulePeriodic();
            }
        });
    }

    /** Join: ask a node I already know about for the nodes near me; that fills my buckets. */
    public void bootstrap(int seedNode, int seedZone) {
        addContact(seedNode, seedZone);
        startLookup(selfDht, OP_FIND_NODE, null, null);
    }

    /** I moved: publish the new location now. */
    public void setZone(int newZone) {
        if (newZone == zone) return;
        zone = newZone;
        publishLocation();
    }

    // ---- public operations ------------------------------------------------------------------

    /** Store "node X is in zone Z" on the k nodes closest to hash(X), with a higher version than before. */
    public void publishLocation() {
        publishedVersion = Math.max(publishedVersion + 1, (int) (clock.nowMs() / 1000));
        byte[] v = guard != null ? guard.signedLocation(self, zone, publishedVersion) : ByteBuffer.allocate(4).putInt(zone).array();
        storeValue(dhtId(self), v, publishedVersion, cfg.valueTtlMs);
    }

    private final Map<Integer, Object[]> pendingStores = new HashMap<>();

    /** Find where a node is. The callback runs once, with zone = -1 if it could not be found. */
    public void lookupLocation(final int nodeId, final LocationCallback cb) {
        if (nodeId == self) {
            cb.onResult(nodeId, zone);
            return;
        }
        findValue(dhtId(nodeId), new ValueCallback() {
            @Override
            public void onResult(int key, byte[] value) {
                cb.onResult(nodeId, value == null ? -1 : guard != null ? (value.length >= 8 ? ByteBuffer.wrap(value).getInt(4) : -1) : value.length == 4 ? ByteBuffer.wrap(value).getInt() : -1);
            }
        });
    }

    /** Generic: find the newest value stored under a key. */
    public void findValue(int key, ValueCallback cb) {
        startLookup(key, OP_FIND_VALUE, cb, null);
    }

    /** Generic: store a value (max about 1 KB) under a key on the k closest nodes. */
    public void storeValue(int key, byte[] value, int version, long ttlMs) {
        putLocal(key, value, version, ttlMs);
        pendingStores.put(key, new Object[]{value, version});
        startLookup(key, OP_FIND_NODE, null, null);
    }

    /** With a guard: only genuine, signed locations pass (checked again for every value that arrives). */
    private boolean valueOk(int key, byte[] value, int version) {
        return guard == null || guard.checkLocation(key, value, version) >= 0;
    }

    // ---- local value store ------------------------------------------------------------------

    private void putLocal(int key, byte[] value, int version, long ttlMs) {
        StoredValue old = store.get(key);
        if (old != null && old.version > version && old.expiresAtMs > clock.nowMs()) return;
        store.put(key, new StoredValue(value, version, clock.nowMs() + ttlMs));
        if (store.size() > cfg.maxStored) {
            expireStore();
            Iterator<Integer> it = store.keySet().iterator();
            while (store.size() > cfg.maxStored && it.hasNext()) {
                int k = it.next();
                if (k != dhtId(self)) it.remove();
            }
        }
    }

    private StoredValue liveValue(int key) {
        StoredValue v = store.get(key);
        if (v == null) return null;
        if (v.expiresAtMs <= clock.nowMs()) {
            store.remove(key);
            return null;
        }
        return v;
    }

    private void expireStore() {
        long now = clock.nowMs();
        for (Iterator<StoredValue> it = store.values().iterator(); it.hasNext(); ) if (it.next().expiresAtMs <= now) it.remove();
    }

    public int storedCount() { return store.size(); }

    // ---- iterative lookup -------------------------------------------------------------------

    private final class Lookup {
        final int target;
        final int op;
        final ValueCallback valueCb;
        final Runnable done;
        final long startedAt;
        final Map<Integer, Contact> candidates = new HashMap<>();   // nodeId -> contact, everything I have heard of
        final Set<Integer> queried = new HashSet<>();
        final Set<Integer> answered = new HashSet<>();
        final Set<Integer> failed = new HashSet<>();
        int pending;
        int valuesSeen;
        int bestVersion = Integer.MIN_VALUE;
        byte[] bestValue;
        boolean finished;
        int rounds;

        Lookup(int target, int op, ValueCallback valueCb, Runnable done) {
            this.target = target;
            this.op = op;
            this.valueCb = valueCb;
            this.done = done;
            this.startedAt = clock.nowMs();
        }

        List<Contact> sorted() {
            List<Contact> l = new ArrayList<>(candidates.values());
            final int t = target;
            Collections.sort(l, new Comparator<Contact>() {
                @Override public int compare(Contact a, Contact b) { return Integer.compareUnsigned(a.dhtId ^ t, b.dhtId ^ t); }
            });
            return l;
        }
    }

    private void startLookup(int target, int op, ValueCallback valueCb, Runnable done) {
        lookupsStarted++;
        Lookup lk = new Lookup(target, op, valueCb, done);
        for (Contact c : closest(target, cfg.k, null)) lk.candidates.put(c.nodeId, c);
        StoredValue mine = op == OP_FIND_VALUE ? liveValue(target) : null;
        if (mine != null) {
            lk.valuesSeen++;
            lk.bestVersion = mine.version;
            lk.bestValue = mine.value;
        }
        final Lookup fl = lk;
        clock.schedule(cfg.lookupMaxMs, new Runnable() {
            @Override public void run() { finish(fl); }
        });
        advance(lk);
    }

    private void advance(Lookup lk) {
        if (lk.finished) return;
        if (lk.op == OP_FIND_VALUE && lk.valuesSeen >= cfg.valueQuorum) {
            finish(lk);
            return;
        }
        List<Contact> sorted = lk.sorted();
        // converged when every one of the k closest candidates has answered (or failed)
        int considered = 0;
        boolean allDone = true;
        for (Contact c : sorted) {
            if (lk.failed.contains(c.nodeId)) continue;
            if (considered++ >= cfg.k) break;
            if (!lk.answered.contains(c.nodeId)) {
                allDone = false;
                break;
            }
        }
        if (allDone && lk.pending == 0) {
            finish(lk);
            return;
        }
        considered = 0;
        for (Contact c : sorted) {
            if (lk.pending >= cfg.alpha) break;
            if (lk.failed.contains(c.nodeId)) continue;
            if (considered++ >= cfg.k + cfg.alpha) break;
            if (lk.queried.contains(c.nodeId)) continue;
            query(lk, c);
        }
        if (lk.pending == 0) finish(lk);               // nobody left to ask
    }

    private void query(final Lookup lk, final Contact c) {
        lk.queried.add(c.nodeId);
        lk.pending++;
        final int rpc = rng.nextInt() & 0x7FFFFFFF;
        lookups.put(rpc, lk);
        inflight.put(rpc, c);
        ByteBuffer b = ByteBuffer.allocate(HEADER + 4);
        b.put((byte) (lk.op == OP_FIND_VALUE ? OP_FIND_VALUE : OP_FIND_NODE)).putInt(rpc).putInt(zone).putInt(lk.target);
        rpcsSent++;
        net.send(c.nodeId, c.zone, b.array());
        clock.schedule(cfg.rpcTimeoutMs, new Runnable() {
            @Override
            public void run() {
                if (lookups.remove(rpc) == null) return;      // answered in time
                inflight.remove(rpc);
                rpcsTimedOut++;
                lk.pending--;
                lk.failed.add(c.nodeId);
                contactFailed(c);
                advance(lk);
            }
        });
    }

    private void finish(Lookup lk) {
        if (lk.finished) return;
        lk.finished = true;
        if (lk.op == OP_FIND_VALUE) {
            if (lk.valueCb != null) lk.valueCb.onResult(lk.target, lk.bestValue);
            return;
        }
        Object[] ps = pendingStores.remove(lk.target);
        if (ps != null) {                                  // the lookup found the k closest: store there
            byte[] value = (byte[]) ps[0];
            int version = (Integer) ps[1];
            int sent = 0;
            for (Contact c : lk.sorted()) {
                if (lk.failed.contains(c.nodeId) || !lk.answered.contains(c.nodeId)) continue;
                ByteBuffer b = ByteBuffer.allocate(HEADER + 4 + 4 + 4 + 2 + value.length);
                b.put((byte) OP_STORE).putInt(0).putInt(zone).putInt(lk.target).putInt(version)
                        .putInt((int) Math.min(Integer.MAX_VALUE, cfg.valueTtlMs / 1000)).putShort((short) value.length).put(value);
                rpcsSent++;
                net.send(c.nodeId, c.zone, b.array());
                if (++sent >= cfg.k) break;
            }
        }
        if (lk.done != null) lk.done.run();
    }

    // ---- incoming messages ------------------------------------------------------------------

    /** Feed a DHT message received from a node. Malformed input is ignored. */
    public void onMessage(int fromNode, byte[] m) {
        if (m == null || m.length < HEADER) return;
        try {
            ByteBuffer b = ByteBuffer.wrap(m);
            int op = b.get() & 0xFF;
            int rpc = b.getInt();
            int fromZone = b.getInt();
            if (fromNode == self) return;
            addContact(fromNode, fromZone);
            switch (op) {
                case OP_FIND_NODE: {
                    int target = b.getInt();
                    reply(fromNode, fromZone, OP_NODES, rpc, nodesPayload(target, fromNode));
                    break;
                }
                case OP_FIND_VALUE: {
                    int key = b.getInt();
                    StoredValue v = liveValue(key);
                    if (v != null) {
                        ByteBuffer p = ByteBuffer.allocate(4 + 2 + v.value.length);
                        p.putInt(v.version).putShort((short) v.value.length).put(v.value);
                        reply(fromNode, fromZone, OP_VALUE, rpc, p.array());
                    } else {
                        reply(fromNode, fromZone, OP_NODES, rpc, nodesPayload(key, fromNode));
                    }
                    break;
                }
                case OP_STORE: {
                    int key = b.getInt();
                    int version = b.getInt();
                    long ttl = Math.min(cfg.valueTtlMs, (b.getInt() & 0xFFFFFFFFL) * 1000L);
                    int len = b.getShort() & 0xFFFF;
                    if (b.remaining() != len || len > 1024) return;
                    byte[] v = new byte[len];
                    b.get(v);
                    if (!valueOk(key, v, version)) break;          // forged or unverifiable: not stored
                    storesAccepted++;
                    putLocal(key, v, version, ttl);
                    break;
                }
                case OP_NODES:
                case OP_VALUE: {
                    Lookup lk = lookups.remove(rpc);
                    Contact asked = inflight.remove(rpc);
                    if (lk == null || asked == null || asked.nodeId != fromNode) return;   // late, unknown, or from someone I did not ask
                    lk.pending--;
                    lk.answered.add(fromNode);
                    asked.failures = 0;
                    if (op == OP_NODES) {
                        int n = b.get() & 0xFF;
                        for (int i = 0; i < n && b.remaining() >= 8; i++) {
                            int id = b.getInt();
                            int z = b.getInt();
                            if (id == self || z < 0) continue;
                            if (!lk.candidates.containsKey(id)) lk.candidates.put(id, new Contact(id, z));
                            addContact(id, z);
                        }
                    } else {
                        int version = b.getInt();
                        int len = b.getShort() & 0xFFFF;
                        if (b.remaining() == len) {
                            byte[] v = new byte[len];
                            b.get(v);
                            if (!valueOk(lk.target, v, version)) { advance(lk); return; }   // a lying replica does not count
                            lk.valuesSeen++;
                            if (lk.bestValue == null || version > lk.bestVersion) {
                                lk.bestVersion = version;
                                lk.bestValue = v;
                            }
                        }
                    }
                    advance(lk);
                    break;
                }
                default:
                    break;
            }
        } catch (RuntimeException e) {
            // never let a broken datagram hurt the engine
        }
    }

    private byte[] nodesPayload(int targetDht, int exceptNode) {
        Set<Integer> ex = new HashSet<>();
        ex.add(exceptNode);
        List<Contact> l = closest(targetDht, cfg.k, ex);
        ByteBuffer p = ByteBuffer.allocate(1 + 8 * l.size());
        p.put((byte) l.size());
        for (Contact c : l) p.putInt(c.nodeId).putInt(c.zone);
        return p.array();
    }

    private void reply(int toNode, int toZone, int op, int rpc, byte[] payload) {
        ByteBuffer b = ByteBuffer.allocate(HEADER + payload.length);
        b.put((byte) op).putInt(rpc).putInt(zone).put(payload);
        net.send(toNode, toZone, b.array());
    }
}
