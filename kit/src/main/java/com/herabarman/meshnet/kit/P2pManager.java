package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.Clock;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Direct phone-to-phone UDP paths through NATs, so internet traffic between two online nodes does not have to
 * go through the central server. The server stays as the introducer (it carries the small signals) and as the
 * fallback relay: if no direct path can be made, nothing changes for the app. This is the job a STUN + TURN setup
 * does, with the existing bridge in the TURN role.
 *
 * How a path is made (UDP hole punching):
 *  1. STUN: ask a public server what address and port our NAT gave this socket (the "public candidate").
 *  2. Signal: through the server, A sends B an OFFER (a random token + its candidates); B answers the same way.
 *  3. Both sides now send PUNCH datagrams to each other's candidates at the same time. Each outgoing datagram makes
 *     its own NAT expect a reply from that address, so the other side's datagrams get in.
 *  4. A side that gets a PUNCH answers PUNCH_ACK to where it came from; a side that gets a PUNCH_ACK has a path.
 *  5. Every datagram carries a session id that only the two sides know (the two tokens XORed): anything else is dropped.
 *  6. A PING every 15 s keeps the NAT mapping alive; no sign of the peer for 45 s closes the path.
 *
 * What it cannot do: if both NATs are symmetric (a new public port per destination) or one is symmetric and the
 * other port-restricted, no punching works. The attempt then fails after about 6 s and the traffic keeps using the
 * server relay; the next attempt waits (1, 2, 4 ... 15 minutes).
 * Limits of this version: IPv4 only; the session id keeps strangers out but does not prove who the peer is (the
 * packets inside are end-to-end encrypted either way, so a stranger would only get ciphertext and could cause a
 * failed path, not read anything).
 * Threading: all state is guarded by this object; callbacks to the Sink run under that lock, so keep the Sink fast.
 */
public final class P2pManager {
    public enum State { PUNCHING, OPEN, FAILED }

    public enum NatType {
        /** Not known yet. */
        UNKNOWN,
        /** The same public port for every destination (full, restricted or port-restricted cone): punching works with most peers. */
        CONE,
        /** A new public port per destination: punching works only against the most open peers. */
        SYMMETRIC
    }

    public interface Signal {
        /** Send a small payload to this node through the server. Must not block. */
        void send(int toNode, byte[] payload);
    }

    public interface Sink {
        /** A core packet arrived over a direct path. */
        void onPacket(byte[] corePacket);
    }

    public interface Listener {
        void onState(int peer, State state);
    }

    public static final byte SIGNAL_MAGIC = (byte) 0xFE;
    static final byte MAGIC = (byte) 0xA7;
    static final int T_PUNCH = 1, T_PUNCH_ACK = 2, T_DATA = 3, T_PING = 4, T_PONG = 5;
    static final int SID = 8;
    static final int MAX_PART = 1100;

    public long punchIntervalMs = 150;
    public long punchTimeoutMs = 6_000;
    public long pingIntervalMs = 15_000;
    public long deadAfterMs = 45_000;
    public long retryBaseMs = 60_000;
    public long retryMaxMs = 900_000;
    public long offerResendMs = 1_500;

    private static final class Session {
        final int peer;
        byte[] localToken;
        byte[] remoteToken;
        byte[] sid;
        List<InetSocketAddress> remote = new ArrayList<>();
        InetSocketAddress peerAddr;
        State state = State.PUNCHING;
        long startedAt;
        long lastRx;
        long retryAt;
        int failures;
        int generation;
        int offersSent;
        long lastPing;
        int nextMsg;
        final Map<Integer, Frag> frags = new HashMap<>();

        Session(int peer) { this.peer = peer; }
    }

    private static final class Frag {
        final byte[][] parts;
        int got;
        final long at;

        Frag(int count, long at) { parts = new byte[count][]; this.at = at; }
    }

    private final int self;
    private final UdpPort port;
    private final Clock clock;
    private final Random rng;
    private final List<InetSocketAddress> stunServers;
    private final Signal signal;
    private final Sink sink;
    private final Map<Integer, Session> sessions = new HashMap<>();
    private final Map<String, Session> bySid = new HashMap<>();
    private final Map<String, Integer> stunTx = new HashMap<>();             // txId hex -> server index
    private final Map<Integer, InetSocketAddress> mapped = new HashMap<>();   // server index -> what it saw
    private Listener listener;
    private volatile boolean running;
    private long sentP2p, recvP2p, opened, failedCount, punchesSent;

    public P2pManager(int selfNode, UdpPort port, Clock clock, Random rng, List<InetSocketAddress> stunServers, Signal signal, Sink sink) {
        this.self = selfNode;
        this.port = port;
        this.clock = clock;
        this.rng = rng;
        this.stunServers = stunServers;
        this.signal = signal;
        this.sink = sink;
    }

    public synchronized void setListener(Listener l) { this.listener = l; }

    public synchronized void start() {
        if (running) return;
        running = true;
        port.setReceiver(new UdpPort.Receiver() {
            @Override public void onDatagram(InetSocketAddress from, byte[] data) { received(from, data); }
        });
        for (int i = 0; i < stunServers.size(); i++) askStun(i, 0);
    }

    public synchronized void stop() {
        running = false;
        for (Session s : sessions.values()) {
            if (s.state == State.OPEN && s.peerAddr != null && s.sid != null) port.send(s.peerAddr, frame(s.sid, T_PING, new byte[0]));
        }
        port.close();
    }

    // ---- what the app and the server link use ------------------------------------------------------------------------

    /** The addresses the outside world sees this socket at, as far as STUN has told us. */
    public synchronized List<InetSocketAddress> publicCandidates() {
        Set<InetSocketAddress> s = new LinkedHashSet<>();
        for (int i = 0; i < stunServers.size(); i++) {
            InetSocketAddress m = mapped.get(i);
            if (m != null) s.add(m);
        }
        return new ArrayList<>(s);
    }

    public synchronized NatType natType() {
        InetSocketAddress first = null;
        int answers = 0;
        for (int i = 0; i < stunServers.size(); i++) {
            InetSocketAddress m = mapped.get(i);
            if (m == null) continue;
            answers++;
            if (first == null) first = m;
            else if (m.getPort() != first.getPort()) return NatType.SYMMETRIC;
        }
        return answers >= 2 ? NatType.CONE : NatType.UNKNOWN;
    }

    public synchronized State stateOf(int peer) {
        Session s = sessions.get(peer);
        return s == null ? null : s.state;
    }

    public synchronized boolean isOpen(int peer) {
        Session s = sessions.get(peer);
        return s != null && s.state == State.OPEN;
    }

    public synchronized long sentOverP2p() { return sentP2p; }
    public synchronized long receivedOverP2p() { return recvP2p; }
    public synchronized long pathsOpened() { return opened; }
    public synchronized long pathsFailed() { return failedCount; }

    /**
     * Send a core packet over the direct path to this node.
     * @return true if it was sent directly; false if there is no open path (use the server relay)
     */
    public synchronized boolean send(int peer, byte[] packet) {
        Session s = sessions.get(peer);
        if (s == null || s.state != State.OPEN || s.peerAddr == null || packet.length > 255 * MAX_PART) return false;
        int count = (packet.length + MAX_PART - 1) / MAX_PART;
        int msg = s.nextMsg++ & 0xFFFF;
        for (int i = 0; i < Math.max(1, count); i++) {
            int off = i * MAX_PART, len = Math.min(MAX_PART, packet.length - off);
            ByteBuffer b = ByteBuffer.allocate(4 + len);
            b.putShort((short) msg).put((byte) i).put((byte) Math.max(1, count));
            b.put(packet, off, len);
            port.send(s.peerAddr, frame(s.sid, T_DATA, b.array()));
        }
        sentP2p++;
        return true;
    }

    /** Try to make a direct path to this node (no-op if one exists or is being made, or the last try failed recently). */
    public synchronized void ensure(int peer) {
        if (!running || peer == self) return;
        long now = clock.nowMs();
        Session s = sessions.get(peer);
        if (s != null) {
            if (s.state == State.OPEN || s.state == State.PUNCHING) return;
            if (now < s.retryAt) return;
        }
        if (publicCandidates().isEmpty() && port.localCandidates().isEmpty()) return;   // nothing to offer yet
        if (s == null) {
            s = new Session(peer);
            sessions.put(peer, s);
        }
        resetAttempt(s, now);
        s.localToken = token();
        offer(s, 1);
        final Session target = s;
        final int gen = s.generation;
        clock.schedule(offerResendMs, new Runnable() { @Override public void run() { resendOffer(target, gen); } });
        changed(s, State.PUNCHING);
        final long timeout = punchTimeoutMs;
        clock.schedule(timeout, new Runnable() { @Override public void run() { punchTimeout(target, gen); } });
    }

    // ---- signals ----------------------------------------------------------------------------------------------------

    private List<InetSocketAddress> myCandidates() {
        Set<InetSocketAddress> all = new LinkedHashSet<>(publicCandidates());
        all.addAll(port.localCandidates());
        return new ArrayList<>(all);
    }

    private void offer(Session s, int kind) {
        List<InetSocketAddress> cands = myCandidates();
        List<byte[]> enc = new ArrayList<>();
        for (InetSocketAddress a : cands) {
            byte[] ip = a.getAddress().getAddress();
            if (ip.length == 4) enc.add(ByteBuffer.allocate(6).put(ip).putShort((short) a.getPort()).array());
        }
        ByteBuffer b = ByteBuffer.allocate(2 + 4 + SID + 1 + enc.size() * 6);
        b.put(SIGNAL_MAGIC).put((byte) kind).putInt(self).put(s.localToken).put((byte) enc.size());
        for (byte[] e : enc) b.put(e);
        signal.send(s.peer, b.array());
        s.offersSent++;
    }

    private void resendOffer(Session s, int gen) {
        synchronized (this) {
            if (!running || s.generation != gen || s.remoteToken != null || s.state != State.PUNCHING) return;
            if (s.offersSent >= 4) return;
            offer(s, 1);
            final int g = gen;
            clock.schedule(offerResendMs, new Runnable() { @Override public void run() { resendOffer(s, g); } });
        }
    }

    /** A signal from the server: payload starts with SIGNAL_MAGIC. */
    public synchronized void onSignal(byte[] p) {
        if (!running || p.length < 2 + 4 + SID + 1 || p[0] != SIGNAL_MAGIC) return;
        ByteBuffer b = ByteBuffer.wrap(p);
        b.get();
        int kind = b.get() & 0xFF;
        int from = b.getInt();
        byte[] tok = new byte[SID];
        b.get(tok);
        int n = b.get() & 0xFF;
        if ((kind != 1 && kind != 2) || from <= 0 || from == self || b.remaining() < n * 6) return;
        List<InetSocketAddress> cands = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            byte[] ip = new byte[4];
            b.get(ip);
            int pt = b.getShort() & 0xFFFF;
            try {
                if (pt != 0) cands.add(new InetSocketAddress(InetAddress.getByAddress(ip), pt));
            } catch (UnknownHostException ignored) {
            }
        }
        long now = clock.nowMs();
        Session s = sessions.get(from);
        boolean fresh = false;
        if (s == null) {
            s = new Session(from);
            sessions.put(from, s);
            fresh = true;
        } else if (s.remoteToken != null && !java.util.Arrays.equals(s.remoteToken, tok)) {
            if (s.state == State.OPEN) unregisterSid(s);              // the peer started over (restart, new NAT binding)
            resetAttempt(s, now);
            fresh = true;
        } else if (s.state == State.FAILED && now >= s.retryAt) {
            resetAttempt(s, now);
            fresh = true;
        } else if (s.state == State.OPEN && s.remoteToken != null) {
            if (kind == 1) { offer(s, 2); }                            // our answer was lost: say it again
            return;
        }
        if (s.state == State.FAILED && !fresh) return;
        if (s.localToken == null) s.localToken = token();
        s.remoteToken = tok;
        s.remote = cands;
        s.sid = new byte[SID];
        for (int i = 0; i < SID; i++) s.sid[i] = (byte) (s.localToken[i] ^ tok[i]);
        registerSid(s);
        if (kind == 1) offer(s, 2);                                    // answer with our token and candidates
        if (s.state != State.PUNCHING) {
            s.state = State.PUNCHING;
            changed(s, State.PUNCHING);
        }
        if (fresh || s.startedAt == 0) {
            s.startedAt = now;
            final Session target = s;
            final int gen = s.generation;
            clock.schedule(punchTimeoutMs, new Runnable() { @Override public void run() { punchTimeout(target, gen); } });
        }
        punchRound(s, s.generation);
    }

    // ---- punching ---------------------------------------------------------------------------------------------------

    private void punchRound(final Session s, final int gen) {
        if (!running || s.generation != gen || s.state != State.PUNCHING || s.sid == null) return;
        byte[] punch = frame(s.sid, T_PUNCH, new byte[0]);
        for (InetSocketAddress a : s.remote) {
            port.send(a, punch);
            punchesSent++;
        }
        if (s.peerAddr != null && !s.remote.contains(s.peerAddr)) port.send(s.peerAddr, punch);   // where it really came from
        clock.schedule(punchIntervalMs, new Runnable() {
            @Override public void run() {
                synchronized (P2pManager.this) { punchRound(s, gen); }
            }
        });
    }

    private void punchTimeout(Session s, int gen) {
        synchronized (this) {
            if (!running || s.generation != gen || s.state != State.PUNCHING) return;
            s.failures++;
            s.retryAt = clock.nowMs() + Math.min(retryMaxMs, retryBaseMs << Math.min(10, s.failures - 1));
            s.generation++;
            unregisterSid(s);
            s.localToken = null;
            s.remoteToken = null;
            s.sid = null;
            failedCount++;
            changed(s, State.FAILED);
        }
    }

    private void resetAttempt(Session s, long now) {
        unregisterSid(s);
        s.generation++;
        s.localToken = null;
        s.remoteToken = null;
        s.sid = null;
        s.remote = new ArrayList<>();
        s.peerAddr = null;
        s.state = State.PUNCHING;
        s.startedAt = now;
        s.lastRx = now;
        s.offersSent = 0;
        s.frags.clear();
    }

    private void registerSid(Session s) { bySid.put(hex(s.sid), s); }

    private void unregisterSid(Session s) { if (s.sid != null) bySid.remove(hex(s.sid)); }

    private void changed(Session s, State st) {
        s.state = st;
        if (st == State.OPEN) opened++;
        if (listener != null) listener.onState(s.peer, st);
    }

    // ---- datagrams --------------------------------------------------------------------------------------------------

    private static byte[] frame(byte[] sid, int type, byte[] body) {
        ByteBuffer b = ByteBuffer.allocate(2 + SID + body.length);
        b.put(MAGIC).put((byte) type).put(sid).put(body);
        return b.array();
    }

    private synchronized void received(InetSocketAddress from, byte[] d) {
        if (!running) return;
        if (StunClient.looksLikeStun(d)) {
            stunAnswer(d);
            return;
        }
        if (d.length < 2 + SID || d[0] != MAGIC) return;
        byte[] sid = new byte[SID];
        System.arraycopy(d, 2, sid, 0, SID);
        Session s = bySid.get(hex(sid));
        if (s == null || s.sid == null) return;                         // not a session we know: a stranger, drop it
        int type = d[1] & 0xFF;
        long now = clock.nowMs();
        switch (type) {
            case T_PUNCH:
                if (s.state == State.FAILED) return;
                s.lastRx = now;
                if (s.state == State.PUNCHING) s.peerAddr = from;       // remember where it really came from
                port.send(from, frame(s.sid, T_PUNCH_ACK, new byte[0]));
                break;
            case T_PUNCH_ACK:
                s.lastRx = now;
                if (s.state == State.PUNCHING) {
                    s.peerAddr = from;
                    s.failures = 0;
                    s.generation++;                                     // stops the punch loop and the timeout of this attempt
                    changed(s, State.OPEN);
                    schedulePing(s);
                } else if (s.state == State.OPEN) {
                    s.peerAddr = from;
                }
                break;
            case T_DATA:
                if (s.state != State.OPEN && s.state != State.PUNCHING) return;
                if (s.state == State.PUNCHING) {                        // data before the ACK: the path works
                    s.peerAddr = from;
                    s.failures = 0;
                    s.generation++;
                    changed(s, State.OPEN);
                    schedulePing(s);
                }
                s.lastRx = now;
                s.peerAddr = from;
                data(s, d, now);
                break;
            case T_PING:
                s.lastRx = now;
                if (s.state == State.OPEN) {
                    s.peerAddr = from;
                    port.send(from, frame(s.sid, T_PONG, new byte[0]));
                }
                break;
            case T_PONG:
                s.lastRx = now;
                break;
            default:
                break;
        }
    }

    private void data(Session s, byte[] d, long now) {
        if (d.length < 2 + SID + 4) return;
        ByteBuffer b = ByteBuffer.wrap(d, 2 + SID, d.length - 2 - SID);
        int msg = b.getShort() & 0xFFFF;
        int idx = b.get() & 0xFF;
        int count = b.get() & 0xFF;
        if (count == 0 || idx >= count) return;
        byte[] part = new byte[b.remaining()];
        b.get(part);
        if (count == 1) {
            recvP2p++;
            sink.onPacket(part);
            return;
        }
        for (Iterator<Frag> it = s.frags.values().iterator(); it.hasNext(); ) if (now - it.next().at > 5_000) it.remove();
        if (s.frags.size() >= 16 && !s.frags.containsKey(msg)) return;   // too many half-built packets: drop
        Frag f = s.frags.get(msg);
        if (f == null || f.parts.length != count) {
            f = new Frag(count, now);
            s.frags.put(msg, f);
        }
        if (f.parts[idx] == null) {
            f.parts[idx] = part;
            f.got++;
        }
        if (f.got == count) {
            s.frags.remove(msg);
            int total = 0;
            for (byte[] p : f.parts) total += p.length;
            byte[] all = new byte[total];
            int o = 0;
            for (byte[] p : f.parts) { System.arraycopy(p, 0, all, o, p.length); o += p.length; }
            recvP2p++;
            sink.onPacket(all);
        }
    }

    /** Looks at an open path often enough to notice that it died, and sends a PING as often as pingIntervalMs says. */
    private void schedulePing(final Session s) {
        final int gen = s.generation;
        long every = Math.max(1_000, Math.min(pingIntervalMs, deadAfterMs / 3));
        clock.schedule(every, new Runnable() { @Override public void run() { ping(s, gen); } });
    }

    private void ping(Session s, int gen) {
        synchronized (this) {
            if (!running || s.generation != gen || s.state != State.OPEN) return;
            long now = clock.nowMs();
            if (now - s.lastRx > deadAfterMs) {                          // the peer is gone, or the NAT closed the path
                s.failures++;
                s.retryAt = now + 30_000;
                s.generation++;
                unregisterSid(s);
                s.sid = null;
                s.localToken = null;
                s.remoteToken = null;
                s.peerAddr = null;
                failedCount++;
                changed(s, State.FAILED);
                return;
            }
            if (s.peerAddr != null && now - s.lastPing >= pingIntervalMs - 1) {
                port.send(s.peerAddr, frame(s.sid, T_PING, new byte[0]));
                s.lastPing = now;
            }
            schedulePing(s);
        }
    }

    // ---- STUN -------------------------------------------------------------------------------------------------------

    private void askStun(final int server, final int attempt) {
        if (!running || mapped.containsKey(server) || attempt >= 4) return;
        byte[] tx = new byte[12];
        rng.nextBytes(tx);
        stunTx.put(hex(tx), server);
        port.send(stunServers.get(server), StunClient.bindingRequest(tx));
        clock.schedule(500L << attempt, new Runnable() {
            @Override public void run() {
                synchronized (P2pManager.this) { askStun(server, attempt + 1); }
            }
        });
    }

    private void stunAnswer(byte[] d) {
        byte[] tx = StunClient.transactionId(d);
        if (tx == null) return;
        Integer server = stunTx.get(hex(tx));
        if (server == null) return;
        InetSocketAddress a = StunClient.parseBindingResponse(d, tx);
        if (a != null) mapped.put(server, a);
    }

    private byte[] token() {
        byte[] t = new byte[SID];
        rng.nextBytes(t);
        return t;
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return s.toString();
    }
}
