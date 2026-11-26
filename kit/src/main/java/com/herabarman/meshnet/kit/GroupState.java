package com.herabarman.meshnet.kit;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The replicated state of one group or channel: a CRDT built from signed, per-author, append-only logs.
 *
 * Every change is an Op written by one author with a contiguous sequence number (1, 2, 3 ...), signed with the
 * author's identity key. A replica applies an op only when it is authentic, allowed and in sequence, otherwise it
 * waits (pending) or drops it; the visible state is a pure function of the SET of accepted ops, so replicas that
 * received the same ops in any order, with repeats, end up identical (tested by shuffling).
 *
 *  - messages: grow-only set keyed by (author, seq), shown in (timestamp, author, seq) order
 *  - reactions: last-writer-wins per (message, emoji, reactor) by (timestamp, author)
 *  - members (private groups): changed only by the founder's own log (add / remove), so they have a total order.
 *    Each op names the founder-log length its author had seen (mv); it is valid if the author was a member at that
 *    version, which every replica computes the same way.
 *  - open channels: no membership; the author is identified by his key (author id = first 4 bytes of the key's hash)
 *    and anyone can post.
 * Not thread-safe. Engine thread only.
 */
public final class GroupState {
    public static final int MSG = 1, REACT = 2, ADD = 3, REMOVE = 4;
    static final int MAX_PENDING = 300, MAX_OPS = 5000, MAX_TEXT = 1000;

    public interface Keys { byte[] keyOf(int node); }

    public static final class Op {
        public final byte[] gid;
        public final int author, seq, mv, type;
        public final long ts;
        public final byte[] body;
        public final byte[] pub;       // open channels only
        public byte[] sig;

        public Op(byte[] gid, int author, int seq, int mv, long ts, int type, byte[] body, byte[] pub) {
            this.gid = gid; this.author = author; this.seq = seq; this.mv = mv; this.ts = ts; this.type = type; this.body = body; this.pub = pub;
        }

        byte[] signedBytes() {
            int pl = pub == null ? 0 : 65;
            ByteBuffer b = ByteBuffer.allocate(8 + 4 + 4 + 4 + 8 + 1 + 2 + body.length + 1 + pl);
            b.put(gid).putInt(author).putInt(seq).putInt(mv).putLong(ts).put((byte) type).putShort((short) body.length).put(body);
            b.put((byte) (pub == null ? 0 : 1));
            if (pub != null) b.put(pub);
            return b.array();
        }

        public byte[] encode() {
            byte[] s = signedBytes();
            return ByteBuffer.allocate(s.length + 1 + sig.length).put(s).put((byte) sig.length).put(sig).array();
        }

        public static Op decode(byte[] d) {
            try {
                ByteBuffer b = ByteBuffer.wrap(d);
                byte[] gid = new byte[8];
                b.get(gid);
                int author = b.getInt(), seq = b.getInt(), mv = b.getInt();
                long ts = b.getLong();
                int type = b.get() & 0xFF;
                byte[] body = new byte[b.getShort() & 0xFFFF];
                b.get(body);
                byte[] pub = null;
                if (b.get() != 0) { pub = new byte[65]; b.get(pub); }
                Op op = new Op(gid, author, seq, mv, ts, type, body, pub);
                op.sig = new byte[b.get() & 0xFF];
                b.get(op.sig);
                if (b.hasRemaining() || seq < 1 || type < 1 || type > 4) return null;
                return op;
            } catch (RuntimeException e) {
                return null;
            }
        }

        public String key() { return author + ":" + seq; }
    }

    public static final class Msg {
        public final String key;
        public final int author;
        public final long ts;
        public final String text;
        public Msg(String key, int author, long ts, String text) { this.key = key; this.author = author; this.ts = ts; this.text = text; }
    }

    private static final class React {
        final boolean on;
        final long ts;
        final int author;
        React(boolean on, long ts, int author) { this.on = on; this.ts = ts; this.author = author; }
    }

    final byte[] gid;
    final boolean open;
    final int founder;
    private final Keys keys;
    private final Map<Integer, List<Op>> logs = new HashMap<>();
    private final List<Op> pending = new ArrayList<>();
    private final Map<Integer, byte[]> memberKeys = new HashMap<>();
    private final Set<Integer> members = new TreeSet<>();
    private final Map<String, Msg> msgs = new HashMap<>();
    private final Map<String, React> reacts = new HashMap<>();   // "msgKey|emoji|reactor"

    public GroupState(byte[] gid, boolean open, int founder, Keys keys) {
        this.gid = gid; this.open = open; this.founder = founder; this.keys = keys;
        if (!open) members.add(founder);
    }

    public static int authorOf(byte[] pub) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(pub);
            return ((d[0] & 0x7F) << 24) | ((d[1] & 0xFF) << 16) | ((d[2] & 0xFF) << 8) | (d[3] & 0xFF);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] b) { return Ratchet.hex(b); }

    // ------------------------------------------------------------------ creating ops (for local authors)

    public Op create(IdentityKeys me, int meNode, int type, long ts, byte[] body) {
        int author = open ? authorOf(me.publicRaw()) : meNode;
        int seq = size(author) + 1;
        Op op = new Op(gid, author, seq, open ? 0 : size(founder), ts, type, body, open ? me.publicRaw() : null);
        op.sig = me.sign(op.signedBytes());
        return op;
    }

    public int size(int author) { List<Op> l = logs.get(author); return l == null ? 0 : l.size(); }

    // ------------------------------------------------------------------ ingesting

    /** @return the ops that became part of the state because of this call (the op itself and any it unblocked) */
    public List<Op> ingest(Op op) {
        List<Op> accepted = new ArrayList<>();
        if (!Arrays.equals(op.gid, gid) || op.body.length > 2 * MAX_TEXT + 100) return accepted;
        int r = tryApply(op, accepted);
        if (r == PENDING && pending.size() < MAX_PENDING) pending.add(op);
        boolean progress = !accepted.isEmpty();
        while (progress) {
            progress = false;
            for (int i = 0; i < pending.size(); i++) {
                Op p = pending.get(i);
                int rr = tryApply(p, accepted);
                if (rr != PENDING) { pending.remove(i); i--; if (rr == APPLIED) progress = true; }
            }
        }
        return accepted;
    }

    private static final int APPLIED = 0, PENDING = 1, REJECTED = 2;

    private int tryApply(Op op, List<Op> accepted) {
        int have = size(op.author);
        if (op.seq <= have) return REJECTED;                              // already have it
        if (totalOps() >= MAX_OPS) return REJECTED;
        if ((op.type == ADD || op.type == REMOVE) && (open || op.author != founder)) return REJECTED;
        byte[] key;
        if (open) {
            if (op.pub == null || authorOf(op.pub) != op.author) return REJECTED;
            key = op.pub;
        } else {
            key = op.author == founder ? founderKey() : memberKeys.get(op.author);
            if (key == null) return PENDING;                               // do not know this author's key yet
        }
        if (!IdentityKeys.verify(key, op.signedBytes(), op.sig)) return REJECTED;
        if (!open && op.author != founder) {
            if (size(founder) < op.mv) return PENDING;                     // have not seen the membership version it relied on
            if (!membersAt(op.mv).contains(op.author)) return REJECTED;
        }
        if (op.seq > have + 1) return PENDING;                             // a gap before it
        List<Op> l = logs.get(op.author);
        if (l == null) { l = new ArrayList<>(); logs.put(op.author, l); }
        l.add(op);
        apply(op);
        accepted.add(op);
        return APPLIED;
    }

    private byte[] founderKey() { byte[] k = memberKeys.get(founder); return k != null ? k : keys.keyOf(founder); }

    private int totalOps() { int n = 0; for (List<Op> l : logs.values()) n += l.size(); return n; }

    private Set<Integer> membersAt(int version) {
        Set<Integer> s = new HashSet<>();
        s.add(founder);
        List<Op> f = logs.get(founder);
        for (int i = 0; f != null && i < Math.min(version, f.size()); i++) {
            Op o = f.get(i);
            if (o.type == ADD || o.type == REMOVE) {
                int node = ByteBuffer.wrap(o.body, 0, 4).getInt();
                if (o.type == ADD) s.add(node); else s.remove(node);
            }
        }
        return s;
    }

    private void apply(Op op) {
        switch (op.type) {
            case ADD: {
                int node = ByteBuffer.wrap(op.body, 0, 4).getInt();
                memberKeys.put(node, Arrays.copyOfRange(op.body, 4, 69));
                members.add(node);
                break;
            }
            case REMOVE:
                members.remove(ByteBuffer.wrap(op.body, 0, 4).getInt());
                break;
            case MSG:
                msgs.put(op.key(), new Msg(op.key(), op.author, op.ts, new String(op.body, StandardCharsets.UTF_8)));
                break;
            case REACT: {
                if (op.body.length < 9) break;
                ByteBuffer b = ByteBuffer.wrap(op.body);
                int tAuthor = b.getInt(), tSeq = b.getInt();
                boolean on = b.get() != 0;
                String emoji = new String(op.body, 9, op.body.length - 9, StandardCharsets.UTF_8);
                String k = tAuthor + ":" + tSeq + "|" + emoji + "|" + op.author;
                React old = reacts.get(k);
                if (old == null || op.ts > old.ts || (op.ts == old.ts && op.author >= old.author)) reacts.put(k, new React(on, op.ts, op.author));
                break;
            }
            default:
        }
    }

    // ------------------------------------------------------------------ reading

    public Set<Integer> members() { return Collections.unmodifiableSet(members); }

    public boolean isMember(int node) { return open || members.contains(node); }

    public List<Msg> history() {
        List<Msg> l = new ArrayList<>(msgs.values());
        Collections.sort(l, new Comparator<Msg>() {
            @Override public int compare(Msg a, Msg b) {
                if (a.ts != b.ts) return Long.compare(a.ts, b.ts);
                if (a.author != b.author) return Integer.compare(a.author, b.author);
                return a.key.compareTo(b.key);
            }
        });
        return l;
    }

    /** emoji -> set of reactors currently "on" for one message key ("author:seq") */
    public Map<String, Set<Integer>> reactionsOf(String msgKey) {
        Map<String, Set<Integer>> out = new TreeMap<>();
        for (Map.Entry<String, React> e : reacts.entrySet()) {
            String[] p = e.getKey().split("\\|", 3);
            if (!p[0].equals(msgKey) || !e.getValue().on) continue;
            Set<Integer> s = out.get(p[1]);
            if (s == null) { s = new TreeSet<>(); out.put(p[1], s); }
            s.add(Integer.parseInt(p[2]));
        }
        return out;
    }

    /** vector: author -> highest contiguous seq I have */
    public Map<Integer, Integer> vector() {
        Map<Integer, Integer> v = new TreeMap<>();
        for (Map.Entry<Integer, List<Op>> e : logs.entrySet()) v.put(e.getKey(), e.getValue().size());
        return v;
    }

    /** ops that a peer with this vector lacks, oldest first, at most `max` */
    public List<Op> missingFor(Map<Integer, Integer> theirs, int max) {
        List<Op> out = new ArrayList<>();
        for (Map.Entry<Integer, List<Op>> e : logs.entrySet()) {
            Integer t = theirs.get(e.getKey());
            for (int i = t == null ? 0 : t; i < e.getValue().size() && out.size() < max; i++) out.add(e.getValue().get(i));
        }
        return out;
    }

    public boolean behind(Map<Integer, Integer> theirs) {
        for (Map.Entry<Integer, Integer> e : theirs.entrySet()) if (e.getValue() > size(e.getKey())) return true;
        return false;
    }

    /** Canonical text of everything visible: identical on every converged replica. */
    public String snapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append("members=").append(members).append('\n');
        for (Msg m : history()) {
            sb.append(m.key).append('@').append(m.ts).append(':').append(m.text).append(' ').append(reactionsOf(m.key)).append('\n');
        }
        return sb.toString();
    }

    public int pendingCount() { return pending.size(); }
}
