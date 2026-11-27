package com.herabarman.meshnet.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import com.herabarman.meshnet.android.AndroidImageCodec
import com.herabarman.meshnet.android.HotspotHelper
import com.herabarman.meshnet.core.Clock
import com.herabarman.meshnet.core.ContactCard
import com.herabarman.meshnet.core.FecCodec
import com.herabarman.meshnet.core.GatewayManager
import com.herabarman.meshnet.core.HybridKeyExchange
import com.herabarman.meshnet.core.MeshConfig
import com.herabarman.meshnet.core.MeshNode
import com.herabarman.meshnet.core.NodeInfo
import com.herabarman.meshnet.core.NoopCrypto
import com.herabarman.meshnet.core.PayloadCompressor
import com.herabarman.meshnet.core.Transport
import com.herabarman.meshnet.kit.IdentityKeys
import com.herabarman.meshnet.kit.IdentityRecordAuth
import com.herabarman.meshnet.kit.LostCrypto
import com.herabarman.meshnet.kit.LostPhone
import com.herabarman.meshnet.kit.PhotoSender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Random

data class LostSightingUi(
    val lat: Double,
    val lon: Double,
    val accuracyM: Int,
    val timeMs: Long,
    val own: Boolean,
    val slot: Long
)

data class LostPhoneUiState(
    val armed: Boolean = true,
    val autoArmEnabled: Boolean = true,
    val backupSecretHex: String = "",
    val sightings: List<LostSightingUi> = emptyList(),
    val reportsRelayed: Int = 0,
    val beaconsSent: Int = 0,
    val trackerAlertCount: Int = 0,
    val lastCommandExecuted: String = "কোনো রিমোট কমান্ড নেই",
    val status: String = "অফলাইন LostPhone প্রস্তুত (15m রোলিং বেকন)"
)

data class PhotoCompressUiState(
    val sourceLabel: String = "নমুনা 2400×1600 ক্যামেরা ছবি",
    val originalBytes: Int = 0,
    val compressedBytes: Int = 0,
    val savedPct: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val ssim: Double = 0.0,
    val quality: Int = 0,
    val fecTotalShards: Int = 6,
    val exifStripped: Boolean = true,
    val status: String = "গ্যালারি বা নমুনা ছবি কম্প্রেস করুন (SSIM >= 0.965)"
)

data class ContactQrUiState(
    val myCardUri: String = "",
    val safetyNumber: String = "",
    val verifiedPeerUser: String = "",
    val cardVerified: Boolean = false,
    val peerRevoked: Boolean = false,
    val status: String = "QR ContactCard ও Safety Number যাচাইযোগ্য"
)

data class GatewayPolicyUiState(
    val batteryPct: Int = 65,
    val isLeafMode: Boolean = false,
    val isGatewayElected: Boolean = true,
    val electedGatewayIds: List<Int> = listOf(101),
    val gatewayScore: Double = 0.85,
    val status: String = "Gateway সক্রিয় (ব্যাটারি >= 20%)"
)

data class MeshServicesUiState(
    val lostPhone: LostPhoneUiState = LostPhoneUiState(),
    val photo: PhotoCompressUiState = PhotoCompressUiState(),
    val contact: ContactQrUiState = ContactQrUiState(),
    val gateway: GatewayPolicyUiState = GatewayPolicyUiState(),
    val hotspotLanIp: String = ""
)

class MeshServicesEngine(private val context: Context) {
    private val _state = MutableStateFlow(MeshServicesUiState())
    val state: StateFlow<MeshServicesUiState> = _state.asStateFlow()

    // LostPhone instances: myDevice (target/armed) & finder/owner relay pair
    private val manualClock = object : Clock {
        var now: Long = System.currentTimeMillis()
        override fun nowMs(): Long = now
        override fun schedule(delayMs: Long, task: Runnable) {}
        fun advance(dtMs: Long) { now += dtMs }
    }
    private val ownerKeys = IdentityKeys.generate()
    private val ownerAuth = IdentityRecordAuth(ownerKeys)
    private val peerKeys = IdentityKeys.generate()
    private val peerAuth = IdentityRecordAuth(peerKeys)
    private val myHybrid = HybridKeyExchange.generateIdentity()
    private val peerHybrid = HybridKeyExchange.generateIdentity()
    private val revocations = ContactCard.RevocationList()
    private var lastVerifiedPeerCard: ContactCard? = null

    private lateinit var targetLostPhone: LostPhone
    private lateinit var finderLostPhone: LostPhone
    private var armedSecretBytes: ByteArray = ByteArray(0)
    private val hotspotHelper = HotspotHelper(context)

    // Gateway policy backed by real MeshNode + GatewayManager
    private val localTransport = object : Transport {
        private var l: Transport.Listener? = null
        override fun setListener(listener: Transport.Listener) { l = listener }
        override fun send(peerId: Int, data: ByteArray) {}
    }
    private val gatewayNodeInfo = NodeInfo(101, 0, 0.65, 0.92, true)
    private val gatewayMeshNode: MeshNode
    private val gatewayManager = GatewayManager(0.4, 0.3, 0.3, 0.20, 0.1)

    init {
        val cfg = MeshConfig().apply {
            discovery = true
            energyAware = true
            lowBattery = 0.20
            batteryHysteresis = 0.05
        }
        gatewayMeshNode = MeshNode(gatewayNodeInfo, cfg, localTransport, manualClock, null, NoopCrypto(), null, Random(42))
        setupLostPhones("MeshMasterSeed-2026", "primary-phone")
        initContactCardAndSafety()
        updateBatteryAndGateway(65, charging = false, manualGatewayToggle = true)
        _state.update { it.copy(hotspotLanIp = hotspotHelper.bestLocalIpv4()) }
    }

    private fun setupLostPhones(seedPhrase: String, deviceLabel: String) {
        armedSecretBytes = LostPhone.deriveOwnerSecret(seedPhrase.toByteArray(StandardCharsets.UTF_8), deviceLabel)
        var targetRef: LostPhone? = null
        var finderRef: LostPhone? = null

        val targetLink = object : LostPhone.Link {
            override fun send(peer: Int, message: ByteArray) {
                if (peer == 202) finderRef?.onMessage(101, message)
            }
            override fun peers(): List<Int> = listOf(202)
        }
        val finderLink = object : LostPhone.Link {
            override fun send(peer: Int, message: ByteArray) {
                if (peer == 101) targetRef?.onMessage(202, message)
            }
            override fun peers(): List<Int> = listOf(101)
        }

        targetLostPhone = LostPhone(
            101,
            LostPhone.Config(),
            manualClock,
            Random(101),
            targetLink,
            LostPhone.Where { LostPhone.Fix(23.8103, 90.4125, 12, manualClock.nowMs()) },
            null
        )
        finderLostPhone = LostPhone(
            202,
            LostPhone.Config(),
            manualClock,
            Random(202),
            finderLink,
            LostPhone.Where { LostPhone.Fix(23.8148, 90.4192, 18, manualClock.nowMs()) },
            null
        )
        targetRef = targetLostPhone
        finderRef = finderLostPhone

        targetLostPhone.armWithControlKey(armedSecretBytes, ownerAuth, ownerKeys.publicRaw())
        finderLostPhone.search(armedSecretBytes)

        targetLostPhone.setCommandHandler { code, nonce, _ ->
            val label = when (code) {
                LostPhone.CMD_RING -> "RING (সাইরেন বাজানো যাচাইকৃত · Nonce #${nonce and 0xFFFF})"
                LostPhone.CMD_LOCK -> "LOCK (ডিভাইস লক যাচাইকৃত · Nonce #${nonce and 0xFFFF})"
                LostPhone.CMD_WIPE -> "WIPE (কী ও ডেটা ওয়াইপ যাচাইকৃত · Nonce #${nonce and 0xFFFF})"
                else -> "CMD #$code"
            }
            _state.update {
                it.copy(
                    lostPhone = it.lostPhone.copy(
                        lastCommandExecuted = label,
                        status = "যাচাইকৃত রিমোট কমান্ড কার্যকর: $label"
                    )
                )
            }
        }
        targetLostPhone.setAntiStalkingListener { peerId, slotsHeard, _ ->
            _state.update {
                it.copy(
                    lostPhone = it.lostPhone.copy(
                        trackerAlertCount = targetLostPhone.trackerAlertsRaised,
                        status = "অ্যান্টি-স্টকিং সতর্কতা: অজানা বেকন #$peerId ($slotsHeard স্লট ধরে অনুসরণ করছে!)"
                    )
                )
            }
        }
        refreshLostPhoneState("অফলাইন LostPhone সক্রিয় · সিক্রেট ব্যাকআপ প্রস্তুত")
    }

    fun toggleLostArm(autoArm: Boolean) {
        if (targetLostPhone.armed()) {
            targetLostPhone.disarm()
        } else {
            targetLostPhone.armWithControlKey(armedSecretBytes, ownerAuth, ownerKeys.publicRaw())
        }
        _state.update {
            it.copy(
                lostPhone = it.lostPhone.copy(
                    armed = targetLostPhone.armed(),
                    autoArmEnabled = autoArm,
                    status = if (targetLostPhone.armed()) "ফোন সশস্ত্র (Armed) — বেকন সক্রিয়" else "ফোন নিরস্ত্র (Disarmed)"
                )
            )
        }
    }

    fun regenerateDerivedSecret(seedPhrase: String) {
        val clean = seedPhrase.ifBlank { "MeshMasterSeed-2026" }
        setupLostPhones(clean, "primary-phone")
    }

    fun triggerLastGaspAndNeighborSighting() {
        if (!targetLostPhone.armed()) {
            targetLostPhone.armWithControlKey(armedSecretBytes, ownerAuth, ownerKeys.publicRaw())
        }
        // Broadcast pre-shutdown last gasp (beacon + own encrypted fix) -> finder receives & opens sighting
        targetLostPhone.onShutdownLastGasp()
        // Advance 6 minutes and send another neighbor beacon so trail shows both own & finder fixes
        manualClock.advance(6 * 60_000L)
        val slotPub = LostCrypto.slotPublic(armedSecretBytes, manualClock.nowMs() / LostPhone.Config().slotMs)
        val beacon = ByteArray(1 + LostCrypto.POINT)
        beacon[0] = LostPhone.OP_BEACON.toByte()
        System.arraycopy(slotPub, 0, beacon, 1, LostCrypto.POINT)
        finderLostPhone.onMessage(101, beacon)
        refreshLostPhoneState("Last-Gasp ও নিকটস্থ পিয়ার সাইটিং সংযুক্ত (${finderLostPhone.sightings().size}টি লোকেশন)")
    }

    fun sendSignedLostCommand(commandCode: Int) {
        if (!targetLostPhone.armed()) {
            targetLostPhone.armWithControlKey(armedSecretBytes, ownerAuth, ownerKeys.publicRaw())
        }
        finderLostPhone.sendRemoteCommand(commandCode, ownerAuth)
        refreshLostPhoneState()
    }

    fun simulateUnfamiliarStalkerBeacons() {
        val stalkerSeed = "unknown-tracker-909".toByteArray(StandardCharsets.UTF_8)
        for (slot in 1L..3L) {
            manualClock.advance(16 * 60_000L)
            val currentSlot = manualClock.nowMs() / LostPhone.Config().slotMs
            val pub = LostCrypto.slotPublic(stalkerSeed, currentSlot)
            val beacon = ByteArray(1 + LostCrypto.POINT)
            beacon[0] = LostPhone.OP_BEACON.toByte()
            System.arraycopy(pub, 0, beacon, 1, LostCrypto.POINT)
            targetLostPhone.onMessage(909, beacon)
        }
        refreshLostPhoneState()
    }

    private fun refreshLostPhoneState(customStatus: String? = null) {
        val sightings = finderLostPhone.sightings().map {
            LostSightingUi(it.lat, it.lon, it.accuracyM, it.timeMs, it.fromLostPhoneItself, it.slot)
        }
        _state.update {
            it.copy(
                lostPhone = it.lostPhone.copy(
                    armed = targetLostPhone.armed(),
                    backupSecretHex = toHex(armedSecretBytes).take(24) + "...",
                    sightings = sightings,
                    reportsRelayed = finderLostPhone.reportsRelayed + targetLostPhone.reportsMade,
                    beaconsSent = targetLostPhone.beaconsSent,
                    trackerAlertCount = targetLostPhone.trackerAlertsRaised,
                    status = customStatus ?: it.lostPhone.status
                )
            )
        }
    }

    // ---- Visually-Lossless Ultra-Compact Photo Compressor ----

    fun compressPickedPhoto(uri: Uri) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                _state.update { it.copy(photo = it.photo.copy(status = "ছবি পড়া যায়নি")) }
                return
            }
            compressRawPhoto(bytes, "গ্যালারি ছবি")
        } catch (e: Exception) {
            _state.update { it.copy(photo = it.photo.copy(status = "ত্রুটি: ${e.message}")) }
        }
    }

    fun compressSynthetic4kSample() {
        val bmp = Bitmap.createBitmap(2400, 1600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.rgb(16, 28, 52)
        canvas.drawRect(0f, 0f, 2400f, 1600f, p)
        p.color = Color.rgb(46, 204, 150)
        canvas.drawCircle(720f, 680f, 420f, p)
        p.color = Color.rgb(74, 163, 255)
        canvas.drawCircle(1550f, 920f, 460f, p)
        p.color = Color.WHITE
        p.textSize = 96f
        canvas.drawText("MeshNet 2400x1600 HD Sample", 420f, 1420f, p)
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
        bmp.recycle()
        compressRawPhoto(baos.toByteArray(), "নমুনা 2400×1600 HD ছবি")
    }

    private fun compressRawPhoto(bytes: ByteArray, label: String) {
        val codec = AndroidImageCodec()
        val result = PhotoSender.compressVisuallyLossless(bytes, codec, 120 * 1024)
        if (result == null) {
            _state.update { it.copy(photo = it.photo.copy(status = "অবৈধ ছবি ফরম্যাট")) }
            return
        }
        val packed = PayloadCompressor.compress(result.bytes)
        val fec = FecCodec.encode(packed, 4, 2)
        val saved = if (result.originalBytes > 0) {
            ((1.0 - result.bytes.size.toDouble() / result.originalBytes) * 100.0).toInt().coerceIn(0, 99)
        } else 0
        _state.update {
            it.copy(
                photo = PhotoCompressUiState(
                    sourceLabel = label,
                    originalBytes = result.originalBytes,
                    compressedBytes = result.bytes.size,
                    savedPct = saved,
                    width = result.width,
                    height = result.height,
                    ssim = result.ssim,
                    quality = result.quality,
                    fecTotalShards = fec.shards.size,
                    exifStripped = !result.usedOriginal,
                    status = String.format(
                        Locale.ROOT,
                        "%d KB ➔ %d KB (%d%% সাশ্রয়) · SSIM %.4f · EXIF মুক্ত · %d FEC শার্ড",
                        result.originalBytes / 1024,
                        result.bytes.size / 1024,
                        saved,
                        result.ssim,
                        fec.shards.size
                    )
                )
            )
        }
    }

    // ---- QR ContactCard & 60-Digit Safety Number Verifier ----

    private fun initContactCardAndSafety() {
        val myCard = ContactCard.createSigned(
            "mesh-user-101",
            101,
            0,
            ownerAuth,
            myHybrid.x25519Pub,
            myHybrid.mlKemEk,
            manualClock.nowMs()
        )
        val peerCard = ContactCard.createSigned(
            "mesh-peer-202",
            202,
            0,
            peerAuth,
            peerHybrid.x25519Pub,
            null,
            manualClock.nowMs()
        )
        lastVerifiedPeerCard = peerCard
        val safety = ContactCard.safetyNumber(ownerKeys.publicRaw(), peerCard.identityPub)
        _state.update {
            it.copy(
                contact = ContactQrUiState(
                    myCardUri = myCard.encodeUri(),
                    safetyNumber = safety,
                    verifiedPeerUser = peerCard.userId,
                    cardVerified = peerCard.verify(ownerAuth, revocations),
                    peerRevoked = revocations.isRevoked(peerCard.identityPub),
                    status = "স্বাক্ষরিত ContactCard ও 60-ডিজিট Safety Number প্রস্তুত"
                )
            )
        }
    }

    fun verifyContactCardUri(uri: String) {
        val parsed = ContactCard.decodeUri(uri.trim())
        if (parsed == null) {
            _state.update {
                it.copy(contact = it.contact.copy(cardVerified = false, status = "অবৈধ ContactCard URI ফরম্যাট"))
            }
            return
        }
        val valid = parsed.verify(ownerAuth, revocations)
        val revoked = revocations.isRevoked(parsed.identityPub)
        val safety = ContactCard.safetyNumber(ownerKeys.publicRaw(), parsed.identityPub)
        lastVerifiedPeerCard = parsed
        _state.update {
            it.copy(
                contact = it.contact.copy(
                    safetyNumber = safety,
                    verifiedPeerUser = parsed.userId,
                    cardVerified = valid,
                    peerRevoked = revoked,
                    status = when {
                        revoked -> "প্রত্যাখ্যাত (Revoked): ${parsed.userId}-এর কী বাতিল করা হয়েছে!"
                        valid -> "যাচাইকৃত কন্টাক্ট: ${parsed.userId} (Node #${parsed.nodeId}) · X25519 + ML-KEM পিন्ड"
                        else -> "সতর্কতা: ContactCard স্বাক্ষর মেলেনি (সম্ভাব্য টেম্পারিং)!"
                    }
                )
            )
        }
    }

    fun revokePeerKey() {
        val target = lastVerifiedPeerCard ?: return
        val sig = ContactCard.RevocationList.signRevocation(peerAuth, "device-lost-or-rotated")
        val ok = revocations.revoke(target.identityPub, "device-lost-or-rotated", ownerAuth, sig)
        _state.update {
            it.copy(
                contact = it.contact.copy(
                    peerRevoked = revocations.isRevoked(target.identityPub),
                    cardVerified = target.verify(ownerAuth, revocations),
                    status = if (ok) "${target.userId}-এর কী স্বাক্ষরিত RevocationList-এ ব্লক হয়েছে" else "রিভোকেশন ব্যর্থ"
                )
            )
        }
    }

    // ---- Gateway Policy & Battery Cutoff ----

    fun updateBatteryAndGateway(batteryPct: Int, charging: Boolean = false, manualGatewayToggle: Boolean = true) {
        val clamped = batteryPct.coerceIn(5, 100)
        val level = clamped / 100.0
        gatewayNodeInfo.charging = charging
        gatewayMeshNode.setBattery(level)

        val peer102 = NodeInfo(102, 0, 0.78, 0.88, true)
        val peer103 = NodeInfo(103, 0, 0.42, 0.71, false)
        val members = listOf(gatewayNodeInfo, peer102, peer103)
        val elected = gatewayManager.elect(members, 1)
        val amGateway = manualGatewayToggle && !gatewayMeshNode.isLeaf && elected.contains(101)
        gatewayMeshNode.setGateway(amGateway)

        val score = gatewayManager.score(gatewayNodeInfo)
        val status = when {
            gatewayMeshNode.isLeaf -> "ব্যাটারি <20%: Leaf Mode চালু (রিলে ও গেটওয়ে বন্ধ) · বিকল্প গেটওয়ে #${elected.firstOrNull()}"
            amGateway -> "Gateway সক্রিয় (#101 নির্বাচিত · Score ${String.format(Locale.ROOT, "%.2f", score)})"
            else -> "Gateway নিষ্ক্রিয় — নির্বাচিত গেটওয়ে #${elected.firstOrNull()}"
        }
        _state.update {
            it.copy(
                gateway = GatewayPolicyUiState(
                    batteryPct = clamped,
                    isLeafMode = gatewayMeshNode.isLeaf,
                    isGatewayElected = gatewayMeshNode.isGateway,
                    electedGatewayIds = elected,
                    gatewayScore = score,
                    status = status
                )
            )
        }
    }

    private fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
}
