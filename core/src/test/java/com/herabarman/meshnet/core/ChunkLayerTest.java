package com.herabarman.meshnet.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The receiving side of ChunkLayer, driven with hand-made frames. */
class ChunkLayerTest {
    private final ManualClock clock = new ManualClock();
    private final MeshConfig cfg = new MeshConfig();
    private final List<byte[]> completed = new ArrayList<>();
    private ChunkLayer layer;

    private ChunkLayer layer() {
        if (layer != null) return layer;
        NodeInfo info = new NodeInfo(1, 0, 1, 1, false);
        MeshNode node = new MeshNode(info, cfg, new Transport() {
            @Override public void send(int peerId, byte[] data) { }
            @Override public void setListener(Listener l) { }
        }, clock, ZoneRouter.blockZones(100), new NoopCrypto(), null, new Random(1));
        MeshNode.Listener silent = new MeshNode.Listener() {
            @Override public void onDelivered(MeshNode n, Packet p, byte[] plain, int from) { }
            @Override public void onDuplicate(MeshNode n, Packet p) { }
            @Override public void onDropped(MeshNode n, Packet p, String reason) { }
        };
        layer = new ChunkLayer(node, silent, cfg, clock, new Random(2), new ChunkLayer.Callbacks() {
            @Override public void onComplete(int from, long id, byte[] data) { completed.add(data); }
            @Override public void onProgress(long id, int done, int total) { }
            @Override public void onFailed(long id, String reason, int missing) { }
        });
        return layer;
    }

    private static byte[] frame(long id, int index, int total, int totalLength, byte[] data) {
        return ByteBuffer.allocate(ChunkLayer.FRAME_HEADER + data.length).put((byte) ChunkLayer.MAGIC).putLong(id)
                .putShort((short) index).putShort((short) total).putInt(totalLength).put(data).array();
    }

    private static byte[] filled(int len, int value) {
        byte[] b = new byte[len];
        java.util.Arrays.fill(b, (byte) value);
        return b;
    }

    @Test
    void everyOutgoingFrameStartsWithTheFixedMarkerWhateverTheTransferId() {
        // a transfer id whose first byte is 0x20 or 0x21 would look like a key exchange frame to an encryption layer
        final List<byte[]> plains = new ArrayList<>();
        NodeInfo info = new NodeInfo(1, 0, 1, 1, false);
        MeshNode node = new MeshNode(info, cfg, new Transport() {
            @Override public void send(int peerId, byte[] data) { }
            @Override public void setListener(Listener l) { }
        }, clock, ZoneRouter.blockZones(100), new Crypto() {
            @Override public byte[] encrypt(byte[] plain, int src, int dst, long msgId) { plains.add(plain.clone()); return plain; }
            @Override public byte[] decrypt(byte[] c, int src, int dst, long msgId) { return c; }
        }, null, new Random(1));
        MeshNode.Listener silent = new MeshNode.Listener() {
            @Override public void onDelivered(MeshNode n, Packet p, byte[] plain, int from) { }
            @Override public void onDuplicate(MeshNode n, Packet p) { }
            @Override public void onDropped(MeshNode n, Packet p, String reason) { }
        };
        ChunkLayer l = new ChunkLayer(node, silent, cfg, clock, new Random(2), null);
        l.send(2, filled(3000, 7), 50, 0x2000000000000001L);
        l.send(3, filled(3000, 7), 50, 0x2100000000000002L);
        assertEquals(6, plains.size());                      // two transfers of 3 chunks (3000 bytes at 1 KB)
        for (byte[] p : plains) assertEquals(ChunkLayer.MAGIC, p[0] & 0xFF);
    }

    @Test
    void chunksInAnyOrderAndRepeatedAreAssembledOnce() {
        ChunkLayer l = layer();
        l.handleChunk(7, frame(42, 2, 3, 3000, filled(1000, 3)));
        l.handleChunk(7, frame(42, 0, 3, 3000, filled(1000, 1)));
        l.handleChunk(7, frame(42, 0, 3, 3000, filled(1000, 1)));   // repeated
        assertEquals(0, completed.size());
        l.handleChunk(7, frame(42, 1, 3, 3000, filled(1000, 2)));
        assertEquals(1, completed.size());
        byte[] data = completed.get(0);
        assertEquals(3000, data.length);
        assertEquals(1, data[0]);
        assertEquals(2, data[1500]);
        assertEquals(3, data[2999]);
        assertEquals(0, l.assembling());
        assertEquals(0, l.bufferedBytes());
    }

    @Test
    void aLateDuplicateOfAFinishedTransferIsIgnored() {
        ChunkLayer l = layer();
        l.handleChunk(7, frame(5, 0, 1, 10, filled(10, 9)));
        assertEquals(1, completed.size());
        l.handleChunk(7, frame(5, 0, 1, 10, filled(10, 9)));
        assertEquals(1, completed.size());
        assertEquals(0, l.assembling());
    }

    @Test
    void forgedOrBrokenFramesAreRejectedWithoutAllocatingAnything() {
        ChunkLayer l = layer();
        l.handleChunk(7, new byte[10]);                                  // too short
        byte[] noMagic = frame(1, 0, 1, 10, filled(10, 1));
        noMagic[0] = 0x20;
        l.handleChunk(7, noMagic);                                       // not a chunk frame
        l.handleChunk(7, frame(1, 5, 3, 3000, filled(100, 1)));          // index beyond total
        l.handleChunk(7, frame(1, 0, 0, 3000, filled(100, 1)));          // zero chunks
        l.handleChunk(7, frame(1, 0, 3, 0, filled(100, 1)));             // empty message
        l.handleChunk(7, frame(1, 0, 3, cfg.maxTransferBytes + 1, filled(100, 1)));   // bigger than allowed
        l.handleChunk(7, frame(1, 0, 3, 50, filled(100, 1)));            // chunk longer than the whole message
        l.handleChunk(7, frame(1, 0, 1, Integer.MAX_VALUE, filled(100, 1)));
        assertEquals(8, l.rejectedFrames());
        assertEquals(0, l.assembling());
        assertEquals(0, l.bufferedBytes());
    }

    @Test
    void aChunkThatContradictsTheFirstOneOfItsTransferIsRejected() {
        ChunkLayer l = layer();
        l.handleChunk(7, frame(9, 0, 3, 3000, filled(1000, 1)));
        l.handleChunk(7, frame(9, 1, 4, 3000, filled(1000, 1)));         // claims 4 chunks now
        l.handleChunk(7, frame(9, 1, 3, 3500, filled(1000, 1)));         // claims another total length
        assertEquals(2, l.rejectedFrames());
        assertEquals(1, l.assembling());
    }

    @Test
    void lengthsThatDoNotAddUpDiscardTheTransfer() {
        ChunkLayer l = layer();
        l.handleChunk(7, frame(3, 0, 2, 150, filled(100, 1)));
        l.handleChunk(7, frame(3, 1, 2, 150, filled(100, 1)));           // 200 bytes arrived, 150 were promised
        assertEquals(0, completed.size());
        assertEquals(1, l.rejectedFrames());
        assertEquals(0, l.assembling());
        assertEquals(0, l.bufferedBytes());
    }

    @Test
    void tooManyTransfersEvictTheOldest() {
        cfg.maxAssemblies = 2;
        ChunkLayer l = layer();
        l.handleChunk(1, frame(1, 0, 2, 200, filled(100, 1)));
        clock.advance(10);
        l.handleChunk(2, frame(2, 0, 2, 200, filled(100, 1)));
        clock.advance(10);
        l.handleChunk(3, frame(3, 0, 2, 200, filled(100, 1)));
        assertEquals(2, l.assembling());
        assertEquals(1, l.evictedAssemblies());
        l.handleChunk(1, frame(1, 1, 2, 200, filled(100, 1)));           // the evicted one cannot finish any more
        assertEquals(0, completed.size());
    }

    @Test
    void memoryHeldByHalfFinishedTransfersIsCapped() {
        cfg.maxReassemblyBytes = 2500;
        ChunkLayer l = layer();
        for (int i = 0; i < 20; i++) {
            l.handleChunk(i, frame(100 + i, 0, 5, 5000, filled(1000, 1)));
            clock.advance(5);
            assertTrue(l.bufferedBytes() <= 2500, "buffered " + l.bufferedBytes());
        }
        assertTrue(l.assembling() <= 2);
    }

    @Test
    void aTransferThatGoesQuietIsDroppedAfterTheTimeout() {
        ChunkLayer l = layer();
        l.handleChunk(7, frame(11, 0, 3, 3000, filled(1000, 1)));
        assertEquals(1, l.assembling());
        clock.advance(cfg.reassemblyTimeoutMs + 40_000);
        assertEquals(0, l.assembling());
        assertEquals(0, l.bufferedBytes());
    }
}
