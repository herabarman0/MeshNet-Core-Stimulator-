package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.Clock;
import com.herabarman.meshnet.core.MiniApp;
import com.herabarman.meshnet.core.MiniAppHost;
import com.herabarman.meshnet.core.StorageAdapter;

import java.util.HashSet;
import java.util.Set;

/**
 * Lets other developers build offline games, quizzes and local markets on top of the mesh: wires a {@link MiniAppHost} onto a
 * MeshClient. Reliable messages use the end-to-end encrypted EXT channel; nearby messages use the link-level channel.
 *
 * <pre>
 * MiniAppHost host = MeshMiniApps.install(client, storage, null);   // before client.start()
 * host.register(new QuizApp(listener));                              // any class implementing MiniApp
 * host.start();
 * </pre>
 */
public final class MeshMiniApps {
    public static final int KIND = 0x07;      // link-level broadcast kind (1, 3, 4 and 6 are taken)
    public static final int EXT = 0x30;       // EXT frame type (0x10 groups and 0x20-0x22 dtn are taken)

    private MeshMiniApps() { }

    /**
     * Same, with signed flooded frames: the app's onNearby() then receives the verified origin of a flooded message (see
     * {@link MiniAppHost#setAuth}). Origin keys come from the client's key directory (phones met directly or via the server);
     * with requireSigned, a flood from an origin whose key is not known yet is dropped.
     */
    public static MiniAppHost install(final MeshClient c, StorageAdapter storage, MiniAppHost.Limits limits, boolean requireSigned) {
        MiniAppHost host = install(c, storage, limits);
        host.setAuth(new IdentityRecordAuth(c.identity()), new MiniAppHost.KeyLookup() {
            @Override public byte[] keyOf(int node) { return c.keyDirectory().get(node); }
        }, requireSigned);
        return host;
    }

    public static MiniAppHost install(final MeshClient c, StorageAdapter storage, MiniAppHost.Limits limits) {
        Clock clock = new Clock() {
            @Override public long nowMs() { return c.now(); }
            @Override public void schedule(long delayMs, Runnable task) { c.schedule(delayMs, task); }
        };
        final MiniAppHost host = new MiniAppHost(new MiniAppHost.Net() {
            @Override public int selfId() { return c.selfNode(); }
            @Override public void sendReliable(int node, byte[] frame) { c.sendExtNode(node, EXT, frame, MeshClient.PRIORITY_NORMAL, null); }
            @Override public void sendNeighbor(int node, byte[] frame) { c.sendToNeighbor(node, KIND, frame); }
            @Override public Set<Integer> neighbors() { return new HashSet<>(c.neighbors()); }
        }, clock, storage, limits);
        c.registerExt(EXT, new MeshClient.ExtHandler() {
            @Override public void onExt(int fromNode, String fromUser, byte[] payload) { host.onReliableFrame(fromNode, payload); }
        });
        c.registerBroadcast(KIND, new MeshClient.BroadcastHandler() {
            @Override public void onBroadcast(int fromNode, byte[] data) { host.onNeighborFrame(fromNode, data); }
        });
        c.addNeighborObserver(new MeshClient.NeighborObserver() {
            @Override public void onNeighbor(int node, boolean up) { host.onNeighbor(node, up); }
        });
        return host;
    }
}
