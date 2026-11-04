package dev.skyward.rf.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.skyward.rf.Container
import dev.skyward.rf.peer.PeerProtocol
import dev.skyward.rf.ui.theme.Sky

private fun requiredPermissions(): List<String> {
    val list = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
    if (Build.VERSION.SDK_INT >= 31) {
        list.add(Manifest.permission.BLUETOOTH_SCAN)
        list.add(Manifest.permission.BLUETOOTH_CONNECT)
        // The peer link is a BLE GATT service, so it has to advertise as well as scan.
        list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
    }
    return list
}

@Composable
fun AppRoot(c: Container) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }

    // Two phones on one network split the work: the bigger screen shows the globe, the smaller
    // one the wardriving readout. The switch happens when the link or the role changes, never on
    // every message, so after that it leaves the user's own tab choice alone.
    val peer by c.peer.state.collectAsState()
    val settings by c.settingsStore.state.collectAsState()
    LaunchedEffect(peer.connected, peer.role, settings.peerRoleMode) {
        // A hand-picked globe view or host always shows the map; "auto" keeps the screen-size
        // split, and an unlinked phone is left on whichever tab the user chose.
        if (PeerProtocol.isGlobeMode(settings.peerRoleMode)) {
            tab = 0
            return@LaunchedEffect
        }
        if (!peer.connected) return@LaunchedEffect
        when (peer.role) {
            PeerProtocol.DISPLAY -> tab = 0
            PeerProtocol.SENSOR -> tab = 1
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    val request: () -> Unit = {
        runCatching { launcher.launch(requiredPermissions().toTypedArray()) }
        runCatching {
            if (!c.location.hasPermission()) {
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        }
        Unit
    }

    LaunchedEffect(Unit) {
        val missing = requiredPermissions().any {
            ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing) runCatching { launcher.launch(requiredPermissions().toTypedArray()) }
    }

    // The tab strip folds away into one square button in the bottom-left corner - the same
    // size, corner and border as the map's own buttons, so it reads as part of that stack
    // rather than a bar. It still occupies the strip the Scaffold reserves, which is what lets
    // the INTEL box sit above it rather than under it. A tap opens the four choices; choosing
    // one puts it back, so the bar does not sit over the map.
    var barOpen by remember { mutableStateOf(false) }
    val tabs = listOf(
        Triple("Map", Icons.Filled.Place, 0),
        Triple("Drive", Icons.Filled.PlayArrow, 1),
        Triple("Log", Icons.Filled.List, 2),
        Triple("Setup", Icons.Filled.Settings, 3),
    )
    val current = tabs[tab.coerceIn(0, tabs.lastIndex)]

    Scaffold(
        containerColor = Sky.bg,
        bottomBar = {
            if (barOpen) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Sky.surface)
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEach { (label, icon, index) ->
                        BottomTab(label, icon, tab == index) {
                            if (index == 2) c.wardrive.refreshSessions()
                            tab = index
                            barOpen = false
                        }
                    }
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { barOpen = false },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("\u25BC", color = Sky.textDim, fontSize = 12.sp)
                    }
                }
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Column(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Sky.surface.copy(alpha = 0.95f))
                            .border(1.dp, Sky.outline, RoundedCornerShape(12.dp))
                            .clickable { barOpen = true },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            current.second,
                            contentDescription = current.first,
                            tint = Sky.cyan,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.height(1.dp))
                        Text("\u25B2", color = Sky.textDim, fontSize = 6.sp)
                    }
                }
            }
        },
    ) { inner ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            when (tab) {
                0 -> MapScreen(c, Modifier.fillMaxSize(), onOpenDrive = { tab = 1 })
                1 -> ScanScreen(c, Modifier.fillMaxSize(), onFixPermissions = request)
                2 -> LogScreen(c, Modifier.fillMaxSize(), onShowOnMap = { tab = 0 })
                else -> SetupScreen(c, Modifier.fillMaxSize(), onFixPermissions = request)
            }
        }
    }
}

@Composable
private fun BottomTab(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) Sky.cyan else Sky.textDim
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Sky.cyan.copy(alpha = 0.10f) else Sky.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = color)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.sp, color = color)
    }
}
