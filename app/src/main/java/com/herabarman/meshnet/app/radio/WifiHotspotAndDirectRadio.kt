package com.herabarman.meshnet.app.radio

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.herabarman.meshnet.kit.TcpBulk
import com.herabarman.meshnet.kit.TcpRadio
import com.herabarman.meshnet.kit.UdpRadio
import java.net.InetSocketAddress

/**
 * High-speed Wi-Fi LAN / Hotspot (UDP) + LocalOnlyHotspot (API 26+) + Wi-Fi Direct (TCP + TcpBulk)
 * connectors for ultra-fast bulk and multi-channel anti-jamming failover.
 */
class WifiHotspotController(private val context: Context) {
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    @Volatile
    var hotspotSsid: String? = null
        private set

    @Volatile
    var hotspotPassphrase: String? = null
        private set

    @Volatile
    var status: String = "Wi-Fi UDP Ready"
        private set

    fun acquireMulticastLock() {
        try {
            val wm = wifiManager ?: return
            if (multicastLock == null) {
                multicastLock = wm.createMulticastLock("meshnet_udp_multicast").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) { }
    }

    fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) { }
        multicastLock = null
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun startLocalOnlyHotspot(onUpdate: () -> Unit) {
        if (Build.VERSION.SDK_INT < 26) {
            status = "LocalOnlyHotspot API 26+ প্রয়োজন"
            onUpdate()
            return
        }
        val wm = wifiManager
        if (wm == null) {
            status = "Wi-Fi হার্ডওয়্যার অনুপলব্ধ"
            onUpdate()
            return
        }
        if (hotspotReservation != null) {
            status = "হটস্পট চালু: $hotspotSsid"
            onUpdate()
            return
        }
        try {
            wm.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        hotspotReservation = reservation
                        if (Build.VERSION.SDK_INT >= 30) {
                            val softAp = reservation.softApConfiguration
                            hotspotSsid = softAp.ssid
                            hotspotPassphrase = softAp.passphrase
                        } else {
                            val cfg = reservation.wifiConfiguration
                            hotspotSsid = cfg?.SSID
                            hotspotPassphrase = cfg?.preSharedKey
                        }
                        status = "অফলাইন হটস্পট চালু: ${hotspotSsid ?: "MeshAP"}"
                        onUpdate()
                    }

                    override fun onStopped() {
                        hotspotReservation = null
                        hotspotSsid = null
                        hotspotPassphrase = null
                        status = "হটস্পট বন্ধ হয়েছে"
                        onUpdate()
                    }

                    override fun onFailed(reason: Int) {
                        status = "হটস্পট ব্যর্থ (কোড $reason — সেটিংস থেকে চালু করুন)"
                        onUpdate()
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (se: SecurityException) {
            status = "NEARBY_WIFI_DEVICES / Location অনুমতি প্রয়োজন"
            onUpdate()
        } catch (e: Exception) {
            status = "হটস্পট: ${e.message}"
            onUpdate()
        }
    }

    fun stopLocalOnlyHotspot() {
        try { hotspotReservation?.close() } catch (_: Exception) { }
        hotspotReservation = null
        hotspotSsid = null
        hotspotPassphrase = null
        status = "হটস্পট বন্ধ"
    }
}

class WifiDirectHelper(
    private val context: Context,
    private val tcpRadio: TcpRadio,
    private val port: Int
) {
    private val main = Handler(Looper.getMainLooper())
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var ownerAddress: InetSocketAddress? = null
    private var started = false
    private var connecting = false

    @Volatile
    var status: String = "Idle"
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        val mgr = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager ?: run {
            status = "Wi-Fi Direct অনুপলব্ধ"
            return
        }
        val ch = mgr.initialize(context, Looper.getMainLooper(), null)
        manager = mgr
        channel = ch
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                handleIntent(intent)
            }
        }
        receiver = rx
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(rx, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(rx, filter)
        }
        started = true
        status = "Wi-Fi Direct সক্রিয়"
        discoverTick()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacksAndMessages(null)
        receiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) { } }
        receiver = null
        try { manager?.removeGroup(channel, null) } catch (_: Exception) { }
        try { manager?.stopPeerDiscovery(channel, null) } catch (_: Exception) { }
        ownerAddress?.let { tcpRadio.forget(it) }
        ownerAddress = null
        status = "বন্ধ"
    }

    @SuppressLint("MissingPermission")
    private fun discoverTick() {
        if (!started) return
        if (ownerAddress == null && !connecting) {
            try {
                manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { status = "Wi-Fi Direct স্ক্যান করছে" }
                    override fun onFailure(reason: Int) { status = "P2P স্ক্যান $reason" }
                })
            } catch (_: SecurityException) {
                status = "Wi-Fi Direct অনুমতি প্রয়োজন"
            }
        }
        main.postDelayed({ discoverTick() }, 45_000L)
    }

    @SuppressLint("MissingPermission")
    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                if (ownerAddress != null || connecting) return
                try {
                    manager?.requestPeers(channel) { peers ->
                        val avail = peers.deviceList.firstOrNull { it.status == WifiP2pDevice.AVAILABLE }
                        if (avail != null) {
                            val cfg = WifiP2pConfig().apply { deviceAddress = avail.deviceAddress }
                            connecting = true
                            try {
                                manager?.connect(channel, cfg, object : WifiP2pManager.ActionListener {
                                    override fun onSuccess() { status = "P2P সংযোগ শুরু..." }
                                    override fun onFailure(r: Int) { connecting = false }
                                })
                            } catch (_: SecurityException) {
                                connecting = false
                            }
                        }
                    }
                } catch (_: SecurityException) { }
            }
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                manager?.requestConnectionInfo(channel) { info ->
                    connecting = false
                    if (info != null && info.groupFormed) {
                        status = if (info.isGroupOwner) "Wi-Fi Direct Group Owner" else "Wi-Fi Direct Client"
                        if (!info.isGroupOwner && info.groupOwnerAddress != null) {
                            val addr = InetSocketAddress(info.groupOwnerAddress, port)
                            ownerAddress?.let { if (it != addr) tcpRadio.forget(it) }
                            ownerAddress = addr
                            tcpRadio.connect(addr)
                        }
                    } else {
                        ownerAddress?.let { tcpRadio.forget(it) }
                        ownerAddress = null
                    }
                }
            }
        }
    }
}

fun createWifiUdpRadio(selfNode: Int, port: Int = 47474): UdpRadio = UdpRadio(selfNode, port, true)
fun createWifiDirectTcpRadio(selfNode: Int, port: Int = 47475): Pair<TcpRadio, TcpBulk> {
    val tcp = TcpRadio(selfNode, port)
    val bulk = TcpBulk(selfNode, port + 1).follow(tcp, port + 1)
    return tcp to bulk
}
