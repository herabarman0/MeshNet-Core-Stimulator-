package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.FecCodec;
import com.herabarman.meshnet.core.HybridKeyExchange;
import com.herabarman.meshnet.core.RecordAuth;
import com.herabarman.meshnet.core.StorageAdapter;
import com.herabarman.meshnet.core.image.ImageShrinker;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

final class KitFuzzTests {
    @Test
    void fuzzFrameAlertsAndGroups() {
        Random rng = new Random(0xBEEFBEEF42L);
        byte[] dummyPub = IdentityKeys.generate().publicRaw();

        for (int i = 0; i < 6_000; i++) {
            byte[] buf = new byte[rng.nextInt(160)];
            rng.nextBytes(buf);
            assertDoesNotThrow(() -> {
                Frame.valid(buf);
                Alerts.decodeVerified(buf, Collections.singletonList(dummyPub));
                GroupState.Op.decode(buf);
            });
        }
    }

    @Test
    void lostPhoneSignedRemoteCommandAntiStalkingAndLastGasp() {
        TestClock clk = new TestClock();
        IdentityKeys ownerKeys = IdentityKeys.generate();
        RecordAuth ownerAuth = new IdentityRecordAuth(ownerKeys);
        byte[] secret = LostPhone.deriveOwnerSecret("my-master-seed".getBytes(StandardCharsets.UTF_8), "phone-1");

        AtomicInteger rxCmd = new AtomicInteger();
        AtomicInteger stalkerCount = new AtomicInteger();

        LostPhone.Link loop = new LostPhone.Link() {
            @Override public void send(int peer, byte[] message) { }
            @Override public List<Integer> peers() { return Collections.singletonList(2); }
        };
        LostPhone target = new LostPhone(1, new LostPhone.Config(), clk, new Random(1), loop,
                () -> new LostPhone.Fix(23.81, 90.41, 10, clk.nowMs()), null);

        target.armWithControlKey(secret, ownerAuth, ownerKeys.publicRaw());
        target.setCommandHandler((code, nonce, ts) -> rxCmd.set(code));
        target.setAntiStalkingListener((peer, slots, first) -> stalkerCount.incrementAndGet());

        // Last-gasp beacon + self-report
        assertTrue(target.onShutdownLastGasp());
        assertEquals(1, target.reportsMade);
        assertEquals(1, target.beaconsSent);

        // Signed RING command
        final byte[][] sentCmd = new byte[1][];
        LostPhone ownerPhone = new LostPhone(2, new LostPhone.Config(), clk, new Random(2),
                new LostPhone.Link() {
                    @Override public void send(int peer, byte[] message) { sentCmd[0] = message; }
                    @Override public List<Integer> peers() { return Collections.singletonList(1); }
                }, null, null);
        assertTrue(ownerPhone.sendRemoteCommand(LostPhone.CMD_RING, ownerAuth));
        target.onMessage(2, sentCmd[0]);
        assertEquals(LostPhone.CMD_RING, rxCmd.get());

        // Replay must be ignored
        target.onMessage(2, sentCmd[0]);
        assertEquals(1, target.commandsExecuted);

        // Anti-stalking across 3 distinct 15-min slots from peer 99
        byte[] stalkerSecret = LostPhone.deriveOwnerSecret("stalker".getBytes(StandardCharsets.UTF_8), "tag");
        for (int s = 0; s < 3; s++) {
            byte[] beacon = new byte[1 + LostCrypto.POINT];
            beacon[0] = (byte) LostPhone.OP_BEACON;
            System.arraycopy(LostCrypto.slotPublic(stalkerSecret, s), 0, beacon, 1, LostCrypto.POINT);
            target.onMessage(99, beacon);
            clk.advance(16 * 60_000L);
        }
        assertEquals(1, stalkerCount.get());
    }

    @Test
    void hybridPqcE2eeAndBulkMuxFecRecovery() {
        IdentityKeys aId = IdentityKeys.generate();
        IdentityKeys bId = IdentityKeys.generate();
        KeyDirectory dirA = new KeyDirectory(null);
        KeyDirectory dirB = new KeyDirectory(null);
        dirA.put(2, bId.publicRaw());
        dirB.put(1, aId.publicRaw());

        E2eeCrypto encA = new E2eeCrypto(1, aId, dirA);
        E2eeCrypto encB = new E2eeCrypto(2, bId, dirB);

        HybridKeyExchange.InitiatorOffer offer = encA.initiatePqcSession(2, encB.hybridX25519Pub(), encB.mlKemEncapsulationKey());
        encB.acceptPqcOffer(1, encA.hybridX25519Pub(), offer.ciphertext);

        byte[] ct = encA.encrypt("Post-Quantum ML-KEM + X25519 message!".getBytes(StandardCharsets.UTF_8), 1, 2, 77L);
        byte[] pt = encB.decrypt(ct, 1, 2, 77L);
        assertEquals("Post-Quantum ML-KEM + X25519 message!", new String(pt, StandardCharsets.UTF_8));

        // BulkMux + FecCodec erasure recovery with 2 missing shards
        byte[] raw = new byte[4096];
        for (int i = 0; i < raw.length; i++) raw[i] = (byte) (i % 29);
        byte[] compressed = com.herabarman.meshnet.core.PayloadCompressor.compress(raw);
        byte[][] shards = FecCodec.encode(compressed, 4, 2).shards;
        shards[0] = null;
        shards[2] = null;
        byte[] recovered = BulkMux.recoverFromShards(shards, 4, 2);
        assertArrayEquals(raw, recovered);
        assertNotNull(ImageShrinker.Config.ultraCompactVisuallyLossless());
    }

    private static final class TestClock implements com.herabarman.meshnet.core.Clock {
        long now = 1_700_000_000_000L;
        @Override public long nowMs() { return now; }
        @Override public void schedule(long delayMs, Runnable task) { }
        void advance(long dt) { now += dt; }
    }
}
