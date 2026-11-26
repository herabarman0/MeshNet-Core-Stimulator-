package com.herabarman.meshnet.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.Looper;
import com.herabarman.meshnet.kit.TcpRadio;

import java.net.InetSocketAddress;

/**
 * Brings up Wi-Fi Direct groups and hands the resulting IP connection to a {@link TcpRadio}: the group owner just listens (TcpRadio
 * already does), every other member connects to the owner's address. So Wi-Fi Direct becomes one more channel for MultiChannelRadio.
 *
 * NOT COMPILED OR TRIED ON A DEVICE (no Android SDK here). Known Wi-Fi Direct realities this class does not hide:
 *  - a phone is in ONE group at a time; the group is a star around its owner (clients cannot reach each other directly);
 *  - Android may show the user an "invitation" dialog the first time two phones connect; there is no silent way on current Android;
 *  - discovery uses location/nearby-devices permissions, and connecting drops a normal Wi-Fi connection on many phones
 *    (so this channel and the Wi-Fi UDP channel usually exclude each other);
 *  - it is slow to set up (several seconds) and battery hungry while discovering, so discovery is run in short bursts.
 * It is meant for a phone that wants a high-speed bulk link (photos, OTA updates) when Bluetooth is the only other path.
 */
public final class WifiDirectConnector {
    private final Context ctx;
    private final TcpRadio radio;
    private final int port;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private InetSocketAddress ownerAddress;
    private boolean started, connecting;
    private long discoverEveryMs = 60_000;

    public WifiDirectConnector(Context ctx, TcpRadio radio, int tcpPort) { this.ctx = ctx.getApplicationContext(); this.radio = radio; this.port = tcpPort; }

    public synchronized void start() {
        if (started) return;
        manager = (WifiP2pManager) ctx.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) return;                                       // no Wi-Fi Direct on this device
        channel = manager.initialize(ctx, Looper.getMainLooper(), null);
        IntentFilter f = new IntentFilter();
        f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { handle(i); }
        };
        ctx.registerReceiver(receiver, f);
        started = true;
        discoverTick();
    }

    public synchronized void stop() {
        if (!started) return;
        started = false;
        try { ctx.unregisterReceiver(receiver); } catch (RuntimeException ignored) { }
        try { manager.removeGroup(channel, null); } catch (RuntimeException ignored) { }
        try { manager.stopPeerDiscovery(channel, null); } catch (RuntimeException ignored) { }
        if (ownerAddress != null) radio.forget(ownerAddress);
        ownerAddress = null;
    }

    @SuppressWarnings("MissingPermission")
    private void discoverTick() {
        if (!started) return;
        if (ownerAddress == null && !connecting) {
            try {
                manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                    @Override public void onSuccess() { }
                    @Override public void onFailure(int reason) { }
                });
            } catch (SecurityException ignored) { }
        }
        main.postDelayed(new Runnable() { @Override public void run() { discoverTick(); } }, discoverEveryMs);
    }

    @SuppressWarnings("MissingPermission")
    private void handle(Intent i) {
        String a = i.getAction();
        if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(a)) {
            if (ownerAddress != null || connecting) return;
            try {
                manager.requestPeers(channel, new WifiP2pManager.PeerListListener() {
                    @Override public void onPeersAvailable(WifiP2pDeviceList list) { connectToOne(list); }
                });
            } catch (SecurityException ignored) { }
        } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(a)) {
            manager.requestConnectionInfo(channel, new WifiP2pManager.ConnectionInfoListener() {
                @Override public void onConnectionInfoAvailable(WifiP2pInfo info) { onGroup(info); }
            });
        }
    }

    /** Connect to one available peer that is also running this app (the device name carries no app identity, so any peer is tried). */
    @SuppressWarnings("MissingPermission")
    private void connectToOne(WifiP2pDeviceList list) {
        for (WifiP2pDevice d : list.getDeviceList()) {
            if (d.status != WifiP2pDevice.AVAILABLE) continue;
            WifiP2pConfig cfg = new WifiP2pConfig();
            cfg.deviceAddress = d.deviceAddress;
            connecting = true;
            try {
                manager.connect(channel, cfg, new WifiP2pManager.ActionListener() {
                    @Override public void onSuccess() { }
                    @Override public void onFailure(int reason) { connecting = false; }
                });
            } catch (SecurityException e) { connecting = false; }
            return;
        }
    }

    private synchronized void onGroup(WifiP2pInfo info) {
        connecting = false;
        if (info != null && info.groupFormed) {
            if (!info.isGroupOwner && info.groupOwnerAddress != null) {            // clients dial the owner; the owner's TcpRadio listens
                InetSocketAddress addr = new InetSocketAddress(info.groupOwnerAddress, port);
                if (ownerAddress != null && !ownerAddress.equals(addr)) radio.forget(ownerAddress);
                ownerAddress = addr;
                radio.connect(addr);
            }
        } else if (ownerAddress != null) {                                        // the group is gone
            radio.forget(ownerAddress);
            ownerAddress = null;
        }
    }
}
