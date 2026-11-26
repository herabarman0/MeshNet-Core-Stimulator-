package com.herabarman.meshnet.app.radio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.herabarman.meshnet.core.TurboScheduler
import com.herabarman.meshnet.kit.BulkLink
import com.herabarman.meshnet.kit.RawRadio
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Real Google Nearby Connections P2P_CLUSTER RawRadio + BulkLink (FILE upgrades automatically
 * to Wi-Fi Direct / Wi-Fi Aware / Wi-Fi Hotspot inside Google Play Services Nearby).
 */
class NearbyConnectionsRadio(
    context: Context,
    private val selfNode: Int
) : RawRadio, BulkLink {

    companion object {
        private const val TAG = "NearbyConnectionsRadio"
        private const val SERVICE_ID = "com.herabarman.meshnet.mesh"
        private const val MAX_BYTES = 32 * 1024
        private const val MAX_BULK = 32L * 1024 * 1024
        private const val PING_MS = 10_000L
        private const val DEAD_MS = 35_000L
        private val PING = byteArrayOf(0)
    }

    private val ctx = context.applicationContext
    private val nearby: ConnectionsClient = Nearby.getConnectionsClient(ctx)
    private val myName = selfNode.toString(36)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bulkIo = Executors.newSingleThreadExecutor()

    @Volatile
    private var rawHandler: RawRadio.Handler? = null

    @Volatile
    private var bulkRx: BulkLink.Receiver? = null

    @Volatile
    var isActive: Boolean = false
        private set

    @Volatile
    var lastStatus: String = "Idle"
        private set

    @Volatile
    var packetsSent: Long = 0L
        private set

    @Volatile
    var packetsReceived: Long = 0L
        private set

    private val discovered = ConcurrentHashMap<String, Int>()
    private val connected = ConcurrentHashMap<String, Int>()
    private val pending = ConcurrentHashMap<String, Int>()
    private val endpointOf = ConcurrentHashMap<Int, String>()
    private val lastRx = ConcurrentHashMap<String, Long>()
    private val bulkOut = ConcurrentHashMap<Long, BulkLink.Done>()
    private val bulkFiles = ConcurrentHashMap<Long, File>()
    private val bulkIn = ConcurrentHashMap<Long, Payload>()
    private val bulkInNode = ConcurrentHashMap<Long, Int>()

    fun connectedPeerCount(): Int = endpointOf.size

    override fun setHandler(h: RawRadio.Handler?) {
        rawHandler = h
    }

    override fun start() {
        if (isActive) return
        isActive = true
        lastStatus = "Nearby Advertising + Discovery শুরু হচ্ছে..."
        try {
            val advOpt = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
            nearby.startAdvertising(myName, SERVICE_ID, lifecycle, advOpt)
                .addOnSuccessListener { lastStatus = "Nearby P2P_CLUSTER সক্রিয়" }
                .addOnFailureListener { e ->
                    if ((e as? ApiException)?.statusCode != ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING) {
                        lastStatus = "Nearby Adv: ${e.localizedMessage}"
                        Log.w(TAG, "adv failed", e)
                    }
                }

            val discOpt = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
            nearby.startDiscovery(SERVICE_ID, discoveryCallback, discOpt)
                .addOnFailureListener { e ->
                    if ((e as? ApiException)?.statusCode != ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) {
                        Log.w(TAG, "discovery failed", e)
                    }
                }
            mainHandler.postDelayed(pingRunnable, PING_MS)
        } catch (e: SecurityException) {
            isActive = false
            lastStatus = "Nearby অনুমতি প্রয়োজন"
        }
    }

    override fun stop() {
        isActive = false
        mainHandler.removeCallbacksAndMessages(null)
        try { nearby.stopAdvertising() } catch (_: Exception) { }
        try { nearby.stopDiscovery() } catch (_: Exception) { }
        try { nearby.stopAllEndpoints() } catch (_: Exception) { }
        discovered.clear()
        connected.clear()
        pending.clear()
        endpointOf.clear()
        lastRx.clear()
        lastStatus = "বন্ধ"
    }

    override fun sendRaw(peerNode: Int, data: ByteArray) {
        if (!isActive || data.size > MAX_BYTES) return
        val ep = endpointOf[peerNode] ?: return
        nearby.sendPayload(ep, Payload.fromBytes(data))
        packetsSent++
    }

    override fun drop(peerNode: Int) {
        val ep = endpointOf[peerNode] ?: return
        try { nearby.disconnectFromEndpoint(ep) } catch (_: Exception) { }
        linkDown(ep)
    }

    override fun canSend(node: Int): Boolean = isActive && endpointOf.containsKey(node)
    override fun setReceiver(r: BulkLink.Receiver?) { bulkRx = r }

    override fun send(node: Int, data: ByteArray, cb: BulkLink.Done) {
        val ep = endpointOf[node]
        if (ep == null || data.isEmpty() || data.size > MAX_BULK) {
            cb.onDone(false)
            return
        }
        bulkIo.execute {
            var file: File? = null
            try {
                file = File.createTempFile("mesh-nearby-", ".bin", ctx.cacheDir)
                FileOutputStream(file).use { it.write(data) }
                val payload = Payload.fromFile(file)
                bulkOut[payload.id] = cb
                bulkFiles[payload.id] = file
                nearby.sendPayload(ep, payload).addOnFailureListener {
                    finishBulkOut(payload.id, false)
                }
            } catch (e: Exception) {
                file?.delete()
                cb.onDone(false)
            }
        }
    }

    private fun finishBulkOut(id: Long, ok: Boolean) {
        bulkFiles.remove(id)?.delete()
        bulkOut.remove(id)?.onDone(ok)
    }

    private fun parseNode(name: String): Int = try {
        val n = name.toInt(36)
        if (n > 0) n else -1
    } catch (_: NumberFormatException) {
        -1
    }

    private fun linkDown(endpointId: String) {
        val node = connected.remove(endpointId)
        pending.remove(endpointId)
        lastRx.remove(endpointId)
        if (node != null && endpointOf[node] == endpointId) {
            endpointOf.remove(node)
            rawHandler?.onLinkDown(node)
        }
    }

    private val pingRunnable = object : Runnable {
        override fun run() {
            if (!isActive) return
            val now = System.currentTimeMillis()
            for (ep in connected.keys.toList()) {
                val t = lastRx[ep]
                if (t != null && now - t > DEAD_MS) {
                    try { nearby.disconnectFromEndpoint(ep) } catch (_: Exception) { }
                    linkDown(ep)
                } else {
                    try { nearby.sendPayload(ep, Payload.fromBytes(PING)) } catch (_: Exception) { }
                }
            }
            mainHandler.postDelayed(this, PING_MS)
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val peer = parseNode(info.endpointName)
            if (peer < 0 || peer == selfNode) return
            discovered[endpointId] = peer
            if (connected.containsKey(endpointId) || pending.containsKey(endpointId)) return
            val iInitiate = myName > peer.toString(36)
            mainHandler.postDelayed({
                if (isActive && discovered.containsKey(endpointId) && !connected.containsKey(endpointId)) {
                    pending[endpointId] = peer
                    nearby.requestConnection(myName, endpointId, lifecycle).addOnFailureListener {
                        pending.remove(endpointId)
                    }
                }
            }, if (iInitiate) 250L else 2500L)
        }

        override fun onEndpointLost(endpointId: String) {
            discovered.remove(endpointId)
            pending.remove(endpointId)
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val peer = parseNode(info.endpointName)
            if (peer < 0) {
                try { nearby.rejectConnection(endpointId) } catch (_: Exception) { }
                return
            }
            pending[endpointId] = peer
            nearby.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val peer = pending.remove(endpointId)
            if (result.status.isSuccess && peer != null) {
                endpointOf.put(peer, endpointId)?.let { old ->
                    if (old != endpointId) {
                        connected.remove(old)
                        try { nearby.disconnectFromEndpoint(old) } catch (_: Exception) { }
                    }
                }
                connected[endpointId] = peer
                lastRx[endpointId] = System.currentTimeMillis()
                lastStatus = "Nearby যুক্ত: ${endpointOf.size} পীয়ার"
                rawHandler?.onLinkUp(peer)
            }
        }

        override fun onDisconnected(endpointId: String) {
            linkDown(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            lastRx[endpointId] = System.currentTimeMillis()
            val peer = connected[endpointId] ?: return
            when (payload.type) {
                Payload.Type.FILE -> {
                    if (bulkRx != null) {
                        bulkIn[payload.id] = payload
                        bulkInNode[payload.id] = peer
                    } else {
                        nearby.cancelPayload(payload.id)
                    }
                }
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    if (bytes.size == 1 && bytes[0] == 0.toByte()) return
                    packetsReceived++
                    for (frame in TurboScheduler.unpackBurst(bytes)) {
                        rawHandler?.onRaw(peer, frame)
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val id = update.payloadId
            val st = update.status
            if (bulkOut.containsKey(id)) {
                if (st == PayloadTransferUpdate.Status.SUCCESS) finishBulkOut(id, true)
                else if (st == PayloadTransferUpdate.Status.FAILURE || st == PayloadTransferUpdate.Status.CANCELED) finishBulkOut(id, false)
                return
            }
            if (bulkIn.containsKey(id) && st == PayloadTransferUpdate.Status.SUCCESS) {
                val payload = bulkIn.remove(id) ?: return
                val peer = bulkInNode.remove(id) ?: return
                val rx = bulkRx ?: return
                bulkIo.execute {
                    try {
                        val pfd = payload.asFile()?.asParcelFileDescriptor() ?: return@execute
                        val out = ByteArrayOutputStream()
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                if (out.size() > MAX_BULK) return@execute
                            }
                        }
                        rx.onReceived(peer, out.toByteArray())
                    } catch (e: Exception) {
                        Log.w(TAG, "bulk rx error", e)
                    }
                }
            }
        }
    }
}
