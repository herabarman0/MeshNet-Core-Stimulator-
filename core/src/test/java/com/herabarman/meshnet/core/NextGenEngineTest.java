package com.herabarman.meshnet.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class NextGenEngineTest {
    private static MeshNode.Listener noopListener() {
        return new MeshNode.Listener() {
            @Override public void onDelivered(MeshNode node, Packet packet, byte[] plain, int fromPeer) {}
            @Override public void onDuplicate(MeshNode node, Packet packet) {}
            @Override public void onDropped(MeshNode node, Packet packet, String reason) {}
        };
    }

    @Test
    void mlKem768RoundTripAndImplicitRejection() {
        MlKem768.KeyPair kp = MlKem768.keygen();
        assertEquals(MlKem768.EK_BYTES, kp.ek.length);
        assertEquals(MlKem768.DK_BYTES, kp.dk.length);

        MlKem768.Encapsulation enc = MlKem768.encapsulate(kp.ek);
        assertEquals(MlKem768.CT_BYTES, enc.ciphertext.length);
        assertEquals(MlKem768.SS_BYTES, enc.sharedSecret.length);

        byte[] dec = MlKem768.decapsulate(kp.dk, enc.ciphertext);
        assertArrayEquals(enc.sharedSecret, dec);

        // Tamper 1 bit of ciphertext -> FO transform triggers implicit rejection
        byte[] tampered = enc.ciphertext.clone();
        tampered[42] ^= 0x01;
        byte[] rejected = MlKem768.decapsulate(kp.dk, tampered);
        assertFalse(Arrays.equals(enc.sharedSecret, rejected));
    }

    @Test
    void hybridX25519PlusMlKem768EndToEndEncryption() {
        HybridKeyExchange.HybridIdentity alice = HybridKeyExchange.generateIdentity();
        HybridKeyExchange.HybridIdentity bob = HybridKeyExchange.generateIdentity();

        HybridKeyExchange.InitiatorOffer offer = HybridKeyExchange.initiate(alice, bob.x25519Pub, bob.mlKemEk);
        byte[] bobKey = HybridKeyExchange.respond(bob, alice.x25519Pub, offer.ciphertext);

        assertArrayEquals(offer.sessionKey, bobKey);
        byte[] msg = "Post-Quantum Hybrid MeshNet E2EE".getBytes(StandardCharsets.UTF_8);
        byte[] enc = new AesGcmCrypto(offer.sessionKey).encrypt(msg, 1, 2, 1001L);
        assertArrayEquals(msg, new AesGcmCrypto(bobKey).decrypt(enc, 1, 2, 1001L));
    }

    @Test
    void meshLedgerPaymentsDoubleSpendHtlcAndSync() {
        ManualClock clock = new ManualClock();
        clock.advance(10_000L);
        final byte[] pubA = new byte[]{1, 2, 3};
        final byte[] pubB = new byte[]{4, 5, 6};
        RecordAuth simpleAuth = new RecordAuth() {
            @Override public byte[] publicKey() { return pubA; }
            @Override public byte[] sign(byte[] data) { return Keccak.sha3_256(data); }
            @Override public boolean verify(byte[] key, byte[] data, byte[] sig) {
                return Arrays.equals(Keccak.sha3_256(data), sig);
            }
        };
        MeshLedger ledger = new MeshLedger(clock, simpleAuth);
        ledger.registerAccount(10, pubA, 1000L);
        ledger.registerAccount(20, pubB, 100L);

        MeshLedger.Tx tx1 = ledger.createPayment(10, 20, 250L, "Offline Coffee", pubA);
        assertEquals(750L, ledger.balanceOf(10));
        assertEquals(350L, ledger.balanceOf(20));

        // Replay with tampered/reused nonce fails double-spend check
        MeshLedger.Tx forgedNonce = new MeshLedger.Tx("TX-bad", 10, 20, 50L, 1L, clock.nowMs(), "Replay", pubA, tx1.signature, tx1.receiptHash);
        assertFalse(ledger.applyVerifiedTx(forgedNonce));
        assertEquals(1, ledger.rejectedDoubleSpends);

        // HTLC Smart Contract: Lock 200 -> settle with preimage
        byte[] secret = "delivery-otp-9988".getBytes(StandardCharsets.UTF_8);
        MeshLedger.SmartContract sc = ledger.createHtlcContract(10, 20, 200L, secret, 60_000L);
        assertEquals(550L, ledger.balanceOf(10));
        assertFalse(ledger.settleHtlc(sc.contractId, "wrong".getBytes(StandardCharsets.UTF_8)));
        assertTrue(ledger.settleHtlc(sc.contractId, secret));
        assertEquals(MeshLedger.ContractState.RELEASED, sc.state);
        assertEquals(550L, ledger.balanceOf(20));

        // HTLC Expiry Refund
        MeshLedger.SmartContract sc2 = ledger.createHtlcContract(10, 20, 50L, secret, 5_000L);
        clock.advance(6_000L);
        assertTrue(ledger.refundExpiredHtlc(sc2.contractId));
        assertEquals(MeshLedger.ContractState.REFUNDED, sc2.state);
        assertEquals(550L, ledger.balanceOf(10));

        assertEquals(1, ledger.pendingServerSync().size());
        assertEquals(3, ledger.syncToServerBatch());
        assertEquals(0, ledger.pendingServerSync().size());
    }

    @Test
    void meshIpfsDistributedShardingAndFecRecovery() {
        final Map<Integer, Boolean> online = new HashMap<>();
        final Map<String, byte[]> disk = new HashMap<>();
        List<Integer> peers = Arrays.asList(1, 2, 3, 4, 5, 6);
        for (int p : peers) online.put(p, true);

        MeshIpfs.PeerShardStore store = new MeshIpfs.PeerShardStore() {
            @Override public boolean isOnline(int peerId) { return Boolean.TRUE.equals(online.get(peerId)); }
            @Override public void putShard(int peerId, String cid, int shardIdx, byte[] data) {
                disk.put(peerId + ":" + cid + ":" + shardIdx, data.clone());
            }
            @Override public byte[] getShard(int peerId, String cid, int shardIdx) {
                return disk.get(peerId + ":" + cid + ":" + shardIdx);
            }
        };

        MeshIpfs ipfs = new MeshIpfs();
        byte[] file = new byte[4096];
        for (int i = 0; i < file.length; i++) file[i] = (byte) (i * 31 + 7);
        MeshIpfs.CidManifest manifest = ipfs.publish(file, "video/mp4", 4, 2, peers, store);
        assertTrue(manifest.cid.startsWith("mnfs-"));

        // Take 2 peers offline; Reed-Solomon parity recovers the full file
        online.put(1, false);
        online.put(4, false);
        byte[] recovered = ipfs.fetch(manifest.cid, store);
        assertArrayEquals(file, recovered);
        assertTrue(ipfs.exportInventoryFilter().toBytes().length > 0);
    }

    @Test
    void turboSchedulerPriorityAndBatchCoalescing() {
        TurboScheduler sched = new TurboScheduler();
        sched.enqueue(Priority.BULK, Packet.TYPE_CHUNK, new byte[]{1, 2, 3, 4});
        sched.enqueue(Priority.SOS, Packet.TYPE_DATA, new byte[]{9, 9, 9});
        sched.enqueue(Priority.URGENT, Packet.TYPE_VOICE, new byte[]{7, 8});

        byte[] burst = sched.nextBurst(4096);
        assertNotNull(burst);
        List<byte[]> unpacked = TurboScheduler.unpackBurst(burst);
        assertEquals(3, unpacked.size());
        assertArrayEquals(new byte[]{9, 9, 9}, unpacked.get(0));
        assertArrayEquals(new byte[]{7, 8}, unpacked.get(1));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, unpacked.get(2));
    }

    @Test
    void meshRtpAudioAndFragmentedVideoDelivery() {
        MeshConfig cfg = new MeshConfig();
        TestKit.Net net = new TestKit.Net(cfg, 1L);
        MeshNode a = net.add(1, 0, 1.0);
        MeshNode b = net.add(2, 0, 1.0);
        net.link(1, 2);
        net.clock.run(2000);

        final byte[][] receivedVideo = new byte[1][];
        final int[] receivedAudio = new int[1];
        MeshRtpLayer rtpA = new MeshRtpLayer(a, noopListener(), net.clock, null);
        MeshRtpLayer rtpB = new MeshRtpLayer(b, noopListener(), net.clock, new MeshRtpLayer.Callbacks() {
            @Override public void onCallSignal(int fromNode, int callId, int signal, boolean withVideo) {}
            @Override public void onAudioFrame(int fromNode, int callId, int seq, long timestampMs, byte[] audioBytes) {
                receivedAudio[0]++;
            }
            @Override public void onVideoFrame(int fromNode, int callId, int frameSeq, boolean keyframe, long timestampMs, byte[] videoBytes) {
                receivedVideo[0] = videoBytes;
            }
            @Override public void onQualityReport(int peerNode, int callId, int lossPercent, int suggestedVideoKbps) {}
        });

        byte[] videoFrame = new byte[2200];
        for (int i = 0; i < videoFrame.length; i++) videoFrame[i] = (byte) (i & 0xff);
        rtpA.sendAudio(2, 77, new byte[]{10, 20, 30, 40});
        int frags = rtpA.sendVideoFrame(2, 77, videoFrame, true);
        assertEquals(3, frags);
        net.clock.run(1000);

        assertEquals(1, receivedAudio[0]);
        assertArrayEquals(videoFrame, receivedVideo[0]);
        assertNotNull(rtpB);
    }
}
