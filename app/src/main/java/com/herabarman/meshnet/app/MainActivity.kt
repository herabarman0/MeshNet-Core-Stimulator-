package com.herabarman.meshnet.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.herabarman.meshnet.app.ui.EngineLabTab
import com.herabarman.meshnet.app.ui.LiveMeshTab
import com.herabarman.meshnet.app.ui.MeshHubTab
import com.herabarman.meshnet.app.ui.MultiPlatformTab
import com.herabarman.meshnet.app.ui.theme.MeshBg
import com.herabarman.meshnet.app.ui.theme.MeshMint
import com.herabarman.meshnet.app.ui.theme.MeshNetTheme
import com.herabarman.meshnet.app.ui.theme.MeshPanel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            MeshNetTheme {
                MeshAppShell()
            }
        }
    }
}

private data class NavTab(
    val titleRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val tag: String
)

private val NAV_TABS = listOf(
    NavTab(R.string.tab_live_mesh, Icons.Rounded.Share, Icons.Outlined.Share, "nav_live_mesh"),
    NavTab(R.string.tab_mesh_hub, Icons.Rounded.Star, Icons.Outlined.Star, "nav_mesh_hub"),
    NavTab(R.string.tab_engine_lab, Icons.Rounded.Build, Icons.Outlined.Build, "nav_engine_lab"),
    NavTab(R.string.tab_multiplatform, Icons.Rounded.Info, Icons.Outlined.Info, "nav_multiplatform")
)

@Composable
private fun MeshAppShell(
    vm: MeshDashboardViewModel = viewModel()
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 840.dp
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MeshBg,
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (!useRail) {
                    NavigationBar(
                        containerColor = MeshPanel,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        NAV_TABS.forEachIndexed { idx, tab ->
                            val selected = selectedTab == idx
                            NavigationBarItem(
                                selected = selected,
                                onClick = { selectedTab = idx },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.titleRes)
                                    )
                                },
                                label = { Text(stringResource(tab.titleRes)) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color(0xFF04110C),
                                    selectedTextColor = MeshMint,
                                    indicatorColor = MeshMint,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.testTag(tab.tag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(containerColor = MeshPanel) {
                        NAV_TABS.forEachIndexed { idx, tab ->
                            val selected = selectedTab == idx
                            NavigationRailItem(
                                selected = selected,
                                onClick = { selectedTab = idx },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.titleRes)
                                    )
                                },
                                label = { Text(stringResource(tab.titleRes)) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = Color(0xFF04110C),
                                    selectedTextColor = MeshMint,
                                    indicatorColor = MeshMint,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.testTag(tab.tag)
                            )
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    when (selectedTab) {
                        0 -> LiveMeshTab(vm = vm)
                        1 -> MeshHubTab(vm = vm)
                        2 -> EngineLabTab(vm = vm)
                        else -> MultiPlatformTab(vm = vm)
                    }
                }
            }
        }
    }
}
