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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.herabarman.meshnet.app.ui.theme.MeshMint
import com.herabarman.meshnet.app.ui.theme.MeshOutline
import com.herabarman.meshnet.app.ui.theme.MeshPanel
import com.herabarman.meshnet.app.ui.theme.MeshPanelElevated
import com.herabarman.meshnet.app.ui.theme.MeshViolet
import com.herabarman.meshnet.app.ui.theme.MonoFont
import java.util.Locale

private data class BenchmarkSpec(
    val id: String,
    val label: String,
    val detail: String
)

private data class PlatformTargetSpec(
    val name: String,
    val path: String,
    val summary: String,
    val icon: ImageVector,
    val accent: Color
)

private val BENCHMARK_SPECS = listOf(
    BenchmarkSpec("baseline_120", "১২০ নোড · ফুল মেশ", "১০ জোন · রুট + ACK + সাপ্রেশন"),
    BenchmarkSpec("chord_300", "৩০০ নোড · কর্ড রিং", "২৫ জোন · log2(N) হপস · ডিসকভারি"),
    BenchmarkSpec("burst_pacing", "প্যাকেট বার্স্ট + পেসার", "txRate=25/s · বাল্ক রিজার্ভ সুরক্ষা"),
    BenchmarkSpec("storm_churn", "২০% লস + গেটওয়ে ফেইল", "১৪৪ নোড · অফলাইন-ফার্স্ট রিকভারি"),
    BenchmarkSpec("turbo_250", "২৫০ নোড · আল্ট্রা হাই-ট্রাফিক", "txRate=60/s · টার্বো বার্স্ট ও পেসার")
)

private val PLATFORM_TARGETS = listOf(
    PlatformTargetSpec(
        name = "Android (Kotlin + Java 11)",
        path = "app/ · android-kit/",
        summary = "Jetpack Compose, Nearby Connections, Wi-Fi Direct, SQLite, AMR-NB PTT",
        icon = Icons.Rounded.Phone,
        accent = MeshMint
    ),
    PlatformTargetSpec(
        name = "iOS / macOS / watchOS (Swift)",
        path = "sdk/swift/",
        summary = "Swift Package · 30B MeshPacket, Murmur3 homeZone, URLSession bridge",
        icon = Icons.Rounded.Phone,
        accent = MeshCobalt
    ),
    PlatformTargetSpec(
        name = "C++17 + C99 FFI ABI",
        path = "native/cpp/ · native/c/",
        summary = "Header-only zero-alloc core, ZoneRouter, Pacer, Store, CMake shared lib",
        icon = Icons.Rounded.Build,
        accent = MeshAmber
    ),
    PlatformTargetSpec(
        name = "Rust (no_std + Wasm + FFI)",
        path = "native/rust/",
        summary = "Memory-safe Packet, Caches, Router, Store & 3,517-vector conformance",
        icon = Icons.Rounded.Settings,
        accent = MeshViolet
    ),
    PlatformTargetSpec(
        name = "TypeScript / Web / React Native",
        path = "sdk/ts/",
        summary = "Zero-dep ESM SDK · Browser, Node.js, Electron & MeshBridgeClient",
        icon = Icons.Rounded.Share,
        accent = MeshMint
    ),
    PlatformTargetSpec(
        name = "Node.js RAM-Only Relay Bridge",
        path = "server/mesh-bridge.js",
        summary = "Zero-DB binary long-poll relay · E2EE blind forwarding",
        icon = Icons.Rounded.Info,
        accent = MeshCobalt
    )
)

@Composable
fun MultiPlatformTab(
    vm: MeshDashboardViewModel,
    modifier: Modifier = Modifier
) {
    val bench by vm.benchState.collectAsStateWithLifecycle()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF0E1626), MeshBg, MeshBg))
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
                    text = "Multi-Platform & Benchmarks",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MeshMint
                )
                Text(
                    text = "একই ৩০-বাইট বাইনারি প্রোটোকল · ৭টি প্ল্যাটফর্ম · ৩,৫১৭ কনফরমেন্স ভেক্টর",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                LabCard(
                    title = "অন-ডিভাইস সিমুলেশন বেঞ্চমার্ক",
                    icon = Icons.Rounded.Build,
                    accent = MeshMint
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (spec in BENCHMARK_SPECS) {
                            val res = bench.results[spec.id]
                            val isRunning = bench.runningId == spec.id
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MeshPanelElevated)
                                    .padding(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(spec.label, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            spec.detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Button(
                                        onClick = { vm.runSimBenchmark(spec.id) },
                                        enabled = bench.runningId == null,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MeshMint,
                                            contentColor = Color(0xFF04110C)
                                        ),
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("bench_run_${spec.id}")
                                    ) {
                                        if (isRunning) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = Color(0xFF04110C)
                                            )
                                        } else {
                                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Text("চালান")
                                        }
                                    }
                                }
                                if (res != null) {
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        MetricBadge(
                                            label = "ডেলিভারি",
                                            value = String.format(Locale.ROOT, "%.1f%%", res.deliveryPct),
                                            color = MeshMint,
                                            modifier = Modifier.weight(1f)
                                        )
                                        MetricBadge(
                                            label = "গড় হপ",
                                            value = String.format(Locale.ROOT, "%.2f", res.avgHops),
                                            color = MeshCobalt,
                                            modifier = Modifier.weight(1f)
                                        )
                                        MetricBadge(
                                            label = "P95",
                                            value = "${res.p95LatencyMs}ms",
                                            color = MeshAmber,
                                            modifier = Modifier.weight(1f)
                                        )
                                        MetricBadge(
                                            label = "Tx/Msg",
                                            value = String.format(Locale.ROOT, "%.1f", res.txPerMsg),
                                            color = MeshViolet,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, tint = MeshCobalt)
                    Text(
                        text = "সাপোর্টেড মাল্টি-প্ল্যাটফর্ম টার্গেট",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            items(PLATFORM_TARGETS) { target ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MeshPanel),
                    border = BorderStroke(1.dp, MeshOutline),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(target.accent.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(target.icon, contentDescription = null, tint = target.accent)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(target.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = target.path,
                                fontFamily = MonoFont,
                                style = MaterialTheme.typography.labelSmall,
                                color = target.accent
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = target.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
