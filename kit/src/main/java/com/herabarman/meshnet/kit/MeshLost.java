package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.Clock;
import com.herabarman.meshnet.core.StorageAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Wires {@link LostPhone} onto a MeshClient (link-level broadcast kind 0x08). Call before client.start().
 * <pre>
 * LostPhone lost = MeshLost.install(client, null, () -> new LostPhone.Fix(lat, lon, acc, time), storage);
 * lost.arm(secret);                  // on the phone that might get lost
 * lost.search(secret);               // on any phone, after it is lost
 * </pre>
 */
public final class MeshLost {
    public static final int KIND = 0x08;

    private MeshLost() { }

    public static LostPhone install(final MeshClient c, LostPhone.Config cfg, LostPhone.Where where, StorageAdapter store) {
        Clock clock = new Clock() {
            @Override public long nowMs() { return c.now(); }
            @Override public void schedule(long delayMs, Runnable task) { c.schedule(delayMs, task); }
        };
        final LostPhone lp = new LostPhone(c.selfNode(), cfg, clock, new Random(), new LostPhone.Link() {
            @Override public void send(int peer, byte[] message) { c.sendToNeighbor(peer, KIND, message); }
            @Override public List<Integer> peers() { return new ArrayList<>(c.neighbors()); }
        }, where, store);
        c.registerBroadcast(KIND, new MeshClient.BroadcastHandler() {
            @Override public void onBroadcast(int from, byte[] data) { lp.onMessage(from, data); }
        });
        c.addNeighborObserver(new MeshClient.NeighborObserver() {
            @Override public void onNeighbor(int node, boolean up) { if (up) lp.onPeerUp(node); else lp.onPeerDown(node); }
        });
        c.post(new Runnable() { @Override public void run() { lp.start(); } });
        return lp;
    }
}
