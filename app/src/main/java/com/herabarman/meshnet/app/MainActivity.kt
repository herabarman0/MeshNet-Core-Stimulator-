package com.herabarman.meshnet.app

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.herabarman.meshnet.dashboard.DashboardSim
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class MeshDashboardViewModel : ViewModel() {
    private val ticker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val simFuture: CompletableFuture<DashboardSim> = CompletableFuture()

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
    }

    fun handleRequest(uri: Uri): WebResourceResponse? {
        if (uri.host != "meshnet.local") return null
        val path = uri.path ?: "/"
        if (path == "/" || path == "/index.html") {
            val stream = DashboardSim::class.java.getResourceAsStream("/index.html")
                ?: return WebResourceResponse(
                    "text/plain",
                    "utf-8",
                    404,
                    "Not Found",
                    mapOf("Cache-Control" to "no-store"),
                    ByteArrayInputStream("index.html not found".toByteArray(StandardCharsets.UTF_8))
                )
            return WebResourceResponse(
                "text/html",
                "utf-8",
                200,
                "OK",
                mapOf("Cache-Control" to "no-store"),
                stream
            )
        }
        if (path.startsWith("/api/")) {
            val sim = simFuture.get()
            val query = HashMap<String, String>()
            for (name in uri.queryParameterNames) {
                query[name] = uri.getQueryParameter(name) ?: ""
            }
            val json = try {
                when (path) {
                    "/api/state" -> sim.state(
                        query["since"]?.toLongOrNull() ?: 0L,
                        query["a"]?.toIntOrNull() ?: -1,
                        query["b"]?.toIntOrNull() ?: -1
                    )
                    "/api/send" -> sim.send(
                        query["mode"] ?: "remote",
                        query["src"]?.toIntOrNull() ?: -1,
                        query["dst"]?.toIntOrNull() ?: -1
                    )
                    "/api/kill" -> {
                        sim.kill(query["id"]?.toIntOrNull() ?: -1)
                        "{\"ok\":true}"
                    }
                    "/api/revive" -> {
                        sim.revive(query["id"]?.toIntOrNull() ?: -1)
                        "{\"ok\":true}"
                    }
                    "/api/killgw" -> {
                        sim.killGateways(query["zone"]?.toIntOrNull() ?: -1)
                        "{\"ok\":true}"
                    }
                    "/api/config" -> {
                        sim.configure(query)
                        "{\"ok\":true}"
                    }
                    else -> "{\"ok\":false}"
                }
            } catch (_: RuntimeException) {
                "{\"ok\":false}"
            }
            return WebResourceResponse(
                "application/json",
                "utf-8",
                200,
                "OK",
                mapOf("Cache-Control" to "no-store"),
                ByteArrayInputStream(json.toByteArray(StandardCharsets.UTF_8))
            )
        }
        return null
    }

    override fun onCleared() {
        ticker.shutdownNow()
        super.onCleared()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            MeshNetTheme {
                MeshDashboardScreen()
            }
        }
    }
}

private val MeshColorScheme = darkColorScheme(
    primary = Color(0xFF00E5A0),
    background = Color(0xFF0B0F17),
    surface = Color(0xFF121927),
    onBackground = Color(0xFFE6E9EF),
    onSurface = Color(0xFFE6E9EF)
)

@Composable
private fun MeshNetTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MeshColorScheme,
        content = content
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MeshDashboardScreen(
    vm: MeshDashboardViewModel = viewModel()
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    BackHandler(enabled = webViewRef?.canGoBack() == true) {
        webViewRef?.goBack()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("mesh_dashboard_webview"),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(0xFF0B0F17.toInt())
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val url = request?.url ?: return null
                            return vm.handleRequest(url) ?: super.shouldInterceptRequest(view, request)
                        }
                    }
                    loadUrl("https://meshnet.local/index.html")
                    webViewRef = this
                }
            },
            update = { webViewRef = it }
        )
    }
}
