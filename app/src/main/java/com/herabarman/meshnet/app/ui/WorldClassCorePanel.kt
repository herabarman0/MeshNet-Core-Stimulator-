package com.herabarman.meshnet.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.herabarman.meshnet.app.MeshDashboardViewModel
import com.herabarman.meshnet.app.ui.theme.MeshAmber
import com.herabarman.meshnet.app.ui.theme.MeshCobalt
import com.herabarman.meshnet.app.ui.theme.MeshCoral
import com.herabarman.meshnet.app.ui.theme.MeshMint
import com.herabarman.meshnet.app.ui.theme.MeshViolet
import com.herabarman.meshnet.app.ui.theme.MonoFont
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun WorldClassCorePanel(
    vm: MeshDashboardViewModel,
    modifier: Modifier = Modifier
) {
    val wc by vm.worldClassState.collectAsStateWithLifecycle()

    var rssiSlider by remember { mutableFloatStateOf(wc.routing.node104Rssi.toFloat()) }
    var lossSlider by remember { mutableFloatStateOf(wc.routing.node104LossPct.toFloat()) }
    var ratchetMsg by remember { mutableStateOf("অফলাইন ডাবল-র‍্যাচেট টেস্ট") }
    var onionMsg by remember { mutableStateOf("Hidden Sender Dispatch") }
    var dutyBatterySlider by remember { mutableFloatStateOf(wc.optimization.batteryPct.toFloat()) }
    var dutyPeersSlider by remember { mutableFloatStateOf(wc.optimization.neighborCount.toFloat()) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = 720.dp)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Adaptive Routing (AOMDV + B.A.T.M.A.N. TQ)
        item {
            LabCard(
                title = "১. Adaptive Routing (AOMDV + B.A.T.M.A.N.)",
                icon = Icons.Rounded.Share,
                accent = MeshMint
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MetricBadge("নির্বাচিত হপ", "#${wc.routing.chosenHop}", MeshMint, Modifier.weight(0.9f))
                    MetricBadge("RSSI", "${wc.routing.rssiDbm.roundToInt()}dBm", MeshCobalt, Modifier.weight(0.9f))
                    MetricBadge("লেটেন্সি", "${wc.routing.rttMs.roundToInt()}ms", MeshAmber, Modifier.weight(0.9f))
                    MetricBadge("TQ কোয়ালিটি", String.format(Locale.ROOT, "%.0f%%", wc.routing.batmanTq * 100), MeshViolet, Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Text(wc.routing.summary, style = MaterialTheme.typography.bodySmall, color = MeshMint)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "রিলে #১০৪ RSSI: ${rssiSlider.roundToInt()} dBm   |   প্যাকেট লস: ${lossSlider.roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall
                )
                Slider(
                    value = rssiSlider,
                    onValueChange = {
                        rssiSlider = it
                        vm.evaluateAdaptiveRouting(it.roundToInt(), lossSlider.roundToInt())
                    },
                    valueRange = -90f..-50f,
                    colors = SliderDefaults.colors(thumbColor = MeshCobalt, activeTrackColor = MeshCobalt),
                    modifier = Modifier.testTag("adaptive_rssi_slider")
                )
                Slider(
                    value = lossSlider,
                    onValueChange = {
                        lossSlider = it
                        vm.evaluateAdaptiveRouting(rssiSlider.roundToInt(), it.roundToInt())
                    },
                    valueRange = 0f..60f,
                    colors = SliderDefaults.colors(thumbColor = MeshCoral, activeTrackColor = MeshCoral),
                    modifier = Modifier.testTag("adaptive_loss_slider")
                )
            }
        }

        // 2. Multi-Transport Bonding + Wi-Fi Aware + Ultrasonic + NAT Traversal
        item {
            LabCard(
                title = "মাল্টি-ট্রান্সপোর্ট বন্ডিং ও NAT Hole Punch",
                icon = Icons.Rounded.Settings,
                accent = MeshCobalt
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MetricBadge("বন্ডেড মাধ্যম", wc.bonding.activeTransport.substringBefore(" "), MeshMint, Modifier.weight(1f))
                    MetricBadge("NAT ধরন", wc.nat.natType, MeshAmber, Modifier.weight(0.8f))
                    MetricBadge(
                        "P2P টানেল",
                        if (wc.nat.directTunnelOpen) "ওপেন ✓" else "TURN",
                        if (wc.nat.directTunnelOpen) MeshMint else MeshCoral,
                        Modifier.weight(0.8f)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(wc.bonding.status, style = MaterialTheme.typography.bodySmall, color = MeshCobalt)
                Text(wc.nat.status, style = MaterialTheme.typography.bodySmall, color = MeshMint)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { vm.runTransportBonding(256, isRealtimeVoice = false) },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshCobalt, contentColor = Color(0xFF04111D)),
                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("bonding_bulk_button")
                    ) {
                        Text("ফাইল বন্ডিং")
                    }
                    OutlinedButton(
                        onClick = { vm.runUltrasonicChirpTest() },
                        border = BorderStroke(1.dp, MeshViolet),
                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("ultrasonic_chirp_button")
                    ) {
                        Text("Ultrasonic 19kHz", color = MeshViolet)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { vm.runNatTraversalPunch(simulateSymmetricNat = false) },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshMint, contentColor = Color(0xFF04110C)),
                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("nat_cone_punch_button")
                    ) {
                        Text("STUN Hole Punch")
                    }
                    OutlinedButton(
                        onClick = { vm.runNatTraversalPunch(simulateSymmetricNat = true) },
                        border = BorderStroke(1.dp, MeshAmber),
                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("nat_symmetric_turn_button")
                    ) {
                        Text("Symmetric TURN", color = MeshAmber)
                    }
                }
            }
        }

        // 3. Security & Privacy (Double Ratchet + Onion Routing + Sybil PoW)
        item {
            LabCard(
                title = "২. Double Ratchet · Onion · Sybil PoW",
                icon = Icons.Rounded.Lock,
                accent = MeshViolet
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MetricBadge("র‍্যাচেট স্টেপ", "#${wc.security.ratchetSteps}", MeshMint, Modifier.weight(0.9f))
                    MetricBadge("অনিয়ন ব্লাইন্ড", if (wc.security.onionBlindHopsVerified) "যাচাই ✓" else "ব্যর্থ", MeshViolet, Modifier.weight(1f))
                    MetricBadge("Sybil ব্লক", "${wc.security.sybilBlocked}", MeshCoral, Modifier.weight(0.9f))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "RootKey: ${wc.security.rootKeyHex} · DH: ${wc.security.dhKeyHex}",
                    fontFamily = MonoFont,
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshCobalt
                )
                Text(wc.security.ratchetStatus, style = MaterialTheme.typography.bodySmall, color = MeshMint)
                Text(wc.security.onionStatus, style = MaterialTheme.typography.bodySmall, color = MeshViolet)
                Text(wc.security.sybilStatus, style = MaterialTheme.typography.bodySmall, color = MeshAmber)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = ratchetMsg,
                        onValueChange = { ratchetMsg = it },
                        label = { Text("র‍্যাচেট মেসেজ") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("ratchet_msg_input")
                    )
                    OutlinedTextField(
                        value = onionMsg,
                        onValueChange = { onionMsg = it },
                        label = { Text("অনিয়ন পেলোড") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("onion_msg_input")
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { vm.sendDoubleRatchetMessage(ratchetMsg) },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshMint, contentColor = Color(0xFF04110C)),
                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("ratchet_send_button")
                    ) {
                        Text("Ratchet এনক্রিপ্ট")
                    }
                    OutlinedButton(
                        onClick = { vm.testOutOfOrderAndHealRatchet() },
                        border = BorderStroke(1.dp, MeshCobalt),
                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("ratchet_heal_button")
                    ) {
                        Text("DH হিলিং", color = MeshCobalt)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { vm.runOnionRoutingHopTest(onionMsg) },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshViolet, contentColor = Color(0xFF0A061D)),
                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("onion_hop_button")
                    ) {
                        Text("৩-হপ Onion")
                    }
                    OutlinedButton(
                        onClick = { vm.runSybilPowFloodTest(simulateFlood = true) },
                        border = BorderStroke(1.dp, MeshCoral),
                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("sybil_flood_button")
                    ) {
                        Text("Sybil ফ্লাড টেস্ট", color = MeshCoral)
                    }
                }
            }
        }

        // 4. Data Transfer & Performance (LZ4 + GF(256) RS-FEC + Priority QoS)
        item {
            LabCard(
                title = "৩. LZ4 সংকোচন + RS-FEC + Priority QoS",
                icon = Icons.Rounded.CheckCircle,
                accent = MeshAmber
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricBadge("আসল ➔ LZ4", "${wc.pipeline.rawBytes}➔${wc.pipeline.compressedBytes}B", MeshMint, Modifier.weight(1.2f))
                    MetricBadge("অনুপাত", String.format(Locale.ROOT, "%.1fx", wc.pipeline.compressionRatio), MeshCobalt, Modifier.weight(0.8f))
                    MetricBadge("FEC + QoS", if (wc.pipeline.fecRecoveredOk && wc.pipeline.qosSosFirst) "সফল ✓" else "ত্রুটি", MeshAmber, Modifier.weight(0.9f))
                }
                Spacer(Modifier.height(6.dp))
                Text(wc.pipeline.status, style = MaterialTheme.typography.bodySmall, color = MeshMint)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        vm.runCompressFecQosPipeline("{\"mesh\":\"v2\",\"zone\":4,\"gps\":[23.81,90.41],\"status\":\"OK\"}".repeat(6))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MeshAmber, contentColor = Color(0xFF1F1200)),
                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("pipeline_lz4_fec_button")
                ) {
                    Text("LZ4 + ৩৩% লস FEC + SOS QoS চালান")
                }
            }
        }

        // 5. Mobile Optimization (Adaptive Duty Cycling + Rust/C++ Native Core)
        item {
            LabCard(
                title = "৪. Adaptive Duty Cycling ও Rust/C++ Native Core",
                icon = Icons.Rounded.Build,
                accent = MeshMint
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MetricBadge("ডিউটি (On/Off)", "${wc.optimization.dutyOnSec}s / ${wc.optimization.dutyOffSec}s", MeshCobalt, Modifier.weight(1.1f))
                    MetricBadge("ব্যাটারি সেভ", "${wc.optimization.dutySavePct}%", MeshMint, Modifier.weight(0.9f))
                    MetricBadge("Native গতি", String.format(Locale.ROOT, "%.0fns", wc.optimization.nativeNsPerOp), MeshViolet, Modifier.weight(0.9f))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "ABI: ${wc.optimization.nativeAbi} · ${String.format(Locale.ROOT, "%,d", wc.optimization.nativeOpsPerSec)} ops/s",
                    fontFamily = MonoFont,
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshMint
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "ব্যাটারি: ${dutyBatterySlider.roundToInt()}%   |   আশেপাশে পিয়ার: ${dutyPeersSlider.roundToInt()}",
                    style = MaterialTheme.typography.labelSmall
                )
                Slider(
                    value = dutyBatterySlider,
                    onValueChange = {
                        dutyBatterySlider = it
                        vm.updateAdaptiveDuty(it.roundToInt(), dutyPeersSlider.roundToInt(), batterySaver = false)
                    },
                    valueRange = 5f..100f,
                    colors = SliderDefaults.colors(thumbColor = MeshMint, activeTrackColor = MeshMint),
                    modifier = Modifier.testTag("duty_battery_slider")
                )
                Slider(
                    value = dutyPeersSlider,
                    onValueChange = {
                        dutyPeersSlider = it
                        vm.updateAdaptiveDuty(dutyBatterySlider.roundToInt(), it.roundToInt(), batterySaver = false)
                    },
                    valueRange = 0f..6f,
                    steps = 5,
                    colors = SliderDefaults.colors(thumbColor = MeshCobalt, activeTrackColor = MeshCobalt),
                    modifier = Modifier.testTag("duty_peers_slider")
                )
                Button(
                    onClick = { vm.runNativeCoreBenchmark() },
                    colors = ButtonDefaults.buttonColors(containerColor = MeshViolet, contentColor = Color(0xFF0A061D)),
                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("native_core_bench_button")
                ) {
                    Text("Rust/C++ কনফরমেন্স ও স্পিড বেঞ্চমার্ক")
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}
