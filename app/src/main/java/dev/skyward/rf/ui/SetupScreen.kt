package dev.skyward.rf.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyward.rf.Container
import dev.skyward.rf.core.Settings
import dev.skyward.rf.peer.PeerProtocol
import dev.skyward.rf.ui.theme.Sky
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(c: Container, modifier: Modifier = Modifier, onFixPermissions: () -> Unit) {
    val settings by c.settingsStore.state.collectAsState()
    val peer by c.peer.state.collectAsState()
    val context = LocalContext.current
    val tileStore = remember { TileStore(context, c.http) }

    LazyColumn(
        modifier
            .fillMaxSize()
            .background(Sky.bg)
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(6.dp)) }

        item { AccountCard(c, settings) }

        item {
            SectionCard(title = "Satellites", trailing = "${c.sat.cachedCount()} TLEs") {
                Text("CelesTrak element sets, propagated on device (SGP4).", color = Sky.textDim, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (g in listOf("stations", "visual", "weather")) {
                        GroupChip(g, settings) { c.settingsStore.update(it) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (g in listOf("gps-ops", "science", "amateur")) {
                        GroupChip(g, settings) { c.settingsStore.update(it) }
                    }
                }
            }
        }

        item {
            SectionCard(title = "Aircraft", trailing = if (settings.aircraftEnabled) "on" else "off") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("ADS-B via api.adsb.lol", color = Sky.text, fontSize = 13.sp)
                        Text("Community feed, no key needed", color = Sky.textDim, fontSize = 10.sp)
                    }
                    Switch(
                        checked = settings.aircraftEnabled,
                        onCheckedChange = { on -> c.settingsStore.update { it.copy(aircraftEnabled = on) } },
                    )
                }
                Spacer(Modifier.height(6.dp))
                // Fixed, not a dial: a contact past 30 miles is not overhead traffic and its
                // link estimate is noise, so it is neither plotted nor logged.
                Text(
                    "Only aircraft inside 30 miles are shown and logged",
                    color = Sky.textDim,
                    fontSize = 10.sp,
                )
            }
        }

        item {
            SectionCard(title = "Mesh nodes", trailing = if (c.mesh.isRunning()) "live" else "off") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Meshtastic MQTT", color = Sky.text, fontSize = 13.sp)
                        Text("Public broker JSON feed + BLE advertisers", color = Sky.textDim, fontSize = 10.sp)
                    }
                    Switch(
                        checked = settings.meshMqttEnabled,
                        onCheckedChange = { on ->
                            c.settingsStore.update { it.copy(meshMqttEnabled = on) }
                            c.mesh.stop()
                            if (on) c.mesh.start()
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                MeshConfig(c, settings)
            }
        }

        item {
            SectionCard(title = "ESP32 sensors", trailing = "${c.esp32.count()} seen") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Boards and GPS modules", color = Sky.text, fontSize = 13.sp)
                        Text("BLE adverts, or JSON over MQTT", color = Sky.textDim, fontSize = 10.sp)
                    }
                    Switch(
                        checked = settings.esp32Enabled,
                        onCheckedChange = { on ->
                            c.settingsStore.update { it.copy(esp32Enabled = on) }
                            if (!on) c.esp32.stop()
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use board GPS as fallback", color = Sky.text, fontSize = 13.sp)
                        Text(
                            "While the phone has no fix of its own",
                            color = Sky.textDim,
                            fontSize = 10.sp,
                        )
                    }
                    Switch(
                        checked = settings.esp32GpsFallback,
                        onCheckedChange = { on -> c.settingsStore.update { it.copy(esp32GpsFallback = on) } },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("MQTT ingest", color = Sky.text, fontSize = 13.sp)
                        Text("For boards that publish over Wi-Fi", color = Sky.textDim, fontSize = 10.sp)
                    }
                    Switch(
                        checked = settings.esp32MqttEnabled,
                        onCheckedChange = { on ->
                            c.settingsStore.update { it.copy(esp32MqttEnabled = on) }
                            c.esp32.stop()
                            if (on) c.esp32.start()
                        },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("USB boards", color = Sky.text, fontSize = 13.sp)
                        Text("GhostESP, ESP32 Marauder, Biscuit, any ESP32 on a cable", color = Sky.textDim, fontSize = 10.sp)
                    }
                    Switch(
                        checked = settings.esp32UsbEnabled,
                        onCheckedChange = { on ->
                            c.settingsStore.update { it.copy(esp32UsbEnabled = on) }
                            if (on) c.esp32Usb.start() else c.esp32Usb.stop()
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Esp32Config(c, settings)
            }
        }

        item {
            SectionCard(title = "Map") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TileChip("dark", "Dark", settings) { c.settingsStore.update(it) }
                    TileChip("streets", "OSM", settings) { c.settingsStore.update(it) }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Hide weak dots", color = Sky.text, fontSize = 13.sp)
                        Text("Only plot entities above the red bin", color = Sky.textDim, fontSize = 10.sp)
                    }
                    Switch(
                        checked = settings.dropWeakDots,
                        onCheckedChange = { on -> c.settingsStore.update { it.copy(dropWeakDots = on) } },
                    )
                }
                Spacer(Modifier.height(8.dp))
                SmallAction("CLEAR TILE CACHE") { tileStore.clear() }
            }
        }

        item {
            SectionCard(title = "Logging", trailing = "${settings.logRadiusKm.toInt()} km") {
                Text(
                    "Satellites within this radius are captured during a drive. Aircraft are the 30 mi set.",
                    color = Sky.textDim,
                    fontSize = 11.sp,
                )
                Slider(
                    value = settings.logRadiusKm.toFloat(),
                    onValueChange = { v -> c.settingsStore.update { it.copy(logRadiusKm = v.toDouble()) } },
                    valueRange = 25f..500f,
                )
            }
        }

        item {
            SectionCard(
                title = "Two devices",
                trailing = when {
                    peer.connected -> "linked"
                    peer.running -> "searching"
                    else -> "off"
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Share scans and GPS over BLE", color = Sky.text, fontSize = 13.sp)
                        Text(
                            "Both phones keep wardriving on their own. The larger screen takes the globe, the smaller one the wardriving view.",
                            color = Sky.textDim,
                            fontSize = 10.sp,
                        )
                    }
                    Switch(
                        checked = settings.peerEnabled,
                        onCheckedChange = { on ->
                            c.settingsStore.update { it.copy(peerEnabled = on) }
                            if (on) c.peer.start() else c.peer.stop()
                        },
                    )
                }
                if (settings.peerEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This screen: ${PeerProtocol.roleLabel(peer.role)}",
                        color = Sky.cyan,
                        fontSize = 11.sp,
                    )
                    Text(
                        peer.detail + if (peer.connected) " · ${peer.peerName}" else "",
                        color = Sky.textDim,
                        fontSize = 10.sp,
                    )
                    Text(
                        "peer wifi ${peer.peerWifi} · ble ${peer.peerBle} · up ${peer.sent} down ${peer.received}",
                        color = Sky.textDim,
                        fontSize = 10.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Bluetooth must be on for both phones, and the link is peer to peer - nothing leaves the two devices.",
                        color = Sky.textDim,
                        fontSize = 10.sp,
                    )
                }
            }
        }

        item {
            SectionCard(title = "Permissions") {
                PermissionRow("Precise location", c.location.hasPermission())
                PermissionRow("Wi-Fi scanning", c.rf.hasWifiPermission())
                PermissionRow("Bluetooth scanning", c.rf.hasBlePermission())
                Spacer(Modifier.height(8.dp))
                SmallAction("REVIEW PERMISSIONS", Sky.cyan, onFixPermissions)
            }
        }

        item {
            SectionCard(title = "About") {
                Text("Signal_IntelGo 1.0", color = Sky.text, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Live satellites (CelesTrak + SGP4), aircraft (ADS-B), drones (ASTM F3411 Remote ID over BLE), " +
                        "mesh nodes (Meshtastic), and on-device Wi-Fi/BLE war-driving.",
                    color = Sky.textDim,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use device maps", color = Sky.text, fontSize = 13.sp)
                        Text(
                            "Open the phone's map app instead of fetching online tiles",
                            color = Sky.textDim,
                            fontSize = 10.sp,
                        )
                    }
                    Switch(
                        checked = settings.useDeviceMap,
                        onCheckedChange = { on -> c.settingsStore.update { it.copy(useDeviceMap = on) } },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(TileSources.attribution(settings.tileSource), color = Sky.textDim, fontSize = 10.sp)
                Text("Remote ID parsing is best-effort; some broadcasters rotate payloads.", color = Sky.textDim, fontSize = 10.sp)
            }
        }

        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
private fun MeshConfig(c: Container, settings: Settings) {
    var broker by remember(settings.meshBroker) { mutableStateOf(settings.meshBroker) }
    var topic by remember(settings.meshTopic) { mutableStateOf(settings.meshTopic) }
    var user by remember(settings.meshUser) { mutableStateOf(settings.meshUser) }
    var pass by remember(settings.meshPass) { mutableStateOf(settings.meshPass) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = broker,
            onValueChange = { broker = it },
            label = { Text("Broker", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = topic,
            onValueChange = { topic = it },
            label = { Text("Topic", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("User", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it },
                label = { Text("Password", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        SmallAction("APPLY + RECONNECT", Sky.mesh) {
            c.settingsStore.update {
                it.copy(meshBroker = broker, meshTopic = topic, meshUser = user, meshPass = pass)
            }
            c.mesh.stop()
            if (c.settings.meshMqttEnabled) c.mesh.start()
        }
        Text(
            "Public broker credentials are prefilled; use your own for a private mesh.",
            color = Sky.textDim,
            fontSize = 9.sp,
        )
    }
}

@Composable
private fun Esp32Config(c: Container, settings: Settings) {
    var broker by remember(settings.esp32Broker) { mutableStateOf(settings.esp32Broker) }
    var topic by remember(settings.esp32Topic) { mutableStateOf(settings.esp32Topic) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = broker,
            onValueChange = { broker = it },
            label = { Text("Broker", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = topic,
            onValueChange = { topic = it },
            label = { Text("Topic", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SmallAction("APPLY + RECONNECT", Sky.esp32) {
            c.settingsStore.update { it.copy(esp32Broker = broker, esp32Topic = topic) }
            c.esp32.stop()
            if (c.settings.esp32MqttEnabled) c.esp32.start()
        }
        Text(
            "BLE: manufacturer id 0xFFFF, then 53 47 01, lat/lon int32 x1e7, alt int16, " +
                "sats, hdop x10, battery, flags, wifi/ble counts (little-endian). " +
                "MQTT: {\"id\":\"roof\",\"lat\":51.5,\"lon\":-0.12,\"sats\":9,\"hdop\":0.9}",
            color = Sky.textDim,
            fontSize = 9.sp,
        )
    }
}

@Composable
private fun GroupChip(label: String, settings: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    val selected = settings.satGroups.contains(label)
    FilterChip(
        selected = selected,
        onClick = {
            onChange { s ->
                val next = if (selected) s.satGroups - label else s.satGroups + label
                s.copy(satGroups = if (next.isEmpty()) listOf(label) else next)
            }
        },
        label = { Text(label, fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
        shape = RoundedCornerShape(9.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Sky.surfaceHigh,
            labelColor = Sky.textDim,
            selectedContainerColor = Sky.satellite.copy(alpha = 0.22f),
            selectedLabelColor = Sky.satellite,
        ),
    )
}

@Composable
private fun TileChip(value: String, label: String, settings: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    val selected = settings.tileSource == value
    FilterChip(
        selected = selected,
        onClick = { onChange { it.copy(tileSource = value) } },
        label = { Text(label, fontSize = 10.sp) },
        shape = RoundedCornerShape(9.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Sky.surfaceHigh,
            labelColor = Sky.textDim,
            selectedContainerColor = Sky.cyan.copy(alpha = 0.2f),
            selectedLabelColor = Sky.cyan,
        ),
    )
}

@Composable
private fun PermissionRow(label: String, granted: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(4.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (granted) Sky.mesh else Sky.danger),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = Sky.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(
            if (granted) "granted" else "missing",
            color = if (granted) Sky.mesh else Sky.danger,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * The email account, and the copy of the data that outlives an uninstall.
 *
 * It starts as a plain server-and-key pair because the account is the user's own project, not one
 * this app runs: pasting two values is the whole setup, and the README has the four steps behind
 * them. Once that is saved the card stops asking and shows who is signed in instead.
 */
@Composable
private fun AccountCard(c: Container, settings: Settings) {
    val account by c.account.state.collectAsState()
    val cloud by c.cloud.state.collectAsState()
    val scope = rememberCoroutineScope()
    var url by remember(settings.cloudUrl) { mutableStateOf(settings.cloudUrl) }
    var key by remember(settings.cloudKey) { mutableStateOf(settings.cloudKey) }
    var email by remember(account.email) { mutableStateOf(account.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    val configured = settings.cloudUrl.isNotBlank() && settings.cloudKey.isNotBlank()

    SectionCard(
        title = "Account",
        trailing = when {
            account.busy || cloud.busy -> "working"
            account.signedIn -> "signed in"
            configured -> "signed out"
            else -> "off"
        },
    ) {
        Text(
            "Drives, the signal log and settings are copied to an account of your own, so a " +
                "reinstall gets them back. Nothing leaves the phone until you sign in.",
            color = Sky.textDim,
            fontSize = 11.sp,
        )

        if (!configured) {
            Spacer(Modifier.height(8.dp))
            AccountField("Project URL", "https://your-project.supabase.co", url) { url = it }
            Spacer(Modifier.height(6.dp))
            AccountField("Anon key", "public key from the project's API page", key) { key = it }
            Spacer(Modifier.height(8.dp))
            SmallAction("SAVE SERVER", Sky.cyan) {
                c.settingsStore.update { it.copy(cloudUrl = url.trim(), cloudKey = key.trim()) }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "A free project is enough. The README lists the four steps, including the one " +
                    "table to create.",
                color = Sky.textDim,
                fontSize = 9.sp,
            )
        } else if (!account.signedIn) {
            Spacer(Modifier.height(8.dp))
            AccountField("Email", "you@example.com", email) { email = it }
            Spacer(Modifier.height(6.dp))
            AccountField("Password", "at least 6 characters", password, secret = true) { password = it }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallAction("SIGN IN", Sky.cyan) {
                    scope.launch { c.account.signIn(settings.cloudUrl, settings.cloudKey, email, password) }
                }
                SmallAction("CREATE ACCOUNT", Sky.mesh) {
                    scope.launch { c.account.signUp(settings.cloudUrl, settings.cloudKey, email, password) }
                }
            }
            Spacer(Modifier.height(6.dp))
            // Swapping servers is not destructive: the file on this phone is not on either one.
            SmallAction("CHANGE SERVER", Sky.textDim) {
                c.settingsStore.update { it.copy(cloudUrl = "", cloudKey = "") }
            }
        } else {
            Text(account.email.orEmpty(), color = Sky.text, fontSize = 13.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                if (cloud.lastAt > 0L) "Last backup ${ago(cloud.lastAt)}" else "Nothing backed up yet",
                color = Sky.textDim,
                fontSize = 10.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallAction("BACK UP NOW", Sky.cyan) {
                    scope.launch { c.cloud.backupNow() }
                }
                SmallAction("RESTORE", Sky.warn) {
                    scope.launch { c.cloud.restoreNow() }
                }
                SmallAction("SIGN OUT", Sky.textDim) {
                    scope.launch { c.account.signOut(settings.cloudUrl, settings.cloudKey) }
                }
            }
        }

        val note = account.message ?: cloud.message.takeIf { it.isNotBlank() }
        if (!note.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                note,
                color = when {
                    note.startsWith("Signed out") || note.startsWith("Signed in") -> Sky.textDim
                    note.startsWith("Backed up") || note.startsWith("Restored") -> Sky.mesh
                    else -> Sky.warn
                },
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun AccountField(
    label: String,
    hint: String,
    value: String,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) },
        placeholder = { Text(hint, fontSize = 10.sp, color = Sky.textDim) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun ago(at: Long): String {
    val minutes = ((System.currentTimeMillis() - at) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} d ago"
    }
}
