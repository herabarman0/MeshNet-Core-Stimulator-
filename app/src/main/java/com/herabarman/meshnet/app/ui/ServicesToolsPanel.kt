package com.herabarman.meshnet.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
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
import com.herabarman.meshnet.kit.LostPhone
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ServicesToolsPanel(
    vm: MeshDashboardViewModel,
    modifier: Modifier = Modifier
) {
    val svc by vm.servicesState.collectAsStateWithLifecycle()

    var seedInput by remember { mutableStateOf("MeshMasterSeed-2026") }
    var cardInput by remember(svc.contact.myCardUri) { mutableStateOf(svc.contact.myCardUri) }
    var batterySlider by remember { mutableFloatStateOf(svc.gateway.batteryPct.toFloat()) }
    var gatewayDesired by remember { mutableStateOf(true) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) vm.compressPhotoUri(uri)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 720.dp)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Offline Lost Phone Finder
        item {
            LabCard(
                title = "অফলাইন LostPhone ফাইন্ডার (ইন্টারনেট ছাড়াই)",
                icon = Icons.Rounded.Warning,
                accent = MeshCoral
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MetricBadge("অবস্থা", if (svc.lostPhone.armed) "Armed" else "Disarmed", if (svc.lostPhone.armed) MeshMint else MeshAmber, Modifier.weight(1f))
                    MetricBadge("সাইটিং", "${svc.lostPhone.sightings.size}", MeshCobalt, Modifier.weight(0.8f))
                    MetricBadge("বেকন", "${svc.lostPhone.beaconsSent}", MeshViolet, Modifier.weight(0.8f))
                    MetricBadge("স্টকার অ্যালার্ট", "${svc.lostPhone.trackerAlertCount}", MeshCoral, Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "ব্যাকআপ ডেরাইভড সিক্রেট: ${svc.lostPhone.backupSecretHex}",
                    fontFamily = MonoFont,
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshAmber
                )
                Text(
                    text = svc.lostPhone.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshMint
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = seedInput,
                        onValueChange = { seedInput = it },
                        label = { Text("মাস্টার পাসফ্রেজ") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("lost_seed_input")
                    )
                    Button(
                        onClick = { vm.regenerateLostSecret(seedInput) },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshCobalt, contentColor = Color(0xFF04111D)),
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("lost_derive_button")
                    ) {
                        Text("কী তৈরি")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { vm.toggleLostPhoneArm(!svc.lostPhone.armed) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (svc.lostPhone.armed) MeshAmber else MeshMint,
                            contentColor = Color(0xFF04110C)
                        ),
                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("lost_arm_button")
                    ) {
                        Text(if (svc.lostPhone.armed) "Disarm করুন" else "Arm করুন")
                    }
                    Button(
                        onClick = { vm.triggerLastGaspAndSighting() },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshMint, contentColor = Color(0xFF04110C)),
                        modifier = Modifier.weight(1.2f).minimumInteractiveComponentSize().testTag("lost_lastgasp_button")
                    ) {
                        Text("Last-Gasp ও লোকেশন")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = { vm.sendLostRemoteCommand(LostPhone.CMD_RING) },
                        border = BorderStroke(1.dp, MeshCobalt),
                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("lost_cmd_ring")
                    ) {
                        Text("RING", color = MeshCobalt)
                    }
                    OutlinedButton(
                        onClick = { vm.sendLostRemoteCommand(LostPhone.CMD_LOCK) },
                        border = BorderStroke(1.dp, MeshAmber),
                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("lost_cmd_lock")
                    ) {
                        Text("LOCK", color = MeshAmber)
                    }
                    OutlinedButton(
                        onClick = { vm.sendLostRemoteCommand(LostPhone.CMD_WIPE) },
                        border = BorderStroke(1.dp, MeshCoral),
                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("lost_cmd_wipe")
                    ) {
                        Text("WIPE", color = MeshCoral)
                    }
                    OutlinedButton(
                        onClick = { vm.simulateAntiStalking() },
                        border = BorderStroke(1.dp, MeshViolet),
                        modifier = Modifier.weight(1.2f).minimumInteractiveComponentSize().testTag("lost_stalker_test")
                    ) {
                        Text("স্টকার চেক", color = MeshViolet)
                    }
                }
                if (svc.lostPhone.sightings.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    svc.lostPhone.sightings.take(3).forEach { s ->
                        Text(
                            text = "• ${String.format(Locale.ROOT, "%.5f, %.5f", s.lat, s.lon)} ±${s.accuracyM}m ${if (s.own) "(নিজস্ব Last-Gasp)" else "(পিয়ার সাইটিং)"}",
                            fontFamily = MonoFont,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (s.own) MeshAmber else MeshCobalt
                        )
                    }
                }
            }
        }

        // 2. Visually-Lossless Ultra-Compact Photo Compressor
        item {
            LabCard(
                title = "ভিজ্যুয়ালি-লসলেস আল্ট্রা-কম্প্যাক্ট ফটো কম্প্রেসর",
                icon = Icons.Rounded.Share,
                accent = MeshMint
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MetricBadge("আসল সাইজ", "${svc.photo.originalBytes / 1024} KB", MeshAmber, Modifier.weight(1f))
                    MetricBadge("নতুন সাইজ", "${svc.photo.compressedBytes / 1024} KB", MeshMint, Modifier.weight(1f))
                    MetricBadge("সাশ্রয়", "${svc.photo.savedPct}%", MeshCobalt, Modifier.weight(0.8f))
                    MetricBadge("SSIM", String.format(Locale.ROOT, "%.3f", svc.photo.ssim), MeshViolet, Modifier.weight(0.9f))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${svc.photo.sourceLabel} · ${svc.photo.width}×${svc.photo.height} · EXIF: ${if (svc.photo.exifStripped) "Stripped" else "Original"} · RS-FEC ${svc.photo.fecTotalShards} শার্ড",
                    fontFamily = MonoFont,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = svc.photo.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshMint
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshMint, contentColor = Color(0xFF04110C)),
                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("photo_pick_compress_button")
                    ) {
                        Text("গ্যালারি ছবি কম্প্রেস")
                    }
                    OutlinedButton(
                        onClick = { vm.compressSyntheticPhoto() },
                        border = BorderStroke(1.dp, MeshCobalt),
                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("photo_sample_compress_button")
                    ) {
                        Text("নমুনা ২৪০০px ছবি টেস্ট", color = MeshCobalt)
                    }
                }
            }
        }

        // 3. QR ContactCard & 60-Digit Safety Number Verifier
        item {
            LabCard(
                title = "QR ContactCard · 60-ডিজিট Safety Number · Revocation",
                icon = Icons.Rounded.Lock,
                accent = MeshCobalt
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricBadge("পিয়ার", svc.contact.verifiedPeerUser.ifBlank { "—" }, MeshCobalt, Modifier.weight(1f))
                    MetricBadge("স্বাক্ষর", if (svc.contact.cardVerified) "VERIFIED" else "REJECTED", if (svc.contact.cardVerified) MeshMint else MeshCoral, Modifier.weight(1f))
                    MetricBadge("রিভোকেশন", if (svc.contact.peerRevoked) "REVOKED" else "ACTIVE", if (svc.contact.peerRevoked) MeshCoral else MeshMint, Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MeshPanelElevated)
                        .padding(10.dp)
                ) {
                    Text(
                        text = "৬০-ডিজিট Safety Number:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = svc.contact.safetyNumber,
                        fontFamily = MonoFont,
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshAmber
                    )
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = cardInput,
                    onValueChange = { cardInput = it },
                    label = { Text("meshnet://card?... URI যাচাই") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("contact_card_uri_input")
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = svc.contact.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (svc.contact.cardVerified) MeshMint else MeshCoral
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { vm.verifyContactUri(cardInput) },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshCobalt, contentColor = Color(0xFF04111D)),
                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("verify_contact_card_button")
                    ) {
                        Text("কার্ড যাচাই করুন")
                    }
                    OutlinedButton(
                        onClick = { vm.revokePeerContactKey() },
                        border = BorderStroke(1.dp, MeshCoral),
                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("revoke_contact_key_button")
                    ) {
                        Text("কী বাতিল (Revoke)", color = MeshCoral)
                    }
                }
            }
        }

        // 4. Gateway Policy, Low-Battery Cutoff & Hotspot IP
        item {
            LabCard(
                title = "গেটওয়ে পলিসি, লো-ব্যাটারি কাটঅফ ও HotspotHelper",
                icon = Icons.Rounded.Settings,
                accent = MeshAmber
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricBadge("ব্যাটারি", "${svc.gateway.batteryPct}%", if (svc.gateway.batteryPct < 20) MeshCoral else MeshMint, Modifier.weight(1f))
                    MetricBadge("রোল", if (svc.gateway.isLeafMode) "LEAF (<20%)" else if (svc.gateway.isGatewayElected) "GATEWAY" else "RELAY", if (svc.gateway.isLeafMode) MeshCoral else MeshAmber, Modifier.weight(1f))
                    MetricBadge("LAN IP", svc.hotspotLanIp.ifBlank { "—" }, MeshCobalt, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Gateway নির্বাচন অনুমতি",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = gatewayDesired,
                        onCheckedChange = {
                            gatewayDesired = it
                            vm.updateGatewayPolicy(batterySlider.roundToInt(), it)
                        },
                        modifier = Modifier.testTag("gateway_policy_switch")
                    )
                }
                Text(
                    text = "ব্যাটারি সীমা পরীক্ষা (<20% এ স্বয়ংক্রিয় Leaf Mode): ${batterySlider.roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = batterySlider,
                    onValueChange = {
                        batterySlider = it
                        vm.updateGatewayPolicy(it.roundToInt(), gatewayDesired)
                    },
                    valueRange = 5f..100f,
                    colors = SliderDefaults.colors(thumbColor = MeshAmber, activeTrackColor = MeshAmber),
                    modifier = Modifier.testTag("gateway_battery_slider")
                )
                Text(
                    text = svc.gateway.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (svc.gateway.isLeafMode) MeshCoral else MeshMint
                )
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}
