package com.herabarman.meshnet.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.herabarman.meshnet.app.radio.MeshAudioEngine
import com.herabarman.meshnet.app.radio.MultiRadioFabric
import com.herabarman.meshnet.core.Keccak
import com.herabarman.meshnet.core.MeshConfig
import com.herabarman.meshnet.core.MeshIpfs
import com.herabarman.meshnet.core.MeshLedger
import com.herabarman.meshnet.core.MeshNode
import com.herabarman.meshnet.core.MeshRtpLayer
import com.herabarman.meshnet.core.MiniAppHost
import com.herabarman.meshnet.core.NodeInfo
import com.herabarman.meshnet.core.NoopCrypto
import com.herabarman.meshnet.core.OtaManifest
import com.herabarman.meshnet.core.OtaSwarm
import com.herabarman.meshnet.core.Packet
import com.herabarman.meshnet.core.Transport
import com.herabarman.meshnet.core.ZoneRouter
import com.herabarman.meshnet.core.apps.MarketApp
import com.herabarman.meshnet.core.apps.QuizApp
import com.herabarman.meshnet.kit.IdentityKeys
import com.herabarman.meshnet.kit.IdentityRecordAuth
import com.herabarman.meshnet.sim.SimClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

data class RadioHubState(
    val radiosActive: Boolean = false,
    val permissionsGranted: Boolean = false,
    val activeChannelName: String = "Wi-Fi / Hotspot UDP",
    val nearbyPeers: Int = 0,
    val blePeers: Int = 0,
    val wifiDirectPeers: Int = 0,
    val hotspotActive: Boolean = false,
    val hotspotSsid: String = "",
    val hotspotPassphrase: String = "",
    val hotspotStatus: String = "অফলাইন হটস্পট বন্ধ",
    val turboSummary: String = "প্রস্তুত (60-প্যাকেট বার্স্ট চালান)",
    val channelLogs: List<String> = emptyList()
)

data class VoiceVideoHubState(
    val isTalking: Boolean = false,
    val loopbackEnabled: Boolean = true,
    val micLevel: Float = 0f,
    val framesCaptured: Long = 0,
    val framesPlayed: Long = 0,
    val audioError: String? = null,
    val avCallSummary: String = "মাল্টি-হপ লাইভ অডিও/ভিডিও RTP পরীক্ষা করুন",
    val avAudioDelivered: Int = 0,
    val avVideoFramesDelivered: Int = 0,
    val avVideoBytesDelivered: Int = 0,
    val avAdaptedKbps: Int = 240,
    val avMultiHopRoute: String = "Node 101 ➔ Relay 102 ➔ Node 103"
)

data class LedgerTxUi(
    val txId: String,
    val from: Int,
    val to: Int,
    val amount: Long,
    val nonce: Long,
    val memo: String,
    val synced: Boolean
)

data class LedgerContractUi(
    val contractId: String,
    val sender: Int,
    val recipient: Int,
    val amount: Long,
    val state: String,
    val synced: Boolean
)

data class LedgerHubState(
    val myBalance: Long = 1000L,
    val peerBalance: Long = 500L,
    val merchantBalance: Long = 250L,
    val ledgerRootHex: String = "0000000000000000",
    val pendingSyncCount: Int = 0,
    val rejectedDoubleSpends: Long = 0L,
    val transactions: List<LedgerTxUi> = emptyList(),
    val contracts: List<LedgerContractUi> = emptyList(),
    val statusMessage: String = "অফলাইন লেজার প্রস্তুত (ECDSA + HTLC)"
)

data class OtaHubState(
    val versionName: String = "v2.4.0-mesh",
    val totalChunks: Int = 0,
    val peer1Have: Int = 0,
    val peer2Have: Int = 0,
    val badChunksRejected: Int = 0,
    val verifiedBytes: Int = 0,
    val statusMessage: String = "Merkle OTA সোয়ার্ম প্রস্তুত",
    val verifiedBundleReady: Boolean = false
)

data class MarketListingUi(
    val id: Long,
    val seller: Int,
    val title: String,
    val priceBdt: Long
)

data class MiniAppHubState(
    val runningApps: List<String> = emptyList(),
    val listings: List<MarketListingUi> = emptyList(),
    val quizRound: Int = 0,
    val quizQuestion: String = "",
    val quizChoices: List<String> = emptyList(),
    val quizResultSummary: String = "মিনি-অ্যাপ হোস্ট সক্রিয় (Market + Quiz)",
    val logs: List<String> = emptyList()
)

data class IpfsCidUi(
    val cid: String,
    val mimeType: String,
    val totalBytes: Int,
    val dataShards: Int,
    val parityShards: Int
)

data class IpfsHubState(
    val catalog: List<IpfsCidUi> = emptyList(),
    val offlinePeersCount: Int = 2,
    val lastStatus: String = "বড় ফাইল / ছবি 6-টি ফোনে ছড়াতে ফাইল বেছে নিন",
    val bloomBytes: Int = 0
)

data class MeshHubUiState(
    val radio: RadioHubState = RadioHubState(),
    val voice: VoiceVideoHubState = VoiceVideoHubState(),
    val ledger: LedgerHubState = LedgerHubState(),
    val ota: OtaHubState = OtaHubState(),
    val miniApps: MiniAppHubState = MiniAppHubState(),
    val ipfs: IpfsHubState = IpfsHubState()
)

/**
 * Coordinates the Android hardware multi-radio fabric + audio PTT + offline Ledger,
 * OTA swarm, MiniApp host, Offline IPFS storage, and Multi-Hop Audio/Video RTP engine.
 */
class MeshHubEngine(private val context: Context) {
    val selfId = 101
    private val peerId = 202
    private val merchantId = 303

    val multiRadio = MultiRadioFabric(context, selfId)
    val audioEngine = MeshAudioEngine(context)

    private val _state = MutableStateFlow(MeshHubUiState())
    val state: StateFlow<MeshHubUiState> = _state.asStateFlow()

    // Identity + Offline Mesh Ledger
    private val ledgerKeys = IdentityKeys.generate()
    private val peerKeys = IdentityKeys.generate()
    private val ledgerAuth = IdentityRecordAuth(ledgerKeys)
    private val ledgerClock = com.herabarman.meshnet.core.SystemClock()
    private val ledger = MeshLedger(ledgerClock, ledgerAuth)

    // OTA Swarm release cache
    @Volatile
    private var verifiedOtaPayload: ByteArray? = null

    // MiniApp Host (Zero-config local cluster over SimClock)
    private val miniClock = SimClock()
    private val miniHosts = LinkedHashMap<Int, MiniAppHost>()
    private val marketApps = LinkedHashMap<Int, MarketApp>()
    private val quizApps = LinkedHashMap<Int, QuizApp>()
    private val miniLogs = ArrayList<String>()

    // Offline IPFS store across 6 peer devices
    private val ipfs = MeshIpfs()
    private val ipfsPeers = listOf(101, 102, 103, 104, 105, 106)
    private val peerOnline = ConcurrentHashMap<Int, Boolean>().apply {
        ipfsPeers.forEach { put(it, true) }
    }
    private val shardDisk = ConcurrentHashMap<String, ByteArray>()
    private val shardStore = object : MeshIpfs.PeerShardStore {
        override fun isOnline(peerId: Int): Boolean = peerOnline[peerId] ?: false
        override fun putShard(peerId: Int, cid: String, shardIdx: Int, data: ByteArray) {
            shardDisk["$peerId:$cid:$shardIdx"] = data.clone()
        }
        override fun getShard(peerId: Int, cid: String, shardIdx: Int): ByteArray? =
            shardDisk["$peerId:$cid:$shardIdx"]
    }

    init {
        ledger.registerAccount(selfId, ledgerKeys.publicRaw(), 1000L)
        ledger.registerAccount(peerId, peerKeys.publicRaw(), 500L)
        ledger.registerAccount(merchantId, peerKeys.publicRaw(), 250L)

        setupMiniApps()
        publishSampleIpfs()
        refreshLedgerState("অফলাইন লেজার প্রস্তুত (ECDSA + HTLC)")
    }

    fun updatePermissionStatus(allGranted: Boolean) {
        _state.update { it.copy(radio = it.radio.copy(permissionsGranted = allGranted)) }
    }

    fun startRadios() {
        multiRadio.startAll()
        refreshRadioState()
    }

    fun stopRadios() {
        multiRadio.stopAll()
        refreshRadioState()
    }

    fun toggleHotspot() {
        if (multiRadio.hotspot.hotspotSsid != null) {
            multiRadio.hotspot.stopLocalOnlyHotspot()
            _state.update {
                it.copy(
                    radio = it.radio.copy(
                        hotspotActive = false,
                        hotspotSsid = "",
                        hotspotPassphrase = "",
                        hotspotStatus = "অফলাইন হটস্পট বন্ধ"
                    )
                )
            }
        } else {
            _state.update { it.copy(radio = it.radio.copy(hotspotStatus = "হটস্পট চালু হচ্ছে...")) }
            multiRadio.hotspot.startLocalOnlyHotspot {
                val ssid = multiRadio.hotspot.hotspotSsid.orEmpty()
                val pass = multiRadio.hotspot.hotspotPassphrase.orEmpty()
                val active = ssid.isNotEmpty()
                _state.update {
                    it.copy(
                        radio = it.radio.copy(
                            hotspotActive = active,
                            hotspotSsid = ssid,
                            hotspotPassphrase = pass,
                            hotspotStatus = multiRadio.hotspot.status
                        )
                    )
                }
            }
        }
    }

    fun runTurboAndJam(injectJam: Boolean) {
        val summary = multiRadio.runTurboAndJamCheck(injectJam)
        _state.update {
            it.copy(
                radio = it.radio.copy(
                    activeChannelName = MultiRadioFabric.channelName(multiRadio.activeChannel),
                    nearbyPeers = multiRadio.nearby.connectedPeerCount(),
                    blePeers = multiRadio.ble.connectedPeerCount(),
                    wifiDirectPeers = if (multiRadio.wifiDirectHelper.status.contains("Owner") ||
                        multiRadio.wifiDirectHelper.status.contains("Client")) 1 else 0,
                    turboSummary = summary,
                    channelLogs = multiRadio.channelLogs.toList()
                )
            )
        }
    }

    fun refreshRadioState() {
        _state.update {
            it.copy(
                radio = it.radio.copy(
                    radiosActive = multiRadio.isStarted,
                    activeChannelName = MultiRadioFabric.channelName(multiRadio.activeChannel),
                    nearbyPeers = multiRadio.nearby.connectedPeerCount(),
                    blePeers = multiRadio.ble.connectedPeerCount(),
                    wifiDirectPeers = if (multiRadio.wifiDirectHelper.status.contains("Owner") ||
                        multiRadio.wifiDirectHelper.status.contains("Client")) 1 else 0,
                    hotspotActive = multiRadio.hotspot.hotspotSsid != null,
                    channelLogs = multiRadio.channelLogs.toList()
                )
            )
        }
    }

    // ---- Voice PTT + Multi-Hop Audio/Video RTP ----

    fun togglePtt(loopback: Boolean) {
        if (audioEngine.isTalking()) {
            audioEngine.stopPushToTalk()
        } else {
            audioEngine.startPushToTalkLoopbackOrStream(
                loopbackAudio = loopback,
                onEncodedPacket = { encoded, _ ->
                    if (multiRadio.isStarted && multiRadio.nearby.connectedPeerCount() > 0) {
                        multiRadio.multiChannel.sendRaw(selfId, encoded)
                    }
                },
                onStateChanged = {
                    _state.update {
                        it.copy(
                            voice = it.voice.copy(
                                isTalking = audioEngine.isTalking(),
                                loopbackEnabled = loopback,
                                micLevel = audioEngine.lastDbLevel,
                                framesCaptured = audioEngine.framesCaptured,
                                framesPlayed = audioEngine.framesPlayed,
                                audioError = audioEngine.lastError
                            )
                        )
                    }
                }
            )
        }
    }

    fun stopPtt() {
        audioEngine.stopPushToTalk()
        _state.update {
            it.copy(voice = it.voice.copy(isTalking = false, micLevel = 0f))
        }
    }

    fun runMultiHopAvCallDemo() {
        val clk = SimClock()
        val cfg = MeshConfig().apply { discovery = false }
        val zones = ZoneRouter.ZoneMap { 0 }
        val listeners = HashMap<Int, Transport.Listener>()
        val adj = mapOf(
            101 to listOf(102),
            102 to listOf(101, 103),
            103 to listOf(102)
        )

        fun transportFor(me: Int): Transport = object : Transport {
            override fun setListener(l: Transport.Listener) {
                listeners[me] = l
            }
            override fun send(peerId: Int, data: ByteArray) {
                val copy = data.clone()
                clk.schedule(15L) {
                    listeners[peerId]?.onReceive(me, copy)
                }
            }
        }

        val rng = Random(42L)
        val n1 = MeshNode(NodeInfo(101, 0, 1.0, 1.0, false), cfg, transportFor(101), clk, zones, NoopCrypto(), null, rng)
        val n2 = MeshNode(NodeInfo(102, 0, 1.0, 1.0, false), cfg, transportFor(102), clk, zones, NoopCrypto(), null, rng)
        val n3 = MeshNode(NodeInfo(103, 0, 1.0, 1.0, false), cfg, transportFor(103), clk, zones, NoopCrypto(), null, rng)

        val noopNodeListener = object : MeshNode.Listener {
            override fun onDelivered(node: MeshNode, packet: Packet, plain: ByteArray, fromPeer: Int) {}
            override fun onDuplicate(node: MeshNode, packet: Packet) {}
            override fun onDropped(node: MeshNode, packet: Packet, reason: String) {}
        }

        var audioRx = 0
        var videoFramesRx = 0
        var videoBytesRx = 0
        var adaptedKbps = 240
        var sigAccepted = false

        lateinit var rtp1: MeshRtpLayer
        rtp1 = MeshRtpLayer(n1, noopNodeListener, clk, object : MeshRtpLayer.Callbacks {
            override fun onCallSignal(fromNode: Int, callId: Int, signal: Int, withVideo: Boolean) {
                if (signal == MeshRtpLayer.SIG_ACCEPT) sigAccepted = true
            }
            override fun onAudioFrame(fromNode: Int, callId: Int, seq: Int, timestampMs: Long, audioBytes: ByteArray) {}
            override fun onVideoFrame(fromNode: Int, callId: Int, frameSeq: Int, keyframe: Boolean, timestampMs: Long, videoBytes: ByteArray) {}
            override fun onQualityReport(peerNode: Int, callId: Int, lossPercent: Int, suggestedVideoKbps: Int) {
                adaptedKbps = suggestedVideoKbps
            }
        })
        n2.setListener(noopNodeListener)
        lateinit var rtp3: MeshRtpLayer
        rtp3 = MeshRtpLayer(n3, noopNodeListener, clk, object : MeshRtpLayer.Callbacks {
            override fun onCallSignal(fromNode: Int, callId: Int, signal: Int, withVideo: Boolean) {
                if (signal == MeshRtpLayer.SIG_RING) {
                    rtp3.sendCallSignal(fromNode, callId, MeshRtpLayer.SIG_ACCEPT, withVideo)
                }
            }
            override fun onAudioFrame(fromNode: Int, callId: Int, seq: Int, timestampMs: Long, audioBytes: ByteArray) {
                audioRx++
            }
            override fun onVideoFrame(fromNode: Int, callId: Int, frameSeq: Int, keyframe: Boolean, timestampMs: Long, videoBytes: ByteArray) {
                videoFramesRx++
                videoBytesRx += videoBytes.size
            }
            override fun onQualityReport(peerNode: Int, callId: Int, lossPercent: Int, suggestedVideoKbps: Int) {}
        })

        n1.start(); n2.start(); n3.start()
        adj.forEach { (u, nbrs) -> nbrs.forEach { v -> listeners[u]?.onPeerUp(v) } }
        clk.runFor(600L)

        val callId = 808
        rtp1.sendCallSignal(103, callId, MeshRtpLayer.SIG_RING, true)
        clk.runFor(400L)

        repeat(6) { idx ->
            val audioFrame = ByteArray(244) { (it + idx).toByte() }
            rtp1.sendAudio(103, callId, audioFrame)
            clk.runFor(40L)
        }
        val keyFrame = ByteArray(2600) { (it * 13).toByte() }
        val deltaFrame = ByteArray(1400) { (it * 7).toByte() }
        rtp1.sendVideoFrame(103, callId, keyFrame, true)
        clk.runFor(250L)
        rtp1.sendVideoFrame(103, callId, deltaFrame, false)
        clk.runFor(250L)
        rtp3.sendQualityReport(101, callId)
        clk.runFor(250L)

        _state.update {
            it.copy(
                voice = it.voice.copy(
                    avCallSummary = if (sigAccepted && videoFramesRx >= 2) {
                        "২-হপ (101➔102➔103) কল সংযুক্ত · $audioRx অডিও + $videoFramesRx ভিডিও ফ্রেম"
                    } else {
                        "RTP কল ফ্লো: অডিও=$audioRx, ভিডিও=$videoFramesRx ফ্রেম"
                    },
                    avAudioDelivered = audioRx,
                    avVideoFramesDelivered = videoFramesRx,
                    avVideoBytesDelivered = videoBytesRx,
                    avAdaptedKbps = adaptedKbps
                )
            )
        }
    }

    // ---- Offline Payment & HTLC Smart Contracts ----

    fun sendOfflinePayment(amount: Long, memo: String) {
        try {
            val tx = ledger.createPayment(selfId, peerId, amount, memo.ifBlank { "অফলাইন পে" }, ledgerKeys.publicRaw())
            refreshLedgerState("রসিদ ${tx.txId} তৈরি · ননস #${tx.nonce} স্বাক্ষরিত")
        } catch (e: Exception) {
            refreshLedgerState("পেমেন্ট ত্রুটি: ${e.message}")
        }
    }

    fun testReplayProtection() {
        val last = ledger.allTransactions().lastOrNull()
        if (last == null) {
            sendOfflinePayment(50L, "ডাবল-স্পেন্ড টেস্ট")
        }
        val target = ledger.allTransactions().last()
        val replay = MeshLedger.Tx("TX-REPLAY", target.from, target.to, 999L, target.nonce, System.currentTimeMillis(), "Replay", target.senderPubKey, target.signature, target.receiptHash)
        val ok = ledger.applyVerifiedTx(replay)
        refreshLedgerState(
            if (!ok) "ডাবল-স্পেন্ড ব্লক হয়েছে! (ননস #${target.nonce} আগেই ব্যবহৃত)"
            else "অনুমোদিত"
        )
    }

    fun createEscrowContract(amount: Long, secretCode: String) {
        try {
            val secret = secretCode.ifBlank { "mesh-otp-2026" }.toByteArray(StandardCharsets.UTF_8)
            val sc = ledger.createHtlcContract(selfId, merchantId, amount, secret, 3_600_000L)
            refreshLedgerState("HTLC কন্ট্রাক্ট ${sc.contractId} লক হয়েছে ($amount MNT)")
        } catch (e: Exception) {
            refreshLedgerState("কন্ট্রাক্ট ত্রুটি: ${e.message}")
        }
    }

    fun settleLatestEscrow(secretCode: String) {
        val locked = ledger.allContracts().lastOrNull { it.state == MeshLedger.ContractState.LOCKED }
        if (locked == null) {
            refreshLedgerState("কোনো লকড স্মার্ট কন্ট্রাক্ট নেই")
            return
        }
        val secret = secretCode.ifBlank { "mesh-otp-2026" }.toByteArray(StandardCharsets.UTF_8)
        val settled = ledger.settleHtlc(locked.contractId, secret)
        refreshLedgerState(
            if (settled) "${locked.contractId} সেটেলড! ${locked.amount} MNT মার্চেন্টে গেছে"
            else "ভুল সিক্রেট কোড — প্রিইমেজ SHA3-256 মেলেনি"
        )
    }

    fun syncLedgerToServer() {
        val count = ledger.syncToServerBatch()
        refreshLedgerState(
            if (count > 0) "$count-টি অফলাইন রসিদ/কন্ট্রাক্ট সার্ভারে সিঙ্ক সম্পন্ন"
            else "সিঙ্ক করার মতো পেন্ডিং রসিদ নেই"
        )
    }

    private fun refreshLedgerState(msg: String) {
        val rootHex = MeshLedger.hexPrefix(ledger.ledgerRootHash(), 8)
        _state.update {
            it.copy(
                ledger = LedgerHubState(
                    myBalance = ledger.balanceOf(selfId),
                    peerBalance = ledger.balanceOf(peerId),
                    merchantBalance = ledger.balanceOf(merchantId),
                    ledgerRootHex = rootHex,
                    pendingSyncCount = ledger.pendingServerSync().size +
                        ledger.allContracts().count { c -> !c.syncedToServer && c.state != MeshLedger.ContractState.LOCKED },
                    rejectedDoubleSpends = ledger.rejectedDoubleSpends,
                    transactions = ledger.allTransactions().reversed().take(6).map { tx ->
                        LedgerTxUi(tx.txId, tx.from, tx.to, tx.amount, tx.nonce, tx.memo, tx.syncedToServer)
                    },
                    contracts = ledger.allContracts().reversed().take(4).map { c ->
                        LedgerContractUi(c.contractId, c.sender, c.recipient, c.amount, c.state.name, c.syncedToServer)
                    },
                    statusMessage = msg
                )
            )
        }
    }

    // ---- OTA Offline Swarm Updates ----

    fun runOtaSwarmDemo(injectPoisonPeer: Boolean) {
        val clk = SimClock()
        val rng = Random(77L)
        val appId = context.packageName
        val releasePayload = ByteArray(14_000) { i -> ((i * 29 + 11) and 0xff).toByte() }
        val manifest = OtaManifest.create(appId, 24, "v2.4.0-mesh", releasePayload, 1024, ledgerAuth)

        val swarms = HashMap<Int, OtaSwarm>()
        val adj = mapOf(
            0 to listOf(1, 2),
            1 to listOf(0, 2),
            2 to listOf(0, 1)
        )
        var poisonInjected = false

        fun makeSwarm(id: Int, installedVer: Int): OtaSwarm {
            val cfg = OtaSwarm.Config().apply {
                this.appId = appId
                this.installedVersion = installedVer
                this.trustedPublishers = mutableListOf(ledgerAuth.publicKey())
                this.autoDownload = true
                this.tickMs = 150L
            }
            val link = object : OtaSwarm.Link {
                override fun send(peer: Int, message: ByteArray) {
                    val out = message.clone()
                    if (injectPoisonPeer && id == 0 && peer == 2 && !poisonInjected && out.isNotEmpty() && (out[0].toInt() and 0xFF) == 3) {
                        poisonInjected = true
                        if (out.size > 40) out[40] = (out[40].toInt() xor 0xFF).toByte()
                    }
                    clk.schedule(25L) { swarms[peer]?.onMessage(id, out) }
                }
                override fun peers(): MutableList<Int> = (adj[id] ?: emptyList()).toMutableList()
            }
            return OtaSwarm(id, cfg, clk, rng, link, ledgerAuth)
        }

        val seeder = makeSwarm(0, 23)
        val phone1 = makeSwarm(1, 1)
        val phone2 = makeSwarm(2, 1)
        swarms[0] = seeder; swarms[1] = phone1; swarms[2] = phone2
        swarms.values.forEach { it.start() }

        seeder.seed(manifest, releasePayload)
        adj.forEach { (u, nbrs) -> nbrs.forEach { v -> swarms[u]?.onPeerUp(v) } }
        clk.runFor(8_000L)

        val recoveredBytes = phone2.file()
        val ok = recoveredBytes != null && recoveredBytes.contentEquals(releasePayload)
        if (ok) {
            verifiedOtaPayload = recoveredBytes
        }
        val badTotal = phone1.badChunks + phone2.badChunks
        _state.update {
            it.copy(
                ota = OtaHubState(
                    versionName = manifest.versionName,
                    totalChunks = manifest.chunkCount(),
                    peer1Have = phone1.haveChunks(),
                    peer2Have = phone2.haveChunks(),
                    badChunksRejected = badTotal,
                    verifiedBytes = recoveredBytes?.size ?: 0,
                    verifiedBundleReady = ok,
                    statusMessage = if (ok && injectPoisonPeer) {
                        "পয়জন্ড চাঙ্ক বাতিল ($badTotal)! Merkle প্রুফে ২ ফোনে ${manifest.chunkCount()}/${manifest.chunkCount()} চাঙ্ক সিঙ্ক"
                    } else if (ok) {
                        "সফল: P2P সোয়ার্মে ${manifest.chunkCount()}/${manifest.chunkCount()} Merkle চাঙ্ক যাচাই সম্পন্ন"
                    } else {
                        "সোয়ার্ম চলছে (${phone1.haveChunks()}/${manifest.chunkCount()})"
                    }
                )
            )
        }
    }

    fun buildOtaShareIntent(): Intent? {
        val data = verifiedOtaPayload ?: return null
        val dir = File(context.cacheDir, "ota").apply { mkdirs() }
        val outFile = File(dir, "meshnet-update-v2.4.0.bin").apply { writeBytes(data) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "MeshNet Verified Offline Update Bundle")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ---- Zero-Config Mini-Apps (MarketApp + QuizApp over MiniAppHost) ----

    private fun setupMiniApps() {
        val nodeIds = listOf(selfId, peerId, merchantId)
        for (nid in nodeIds) {
            val net = object : MiniAppHost.Net {
                override fun selfId(): Int = nid
                override fun sendReliable(node: Int, frame: ByteArray) {
                    val f = frame.clone()
                    miniClock.schedule(15L) { miniHosts[node]?.onReliableFrame(nid, f) }
                }
                override fun sendNeighbor(node: Int, frame: ByteArray) {
                    val f = frame.clone()
                    miniClock.schedule(10L) { miniHosts[node]?.onNeighborFrame(nid, f) }
                }
                override fun neighbors(): MutableSet<Int> = nodeIds.filter { it != nid }.toMutableSet()
            }
            val host = MiniAppHost(net, miniClock, null, MiniAppHost.Limits())
            val mkt = MarketApp(object : MarketApp.Listener {
                override fun onListing(l: MarketApp.Listing) {
                    if (nid == selfId) appendMiniLog("Market: #${l.seller} ➔ ${l.title} (${l.priceCents / 100}৳)")
                }
                override fun onInterest(buyer: Int, listingId: Long, note: String) {
                    if (nid == selfId) appendMiniLog("Market আগ্রহ: Node $buyer ➔ \"$note\"")
                }
            })
            val quiz = QuizApp(object : QuizApp.Listener {
                override fun onQuestion(quizmaster: Int, round: Int, question: String, choices: Array<out String>) {
                    if (nid == selfId) {
                        _state.update {
                            it.copy(
                                miniApps = it.miniApps.copy(
                                    quizRound = round,
                                    quizQuestion = question,
                                    quizChoices = choices.toList()
                                )
                            )
                        }
                    }
                }
                override fun onResults(round: Int, correctIndex: Int, scores: MutableMap<Int, Int>) {
                    if (nid == selfId) {
                        val summary = "রাউন্ড #$round স্কোর: " + scores.entries.joinToString(", ") { "N${it.key}=${it.value}" }
                        appendMiniLog(summary)
                        _state.update { it.copy(miniApps = it.miniApps.copy(quizResultSummary = summary)) }
                    }
                }
            })
            host.register(mkt)
            host.register(quiz)
            miniHosts[nid] = host
            marketApps[nid] = mkt
            quizApps[nid] = quiz
        }
        miniHosts.values.forEach { it.start() }
        for (u in nodeIds) for (v in nodeIds) if (u != v) miniHosts[u]?.onNeighbor(v, true)
        miniClock.runFor(200L)

        // Seed 2 starter local listings
        marketApps[peerId]?.post("সোলার পাওয়ারব্যাংক ২০,০০০mAh", 1850_00L)
        marketApps[merchantId]?.post("অফলাইন ওয়াকি-টকি হ্যান্ডসেট", 2400_00L)
        miniClock.runFor(200L)
        refreshMiniAppsState()
    }

    fun postMarketListing(title: String, priceBdt: Long) {
        val clean = title.ifBlank { "মেডিক্যাল ফার্স্ট-এইড কিট" }
        marketApps[selfId]?.post(clean, maxOf(10L, priceBdt) * 100L)
        miniClock.runFor(150L)
        refreshMiniAppsState()
    }

    fun expressMarketInterest(listingId: Long) {
        val ok = marketApps[selfId]?.contact(listingId, "আমি এটি কিনতে চাই!") ?: false
        miniClock.runFor(150L)
        if (!ok) appendMiniLog("নিজের লিস্টিং বা লিস্টিং পাওয়া যায়নি")
        refreshMiniAppsState()
    }

    fun runQuizRound() {
        val r = (_state.value.miniApps.quizRound + 1)
        val q = when (r % 3) {
            1 -> "MeshNet-এর ওয়্যার হেডার কত বাইট?"
            2 -> "পোস্ট-কোয়ান্টাম KEM অ্যালগরিদম কোনটি?"
            else -> "জ্যাম হলে ইঞ্জিন কোথায় সুইচ করে?"
        }
        val ch = when (r % 3) {
            1 -> arrayOf("30 বাইট", "128 বাইট", "512 বাইট")
            2 -> arrayOf("ML-KEM-768", "RSA-1024", "MD5")
            else -> arrayOf("5GHz/BLE ফাঁকা চ্যানেলে", "অ্যাপ বন্ধ করে", "কিছুই করে না")
        }
        val master = quizApps[selfId] ?: return
        master.ask(q, ch, 0, 500L)
        miniClock.runFor(100L)
        val curRound = _state.value.miniApps.quizRound
        quizApps[peerId]?.answer(selfId, curRound, 0)
        quizApps[merchantId]?.answer(selfId, curRound, if (r % 2 == 0) 0 else 1)
        miniClock.runFor(600L)
        refreshMiniAppsState()
    }

    private fun refreshMiniAppsState() {
        val items = marketApps[selfId]?.browse()?.map {
            MarketListingUi(it.id, it.seller, it.title, it.priceCents / 100L)
        } ?: emptyList()
        _state.update {
            it.copy(
                miniApps = it.miniApps.copy(
                    runningApps = miniHosts[selfId]?.apps()?.toList() ?: emptyList(),
                    listings = items,
                    logs = miniLogs.toList()
                )
            )
        }
    }

    private fun appendMiniLog(msg: String) {
        miniLogs.add(0, msg)
        while (miniLogs.size > 8) miniLogs.removeAt(miniLogs.size - 1)
    }

    // ---- Offline IPFS (Distributed Sharding + Reed-Solomon Recovery) ----

    private fun publishSampleIpfs() {
        val sample = "MeshNet-IPFS-Emergency-Map-Tile-BD-2026".repeat(24).toByteArray(StandardCharsets.UTF_8)
        ipfs.publish(sample, "application/mesh-tile", 4, 2, ipfsPeers, shardStore)
        refreshIpfsState("স্যাম্পল ম্যাপ ৬-টি পিয়ারে ৪+২ শার্ডে সংরক্ষিত")
    }

    fun publishUriToIpfs(uri: Uri) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            if (bytes.isEmpty()) return
            val capped = if (bytes.size > 256 * 1024) bytes.copyOf(256 * 1024) else bytes
            val mime = context.contentResolver.getType(uri) ?: resolveName(uri)
            val m = ipfs.publish(capped, mime, 4, 2, ipfsPeers, shardStore)
            refreshIpfsState("ছড়ানো হয়েছে: ${m.cid} (${capped.size} B -> ৪ ডাটা + ২ প্যারিটি শার্ড)")
        } catch (e: Exception) {
            refreshIpfsState("IPFS ত্রুটি: ${e.message}")
        }
    }

    fun simulateIpfsRecovery(offlineCount: Int) {
        val first = ipfs.listCatalog().firstOrNull() ?: return
        val offline = offlineCount.coerceIn(0, 4)
        ipfsPeers.forEachIndexed { idx, p -> peerOnline[p] = idx >= offline }
        val recovered = ipfs.fetch(first.cid, shardStore)
        val ok = recovered != null && Keccak.sha3_256(recovered).contentEquals(first.rootHash)
        ipfsPeers.forEach { peerOnline[it] = true }
        val msg = if (ok) {
            "${offline}টি ফোন অফলাইন থাকার পরও ${first.cid} (${recovered!!.size} B) Reed-Solomon-এ শতভাগ রিকভার!"
        } else {
            "${offline}টি ফোন অফলাইন — পর্যাপ্ত প্যারিটি নেই (> ${first.parityShards} লস)"
        }
        _state.update {
            it.copy(
                ipfs = it.ipfs.copy(
                    offlinePeersCount = offline,
                    lastStatus = msg,
                    bloomBytes = ipfs.exportInventoryFilter().toBytes().size
                )
            )
        }
    }

    private fun refreshIpfsState(status: String) {
        _state.update {
            it.copy(
                ipfs = it.ipfs.copy(
                    catalog = ipfs.listCatalog().reversed().map { m ->
                        IpfsCidUi(m.cid, m.mimeType, m.totalBytes, m.dataShards, m.parityShards)
                    },
                    lastStatus = status,
                    bloomBytes = ipfs.exportInventoryFilter().toBytes().size
                )
            )
        }
    }

    private fun resolveName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "application/octet-stream"
        }
        return "application/octet-stream"
    }

    fun release() {
        audioEngine.stopPushToTalk()
        multiRadio.stopAll()
        ledgerClock.shutdown()
    }
}
