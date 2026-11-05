package dev.skyward.rf.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyward.rf.desktop.DesktopContainer
import dev.skyward.rf.desktop.host.DeviceHost
import dev.skyward.rf.desktop.host.HostSettings

/** The phone's palette, so the desk window reads as the same product. */
val Ink = Color(0xFF05070C)
val Panel = Color(0xFF0B111B)
val Line = Color(0xFF243044)
val Sky = Color(0xFF22D3EE)
val Mint = Color(0xFF5BE49B)
val Violet = Color(0xFFC084FC)
val Amber = Color(0xFFFB923C)
val TextMain = Color(0xFFE8EEF7)
val TextDim = Color(0xFF8FA3BF)

private val tabs = listOf("Map", "Drive", "Log", "Setup", "Host")

@Composable
fun DesktopApp(container: DesktopContainer) {
    val host by container.host.state.collectAsState()
    val settings by container.settings.collectAsState()
    val sky by container.sky.state.collectAsState()
    val drive by container.wardrive.overlay.collectAsState()
    val config by container.settingsStore.state.collectAsState()

    // The first device on the link that holds a fix stands in as the observer, the way a board
    // stands in on the phone when the handset has none.
    val observer = host.devices.firstNotNullOfOrNull { it.fix }

    var tab by remember { mutableStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Ink)) {
        when (tab) {
            0 -> MapScreen(
                sky = sky,
                observer = observer,
                land = container.land,
                tiles = container.tiles,
                drive = drive,
                spin = config.globeSpin > 0,
                modifier = Modifier.fillMaxSize(),
            )

            1 -> DriveScreen(container, host, Modifier.fillMaxSize())
            2 -> LogScreen(container, Modifier.fillMaxSize()) { tab = 0 }
            3 -> SetupScreen(container, Modifier.fillMaxSize())
            else -> HostScreen(host, settings, container, Modifier.fillMaxSize())
        }

        BottomMenu(
            tab = tab,
            open = menuOpen,
            onOpen = { menuOpen = true },
            onClose = { menuOpen = false },
            onPick = {
                tab = it
                menuOpen = false
            },
        )
    }
}

/**
 * The folded menu, built like the phone's: nothing at all until it is opened, then one square
 * button in the corner, then the tabs.
 */
@Composable
private fun BottomMenu(
    tab: Int,
    open: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onPick: (Int) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (open) {
            Row(
                Modifier.align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Panel.copy(alpha = 0.97f))
                    .border(1.dp, Line, RoundedCornerShape(16.dp))
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, label ->
                    val selected = tab == index
                    Row(
                        Modifier.clip(RoundedCornerShape(11.dp))
                            .background(if (selected) Sky.copy(alpha = 0.12f) else Panel)
                            .clickable { onPick(index) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label,
                            fontSize = 12.sp,
                            color = if (selected) Sky else TextDim,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("\u25BC", color = TextDim, fontSize = 12.sp)
                }
            }
        } else {
            Column(
                Modifier.align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 12.dp)
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Panel.copy(alpha = 0.95f))
                    .border(1.dp, Line, RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpen),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("HOST", color = Sky, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                Spacer(Modifier.height(1.dp))
                Text("\u25B2", color = TextDim, fontSize = 7.sp)
            }
        }
    }
}

@Composable
private fun HostScreen(
    host: DeviceHost.State,
    settings: HostSettings,
    container: DesktopContainer,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 70.dp),
    ) {
        Text("HOST", color = Sky, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        Text(
            "every phone and sensor board the desktop has linked",
            color = TextDim,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(if (host.adapterOk) Mint else Amber))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (host.adapterOk) "BLE ADAPTER - HOSTING" else "BLE ADAPTER - NOT READY",
                    color = if (host.adapterOk) Mint else Amber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                )
                Text(host.adapterDetail, color = TextDim, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("DEVICES (${host.devices.size})", color = TextDim, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(6.dp))
        if (host.devices.isEmpty()) {
            Text(
                "No phone or sensor board heard yet. Turn the phone's two-device link on, or power a board.",
                color = TextDim,
                fontSize = 11.sp,
            )
        } else {
            host.devices.forEach { device -> DeviceRow(device); Spacer(Modifier.height(6.dp)) }
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("Wi-Fi", host.wifi.size.toString(), Modifier.weight(1f))
            Stat("BLE", host.ble.size.toString(), Modifier.weight(1f))
            Stat("In", host.framesIn.toString(), Modifier.weight(1f))
            Stat("Out", host.framesOut.toString(), Modifier.weight(1f))
        }

        Spacer(Modifier.height(14.dp))
        Text("PUSHED TO EVERY LINKED DEVICE", color = TextDim, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Text("revision ${settings.rev}", color = TextDim, fontSize = 10.sp)
        Spacer(Modifier.height(6.dp))
        Toggle("Aircraft layer", settings.aircraftEnabled) { on -> container.update { it.copy(aircraftEnabled = on) } }
        Toggle("Drop weak dots", settings.dropWeakDots) { on -> container.update { it.copy(dropWeakDots = on) } }
        Toggle("Spinning globe", settings.globeSpin > 0) { on -> container.update { it.copy(globeSpin = if (on) 1 else 0) } }
        Toggle("Miles", settings.distanceUnit == "mi") { on -> container.update { it.copy(distanceUnit = if (on) "mi" else "km") } }
        Toggle("Fahrenheit", settings.tempUnit == "f") { on -> container.update { it.copy(tempUnit = if (on) "f" else "c") } }

        Spacer(Modifier.height(14.dp))
        Text("ACTIVITY", color = TextDim, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(4.dp))
        host.log.take(14).forEach { line ->
            Text(line, color = TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 1.dp))
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceHost.Hosted) {
    val accent = when (device.kind) {
        DeviceHost.Kind.PEER -> Sky
        DeviceHost.Kind.BOARD -> Mint
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Panel)
            .border(1.dp, Line, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(8.dp))
            Text(device.name, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${device.rssi} dBm", color = TextDim, fontSize = 11.sp)
        }
        Spacer(Modifier.height(3.dp))
        val parts = mutableListOf<String>()
        if (device.kind == DeviceHost.Kind.PEER) {
            parts += if (device.connected) "linked" else "seen"
            if (device.mtu > 0) parts += "mtu ${device.mtu}"
            parts += "wifi ${device.wifi}"
            parts += "ble ${device.ble}"
            parts += "${device.frames} frames"
            if (device.recording) parts += "recording ${device.recordName}"
        } else {
            parts += device.firmware.ifBlank { "sensor" }
            parts += "wifi ${device.wifi}"
            parts += "ble ${device.ble}"
            if (device.sats > 0) parts += "${device.sats} sats"
            if (device.battery >= 0) parts += "${device.battery}%"
        }
        device.fix?.let { parts += "%.4f, %.4f".format(it.lat, it.lon) }
        Text(parts.joinToString("  ·  "), color = TextDim, fontSize = 10.sp)
    }
}

/** A tappable label in the phone's style: tinted, boxed, and quiet when it is not available. */
@Composable
fun Action(label: String, color: Color = Sky, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = if (enabled) 0.12f else 0.04f))
            .border(1.dp, color.copy(alpha = if (enabled) 0.45f else 0.12f), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (enabled) color else TextDim.copy(alpha = 0.5f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
        )
    }
}

/** A titled block: the shape every screen on the phone is built from. */
@Composable
fun Section(title: String, trailing: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Panel)
            .border(1.dp, Line, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title.uppercase(),
                color = Sky,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) Text(trailing, color = TextDim, fontSize = 10.sp)
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).background(Panel).border(1.dp, Line, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label.uppercase(), color = TextDim, fontSize = 9.sp, letterSpacing = 1.sp)
        Text(value, color = Sky, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextMain, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Sky),
        )
    }
}
