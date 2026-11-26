package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.StorageAdapter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Offline group chats and public channels that sync on their own (see GroupState for the CRDT).
 *
 *  Private group: a founder adds and removes members (needs their key: exchange a message first). Ops are signed by
 *  their author and travel end-to-end encrypted to each member (reliable, retried, DTN-friendly); members that meet
 *  compare version vectors (HAVE) and send each other what is missing (OPS), so a member that was away catches up.
 *  Practical size: tens of members (every op is sent to every member).
 *
 *  Open channel: no membership, anyone with the channel id can read and post; posts are signed (identity = key).
 *  Neighbours exchange them over link-level broadcast, and phones that meet compare vectors, so a post spreads
 *  through the mesh and crosses gaps with whoever walks between. Anybody can post: moderation is up to the app.
 *
 * Known limits: an author who signs two different ops with the same sequence number (a forked log) can split
 * replicas; this is not detected. Messages are limited to 1000 bytes. Own ops are delivered to the listener too.
 */
public final class Groups {
    public static final int EXT_GROUP = 0x10;
    public static final int BCAST_CHANNEL = 0x04;
    static final int SUB_INVITE = 1, SUB_HAVE = 2, SUB_OPS = 3;
    static final int OPS_CHUNK_BYTES = 20_000, OPS_CHUNK_COUNT = 60;

    public interface Listener {
        void onMessage(String groupId, String authorUser, long timestamp, String text, String opId);
        void onReaction(String groupId, String opId, String emoji, String reactorUser, boolean on);
        void onMembers(String groupId, Set<String> members);
    }

    public static final class Message {
        public final String opId, authorUser, text;
        public final long timestamp;
        public final Map<String, Set<String>> reactions;
        Message(String opId, String author, long ts, String text, Map<String, Set<String>> r) {
            this.opId = opId; this.authorUser = author; this.timestamp = ts; this.text = text; this.reactions = r;
        }
    }

    private static final class G {
        final GroupState st;
        String name;
        final Map<Integer, Integer> sentVec = new HashMap<>();     // neighbour -> hash of the vector I last told it
        G(GroupState st, String name) { this.st = st; this.name = name; }
    }

    private final MeshClient c;
    private final Listener listener;
    private final long syncMs;
    private final Object lock = new Object();
    private final Map<String, G> groups = new LinkedHashMap<>();
    private final SecureRandom rnd = new SecureRandom();
    private final GroupState.Keys keys;

    /** Create before MeshClient.start(). syncMs: how often neighbours are compared (default 20 s). */
    public Groups(final MeshClient c, Listener listener, long syncMs) {
        this.c = c;
        this.listener = listener;
        this.syncMs = syncMs;
        this.keys = new GroupState.Keys() {
            @Override public byte[] keyOf(int node) { return node == c.selfNode() ? c.identity().publicRaw() : c.keyDirectory().get(node); }
        };
        c.registerExt(EXT_GROUP, new MeshClient.ExtHandler() {
            @Override public void onExt(int from, String user, byte[] p) { onMessage(from, p, false); }
        });
        c.registerBroadcast(BCAST_CHANNEL, new MeshClient.BroadcastHandler() {
            @Override public void onBroadcast(int from, byte[] data) { onMessage(from, data, true); }
        });
        c.addNeighborObserver(new MeshClient.NeighborObserver() {
            @Override public void onNeighbor(int node, boolean up) { if (up) neighborUp(node); }
        });
        c.post(new Runnable() { @Override public void run() { load(); tick(); } });
    }

    public Groups(MeshClient c, Listener listener) { this(c, listener, 20_000); }

    // ------------------------------------------------------------------ public API

    public String createGroup(String name) { return create(name, false); }

    public String createChannel(String name) { return create(name, true); }

    private String create(String name, boolean open) {
        final byte[] gid = new byte[8];
        rnd.nextBytes(gid);
        final String id = GroupState.hex(gid);
        synchronized (lock) {
            G g = new G(new GroupState(gid, open, open ? 0 : c.selfNode(), keys), name);
            groups.put(id, g);
            saveMeta(id, g);
        }
        return id;
    }

    /** Subscribe to an open channel whose id you were given. */
    public void joinChannel(String groupId, String name) {
        synchronized (lock) {
            if (groups.containsKey(groupId)) return;
            byte[] gid = unhex(groupId);
            G g = new G(new GroupState(gid, true, 0, keys), name);
            groups.put(groupId, g);
            saveMeta(groupId, g);
        }
        c.post(new Runnable() { @Override public void run() { for (int nb : c.neighbors()) neighborUp(nb); } });
    }

    /** Founder only. The member's key must be known (you have exchanged a message with them). */
    public void addMember(final String groupId, String user) {
        final int node = c.idMap().nodeOf(user);
        final byte[] pub = c.keyDirectory().get(node);
        if (pub == null) throw new IllegalStateException("no key for " + user + " yet: exchange a message first");
        final byte[] body = ByteBuffer.allocate(69).putInt(node).put(pub).array();
        c.post(new Runnable() {
            @Override public void run() {
                synchronized (lock) {
                    G g = groups.get(groupId);
                    if (g == null || g.st.open || g.st.founder != c.selfNode()) return;
                    localOp(groupId, g, GroupState.ADD, body);
                    byte[] name = g.name.getBytes(StandardCharsets.UTF_8);
                    byte[] inv = ByteBuffer.allocate(1 + 8 + 4 + 1 + name.length).put((byte) SUB_INVITE).put(g.st.gid)
                            .putInt(g.st.founder).put((byte) 0).put(name).array();
                    c.sendExtNode(node, EXT_GROUP, inv, MeshClient.PRIORITY_NORMAL, null);
                }
            }
        });
    }

    public void removeMember(final String groupId, String user) {
        final int node = c.idMap().nodeOf(user);
        c.post(new Runnable() {
            @Override public void run() {
                synchronized (lock) {
                    G g = groups.get(groupId);
                    if (g == null || g.st.open || g.st.founder != c.selfNode()) return;
                    localOp(groupId, g, GroupState.REMOVE, ByteBuffer.allocate(4).putInt(node).array());
                }
            }
        });
    }

    public void post(final String groupId, final String text) {
        final byte[] t = text.getBytes(StandardCharsets.UTF_8);
        if (t.length > GroupState.MAX_TEXT) throw new IllegalArgumentException("text too long");
        c.post(new Runnable() {
            @Override public void run() {
                synchronized (lock) {
                    G g = groups.get(groupId);
                    if (g != null && g.st.isMember(c.selfNode())) localOp(groupId, g, GroupState.MSG, t);
                }
            }
        });
    }

    /** React to a message (opId as given to the listener / in history()). on=false takes a reaction back. */
    public void react(final String groupId, String opId, String emoji, boolean on) {
        String[] p = opId.split(":");
        final byte[] e = emoji.getBytes(StandardCharsets.UTF_8);
        final byte[] body = ByteBuffer.allocate(9 + e.length).putInt(Integer.parseInt(p[0])).putInt(Integer.parseInt(p[1])).put((byte) (on ? 1 : 0)).put(e).array();
        c.post(new Runnable() {
            @Override public void run() {
                synchronized (lock) {
                    G g = groups.get(groupId);
                    if (g != null && g.st.isMember(c.selfNode())) localOp(groupId, g, GroupState.REACT, body);
                }
            }
        });
    }

    public List<Message> history(String groupId) {
        synchronized (lock) {
            G g = groups.get(groupId);
            List<Message> out = new ArrayList<>();
            if (g == null) return out;
            for (GroupState.Msg m : g.st.history()) {
                Map<String, Set<String>> r = new LinkedHashMap<>();
                for (Map.Entry<String, Set<Integer>> e : g.st.reactionsOf(m.key).entrySet()) {
                    Set<String> names = new TreeSet<>();
                    for (int n : e.getValue()) names.add(name(g, n));
                    r.put(e.getKey(), names);
                }
                out.add(new Message(m.key, name(g, m.author), m.ts, m.text, r));
            }
            return out;
        }
    }

    public Set<String> members(String groupId) {
        synchronized (lock) {
            G g = groups.get(groupId);
            Set<String> out = new TreeSet<>();
            if (g != null) for (int n : g.st.members()) out.add(name(g, n));
            return out;
        }
    }

    public List<String> groupIds() { synchronized (lock) { return new ArrayList<>(groups.keySet()); } }

    // ------------------------------------------------------------------ local ops

    private String name(G g, int author) {
        return g.st.open ? "key-" + Integer.toHexString(author) : c.idMap().userOrPlaceholder(author);
    }

    private void localOp(String id, G g, int type, byte[] body) {
        GroupState.Op op = g.st.create(c.identity(), c.selfNode(), type, System.currentTimeMillis(), body);
        List<GroupState.Op> acc = g.st.ingest(op);
        if (acc.isEmpty()) return;
        persist(id, g, acc);
        emit(id, g, acc);
        push(g, op, type == GroupState.REMOVE ? ByteBuffer.wrap(body).getInt() : -1);
    }

    private void push(G g, GroupState.Op op) { push(g, op, -1); }

    /** extra: one more recipient (a member who has just been removed must still hear about it) */
    private void push(G g, GroupState.Op op, int extra) {
        byte[] enc = op.encode();
        byte[] payload = ByteBuffer.allocate(1 + 8 + 1 + 2 + enc.length).put((byte) SUB_OPS).put(g.st.gid).put((byte) 1)
                .putShort((short) enc.length).put(enc).array();
        if (g.st.open) {
            c.broadcast(BCAST_CHANNEL, payload);
        } else {
            java.util.Set<Integer> to = new java.util.TreeSet<>(g.st.members());
            if (extra > 0) to.add(extra);
            for (int node : to) if (node != c.selfNode()) c.sendExtNode(node, EXT_GROUP, payload, MeshClient.PRIORITY_NORMAL, null);
        }
    }

    // ------------------------------------------------------------------ receiving

    private void onMessage(int from, byte[] p, boolean linkLevel) {
        if (p.length < 1 + 8) return;
        int sub = p[0] & 0xFF;
        byte[] gidBytes = Arrays.copyOfRange(p, 1, 9);
        String id = GroupState.hex(gidBytes);
        synchronized (lock) {
            G g = groups.get(id);
            if (sub == SUB_INVITE) { onInvite(from, id, gidBytes, p); return; }
            if (g == null || g.st.open != linkLevel) return;
            if (sub == SUB_HAVE) onHave(from, id, g, p);
            else if (sub == SUB_OPS) onOps(id, g, p);
        }
    }

    private void onInvite(int from, String id, byte[] gid, byte[] p) {
        if (p.length < 1 + 8 + 4 + 1) return;
        ByteBuffer b = ByteBuffer.wrap(p, 9, p.length - 9);
        int founder = b.getInt();
        b.get();
        if (founder != from || groups.containsKey(id)) return;          // only the founder himself can invite, and I must not know it yet
        byte[] nm = new byte[b.remaining()];
        b.get(nm);
        G g = new G(new GroupState(gid, false, founder, keys), new String(nm, StandardCharsets.UTF_8));
        groups.put(id, g);
        saveMeta(id, g);
        sendHave(g, from);
    }

    private Map<Integer, Integer> parseVector(byte[] p) {
        Map<Integer, Integer> v = new HashMap<>();
        if (p.length < 10) return v;
        int n = p[9] & 0xFF;
        if (p.length != 10 + 8 * n) return v;
        ByteBuffer b = ByteBuffer.wrap(p, 10, 8 * n);
        for (int i = 0; i < n; i++) v.put(b.getInt(), b.getInt());
        return v;
    }

    private void onHave(int from, String id, G g, byte[] p) {
        if (!g.st.open && !g.st.isMember(from)) return;                 // do not talk about a private group with a stranger
        Map<Integer, Integer> theirs = parseVector(p);
        List<GroupState.Op> miss = g.st.missingFor(theirs, 500);
        int i = 0;
        while (i < miss.size()) {
            List<byte[]> chunk = new ArrayList<>();
            int bytes = 0;
            while (i < miss.size() && chunk.size() < OPS_CHUNK_COUNT && bytes < OPS_CHUNK_BYTES) {
                byte[] e = miss.get(i++).encode();
                chunk.add(e);
                bytes += e.length + 2;
            }
            ByteBuffer b = ByteBuffer.allocate(1 + 8 + 1 + bytes).put((byte) SUB_OPS).put(g.st.gid).put((byte) chunk.size());
            for (byte[] e : chunk) b.putShort((short) e.length).put(e);
            if (g.st.open) c.sendToNeighbor(from, BCAST_CHANNEL, b.array());
            else c.sendExtNode(from, EXT_GROUP, b.array(), MeshClient.PRIORITY_NORMAL, null);
        }
        if (g.st.behind(theirs)) sendHave(g, from, true);               // they have things I lack: ask by telling them what I have (even if I said it before)
    }

    private void onOps(String id, G g, byte[] p) {
        if (p.length < 10) return;
        int n = p[9] & 0xFF;
        ByteBuffer b = ByteBuffer.wrap(p, 10, p.length - 10);
        List<GroupState.Op> acc = new ArrayList<>();
        for (int i = 0; i < n && b.remaining() >= 2; i++) {
            int len = b.getShort() & 0xFFFF;
            if (len > b.remaining()) break;
            byte[] e = new byte[len];
            b.get(e);
            GroupState.Op op = GroupState.Op.decode(e);
            if (op != null) acc.addAll(g.st.ingest(op));
        }
        if (acc.isEmpty()) return;
        persist(id, g, acc);
        emit(id, g, acc);
    }

    // ------------------------------------------------------------------ sync

    private void neighborUp(int node) {
        synchronized (lock) {
            for (G g : groups.values()) {
                g.sentVec.remove(node);
                if (g.st.open || g.st.isMember(node)) sendHave(g, node);
            }
        }
    }

    private void tick() {
        synchronized (lock) {
            for (G g : groups.values()) {
                for (int nb : c.neighbors()) if (g.st.open || g.st.isMember(nb)) sendHave(g, nb);
            }
        }
        c.schedule(syncMs, new Runnable() { @Override public void run() { tick(); } });
    }

    private void sendHave(G g, int node) { sendHave(g, node, false); }

    private void sendHave(G g, int node, boolean force) {
        Map<Integer, Integer> v = g.st.vector();
        int h = v.hashCode();
        Integer last = g.sentVec.get(node);
        if (!force && last != null && last == h) return;                          // nothing new since I last told this neighbour
        g.sentVec.put(node, h);
        ByteBuffer b = ByteBuffer.allocate(1 + 8 + 1 + 8 * Math.min(v.size(), 255)).put((byte) SUB_HAVE).put(g.st.gid).put((byte) Math.min(v.size(), 255));
        int k = 0;
        for (Map.Entry<Integer, Integer> e : v.entrySet()) { if (k++ >= 255) break; b.putInt(e.getKey()).putInt(e.getValue()); }
        if (g.st.open) c.sendToNeighbor(node, BCAST_CHANNEL, b.array());
        else c.sendExtNode(node, EXT_GROUP, b.array(), MeshClient.PRIORITY_NORMAL, null);
    }

    // ------------------------------------------------------------------ events

    private void emit(String id, G g, List<GroupState.Op> accepted) {
        boolean membersChanged = false;
        for (GroupState.Op op : accepted) {
            switch (op.type) {
                case GroupState.MSG:
                    listener.onMessage(id, name(g, op.author), op.ts, new String(op.body, StandardCharsets.UTF_8), op.key());
                    break;
                case GroupState.REACT: {
                    ByteBuffer b = ByteBuffer.wrap(op.body);
                    int ta = b.getInt(), ts = b.getInt();
                    boolean on = b.get() != 0;
                    listener.onReaction(id, ta + ":" + ts, new String(op.body, 9, op.body.length - 9, StandardCharsets.UTF_8), name(g, op.author), on);
                    break;
                }
                default:
                    membersChanged = true;
            }
        }
        if (membersChanged) {
            Set<String> m = new TreeSet<>();
            for (int n : g.st.members()) m.add(name(g, n));
            listener.onMembers(id, m);
        }
    }

    // ------------------------------------------------------------------ storage

    private static byte[] unhex(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    private void saveMeta(String id, G g) {
        StorageAdapter st = c.storage();
        if (st == null) return;
        byte[] nm = g.name.getBytes(StandardCharsets.UTF_8);
        st.put("grp-" + id, ByteBuffer.allocate(1 + 4 + nm.length).put((byte) (g.st.open ? 1 : 0)).putInt(g.st.founder).put(nm).array());
    }

    private void persist(String id, G g, List<GroupState.Op> ops) {
        StorageAdapter st = c.storage();
        if (st == null) return;
        for (GroupState.Op op : ops) st.put("gop-" + id + "-" + String.format("%08x-%08x", op.author, op.seq), op.encode());
    }

    private void load() {
        StorageAdapter st = c.storage();
        if (st == null) return;
        synchronized (lock) {
            for (String k : st.keys("grp-")) {
                String id = k.substring(4);
                byte[] m = st.get(k);
                if (m == null || m.length < 5) continue;
                ByteBuffer b = ByteBuffer.wrap(m);
                boolean open = b.get() != 0;
                int founder = b.getInt();
                byte[] nm = new byte[b.remaining()];
                b.get(nm);
                G g = new G(new GroupState(unhex(id), open, founder, keys), new String(nm, StandardCharsets.UTF_8));
                List<String> opKeys = st.keys("gop-" + id + "-");
                Collections.sort(opKeys);
                for (String ok : opKeys) {
                    GroupState.Op op = GroupState.Op.decode(st.get(ok));
                    if (op != null) g.st.ingest(op);
                }
                groups.put(id, g);
            }
        }
    }
}
