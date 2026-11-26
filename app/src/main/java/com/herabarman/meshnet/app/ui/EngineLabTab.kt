package com.herabarman.meshnet.app.ui

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
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.herabarman.meshnet.app.MeshDashboardViewModel
import com.herabarman.meshnet.app.ui.theme.MeshAmber
import com.herabarman.meshnet.app.ui.theme.MeshBg
import com.herabarman.meshnet.app.ui.theme.MeshCobalt
import com.herabarman.meshnet.app.ui.theme.MeshCoral
import com.herabarman.meshnet.app.ui.theme.MeshMint
import com.herabarman.meshnet.app.ui.theme.MeshOutline
import com.herabarman.meshnet.app.ui.theme.MeshPanel
import com.herabarman.meshnet.app.ui.theme.MeshPanelElevated
import com.herabarman.meshnet.app.ui.theme.MeshViolet
import com.herabarman.meshnet.app.ui.theme.MonoFont
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun EngineLabTab(
    vm: MeshDashboardViewModel,
    modifier: Modifier = Modifier
) {
    val state by vm.labState.collectAsStateWithLifecycle()
    var secretInput by remember { mutableStateOf("MeshNet E2EE SOS OK") }
    var fecDropSlider by remember { mutableFloatStateOf(3f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0E1626), MeshBg, MeshBg)
                )
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "MeshNet Engine Lab",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MeshMint
                )
                Text(
                    text = "রিয়েল অন-ডিভাইস ওয়্যার প্রোটোকল, ক্রিপ্টোগ্রাফি, FEC ও ML ভেরিফিকেশন",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 1. Wire Codec & Throughput
            item {
                LabCard(
                    title = "ওয়্যার ফরম্যাট ও থ্রুপুট (v2 · 30B)",
                    icon = Icons.Rounded.PlayArrow,
                    accent = MeshMint
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricBadge(
                            label = "ভেক্টর পাস",
                            value = "${state.wire.vectorsPassed}",
                            color = MeshMint,
                            modifier = Modifier.weight(1f)
                        )
                        MetricBadge(
                            label = "গতি (ops/s)",
                            value = String.format(Locale.ROOT, "%,d", state.wire.throughputOpsPerSec),
                            color = MeshCobalt,
                            modifier = Modifier.weight(1f)
                        )
                        MetricBadge(
                            label = "ব্লুম ফিল্টার",
                            value = "${state.wire.bloomBytes} B",
                            color = MeshAmber,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (state.wire.sampleHex.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "30B হেডার: ${state.wire.sampleHex}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { vm.runWireCheck() },
                        enabled = !state.wire.running,
                        colors = ButtonDefaults.buttonColors(containerColor = MeshMint, contentColor = Color(0xFF04110C)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("run_wire_check_button")
                    ) {
                        Text(if (state.wire.running) "পরীক্ষা চলছে..." else "ওয়্যার বেঞ্চমার্ক আবার চালান")
                    }
                }
            }

            // 2. RFC 7748 X25519 + AES-256-GCM
            item {
                LabCard(
                    title = "RFC 7748 X25519 + AES-256-GCM",
                    icon = Icons.Rounded.Lock,
                    accent = MeshCobalt
                ) {
                    OutlinedTextField(
                        value = secretInput,
                        onValueChange = { secretInput = it },
                        label = { Text("গোপন মেসেজ লিখুন") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("crypto_message_input")
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricBadge(
                            label = "শেয়ার্ড কী",
                            value = state.crypto.sharedKeyHex.ifEmpty { "-" },
                            color = MeshCobalt,
                            modifier = Modifier.weight(1f)
                        )
                        MetricBadge(
                            label = "ওভারহেড",
                            value = "${state.crypto.overheadBytes} B",
                            color = MeshMint,
                            modifier = Modifier.weight(1f)
                        )
                        MetricBadge(
                            label = "সময়",
                            value = "${state.crypto.latencyMicros} µs",
                            color = MeshAmber,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            imageVector = if (state.crypto.verifiedRoundTrip) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = if (state.crypto.verifiedRoundTrip) MeshMint else MeshCoral
                        )
                        Text(
                            text = if (state.crypto.verifiedRoundTrip)
                                "Alice=${state.crypto.alicePubHex} ↔ Bob=${state.crypto.bobPubHex} ডিক্রিপশন সফল (${state.crypto.cipherBytes} B)"
                            else "যাচাই করুন",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { vm.runCryptoDemo(secretInput) },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshCobalt, contentColor = Color(0xFF04111D)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("run_crypto_demo_button")
                    ) {
                        Text("নতুন X25519 হ্যান্ডশেক ও এনক্রিপ্ট করুন")
                    }
                }
            }

            // 2b. Post-Quantum FIPS 203 ML-KEM-768 (Kyber) + X25519 Hybrid
            item {
                LabCard(
                    title = "পোস্ট-কোয়ান্টাম সিকিউরিটি (ML-KEM-768 + X25519)",
                    icon = Icons.Rounded.Lock,
                    accent = MeshViolet
                ) {
                    Text(
                        text = "কোয়ান্টাম কম্পিউটার প্রতিরোধী হাইব্রিড কী এক্সচেঞ্জ (FIPS 203 Kyber-768 + SHA3-256)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricBadge(
                            label = "ML-KEM ek/ct",
                            value = "${state.pqCrypto.kemEkBytes}/${state.pqCrypto.kemCtBytes}B",
                            color = MeshViolet,
                            modifier = Modifier.weight(1f)
                        )
                        MetricBadge(
                            label = "হাইব্রিড কী",
                            value = state.pqCrypto.hybridKeyHex.ifEmpty { "-" },
                            color = MeshMint,
                            modifier = Modifier.weight(1f)
                        )
                        MetricBadge(
                            label = "সময়",
                            value = "${state.pqCrypto.latencyMicros} µs",
                            color = MeshAmber,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            imageVector = if (state.pqCrypto.verifiedRoundTrip) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = if (state.pqCrypto.verifiedRoundTrip) MeshMint else MeshCoral
                        )
                        Text(
                            text = if (state.pqCrypto.verifiedRoundTrip)
                                "কোয়ান্টাম-সেফ এনক্যাপসুলেশন ও AES-256-GCM যাচাই সফল (${state.pqCrypto.cipherBytes} B)"
                            else "যাচাই করুন",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { vm.runPostQuantumDemo(secretInput) },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshViolet, contentColor = Color(0xFF0A061D)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("run_pq_demo_button")
                    ) {
                        Text("ML-KEM-768 + X25519 হাইব্রিড কী টেস্ট করুন")
                    }
                }
            }

            // 3. GF(256) Reed-Solomon FEC
            item {
                LabCard(
                    title = "GF(256) Reed-Solomon FEC লস রিকভারি",
                    icon = Icons.Rounded.Build,
                    accent = MeshAmber
                ) {
                    val drops = fecDropSlider.roundToInt()
                    Text(
                        text = "হারানো শার্ড সংখ্যা: $drops / ${state.fec.dataShards + state.fec.parityShards} (সর্বোচ্চ ${state.fec.parityShards}টি রিকভারযোগ্য)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = fecDropSlider,
                        onValueChange = {
                            fecDropSlider = it
                            vm.runFecDemo(it.roundToInt())
                        },
                        valueRange = 0f..4f,
                        steps = 3,
                        colors = SliderDefaults.colors(thumbColor = MeshAmber, activeTrackColor = MeshAmber),
                        modifier = Modifier.testTag("fec_drop_slider")
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val total = state.fec.dataShards + state.fec.parityShards
                        for (i in 0 until total) {
                            val isDropped = i in state.fec.droppedIndices
                            val tag = if (i < state.fec.dataShards) "D$i" else "P${i - state.fec.dataShards}"
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isDropped) MeshCoral.copy(alpha = 0.22f) else MeshMint.copy(alpha = 0.18f))
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isDropped) "$tag ✕" else "$tag ✓",
                                    fontFamily = MonoFont,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isDropped) MeshCoral else MeshMint
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (state.fec.recoveredOk) "রিকভার সফল: \"${state.fec.recoveredText}\""
                        else "প্যারিটি সীমার বাইরে (> ${state.fec.parityShards}টি লস) — ARQ রিট্রাই লাগবে",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.fec.recoveredOk) MeshMint else MeshCoral
                    )
                }
            }

            // 4. DTN On-Device ML
            item {
                LabCard(
                    title = "অন-ডিভাইস DTN ব্রেইন (লজিস্টিক ML)",
                    icon = Icons.Rounded.Star,
                    accent = MeshMint
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricBadge(
                            label = "রিলে স্কোর",
                            value = String.format(Locale.ROOT, "%.1f%%", state.dtn.prediction * 100.0),
                            color = MeshMint,
                            modifier = Modifier.weight(1f)
                        )
                        MetricBadge(
                            label = "এক্সপ্লোরেশন",
                            value = String.format(Locale.ROOT, "%.3f", state.dtn.exploration),
                            color = MeshCobalt,
                            modifier = Modifier.weight(1f)
                        )
                        MetricBadge(
                            label = "আপডেট",
                            value = "${state.dtn.updates}",
                            color = MeshAmber,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "ওয়েটস: " + state.dtn.weightsPreview.take(5).joinToString("  ") {
                            String.format(Locale.ROOT, "%.2f", it)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { vm.trainDtnBrain(1.0) },
                            colors = ButtonDefaults.buttonColors(containerColor = MeshMint, contentColor = Color(0xFF04110C)),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("dtn_reward_button")
                        ) {
                            Text("ডেলিভারি সফল (+1)")
                        }
                        OutlinedButton(
                            onClick = { vm.trainDtnBrain(0.0) },
                            border = BorderStroke(1.dp, MeshCoral),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("dtn_penalty_button")
                        ) {
                            Text("ব্ল্যাক-হোল (0)", color = MeshCoral)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun LabCard(
    title: String,
    icon: ImageVector,
    accent: Color,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MeshPanel),
        border = BorderStroke(1.dp, MeshOutline),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(icon, contentDescription = null, tint = accent)
                Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun MetricBadge(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MeshPanelElevated)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontFamily = MonoFont,
            style = MaterialTheme.typography.titleSmall,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
