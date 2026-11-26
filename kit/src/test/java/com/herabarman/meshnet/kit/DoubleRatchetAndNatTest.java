package com.herabarman.meshnet.kit;

import static org.junit.jupiter.api.Assertions.*;

import com.herabarman.meshnet.core.Clock;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

public class DoubleRatchetAndNatTest {

    @Test
    public void doubleRatchetForwardSecrecyAndOutOfOrderHealing() {
        IdentityKeys aliceId = IdentityKeys.generate();
        IdentityKeys bobId = IdentityKeys.generate();
        DoubleRatchetSession alice = DoubleRatchetSession.createInitiator(aliceId, bobId.publicRaw());
        DoubleRatchetSession bob = DoubleRatchetSession.createResponder(bobId, aliceId.publicRaw());
        byte[] ad = "101->202".getBytes(StandardCharsets.UTF_8);

        byte[] c1 = alice.encrypt("msg-1".getBytes(StandardCharsets.UTF_8), ad);
        byte[] c2 = alice.encrypt("msg-2".getBytes(StandardCharsets.UTF_8), ad);
        // Deliver out of order: msg-2 arrives before msg-1
        assertEquals("msg-2", new String(bob.decrypt(c2, ad), StandardCharsets.UTF_8));
        assertEquals(1, bob.skippedKeyCount());
        assertEquals("msg-1", new String(bob.decrypt(c1, ad), StandardCharsets.UTF_8));
        assertEquals(0, bob.skippedKeyCount());

        // Bob replies -> triggers DH ratchet rotation (post-compromise security)
        String rootBefore = bob.rootKeyPrefixHex();
        byte[] reply = bob.encrypt("reply-1".getBytes(StandardCharsets.UTF_8), ad);
        assertEquals("reply-1", new String(alice.decrypt(reply, ad), StandardCharsets.UTF_8));
        assertNotEquals(rootBefore, alice.rootKeyPrefixHex());
    }

    @Test
    public void stunBindingAndP2pHolePunchingWorksAcrossConeNats() throws Exception {
        byte[] txId = new byte[12];
        new Random(7).nextBytes(txId);
        byte[] req = StunClient.bindingRequest(txId);
        assertTrue(StunClient.looksLikeStun(req));
        InetSocketAddress mapped = new InetSocketAddress("203.0.113.42", 54321);
        byte[] resp = StunClient.bindingResponse(txId, mapped);
        assertEquals(mapped, StunClient.parseBindingResponse(resp, txId));

        // Dual simulated UdpPorts behind Cone NATs punching a direct P2P tunnel
        final InetSocketAddress pubA = new InetSocketAddress("198.51.100.10", 41001);
        final InetSocketAddress pubB = new InetSocketAddress("198.51.100.20", 42002);
        final InetSocketAddress stunAddr = new InetSocketAddress("8.8.8.8", 3478);

        java.util.ArrayDeque<Runnable> netQueue = new java.util.ArrayDeque<>();

        class MemClock implements Clock {
            long now = 1000L;
            @Override public long nowMs() { return now; }
            @Override public void schedule(long delayMs, Runnable task) {}
        }
        MemClock clk = new MemClock();

        class SimPort implements UdpPort {
            Receiver rx;
            SimPort peer;
            InetSocketAddress myPub;
            @Override public void setReceiver(Receiver r) { this.rx = r; }
            @Override public List<InetSocketAddress> localCandidates() { return List.of(myPub); }
            @Override public void close() {}
            @Override public void send(InetSocketAddress to, byte[] data) {
                byte[] copy = data.clone();
                if (to.equals(stunAddr) && StunClient.looksLikeStun(copy)) {
                    netQueue.add(() -> rx.onDatagram(stunAddr, StunClient.bindingResponse(StunClient.transactionId(copy), myPub)));
                } else if (peer != null && peer.rx != null) {
                    netQueue.add(() -> peer.rx.onDatagram(myPub, copy));
                }
            }
        }

        SimPort portA = new SimPort(); portA.myPub = pubA;
        SimPort portB = new SimPort(); portB.myPub = pubB;
        portA.peer = portB;
        portB.peer = portA;

        CopyOnWriteArrayList<byte[]> recvByB = new CopyOnWriteArrayList<>();
        final P2pManager[] refA = new P2pManager[1];
        final P2pManager[] refB = new P2pManager[1];

        refA[0] = new P2pManager(101, portA, clk, new Random(1), List.of(stunAddr),
                (to, payload) -> netQueue.add(() -> refB[0].onSignal(payload)), p -> {});
        refB[0] = new P2pManager(202, portB, clk, new Random(2), List.of(stunAddr),
                (to, payload) -> netQueue.add(() -> refA[0].onSignal(payload)), recvByB::add);

        refA[0].start();
        refB[0].start();
        while (!netQueue.isEmpty()) netQueue.poll().run();

        refA[0].ensure(202);
        while (!netQueue.isEmpty()) netQueue.poll().run();

        assertTrue(refA[0].isOpen(202));
        assertTrue(refB[0].isOpen(101));
        assertTrue(refA[0].send(202, "direct-p2p-ok".getBytes(StandardCharsets.UTF_8)));
        while (!netQueue.isEmpty()) netQueue.poll().run();
        assertEquals(1, recvByB.size());
        assertEquals("direct-p2p-ok", new String(recvByB.get(0), StandardCharsets.UTF_8));
    }
}
