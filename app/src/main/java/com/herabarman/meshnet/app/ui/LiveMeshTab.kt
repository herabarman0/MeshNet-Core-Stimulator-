package com.herabarman.meshnet.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.herabarman.meshnet.app.LiveMeshUiState
import com.herabarman.meshnet.app.MeshDashboardViewModel
import com.herabarman.meshnet.app.PacketAnim
import com.herabarman.meshnet.app.ui.theme.MeshAmber
import com.herabarman.meshnet.app.ui.theme.MeshBg
import com.herabarman.meshnet.app.ui.theme.MeshCobalt
import com.herabarman.meshnet.app.ui.theme.MeshCoral
import com.herabarman.meshnet.app.ui.theme.MeshMint
import com.herabarman.meshnet.app.ui.theme.MeshOutline
import com.herabarman.meshnet.app.ui.theme.MeshPanel
import com.herabarman.meshnet.app.ui.theme.MeshPanelElevated
import com.herabarman.meshnet.app.ui.theme.MeshTextDim
import com.herabarman.meshnet.app.ui.theme.MeshViolet
import com.herabarman.meshnet.app.ui.theme.MonoFont
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private data class TopologyLayout(
    val server: Offset,
    val zones: List<Triple<Float, Float, Float>>, // cx, cy, r
    val nodes: Array<Offset>
)

private fun computeTopologyLayout(w: Float, h: Float, n: Int, zoneSize: Int, zoneCount: Int): TopologyLayout {
    val cx = w / 2f
    val cy = h / 2f
    val size = min(w, h)
    val radius = 0.37f * size
    val zCount = maxOf(1, zoneCount)
    val rz = min(0.9f * PI.toFloat() * radius / zCount, 0.2f * size)
    val pos = Array(n) { Offset(cx, cy) }
    val zList = ArrayList<Triple<Float, Float, Float>>(zCount)
    for (z in 0 until zCount) {
        val ang = (2.0 * PI * z / zCount - PI / 2.0)
        val zx = cx + radius * cos(ang).toFloat()
        val zy = cy + radius * sin(ang).toFloat()
        zList.add(Triple(zx, zy, rz))
        val start = z * zoneSize
        val end = if (z == zCount - 1) n else min(n, start + zoneSize)
        val cnt = maxOf(1, end - start)
        for (i in 0 until (end - start)) {
            val a = 2.0 * PI * i / cnt
            val rr = if (cnt > 14) rz * (if (i % 2 == 1) 0.88f else 0.56f) else rz * 0.78f
            pos[start + i] = Offset(zx + rr * cos(a).toFloat(), zy + rr * sin(a).toFloat())
        }
    }
    return TopologyLayout(Offset(cx, cy), zList, pos)
}

@Composable
fun LiveMeshTab(
    vm: MeshDashboardViewModel,
    modifier: Modifier = Modifier
) {
    val state by vm.liveState.collectAsStateWithLifecycle()
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val anims = remember { mutableStateListOf<PacketAnim>() }
    var lastQueuedSimT by remember { mutableLongStateOf(0L) }
    var lastSeqSeen by remember { mutableLongStateOf(0L) }
    val frameMsState = remember { mutableLongStateOf(System.nanoTime() / 1_000_000L) }
    var showSettings by remember { mutableStateOf(false) }

    val textMeasurer = rememberTextMeasurer()

    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { now ->
                frameMsState.longValue = now
                if (anims.isNotEmpty()) {
                    anims.removeAll { now - it.startMs > it.durMs }
                }
            }
        }
    }

    val topoLayout = remember(canvasSize, state.nodeCount, state.zoneSize, state.zoneCount) {
        if (canvasSize.width > 0 && canvasSize.height > 0 && state.nodeCount > 0) {
            computeTopologyLayout(
                canvasSize.width.toFloat(),
                canvasSize.height.toFloat(),
                state.nodeCount,
                state.zoneSize,
                state.zoneCount
            )
        } else null
    }

    LaunchedEffect(state.seq, topoLayout) {
        val layout = topoLayout ?: return@LaunchedEffect
        if (state.seq < lastSeqSeen) {
            anims.clear()
            lastQueuedSimT = state.simTimeMs
        }
        lastSeqSeen = state.seq
        val now = frameMsState.longValue
        val spd = maxOf(0.05, state.speed)
        for (ev in state.newEvents) {
            if (ev.from < 0 || ev.from >= layout.nodes.size || ev.to < 0 || ev.to >= layout.nodes.size) continue
            val from = layout.nodes[ev.from]
            val to = layout.nodes[ev.to]
            val delay = ((ev.simTimeMs - lastQueuedSimT) / spd).toLong().coerceIn(0L, 900L)
            if (ev.viaServer) {
                anims.add(PacketAnim(from.x, from.y, layout.server.x, layout.server.y, now + delay, 260L, 2))
                anims.add(PacketAnim(layout.server.x, layout.server.y, to.x, to.y, now + delay + 260L, 260L, 2))
            } else {
                anims.add(PacketAnim(from.x, from.y, to.x, to.y, now + delay, 320L, ev.kind))
            }
        }
        if (anims.size > 1200) {
            anims.removeRange(0, anims.size - 1200)
        }
        lastQueuedSimT = state.simTimeMs
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MeshBg),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header + Presets
            item {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("MeshNet", style = MaterialTheme.typography.headlineSmall, color = MeshMint)
                        Text("লাইভ সিমুলেটর", style = MaterialTheme.typography.bodySmall, color = MeshTextDim)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = String.format(Locale.ROOT, "%.1fs", state.simTimeMs / 1000.0),
                            fontFamily = MonoFont,
                            style = MaterialTheme.typography.labelMedium,
                            color = MeshTextDim
                        )
                        IconButton(
                            onClick = { vm.togglePause() },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("pause_resume_button")
                        ) {
                            Icon(
                                imageVector = if (state.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Warning,
                                contentDescription = if (state.paused) "চালান" else "থামান",
                                tint = if (state.paused) MeshMint else MeshAmber
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = { vm.applySimPreset("standard") },
                        label = { Text("স্ট্যান্ডার্ড ১২০") },
                        leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null, tint = MeshMint) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = MeshPanelElevated),
                        border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = MeshOutline),
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("preset_standard")
                    )
                    AssistChip(
                        onClick = { vm.applySimPreset("storm") },
                        label = { Text("৩০% লস ঝড়") },
                        leadingIcon = { Icon(Icons.Rounded.Warning, contentDescription = null, tint = MeshAmber) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = MeshPanelElevated),
                        border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = MeshOutline),
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("preset_storm")
                    )
                    AssistChip(
                        onClick = { vm.applySimPreset("chord") },
                        label = { Text("কর্ড রিং ৩৬০") },
                        leadingIcon = { Icon(Icons.Rounded.Send, contentDescription = null, tint = MeshCobalt) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = MeshPanelElevated),
                        border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = MeshOutline),
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("preset_chord")
                    )
                    AssistChip(
                        onClick = { vm.applySimPreset("reset") },
                        label = { Text("রিসেট") },
                        leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null, tint = MeshMint) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = MeshPanelElevated),
                        border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = MeshOutline),
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("preset_reset")
                    )
                }
            }

            // Native Interactive Topology Canvas (Zero WebView / Zero Chromium crash!)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MeshBg),
                    border = BorderStroke(1.dp, MeshOutline),
                    shape = MaterialTheme.shapes.medium
                ) {
                    TopologyCanvas(
                        state = state,
                        layout = topoLayout,
                        anims = anims,
                        frameMsProvider = { frameMsState.longValue },
                        textMeasurer = textMeasurer,
                        onSizeChanged = { canvasSize = it },
                        onTapNode = { vm.selectNode(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.0f)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(Color(0xFF101828), MeshBg)
                                )
                            )
                            .testTag("mesh_topology_canvas")
                    )
                }
                Spacer(Modifier.height(6.dp))
                LegendRow()
            }

            // Selection Info
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MeshPanel),
                    border = BorderStroke(1.dp, MeshOutline)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val aInfo = state.selectedAInfo
                        val selText = if (state.selectedA >= 0 && aInfo != null) {
                            buildString {
                                append("A = নোড ${aInfo.id} · জোন ${aInfo.zone} · ")
                                append(if (aInfo.alive) (if (aInfo.online) "অনলাইন" else "অফলাইন") else "বন্ধ")
                                if (aInfo.gateway) {
                                    append(if (state.settings.backbone == "chord") " · গেটওয়ে" else " · বর্ডার নোড")
                                }
                                append(" · ব্যাটারি ${(aInfo.battery * 100).roundToInt()}%")
                                if (state.selectedB >= 0) {
                                    val bz = minOf(state.selectedB / maxOf(1, state.zoneSize), maxOf(0, state.zoneCount - 1))
                                    append("  |  B = নোড ${state.selectedB} · জোন $bz")
                                }
                            }
                        } else {
                            "একটা নোডে ট্যাপ করুন (A), তারপর আরেকটায় (B)।"
                        }
                        Text(selText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)

                        if (state.selectedA >= 0 && state.selectedB >= 0) {
                            val r = state.route
                            val routeMsg = when {
                                r.size > 1 && r.last() == state.selectedB ->
                                    "শেখা পথ: ${r.joinToString(" → ")} (সরাসরি ইউনিকাস্ট)"
                                r.size > 1 ->
                                    "আংশিক পথ: ${r.joinToString(" → ")} … বাকিটা ফ্লাড হবে"
                                else ->
                                    "এই জোড়ার পথ এখনো শেখা হয়নি — প্রথম মেসেজ ফ্লাড হবে।"
                            }
                            Text(routeMsg, style = MaterialTheme.typography.bodySmall, color = MeshCobalt)
                        }
                    }
                }
            }

            // Control Buttons
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MeshPanel),
                    border = BorderStroke(1.dp, MeshOutline)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { vm.sendRandom("local") },
                                colors = ButtonDefaults.buttonColors(containerColor = MeshMint, contentColor = Color(0xFF04110C)),
                                modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("send_local_button")
                            ) { Text("লোকাল মেসেজ") }
                            Button(
                                onClick = { vm.sendRandom("remote") },
                                colors = ButtonDefaults.buttonColors(containerColor = MeshMint, contentColor = Color(0xFF04110C)),
                                modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("send_remote_button")
                            ) { Text("রিমোট মেসেজ") }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { vm.sendAB() },
                                enabled = state.selectedA >= 0 && state.selectedB >= 0,
                                modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("send_ab_button")
                            ) { Text("A → B পাঠান") }
                            OutlinedButton(
                                onClick = { vm.killA() },
                                enabled = state.selectedA >= 0,
                                border = BorderStroke(1.dp, if (state.selectedA >= 0) MeshCoral else MeshOutline),
                                modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("kill_a_button")
                            ) { Text("A বন্ধ", color = if (state.selectedA >= 0) MeshCoral else MeshTextDim) }
                            OutlinedButton(
                                onClick = { vm.reviveA() },
                                enabled = state.selectedA >= 0,
                                modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("revive_a_button")
                            ) { Text("A চালু") }
                        }
                        OutlinedButton(
                            onClick = { vm.killBorderNodesOfA() },
                            enabled = state.selectedA >= 0,
                            border = BorderStroke(1.dp, if (state.selectedA >= 0) MeshCoral else MeshOutline),
                            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("kill_gw_button")
                        ) {
                            Text("A-এর জোনের বর্ডার নোড বন্ধ", color = if (state.selectedA >= 0) MeshCoral else MeshTextDim)
                        }
                    }
                }
            }

            // Metrics Grid
            item {
                val m = state.metrics
                val pct = { x: Double -> String.format(Locale.ROOT, "%.1f%%", x * 100.0) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricBadge("ডেলিভারি", if (m.sent > 0) pct(m.deliveryRate) else "–", MeshMint, Modifier.weight(1f))
                        MetricBadge("গড় হপ", if (m.delivered > 0) String.format(Locale.ROOT, "%.1f", m.avgHops) else "–", MeshCobalt, Modifier.weight(1f))
                        MetricBadge("ল্যাটেন্সি", if (m.delivered > 0) "${m.avgLatency}ms" else "–", MeshAmber, Modifier.weight(1f))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricBadge("Tx/মেসেজ", if (m.sent > 0) String.format(Locale.ROOT, "%.1f", m.txPerMsg) else "–", MeshMint, Modifier.weight(1f))
                        MetricBadge("ACK", "${m.acked}/${m.sent}", MeshCobalt, Modifier.weight(1f))
                        MetricBadge("রিট্রাই", "${m.retries}", MeshAmber, Modifier.weight(1f))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricBadge("ডুপ্লিকেট", if (m.sent > 0) pct(m.dupRatio) else "–", MeshViolet, Modifier.weight(1f))
                        MetricBadge("সার্ভার", if (m.delivered > 0) pct(m.viaServer.toDouble() / m.delivered) else "–", MeshViolet, Modifier.weight(1f))
                        MetricBadge("কন্ট্রোল", if (m.tx > 0) pct(m.ctrl.toDouble() / m.tx) else "–", MeshTextDim, Modifier.weight(1f))
                    }
                }
            }

            // Event Log
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MeshPanel),
                    border = BorderStroke(1.dp, MeshOutline)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("মেসেজ লগ (নতুন উপরে)", style = MaterialTheme.typography.labelSmall, color = MeshTextDim)
                        Spacer(Modifier.height(6.dp))
                        val logText = if (state.logLines.isEmpty()) "এখনো কিছু নেই" else state.logLines.take(12).joinToString("\n")
                        Text(logText, fontFamily = MonoFont, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            // Expandable Settings
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MeshPanel),
                    border = BorderStroke(1.dp, MeshOutline)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .clickable { showSettings = !showSettings }
                                .testTag("toggle_settings_row"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Rounded.Settings, contentDescription = null, tint = MeshMint)
                                Text("লাইভ সেটিংস ও নেটওয়ার্ক কনফিগার", style = MaterialTheme.typography.titleSmall)
                            }
                            Text(if (showSettings) "লুকান" else "খুলুন", style = MaterialTheme.typography.labelSmall, color = MeshMint)
                        }
                        if (showSettings) {
                            Spacer(Modifier.height(8.dp))
                            LiveSettingsPanel(state = state, vm = vm)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun TopologyCanvas(
    state: LiveMeshUiState,
    layout: TopologyLayout?,
    anims: List<PacketAnim>,
    frameMsProvider: () -> Long,
    textMeasurer: TextMeasurer,
    onSizeChanged: (IntSize) -> Unit,
    onTapNode: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f) }

    Canvas(
        modifier = modifier
            .onSizeChanged(onSizeChanged)
            .pointerInput(layout) {
                val hitRadius = maxOf(42f, 24.dp.toPx())
                val hitRadiusSq = hitRadius * hitRadius
                detectTapGestures { offset ->
                    val l = layout ?: return@detectTapGestures
                    var best = -1
                    var bestD = hitRadiusSq
                    for (i in l.nodes.indices) {
                        val dx = l.nodes[i].x - offset.x
                        val dy = l.nodes[i].y - offset.y
                        val d = dx * dx + dy * dy
                        if (d < bestD) {
                            bestD = d
                            best = i
                        }
                    }
                    onTapNode(best)
                }
            }
    ) {
        val frameMs = frameMsProvider()
        val l = layout ?: return@Canvas
        val flags = state.nodeFlags
        if (flags.length < state.nodeCount) return@Canvas

        // Zone circles
        for ((zx, zy, zr) in l.zones) {
            drawCircle(
                color = Color(0xFF2B3550),
                radius = zr,
                center = Offset(zx, zy),
                style = Stroke(width = 2f, pathEffect = dashEffect)
            )
        }

        // Online nodes -> Server lines
        for (i in 0 until state.nodeCount) {
            val f = flags[i].code - 48
            if ((f and 1) != 0 && (f and 2) != 0) {
                drawLine(
                    color = MeshMint.copy(alpha = 0.11f),
                    start = l.server,
                    end = l.nodes[i],
                    strokeWidth = 1.5f
                )
            }
        }

        // Intra-zone links
        val intra = state.intraEdges
        var idx = 0
        while (idx + 1 < intra.size) {
            val u = intra[idx]
            val v = intra[idx + 1]
            if (u in l.nodes.indices && v in l.nodes.indices) {
                drawLine(
                    color = MeshTextDim.copy(alpha = 0.28f),
                    start = l.nodes[u],
                    end = l.nodes[v],
                    strokeWidth = 1.5f
                )
            }
            idx += 2
        }

        // Backbone zone-to-zone links
        val bb = state.backboneEdges
        idx = 0
        while (idx + 1 < bb.size) {
            val u = bb[idx]
            val v = bb[idx + 1]
            if (u in l.nodes.indices && v in l.nodes.indices) {
                drawLine(
                    color = MeshAmber.copy(alpha = 0.72f),
                    start = l.nodes[u],
                    end = l.nodes[v],
                    strokeWidth = 2.4f
                )
            }
            idx += 2
        }

        // Learned route
        if (state.route.size > 1) {
            val path = Path()
            state.route.forEachIndexed { k, id ->
                if (id in l.nodes.indices) {
                    val p = l.nodes[id]
                    if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
            }
            drawPath(path = path, color = MeshCobalt, style = Stroke(width = 5f))
        }

        // Nodes
        val isChord = state.settings.backbone == "chord"
        for (i in 0 until state.nodeCount) {
            val f = flags[i].code - 48
            val p = l.nodes[i]
            val alive = (f and 1) != 0
            val online = (f and 2) != 0
            val gw = if (isChord) ((f and 4) != 0) else false
            val r = if (gw) 7.5f else 4.5f
            if (!alive) {
                drawCircle(color = MeshCoral, radius = r, center = p, style = Stroke(width = 2.2f))
            } else {
                drawCircle(color = if (online) MeshMint else MeshTextDim, radius = r, center = p)
                if (gw) {
                    drawCircle(color = Color.White, radius = r, center = p, style = Stroke(width = 2.2f))
                }
            }
        }

        // Selected A and B highlights
        for ((selId, col, label) in listOf(
            Triple(state.selectedA, Color.White, "A"),
            Triple(state.selectedB, MeshAmber, "B")
        )) {
            if (selId in l.nodes.indices) {
                val p = l.nodes[selId]
                drawCircle(color = col, radius = 14f, center = p, style = Stroke(width = 3.5f))
                drawText(
                    textMeasurer = textMeasurer,
                    text = label,
                    topLeft = Offset(p.x + 12f, p.y - 24f),
                    style = TextStyle(color = col, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                )
            }
        }

        // Flying packets
        for (a in anims) {
            val t = (frameMs - a.startMs).toFloat() / a.durMs.toFloat()
            if (t < 0f || t > 1f) continue
            val x = a.fromX + (a.toX - a.fromX) * t
            val y = a.fromY + (a.toY - a.fromY) * t
            val col = when (a.kind) {
                1 -> MeshCobalt
                2 -> MeshViolet
                else -> Color.White
            }
            val r = if (a.kind == 1) 5f else 6f
            drawCircle(color = col.copy(alpha = 0.35f), radius = r * 2.1f, center = Offset(x, y))
            drawCircle(color = col, radius = r, center = Offset(x, y))
        }

        // Server hub at center
        drawCircle(color = Color(0xFF6C5CE7), radius = 24f, center = l.server)
        val srvText = textMeasurer.measure(
            text = "SRV",
            style = TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        )
        drawText(
            textLayoutResult = srvText,
            topLeft = Offset(l.server.x - srvText.size.width / 2f, l.server.y - srvText.size.height / 2f)
        )
    }
}

@Composable
private fun LegendRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendDot(MeshMint, "অনলাইন")
        LegendDot(MeshTextDim, "অফলাইন")
        LegendDot(MeshCoral, "বন্ধ")
        LegendDot(MeshAmber, "জোন লিংক")
        LegendDot(MeshCobalt, "শেখা পথ / ACK")
        LegendDot(MeshViolet, "সার্ভার")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MeshTextDim)
    }
}

@Composable
private fun LiveSettingsPanel(state: LiveMeshUiState, vm: MeshDashboardViewModel) {
    val s = state.settings
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("প্যাকেট লস: ${s.loss}%", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = s.loss.toFloat(),
            onValueChange = { vm.configureSim(mapOf("loss" to it.roundToInt().toString())) },
            valueRange = 0f..60f,
            colors = SliderDefaults.colors(thumbColor = MeshMint, activeTrackColor = MeshMint)
        )
        Text("নোড বন্ধ হওয়ার হার (Churn): ${s.churn}%", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = s.churn.toFloat(),
            onValueChange = { vm.configureSim(mapOf("churn" to it.roundToInt().toString())) },
            valueRange = 0f..20f,
            colors = SliderDefaults.colors(thumbColor = MeshMint, activeTrackColor = MeshMint)
        )
        Text("অটো ট্রাফিক: ${s.traffic} /মিনিট", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = s.traffic.toFloat(),
            onValueChange = { vm.configureSim(mapOf("traffic" to it.roundToInt().toString())) },
            valueRange = 0f..120f,
            colors = SliderDefaults.colors(thumbColor = MeshMint, activeTrackColor = MeshMint)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = s.showAll,
                onCheckedChange = { vm.configureSim(mapOf("showAll" to if (it) "1" else "0")) },
                colors = CheckboxDefaults.colors(checkedColor = MeshMint)
            )
            Text("সব প্যাকেট দেখান", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = s.routes,
                onCheckedChange = { vm.configureSim(mapOf("routes" to if (it) "1" else "0")) },
                colors = CheckboxDefaults.colors(checkedColor = MeshMint)
            )
            Text("রুট লার্নিং (পুনঃনির্মাণ করবে)", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = s.ack,
                onCheckedChange = { vm.configureSim(mapOf("ack" to if (it) "1" else "0")) },
                colors = CheckboxDefaults.colors(checkedColor = MeshMint)
            )
            Text("ACK + ফলব্যাক (পুনঃনির্মাণ করবে)", style = MaterialTheme.typography.bodySmall)
        }
    }
}
