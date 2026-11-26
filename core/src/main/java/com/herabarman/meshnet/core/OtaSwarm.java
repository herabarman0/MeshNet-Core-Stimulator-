package com.herabarman.meshnet.core;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Offline app updates over the mesh. One phone has the new release (or is seeded with it); neighbours learn of it
 * from a periodic OFFER, pull it chunk by chunk, check every chunk against the signed manifest, and then offer it
 * to THEIR neighbours, so the update spreads from phone to phone with no internet and no store.
 *
 * Security: a manifest is only believed if it is for this app, newer than what runs here, and signed by a pinned publisher
 * key; every chunk carries a Merkle proof against the signed root, so a bad neighbour cannot poison the file (and
 * is ignored after a couple of bad chunks). The OS still has the last word on installing (on Android the APK must carry the
 * same signing key as the installed app, and the user confirms).
 *
 * Only whole-file holders serve large files (the chunk bitmap would not fit a radio frame); small files are also shared by partial
 * holders. Runs on one thread (the engine's), like the rest of the core.
 */
public final class OtaSwarm {
    private static final int OP_OFFER = 1, OP_REQ = 2, OP_CHUNK = 3;
    private static final int MAX_BITMAP_CHUNKS = 2048;

    public static final class Config {
        public String appId = "";
        /** versionCode of the app that is running now: only strictly newer releases are taken. */
        public int installedVersion;
        /** Public keys (as RecordAuth encodes them) whose releases are accepted. */
        public List<byte[]> trustedPublishers = new ArrayList<>();
        public long offerMs = 20_000;
        public long tickMs = 500;
        public long requestTimeoutMs = 4_000;
        /** Chunks in flight at once, and per neighbour. */
        public int window = 8, perPeer = 3;
        /** Chunks I will serve per second (keeps a phone from being drained by a greedy neighbour). */
        public int serveLimitPerSec = 20;
        /** Download as soon as a newer release is offered (otherwise call {@link #startDownload}). */
        public boolean autoDownload = true;
    }

    public interface Link {
        void send(int peer, byte[] message);
        List<Integer> peers();
    }

    public interface Listener {
        /** A newer, genuine release exists (before any download). */
        void onAvailable(OtaManifest m);
        void onProgress(OtaManifest m, int have, int total);
        /** The whole file arrived and is verified. */
        void onComplete(OtaManifest m, byte[] file);
    }

    /** Lets the app hold back while the battery is low, the user is on a call... */
    public interface Policy { boolean mayDownload(); }

    private static final class PeerState {
        boolean[] have;            // null = unknown
        boolean complete;
        int strikes, failures;
        long bannedUntil, lastOffer;
    }

    private final int self;
    private final Config cfg;
    private final Clock clock;
    private final Random rng;
    private final Link link;
    private final RecordAuth verifier;
    private Listener listener;
    private Policy policy;
    private StorageAdapter store;
    public int chunksRestored;

    private OtaManifest target;
    private byte[][] chunks, proofs;
    private boolean[] have;
    private int haveCount;
    private boolean downloading, running;
    private byte[] file;                  // set when complete
    private MerkleTree tree;              // set for seeded files
    private final Map<Integer, PeerState> peers = new HashMap<>();
    private final Map<Integer, long[]> inflight = new HashMap<>();   // chunk -> {peer, sentAt}
    private long serveWindowStart, lastOfferAt;
    private int offeredCount = -1;
    private final java.util.ArrayDeque<int[]> serveQueue = new java.util.ArrayDeque<>();   // {peer, chunk} waiting for the rate limit
    private int served;

    public int badChunks, chunksServed, chunksReceived, manifestsRejected;

    public OtaSwarm(int self, Config cfg, Clock clock, Random rng, Link link, RecordAuth verifier) {
        this.self = self;
        this.cfg = cfg;
        this.clock = clock;
        this.rng = rng;
        this.link = link;
        this.verifier = verifier;
    }

    public void setListener(Listener l) { this.listener = l; }
    public void setPolicy(Policy p) { this.policy = p; }

    /**
     * Keep a half-finished download across an app restart or reboot: the manifest and every verified chunk are saved, and
     * {@link #resume()} (called once, after setListener) picks them up again. Every restored chunk is verified against the Merkle root
     * again, so a damaged or tampered store cannot poison the download. Saved data is removed when the release completes.
     */
    public void setStorage(StorageAdapter s) { this.store = s; }

    /** @return true if an unfinished download was restored. */
    public boolean resume() {
        if (store == null || target != null) return false;
        byte[] mb = store.get(keyM());
        if (mb == null) return false;
        OtaManifest m = OtaManifest.decode(mb);
        if (m == null || !acceptable(m)) { clearStore(); return false; }
        adopt(m, false);
        for (String k : store.keys(keyC())) {
            int idx;
            try { idx = Integer.parseInt(k.substring(keyC().length())); } catch (NumberFormatException e) { store.remove(k); continue; }
            byte[] v = store.get(k);
            if (idx < 0 || idx >= have.length || v == null || v.length < 1) { store.remove(k); continue; }
            int plen = v[0] & 0xFF;
            if (v.length < 1 + plen) { store.remove(k); continue; }
            byte[] proof = Arrays.copyOfRange(v, 1, 1 + plen), data = Arrays.copyOfRange(v, 1 + plen, v.length);
            int expected = idx == have.length - 1 ? target.size - idx * target.chunkSize : target.chunkSize;
            if (data.length != expected || !MerkleTree.verify(target.root, idx, have.length, data, 0, data.length, proof)) { store.remove(k); continue; }
            if (!have[idx]) { chunks[idx] = data; proofs[idx] = proof; have[idx] = true; haveCount++; chunksRestored++; }
        }
        downloading = cfg.autoDownload;
        if (haveCount == have.length) finish();
        else if (listener != null) { listener.onAvailable(target); listener.onProgress(target, haveCount, have.length); }
        return true;
    }

    private String keyM() { return "ota.m." + cfg.appId; }
    private String keyC() { return "ota.c." + cfg.appId + "."; }

    private void clearStore() {
        if (store == null) return;
        store.remove(keyM());
        for (String k : store.keys(keyC())) store.remove(k);
    }
    public OtaManifest target() { return target; }
    public boolean complete() { return file != null; }
    public int haveChunks() { return haveCount; }
    public byte[] file() { return file == null ? null : file.clone(); }

    public void start() {
        if (running) return;
        running = true;
        clock.schedule(cfg.tickMs, new Runnable() { @Override public void run() { tick(); } });
    }

    public void stop() { running = false; }

    /** This device already has the release (it is the publisher's phone, or just installed it): serve it to others. */
    public boolean seed(OtaManifest m, byte[] data) {
        if (!acceptable(m) || data.length != m.size) return false;
        MerkleTree t = new MerkleTree(data, m.chunkSize);
        if (!Arrays.equals(t.root(), m.root)) return false;
        adopt(m, false);
        tree = t;
        file = data.clone();
        Arrays.fill(have, true);
        haveCount = have.length;
        offerAll();
        return true;
    }

    /** For autoDownload = false: the user agreed to fetch the offered release. */
    public void startDownload() { downloading = true; }

    public void onPeerUp(int peer) { sendOffer(peer); }

    public void onPeerDown(int peer) {
        peers.remove(peer);
        for (Iterator<Map.Entry<Integer, long[]>> it = inflight.entrySet().iterator(); it.hasNext(); )
            if (it.next().getValue()[0] == peer) it.remove();
    }

    // ---- receiving ----------------------------------------------------------------------

    public void onMessage(int peer, byte[] m) {
        if (m.length < 1) return;
        PeerState ps = peerState(peer);
        if (clock.nowMs() < ps.bannedUntil) return;
        try {
            switch (m[0] & 0xFF) {
                case OP_OFFER: onOffer(peer, ps, m); break;
                case OP_REQ: onRequest(peer, m); break;
                case OP_CHUNK: onChunk(peer, ps, m); break;
                default: break;
            }
        } catch (RuntimeException malformed) {
            strike(ps);
        }
    }

    private boolean acceptable(OtaManifest m) {
        if (m == null || !m.appId.equals(cfg.appId) || m.versionCode <= cfg.installedVersion) return false;
        for (byte[] k : cfg.trustedPublishers) if (Arrays.equals(k, m.publisherKey)) return m.signatureValid(verifier);
        return false;
    }

    private void onOffer(int peer, PeerState ps, byte[] m) {
        ByteBuffer b = ByteBuffer.wrap(m, 1, m.length - 1);
        byte[] mb = new byte[b.getShort() & 0xFFFF];
        b.get(mb);
        int flag = b.get() & 0xFF;
        byte[] bits = new byte[b.remaining()];
        b.get(bits);
        long now = clock.nowMs();
        if (now - ps.lastOffer < 1000 && ps.lastOffer != 0) return;        // an offer flood costs signature checks: slow it down
        ps.lastOffer = now;
        OtaManifest o;
        if (target != null && Arrays.equals(mb, target.encode())) o = target;           // already verified
        else {
            o = OtaManifest.decode(mb);
            if (!acceptable(o)) { manifestsRejected++; strike(ps); return; }
        }
        if (target == null || o.versionCode > target.versionCode) {
            adopt(o);
            if (listener != null) listener.onAvailable(o);
            downloading = cfg.autoDownload;
        } else if (!o.sameRelease(target)) {
            return;                                                         // an older release than the one I am after
        }
        if (flag == 0) { ps.complete = true; ps.have = null; }
        else if (flag == 1) {
            boolean[] h = new boolean[target.chunkCount()];
            for (int i = 0; i < h.length && i / 8 < bits.length; i++) h[i] = (bits[i / 8] >> (i % 8) & 1) != 0;
            ps.have = h;
            ps.complete = false;
        }
        schedule();
    }

    private void onRequest(int peer, byte[] m) {
        if (target == null || have == null) return;
        ByteBuffer b = ByteBuffer.wrap(m, 1, m.length - 1);
        byte[] root = new byte[MerkleTree.HASH];
        b.get(root);
        if (!Arrays.equals(root, target.root)) return;
        int n = b.get() & 0xFF;
        for (int i = 0; i < n; i++) {
            int idx = b.getInt();
            if (idx < 0 || idx >= have.length || !have[idx]) continue;
            if (serveQueue.size() < 512) serveQueue.add(new int[]{peer, idx});     // beyond that the requester's timeout retries it
        }
        drainServe();
    }

    /** Send queued chunks, at most serveLimitPerSec per second: a greedy neighbour waits, it is not dropped or punished. */
    private void drainServe() {
        long now = clock.nowMs();
        if (now - serveWindowStart >= 1000) { serveWindowStart = now; served = 0; }
        while (!serveQueue.isEmpty() && served < cfg.serveLimitPerSec) {
            int[] q = serveQueue.poll();
            if (target == null || have == null || q[1] >= have.length || !have[q[1]]) continue;
            served++;
            byte[] data = chunkData(q[1]), proof = proofFor(q[1]);
            ByteBuffer o = ByteBuffer.allocate(1 + MerkleTree.HASH + 4 + 2 + data.length + 1 + proof.length);
            o.put((byte) OP_CHUNK).put(target.root).putInt(q[1]).putShort((short) data.length).put(data).put((byte) proof.length).put(proof);
            link.send(q[0], o.array());
            chunksServed++;
        }
    }

    private void onChunk(int peer, PeerState ps, byte[] m) {
        if (target == null || file != null || !downloading) return;
        ByteBuffer b = ByteBuffer.wrap(m, 1, m.length - 1);
        byte[] root = new byte[MerkleTree.HASH];
        b.get(root);
        if (!Arrays.equals(root, target.root)) return;
        int idx = b.getInt();
        byte[] data = new byte[b.getShort() & 0xFFFF];
        b.get(data);
        byte[] proof = new byte[b.get() & 0xFF];
        b.get(proof);
        if (idx < 0 || idx >= have.length || have[idx]) { inflight.remove(idx); return; }
        long[] req = inflight.get(idx);
        if (req == null || req[0] != peer) return;                           // I did not ask this neighbour for it
        int expected = idx == have.length - 1 ? target.size - idx * target.chunkSize : target.chunkSize;
        if (data.length != expected || !MerkleTree.verify(target.root, idx, have.length, data, 0, data.length, proof)) {
            badChunks++;
            inflight.remove(idx);
            strike(ps);
            return;
        }
        inflight.remove(idx);
        chunks[idx] = data;
        proofs[idx] = proof;
        have[idx] = true;
        haveCount++;
        chunksReceived++;
        if (store != null) store.put(keyC() + idx, ByteBuffer.allocate(1 + proof.length + data.length).put((byte) proof.length).put(proof).put(data).array());
        ps.failures = 0;
        if (listener != null) listener.onProgress(target, haveCount, have.length);
        if (haveCount == have.length) finish(); else schedule();
    }

    private void finish() {
        byte[] out = new byte[target.size];
        for (int i = 0; i < chunks.length; i++) System.arraycopy(chunks[i], 0, out, i * target.chunkSize, chunks[i].length);
        file = out;
        downloading = false;
        clearStore();
        inflight.clear();
        if (listener != null) listener.onComplete(target, out.clone());
        offerAll();                                  // from now on I am a source too
    }

    // ---- sending ------------------------------------------------------------------------

    private void adopt(OtaManifest m) { adopt(m, true); }

    private void adopt(OtaManifest m, boolean persist) {
        if (persist && store != null && (target == null || target.versionCode != m.versionCode)) {
            for (String k : store.keys(keyC())) store.remove(k);                    // chunks of an older release are useless now
            store.put(keyM(), m.encode());
        }
        target = m;
        int n = m.chunkCount();
        chunks = new byte[n][];
        proofs = new byte[n][];
        have = new boolean[n];
        haveCount = 0;
        file = null;
        tree = null;
        inflight.clear();
        for (PeerState p : peers.values()) { p.have = null; p.complete = false; }
    }

    private byte[] chunkData(int idx) {
        if (chunks[idx] != null) return chunks[idx];
        int from = idx * target.chunkSize, to = Math.min(file.length, from + target.chunkSize);
        return Arrays.copyOfRange(file, from, to);
    }

    private byte[] proofFor(int idx) { return proofs[idx] != null ? proofs[idx] : tree.proof(idx); }

    private void sendOffer(int peer) {
        if (target == null || haveCount == 0) return;
        boolean complete = haveCount == have.length;
        if (!complete && have.length > MAX_BITMAP_CHUNKS) return;            // too big to describe: only whole-file holders offer
        byte[] mb = target.encode();
        byte[] bits = new byte[complete ? 0 : (have.length + 7) / 8];
        if (!complete) for (int i = 0; i < have.length; i++) if (have[i]) bits[i / 8] |= 1 << (i % 8);
        link.send(peer, ByteBuffer.allocate(1 + 2 + mb.length + 1 + bits.length).put((byte) OP_OFFER).putShort((short) mb.length).put(mb)
                .put((byte) (complete ? 0 : 1)).put(bits).array());
    }

    private void offerAll() {
        lastOfferAt = clock.nowMs();
        offeredCount = haveCount;
        for (int p : link.peers()) sendOffer(p);
    }

    private void tick() {
        if (!running) return;
        drainServe();
        long now = clock.nowMs();
        // tell neighbours what I have: regularly, and soon after it changed, so a partial holder can be pulled from while it downloads
        if (now / cfg.offerMs != (now - cfg.tickMs) / cfg.offerMs || (haveCount != offeredCount && now - lastOfferAt >= 1500)) offerAll();
        java.util.Set<Integer> timedOut = new java.util.HashSet<>();
        for (Iterator<Map.Entry<Integer, long[]>> it = inflight.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, long[]> e = it.next();
            if (now - e.getValue()[1] > cfg.requestTimeoutMs) { timedOut.add((int) e.getValue()[0]); it.remove(); }
        }
        for (int p : timedOut) {                     // a lost batch counts once, and only a few of them in a row mean a dead neighbour
            PeerState ps = peers.get(p);
            if (ps != null && ++ps.failures >= 4) { ps.bannedUntil = now + 10_000; ps.failures = 0; }
        }
        schedule();
        clock.schedule(cfg.tickMs, new Runnable() { @Override public void run() { tick(); } });
    }

    /** Ask neighbours for missing chunks, spreading the requests over those that have them. */
    private void schedule() {
        if (target == null || file != null || !downloading || (policy != null && !policy.mayDownload())) return;
        long now = clock.nowMs();
        Map<Integer, List<Integer>> batches = new HashMap<>();
        Map<Integer, Integer> load = new HashMap<>();
        for (long[] r : inflight.values()) { int p = (int) r[0]; load.put(p, (load.containsKey(p) ? load.get(p) : 0) + 1); }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < have.length; i++) if (!have[i] && !inflight.containsKey(i)) order.add(i);
        java.util.Collections.shuffle(order, rng);
        for (int idx : order) {
            if (inflight.size() >= cfg.window) break;
            int best = -1, bestLoad = Integer.MAX_VALUE;
            for (Map.Entry<Integer, PeerState> e : peers.entrySet()) {
                PeerState ps = e.getValue();
                if (now < ps.bannedUntil || !(ps.complete || (ps.have != null && ps.have[idx]))) continue;
                int l = load.containsKey(e.getKey()) ? load.get(e.getKey()) : 0;
                if (l < cfg.perPeer && l < bestLoad) { best = e.getKey(); bestLoad = l; }
            }
            if (best < 0) continue;
            inflight.put(idx, new long[]{best, now});
            load.put(best, bestLoad + 1);
            List<Integer> l = batches.get(best);
            if (l == null) { l = new ArrayList<>(); batches.put(best, l); }
            l.add(idx);
        }
        for (Map.Entry<Integer, List<Integer>> e : batches.entrySet()) {
            List<Integer> l = e.getValue();
            ByteBuffer b = ByteBuffer.allocate(1 + MerkleTree.HASH + 1 + 4 * l.size()).put((byte) OP_REQ).put(target.root).put((byte) l.size());
            for (int idx : l) b.putInt(idx);
            link.send(e.getKey(), b.array());
        }
    }

    private PeerState peerState(int peer) {
        PeerState p = peers.get(peer);
        if (p == null) { p = new PeerState(); peers.put(peer, p); }
        return p;
    }

    private void strike(PeerState ps) {
        if (++ps.strikes >= 3) { ps.bannedUntil = clock.nowMs() + 5 * 60_000; ps.strikes = 0; }
    }
}
