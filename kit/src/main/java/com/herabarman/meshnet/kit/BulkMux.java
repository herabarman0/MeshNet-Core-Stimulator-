package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.FecCodec;
import com.herabarman.meshnet.core.PayloadCompressor;
import com.herabarman.meshnet.core.Priority;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multiplexed Concurrent Bulk Transfer Manager with LZ4 pre-compression & Reed-Solomon FEC parity.
 * Allows multiple photos, OTA apks, and offline maps to stream concurrently across {@link MeshClient}
 * without starving chat or voice, automatically recovering dropped chunks using {@link FecCodec}.
 */
public final class BulkMux {
    public static final int MAGIC = 0x424D5831; // "BMX1"

    public static final class StreamStatus {
        public final long streamId;
        public final String peerUser;
        public final int totalBytes;
        public final int compressedBytes;
        public final int dataShards;
        public final int parityShards;
        public final int shardsDispatched;
        public final int priority;

        StreamStatus(long streamId, String peerUser, int totalBytes, int compressedBytes,
                     int dataShards, int parityShards, int shardsDispatched, int priority) {
            this.streamId = streamId;
            this.peerUser = peerUser;
            this.totalBytes = totalBytes;
            this.compressedBytes = compressedBytes;
            this.dataShards = dataShards;
            this.parityShards = parityShards;
            this.shardsDispatched = shardsDispatched;
            this.priority = priority;
        }
    }

    private static final class ActiveStream {
        final long streamId;
        final String peerUser;
        final int origLen;
        final int compressedLen;
        final byte[][] shards;
        final int dataShards;
        final int parityShards;
        final int priority;
        int nextIdx;

        ActiveStream(long streamId, String peerUser, int origLen, int compressedLen,
                     byte[][] shards, int dataShards, int parityShards, int priority) {
            this.streamId = streamId;
            this.peerUser = peerUser;
            this.origLen = origLen;
            this.compressedLen = compressedLen;
            this.shards = shards;
            this.dataShards = dataShards;
            this.parityShards = parityShards;
            this.priority = priority;
        }
    }

    private final MeshClient client;
    private final Map<Long, ActiveStream> active = new LinkedHashMap<>();
    private long seq = 1;

    public BulkMux(MeshClient client) {
        this.client = client;
    }

    /** Enqueue a compressed, FEC-protected transfer across the multiplexed scheduler. */
    public synchronized long enqueue(String toUser, byte[] data, int parityShards, int priority) {
        if (data == null || data.length == 0) throw new IllegalArgumentException("empty");
        byte[] packed = PayloadCompressor.compress(data);
        int dShards = Math.max(2, Math.min(16, (packed.length + 1023) / 1024));
        int pShards = Math.max(1, Math.min(8, parityShards));
        FecCodec.EncodedBlock enc = FecCodec.encode(packed, dShards, pShards);
        long id = seq++;
        active.put(id, new ActiveStream(id, toUser, data.length, packed.length, enc.shards, dShards, pShards, priority));
        return id;
    }

    /** Round-robin dispatch across active streams (higher priority streams lead). */
    public synchronized int pumpNextBatch(int maxShardsPerCall) {
        if (active.isEmpty() || client == null) return 0;
        List<ActiveStream> ordered = new ArrayList<>(active.values());
        Collections.sort(ordered, new Comparator<ActiveStream>() {
            @Override public int compare(ActiveStream a, ActiveStream b) {
                return Integer.compare(b.priority, a.priority);
            }
        });
        int sent = 0;
        boolean progress = true;
        while (sent < maxShardsPerCall && progress) {
            progress = false;
            for (ActiveStream s : ordered) {
                if (sent >= maxShardsPerCall) break;
                if (s.nextIdx >= s.shards.length) continue;
                int idx = s.nextIdx++;
                byte[] frame = wrapShard(s.streamId, idx, s.dataShards, s.parityShards, s.shards[idx]);
                client.sendBytes(s.peerUser, frame, Math.min(Priority.NORMAL, s.priority));
                sent++;
                progress = true;
                if (s.nextIdx >= s.shards.length) active.remove(s.streamId);
            }
        }
        return sent;
    }

    /** Recover original uncompressed payload once >= dataShards arrive (even after packet drops). */
    public static byte[] recoverFromShards(byte[][] receivedShards, int dataShards, int parityShards) {
        byte[] packed = FecCodec.decode(receivedShards, dataShards, parityShards);
        return packed == null ? null : PayloadCompressor.decompress(packed);
    }

    public static byte[] wrapShard(long streamId, int shardIdx, int dataShards, int parityShards, byte[] shardBytes) {
        return ByteBuffer.allocate(4 + 8 + 3 + shardBytes.length)
                .putInt(MAGIC)
                .putLong(streamId)
                .put((byte) shardIdx)
                .put((byte) dataShards)
                .put((byte) parityShards)
                .put(shardBytes)
                .array();
    }

    public synchronized List<StreamStatus> status() {
        List<StreamStatus> out = new ArrayList<>(active.size());
        for (ActiveStream s : active.values()) {
            out.add(new StreamStatus(s.streamId, s.peerUser, s.origLen, s.compressedLen,
                    s.dataShards, s.parityShards, s.nextIdx, s.priority));
        }
        return out;
    }
}
