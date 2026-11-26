package com.herabarman.meshnet.core;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Large messages (photos, voice) on top of the engine: split into chunks, send a few at a time, put back together.
 *
 * Every chunk is a normal engine message, so it is routed, ACKed and retried on its own. That gives the
 * "retry only the missing piece" behaviour for free: when a chunk finally fails the transfer pauses, and
 * retryMissing() re-sends only the chunks that were never confirmed.
 *
 * Sender: at most chunkWindow chunks in flight (the window also keeps one big transfer from flooding the radio).
 * Receiver: chunks may arrive in any order and more than once; memory is capped (maxAssemblies, maxReassemblyBytes)
 * and a transfer that goes quiet is dropped after reassemblyTimeoutMs.
 *
 * The layer sits between the node and your listener: it installs itself as the node's listener and passes
 * everything that is not a chunk on to the listener you give it.
 */
public final class ChunkLayer implements MeshNode.Listener {
    public interface Callbacks {
        /** A complete large message arrived. */
        void onComplete(int fromNode, long transferId, byte[] data);

        /** Sender side: how many chunks are confirmed so far. */
        void onProgress(long transferId, int chunksDone, int chunksTotal);

        /** Sender side: a chunk could not be delivered. Call retryMissing(transferId) to continue. */
        void onFailed(long transferId, String reason, int missingChunks);
    }

    /**
     * magic(1) id(8) index(2) total(2) totalLength(4).
     * The first byte is a fixed marker, never a random byte: a layer below (for example an encryption layer that
     * sends frames whose first byte looks like a key exchange in clear) must not be able to mistake a chunk for
     * something else. 0xC7 collides with no frame type of this project.
     */
    static final int FRAME_HEADER = 17;
    public static final int MAGIC = 0xC7;

    private static final class Transfer {
        final long id;
        final int dst;
        final int priority;
        final byte[] data;
        final int total;
        final boolean[] acked;
        final boolean[] sent;
        final Map<Long, Integer> msgIndex = new HashMap<>();
        int ackedCount;
        int inFlight;
        boolean failed;

        Transfer(long id, int dst, int priority, byte[] data, int total) {
            this.id = id;
            this.dst = dst;
            this.priority = priority;
            this.data = data;
            this.total = total;
            this.acked = new boolean[total];
            this.sent = new boolean[total];
        }
    }

    private static final class Assembly {
        final int total;
        final int totalLength;
        final byte[][] parts;
        int received;
        int bytes;
        long lastMs;

        Assembly(int total, int totalLength) {
            this.total = total;
            this.totalLength = totalLength;
            this.parts = new byte[total][];
        }
    }

    private final MeshNode node;
    private final MeshNode.Listener downstream;
    private final MeshConfig cfg;
    private final Clock clock;
    private final Random rng;
    private final Callbacks callbacks;
    private final Map<Long, Transfer> outbound = new HashMap<>();
    private final Map<Long, Long> chunkOwner = new HashMap<>();   // chunk message id -> transfer id
    private final Map<String, Assembly> assemblies = new HashMap<>();
    private final DedupCache completed = new DedupCache(256, 600_000);
    private long bufferedBytes;
    private boolean cleanupScheduled;
    private int rejectedFrames;
    private int chunksSent;
    private int evictedAssemblies;

    public ChunkLayer(MeshNode node, MeshNode.Listener downstream, MeshConfig cfg, Clock clock, Random rng, Callbacks callbacks) {
        this.node = node;
        this.downstream = downstream;
        this.cfg = cfg;
        this.clock = clock;
        this.rng = rng;
        this.callbacks = callbacks;
        node.setListener(this);
    }

    // ---- sending ----------------------------------------------------------------------------

    /** Start a transfer. @return the transfer id (progress and failure are reported through Callbacks). */
    public long send(int dst, byte[] data, int priority) {
        return send(dst, data, priority, rng.nextLong());
    }

    /** Like send(), with an id of your choosing (it must be unique per sender; the receiver uses it to tell transfers apart). */
    public long send(int dst, byte[] data, int priority, long transferId) {
        if (outbound.containsKey(transferId)) throw new IllegalArgumentException("transfer id in use");
        if (data.length == 0 || data.length > cfg.maxTransferBytes) throw new IllegalArgumentException("size");
        int total = (data.length + cfg.chunkBytes - 1) / cfg.chunkBytes;
        if (total > 0xFFFF) throw new IllegalArgumentException("too many chunks: raise chunkBytes");
        long id = transferId;
        Transfer t = new Transfer(id, dst, priority, data, total);
        outbound.put(id, t);
        pump(t);
        return id;
    }

    /** Re-send only the chunks that were never confirmed. @return false if the transfer is unknown or finished */
    public boolean retryMissing(long transferId) {
        Transfer t = outbound.get(transferId);
        if (t == null) return false;
        t.failed = false;
        pump(t);
        return true;
    }

    public int missingChunks(long transferId) {
        Transfer t = outbound.get(transferId);
        return t == null ? 0 : t.total - t.ackedCount;
    }

    private void pump(Transfer t) {
        for (int idx = 0; idx < t.total && !t.failed && t.inFlight < cfg.chunkWindow; idx++) {
            if (t.acked[idx] || t.sent[idx]) continue;
            int from = idx * cfg.chunkBytes;
            int len = Math.min(cfg.chunkBytes, t.data.length - from);
            ByteBuffer frame = ByteBuffer.allocate(FRAME_HEADER + len);
            frame.put((byte) MAGIC).putLong(t.id).putShort((short) idx).putShort((short) t.total).putInt(t.data.length);
            frame.put(t.data, from, len);
            long msgId = node.sendChunk(t.dst, frame.array(), t.priority);
            chunkOwner.put(msgId, t.id);
            t.msgIndex.put(msgId, idx);
            t.sent[idx] = true;
            t.inFlight++;
            chunksSent++;
        }
    }

    @Override
    public void onAcked(MeshNode n, long msgId) {
        Long tid = chunkOwner.remove(msgId);
        if (tid == null) {
            downstream.onAcked(n, msgId);
            return;
        }
        Transfer t = outbound.get(tid);
        if (t == null) return;
        Integer idx = t.msgIndex.remove(msgId);
        if (idx != null && !t.acked[idx]) {
            t.acked[idx] = true;
            t.ackedCount++;
            t.inFlight--;
        }
        if (callbacks != null) callbacks.onProgress(t.id, t.ackedCount, t.total);
        if (t.ackedCount == t.total) outbound.remove(t.id);
        else pump(t);
    }

    @Override
    public void onDropped(MeshNode n, Packet p, String reason) {
        Long tid = p == null ? null : chunkOwner.get(p.msgId);
        if (tid == null) {
            downstream.onDropped(n, p, reason);
            return;
        }
        chunkOwner.remove(p.msgId);
        Transfer t = outbound.get(tid);
        if (t == null) return;
        Integer idx = t.msgIndex.remove(p.msgId);
        if (idx != null && !t.acked[idx]) {
            t.sent[idx] = false;       // eligible again for retryMissing()
            t.inFlight--;
        }
        if (!t.failed) {
            t.failed = true;
            if (callbacks != null) callbacks.onFailed(t.id, reason, t.total - t.ackedCount);
        }
    }

    // ---- receiving --------------------------------------------------------------------------

    @Override
    public void onDelivered(MeshNode n, Packet p, byte[] plain, int fromPeer) {
        if (p.type != Packet.TYPE_CHUNK) {
            downstream.onDelivered(n, p, plain, fromPeer);
            return;
        }
        handleChunk(p.src, plain);
    }

    void handleChunk(int src, byte[] frame) {
        if (frame.length <= FRAME_HEADER) { rejectedFrames++; return; }
        if ((frame[0] & 0xFF) != MAGIC) { rejectedFrames++; return; }
        ByteBuffer b = ByteBuffer.wrap(frame, 1, frame.length - 1);
        long id = b.getLong();
        int index = b.getShort() & 0xFFFF;
        int total = b.getShort() & 0xFFFF;
        int totalLength = b.getInt();
        int len = frame.length - FRAME_HEADER;
        if (total < 1 || index >= total || totalLength <= 0 || totalLength > cfg.maxTransferBytes
                || len > totalLength || (long) total * 0xFFFF < totalLength) {
            rejectedFrames++;
            return;
        }
        long now = clock.nowMs();
        if (completed.contains(id * 31 + src, now)) return;   // already put together and handed over: a late duplicate chunk

        String key = src + ":" + id;
        Assembly a = assemblies.get(key);
        if (a == null) {
            while (assemblies.size() >= cfg.maxAssemblies) evictOldest();
            a = new Assembly(total, totalLength);
            assemblies.put(key, a);
        } else if (a.total != total || a.totalLength != totalLength) {
            rejectedFrames++;          // inconsistent with the first chunk of this transfer
            return;
        }
        a.lastMs = now;
        if (a.parts[index] == null) {
            while (bufferedBytes + len > cfg.maxReassemblyBytes && !assemblies.isEmpty()) {
                if (assemblies.size() == 1 && assemblies.containsKey(key)) { rejectedFrames++; return; }
                evictOldestExcept(key);
            }
            byte[] copy = new byte[len];
            System.arraycopy(frame, FRAME_HEADER, copy, 0, len);
            a.parts[index] = copy;
            a.received++;
            a.bytes += len;
            bufferedBytes += len;
        }
        if (a.received == a.total) finish(key, a, src, id);
        else scheduleCleanup();
    }

    private void finish(String key, Assembly a, int src, long id) {
        assemblies.remove(key);
        bufferedBytes -= a.bytes;
        completed.firstSeen(id * 31 + src, clock.nowMs());      // from now on late duplicates are ignored
        byte[] data = new byte[a.totalLength];
        int pos = 0;
        for (byte[] part : a.parts) {
            if (pos + part.length > data.length) { rejectedFrames++; return; }   // lengths do not add up: corrupt
            System.arraycopy(part, 0, data, pos, part.length);
            pos += part.length;
        }
        if (pos != a.totalLength) { rejectedFrames++; return; }
        if (callbacks != null) callbacks.onComplete(src, id, data);
    }

    private void evictOldest() {
        evictOldestExcept(null);
    }

    private void evictOldestExcept(String keep) {
        String oldestKey = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, Assembly> e : assemblies.entrySet()) {
            if (e.getKey().equals(keep)) continue;
            if (e.getValue().lastMs < oldest) {
                oldest = e.getValue().lastMs;
                oldestKey = e.getKey();
            }
        }
        if (oldestKey == null) return;
        Assembly gone = assemblies.remove(oldestKey);
        bufferedBytes -= gone.bytes;
        evictedAssemblies++;
    }

    private void scheduleCleanup() {
        if (cleanupScheduled) return;
        cleanupScheduled = true;
        clock.schedule(Math.max(1000, cfg.reassemblyTimeoutMs / 4), new Runnable() {
            @Override
            public void run() {
                cleanupScheduled = false;
                long now = clock.nowMs();
                Iterator<Map.Entry<String, Assembly>> it = assemblies.entrySet().iterator();
                while (it.hasNext()) {
                    Assembly a = it.next().getValue();
                    if (now - a.lastMs > cfg.reassemblyTimeoutMs) {
                        bufferedBytes -= a.bytes;
                        it.remove();
                    }
                }
                if (!assemblies.isEmpty()) scheduleCleanup();
            }
        });
    }

    /** Chunk messages handed to the engine so far (resends after retryMissing() count too). */
    public int chunksSent() { return chunksSent; }
    public int assembling() { return assemblies.size(); }
    public long bufferedBytes() { return bufferedBytes; }
    public int rejectedFrames() { return rejectedFrames; }
    public int evictedAssemblies() { return evictedAssemblies; }
    public int activeTransfers() { return outbound.size(); }

    // ---- everything else goes to the real listener ------------------------------------------

    @Override public void onDuplicate(MeshNode n, Packet p) { downstream.onDuplicate(n, p); }
    @Override public void onFloodSuppressed(MeshNode n, Packet p) { downstream.onFloodSuppressed(n, p); }
    @Override public void onRetry(MeshNode n, Packet p) { downstream.onRetry(n, p); }
    @Override public void onShed(MeshNode n, Packet p) { downstream.onShed(n, p); }
}
