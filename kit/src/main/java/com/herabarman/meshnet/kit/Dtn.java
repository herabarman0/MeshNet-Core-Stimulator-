package com.herabarman.meshnet.kit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Delay-tolerant delivery ("data mule"): when a message cannot reach its destination now, the sender hands
 * encrypted copies to phones that pass by; they carry them (up to 24 h, saved on disk) and deliver them when
 * they meet the destination or somebody who can reach it. Nobody but the destination can read the copy: it is
 * the sender's end-to-end ciphertext.
 *
 * Spray-and-wait with learning: a message starts with 8 copies. On meeting a neighbour the learner (DtnBrain)
 * decides whether to give it half of the copies, using PRoPHET predictabilities (who is likely to meet whom),
 * the time-of-day rhythm of encounters, link quality and fairness. The destination's confirmation (a receipt
 * that names who delivered) is the training signal. A carrier that holds the last copy only delivers.
 *
 * Needs the sender to already hold the destination's key (it met them, or got the key through the server) and
 * a key exchange with each carrier (automatic). Messages up to 4 KB, priority normal or higher.
 * Engine thread only.
 */
public final class Dtn {
    public static final int EXT_CUSTODY = 0x20, EXT_DELIVER = 0x21, EXT_RECEIPT = 0x22;
    public static final int BCAST_PROPHET = 0x03;
    static final int MAX_ITEMS = 200, INITIAL_COPIES = 8, MAX_CIPHER = 4096;
    static final long TTL_MS = 24L * 60 * 60 * 1000;
    static final long TICK_MS = 30_000;

    private static final class Item {
        final long key;
        final int origin, dst;
        final long created;
        int copies;
        final int priority;
        final byte[] cipher;
        final boolean mine;
        boolean deliverQueued;
        boolean delivered;
        long deliveredAt;
        final Map<Integer, double[]> handed = new HashMap<>();   // neighbour -> features at hand-over time

        Item(long key, int origin, int dst, long created, int copies, int priority, byte[] cipher, boolean mine) {
            this.key = key; this.origin = origin; this.dst = dst; this.created = created;
            this.copies = copies; this.priority = priority; this.cipher = cipher; this.mine = mine;
        }
    }

    public static final class Stats {
        public long handedOver, kept, delivered, learnedUpdates, itemsHeld;
        public double exploration;
        public double[] weights;
    }

    private final MeshClient c;
    private final Prophet prophet;
    private final Rhythm rhythm = new Rhythm();
    private final DtnBrain brain;
    private final Map<Long, Item> items = new LinkedHashMap<>();
    private final Map<Integer, Map<Integer, Double>> claimed = new HashMap<>();
    private final Map<Integer, double[]> reliab = new HashMap<>();
    private final Map<String, Long> giving = new HashMap<>();
    private long handedOver, kept, delivered;
    private long lastSave;

    Dtn(MeshClient c, Random rnd) {
        this.c = c;
        this.prophet = new Prophet(c.selfNode(), System.currentTimeMillis());
        this.brain = new DtnBrain(rnd);
        c.registerExt(EXT_CUSTODY, new MeshClient.ExtHandler() {
            @Override public void onExt(int from, String user, byte[] p) { onCustody(from, p); }
        });
        c.registerExt(EXT_DELIVER, new MeshClient.ExtHandler() {
            @Override public void onExt(int from, String user, byte[] p) { onDeliver(from, p); }
        });
        c.registerExt(EXT_RECEIPT, new MeshClient.ExtHandler() {
            @Override public void onExt(int from, String user, byte[] p) { onReceipt(p); }
        });
        c.registerBroadcast(BCAST_PROPHET, new MeshClient.BroadcastHandler() {
            @Override public void onBroadcast(int from, byte[] data) {
                Map<Integer, Double> t = Prophet.parseTable(data);
                claimed.put(from, t);
                prophet.transitive(from, t, c.now());
            }
        });
        c.addNeighborObserver(new MeshClient.NeighborObserver() {
            @Override public void onNeighbor(int node, boolean up) { if (up) onNeighborUp(node); }
        });
    }

    // ------------------------------------------------------------------ public

    public Stats stats() {
        Stats s = new Stats();
        s.handedOver = handedOver; s.kept = kept; s.delivered = delivered;
        s.learnedUpdates = brain.updates; s.itemsHeld = items.size();
        s.exploration = brain.exploration(); s.weights = brain.weights();
        return s;
    }

    public double predictability(int node) { return prophet.get(node); }

    // ------------------------------------------------------------------ lifecycle (engine thread)

    void start() {
        load();
        tick();
    }

    void stop() { save(); }

    private void tick() {
        long now = c.now();
        prophet.age(now);
        List<Item> all = new ArrayList<>(items.values());
        for (Item it : all) {
            if (now - it.created > TTL_MS) { expire(it); continue; }
            if (it.delivered && now - it.deliveredAt > 600_000) { items.remove(it.key); drop(it); continue; }
            if (!it.delivered) { ensureDeliver(it); for (int nb : c.neighbors()) spreadTo(it, nb); }
        }
        giving.values().removeAll(java.util.Collections.singleton(null));
        java.util.Iterator<Map.Entry<String, Long>> gi = giving.entrySet().iterator();
        while (gi.hasNext()) if (now - gi.next().getValue() > 600_000) gi.remove();
        byte[] table = prophet.table(30);
        for (int nb : c.neighbors()) c.sendToNeighbor(nb, BCAST_PROPHET, table);
        if (now - lastSave > 300_000) save();
        c.schedule(TICK_MS, new Runnable() { @Override public void run() { tick(); } });
    }

    private void onNeighborUp(final int node) {
        long now = c.now();
        prophet.encounter(node, now);
        rhythm.hit(node, now);
        if (c.aiRouter() != null) c.aiRouter().observeNode(node, c.zone(), now);
        c.sendToNeighbor(node, BCAST_PROPHET, prophet.table(30));
        c.schedule(400, new Runnable() {          // give the neighbour's table a moment to arrive
            @Override public void run() {
                for (Item it : new ArrayList<>(items.values())) { ensureDeliver(it); if (!it.delivered) spreadTo(it, node); }
            }
        });
    }

    // ------------------------------------------------------------------ taking custody

    /** The sender could not deliver this now: keep a copy that can travel. */
    void offer(Outbox.Entry e) {
        if (items.containsKey(e.localId) || items.size() >= MAX_ITEMS) return;
        int ft = Frame.type(e.frame);
        boolean receipt = e.control && ft == Frame.RCPT;
        if (e.control && !receipt) return;
        if (ft == Frame.EXT && Frame.extType(e.frame) != EXT_RECEIPT) return;   // custody traffic itself is never re-wrapped; the learning receipt may travel back
        if (e.priority < MeshClient.PRIORITY_NORMAL || e.frame.length > MAX_CIPHER) return;
        if (!c.hasKeyFor(e.dst) || e.dst == c.selfNode()) return;
        byte[] cipher;
        try { cipher = c.encryptFor(e.dst, e.frame); } catch (RuntimeException ex) { return; }
        if (cipher.length > MAX_CIPHER) return;
        Item it = new Item(e.localId, c.selfNode(), e.dst, e.createdMs, INITIAL_COPIES, e.priority, cipher, true);
        items.put(it.key, it);
        persist(it);
        for (int nb : c.neighbors()) spreadTo(it, nb);
    }

    /** The sender's own delivery confirmation arrived by another path. */
    void onOriginDelivered(long localId) {
        Item it = items.get(localId);
        if (it != null && !it.delivered) { it.delivered = true; it.deliveredAt = c.now(); delivered++; }
    }

    private void onCustody(int from, byte[] p) {
        if (p.length < 8 + 4 + 4 + 8 + 2 + 1 + 1) return;
        ByteBuffer b = ByteBuffer.wrap(p);
        long key = b.getLong();
        int origin = b.getInt(), dst = b.getInt();
        long created = b.getLong();
        int copies = b.getShort();
        int prio = b.get() & 0xFF;
        byte[] cipher = new byte[b.remaining()];
        b.get(cipher);
        long now = c.now();
        if (copies < 1 || copies > INITIAL_COPIES || cipher.length > MAX_CIPHER || now - created > TTL_MS || created > now + 60_000) return;
        if (origin == c.selfNode()) return;
        if (dst == c.selfNode()) { acceptDelivery(from, key, origin, cipher); return; }
        if (items.containsKey(key) || items.size() >= MAX_ITEMS) return;
        Item it = new Item(key, origin, dst, created, copies, prio, cipher, false);
        items.put(key, it);
        persist(it);
        ensureDeliver(it);
        for (int nb : c.neighbors()) if (nb != from) spreadTo(it, nb);
    }

    // ------------------------------------------------------------------ delivering

    /** A carrier tries the normal way to the destination once; the outbox keeps retrying it whenever the mesh changes. */
    private void ensureDeliver(final Item it) {
        if (it.mine || it.deliverQueued || it.delivered) return;
        it.deliverQueued = true;
        byte[] payload = ByteBuffer.allocate(16 + it.cipher.length).putLong(it.key).putInt(it.origin).putInt(it.dst).put(it.cipher).array();
        c.sendExtNode(it.dst, EXT_DELIVER, payload, it.priority, new Runnable() {
            @Override public void run() { items.remove(it.key); drop(it); delivered++; }
        });
    }

    private void onDeliver(int from, byte[] p) {
        if (p.length < 17) return;
        ByteBuffer b = ByteBuffer.wrap(p);
        long key = b.getLong();
        int origin = b.getInt(), dst = b.getInt();
        if (dst != c.selfNode()) return;
        byte[] cipher = new byte[b.remaining()];
        b.get(cipher);
        acceptDelivery(from, key, origin, cipher);
    }

    private void acceptDelivery(int via, long key, int origin, byte[] cipher) {
        byte[] plain = c.decryptFrom(origin, cipher);
        if (plain == null) return;
        c.handlePlain(origin, -1, plain);
        c.sendExtNode(origin, EXT_RECEIPT, ByteBuffer.allocate(12).putLong(key).putInt(via).array(), MeshClient.PRIORITY_NORMAL, null);
    }

    /** I originated this message: who delivered it? Learn from it. */
    private void onReceipt(byte[] p) {
        if (p.length != 12) return;
        ByteBuffer b = ByteBuffer.wrap(p);
        long key = b.getLong();
        int via = b.getInt();
        Item it = items.remove(key);
        if (it == null || !it.mine) return;
        for (Map.Entry<Integer, double[]> e : it.handed.entrySet()) {
            double y = e.getKey() == via ? 1.0 : 0.6;
            brain.learn(e.getValue(), y);
            if (c.aiRouter() != null) c.aiRouter().trainOutcome(aiFeaturesFor(it, e.getKey()), y);
            bumpReliability(e.getKey(), y);
        }
        if (!it.delivered) delivered++;
        drop(it);
    }

    private void expire(Item it) {
        items.remove(it.key);
        if (it.mine && !it.delivered) {
            for (Map.Entry<Integer, double[]> e : it.handed.entrySet()) {   // never arrived: every hand-over was a bad bet
                brain.learn(e.getValue(), 0.0);
                if (c.aiRouter() != null) c.aiRouter().trainOutcome(aiFeaturesFor(it, e.getKey()), 0.0);
                bumpReliability(e.getKey(), 0.0);
            }
        }
        drop(it);
    }

    // ------------------------------------------------------------------ spreading (the decision)

    private void spreadTo(final Item it, final int nb) {
        if (it.delivered || it.copies <= 1 || nb == it.dst || nb == it.origin || it.handed.containsKey(nb)) return;
        final String gk = it.key + ":" + nb;
        if (giving.containsKey(gk)) return;
        final double[] x = features(it, nb);
        boolean aiAgree = true;
        if (c.aiRouter() != null) {
            com.herabarman.meshnet.core.PredictiveAiRouter.RouteDecision aiDec = c.aiRouter().precomputeBestRoute(
                    it.dst, java.util.Collections.singletonList(nb), null, null, c.now());
            aiAgree = aiDec != null && aiDec.reliability >= 0.45;
        }
        if (brain.decide(x) && aiAgree) {
            final int give = it.copies / 2;
            giving.put(gk, c.now());
            byte[] payload = ByteBuffer.allocate(27 + it.cipher.length).putLong(it.key).putInt(it.origin).putInt(it.dst)
                    .putLong(it.created).putShort((short) give).put((byte) it.priority).put(it.cipher).array();
            c.sendExtNode(nb, EXT_CUSTODY, payload, it.priority, new Runnable() {
                @Override public void run() {
                    it.copies -= give;
                    it.handed.put(nb, x);
                    handedOver++;
                    persist(it);
                }
            });
        } else {
            kept++;
        }
    }

    private double[] aiFeaturesFor(Item it, int nb) {
        double[] f = features(it, nb);
        return new double[]{
                f[0], f[1], f[2], f[8], 0.5, f[3], f[4], 0.8, 0.0, 0.8, f[6], f[5]
        };
    }

    private double[] features(Item it, int nb) {
        long now = c.now();
        Map<Integer, Double> t = claimed.get(nb);
        double pnb = t == null || !t.containsKey(it.dst) ? 0 : t.get(it.dst);
        double pme = prophet.get(it.dst);
        double[] r = reliab.get(nb);
        double rel = r != null ? r[0] : c.fairness(nb);
        FramedTransport.LinkQuality q = c.quality(nb);
        double linkQ = q.samples >= 3 ? 1.0 - q.lossPct / 100.0 : 1.0;
        return new double[]{
                1.0, pnb, Math.max(0, pnb - pme), rel, linkQ,
                Math.min(1.0, (now - it.created) / (double) TTL_MS),
                Math.min(1.0, it.priority / 100.0),
                it.copies / (double) INITIAL_COPIES,
                rhythm.likelihood(it.dst, now)};
    }

    private void bumpReliability(int nb, double y) {
        double[] r = reliab.get(nb);
        if (r == null) { r = new double[]{c.fairness(nb)}; reliab.put(nb, r); }
        r[0] = 0.8 * r[0] + 0.2 * y;
    }

    // ------------------------------------------------------------------ storage

    private static String hex(long v) { return String.format("%016x", v); }

    private void persist(Item it) {
        com.herabarman.meshnet.core.StorageAdapter st = c.storage();
        if (st == null) return;
        ByteBuffer b = ByteBuffer.allocate(8 + 4 + 4 + 8 + 2 + 1 + 1 + it.cipher.length);
        b.putLong(it.key).putInt(it.origin).putInt(it.dst).putLong(it.created).putShort((short) it.copies)
                .put((byte) it.priority).put((byte) (it.mine ? 1 : 0)).put(it.cipher);
        st.put("dtn-it-" + hex(it.key), b.array());
    }

    private void drop(Item it) {
        com.herabarman.meshnet.core.StorageAdapter st = c.storage();
        if (st != null) st.remove("dtn-it-" + hex(it.key));
    }

    private void save() {
        lastSave = c.now();
        com.herabarman.meshnet.core.StorageAdapter st = c.storage();
        if (st == null) return;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bo);
            o.writeByte(1);
            prophet.write(o);
            rhythm.write(o);
            brain.write(o);
            st.put("dtn-state", bo.toByteArray());
        } catch (IOException ignored) { }
    }

    private void load() {
        com.herabarman.meshnet.core.StorageAdapter st = c.storage();
        if (st == null) return;
        byte[] s = st.get("dtn-state");
        if (s != null) {
            try {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(s));
                if (in.readByte() == 1) { prophet.read(in); rhythm.read(in); brain.read(in); }
            } catch (IOException ignored) { }
        }
        for (String k : st.keys("dtn-it-")) {
            byte[] v = st.get(k);
            try {
                ByteBuffer b = ByteBuffer.wrap(v);
                long key = b.getLong();
                int origin = b.getInt(), dst = b.getInt();
                long created = b.getLong();
                int copies = b.getShort();
                int prio = b.get() & 0xFF;
                boolean mine = b.get() != 0;
                byte[] cipher = new byte[b.remaining()];
                b.get(cipher);
                items.put(key, new Item(key, origin, dst, created, copies, prio, cipher, mine));
            } catch (RuntimeException e) {
                st.remove(k);
            }
        }
    }
}
