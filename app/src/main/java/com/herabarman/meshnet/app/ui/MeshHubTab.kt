package com.herabarman.meshnet.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.herabarman.meshnet.app.MeshDashboardViewModel
import com.herabarman.meshnet.app.ui.theme.MeshAmber
import com.herabarman.meshnet.app.ui.theme.MeshBg
import com.herabarman.meshnet.app.ui.theme.MeshCobalt
import com.herabarman.meshnet.app.ui.theme.MeshCoral
import com.herabarman.meshnet.app.ui.theme.MeshMint
import com.herabarman.meshnet.app.ui.theme.MeshPanelElevated
import com.herabarman.meshnet.app.ui.theme.MeshViolet
import com.herabarman.meshnet.app.ui.theme.MonoFont
import kotlin.math.roundToInt

@Composable
fun MeshHubTab(
    vm: MeshDashboardViewModel,
    modifier: Modifier = Modifier
) {
    val hub by vm.hubState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var payMemo by remember { mutableStateOf("বাজার পেমেন্ট") }
    var htlcSecret by remember { mutableStateOf("mesh-otp-2026") }
    var marketTitle by remember { mutableStateOf("সোলার টর্চ ও চার্জার") }
    var offlineIpfsSlider by remember { mutableFloatStateOf(2f) }

    var pendingAction by remember { mutableStateOf<String?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allOk = results.values.all { it }
        vm.updatePermissions(allOk)
        when (pendingAction) {
            "radios" -> vm.toggleRadios()
            "ptt" -> if (results[android.Manifest.permission.RECORD_AUDIO] == true) vm.togglePtt(loopback = true)
        }
        pendingAction = null
    }

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) vm.publishIpfsUri(uri)
    }

    val docPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.publishIpfsUri(uri)
    }

    var subTab by remember { mutableStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0E1626), MeshBg, MeshBg))),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "MeshNet সুপার-হাব",
                style = MaterialTheme.typography.headlineSmall,
                color = MeshMint
            )
            Text(
                text = "BLE · Wi-Fi · LoRa · Satellite · AI Routing · Stego · CRDT · Ledger · IPFS",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = { subTab = 0 },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (subTab == 0) MeshMint else MeshPanelElevated,
                        contentColor = if (subTab == 0) Color(0xFF04110C) else MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("hub_subtab_radio")
                ) {
                    Text("রেডিও · পে", maxLines = 1)
                }
                Button(
                    onClick = { subTab = 1 },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (subTab == 1) MeshCobalt else MeshPanelElevated,
                        contentColor = if (subTab == 1) Color(0xFF04111D) else MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("hub_subtab_ai")
                ) {
                    Text("AI · LoRa · মাস্ক", maxLines = 1)
                }
                Button(
                    onClick = { subTab = 2 },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (subTab == 2) MeshViolet else MeshPanelElevated,
                        contentColor = if (subTab == 2) Color(0xFF0A061D) else MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("hub_subtab_worldclass")
                ) {
                    Text("কোর", maxLines = 1)
                }
                Button(
                    onClick = { subTab = 3 },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (subTab == 3) MeshAmber else MeshPanelElevated,
                        contentColor = if (subTab == 3) Color(0xFF1F1200) else MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .weight(1.1f)
                        .minimumInteractiveComponentSize()
                        .testTag("hub_subtab_services")
                ) {
                    Text("ফোন·ছবি", maxLines = 1)
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (subTab == 1) {
            ResilienceAiPanel(vm = vm, modifier = Modifier.weight(1f))
        } else if (subTab == 2) {
            WorldClassCorePanel(vm = vm, modifier = Modifier.weight(1f))
        } else if (subTab == 3) {
            ServicesToolsPanel(vm = vm, modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .widthIn(max = 720.dp)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

            // 1. Multi-Radio Turbo Transport & Anti-Jamming
            item {
                LabCard(
                    title = "টার্বো মাল্টি-রেডিও ও অ্যান্টি-জ্যামিং",
                    icon = Icons.Rounded.Share,
                    accent = MeshMint
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MetricBadge("সক্রিয় চ্যানেল", hub.radio.activeChannelName, MeshMint, Modifier.weight(1.3f))
                        MetricBadge("Nearby", "${hub.radio.nearbyPeers}", MeshCobalt, Modifier.weight(0.7f))
                        MetricBadge("BLE", "${hub.radio.blePeers}", MeshAmber, Modifier.weight(0.7f))
                        MetricBadge("P2P", "${hub.radio.wifiDirectPeers}", MeshViolet, Modifier.weight(0.7f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = hub.radio.turboSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshMint
                    )
                    if (hub.radio.hotspotSsid.isNotEmpty()) {
                        Text(
                            text = "Hotspot SSID: ${hub.radio.hotspotSsid}  Pass: ${hub.radio.hotspotPassphrase}",
                            fontFamily = MonoFont,
                            style = MaterialTheme.typography.labelSmall,
                            color = MeshAmber
                        )
                    } else {
                        Text(
                            text = hub.radio.hotspotStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { permLauncher.launch(vm.requiredRuntimePermissions()) },
                            border = BorderStroke(1.dp, MeshCobalt),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("grant_radio_perms_button")
                        ) {
                            Text("অনুমতি দিন", color = MeshCobalt)
                        }
                        Button(
                            onClick = {
                                if (!hub.radio.radiosActive && !hub.radio.permissionsGranted) {
                                    pendingAction = "radios"
                                    permLauncher.launch(vm.requiredRuntimePermissions())
                                } else {
                                    vm.toggleRadios()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (hub.radio.radiosActive) MeshCoral else MeshMint,
                                contentColor = Color(0xFF04110C)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("toggle_radios_button")
                        ) {
                            Text(if (hub.radio.radiosActive) "রেডিও বন্ধ" else "সব রেডিও চালু")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { vm.toggleHotspot() },
                            border = BorderStroke(1.dp, MeshAmber),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("toggle_hotspot_button")
                        ) {
                            Text(if (hub.radio.hotspotActive) "হটস্পট বন্ধ" else "অফলাইন হটস্পট", color = MeshAmber)
                        }
                        Button(
                            onClick = { vm.runTurboAndJam(injectJam = false) },
                            colors = ButtonDefaults.buttonColors(containerColor = MeshCobalt, contentColor = Color(0xFF04111D)),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("turbo_burst_button")
                        ) {
                            Text("টার্বো বার্স্ট")
                        }
                        OutlinedButton(
                            onClick = { vm.runTurboAndJam(injectJam = true) },
                            border = BorderStroke(1.dp, MeshCoral),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("inject_jam_button")
                        ) {
                            Text("জ্যাম টেস্ট", color = MeshCoral)
                        }
                    }
                    if (hub.radio.channelLogs.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        hub.radio.channelLogs.take(3).forEach { log ->
                            Text("• $log", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // 2. Offline Voice PTT & Multi-Hop Video/Audio RTP Calling
            item {
                LabCard(
                    title = "অফলাইন ভয়েস (PTT) ও মাল্টি-হপ ভিডিও RTP",
                    icon = Icons.Rounded.Phone,
                    accent = MeshCobalt
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricBadge("মাইক ফ্রেম", "${hub.voice.framesCaptured}", MeshMint, Modifier.weight(1f))
                        MetricBadge("মাল্টি-হপ অডিও", "${hub.voice.avAudioDelivered}", MeshCobalt, Modifier.weight(1f))
                        MetricBadge("ভিডিও ফ্রেম", "${hub.voice.avVideoFramesDelivered}", MeshAmber, Modifier.weight(1f))
                        MetricBadge("বিটরেট", "${hub.voice.avAdaptedKbps}k", MeshViolet, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { hub.voice.micLevel.coerceIn(0f, 1f) },
                        color = if (hub.voice.isTalking) MeshMint else MeshCobalt,
                        trackColor = MeshPanelElevated,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = hub.voice.audioError ?: hub.voice.avCallSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hub.voice.audioError != null) MeshCoral else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (!hub.voice.isTalking &&
                                    androidx.core.content.ContextCompat.checkSelfPermission(
                                        context,
                                        android.Manifest.permission.RECORD_AUDIO
                                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) {
                                    pendingAction = "ptt"
                                    permLauncher.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
                                } else {
                                    vm.togglePtt(loopback = true)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (hub.voice.isTalking) MeshCoral else MeshMint,
                                contentColor = Color(0xFF04110C)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("ptt_talk_button")
                        ) {
                            Text(if (hub.voice.isTalking) "PTT বন্ধ করুন" else "লাইভ ওয়াকি-টকি (PTT)")
                        }
                        Button(
                            onClick = { vm.runAvCallDemo() },
                            colors = ButtonDefaults.buttonColors(containerColor = MeshCobalt, contentColor = Color(0xFF04111D)),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("av_call_test_button")
                        ) {
                            Text("২-হপ ভিডিও কল টেস্ট")
                        }
                    }
                }
            }

            // 3. Offline Payment & Smart Contract Ledger
            item {
                LabCard(
                    title = "অফলাইন পেমেন্ট ও স্মার্ট কন্ট্রাক্ট (Mesh Ledger)",
                    icon = Icons.Rounded.Lock,
                    accent = MeshAmber
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricBadge("আমার ব্যালেন্স", "${hub.ledger.myBalance}৳", MeshMint, Modifier.weight(1f))
                        MetricBadge("পিয়ার (২০২)", "${hub.ledger.peerBalance}৳", MeshCobalt, Modifier.weight(1f))
                        MetricBadge("মার্চেন্ট (৩০৩)", "${hub.ledger.merchantBalance}৳", MeshAmber, Modifier.weight(1f))
                        MetricBadge("পেন্ডিং সিঙ্ক", "${hub.ledger.pendingSyncCount}", MeshViolet, Modifier.weight(0.8f))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Merkle Root: ${hub.ledger.ledgerRootHex} · রিজেক্টেড ডাবল-স্পেন্ড: ${hub.ledger.rejectedDoubleSpends}",
                        fontFamily = MonoFont,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = hub.ledger.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshMint
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = payMemo,
                            onValueChange = { payMemo = it },
                            label = { Text("পেমেন্ট মেমো") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("ledger_memo_input")
                        )
                        OutlinedTextField(
                            value = htlcSecret,
                            onValueChange = { htlcSecret = it },
                            label = { Text("HTLC সিক্রেট কোড") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("htlc_secret_input")
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { vm.sendPayment(100L, payMemo) },
                            colors = ButtonDefaults.buttonColors(containerColor = MeshMint, contentColor = Color(0xFF04110C)),
                            modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("send_offline_pay_button")
                        ) {
                            Text("১০০৳ অফলাইন পে")
                        }
                        OutlinedButton(
                            onClick = { vm.testReplayProtection() },
                            border = BorderStroke(1.dp, MeshCoral),
                            modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("double_spend_test_button")
                        ) {
                            Text("ডাবল-স্পেন্ড টেস্ট", color = MeshCoral)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { vm.createEscrow(150L, htlcSecret) },
                            border = BorderStroke(1.dp, MeshAmber),
                            modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("create_htlc_button")
                        ) {
                            Text("HTLC লক (১৫০৳)", color = MeshAmber)
                        }
                        Button(
                            onClick = { vm.settleEscrow(htlcSecret) },
                            colors = ButtonDefaults.buttonColors(containerColor = MeshAmber, contentColor = Color(0xFF1F1200)),
                            modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("settle_htlc_button")
                        ) {
                            Text("কোড দিয়ে রিলিজ")
                        }
                        OutlinedButton(
                            onClick = { vm.syncLedger() },
                            border = BorderStroke(1.dp, MeshCobalt),
                            modifier = Modifier.weight(0.9f).minimumInteractiveComponentSize().testTag("sync_ledger_button")
                        ) {
                            Text("সার্ভার সিঙ্ক", color = MeshCobalt)
                        }
                    }
                }
            }

            // 4. Over-the-Air (OTA) Offline App Update Swarm
            item {
                LabCard(
                    title = "ওভার-দ্য-এয়ার (OTA) অফলাইন অ্যাপ আপডেট",
                    icon = Icons.Rounded.Refresh,
                    accent = MeshViolet
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricBadge("ভার্সন", hub.ota.versionName, MeshViolet, Modifier.weight(1f))
                        MetricBadge("ফোন-১", "${hub.ota.peer1Have}/${hub.ota.totalChunks}", MeshMint, Modifier.weight(0.8f))
                        MetricBadge("ফোন-২", "${hub.ota.peer2Have}/${hub.ota.totalChunks}", MeshCobalt, Modifier.weight(0.8f))
                        MetricBadge("পয়জন ব্লক", "${hub.ota.badChunksRejected}", MeshCoral, Modifier.weight(0.8f))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = hub.ota.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hub.ota.verifiedBundleReady) MeshMint else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { vm.runOtaSwarm(injectPoison = true) },
                            colors = ButtonDefaults.buttonColors(containerColor = MeshViolet, contentColor = Color(0xFF0A061D)),
                            modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("run_ota_swarm_button")
                        ) {
                            Text("OTA সোয়ার্ম ও পয়জন চেক")
                        }
                        OutlinedButton(
                            onClick = {
                                val intent = vm.buildOtaShareIntent()
                                if (intent != null) {
                                    context.startActivity(Intent.createChooser(intent, "যাচাইকৃত অফলাইন আপডেট শেয়ার"))
                                }
                            },
                            enabled = hub.ota.verifiedBundleReady,
                            border = BorderStroke(1.dp, MeshMint),
                            modifier = Modifier.weight(0.9f).minimumInteractiveComponentSize().testTag("share_ota_bundle_button")
                        ) {
                            Text("বান্ডিল শেয়ার", color = if (hub.ota.verifiedBundleReady) MeshMint else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // 5. Zero-Config Mini-Apps (Market & Quiz)
            item {
                LabCard(
                    title = "জিরো-কনফিগ মিনি-অ্যাপ প্লাগইন (Market + Quiz)",
                    icon = Icons.Rounded.ShoppingCart,
                    accent = MeshMint
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = marketTitle,
                            onValueChange = { marketTitle = it },
                            label = { Text("মার্কেটপ্লেস আইটেম") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("market_title_input")
                        )
                        Button(
                            onClick = { vm.postMarketListing(marketTitle, 950L) },
                            colors = ButtonDefaults.buttonColors(containerColor = MeshMint, contentColor = Color(0xFF04110C)),
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("post_market_button")
                        ) {
                            Text("পোস্ট")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    hub.miniApps.listings.take(3).forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MeshPanelElevated)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.bodySmall)
                                Text("বিক্রেতা #${item.seller} · ${item.priceBdt}৳", style = MaterialTheme.typography.labelSmall, color = MeshAmber)
                            }
                            OutlinedButton(
                                onClick = { vm.contactMarketSeller(item.id) },
                                border = BorderStroke(1.dp, MeshCobalt),
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Text("কিনুন", color = MeshCobalt)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { vm.runQuizRound() },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshCobalt, contentColor = Color(0xFF04111D)),
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("run_quiz_round_button")
                    ) {
                        Text("মেশ কুইজ রাউন্ড চালান")
                    }
                    if (hub.miniApps.quizQuestion.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("প্রশ্ন: ${hub.miniApps.quizQuestion}", style = MaterialTheme.typography.bodySmall, color = MeshMint)
                    }
                    Text(
                        text = hub.miniApps.quizResultSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 6. Distributed File Storage (Offline IPFS + Reed-Solomon)
            item {
                LabCard(
                    title = "ডিস্ট্রিবিউটেড ফাইল স্টোরেজ (Offline IPFS)",
                    icon = Icons.Rounded.Build,
                    accent = MeshAmber
                ) {
                    Text(
                        text = hub.ipfs.lastStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshMint
                    )
                    Text(
                        text = "Bloom ইনভেন্টরি: ${hub.ipfs.bloomBytes} B · মোট পিন্ড CID: ${hub.ipfs.catalog.size}",
                        fontFamily = MonoFont,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MeshAmber, contentColor = Color(0xFF1F1200)),
                            modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("ipfs_pick_media_button")
                        ) {
                            Text("ছবি/ভিডিও ছড়ান")
                        }
                        OutlinedButton(
                            onClick = { docPicker.launch(arrayOf("*/*")) },
                            border = BorderStroke(1.dp, MeshCobalt),
                            modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("ipfs_pick_doc_button")
                        ) {
                            Text("যেকোনো ফাইল", color = MeshCobalt)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val offCount = offlineIpfsSlider.roundToInt()
                    Text(
                        text = "অফলাইন পিয়ার সিমুলেশন: $offCount / 6টি ফোন বন্ধ",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = offlineIpfsSlider,
                        onValueChange = {
                            offlineIpfsSlider = it
                            vm.testIpfsRecovery(it.roundToInt())
                        },
                        valueRange = 0f..4f,
                        steps = 3,
                        colors = SliderDefaults.colors(thumbColor = MeshAmber, activeTrackColor = MeshAmber),
                        modifier = Modifier.testTag("ipfs_offline_slider")
                    )
                    hub.ipfs.catalog.take(3).forEach { c ->
                        Text(
                            text = "• ${c.cid} (${c.totalBytes} B · ${c.dataShards}+${c.parityShards} RS)",
                            fontFamily = MonoFont,
                            style = MaterialTheme.typography.labelSmall,
                            color = MeshCobalt
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
        }
    }
}
