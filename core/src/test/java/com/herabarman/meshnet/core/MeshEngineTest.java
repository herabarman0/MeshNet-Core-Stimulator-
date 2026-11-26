package com.herabarman.meshnet.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MeshEngineTest {
    /** In-memory radio: transports are wired together by link(). */
    static final class Hub {
        private final Map<Integer, HubTransport> transports = new HashMap<>();

        HubTransport transport(int id) {
            HubTransport t = new HubTransport(id);
            transports.put(id, t);
            return t;
        }

        void link(int a, int b) {
            transports.get(a).listener.onPeerUp(b);
            transports.get(b).listener.onPeerUp(a);
        }

        final class HubTransport implements Transport {
            final int id;
            volatile Listener listener;

            HubTransport(int id) { this.id = id; }

            @Override public void setListener(Listener l) { this.listener = l; }

            @Override
            public void send(int peerId, byte[] data) {
                HubTransport peer = transports.get(peerId);
                if (peer != null && peer.listener != null) peer.listener.onReceive(id, data);
            }
        }
    }

    private static MeshEngine.Builder engine(Hub hub, int id) {
        return MeshEngine.builder(id).zone(0).zoneCount(1).zones(ZoneRouter.blockZones(16)).transport(hub.transport(id));
    }

    @Test
    void twoEnginesExchangeAMessageAndTheSenderGetsTheAck() throws Exception {
        Hub hub = new Hub();
        final CountDownLatch got = new CountDownLatch(1);
        final CountDownLatch acked = new CountDownLatch(1);
        final AtomicReference<String> text = new AtomicReference<>();
        MeshEngine b = engine(hub, 2).onMessage(new MeshEngine.MessageListener() {
            @Override public void onMessage(int from, byte[] payload) { text.set(from + ":" + new String(payload)); got.countDown(); }
        }).build();
        MeshEngine a = engine(hub, 1).onDelivery(new MeshEngine.DeliveryListener() {
            @Override public void onAcked(long msgId) { acked.countDown(); }
            @Override public void onFailed(long msgId, String reason) { }
        }).build();
        hub.link(1, 2);
        a.start();
        b.start();
        a.send(2, "hello".getBytes());
        assertTrue(got.await(3, TimeUnit.SECONDS));
        assertEquals("1:hello", text.get());
        assertTrue(acked.await(3, TimeUnit.SECONDS));
        assertEquals(1, a.stats().acked);
        assertEquals(1, b.stats().received);
        a.stop();
        b.stop();
    }

    @Test
    void encryptedMessagesWork() throws Exception {
        Hub hub = new Hub();
        byte[] key = new byte[32];
        final CountDownLatch got = new CountDownLatch(1);
        final AtomicReference<byte[]> data = new AtomicReference<>();
        MeshEngine b = engine(hub, 2).crypto(new AesGcmCrypto(key)).onMessage(new MeshEngine.MessageListener() {
            @Override public void onMessage(int from, byte[] payload) { data.set(payload); got.countDown(); }
        }).build();
        MeshEngine a = engine(hub, 1).crypto(new AesGcmCrypto(key)).build();
        hub.link(1, 2);
        a.start();
        b.start();
        a.send(2, new byte[]{5, 6, 7});
        assertTrue(got.await(3, TimeUnit.SECONDS));
        assertArrayEquals(new byte[]{5, 6, 7}, data.get());
        a.stop();
        b.stop();
    }

    @Test
    void unconfirmedMessageSurvivesARestartAndIsDeliveredWhenThePeerAppears() throws Exception {
        File dir = StorageTest.tempDir("engine");
        Hub hub1 = new Hub();
        MeshEngine first = engine(hub1, 1).storage(new FileStorage(dir)).build();
        first.start();
        first.send(2, "keep me".getBytes());     // nobody is connected: it sits unconfirmed in the outbox
        Thread.sleep(150);
        first.stop();                            // "the app is killed"

        Hub hub2 = new Hub();
        final CountDownLatch got = new CountDownLatch(1);
        final AtomicReference<String> text = new AtomicReference<>();
        MeshEngine peer = engine(hub2, 2).onMessage(new MeshEngine.MessageListener() {
            @Override public void onMessage(int from, byte[] payload) { text.set(new String(payload)); got.countDown(); }
        }).build();
        final CountDownLatch acked = new CountDownLatch(1);
        MeshEngine second = engine(hub2, 1).storage(new FileStorage(dir)).onDelivery(new MeshEngine.DeliveryListener() {
            @Override public void onAcked(long msgId) { acked.countDown(); }
            @Override public void onFailed(long msgId, String reason) { }
        }).build();
        hub2.link(1, 2);
        peer.start();
        second.start();                          // the app is opened again: outbox is restored and re-sent
        assertTrue(got.await(4, TimeUnit.SECONDS), "restored message was not delivered");
        assertEquals("keep me", text.get());
        assertTrue(acked.await(4, TimeUnit.SECONDS), "restored message was not confirmed");
        Thread.sleep(100);
        assertEquals(0, new FileStorage(dir).keys("out-").size());   // confirmed: removed from disk
        second.stop();
        peer.stop();
    }

    @Test
    void enginesDiscoverZonesWithoutAnyZoneMap() throws Exception {
        Hub hub = new Hub();
        final CountDownLatch got = new CountDownLatch(1);
        final CountDownLatch acked = new CountDownLatch(1);
        // no .zones(...) given: discovery is on, the two engines learn each other's zone from HELLO
        MeshEngine b = MeshEngine.builder(2).zone(3).zoneCount(5).transport(hub.transport(2))
                .onMessage(new MeshEngine.MessageListener() {
                    @Override public void onMessage(int from, byte[] payload) { got.countDown(); }
                }).build();
        MeshEngine a = MeshEngine.builder(1).zone(3).zoneCount(5).transport(hub.transport(1))
                .onDelivery(new MeshEngine.DeliveryListener() {
                    @Override public void onAcked(long msgId) { acked.countDown(); }
                    @Override public void onFailed(long msgId, String reason) { }
                }).build();
        hub.link(1, 2);
        a.start();
        b.start();
        a.send(2, "hi".getBytes());     // sent before the HELLO exchange may have finished: it waits, then goes
        assertTrue(got.await(4, TimeUnit.SECONDS), "not delivered");
        assertTrue(acked.await(4, TimeUnit.SECONDS), "not acked");
        a.stop();
        b.stop();
    }

    @Test
    void aLargeMessageIsSplitSentAndPutTogether() throws Exception {
        Hub hub = new Hub();
        final CountDownLatch got = new CountDownLatch(1);
        final AtomicReference<byte[]> data = new AtomicReference<>();
        final java.util.concurrent.atomic.AtomicInteger lastDone = new java.util.concurrent.atomic.AtomicInteger();
        MeshConfig cfg = new MeshConfig();
        cfg.chunkBytes = 512;
        MeshEngine b = engine(hub, 2).onLarge(new MeshEngine.LargeListener() {
            @Override public void onLarge(int from, long id, byte[] d) { data.set(d); got.countDown(); }
            @Override public void onProgress(long id, int done, int total) { }
            @Override public void onLargeFailed(long id, String reason, int missing) { }
        }).config(cfg).build();
        MeshEngine a = engine(hub, 1).config(cfg).onLarge(new MeshEngine.LargeListener() {
            @Override public void onLarge(int from, long id, byte[] d) { }
            @Override public void onProgress(long id, int done, int total) { lastDone.set(done); }
            @Override public void onLargeFailed(long id, String reason, int missing) { }
        }).build();
        hub.link(1, 2);
        a.start();
        b.start();
        byte[] photo = new byte[5000];
        new java.util.Random(7).nextBytes(photo);
        a.sendLarge(2, photo, 100);
        assertTrue(got.await(5, TimeUnit.SECONDS), "large message did not arrive");
        assertArrayEquals(photo, data.get());
        Thread.sleep(300);
        assertEquals(10, lastDone.get());            // 5000 bytes in chunks of 512 = 10 chunks, all confirmed
        a.stop();
        b.stop();
    }

    @Test
    void builderRequiresATransport() {
        assertThrows(IllegalStateException.class, () -> MeshEngine.builder(1).build());
    }
}
