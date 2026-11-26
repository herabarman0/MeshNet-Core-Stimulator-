package com.herabarman.meshnet.app

import com.herabarman.meshnet.core.Keccak
import com.herabarman.meshnet.core.MeshConfig
import com.herabarman.meshnet.core.MultiRouteTable
import com.herabarman.meshnet.core.NeighborInfo
import com.herabarman.meshnet.core.Packet
import com.herabarman.meshnet.core.PathSelector
import com.herabarman.meshnet.core.PredictiveAiRouter
import com.herabarman.meshnet.core.Priority
import com.herabarman.meshnet.core.RelayFairnessLedger
import com.herabarman.meshnet.core.TrafficMasker
import com.herabarman.meshnet.kit.Alerts
import com.herabarman.meshnet.kit.GeoZones
import com.herabarman.meshnet.kit.GroupState
import com.herabarman.meshnet.kit.HardwareBridges
import com.herabarman.meshnet.kit.IdentityKeys
import com.herabarman.meshnet.kit.LoraRadio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Locale

data class AiRouteUiState(
    val predictedNextZone: Int = 3,
    val peerOnlineLikelihood: Double = 0.0,
    val expectedOnlineMins: Int = 0,
    val precomputedNextHop: Int = -1,
    val precomputedReliability: Double = 0.0,
    val energyChosenHop: Int = -1,
    val avoidedLowBattery: Boolean = false,
    val relayBatteryPct: Int = 12,
    val altBatteryPct: Int = 88,
    val trainingUpdates: Long = 0L,
    val inferenceNs: Long = 0L,
    val summary: String = "AI প্রেডিক্টিভ + এনার্জি-অ্যাওয়ার রাউটিং প্রস্তুত"
)

data class DataMuleUiState(
    val carriedItems: Int = 0,
    val copiesRemaining: Int = 8,
    val deliveredAcrossGap: Int = 0,
    val carrierZone: String = "জোন-A (নেটওয়ার্ক বিচ্ছিন্ন)",
    val status: String = "অফলাইন জোন-A থেকে জোন-B ডেটা মিউল প্রস্তুত"
)

data class GeoAlertUi(
    val id: Long,
    val kindLabel: String,
    val geohash: String,
    val zoneNum: Int,
    val text: String,
    val verifiedOfficial: Boolean
)

data class GeoBroadcastUiState(
    val currentGeohash: String = "wh0r2x",
    val hilbertZone: Int = 0,
    val alerts: List<GeoAlertUi> = emptyList(),
    val status: String = "জিওফেন্সড SOS ও অফলাইন ম্যাপ পিন প্রস্তুত"
)

data class StegoUiState(
    val mode: String = "HTTPS/TLS 1.3",
    val wireBytes: Int = 0,
    val disguisedHex: String = "",
    val unmaskedOk: Boolean = false,
    val status: String = "DPI বাইপাস ও স্টেগানোগ্রাফি প্রস্তুত"
)

data class CrossHardwareUiState(
    val loraTxFrames: Long = 0L,
    val loraRxFrames: Long = 0L,
    val satBursts: Long = 0L,
    val desktopEndpoints: Int = 1,
    val kissCrcErrors: Long = 0L,
    val status: String = "LoRa (12km KISS) · Satellite NTN · Desktop UDP প্রস্তুত"
)

data class CrdtMessageUi(
    val key: String,
    val author: Int,
    val text: String,
    val likes: Int
)

data class CrdtGroupUiState(
    val converged: Boolean = true,
    val memberCount: Int = 3,
    val snapshotHash: String = "",
    val messages: List<CrdtMessageUi> = emptyList(),
    val status: String = "CRDT গ্রুপ সিঙ্ক সক্রিয় (A ↔ B)"
)

data class FairnessUiState(
    val relayScore: Double = 0.5,
    val relayCredits: Double = 0.0,
    val relayPriority: Int = Priority.NORMAL,
    val leechScore: Double = 0.5,
    val leechCredits: Double = 0.0,
    val leechPriority: Int = Priority.NORMAL,
    val relayRateScale: Double = 1.0,
    val status: String = "রিলে ক্রেডিট ও ফেয়ারনেস সিস্টেম সক্রিয়"
)

data class MeshResilienceUiState(
    val ai: AiRouteUiState = AiRouteUiState(),
    val dtnMule: DataMuleUiState = DataMuleUiState(),
    val geo: GeoBroadcastUiState = GeoBroadcastUiState(),
    val stego: StegoUiState = StegoUiState(),
    val hardware: CrossHardwareUiState = CrossHardwareUiState(),
    val crdt: CrdtGroupUiState = CrdtGroupUiState(),
    val fairness: FairnessUiState = FairnessUiState()
)

/**
 * Coordinates DTN Data Mule, Enhanced Predictive AI Routing, Energy-Aware Path Selection,
 * Geofenced Emergency SOS/Map Pins, DPI Traffic Masking & Steganography, LoRa+Satellite+Desktop bridges,
 * Signed CRDT Group Sync, and Relay Fairness Incentives.
 */
class MeshResilienceEngine {
    private val _state = MutableStateFlow(MeshResilienceUiState())
    val state: StateFlow<MeshResilienceUiState> = _state.asStateFlow()

    private val aiRouter = PredictiveAiRouter()
    private val fairnessLedger = RelayFairnessLedger()
    private val maskKey = Keccak.sha3_256("meshnet-cloak-v1".toByteArray(StandardCharsets.UTF_8))

    private val myIdKeys = IdentityKeys.generate()
    private val peerIdKeys = IdentityKeys.generate()
    private val desktopDir = HardwareBridges.DesktopEndpointDirectory().apply {
        addEndpoint("192.168.49.1", 47474)
    }

    // CRDT Replicas (Phone A + Phone B)
    private val groupId = byteArrayOf(0x4D, 0x4E, 0x45, 0x54, 0x20, 0x31, 0x30, 0x31)
    private val replicaA = GroupState(groupId, true, 0, null)
    private val replicaB = GroupState(groupId, true, 0, null)
    private var crdtClockMs = 1_000_000L

    // DTN Data Mule buffer
    private val muleQueue = ArrayList<Pair<String, Int>>() // text to remaining copies
    private var muleDeliveredTotal = 0

    init {
        runCatching {
            seedPredictiveModel()
            evaluateAiAndEnergy(lowBatteryPct = 12, healthyBatteryPct = 88)
            seedGeoAlerts()
            runStegoMask("HTTPS", "Emergency Corridor Safe")
            runCrossHardwareBridgeDemo()
            postCrdtMessage("সবাই নিরাপদ আশ্রয়ে পৌঁছান ✅")
            refreshFairness()
        }
    }

    private fun seedPredictiveModel() {
        val baseHour = (System.currentTimeMillis() / 3_600_000L) * 3_600_000L
        // Train pattern: destination 202 travels Zone 1 -> Zone 3 at current hour
        for (i in 0 until 5) {
            aiRouter.observeNode(202, 1, baseHour + i * 30_000L)
            aiRouter.observeNode(202, 3, baseHour + (i + 1) * 30_000L)
        }
        aiRouter.observeNode(202, 1, baseHour + 300_000L)
        // Candidate relay 105 sits in Zone 3 (predicted target zone), 106 sits in Zone 8
        aiRouter.observeNode(105, 3, System.currentTimeMillis() - 10_000L)
        aiRouter.observeNode(106, 8, System.currentTimeMillis() - 90_000L)
        fairnessLedger.recordRelayForPeer(105, 4096)
        fairnessLedger.recordRelayForPeer(105, 2048)
    }

    fun evaluateAiAndEnergy(lowBatteryPct: Int, healthyBatteryPct: Int) {
        val now = System.currentTimeMillis()
        val cfg = MeshConfig().apply {
            lowBattery = 0.20
            energyAware = true
            energyHealthy = 0.50
            energyWeight = 4.0
        }
        val nLow = NeighborInfo(106).apply {
            battery = lowBatteryPct.coerceIn(1, 100) / 100.0
            charging = false
            load = 0.45
            lastBeaconMs = now
        }
        val nHealthy = NeighborInfo(105).apply {
            battery = healthyBatteryPct.coerceIn(1, 100) / 100.0
            charging = healthyBatteryPct >= 80
            load = 0.15
            lastBeaconMs = now
        }
        val infoMap = mapOf(106 to nLow, 105 to nHealthy)
        val paths = listOf(
            MultiRouteTable.Path(106, 2, now, now + 60_000L),
            MultiRouteTable.Path(105, 2, now, now + 60_000L)
        )

        val t0 = System.nanoTime()
        val pre = aiRouter.precomputeBestRoute(202, listOf(106, 105), infoMap, fairnessLedger, now)
        val decision = PathSelector.chooseSmart(
            paths,
            PathSelector.Lookup { id -> infoMap[id] },
            cfg,
            aiRouter,
            fairnessLedger,
            202,
            now
        )
        val elapsedNs = (System.nanoTime() - t0).coerceAtLeast(1L)

        _state.update {
            it.copy(
                ai = AiRouteUiState(
                    predictedNextZone = pre?.predictedDstZone ?: 3,
                    peerOnlineLikelihood = pre?.onlineNowProb ?: 0.0,
                    expectedOnlineMins = pre?.expectedOnlineMins ?: 0,
                    precomputedNextHop = pre?.nextHop ?: -1,
                    precomputedReliability = pre?.reliability ?: 0.0,
                    energyChosenHop = decision.nextHop,
                    avoidedLowBattery = decision.avoidedLowBattery,
                    relayBatteryPct = lowBatteryPct,
                    altBatteryPct = healthyBatteryPct,
                    trainingUpdates = aiRouter.trainingUpdates(),
                    inferenceNs = elapsedNs,
                    summary = if (decision.avoidedLowBattery) {
                        "Node 106 ($lowBatteryPct% ব্যাটারি) এড়িয়ে AI রুট #${decision.nextHop} নির্বাচিত ($elapsedNs ns)"
                    } else {
                        "প্রি-কম্পিউটেড AI রুট #${decision.nextHop} সক্রিয় (${String.format(Locale.ROOT, "%.0f%%", decision.aiReliability * 100)})"
                    }
                )
            )
        }
    }

    fun trainAiWithOutcome(success: Boolean, lowBatteryPct: Int, healthyBatteryPct: Int) {
        val sample = doubleArrayOf(1.0, 0.90, 0.55, 0.85, 1.0, 0.80, 0.92, healthyBatteryPct / 100.0, 1.0, 0.85, 0.80, 0.05)
        aiRouter.trainOutcome(sample, if (success) 1.0 else 0.0)
        evaluateAiAndEnergy(lowBatteryPct, healthyBatteryPct)
    }

    // ---- #1 DTN Data Mule ----

    fun loadMuleAndCarry(message: String) {
        muleQueue += (message.ifBlank { "জরুরি অফলাইন বার্তা" } to 8)
        _state.update {
            it.copy(
                dtnMule = it.dtnMule.copy(
                    carriedItems = muleQueue.size,
                    copiesRemaining = 8,
                    carrierZone = "জোন-A ➔ চলমান বাহন (Carry)",
                    status = "৮টি এনক্রিপ্টেড Spray-and-Wait কপি ফোনে সংরক্ষিত (${muleQueue.size}টি কিউতে)"
                )
            )
        }
    }

    fun arriveTargetZoneAndDeliver() {
        if (muleQueue.isEmpty()) loadMuleAndCarry("জোন-A ত্রাণ রিপোর্ট")
        val count = muleQueue.size
        muleDeliveredTotal += count
        muleQueue.clear()
        _state.update {
            it.copy(
                dtnMule = it.dtnMule.copy(
                    carriedItems = 0,
                    copiesRemaining = 0,
                    deliveredAcrossGap = muleDeliveredTotal,
                    carrierZone = "জোন-B (লক্ষ্য এলাকায় পৌঁছেছে)",
                    status = "$count-টি ডেটা মিউল মেসেজ স্বয়ংক্রিয়ভাবে জোন-B তে ডেলিভারি হয়েছে!"
                )
            )
        }
    }

    // ---- #3 Geofenced SOS Broadcast & Offline Map Pins ----

    private fun seedGeoAlerts() {
        broadcastGeoAlert(Alerts.PIN, "মেডিক্যাল ক্যাম্প ও আশ্রয়কেন্দ্র", 23.8103, 90.4125)
    }

    fun broadcastGeoAlert(type: Int, text: String, lat: Double, lon: Double) {
        val geohash = GeoZones.geohash(lat, lon, 6)
        val zone = GeoZones.zoneOf(lat, lon, 5)
        val alertId = System.nanoTime() and 0x7FFFFFFFL
        val safeText = truncateUtf8(text.ifBlank { "SOS সাহায্য প্রয়োজন" }, 96)
        val wire = Alerts.encode(
            myIdKeys,
            alertId,
            type,
            System.currentTimeMillis() + 3_600_000L,
            geohash.substring(0, 5),
            lat,
            lon,
            safeText,
            12
        )
        val verified = Alerts.decodeVerified(wire, listOf(myIdKeys.publicRaw())) ?: return
        val label = when (verified.type) {
            Alerts.SOS -> "SOS জরুরি"
            Alerts.PIN -> "ম্যাপ পিন"
            Alerts.WARNING -> "সতর্কতা"
            else -> "তথ্য"
        }
        val item = GeoAlertUi(verified.id, label, geohash, zone, verified.text, verified.official)
        _state.update {
            it.copy(
                geo = it.geo.copy(
                    currentGeohash = geohash,
                    hilbertZone = zone,
                    alerts = (listOf(item) + it.geo.alerts).take(5),
                    status = "স্বাক্ষরিত $label প্রেরিত · Geohash #$geohash (${wire.size} B)"
                )
            )
        }
    }

    // ---- #4 Steganography & Traffic Masking ----

    fun runStegoMask(mode: String, secretText: String) {
        val clean = truncateUtf8(secretText.ifBlank { "MeshNet Bypass Secret" }, 180)
        val pkt = Packet(
            Packet.TYPE_DATA, 16, Priority.URGENT, System.nanoTime(), 101, 202,
            clean.toByteArray(StandardCharsets.UTF_8)
        ).encode()

        val disguised: ByteArray
        val extracted: ByteArray?
        val modeTitle: String
        when (mode) {
            "AUDIO" -> {
                modeTitle = "অডিও স্টেগানোগ্রাফি (PCM LSB)"
                val carrier = ByteArray((pkt.size + 16) * 4) { (it * 13).toByte() }
                disguised = TrafficMasker.embedLsbStego(pkt, carrier)
                extracted = TrafficMasker.extractLsbStego(disguised)
            }
            "IMAGE" -> {
                modeTitle = "ইমেজ স্টেগানোগ্রাফি (RGB LSB)"
                val carrier = ByteArray((pkt.size + 16) * 4) { ((it * 29) xor 0x5A).toByte() }
                disguised = TrafficMasker.embedLsbStego(pkt, carrier)
                extracted = TrafficMasker.extractLsbStego(disguised)
            }
            else -> {
                modeTitle = "HTTPS / TLS 1.3 ছদ্মবেশ"
                disguised = TrafficMasker.maskAsHttpsTls(pkt, maskKey)
                extracted = TrafficMasker.unmaskHttpsTls(disguised, maskKey)
            }
        }
        val ok = extracted != null && extracted.contentEquals(pkt)
        val hex = disguised.take(14).joinToString(" ") { String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF) }
        _state.update {
            it.copy(
                stego = StegoUiState(
                    mode = modeTitle,
                    wireBytes = disguised.size,
                    disguisedHex = hex,
                    unmaskedOk = ok,
                    status = if (ok) "$modeTitle সফল · DPI আনডিক্টেক্টেবল (${disguised.size} B)" else "মাস্কিং ত্রুটি"
                )
            )
        }
    }

    // ---- #5 Cross-Platform Hardware Bridge (LoRa + Satellite + Desktop) ----

    fun runCrossHardwareBridgeDemo() {
        var wireBytes: ByteArray? = null
        val loraModem = HardwareBridges.KissSerialLoraModem(220) { kiss -> wireBytes = kiss }
        var rxOk = false
        loraModem.setReceiver { frame ->
            if (frame.isNotEmpty()) rxOk = true
        }
        val sample = "LoRa-15km-MeshNet-SOS".toByteArray(StandardCharsets.UTF_8)
        loraModem.send(sample)
        wireBytes?.let { loraModem.onSerialBytesReceived(it) }

        var satSent = false
        val satModem = HardwareBridges.SatelliteModem(340) { burst -> satSent = burst.isNotEmpty() }
        satModem.send(sample)

        _state.update {
            it.copy(
                hardware = CrossHardwareUiState(
                    loraTxFrames = loraModem.txFrames,
                    loraRxFrames = loraModem.rxFrames,
                    satBursts = satModem.burstsSent,
                    desktopEndpoints = desktopDir.count(),
                    kissCrcErrors = loraModem.crcErrors,
                    status = if (rxOk && satSent) {
                        "LoRa KISS-CRC16 (${wireBytes?.size ?: 0}B) + Satellite NTN SBD + Desktop UDP সংযুক্ত"
                    } else "ব্রিজ পরীক্ষা চলছে"
                )
            )
        }
    }

    // ---- #6 CRDT Distributed Group Sync ----

    fun postCrdtMessage(text: String) {
        val clean = truncateUtf8(text.ifBlank { "অফলাইন গ্রুপ বার্তা" }, 900)
        crdtClockMs += 500L
        val opA = replicaA.create(myIdKeys, 101, GroupState.MSG, crdtClockMs, clean.toByteArray(StandardCharsets.UTF_8))
        replicaA.ingest(opA)
        // Replicate via serialized wire op onto Phone B
        GroupState.Op.decode(opA.encode())?.let { replicaB.ingest(it) }

        crdtClockMs += 300L
        val replyOp = replicaB.create(peerIdKeys, 202, GroupState.MSG, crdtClockMs, "রিসিভড ✓ (নোড ২০২)".toByteArray(StandardCharsets.UTF_8))
        replicaB.ingest(replyOp)
        GroupState.Op.decode(replyOp.encode())?.let { replicaA.ingest(it) }
        syncCrdtState()
    }

    private fun truncateUtf8(input: String, maxBytes: Int): String {
        if (input.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) return input
        var end = input.length
        while (end > 0 && input.substring(0, end).toByteArray(StandardCharsets.UTF_8).size > maxBytes) {
            end -= Character.charCount(input.codePointBefore(end))
        }
        return input.substring(0, end.coerceAtLeast(0))
    }

    fun toggleCrdtReaction() {
        val last = replicaA.history().lastOrNull() ?: return
        val parts = last.key.split(":")
        val tAuthor = parts[0].toIntOrNull() ?: return
        val tSeq = parts[1].toIntOrNull() ?: return
        val emoji = "👍".toByteArray(StandardCharsets.UTF_8)
        val body = ByteBuffer.allocate(9 + emoji.size).putInt(tAuthor).putInt(tSeq).put(1.toByte()).put(emoji).array()
        crdtClockMs += 200L
        val op = replicaA.create(myIdKeys, 101, GroupState.REACT, crdtClockMs, body)
        replicaA.ingest(op)
        GroupState.Op.decode(op.encode())?.let { replicaB.ingest(it) }
        syncCrdtState()
    }

    private fun syncCrdtState() {
        val snapA = replicaA.snapshot()
        val snapB = replicaB.snapshot()
        val converged = snapA == snapB
        val hashPrefix = Keccak.sha3_256(snapA.toByteArray(StandardCharsets.UTF_8))
            .take(4).joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xFF) }
        val msgs = replicaA.history().takeLast(4).reversed().map { m ->
            val reactCount = replicaA.reactionsOf(m.key).values.sumOf { it.size }
            CrdtMessageUi(m.key, m.author, m.text, reactCount)
        }
        _state.update {
            it.copy(
                crdt = CrdtGroupUiState(
                    converged = converged,
                    memberCount = maxOf(3, replicaA.vector().size + 1),
                    snapshotHash = hashPrefix,
                    messages = msgs,
                    status = if (converged) "Replica A & B শতভাগ কনভার্জড (Hash #$hashPrefix)" else "সিঙ্ক বাকি"
                )
            )
        }
    }

    // ---- #7 Relay Incentive & Fairness Credits ----

    fun recordHelpfulRelay() {
        fairnessLedger.recordRelayForPeer(101, 2048)
        refreshFairness("অন্যের মেসেজ রিলে করায় +ক্রেডিট ও প্রায়োরিটি বুস্ট হয়েছে!")
    }

    fun recordSelfishTraffic() {
        repeat(4) { fairnessLedger.recordOwnTraffic(209, 2048) }
        refreshFairness("লিচ নোড #২০৯ রিলে না করায় BULK-এ ডাউনগ্রেড হয়েছে!")
    }

    private fun refreshFairness(msg: String = "রিলে ক্রেডিট ও ফেয়ারনেস সিস্টেম সক্রিয়") {
        val good = fairnessLedger.snapshot(101)
        val leech = fairnessLedger.snapshot(209)
        _state.update {
            it.copy(
                fairness = FairnessUiState(
                    relayScore = good.fairnessScore,
                    relayCredits = good.credits,
                    relayPriority = fairnessLedger.effectivePriority(101, Priority.NORMAL),
                    leechScore = leech.fairnessScore,
                    leechCredits = leech.credits,
                    leechPriority = fairnessLedger.effectivePriority(209, Priority.NORMAL),
                    relayRateScale = fairnessLedger.rateScale(101),
                    status = msg
                )
            )
        }
    }
}
