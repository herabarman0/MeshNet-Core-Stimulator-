package com.herabarman.meshnet.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.herabarman.meshnet.app.radio.MultiRadioFabric
import com.herabarman.meshnet.core.AesGcmCrypto
import com.herabarman.meshnet.core.BloomFilter
import com.herabarman.meshnet.core.DeliveryMode
import com.herabarman.meshnet.core.FecCodec
import com.herabarman.meshnet.core.HybridKeyExchange
import com.herabarman.meshnet.core.KeyExchange
import com.herabarman.meshnet.core.LocationDirectory
import com.herabarman.meshnet.core.MlKem768
import com.herabarman.meshnet.core.Packet
import com.herabarman.meshnet.core.Priority
import com.herabarman.meshnet.dashboard.DashboardSim
import com.herabarman.meshnet.sim.SimConfig
import com.herabarman.meshnet.sim.SimNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

data class WireCheckResult(
    val running: Boolean = false,
    val vectorsPassed: Int = 0,
    val throughputOpsPerSec: Long = 0,
    val nsPerPacket: Double = 0.0,
    val sampleHex: String = "",
    val bloomBytes: Int = 0
)

data class CryptoLabResult(
    val alicePubHex: String = "",
    val bobPubHex: String = "",
    val sharedKeyHex: String = "",
    val plaintext: String = "MeshNet E2EE SOS OK",
    val cipherBytes: Int = 0,
    val overheadBytes: Int = AesGcmCrypto.TAG_BYTES,
    val verifiedRoundTrip: Boolean = false,
    val latencyMicros: Long = 0
)

data class FecLabResult(
    val dataShards: Int = 4,
    val parityShards: Int = 3,
    val droppedIndices: Set<Int> = setOf(0, 2, 5),
    val recoveredOk: Boolean = false,
    val recoveredText: String = "",
    val shardBytes: Int = 0
)

data class DtnBrainState(
    val prediction: Double = 0.0,
    val exploration: Double = 0.10,
    val updates: Long = 0,
    val weightsPreview: List<Double> = listOf(-1.0, 3.0, 2.0, 1.5, 0.5, 0.3, 0.3, 0.2, -0.5)
)

data class PostQuantumLabResult(
    val kemEkBytes: Int = MlKem768.EK_BYTES,
    val kemCtBytes: Int = MlKem768.CT_BYTES,
    val hybridKeyHex: String = "",
    val plaintext: String = "Quantum-Safe MeshNet",
    val cipherBytes: Int = 0,
    val verifiedRoundTrip: Boolean = false,
    val latencyMicros: Long = 0L
)

data class EngineLabUiState(
    val wire: WireCheckResult = WireCheckResult(),
    val crypto: CryptoLabResult = CryptoLabResult(),
    val pqCrypto: PostQuantumLabResult = PostQuantumLabResult(),
    val fec: FecLabResult = FecLabResult(),
    val dtn: DtnBrainState = DtnBrainState()
)

data class BenchmarkRunResult(
    val id: String,
    val title: String,
    val subtitle: String,
    val deliveryPct: Double,
    val avgHops: Double,
    val p95LatencyMs: Long,
    val txPerMsg: Double,
    val ctrlPct: Double,
    val elapsedMs: Long
)

data class MultiPlatformUiState(
    val runningId: String? = null,
    val results: Map<String, BenchmarkRunResult> = emptyMap()
)

class MeshDashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val ticker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val simFuture: CompletableFuture<DashboardSim> = CompletableFuture()
    val hub = MeshHubEngine(application)
    val hubState: StateFlow<MeshHubUiState> = hub.state
    val resilience = MeshResilienceEngine()
    val resilienceState: StateFlow<MeshResilienceUiState> = resilience.state
    val worldClass = MeshWorldClassEngine(hub.multiRadio)
    val worldClassState: StateFlow<WorldClassUiState> = worldClass.state
    val services = MeshServicesEngine(application)
    val servicesState: StateFlow<MeshServicesUiState> = services.state

    @Volatile
    private var selA: Int = -1

    @Volatile
    private var selB: Int = -1

    @Volatile
    private var lastSeq: Long = 0L

    @Volatile
    private var prevSimTimeMs: Long = 0L

    private val _liveState = MutableStateFlow(LiveMeshUiState())
    val liveState: StateFlow<LiveMeshUiState> = _liveState.asStateFlow()

    private val _labState = MutableStateFlow(EngineLabUiState())
    val labState: StateFlow<EngineLabUiState> = _labState.asStateFlow()

    private val _benchState = MutableStateFlow(MultiPlatformUiState())
    val benchState: StateFlow<MultiPlatformUiState> = _benchState.asStateFlow()

    // On-device logistic regression weights mirroring DtnBrain
    private val dtnWeights = doubleArrayOf(-1.0, 3.0, 2.0, 1.5, 0.5, 0.3, 0.3, 0.2, -0.5)
    private var dtnEps = 0.10
    private var dtnUpdates = 0L
    private val dtnSampleFeatures = doubleArrayOf(1.0, 0.85, 0.45, 0.90, 0.80, 0.20, 0.70, 0.50, 1.0)

    init {
        ticker.execute {
            try {
                simFuture.complete(DashboardSim())
            } catch (t: Throwable) {
                simFuture.completeExceptionally(t)
            }
        }
        ticker.scheduleAtFixedRate(
            {
                try {
                    if (simFuture.isDone && !simFuture.isCompletedExceptionally) {
                        simFuture.get().tick(50)
                    }
                } catch (e: RuntimeException) {
                    e.printStackTrace()
                }
            },
            50,
            50,
            TimeUnit.MILLISECONDS
        )
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                pollLiveOnce()
                delay(150L)
            }
        }
        runWireCheck()
        runCryptoDemo("MeshNet E2EE SOS OK")
        runPostQuantumDemo("Quantum-Safe MeshNet")
        runFecDemo(droppedCount = 3)
        refreshDtnState()
    }

    private fun pollLiveOnce() {
        if (!simFuture.isDone || simFuture.isCompletedExceptionally) return
        try {
            val sim = simFuture.get()
            val json = sim.state(lastSeq, selA, selB)
            val parsed = LiveMeshUiState.parse(json, selA, selB)
            if (parsed.simTimeMs < prevSimTimeMs) {
                selA = -1
                selB = -1
            }
            prevSimTimeMs = parsed.simTimeMs
            lastSeq = parsed.seq
            _liveState.value = parsed.copy(selectedA = selA, selectedB = selB)
        } catch (_: Exception) {
        }
    }

    fun selectNode(nodeId: Int) {
        if (nodeId < 0) {
            selA = -1
            selB = -1
        } else if (selA < 0 || (selA >= 0 && selB >= 0)) {
            selA = nodeId
            selB = -1
        } else if (nodeId != selA) {
            selB = nodeId
        }
        viewModelScope.launch(Dispatchers.Default) { pollLiveOnce() }
    }

    fun sendRandom(mode: String) {
        viewModelScope.launch(Dispatchers.Default) {
            if (!simFuture.isDone || simFuture.isCompletedExceptionally) return@launch
            val resp = JSONObject(simFuture.get().send(mode, -1, -1))
            if (resp.optBoolean("ok", false)) {
                selA = resp.optInt("src", -1)
                selB = resp.optInt("dst", -1)
            }
            pollLiveOnce()
        }
    }

    fun sendAB() {
        val a = selA
        val b = selB
        if (a < 0 || b < 0) return
        viewModelScope.launch(Dispatchers.Default) {
            if (!simFuture.isDone || simFuture.isCompletedExceptionally) return@launch
            simFuture.get().send("remote", a, b)
            pollLiveOnce()
        }
    }

    fun killA() {
        val a = selA
        if (a < 0) return
        viewModelScope.launch(Dispatchers.Default) {
            if (!simFuture.isDone || simFuture.isCompletedExceptionally) return@launch
            simFuture.get().kill(a)
            pollLiveOnce()
        }
    }

    fun reviveA() {
        val a = selA
        if (a < 0) return
        viewModelScope.launch(Dispatchers.Default) {
            if (!simFuture.isDone || simFuture.isCompletedExceptionally) return@launch
            simFuture.get().revive(a)
            pollLiveOnce()
        }
    }

    fun killBorderNodesOfA() {
        val a = selA
        val st = _liveState.value
        if (a < 0 || st.zoneSize <= 0) return
        val zone = minOf(a / st.zoneSize, maxOf(0, st.zoneCount - 1))
        viewModelScope.launch(Dispatchers.Default) {
            if (!simFuture.isDone || simFuture.isCompletedExceptionally) return@launch
            simFuture.get().killGateways(zone)
            pollLiveOnce()
        }
    }

    fun togglePause() {
        val nextPaused = !_liveState.value.paused
        configureSim(mapOf("paused" to if (nextPaused) "1" else "0"))
    }

    fun configureSim(params: Map<String, String>) {
        viewModelScope.launch(Dispatchers.Default) {
            if (!simFuture.isDone || simFuture.isCompletedExceptionally) return@launch
            if (params["reset"] == "1") {
                selA = -1
                selB = -1
                lastSeq = 0L
            }
            simFuture.get().configure(params)
            pollLiveOnce()
        }
    }

    fun applySimPreset(preset: String) {
        val q = when (preset) {
            "standard" -> mapOf(
                "nodes" to "120", "zoneSize" to "12", "online" to "20", "gateways" to "2",
                "backbone" to "full", "loss" to "0", "churn" to "0", "traffic" to "15",
                "routes" to "1", "ack" to "1", "suppress" to "1", "discovery" to "1", "reset" to "1"
            )
            "storm" -> mapOf(
                "nodes" to "120", "zoneSize" to "12", "online" to "15", "gateways" to "2",
                "backbone" to "full", "loss" to "30", "churn" to "5", "traffic" to "30",
                "routes" to "1", "ack" to "1", "suppress" to "1", "discovery" to "1", "reset" to "1"
            )
            "chord" -> mapOf(
                "nodes" to "360", "zoneSize" to "12", "online" to "20", "gateways" to "2",
                "backbone" to "chord", "loss" to "5", "churn" to "2", "traffic" to "20",
                "routes" to "1", "ack" to "1", "suppress" to "1", "discovery" to "1", "reset" to "1"
            )
            else -> mapOf("reset" to "1")
        }
        configureSim(q)
    }

    fun runWireCheck() {
        viewModelScope.launch(Dispatchers.Default) {
            _labState.update { it.copy(wire = it.wire.copy(running = true)) }
            val rng = Random(20261122L)
            var passed = 0
            val payload = "MeshNet-Wire-v2".toByteArray(StandardCharsets.UTF_8)
            val samplePacket = Packet(Packet.TYPE_DATA, 32, Priority.URGENT, 0x1A2B3C4D5E6F7788L, 101, 202, payload, 0, 3, 9)
            val sampleBytes = samplePacket.encode()

            for (i in 0 until 600) {
                val p = Packet(
                    1 + (i % 8),
                    i % 256,
                    i % 256,
                    rng.nextLong(),
                    rng.nextInt(),
                    rng.nextInt(),
                    ByteArray(i % 64).also { rng.nextBytes(it) },
                    i % 16,
                    i % 40,
                    (i + 7) % 40
                )
                val enc = p.encode()
                val dec = Packet.decode(enc)
                if (dec.msgId == p.msgId && dec.src == p.src && dec.dst == p.dst && dec.encode().contentEquals(enc)) {
                    passed++
                }
            }
            for (badLen in listOf(0, 10, 29, 31)) {
                val bad = ByteArray(badLen)
                if (bad.isNotEmpty()) bad[0] = Packet.VERSION.toByte()
                val rejected = try {
                    Packet.decode(bad)
                    false
                } catch (_: IllegalArgumentException) {
                    true
                }
                if (rejected) passed++
            }
            for (i in 0 until 1000) {
                val hz = LocationDirectory.homeZone(i * 7919, 64)
                if (hz in 0 until 64) passed++
            }

            val bloom = BloomFilter.forCapacity(128)
            for (i in 1L..100L) bloom.add(i * 1009L)
            val bloomBytes = bloom.toBytes().size

            val iters = 50_000
            val t0 = System.nanoTime()
            var sink = 0
            for (i in 0 until iters) {
                val enc = samplePacket.encode()
                val dec = Packet.decode(enc)
                sink = sink xor LocationDirectory.homeZone(dec.src + i, 64)
            }
            val elapsedNs = (System.nanoTime() - t0).coerceAtLeast(1L)
            val nsPerOp = elapsedNs.toDouble() / iters
            val opsPerSec = (iters * 1_000_000_000.0 / elapsedNs).toLong() + (sink and 0)

            _labState.update {
                it.copy(
                    wire = WireCheckResult(
                        running = false,
                        vectorsPassed = passed,
                        throughputOpsPerSec = opsPerSec,
                        nsPerPacket = nsPerOp,
                        sampleHex = toHex(sampleBytes.copyOfRange(0, 30)),
                        bloomBytes = bloomBytes
                    )
                )
            }
        }
    }

    fun runCryptoDemo(message: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val text = message.ifBlank { "MeshNet E2EE" }
            val t0 = System.nanoTime()
            val alice = KeyExchange.generateRawKeyPair()
            val bob = KeyExchange.generateRawKeyPair()
            val keyA = KeyExchange.deriveRawKey(alice[0], bob[1])
            val keyB = KeyExchange.deriveRawKey(bob[0], alice[1])
            val enc = AesGcmCrypto(keyA).encrypt(text.toByteArray(StandardCharsets.UTF_8), 101, 202, 777L)
            val dec = AesGcmCrypto(keyB).decrypt(enc, 101, 202, 777L)
            val micros = (System.nanoTime() - t0) / 1_000L
            val ok = dec != null && String(dec, StandardCharsets.UTF_8) == text
            _labState.update {
                it.copy(
                    crypto = CryptoLabResult(
                        alicePubHex = toHex(alice[1].copyOfRange(0, 8)),
                        bobPubHex = toHex(bob[1].copyOfRange(0, 8)),
                        sharedKeyHex = toHex(keyA.copyOfRange(0, 8)),
                        plaintext = text,
                        cipherBytes = enc.size,
                        overheadBytes = enc.size - text.toByteArray(StandardCharsets.UTF_8).size,
                        verifiedRoundTrip = ok,
                        latencyMicros = micros
                    )
                )
            }
        }
    }

    fun runPostQuantumDemo(message: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val text = message.ifBlank { "Quantum-Safe MeshNet" }
            val t0 = System.nanoTime()
            val alice = HybridKeyExchange.generateIdentity()
            val bob = HybridKeyExchange.generateIdentity()
            val offer = HybridKeyExchange.initiate(alice, bob.x25519Pub, bob.mlKemEk)
            val bobKey = HybridKeyExchange.respond(bob, alice.x25519Pub, offer.ciphertext)
            val plain = text.toByteArray(StandardCharsets.UTF_8)
            val enc = AesGcmCrypto(offer.sessionKey).encrypt(plain, 101, 202, 9001L)
            val dec = AesGcmCrypto(bobKey).decrypt(enc, 101, 202, 9001L)
            val micros = (System.nanoTime() - t0) / 1_000L
            val ok = dec != null && offer.sessionKey.contentEquals(bobKey) && String(dec, StandardCharsets.UTF_8) == text
            _labState.update {
                it.copy(
                    pqCrypto = PostQuantumLabResult(
                        kemEkBytes = bob.mlKemEk.size,
                        kemCtBytes = offer.ciphertext.size,
                        hybridKeyHex = toHex(offer.sessionKey.copyOfRange(0, 8)),
                        plaintext = text,
                        cipherBytes = enc.size,
                        verifiedRoundTrip = ok,
                        latencyMicros = micros
                    )
                )
            }
        }
    }

    fun runFecDemo(droppedCount: Int) {
        viewModelScope.launch(Dispatchers.Default) {
            val dataShards = 4
            val parityShards = 3
            val msg = "MeshNet GF(256) Reed-Solomon recovers lost radio bursts!"
            val block = FecCodec.encode(msg.toByteArray(StandardCharsets.UTF_8), dataShards, parityShards)
            val shards = block.shards.clone()
            val dropOrder = listOf(0, 2, 5, 1, 3)
            val dropped = dropOrder.take(droppedCount.coerceIn(0, 4)).toSet()
            for (idx in dropped) shards[idx] = null
            val recovered = FecCodec.decode(shards, dataShards, parityShards)
            val text = recovered?.let { String(it, StandardCharsets.UTF_8) } ?: ""
            _labState.update {
                it.copy(
                    fec = FecLabResult(
                        dataShards = dataShards,
                        parityShards = parityShards,
                        droppedIndices = dropped,
                        recoveredOk = recovered != null && text == msg,
                        recoveredText = text,
                        shardBytes = block.shardLength
                    )
                )
            }
        }
    }

    fun trainDtnBrain(deliveredReward: Double) {
        var z = 0.0
        for (i in 0 until 9) z += dtnWeights[i] * dtnSampleFeatures[i]
        val pred = 1.0 / (1.0 + kotlin.math.exp(-z))
        val err = deliveredReward - pred
        val lr = 0.08
        for (i in 0 until 9) {
            dtnWeights[i] = (dtnWeights[i] + lr * err * dtnSampleFeatures[i] - lr * 0.001 * dtnWeights[i]).coerceIn(-8.0, 8.0)
        }
        dtnEps = maxOf(0.02, dtnEps * 0.96)
        dtnUpdates++
        refreshDtnState()
    }

    private fun refreshDtnState() {
        var z = 0.0
        for (i in 0 until 9) z += dtnWeights[i] * dtnSampleFeatures[i]
        val pred = 1.0 / (1.0 + kotlin.math.exp(-z))
        _labState.update {
            it.copy(
                dtn = DtnBrainState(
                    prediction = pred,
                    exploration = dtnEps,
                    updates = dtnUpdates,
                    weightsPreview = dtnWeights.toList()
                )
            )
        }
    }

    fun runSimBenchmark(scenarioId: String) {
        if (_benchState.value.runningId != null) return
        viewModelScope.launch(Dispatchers.Default) {
            _benchState.update { it.copy(runningId = scenarioId) }
            val cfg = SimConfig()
            val title: String
            val subtitle: String
            val messages: Int
            when (scenarioId) {
                "chord_300" -> {
                    title = "৩০০ নোড · কর্ড রিং (স্কেল)"
                    subtitle = "25 zones · chord fingers · 20% online · discovery on"
                    cfg.nodes = 300
                    cfg.zoneSize = 12
                    cfg.backbone = "chord"
                    cfg.onlineRatio = 0.20
                    cfg.discovery = "on"
                    messages = 250
                }
                "burst_pacing" -> {
                    title = "প্যাকেট বার্স্ট + পেসার সুরক্ষা"
                    subtitle = "120 nodes · txRate=25/s · bulk + text priority reserve"
                    cfg.nodes = 120
                    cfg.zoneSize = 12
                    cfg.backbone = "full"
                    cfg.txRatePerSec = 25.0
                    cfg.txBurst = 16
                    cfg.onlineRatio = 0.15
                    cfg.discovery = "on"
                    messages = 220
                }
                "storm_churn" -> {
                    title = "হাই লস + গেটওয়ে ফেইল"
                    subtitle = "144 nodes · 20% packet loss · 5% gw fail · offline-first"
                    cfg.nodes = 144
                    cfg.zoneSize = 12
                    cfg.backbone = "full"
                    cfg.lossRate = 0.20
                    cfg.gatewayFail = 0.05
                    cfg.onlineRatio = 0.20
                    cfg.mode = DeliveryMode.OFFLINE_FIRST
                    cfg.discovery = "on"
                    messages = 200
                }
                "turbo_250" -> {
                    title = "২৫০ নোড · আল্ট্রা হাই-ট্রাফিক টার্বো"
                    subtitle = "250 nodes · chord · high txRate=60/s · heavy load pacing"
                    cfg.nodes = 250
                    cfg.zoneSize = 15
                    cfg.backbone = "chord"
                    cfg.txRatePerSec = 60.0
                    cfg.txBurst = 32
                    cfg.onlineRatio = 0.25
                    cfg.discovery = "on"
                    messages = 450
                }
                else -> {
                    title = "১২০ নোড · ফুল মেশ বেসলাইন"
                    subtitle = "10 zones · routes + ACK + suppression · discovery on"
                    cfg.nodes = 120
                    cfg.zoneSize = 12
                    cfg.backbone = "full"
                    cfg.onlineRatio = 0.20
                    cfg.discovery = "on"
                    messages = 200
                }
            }

            val t0 = System.currentTimeMillis()
            val net = SimNetwork(cfg)
            net.clock.runFor(2_000)
            val rnd = Random(cfg.seed + 7)
            for (i in 0 until messages) {
                val src = rnd.nextInt(cfg.nodes)
                var dst = rnd.nextInt(cfg.nodes)
                if (dst == src) dst = (src + 1) % cfg.nodes
                net.sendMessage(src, dst, ByteArray(32), if (i % 4 == 0) 2 else 1)
                net.clock.runFor(15)
            }
            net.clock.runFor(6_000)
            val elapsed = System.currentTimeMillis() - t0
            val m = net.metrics
            val ctrlPct = if (m.transmissions > 0) (m.controlTransmissions() * 100.0 / m.transmissions) else 0.0

            val res = BenchmarkRunResult(
                id = scenarioId,
                title = title,
                subtitle = subtitle,
                deliveryPct = m.deliveryRate() * 100.0,
                avgHops = m.avgHops(),
                p95LatencyMs = m.p95LatencyMs(),
                txPerMsg = m.transmissionsPerMessage(),
                ctrlPct = ctrlPct,
                elapsedMs = elapsed
            )
            _benchState.update {
                it.copy(
                    runningId = null,
                    results = it.results + (scenarioId to res)
                )
            }
        }
    }

    // ---- Hub Actions ----

    fun requiredRuntimePermissions(): Array<String> = MultiRadioFabric.requiredRuntimePermissions()
    fun updatePermissions(allGranted: Boolean) = hub.updatePermissionStatus(allGranted)
    fun toggleRadios() {
        viewModelScope.launch(Dispatchers.IO) {
            if (hub.multiRadio.isStarted) hub.stopRadios() else hub.startRadios()
        }
    }
    fun toggleHotspot() = hub.toggleHotspot()
    fun runTurboAndJam(injectJam: Boolean) {
        viewModelScope.launch(Dispatchers.Default) { hub.runTurboAndJam(injectJam) }
    }
    fun togglePtt(loopback: Boolean) = hub.togglePtt(loopback)
    fun runAvCallDemo() {
        viewModelScope.launch(Dispatchers.Default) { hub.runMultiHopAvCallDemo() }
    }
    fun sendPayment(amount: Long, memo: String) {
        viewModelScope.launch(Dispatchers.Default) { hub.sendOfflinePayment(amount, memo) }
    }
    fun testReplayProtection() {
        viewModelScope.launch(Dispatchers.Default) { hub.testReplayProtection() }
    }
    fun createEscrow(amount: Long, secret: String) {
        viewModelScope.launch(Dispatchers.Default) { hub.createEscrowContract(amount, secret) }
    }
    fun settleEscrow(secret: String) {
        viewModelScope.launch(Dispatchers.Default) { hub.settleLatestEscrow(secret) }
    }
    fun syncLedger() {
        viewModelScope.launch(Dispatchers.Default) { hub.syncLedgerToServer() }
    }
    fun runOtaSwarm(injectPoison: Boolean) {
        viewModelScope.launch(Dispatchers.Default) { hub.runOtaSwarmDemo(injectPoison) }
    }
    fun buildOtaShareIntent(): Intent? = hub.buildOtaShareIntent()
    fun postMarketListing(title: String, priceBdt: Long) {
        viewModelScope.launch(Dispatchers.Default) { hub.postMarketListing(title, priceBdt) }
    }
    fun contactMarketSeller(listingId: Long) {
        viewModelScope.launch(Dispatchers.Default) { hub.expressMarketInterest(listingId) }
    }
    fun runQuizRound() {
        viewModelScope.launch(Dispatchers.Default) { hub.runQuizRound() }
    }
    fun publishIpfsUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) { hub.publishUriToIpfs(uri) }
    }
    fun testIpfsRecovery(offlinePeers: Int) {
        viewModelScope.launch(Dispatchers.Default) { hub.simulateIpfsRecovery(offlinePeers) }
    }

    // ---- Resilience & AI Actions ----

    fun evaluateAiAndEnergy(lowBatt: Int, altBatt: Int) {
        viewModelScope.launch(Dispatchers.Default) {
            resilience.evaluateAiAndEnergy(lowBatt, altBatt)
        }
    }

    fun trainAiWithOutcome(success: Boolean, lowBatt: Int, altBatt: Int) {
        viewModelScope.launch(Dispatchers.Default) {
            resilience.trainAiWithOutcome(success, lowBatt, altBatt)
        }
    }

    fun loadMuleAndCarry(msg: String) {
        viewModelScope.launch(Dispatchers.Default) {
            resilience.loadMuleAndCarry(msg)
        }
    }

    fun arriveMuleZone() {
        viewModelScope.launch(Dispatchers.Default) {
            resilience.arriveTargetZoneAndDeliver()
        }
    }

    fun broadcastGeoAlert(type: Int, text: String, lat: Double, lon: Double) {
        viewModelScope.launch(Dispatchers.Default) {
            resilience.broadcastGeoAlert(type, text, lat, lon)
        }
    }

    fun runStegoMask(mode: String, secret: String) {
        viewModelScope.launch(Dispatchers.Default) {
            resilience.runStegoMask(mode, secret)
        }
    }

    fun runCrossHardwareBridge() {
        viewModelScope.launch(Dispatchers.Default) {
            resilience.runCrossHardwareBridgeDemo()
        }
    }

    fun postCrdtMessage(text: String) {
        viewModelScope.launch(Dispatchers.Default) {
            resilience.postCrdtMessage(text)
        }
    }

    fun toggleCrdtReaction() {
        viewModelScope.launch(Dispatchers.Default) {
            resilience.toggleCrdtReaction()
        }
    }

    fun recordHelpfulRelay() {
        viewModelScope.launch(Dispatchers.Default) {
            resilience.recordHelpfulRelay()
        }
    }

    fun recordSelfishTraffic() {
        viewModelScope.launch(Dispatchers.Default) {
            resilience.recordSelfishTraffic()
        }
    }

    // ---- World-Class Core Actions ----

    fun evaluateAdaptiveRouting(rssi: Int, lossPct: Int) {
        viewModelScope.launch(Dispatchers.Default) {
            worldClass.evaluateAdaptiveRouting(rssi, lossPct)
        }
    }

    fun runTransportBonding(workloadKb: Int, isRealtimeVoice: Boolean) {
        viewModelScope.launch(Dispatchers.Default) {
            worldClass.runTransportBonding(workloadKb, isRealtimeVoice)
        }
    }

    fun runUltrasonicChirpTest() {
        viewModelScope.launch(Dispatchers.Default) {
            worldClass.runUltrasonicChirpTest()
        }
    }

    fun runNatTraversalPunch(simulateSymmetricNat: Boolean) {
        viewModelScope.launch(Dispatchers.Default) {
            worldClass.runNatTraversalPunch(simulateSymmetricNat)
        }
    }

    fun sendDoubleRatchetMessage(text: String) {
        viewModelScope.launch(Dispatchers.Default) {
            worldClass.sendDoubleRatchetMessage(text)
        }
    }

    fun testOutOfOrderAndHealRatchet() {
        viewModelScope.launch(Dispatchers.Default) {
            worldClass.testOutOfOrderAndHealRatchet()
        }
    }

    fun runOnionRoutingHopTest(secret: String) {
        viewModelScope.launch(Dispatchers.Default) {
            worldClass.runOnionRoutingHopTest(secret)
        }
    }

    fun runSybilPowFloodTest(simulateFlood: Boolean) {
        viewModelScope.launch(Dispatchers.Default) {
            worldClass.runSybilPowFloodTest(simulateFlood)
        }
    }

    fun runCompressFecQosPipeline(sampleText: String) {
        viewModelScope.launch(Dispatchers.Default) {
            worldClass.runCompressFecQosPipeline(sampleText)
        }
    }

    fun updateAdaptiveDuty(batteryPct: Int, neighborCount: Int, batterySaver: Boolean) {
        viewModelScope.launch(Dispatchers.Default) {
            worldClass.updateAdaptiveDuty(batteryPct, neighborCount, batterySaver)
        }
    }

    fun runNativeCoreBenchmark() {
        viewModelScope.launch(Dispatchers.Default) {
            worldClass.runNativeCoreBenchmark()
        }
    }

    // ---- LostPhone, Photo Compressor, QR ContactCard & Gateway Actions ----

    fun toggleLostPhoneArm(arm: Boolean) {
        viewModelScope.launch(Dispatchers.Default) { services.toggleLostArm(arm) }
    }

    fun regenerateLostSecret(seedPhrase: String) {
        viewModelScope.launch(Dispatchers.Default) { services.regenerateDerivedSecret(seedPhrase) }
    }

    fun triggerLastGaspAndSighting() {
        viewModelScope.launch(Dispatchers.Default) { services.triggerLastGaspAndNeighborSighting() }
    }

    fun sendLostRemoteCommand(cmdCode: Int) {
        viewModelScope.launch(Dispatchers.Default) { services.sendSignedLostCommand(cmdCode) }
    }

    fun simulateAntiStalking() {
        viewModelScope.launch(Dispatchers.Default) { services.simulateUnfamiliarStalkerBeacons() }
    }

    fun compressPhotoUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.Default) { services.compressPickedPhoto(uri) }
    }

    fun compressSyntheticPhoto() {
        viewModelScope.launch(Dispatchers.Default) { services.compressSynthetic4kSample() }
    }

    fun verifyContactUri(uri: String) {
        viewModelScope.launch(Dispatchers.Default) { services.verifyContactCardUri(uri) }
    }

    fun revokePeerContactKey() {
        viewModelScope.launch(Dispatchers.Default) { services.revokePeerKey() }
    }

    fun updateGatewayPolicy(batteryPct: Int, allowGateway: Boolean) {
        viewModelScope.launch(Dispatchers.Default) { services.updateBatteryAndGateway(batteryPct, manualGatewayToggle = allowGateway) }
    }

    override fun onCleared() {
        hub.release()
        ticker.shutdownNow()
        super.onCleared()
    }

    private fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
}
