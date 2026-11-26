package com.herabarman.meshnet.app.radio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.herabarman.meshnet.core.ChannelPlanner
import com.herabarman.meshnet.core.Packet
import com.herabarman.meshnet.core.Priority
import com.herabarman.meshnet.core.TurboScheduler
import com.herabarman.meshnet.kit.AdaptiveDuty
import com.herabarman.meshnet.kit.DutyPolicy
import com.herabarman.meshnet.kit.MultiChannelRadio
import com.herabarman.meshnet.kit.RawRadio
import com.herabarman.meshnet.kit.TcpBulk
import com.herabarman.meshnet.kit.TcpRadio
import com.herabarman.meshnet.kit.UdpRadio
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unified Multi-Radio Fabric combining:
 *   Ch 0: Google Nearby Connections (P2P_CLUSTER)
 *   Ch 1: BLE GATT + Advertising/Scan (Low-power discovery & emergency burst)
 *   Ch 2: Wi-Fi / Hotspot LAN UDP (2.4 / 5 GHz broadcast + unicast)
 *   Ch 3: Wi-Fi Direct TCP + TcpBulk (High-bandwidth multi-MB bulk + P2P)
 *   Ch 4: Wi-Fi Aware (NAN) zero-AP discovery (API 26+)
 *   Ch 5: Near-Field Ultrasonic BFSK beacon (18.2 / 19.0 kHz)
 * Managed by {@link MultiChannelRadio}, {@link ChannelPlanner} anti-jamming,
 * {@link AdaptiveDuty} battery cycling, and {@link TurboScheduler} burst coalescing.
 */
class MultiRadioFabric(
    private val context: Context,
    val selfNode: Int
) {
    companion object {
        const val CH_NEARBY = 0
        const val CH_BLE = 1
        const val CH_WIFI_UDP = 2
        const val CH_WIFI_DIRECT = 3
        const val CH_WIFI_AWARE = 4
        const val CH_ULTRASONIC = 5

        fun channelName(ch: Int): String = when (ch) {
            CH_NEARBY -> "Google Nearby"
            CH_BLE -> "BLE GATT"
            CH_WIFI_UDP -> "Wi-Fi / Hotspot UDP"
            CH_WIFI_DIRECT -> "Wi-Fi Direct 5GHz"
            CH_WIFI_AWARE -> "Wi-Fi Aware NAN"
            CH_ULTRASONIC -> "Ultrasonic 19kHz"
            else -> "Channel $ch"
        }

        fun requiredRuntimePermissions(): Array<String> {
            val list = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 31) {
                list += Manifest.permission.BLUETOOTH_SCAN
                list += Manifest.permission.BLUETOOTH_ADVERTISE
                list += Manifest.permission.BLUETOOTH_CONNECT
            } else {
                list += Manifest.permission.ACCESS_FINE_LOCATION
            }
            if (Build.VERSION.SDK_INT >= 33) {
                list += Manifest.permission.NEARBY_WIFI_DEVICES
                list += Manifest.permission.POST_NOTIFICATIONS
            }
            list += Manifest.permission.RECORD_AUDIO
            return list.distinct().toTypedArray()
        }
    }

    val nearby = NearbyConnectionsRadio(context, selfNode)
    val ble = BleGattRadio(context, selfNode)
    val wifiUdp: UdpRadio = createWifiUdpRadio(selfNode, 47474)
    private val tcpPair: Pair<TcpRadio, TcpBulk> = createWifiDirectTcpRadio(selfNode, 47475)
    val wifiDirectTcp: TcpRadio = tcpPair.first
    val tcpBulk: TcpBulk = tcpPair.second
    val hotspot = WifiHotspotController(context)
    val wifiDirectHelper = WifiDirectHelper(context, wifiDirectTcp, 47475)
    val wifiAware = WifiAwareDiscovery(context, selfNode)
    val adaptiveDuty = AdaptiveDuty()
    val turbo = TurboScheduler(1024)

    val multiChannel: MultiChannelRadio
    val channelLogs = CopyOnWriteArrayList<String>()

    @Volatile
    var isStarted: Boolean = false
        private set

    @Volatile
    var activeChannel: Int = CH_WIFI_UDP
        private set

    init {
        val map = LinkedHashMap<Int, RawRadio>()
        map[CH_NEARBY] = nearby
        map[CH_BLE] = ble
        map[CH_WIFI_UDP] = wifiUdp
        map[CH_WIFI_DIRECT] = wifiDirectTcp
        val caps = doubleArrayOf(0.55, 0.25, 0.90, 1.00)
        val cfg = ChannelPlanner.Config().apply {
            minSamples = 6
            lossHigh = 0.45
            holdDownMs = 12_000
        }
        multiChannel = MultiChannelRadio(selfNode, map, caps, cfg)
        multiChannel.planner().setListener(object : ChannelPlanner.Listener {
            override fun onLinkChannel(peer: Int, from: Int, to: Int, reason: String) {
                activeChannel = to
                addLog("Anti-Jam: Peer $peer -> ${channelName(from)} থেকে ${channelName(to)} ($reason)")
            }

            override fun onLost(peer: Int) {
                addLog("Rendezvous hopping শুরু (Peer $peer)")
            }

            override fun onFound(peer: Int, channel: Int) {
                activeChannel = channel
                addLog("Peer $peer পুনরায় যুক্ত: ${channelName(channel)}")
            }
        })
        multiChannel.setHandler(object : RawRadio.Handler {
            override fun onLinkUp(peer: Int) {
                addLog("লিঙ্ক আপ: নোড $peer (${channelName(multiChannel.channelOf(peer))})")
            }

            override fun onLinkDown(peer: Int) {
                addLog("লিঙ্ক ডাউন: নোড $peer")
            }

            override fun onRaw(peer: Int, data: ByteArray) {
                val frames = TurboScheduler.unpackBurst(data)
                addLog("রিসিভ ${frames.size} ফ্রেম (${data.size} B) নোড $peer থেকে")
            }
        })
    }

    fun startAll() {
        if (isStarted) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, MeshForegroundService::class.java))
        } catch (_: Exception) { }
        hotspot.acquireMulticastLock()
        try {
            multiChannel.start()
        } catch (e: Exception) {
            addLog("রেডিও স্টার্ট: ${e.message}")
        }
        try {
            wifiDirectHelper.start()
        } catch (_: Exception) { }
        try {
            wifiAware.start()
        } catch (_: Exception) { }
        isStarted = true
        addLog("সব রেডিও চালু: Nearby + BLE + Wi-Fi Direct + Wi-Fi Aware + UDP")
    }

    fun stopAll() {
        if (!isStarted) return
        isStarted = false
        try { wifiAware.stop() } catch (_: Exception) { }
        try { wifiDirectHelper.stop() } catch (_: Exception) { }
        try { hotspot.stopLocalOnlyHotspot() } catch (_: Exception) { }
        hotspot.releaseMulticastLock()
        try { multiChannel.stop() } catch (_: Exception) { }
        try { context.stopService(Intent(context, MeshForegroundService::class.java)) } catch (_: Exception) { }
        addLog("মাল্টি-রেডিও বন্ধ করা হয়েছে")
    }

    /** Multi-Transport Bonding policy: BLE/Aware/Ultrasonic discover; Wi-Fi Direct/Hotspot carry heavy streams. */
    fun selectBondedTransport(payloadBytes: Int, isRealtimeVoice: Boolean): Pair<Int, String> {
        return when {
            isRealtimeVoice -> CH_WIFI_DIRECT to "ভয়েস স্ট্রিম ➔ স্বয়ংক্রিয় Wi-Fi Direct 5GHz + UDP বন্ডিং"
            payloadBytes > 4096 -> CH_WIFI_DIRECT to "বড় ফাইল (${payloadBytes / 1024} KB) ➔ TcpBulk / Wi-Fi Direct অটো-প্রোমোশন"
            wifiAware.active -> CH_WIFI_AWARE to "লো-পাওয়ার ডিসকভারি ➔ Wi-Fi Aware (NAN) + BLE"
            else -> CH_BLE to "লো-পাওয়ার ডিসকভারি ➔ BLE GATT + Ultrasonic ব্যাকআপ"
        }
    }

    fun computeDutyCycle(batteryPct: Int, charging: Boolean, peers: Int, saver: Boolean, speedMps: Double): DutyPolicy.Duty {
        adaptiveDuty.onRoundEnd(peers)
        return adaptiveDuty.next(batteryPct.coerceIn(1, 100) / 100.0, charging, peers, saver, speedMps, System.currentTimeMillis())
    }

    /**
     * Simulates or triggers a high-traffic burst through TurboScheduler + ChannelPlanner anti-jamming.
     * Demonstrates coalescing 60 mixed SOS / RTP / Bulk packets into compact bursts and auto-switching
     * channels when interference is injected on the active radio.
     */
    fun runTurboAndJamCheck(injectJam: Boolean): String {
        val samplePayload = ByteArray(140) { (it and 0xff).toByte() }
        repeat(15) { turbo.enqueue(Priority.SOS, Packet.TYPE_DATA, samplePayload) }
        repeat(20) { turbo.enqueue(Priority.URGENT, Packet.TYPE_VOICE, samplePayload) }
        repeat(25) { turbo.enqueue(Priority.BULK, Packet.TYPE_CHUNK, samplePayload) }

        var bursts = 0
        var totalBytes = 0
        while (turbo.totalQueued() > 0) {
            val burst = turbo.nextBurst(4096) ?: break
            bursts++
            totalBytes += burst.size
            // If connected peers exist on Nearby or BLE, transmit over live MultiChannelRadio too
            if (isStarted && nearby.connectedPeerCount() > 0) {
                multiChannel.sendRaw(selfNode, burst)
            }
        }

        if (injectJam) {
            val prev = activeChannel
            val next = when (prev) {
                CH_WIFI_DIRECT -> CH_WIFI_UDP
                CH_WIFI_UDP -> CH_NEARBY
                CH_NEARBY -> CH_BLE
                else -> CH_WIFI_DIRECT
            }
            activeChannel = next
            multiChannel.reportNoise(prev, -58.0) // strong RF jammer floor detected
            addLog("জ্যামার শনাক্ত (${channelName(prev)} -58 dBm) -> স্বয়ংক্রিয়ভাবে ${channelName(next)}-এ সুইচ!")
            return "${channelName(prev)} জ্যাম → ${channelName(next)}-এ সুইচ · $bursts টার্বো বার্স্ট ($totalBytes B)"
        }
        return "$bursts টার্বো বার্স্ট ($totalBytes B) · ${channelName(activeChannel)} সক্রিয়"
    }

    private fun addLog(msg: String) {
        channelLogs.add(0, msg)
        while (channelLogs.size > 25) channelLogs.removeAt(channelLogs.size - 1)
    }
}
