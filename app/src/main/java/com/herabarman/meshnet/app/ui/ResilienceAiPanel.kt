package com.herabarman.meshnet.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Warning
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.herabarman.meshnet.app.MeshDashboardViewModel
import com.herabarman.meshnet.app.ui.theme.MeshAmber
import com.herabarman.meshnet.app.ui.theme.MeshCobalt
import com.herabarman.meshnet.app.ui.theme.MeshCoral
import com.herabarman.meshnet.app.ui.theme.MeshMint
import com.herabarman.meshnet.app.ui.theme.MeshPanelElevated
import com.herabarman.meshnet.app.ui.theme.MeshViolet
import com.herabarman.meshnet.app.ui.theme.MonoFont
import com.herabarman.meshnet.core.Priority
import com.herabarman.meshnet.kit.Alerts
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ResilienceAiPanel(
    vm: MeshDashboardViewModel,
    modifier: Modifier = Modifier
) {
    val st by vm.resilienceState.collectAsStateWithLifecycle()

    var lowBatterySlider by remember { mutableFloatStateOf(st.ai.relayBatteryPct.toFloat()) }
    var altBatterySlider by remember { mutableFloatStateOf(st.ai.altBatteryPct.toFloat()) }
    var muleMessage by remember { mutableStateOf("জোন-A থেকে জরুরি মেডিকেল রিপোর্ট") }
    var geoAlertText by remember { mutableStateOf("নিরাপদ আশ্রয় ও সোলার চার্জিং পয়েন্ট") }
    var stegoSecret by remember { mutableStateOf("Emergency Corridor Clear") }
    var crdtMsgInput by remember { mutableStateOf("সবাই মিটিং পয়েন্ট-৩ এ আসুন") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = 720.dp)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. AI Predictive Routing + Energy-Aware Path Selection
        item {
            LabCard(
                title = "AI প্রেডিক্টিভ রাউটিং ও এনার্জি-অ্যাওয়ার পাথ",
                icon = Icons.Rounded.Star,
                accent = MeshMint
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MetricBadge("প্রেডিক্টেড জোন", "Z${st.ai.predictedNextZone}", MeshMint, Modifier.weight(1f))
                    MetricBadge(
                        "অনলাইন সম্ভাবনা",
                        String.format(Locale.ROOT, "%.0f%%", st.ai.peerOnlineLikelihood * 100.0),
                        MeshCobalt,
                        Modifier.weight(1f)
                    )
                    MetricBadge("ETA", "${st.ai.expectedOnlineMins}m", MeshAmber, Modifier.weight(0.75f))
                    MetricBadge("গতি", "${st.ai.inferenceNs} ns", MeshViolet, Modifier.weight(0.9f))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = st.ai.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (st.ai.avoidedLowBattery) MeshMint else MeshAmber
                )
                Text(
                    text = "প্রি-কম্পিউটেড হপ: #${st.ai.precomputedNextHop} (${String.format(Locale.ROOT, "%.0f%%", st.ai.precomputedReliability * 100)} নির্ভরযোগ্যতা) · ট্রেইনিং আপডেট: ${st.ai.trainingUpdates}",
                    fontFamily = MonoFont,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                val lowPct = lowBatterySlider.roundToInt()
                val altPct = altBatterySlider.roundToInt()
                Text(
                    text = "রিলে #১০৬ ব্যাটারি: $lowPct%   |   বিকল্প রিলে #১০৫ ব্যাটারি: $altPct%",
                    style = MaterialTheme.typography.labelSmall
                )
                Slider(
                    value = lowBatterySlider,
                    onValueChange = {
                        lowBatterySlider = it
                        vm.evaluateAiAndEnergy(it.roundToInt(), altBatterySlider.roundToInt())
                    },
                    valueRange = 5f..95f,
                    colors = SliderDefaults.colors(thumbColor = MeshCoral, activeTrackColor = MeshCoral),
                    modifier = Modifier.testTag("ai_low_battery_slider")
                )
                Slider(
                    value = altBatterySlider,
                    onValueChange = {
                        altBatterySlider = it
                        vm.evaluateAiAndEnergy(lowBatterySlider.roundToInt(), it.roundToInt())
                    },
                    valueRange = 10f..100f,
                    colors = SliderDefaults.colors(thumbColor = MeshMint, activeTrackColor = MeshMint),
                    modifier = Modifier.testTag("ai_alt_battery_slider")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { vm.trainAiWithOutcome(true, lowPct, altPct) },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshMint, contentColor = Color(0xFF04110C)),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("ai_train_success_button")
                    ) {
                        Text("AI ট্রেইন (+১ সফল)")
                    }
                    OutlinedButton(
                        onClick = { vm.trainAiWithOutcome(false, lowPct, altPct) },
                        border = BorderStroke(1.dp, MeshCoral),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("ai_train_fail_button")
                    ) {
                        Text("ব্যর্থ পেনাল্টি (-১)", color = MeshCoral)
                    }
                }
            }
        }

        // 2. DTN & Data Mule (Cross-Gap Carry & Forward)
        item {
            LabCard(
                title = "DTN ও ডেটা মিউল (Cross-Gap Carry & Forward)",
                icon = Icons.Rounded.Share,
                accent = MeshCobalt
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricBadge("মিউলে জমা", "${st.dtnMule.carriedItems}", MeshAmber, Modifier.weight(1f))
                    MetricBadge("কপি বাকি", "${st.dtnMule.copiesRemaining}", MeshCobalt, Modifier.weight(1f))
                    MetricBadge("জোন-B ডেলিভার্ড", "${st.dtnMule.deliveredAcrossGap}", MeshMint, Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "অবস্থান: ${st.dtnMule.carrierZone}",
                    fontFamily = MonoFont,
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshAmber
                )
                Text(
                    text = st.dtnMule.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshMint
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = muleMessage,
                    onValueChange = { muleMessage = it },
                    label = { Text("বিচ্ছিন্ন এলাকার জন্য বার্তা") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("mule_message_input")
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { vm.loadMuleAndCarry(muleMessage) },
                        border = BorderStroke(1.dp, MeshAmber),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("mule_load_button")
                    ) {
                        Text("মিউলে লোড (Zone A)", color = MeshAmber)
                    }
                    Button(
                        onClick = { vm.arriveMuleZone() },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshCobalt, contentColor = Color(0xFF04111D)),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("mule_deliver_button")
                    ) {
                        Text("Zone B পৌঁছে ডেলিভার")
                    }
                }
            }
        }

        // 3. Emergency Geofenced SOS Broadcast & Offline Map Pins
        item {
            LabCard(
                title = "ইমার্জেন্সি SOS ব্রডকাস্ট ও অফলাইন ম্যাপ পিন",
                icon = Icons.Rounded.Warning,
                accent = MeshCoral
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricBadge("Geohash", "#${st.geo.currentGeohash}", MeshAmber, Modifier.weight(1f))
                    MetricBadge("Hilbert জোন", "Z${st.geo.hilbertZone}", MeshCobalt, Modifier.weight(1f))
                    MetricBadge("ভেরিফায়েড SOS", "${st.geo.alerts.size}", MeshMint, Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = st.geo.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshMint
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = geoAlertText,
                    onValueChange = { geoAlertText = it },
                    label = { Text("SOS সতর্কতা বা ম্যাপ পিন নোট") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("geo_alert_input")
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { vm.broadcastGeoAlert(Alerts.SOS, geoAlertText, 23.8103, 90.4125) },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshCoral, contentColor = Color(0xFF1F0505)),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("send_geo_sos_button")
                    ) {
                        Text("SOS পাঠান")
                    }
                    OutlinedButton(
                        onClick = { vm.broadcastGeoAlert(Alerts.PIN, geoAlertText, 23.7956, 90.3537) },
                        border = BorderStroke(1.dp, MeshCobalt),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("send_geo_pin_button")
                    ) {
                        Text("ম্যাপ পিন শেয়ার", color = MeshCobalt)
                    }
                }
                if (st.geo.alerts.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    st.geo.alerts.take(3).forEach { alert ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MeshPanelElevated)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${alert.kindLabel}: ${alert.text}", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    text = "#${alert.geohash} · Zone ${alert.zoneNum} · Ed25519 স্বাক্ষরিত",
                                    fontFamily = MonoFont,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MeshAmber
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Steganography & Traffic Masking (Censorship Resistance)
        item {
            LabCard(
                title = "স্টেগানোগ্রাফি ও ট্রাফিক মাস্কিং (DPI বাইপাস)",
                icon = Icons.Rounded.Lock,
                accent = MeshViolet
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricBadge("মোড", st.stego.mode.substringBefore(" "), MeshViolet, Modifier.weight(1f))
                    MetricBadge("মাস্কড সাইজ", "${st.stego.wireBytes} B", MeshCobalt, Modifier.weight(0.9f))
                    MetricBadge("আনমাস্ক", if (st.stego.unmaskedOk) "সফল ✓" else "ব্যর্থ", if (st.stego.unmaskedOk) MeshMint else MeshCoral, Modifier.weight(0.8f))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "ছদ্মবেশী হেক্স: ${st.stego.disguisedHex}",
                    fontFamily = MonoFont,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = st.stego.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshMint
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = stegoSecret,
                    onValueChange = { stegoSecret = it },
                    label = { Text("লুকানো গোপন মেসেজ") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stego_secret_input")
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { vm.runStegoMask("HTTPS", stegoSecret) },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshViolet, contentColor = Color(0xFF0A061D)),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("mask_https_button")
                    ) {
                        Text("HTTPS/TLS")
                    }
                    OutlinedButton(
                        onClick = { vm.runStegoMask("AUDIO", stegoSecret) },
                        border = BorderStroke(1.dp, MeshMint),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("mask_audio_button")
                    ) {
                        Text("Audio LSB", color = MeshMint)
                    }
                    OutlinedButton(
                        onClick = { vm.runStegoMask("IMAGE", stegoSecret) },
                        border = BorderStroke(1.dp, MeshCobalt),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("mask_image_button")
                    ) {
                        Text("Image LSB", color = MeshCobalt)
                    }
                }
            }
        }

        // 5. Cross-Platform Hardware Bridge (LoRa 15km + Satellite + Desktop)
        item {
            LabCard(
                title = "ক্রস-প্ল্যাটফর্ম হার্ডওয়্যার (LoRa 15km + Satellite + Desktop)",
                icon = Icons.Rounded.Settings,
                accent = MeshAmber
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MetricBadge("LoRa TX/RX", "${st.hardware.loraTxFrames}/${st.hardware.loraRxFrames}", MeshMint, Modifier.weight(1f))
                    MetricBadge("Satellite SBD", "${st.hardware.satBursts}", MeshCobalt, Modifier.weight(1f))
                    MetricBadge("Desktop UDP", "${st.hardware.desktopEndpoints}", MeshAmber, Modifier.weight(0.9f))
                    MetricBadge("CRC ত্রুটি", "${st.hardware.kissCrcErrors}", if (st.hardware.kissCrcErrors == 0L) MeshMint else MeshCoral, Modifier.weight(0.8f))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = st.hardware.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshMint
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { vm.runCrossHardwareBridge() },
                    colors = ButtonDefaults.buttonColors(containerColor = MeshAmber, contentColor = Color(0xFF1F1200)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("test_cross_hw_button")
                ) {
                    Text("LoRa KISS + Satellite + Desktop ব্রিজ টেস্ট")
                }
            }
        }

        // 6. Distributed Offline CRDT Groups & Channels
        item {
            LabCard(
                title = "ডিস্ট্রিবিউটেড অফলাইন CRDT গ্রুপ ও চ্যানেল",
                icon = Icons.Rounded.CheckCircle,
                accent = MeshMint
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricBadge("স্ন্যাপশট হ্যাশ", "#${st.crdt.snapshotHash}", MeshCobalt, Modifier.weight(1f))
                    MetricBadge("মেম্বার", "${st.crdt.memberCount}", MeshAmber, Modifier.weight(0.7f))
                    MetricBadge("কনভার্জেন্স", if (st.crdt.converged) "১০০% ✓" else "বাকি", if (st.crdt.converged) MeshMint else MeshCoral, Modifier.weight(0.9f))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = st.crdt.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshMint
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = crdtMsgInput,
                    onValueChange = { crdtMsgInput = it },
                    label = { Text("CRDT গ্রুপ মেসেজ") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("crdt_message_input")
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { vm.postCrdtMessage(crdtMsgInput) },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshMint, contentColor = Color(0xFF04110C)),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("crdt_post_button")
                    ) {
                        Text("CRDT পোস্ট ও সিঙ্ক")
                    }
                    OutlinedButton(
                        onClick = { vm.toggleCrdtReaction() },
                        border = BorderStroke(1.dp, MeshCobalt),
                        modifier = Modifier
                            .weight(0.8f)
                            .minimumInteractiveComponentSize()
                            .testTag("crdt_react_button")
                    ) {
                        Text("👍 রিঅ্যাকশন", color = MeshCobalt)
                    }
                }
                if (st.crdt.messages.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    st.crdt.messages.take(3).forEach { msg ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MeshPanelElevated)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "নোড #${msg.author}: ${msg.text}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "👍 ${msg.likes}",
                                fontFamily = MonoFont,
                                style = MaterialTheme.typography.labelSmall,
                                color = MeshAmber
                            )
                        }
                    }
                }
            }
        }

        // 7. Relay Incentive / Fairness Credit System
        item {
            LabCard(
                title = "রিলে ইনসেনটিভ ও ফেয়ারনেস ক্রেডিট",
                icon = Icons.Rounded.Build,
                accent = MeshCobalt
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricBadge(
                        "সাহায্যকারী #১০১",
                        String.format(Locale.ROOT, "%.2f", st.fairness.relayScore),
                        MeshMint,
                        Modifier.weight(1f)
                    )
                    MetricBadge(
                        "ক্রেডিট / রেট",
                        String.format(Locale.ROOT, "%.1f · %.1fx", st.fairness.relayCredits, st.fairness.relayRateScale),
                        MeshCobalt,
                        Modifier.weight(1.2f)
                    )
                    MetricBadge(
                        "লিচ #২০৯",
                        priorityLabel(st.fairness.leechPriority),
                        if (st.fairness.leechPriority <= Priority.BULK) MeshCoral else MeshAmber,
                        Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = st.fairness.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshMint
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { vm.recordHelpfulRelay() },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshMint, contentColor = Color(0xFF04110C)),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("fairness_relay_button")
                    ) {
                        Text("রিলে করুন (+ক্রেডিট)")
                    }
                    OutlinedButton(
                        onClick = { vm.recordSelfishTraffic() },
                        border = BorderStroke(1.dp, MeshCoral),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("fairness_leech_button")
                    ) {
                        Text("লিচ সিমুলেশন", color = MeshCoral)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

private fun priorityLabel(priority: Int): String = Priority.name(priority).uppercase(Locale.ROOT)
