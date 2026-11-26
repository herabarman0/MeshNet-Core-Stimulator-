package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.Clock;
import com.herabarman.meshnet.core.OtaSwarm;
import com.herabarman.meshnet.core.RecordAuth;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Offline app updates over the mesh, wired onto a MeshClient: neighbours offer each other newer releases and pull
 * them chunk by chunk over the link-level channel (see {@link OtaSwarm} for the protocol and its security model).
 *
 * <pre>
 * OtaSwarm.Config oc = new OtaSwarm.Config();
 * oc.appId = "com.herasoft.chat";  oc.installedVersion = BuildConfig.VERSION_CODE;
 * oc.trustedPublishers.add(PUBLISHER_PUBLIC_KEY);                 // pinned in the app
 * OtaSwarm ota = MeshOta.install(client, oc, new IdentityRecordAuth(identity));   // before client.start()
 * ota.setListener(...onComplete -> hand the APK to the installer...);
 * </pre>
 * To publish: build the APK, {@code OtaManifest.create(...)} with the publisher's private key (offline, never on phones), and call
 * {@code ota.seed(manifest, apkBytes)} on the phone that has it.
 */
public final class MeshOta {
    /** Link-level broadcast kind used by this module (Alerts 1, Dtn 3, Groups 4 are taken). */
    public static final int KIND = 0x06;

    private MeshOta() { }

    /** Same, but a half-finished download is saved to storage and continued after an app restart. */
    public static OtaSwarm install(final MeshClient c, OtaSwarm.Config cfg, RecordAuth verifier, final com.herabarman.meshnet.core.StorageAdapter storage) {
        final OtaSwarm swarm = install(c, cfg, verifier);
        swarm.setStorage(storage);
        c.post(new Runnable() { @Override public void run() { swarm.resume(); } });
        return swarm;
    }

    public static OtaSwarm install(final MeshClient c, OtaSwarm.Config cfg, RecordAuth verifier) {
        Clock clock = new Clock() {
            @Override public long nowMs() { return c.now(); }
            @Override public void schedule(long delayMs, Runnable task) { c.schedule(delayMs, task); }
        };
        final OtaSwarm swarm = new OtaSwarm(c.selfNode(), cfg, clock, new Random(), new OtaSwarm.Link() {
            @Override public void send(int peer, byte[] message) { c.sendToNeighbor(peer, KIND, message); }
            @Override public List<Integer> peers() { return new ArrayList<>(c.neighbors()); }
        }, verifier);
        c.registerBroadcast(KIND, new MeshClient.BroadcastHandler() {
            @Override public void onBroadcast(int from, byte[] data) { swarm.onMessage(from, data); }
        });
        c.addNeighborObserver(new MeshClient.NeighborObserver() {
            @Override public void onNeighbor(int node, boolean up) { if (up) swarm.onPeerUp(node); else swarm.onPeerDown(node); }
        });
        c.post(new Runnable() { @Override public void run() { swarm.start(); } });
        return swarm;
    }
}
