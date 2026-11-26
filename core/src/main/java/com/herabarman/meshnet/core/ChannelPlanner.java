package com.herabarman.meshnet.core;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Anti-jamming: moves each link to a free channel (BLE, Wi-Fi 2.4 GHz, 5 GHz, LoRa ...) when interference or a jammer makes
 * the current one unusable, and brings it back when the old channel recovers.
 *
 * Per LINK, not global: a jammer is local, so only the links it hits move and the rest of the mesh stays where it is. The two ends
 * agree through a short REQ/ACK exchange sent on several channels at once (the jammed one may swallow it), a tie-break
 * decides if both ask at the same moment, and a hold-down with exponential back-off stops flapping. How a jammer is told
 * from distance: a jammed channel loses packets while the signal is strong or the noise floor is up, or several links on
 * it fail together; a far-away neighbour is weak and alone. If a peer cannot be reached on any channel the two ends hop over
 * their common channels on a shared pseudo-random schedule until they meet again (rendezvous).
 *
 * Switch requests can be authenticated with a shared network key ({@link ControlAuth}) so an attacker cannot herd the
 * mesh onto a channel it is jamming. Channel ids are 0..255. Needs a radio that can listen on its channels (see the kit's MultiChannelRadio).
 */
public final class ChannelPlanner {
    private static final int OP_REQ = 1, OP_ACK = 2, OP_PROBE = 3, OP_PROBE_ACK = 4;

    public static final class Config {
        public int minSamples = 8;
        public double lossHigh = 0.5, lossGood = 0.2, alpha = 0.25;
        public double strongRssi = -80, noiseHigh = -82;
        public double switchMargin = 0.2, priorLoss = 0.3;
        public long holdDownMs = 20_000, maxHoldDownMs = 10 * 60_000, probeMs = 10_000, probeTimeoutMs = 1_000;
        public long reqTimeoutMs = 3_000, noiseTtlMs = 30_000;
        public long lostAfterMs = 15_000, hopSlotMs = 2_000, tickMs = 500;
    }

    /** Send a small control message on one specific channel (it may be jammed: the planner repeats it on others). */
    public interface Link { void sendOn(int channel, int peer, byte[] control); }

    public interface Listener {
        void onLinkChannel(int peer, int from, int to, String reason);
        void onLost(int peer);
        void onFound(int peer, int channel);
    }

    /** Authenticates control messages with a key shared by the whole mesh. */
    public interface ControlAuth {
        byte[] tag(byte[] data);                       // 8 bytes
        boolean check(byte[] data, byte[] tag);
    }

    private static final class Stat {
        double loss;
        long hist;                      // last 64 outcomes, 1 = failed
        boolean wasBad;                 // this channel once had to be left: only such channels are worth going back to
        int n;
        double rssi = Double.NaN;
        long holdUntil;
        int backoff;
        long probeSentAt = -1;
        int probesOk, probesTotal;
    }

    private static final class LinkState {
        int cur;
        int[] common;
        final Map<Integer, Stat> stats = new HashMap<>();
        long lastHeard, stableSince;
        boolean lost;
        int pendingTo = -1;
        int pendingEpoch;
        long pendingAt;
        int tries;
        int lastEpochSeen = -1;
        Stat stat(int ch) { Stat s = stats.get(ch); if (s == null) { s = new Stat(); stats.put(ch, s); } return s; }
    }

    private final int self;
    private final Config cfg;
    private final Clock clock;
    private final Link link;
    private final int[] mine;
    private final Map<Integer, Double> capacity = new HashMap<>();
    private final Map<Integer, double[]> noise = new HashMap<>();      // channel -> {dbm, measured at ms}
    private final Map<Integer, LinkState> links = new HashMap<>();
    private Listener listener;
    private ControlAuth auth;
    private boolean running;
    private int epochCounter;
    public int switches, rejected, rendezvous;

    /** @param channels my channels, @param relativeCapacity how good each is when clear (0..1), same order, e.g. BLE 0.2, Wi-Fi 2.4 0.7, 5 GHz 1.0 */
    public ChannelPlanner(int self, int[] channels, double[] relativeCapacity, Config cfg, Clock clock, Random rng, Link link) {
        this.self = self;
        this.mine = channels.clone();
        for (int i = 0; i < channels.length; i++) capacity.put(channels[i], relativeCapacity[i]);
        this.cfg = cfg;
        this.clock = clock;
        this.link = link;
    }

    public void setListener(Listener l) { this.listener = l; }
    public void setAuth(ControlAuth a) { this.auth = a; }

    public void start() {
        if (running) return;
        running = true;
        clock.schedule(cfg.tickMs, new Runnable() { @Override public void run() { tick(); } });
    }

    public void stop() { running = false; }

    /** A neighbour and the channels it supports. */
    public void addPeer(int peer, int[] peerChannels, int initialChannel) {
        LinkState st = new LinkState();
        List<Integer> c = new ArrayList<>();
        for (int ch : mine) for (int pc : peerChannels) if (ch == pc) c.add(ch);
        if (c.isEmpty()) return;
        st.common = new int[c.size()];
        for (int i = 0; i < st.common.length; i++) st.common[i] = c.get(i);
        st.cur = c.contains(initialChannel) ? initialChannel : best(st, -1);
        st.lastHeard = st.stableSince = clock.nowMs();
        links.put(peer, st);
    }

    public void removePeer(int peer) { links.remove(peer); }

    /** The channel to use for this peer right now (the rendezvous channel while the peer is lost). */
    public int channelFor(int peer) {
        LinkState st = links.get(peer);
        if (st == null) return -1;
        return st.lost ? hopChannel(peer, st) : st.cur;
    }

    public boolean isLost(int peer) { LinkState st = links.get(peer); return st != null && st.lost; }

    /** Radio noise floor on one of my channels (dBm), if the radio can measure it (a Wi-Fi scan, an energy detect). */
    public void onNoiseFloor(int channel, double dbm) { noise.put(channel, new double[]{dbm, clock.nowMs()}); }

    /** A noise reading counts for 30 s; an old one says nothing about now (the jammer may be gone). */
    private Double noiseOn(int ch) {
        double[] n = noise.get(ch);
        return n == null || clock.nowMs() - n[1] > cfg.noiseTtlMs ? null : (Double) n[0];
    }

    public void onRssi(int peer, int channel, double dbm) {
        LinkState st = links.get(peer);
        if (st == null) return;
        Stat s = st.stat(channel);
        s.rssi = Double.isNaN(s.rssi) ? dbm : 0.7 * s.rssi + 0.3 * dbm;
    }

    /** Outcome of a transmission to this peer on this channel (ACK received or not). */
    public void onTx(int peer, int channel, boolean ok) {
        LinkState st = links.get(peer);
        if (st == null) return;
        Stat s = st.stat(channel);
        if (s.n == 0) s.loss = cfg.priorLoss;
        s.loss = s.loss * (1 - cfg.alpha) + (ok ? 0 : 1) * cfg.alpha;
        s.n = Math.min(s.n + 1, 1000);
        s.hist = (s.hist << 1) | (ok ? 0 : 1);
        if (ok) heard(peer, st, channel);
        if (channel == st.cur && !st.lost) evaluate(peer, st);
    }

    /** Anything at all was received from this peer on this channel. */
    public void onReceive(int peer, int channel) {
        LinkState st = links.get(peer);
        if (st != null) heard(peer, st, channel);
    }

    private void heard(int peer, LinkState st, int channel) {
        st.lastHeard = clock.nowMs();
        if (st.lost) {
            st.lost = false;
            st.cur = channel;
            st.stableSince = clock.nowMs();
            if (listener != null) listener.onFound(peer, channel);
        }
    }

    // ---- deciding ------------------------------------------------------------------------------

    private boolean jamLike(int peer, LinkState st, int ch) {
        Stat s = st.stat(ch);
        if (s.n < cfg.minSamples || s.loss <= cfg.lossHigh || Long.bitCount(s.hist & 0x3FF) < 7) return false;   // 7 of the last 10 failed
        boolean strong = !Double.isNaN(s.rssi) && s.rssi >= cfg.strongRssi;
        Double nf = noiseOn(ch);
        boolean noisy = nf != null && nf >= cfg.noiseHigh;
        boolean together = false;
        for (Map.Entry<Integer, LinkState> e : links.entrySet()) {
            if (e.getKey() == peer || e.getValue().cur != ch) continue;
            Stat o = e.getValue().stat(ch);
            if (o.n >= 3 && o.loss > cfg.lossHigh) together = true;
        }
        // a weak, lonely link that fails is just far away; without any of these signs only a long run of failures counts
        return strong || noisy || together || (s.n >= 32 && Long.bitCount(s.hist & 0xFFFFFFFFL) >= 26);
    }

    private void evaluate(int peer, LinkState st) {
        if (st.pendingTo >= 0 || !jamLike(peer, st, st.cur)) return;
        int to = best(st, st.cur);
        if (to < 0) return;
        double curScore = score(st, st.cur), toScore = score(st, to);
        if (toScore < curScore + cfg.switchMargin) return;
        request(peer, st, to, 1);
    }

    private double score(LinkState st, int ch) {
        Stat s = st.stat(ch);
        double loss = s.n == 0 ? cfg.priorLoss : s.loss;
        Double nf = noiseOn(ch);
        if (nf != null && nf >= cfg.noiseHigh) loss = Math.min(1.0, loss + 0.3);
        return (1 - loss) * (0.5 + 0.5 * capacity.get(ch));
    }

    /** Best channel other than 'except' that is not in hold-down; -1 if none. */
    private int best(LinkState st, int except) {
        long now = clock.nowMs();
        int bestCh = -1;
        double bestScore = -1;
        for (int ch : st.common) {
            if (ch == except || st.stat(ch).holdUntil > now) continue;
            double sc = score(st, ch);
            if (sc > bestScore) { bestScore = sc; bestCh = ch; }
        }
        return bestCh;
    }

    // ---- switching -----------------------------------------------------------------------------

    private void request(int peer, LinkState st, int to, int reason) {
        st.pendingTo = to;
        st.pendingEpoch = ++epochCounter;
        st.pendingAt = clock.nowMs();
        sendEverywhere(peer, st, OP_REQ, st.pendingEpoch, to, reason);
    }

    private void sendEverywhere(int peer, LinkState st, int op, int epoch, int to, int reason) {
        byte[] body = ByteBuffer.allocate(8).put((byte) op).putInt(epoch).put((byte) to).put((byte) reason).array();
        byte[] msg = auth == null ? body : concat(body, auth.tag(withIds(body, self, peer)));
        for (int ch : st.common) link.sendOn(ch, peer, msg);              // the old channel may be jammed: say it on all of them
    }

    private void adopt(int peer, LinkState st, int to, String why) {
        int from = st.cur;
        long now = clock.nowMs();
        Stat old = st.stat(from);
        if (now - st.stableSince > 5 * 60_000) old.backoff = 0;
        old.holdUntil = now + Math.min(cfg.maxHoldDownMs, cfg.holdDownMs << Math.min(old.backoff, 16));
        old.backoff++;
        old.probesOk = old.probesTotal = 0;
        old.wasBad = true;
        st.cur = to;
        st.pendingTo = -1;
        st.stableSince = now;
        st.stat(to).n = Math.min(st.stat(to).n, 2);                       // judge the new channel on new evidence
        st.stat(to).loss = Math.min(st.stat(to).loss, cfg.priorLoss);
        switches++;
        if (listener != null) listener.onLinkChannel(peer, from, to, why);
    }

    /** A control message from a peer, received on a channel. */
    public void onControl(int peer, int channel, byte[] m) {
        LinkState st = links.get(peer);
        if (st == null || m.length < 8) return;
        byte[] body = Arrays.copyOf(m, 8);
        if (auth != null) {
            byte[] tag = Arrays.copyOfRange(m, 8, m.length);
            if (!auth.check(withIds(body, peer, self), tag)) { rejected++; return; }
        }
        heard(peer, st, channel);
        ByteBuffer b = ByteBuffer.wrap(body);
        int op = b.get() & 0xFF, epoch = b.getInt(), ch = b.get() & 0xFF, reason = b.get();
        boolean known = false;
        for (int c : st.common) if (c == ch) known = true;
        if (op == OP_PROBE) { link.sendOn(channel, peer, probe(OP_PROBE_ACK, peer)); return; }
        if (op == OP_PROBE_ACK) {
            Stat s = st.stat(channel);
            if (s.probeSentAt >= 0) { s.probeSentAt = -1; probeResult(peer, st, channel, true); }
            return;
        }
        if (!known) return;
        if (op == OP_REQ) {
            if (st.pendingTo >= 0 && self < peer) return;                         // both asked at once: the lower id wins
            if (epoch < st.lastEpochSeen) return;                                 // an old request
            st.lastEpochSeen = epoch;
            if (st.cur != ch) adopt(peer, st, ch, reason == 1 ? "peer requested (interference)" : "peer requested");
            st.pendingTo = -1;
            sendEverywhere(peer, st, OP_ACK, epoch, ch, 0);
        } else if (op == OP_ACK) {
            if (st.pendingTo == ch && st.pendingEpoch == epoch) adopt(peer, st, ch, "interference on " + st.cur);
        }
    }

    private byte[] probe(int op, int peer) {
        byte[] body = ByteBuffer.allocate(8).put((byte) op).putInt(0).put((byte) 0).put((byte) 0).array();
        return auth == null ? body : concat(body, auth.tag(withIds(body, self, peer)));
    }

    // ---- periodic: timeouts, probing, rendezvous ------------------------------------------------

    private void tick() {
        if (!running) return;
        long now = clock.nowMs();
        for (Map.Entry<Integer, LinkState> e : new ArrayList<>(links.entrySet())) {
            int peer = e.getKey();
            LinkState st = e.getValue();
            // a request nobody answered: that channel does not work for the peer either, try the next best
            if (st.pendingTo >= 0 && now - st.pendingAt > cfg.reqTimeoutMs) {
                Stat bad = st.stat(st.pendingTo);
                bad.holdUntil = now + cfg.holdDownMs;
                st.pendingTo = -1;
                if (++st.tries < 3) { int next = best(st, st.cur); if (next >= 0) request(peer, st, next, 1); }
                else st.tries = 0;
            }
            // lost: nothing heard for a long time while the link kept failing
            if (!st.lost && now - st.lastHeard > cfg.lostAfterMs) {
                st.lost = true;
                st.pendingTo = -1;
                rendezvous++;
                if (listener != null) listener.onLost(peer);
            }
            if (st.lost) link.sendOn(hopChannel(peer, st), peer, probe(OP_PROBE, peer));      // knock on the rendezvous channel
            // probe channels that are in hold-down or unused, to learn when they recover; take the better one back
            for (int ch : st.common) {
                Stat s = st.stat(ch);
                if (s.probeSentAt >= 0 && now - s.probeSentAt > cfg.probeTimeoutMs) { s.probeSentAt = -1; probeResult(peer, st, ch, false); }
                if (ch == st.cur || st.lost || now < s.holdUntil || s.probeSentAt >= 0) continue;
                if (now / cfg.probeMs != (now - cfg.tickMs) / cfg.probeMs) {            // once per probe interval
                    s.probeSentAt = now;
                    link.sendOn(ch, peer, probe(OP_PROBE, peer));
                }
            }
        }
        clock.schedule(cfg.tickMs, new Runnable() { @Override public void run() { tick(); } });
    }

    private void probeResult(int peer, LinkState st, int ch, boolean ok) {
        Stat s = st.stat(ch);
        s.probesTotal++;
        if (ok) s.probesOk++;
        if (s.n == 0) s.loss = cfg.priorLoss;
        s.loss = s.loss * 0.5 + (ok ? 0 : 1) * 0.5;               // a probe is fresh evidence after a hold-down: weigh it heavily
        s.n = Math.min(s.n + 1, 1000);
        // an old, recovered channel that is clearly better than where we are: go back (hysteresis: several good probes in a row)
        if (ok && s.wasBad && s.probesOk >= 3 && st.pendingTo < 0 && !st.lost && ch != st.cur && s.loss < cfg.lossGood
                && score(st, ch) > score(st, st.cur) + 0.05) request(peer, st, ch, 0);
    }

    /** Both ends compute the same hop for the same time slot. */
    private int hopChannel(int peer, LinkState st) {
        long slot = clock.nowMs() / cfg.hopSlotMs;
        long h = (Math.min(self, peer) * 0x9E3779B97F4A7C15L) ^ (Math.max(self, peer) * 0xC2B2AE3D27D4EB4FL) ^ (slot * 0x165667B19E3779F9L);
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return st.common[(int) ((h & 0x7FFFFFFF) % st.common.length)];
    }

    private static byte[] withIds(byte[] body, int from, int to) {
        return ByteBuffer.allocate(body.length + 8).put(body).putInt(from).putInt(to).array();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] o = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }
}
