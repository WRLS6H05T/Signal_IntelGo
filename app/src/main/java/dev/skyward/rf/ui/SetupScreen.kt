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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyward.rf.Container
import dev.skyward.rf.core.LOG_RADIUS_KM
import dev.skyward.rf.core.Settings
import dev.skyward.rf.core.Units
import dev.skyward.rf.peer.PeerProtocol
import dev.skyward.rf.sky.AircraftSource
import dev.skyward.rf.ui.theme.Sky

@Composable
fun SetupScreen(c: Container, modifier: Modifier = Modifier, onFixPermissions: () -> Unit) {
    val settings by c.settingsStore.state.collectAsState()
    val peer by c.peer.state.collectAsState()
    val sky by c.airspace.state.collectAsState()
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

        item { DataFolderCard(c) }

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
                Spacer(Modifier.height(8.dp))
                // Named because they were asked for, and honest about why they are not on
                // the map: a satellite that re-entered has no orbit left to draw.
                Text(
                    "BIRDS-3 - NepaliSat-1, Raavana-1 and Uguisu - deployed from the ISS in " +
                        "June 2019 and re-entered in October 2021. CelesTrak has no live " +
                        "elements, so they are tracked automatically if any are catalogued again.",
                    color = Sky.textDim,
                    fontSize = 10.sp,
                )
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
                    "Only aircraft inside " +
                        Units.distance(AircraftSource.RANGE_KM, settings.distanceUnit, 0) +
                        " are shown and logged",
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
            SectionCard(title = "Clock", trailing = if (settings.showLiveTime) "top right" else "off") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Live time", color = Sky.text, fontSize = 13.sp)
                        Text(
                            "A running clock in the map's top-right corner. Tapping it opens " +
                                "the weather at your GPS pin.",
                            color = Sky.textDim,
                            fontSize = 10.sp,
                        )
                    }
                    Switch(
                        checked = settings.showLiveTime,
                        onCheckedChange = { on -> c.settingsStore.update { it.copy(showLiveTime = on) } },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Weather from Open-Meteo: no account and no key. It is read at most every " +
                        "ten minutes, and again after moving two kilometres. The drop-down also " +
                        "gives the terrain height at the pin, which is a useful second opinion " +
                        "on the map's own elevation.",
                    color = Sky.textDim,
                    fontSize = 10.sp,
                )
            }
        }

        item {
            SectionCard(
                title = "Units",
                trailing = Units.distanceLabel(settings.distanceUnit) + " \u00b7 " +
                    Units.temperatureLabel(settings.tempUnit),
            ) {
                Text("DISTANCE", style = SkywardThemeLabel(), color = Sky.textDim)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    UnitChip("KM", settings.distanceUnit != Units.MILES) {
                        c.settingsStore.update { it.copy(distanceUnit = Units.KM) }
                    }
                    UnitChip("MILES", settings.distanceUnit == Units.MILES) {
                        c.settingsStore.update { it.copy(distanceUnit = Units.MILES) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("TEMPERATURE", style = SkywardThemeLabel(), color = Sky.textDim)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    UnitChip("\u00b0C", settings.tempUnit != Units.FAHRENHEIT) {
                        c.settingsStore.update { it.copy(tempUnit = Units.CELSIUS) }
                    }
                    UnitChip("\u00b0F", settings.tempUnit == Units.FAHRENHEIT) {
                        c.settingsStore.update { it.copy(tempUnit = Units.FAHRENHEIT) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Distances are stored in kilometres and converted only where they are " +
                        "written on screen, so switching units never changes a saved drive or " +
                        "what the other phone receives. Wind and rain follow the distance " +
                        "unit: mph and inches with miles, km/h and millimetres with kilometres.",
                    color = Sky.textDim,
                    fontSize = 10.sp,
                )
            }
        }

        item {
            SectionCard(title = "Watch list", trailing = "${sky.watch.size} heard") {
                Text(
                    "Passive matches for surveillance hardware: Flock Safety ALPR cameras " +
                        "(including the 'FS Ext Battery' packs and Flock-XXXX names), Axon " +
                        "body cameras, and Axon TASERs.",
                    color = Sky.textDim,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "The match is made from the name a device broadcasts, plus the Bluetooth " +
                        "manufacturer id Flock's hardware uses (XUNTONG, 0x09C8). A camera that " +
                        "keeps its name hidden, or any phone whose address rotates, cannot be " +
                        "told apart from an anonymous MAC - so a hit is a lead, not proof. " +
                        "A marker reading 'name + manufacturer id' is the one to trust.",
                    color = Sky.textDim,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.height(6.dp))
                SwatchRow(color = Sky.watchFlock, label = "FLOCK", detail = "ALPR camera")
                SwatchRow(color = Sky.watchAxon, label = "AXON", detail = "body camera")
                SwatchRow(color = Sky.watchTaser, label = "TASER", detail = "Axon CED")
            }
        }

        item {
            SectionCard(
                title = "Logging",
                trailing = Units.distance(LOG_RADIUS_KM, settings.distanceUnit, 0),
            ) {
                // Fixed, not a dial. A wider radius caught most of the catalogue on every
                // tick, which is the map rather than anything overhead.
                Text(
                    "Satellites within " + Units.distance(LOG_RADIUS_KM, settings.distanceUnit, 0) +
                        " are captured during a drive. Aircraft are the " +
                        Units.distance(AircraftSource.RANGE_KM, settings.distanceUnit, 0) + " set.",
                    color = Sky.textDim,
                    fontSize = 11.sp,
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
                    Spacer(Modifier.height(10.dp))
                    Text("Link role", style = SkywardThemeLabel(), color = Sky.textDim)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RoleChip("AUTO", PeerProtocol.MODE_AUTO, settings) { c.settingsStore.update(it) }
                        RoleChip("HOST", PeerProtocol.MODE_HOST, settings) { c.settingsStore.update(it) }
                        RoleChip("GLOBE VIEW", PeerProtocol.MODE_GLOBE, settings) { c.settingsStore.update(it) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when (settings.peerRoleMode) {
                            PeerProtocol.MODE_HOST ->
                                "This phone shows the globe and pushes its scan, map and layer " +
                                    "settings to every linked device."
                            PeerProtocol.MODE_GLOBE ->
                                "This phone shows the globe and takes its settings from the " +
                                    "host. Assigning a role here overrides the screen-size split."
                            else ->
                                "Auto: the larger screen takes the globe, the smaller one the " +
                                    "wardriving view."
                        },
                        color = Sky.textDim,
                        fontSize = 10.sp,
                    )
                    if (settings.peerRoleMode == PeerProtocol.MODE_GLOBE) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Follow host settings", color = Sky.text, fontSize = 13.sp)
                                Text(
                                    "Apply the host's map, layers and scan assignments as they arrive",
                                    color = Sky.textDim,
                                    fontSize = 10.sp,
                                )
                            }
                            Switch(
                                checked = settings.followHost,
                                onCheckedChange = { on -> c.settingsStore.update { it.copy(followHost = on) } },
                            )
                        }
                    }
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
                // The build number is how a phone proves it is running the build that was
                // published, rather than an older one with the same name.
                val version = remember {
                    runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull()
                }
                Text(
                    "Signal_IntelGo ${version ?: "1.1"}",
                    color = Sky.text,
                    fontSize = 13.sp,
                )
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

        // Room for the folded tab button in the corner, so it never covers the last row.
        item { Spacer(Modifier.height(64.dp)) }
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
private fun RoleChip(label: String, value: String, settings: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    val selected = settings.peerRoleMode == value
    FilterChip(
        selected = selected,
        onClick = { onChange { it.copy(peerRoleMode = value) } },
        label = { Text(label, fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
        shape = RoundedCornerShape(9.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Sky.surfaceHigh,
            labelColor = Sky.textDim,
            selectedContainerColor = Sky.cyan.copy(alpha = 0.22f),
            selectedLabelColor = Sky.cyan,
        ),
    )
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
private fun UnitChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
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
 * Where the intel lives, and how to get it off the phone.
 *
 * The folder is on shared storage, so a PC that mounts the phone can copy it with the file manager
 * it already has: no account to sign in to, and this app does not have to be running. That is the
 * whole transfer story - copy the folder off, copy it back on the next phone.
 */
@Composable
private fun DataFolderCard(c: Container) {
    SectionCard(title = "Data folder") {
        Text(
            "Drives, the signal log, what has been captured and the settings are plain files in " +
                "one folder on this phone.",
            color = Sky.textDim,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            c.dataFolderPath,
            color = Sky.cyan,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "On a PC that is Internal storage/Android/data/dev.skyward.rf/files/data. Copy it off " +
                "with the phone mounted over USB.",
            color = Sky.textDim,
            fontSize = 10.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Uninstalling deletes the folder with the app, so copy it off first.",
            color = Sky.warn,
            fontSize = 10.sp,
        )
    }
}
