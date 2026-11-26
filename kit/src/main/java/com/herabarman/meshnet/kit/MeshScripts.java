package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.Clock;
import com.herabarman.meshnet.core.MiniAppHost;
import com.herabarman.meshnet.core.StorageAdapter;
import com.herabarman.meshnet.core.script.MeshScript;
import com.herabarman.meshnet.core.script.ScriptApp;
import com.herabarman.meshnet.core.script.ScriptDistributor;
import com.herabarman.meshnet.core.script.ScriptPackage;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Script mini-apps that spread over the mesh by themselves (link-level broadcast kind 0x09, no internet). Call after
 * {@link MeshMiniApps#install} and before client.start(). A package signed by a publisher in {@code trustedPublishers} that arrives
 * from any neighbour is verified, installed into the host and started; a newer version replaces the old one.
 */
public final class MeshScripts {
    public static final int KIND = 0x09;

    private MeshScripts() { }

    public static ScriptDistributor install(final MeshClient c, final MiniAppHost host, List<byte[]> trustedPublishers, StorageAdapter storage, MeshScript.Limits limits) {
        Clock clock = new Clock() {
            @Override public long nowMs() { return c.now(); }
            @Override public void schedule(long delayMs, Runnable task) { c.schedule(delayMs, task); }
        };
        ScriptDistributor.Config cfg = new ScriptDistributor.Config();
        cfg.trustedPublishers = trustedPublishers;
        cfg.scriptLimits = limits;
        final ScriptDistributor d = new ScriptDistributor(c.selfNode(), cfg, clock, new Random(), new ScriptDistributor.Link() {
            @Override public void send(int peer, byte[] message) { c.sendToNeighbor(peer, KIND, message); }
            @Override public List<Integer> peers() { return new ArrayList<>(c.neighbors()); }
        }, new IdentityRecordAuth(c.identity()), storage);
        d.setListener(new ScriptDistributor.Listener() {
            @Override public void onInstalled(ScriptApp app, ScriptPackage pkg) { host.replace(app); }
            @Override public void onProgress(String appId, int have, int total) { }
            @Override public void onRejected(String appId, String reason) { }
        });
        for (ScriptApp app : d.restore()) host.register(app);
        c.registerBroadcast(KIND, new MeshClient.BroadcastHandler() {
            @Override public void onBroadcast(int from, byte[] data) { d.onMessage(from, data); }
        });
        c.addNeighborObserver(new MeshClient.NeighborObserver() {
            @Override public void onNeighbor(int node, boolean up) { if (up) d.onPeerUp(node); else d.onPeerDown(node); }
        });
        c.post(new Runnable() { @Override public void run() { d.start(); } });
        return d;
    }
}
