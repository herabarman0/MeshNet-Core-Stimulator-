package com.herabarman.meshnet.core;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Offline IPFS (Content-Addressed Distributed Storage over Mesh):
 * Splits files/videos into content-addressed shards (SHA3-256 CID) protected by GF(256) Reed-Solomon parity,
 * spreads shards across nearby peers, indexes holders in a DHT shard table, summarizes held shards via a
 * {@link BloomFilter}, and recovers complete verified files even when several peer phones are offline.
 */
public final class MeshIpfs {
    public static final class CidManifest {
        public final String cid;            // "mnfs-" + 16 hex chars of SHA3-256
        public final byte[] rootHash;       // 32B SHA3-256 of original content
        public final int totalBytes;
        public final int dataShards;
        public final int parityShards;
        public final int shardBytes;
        public final String mimeType;
        public final Map<Integer, Set<Integer>> shardHolders = new HashMap<>(); // shardIdx -> peer node ids

        CidManifest(String cid, byte[] rootHash, int totalBytes, int dataShards, int parityShards, int shardBytes, String mimeType) {
            this.cid = cid;
            this.rootHash = rootHash.clone();
            this.totalBytes = totalBytes;
            this.dataShards = dataShards;
            this.parityShards = parityShards;
            this.shardBytes = shardBytes;
            this.mimeType = mimeType;
        }

        public int totalShards() { return dataShards + parityShards; }
    }

    /** Peer shard storage provider (could be backed by local SQLite/disk or remote ChunkLayer fetches). */
    public interface PeerShardStore {
        boolean isOnline(int peerId);
        void putShard(int peerId, String cid, int shardIdx, byte[] data);
        byte[] getShard(int peerId, String cid, int shardIdx);
    }

    private final Map<String, CidManifest> catalog = new LinkedHashMap<>();
    private final Map<String, byte[][]> localShards = new HashMap<>();

    public synchronized CidManifest publish(byte[] content, String mimeType, int dataShards, int parityShards,
                                            List<Integer> availablePeers, PeerShardStore store) {
        if (content == null || content.length == 0) throw new IllegalArgumentException("empty file");
        if (availablePeers == null || availablePeers.isEmpty()) throw new IllegalArgumentException("no peers");
        byte[] hash = Keccak.sha3_256(content);
        String cid = "mnfs-" + MeshLedger.hexPrefix(hash, 8);
        FecCodec.EncodedBlock block = FecCodec.encode(content, dataShards, parityShards);
        CidManifest m = new CidManifest(cid, hash, content.length, dataShards, parityShards, block.shardLength, mimeType);

        int total = block.shards.length;
        int replicaCount = Math.min(2, availablePeers.size());
        for (int idx = 0; idx < total; idx++) {
            Set<Integer> holders = new HashSet<>();
            for (int r = 0; r < replicaCount; r++) {
                int peer = availablePeers.get((idx + r) % availablePeers.size());
                if (store != null) store.putShard(peer, cid, idx, block.shards[idx]);
                holders.add(peer);
            }
            m.shardHolders.put(idx, holders);
        }
        catalog.put(cid, m);
        localShards.put(cid, block.shards);
        return m;
    }

    /** Retrieve and verify a file by CID across online peers using GF(256) Reed-Solomon recovery if peers are missing. */
    public synchronized byte[] fetch(String cid, PeerShardStore store) {
        CidManifest m = catalog.get(cid);
        if (m == null) return null;
        int total = m.totalShards();
        byte[][] recoveredShards = new byte[total][];
        int available = 0;
        for (int idx = 0; idx < total; idx++) {
            Set<Integer> holders = m.shardHolders.get(idx);
            if (holders == null) continue;
            for (int peer : holders) {
                if (store != null && !store.isOnline(peer)) continue;
                byte[] shard = store == null ? null : store.getShard(peer, cid, idx);
                if (shard != null && shard.length == m.shardBytes) {
                    recoveredShards[idx] = shard;
                    available++;
                    break;
                }
            }
        }
        if (available < m.dataShards) return null;
        byte[] assembled = FecCodec.decode(recoveredShards, m.dataShards, m.parityShards);
        if (assembled == null || assembled.length < m.totalBytes) return null;
        byte[] exact = Arrays.copyOf(assembled, m.totalBytes);
        byte[] check = Keccak.sha3_256(exact);
        if (!Arrays.equals(check, m.rootHash)) return null;
        return exact;
    }

    /** Compact BloomFilter advertisement of all CIDs and shard indices pinned by this node. */
    public synchronized BloomFilter exportInventoryFilter() {
        BloomFilter bf = BloomFilter.forCapacity(Math.max(64, catalog.size() * 8));
        for (Map.Entry<String, CidManifest> e : catalog.entrySet()) {
            long base = ByteBuffer.wrap(e.getValue().rootHash).getLong();
            bf.add(base);
            for (int i = 0; i < e.getValue().totalShards(); i++) {
                bf.add(base ^ (0x9E3779B97F4A7C15L * (i + 1)));
            }
        }
        return bf;
    }

    public synchronized List<CidManifest> listCatalog() {
        return new ArrayList<>(catalog.values());
    }
}
