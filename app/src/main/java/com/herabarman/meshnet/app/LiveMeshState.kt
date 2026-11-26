package com.herabarman.meshnet.app

import org.json.JSONObject

data class SelectedNodeInfo(
    val id: Int,
    val zone: Int,
    val alive: Boolean,
    val online: Boolean,
    val gateway: Boolean,
    val battery: Double,
    val signal: Double
)

data class SimMetricsSnapshot(
    val sent: Int = 0,
    val delivered: Int = 0,
    val acked: Int = 0,
    val retries: Int = 0,
    val viaServer: Int = 0,
    val deliveryRate: Double = 0.0,
    val avgHops: Double = 0.0,
    val avgLatency: Long = 0,
    val txPerMsg: Double = 0.0,
    val dupRatio: Double = 0.0,
    val ctrl: Long = 0,
    val tx: Long = 0
)

data class SimSettingsSnapshot(
    val nodes: Int = 120,
    val zoneSize: Int = 12,
    val gateways: Int = 2,
    val online: Int = 20,
    val churn: Int = 0,
    val loss: Int = 0,
    val traffic: Int = 0,
    val routes: Boolean = true,
    val ack: Boolean = true,
    val suppress: Boolean = true,
    val backbone: String = "full",
    val discovery: Boolean = true,
    val links: Int = 2,
    val mode: String = "offline-first",
    val showAll: Boolean = false,
    val speed: Double = 1.0
)

data class PacketAnim(
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float,
    val startMs: Long,
    val durMs: Long,
    val kind: Int, // 0=data, 1=ack, 2=server
)

data class RawPacketEvent(
    val seq: Long,
    val simTimeMs: Long,
    val from: Int,
    val to: Int,
    val kind: Int,
    val viaServer: Boolean
)

data class LiveMeshUiState(
    val ready: Boolean = false,
    val simTimeMs: Long = 0L,
    val nodeCount: Int = 120,
    val zoneSize: Int = 12,
    val zoneCount: Int = 10,
    val speed: Double = 1.0,
    val paused: Boolean = false,
    val showAll: Boolean = false,
    val seq: Long = 0L,
    val nodeFlags: String = "",
    val intraEdges: IntArray = IntArray(0),
    val backboneEdges: IntArray = IntArray(0),
    val route: IntArray = IntArray(0),
    val selectedA: Int = -1,
    val selectedB: Int = -1,
    val selectedAInfo: SelectedNodeInfo? = null,
    val metrics: SimMetricsSnapshot = SimMetricsSnapshot(),
    val settings: SimSettingsSnapshot = SimSettingsSnapshot(),
    val logLines: List<String> = emptyList(),
    val newEvents: List<RawPacketEvent> = emptyList()
) {
    companion object {
        fun parse(jsonStr: String, selA: Int, selB: Int): LiveMeshUiState {
            val root = JSONObject(jsonStr)
            val sObj = root.getJSONObject("settings")
            val mObj = root.getJSONObject("m")

            val intraArr = root.getJSONArray("intra")
            val intra = IntArray(intraArr.length()) { intraArr.getInt(it) }

            val bbArr = root.getJSONArray("bb")
            val bb = IntArray(bbArr.length()) { bbArr.getInt(it) }

            val routeArr = root.getJSONArray("route")
            val route = IntArray(routeArr.length()) { routeArr.getInt(it) }

            val evArr = root.getJSONArray("events")
            val events = ArrayList<RawPacketEvent>(evArr.length())
            for (i in 0 until evArr.length()) {
                val e = evArr.getJSONArray(i)
                events.add(
                    RawPacketEvent(
                        seq = e.getLong(0),
                        simTimeMs = e.getLong(1),
                        from = e.getInt(2),
                        to = e.getInt(3),
                        kind = e.getInt(4),
                        viaServer = e.getInt(5) != 0
                    )
                )
            }

            val logArr = root.getJSONArray("log")
            val logs = ArrayList<String>(logArr.length())
            for (i in logArr.length() - 1 downTo 0) {
                logs.add(logArr.getString(i))
            }

            val selAInfo = if (root.has("selA") && !root.isNull("selA")) {
                val a = root.getJSONObject("selA")
                SelectedNodeInfo(
                    id = a.getInt("id"),
                    zone = a.getInt("zone"),
                    alive = a.getBoolean("alive"),
                    online = a.getBoolean("online"),
                    gateway = a.getBoolean("gateway"),
                    battery = a.getDouble("battery"),
                    signal = a.getDouble("signal")
                )
            } else null

            val n = root.getInt("n")
            val zs = root.getInt("zoneSize")
            val spd = root.getDouble("speed")
            val showAll = root.getBoolean("showAll")

            return LiveMeshUiState(
                ready = true,
                simTimeMs = root.getLong("t"),
                nodeCount = n,
                zoneSize = zs,
                zoneCount = root.getInt("zoneCount"),
                speed = spd,
                paused = root.getBoolean("paused"),
                showAll = showAll,
                seq = root.getLong("seq"),
                nodeFlags = root.getString("st"),
                intraEdges = intra,
                backboneEdges = bb,
                route = route,
                selectedA = selA,
                selectedB = selB,
                selectedAInfo = selAInfo,
                metrics = SimMetricsSnapshot(
                    sent = mObj.getInt("sent"),
                    delivered = mObj.getInt("delivered"),
                    acked = mObj.getInt("acked"),
                    retries = mObj.getInt("retries"),
                    viaServer = mObj.getInt("viaServer"),
                    deliveryRate = mObj.getDouble("deliveryRate"),
                    avgHops = mObj.getDouble("avgHops"),
                    avgLatency = mObj.getLong("avgLatency"),
                    txPerMsg = mObj.getDouble("txPerMsg"),
                    dupRatio = mObj.getDouble("dupRatio"),
                    ctrl = mObj.getLong("ctrl"),
                    tx = mObj.getLong("tx")
                ),
                settings = SimSettingsSnapshot(
                    nodes = n,
                    zoneSize = zs,
                    gateways = sObj.getInt("gateways"),
                    online = sObj.getInt("online"),
                    churn = sObj.getInt("churn"),
                    loss = sObj.getInt("loss"),
                    traffic = sObj.getInt("traffic"),
                    routes = sObj.getBoolean("routes"),
                    ack = sObj.getBoolean("ack"),
                    suppress = sObj.getBoolean("suppress"),
                    backbone = sObj.getString("backbone"),
                    discovery = sObj.getBoolean("discovery"),
                    links = sObj.getInt("links"),
                    mode = sObj.getString("mode"),
                    showAll = showAll,
                    speed = spd
                ),
                logLines = logs,
                newEvents = events
            )
        }
    }
}
