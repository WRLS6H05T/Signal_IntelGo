package dev.skyward.rf.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyward.rf.desktop.DesktopContainer
import dev.skyward.rf.desktop.auth.GoogleAuth
import dev.skyward.rf.desktop.host.DeviceHost
import dev.skyward.rf.desktop.host.HostSettings

/** The app's own palette, so the desktop reads as the same product as the phone. */
val Ink = Color(0xFF070D17)
val Panel = Color(0xFF0D1626)
val Line = Color(0xFF1E2A3D)
val Sky = Color(0xFF38BDF8)
val Mint = Color(0xFF5BE49B)
val Violet = Color(0xFFC084FC)
val Amber = Color(0xFFFDAE61)
val TextMain = Color(0xFFE2E8F0)
val TextDim = Color(0xFF7C8CA3)

@Composable
fun DesktopApp(container: DesktopContainer) {
    val host by container.host.state.collectAsState()
    val auth by container.auth.state.collectAsState()
    val settings by container.settings.collectAsState()

    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Header(auth, container)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1.2f).fillMaxHeight()) {
                AdapterCard(host.adapterOk, host.adapterDetail, host.devices.size)
                Spacer(Modifier.height(12.dp))
                DevicesCard(host.devices)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                SettingsCard(settings, container)
                Spacer(Modifier.height(12.dp))
                TablesCard(host.wifi.size, host.ble.size, host.framesIn, host.framesOut)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                LogCard(host.log)
            }
        }
    }
}

@Composable
private fun Header(auth: GoogleAuth.State, container: DesktopContainer) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "SIGNAL_INTELGO",
                color = Sky,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
            )
            Text(
                "desktop host - every phone and sensor board on one link",
                color = TextDim,
                fontSize = 12.sp,
            )
        }
        AccountBlock(auth, container)
    }
}

@Composable
private fun AccountBlock(auth: GoogleAuth.State, container: DesktopContainer) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val (label, colour) = when (auth) {
            is GoogleAuth.State.SignedIn -> "${auth.account.name} - ${auth.account.email}" to Mint
            is GoogleAuth.State.SigningIn -> auth.detail to Amber
            is GoogleAuth.State.SignedOut -> auth.detail to TextDim
        }
        Text(label, color = colour, fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp))
        when (auth) {
            is GoogleAuth.State.SignedIn -> Ghost("SIGN OUT") { container.auth.signOut() }
            is GoogleAuth.State.SigningIn -> Ghost("WAITING...") {}
            is GoogleAuth.State.SignedOut -> Primary("SIGN IN WITH GOOGLE") { container.auth.signIn() }
        }
    }
}

@Composable
private fun AdapterCard(ok: Boolean, detail: String, devices: Int) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(9.dp).clip(CircleShape)
                    .background(if (ok) Mint else Amber),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (ok) "BLE ADAPTER - HOSTING" else "BLE ADAPTER - NOT READY",
                    color = if (ok) Mint else Amber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                )
                Text(detail, color = TextDim, fontSize = 11.sp)
            }
            Text("$devices DEVICES", color = TextDim, fontSize = 11.sp)
        }
    }
}

@Composable
private fun DevicesCard(devices: List<DeviceHost.Hosted>) {
    Card(Modifier.fillMaxHeight()) {
        SectionTitle("DEVICES")
        if (devices.isEmpty()) {
            Empty("No phone or sensor board heard yet. Turn the phone's two-device link on, or power a board.")
            return@Card
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(devices) { device -> DeviceRow(device) }
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
            .clip(RoundedCornerShape(6.dp))
            .background(Panel)
            .border(1.dp, Line, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(8.dp))
            Text(
                device.name,
                color = TextMain,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text("${device.rssi} dBm", color = TextDim, fontSize = 11.sp)
        }
        Spacer(Modifier.height(4.dp))
        val parts = mutableListOf<String>()
        if (device.kind == DeviceHost.Kind.PEER) {
            parts += if (device.connected) "linked" else "seen"
            if (device.mtu > 0) parts += "mtu ${device.mtu}"
            parts += "wifi ${device.wifi}"
            parts += "ble ${device.ble}"
            parts += "${device.frames} frames"
            if (device.recording) parts += "recording ${device.recordName}"
            if (device.scan.isNotBlank()) parts += "scan ${device.scan}"
        } else {
            parts += device.firmware.ifBlank { "sensor" }
            parts += "wifi ${device.wifi}"
            parts += "ble ${device.ble}"
            if (device.sats > 0) parts += "${device.sats} sats"
            if (device.battery >= 0) parts += "${device.battery}%"
            if (device.moving) parts += "moving"
        }
        if (device.fix != null) {
            parts += "%.5f, %.5f".format(device.fix.lat, device.fix.lon)
        }
        Text(parts.joinToString(" - "), color = TextDim, fontSize = 11.sp)

        if (device.kind == DeviceHost.Kind.PEER) {
            Spacer(Modifier.height(2.dp))
            Text(device.address, color = TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun SettingsCard(settings: HostSettings, container: DesktopContainer) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("HOST SETTINGS", Modifier.weight(1f))
            Text("rev ${settings.rev}", color = TextDim, fontSize = 10.sp)
        }
        Text(
            "Pushed to every linked device with each snapshot, the way a host phone does it.",
            color = TextDim,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(10.dp))
        Toggle("Aircraft layer", settings.aircraftEnabled) { on ->
            container.update { it.copy(aircraftEnabled = on) }
        }
        Toggle("Drop weak dots", settings.dropWeakDots) { on ->
            container.update { it.copy(dropWeakDots = on) }
        }
        Toggle("Spinning globe", settings.globeSpin > 0) { on ->
            container.update { it.copy(globeSpin = if (on) 1 else 0) }
        }
        Toggle("Miles", settings.distanceUnit == "mi") { on ->
            container.update { it.copy(distanceUnit = if (on) "mi" else "km") }
        }
        Toggle("Fahrenheit", settings.tempUnit == "f") { on ->
            container.update { it.copy(tempUnit = if (on) "f" else "c") }
        }
    }
}

@Composable
private fun TablesCard(wifi: Int, ble: Int, framesIn: Int, framesOut: Int) {
    Card {
        SectionTitle("WHAT THE HOST HOLDS")
        Stat("Wi-Fi networks", "$wifi")
        Stat("BLE devices", "$ble")
        Stat("Frames from devices", "$framesIn")
        Stat("Frames to devices", "$framesOut")
    }
}

@Composable
private fun LogCard(log: List<String>) {
    Card(Modifier.fillMaxHeight()) {
        SectionTitle("ACTIVITY")
        if (log.isEmpty()) {
            Empty("Nothing yet.")
            return@Card
        }
        LazyColumn {
            items(log) { line ->
                Text(
                    line,
                    color = TextDim,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

// ---------------- small pieces ----------------

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Panel)
            .border(1.dp, Line, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        content()
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(bottom = 8.dp),
        color = TextDim,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.6.sp,
    )
}

@Composable
private fun Empty(text: String) {
    Text(text, color = TextDim, fontSize = 11.sp)
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = Sky, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextMain, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Sky),
        )
    }
}

@Composable
private fun Primary(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Sky, contentColor = Ink),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Ghost(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = TextDim),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
