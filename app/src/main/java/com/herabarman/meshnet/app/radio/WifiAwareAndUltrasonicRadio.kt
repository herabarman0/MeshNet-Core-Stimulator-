package com.herabarman.meshnet.app.radio

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Wi-Fi Aware (NAN - Neighbor Awareness Networking, API 26+) discovery + messaging helper.
 * Enables zero-AP, zero-internet peer discovery and L2 discovery frames alongside BLE & Wi-Fi Direct.
 */
class WifiAwareDiscovery(
    private val context: Context,
    private val selfNode: Int
) {
    @Volatile
    var supported: Boolean = false
        private set

    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    var discoveredPeers: Int = 0
        private set

    @Volatile
    var status: String = "Wi-Fi Aware প্রস্তুত"
        private set

    private var session: WifiAwareSession? = null
    private var pubSession: PublishDiscoverySession? = null
    private var subSession: SubscribeDiscoverySession? = null
    private val main = Handler(Looper.getMainLooper())

    init {
        supported = Build.VERSION.SDK_INT >= 26 &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
        if (!supported) {
            status = "Wi-Fi Aware হার্ডওয়্যার নেই (BLE/Wi-Fi Direct ফলব্যাক)"
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!supported || Build.VERSION.SDK_INT < 26) return
        val mgr = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager ?: return
        if (!mgr.isAvailable) {
            status = "Wi-Fi Aware নিষ্ক্রিয় (Wi-Fi চালু করুন)"
            return
        }
        try {
            mgr.attach(object : AttachCallback() {
                override fun onAttached(awareSession: WifiAwareSession) {
                    session = awareSession
                    active = true
                    status = "Wi-Fi Aware NAN সক্রিয়"
                    startPublishAndSubscribe(awareSession)
                }

                override fun onAttachFailed() {
                    active = false
                    status = "Wi-Fi Aware অ্যাট্যাচ ব্যর্থ"
                }
            }, main)
        } catch (_: SecurityException) {
            status = "Wi-Fi Aware অনুমতি প্রয়োজন"
        } catch (e: Exception) {
            status = "Wi-Fi Aware: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPublishAndSubscribe(aware: WifiAwareSession) {
        if (Build.VERSION.SDK_INT < 26) return
        val infoBytes = "MeshNet#$selfNode".toByteArray(StandardCharsets.UTF_8)
        val pubCfg = PublishConfig.Builder()
            .setServiceName("meshnet_nan_v1")
            .setServiceSpecificInfo(infoBytes)
            .build()
        aware.publish(pubCfg, object : DiscoverySessionCallback() {
            override fun onPublishStarted(s: PublishDiscoverySession) {
                pubSession = s
            }
        }, main)

        val subCfg = SubscribeConfig.Builder()
            .setServiceName("meshnet_nan_v1")
            .build()
        aware.subscribe(subCfg, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(s: SubscribeDiscoverySession) {
                subSession = s
            }

            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray?,
                matchFilter: MutableList<ByteArray>?
            ) {
                discoveredPeers++
                status = "NAN পিয়ার শনাক্ত ($discoveredPeers টি)"
            }
        }, main)
    }

    fun stop() {
        active = false
        try { pubSession?.close() } catch (_: Exception) { }
        try { subSession?.close() } catch (_: Exception) { }
        try { session?.close() } catch (_: Exception) { }
        pubSession = null
        subSession = null
        session = null
    }
}

/**
 * Near-Field Ultrasonic BFSK Discovery Beacon (18.2 kHz = '0', 19.0 kHz = '1', 44.1 kHz PCM).
 * Encodes (nodeId + CRC8) into a short ~220ms near-inaudible acoustic chirp and includes a
 * Goertzel DFT matched-filter demodulator so nearby phones can pair offline without BT/Wi-Fi.
 */
object UltrasonicChirpBeacon {
    private const val SAMPLE_RATE = 44_100
    private const val FREQ_ZERO = 18_200.0
    private const val FREQ_ONE = 19_000.0
    private const val SAMPLES_PER_BIT = 220 // ~5 ms/bit -> ~200 bps

    fun modulateNodeBeacon(nodeId: Int): ShortArray {
        val frame = ByteBuffer.allocate(5).putInt(nodeId).put(((nodeId xor (nodeId ushr 8) xor 0x5A) and 0xFF).toByte()).array()
        val totalBits = frame.size * 8
        val pcm = ShortArray(totalBits * SAMPLES_PER_BIT)
        var idx = 0
        for (b in frame) {
            val v = b.toInt() and 0xFF
            for (bit in 7 downTo 0) {
                val isOne = ((v ushr bit) and 1) == 1
                val freq = if (isOne) FREQ_ONE else FREQ_ZERO
                for (s in 0 until SAMPLES_PER_BIT) {
                    val env = if (s < 20) s / 20.0 else if (s > SAMPLES_PER_BIT - 20) (SAMPLES_PER_BIT - s) / 20.0 else 1.0
                    val sample = sin(2.0 * PI * freq * (idx + s) / SAMPLE_RATE) * env * 12000.0
                    pcm[idx + s] = sample.toInt().coerceIn(-32767, 32767).toShort()
                }
                idx += SAMPLES_PER_BIT
            }
        }
        return pcm
    }

    fun demodulateNodeBeacon(pcm: ShortArray): Int? {
        if (pcm.size < 40 * SAMPLES_PER_BIT) return null
        val bytes = ByteArray(5)
        for (byteIdx in 0 until 5) {
            var acc = 0
            for (bit in 0 until 8) {
                val offset = (byteIdx * 8 + bit) * SAMPLES_PER_BIT
                val p0 = goertzelPower(pcm, offset, SAMPLES_PER_BIT, FREQ_ZERO)
                val p1 = goertzelPower(pcm, offset, SAMPLES_PER_BIT, FREQ_ONE)
                acc = (acc shl 1) or (if (p1 > p0) 1 else 0)
            }
            bytes[byteIdx] = acc.toByte()
        }
        val bb = ByteBuffer.wrap(bytes)
        val nodeId = bb.getInt()
        val crc = bb.get().toInt() and 0xFF
        val expected = (nodeId xor (nodeId ushr 8) xor 0x5A) and 0xFF
        return if (crc == expected) nodeId else null
    }

    fun emitChirpAsync(nodeId: Int): Boolean {
        val pcm = modulateNodeBeacon(nodeId)
        return try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    track.stop()
                    track.release()
                } catch (_: Exception) { }
            }, 350L)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun goertzelPower(pcm: ShortArray, offset: Int, len: Int, targetFreq: Double): Double {
        val omega = 2.0 * PI * targetFreq / SAMPLE_RATE
        val coeff = 2.0 * cos(omega)
        var sPrev = 0.0
        var sPrev2 = 0.0
        for (i in 0 until len) {
            val s = pcm[offset + i].toDouble() + coeff * sPrev - sPrev2
            sPrev2 = sPrev
            sPrev = s
        }
        return sPrev2 * sPrev2 + sPrev * sPrev - coeff * sPrev * sPrev2
    }
}
