package dev.skyward.rf.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
    }
    return list
}

@Composable
fun AppRoot(c: Container) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }

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

    Scaffold(
        containerColor = Sky.bg,
        bottomBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Sky.surface)
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BottomTab("Map", Icons.Filled.Place, tab == 0) { tab = 0 }
                BottomTab("Drive", Icons.Filled.PlayArrow, tab == 1) { tab = 1 }
                BottomTab("Log", Icons.Filled.List, tab == 2) {
                    c.wardrive.refreshSessions()
                    tab = 2
                }
                BottomTab("Setup", Icons.Filled.Settings, tab == 3) { tab = 3 }
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
