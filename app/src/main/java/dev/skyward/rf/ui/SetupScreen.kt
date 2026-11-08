package dev.skyward.rf.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.skyward.rf.Container
import dev.skyward.rf.core.LOG_RADIUS_KM
import dev.skyward.rf.core.Settings
import dev.skyward.rf.core.Units
import dev.skyward.rf.core.VoiceStyles
import dev.skyward.rf.core.Watcher
import dev.skyward.rf.meshcore.WadaTransfer
import dev.skyward.rf.peer.PeerProtocol
import dev.skyward.rf.scan.UsbBoards
import dev.skyward.rf.sky.AircraftSource
import dev.skyward.rf.ui.theme.Sky
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(c: Container, modifier: Modifier = Modifier, onFixPermissions: () -> Unit) {
    val settings by c.settingsStore.state.collectAsState()
    val peer by c.peer.state.collectAsState()
    val sky by c.airspace.state.collectAsState()
    val voice by c.voice.state.collectAsState()
    val context = LocalContext.current
    val tileStore = remember { TileStore(context, c.http) }
    // The WADAMESH session belongs to the screen, not to a list row: scrolling Setup past the
    // mesh card would cancel the item's composition and close a device connection the user
    // is still using.
    val wada = remember { WadaTransfer() }
    DisposableEffect(Unit) { onDispose { wada.close() } }

    // The microphone is asked for at the moment the switch is turned on, never at launch: an
    // app that opens a wardriving map should not be asking to record before it has drawn a map.
    var micNote by remember { mutableStateOf<String?>(null) }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            micNote = null
            c.settingsStore.update { it.copy(voiceEnabled = true) }
        } else {
            micNote = "Microphone permission is needed for voice."
        }
    }

    // Two panes, each with its own scroll: what the app does on top, what is plugged
    // into it below. They do not share a scroll position, so reaching the peer card no
    // longer scrolls the satellite groups off the screen.
    Column(modifier.fillMaxSize().background(Sky.bg)) {
        PaneLabel("APP")
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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
                    Spacer(Modifier.height(6.dp))
                    // The two constellations people ask for by name. Starlink is one group of
                    // thousands; PRIVATE is the CubeSat catalogue, which is where privately built
                    // and operated smallsats live - they are not a government series like the
                    // weather or GPS groups above.
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GroupChip("starlink", settings, name = "STRLINK") { c.settingsStore.update(it) }
                        GroupChip("cubesat", settings, name = "PRIVATE") { c.settingsStore.update(it) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "PRIVATE is the CubeSat catalogue: privately built and operated smallsats. " +
                            "STRLINK is the whole Starlink shell, so the sky tick slows while it is on.",
                        color = Sky.textDim,
                        fontSize = 9.sp,
                    )
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
                SectionCard(title = "Location", trailing = if (settings.gpsEnabled) "on" else "off") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Phone GPS", color = Sky.text, fontSize = 13.sp)
                            Text(
                                "Satellites, aircraft and watch hits need a fix",
                                color = Sky.textDim,
                                fontSize = 10.sp,
                            )
                        }
                        Switch(
                            checked = settings.gpsEnabled,
                            onCheckedChange = { on -> c.settingsStore.update { it.copy(gpsEnabled = on) } },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Off stops the receiver; the map keeps the last position it had. Everything " +
                            "that needs a fix - overhead satellites, ADS-B, a watch hit's placement - " +
                            "goes quiet until it is switched back on.",
                        color = Sky.textDim,
                        fontSize = 10.sp,
                    )
                }
            }
            item {
                SectionCard(title = "Offline", trailing = if (settings.offlineMode) "on" else "off") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Offline mode", color = Sky.text, fontSize = 13.sp)
                            Text(
                                "GPS satellites only, from what is already on the phone",
                                color = Sky.textDim,
                                fontSize = 10.sp,
                            )
                        }
                        Switch(
                            checked = settings.offlineMode,
                            onCheckedChange = { on -> c.settingsStore.update { it.copy(offlineMode = on) } },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "On, the globe and the map draw the GPS constellation and nothing else - no " +
                            "aircraft, drones, mesh nodes, cell towers, ESP32 boards, watch hits, " +
                            "logged signals or drive path. The element sets are the ones already " +
                            "saved and the basemap is the tiles already cached, so no feed is asked " +
                            "for and it works with no signal at all. An empty sky means the " +
                            "satellites have not been downloaded yet: switch this off once with a " +
                            "network, then back on.",
                        color = Sky.textDim,
                        fontSize = 10.sp,
                    )
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
                    Spacer(Modifier.height(10.dp))
                    // One ring colour for every logged entity - aircraft, drones, mesh, towers,
                    // boards and satellites alike - so "caught" looks the same in every layer. It
                    // lives here rather than under Satellites because it is no longer theirs.
                    Text("ENTITY COLOUR", style = SkywardThemeLabel(), color = Sky.textDim)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((key, swatch) in EntityColors.entries.take(3)) {
                            EntityColorChip(key, swatch, settings) { c.settingsStore.update(it) }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((key, swatch) in EntityColors.entries.drop(3)) {
                            EntityColorChip(key, swatch, settings) { c.settingsStore.update(it) }
                        }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Seconds", color = Sky.text, fontSize = 13.sp)
                            Text(
                                "Off, the hour and minute are drawn larger in the same corner",
                                color = Sky.textDim,
                                fontSize = 10.sp,
                            )
                        }
                        Switch(
                            checked = settings.clockSeconds,
                            onCheckedChange = { on -> c.settingsStore.update { it.copy(clockSeconds = on) } },
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
                SectionCard(
                    title = "Watcher",
                    trailing = when {
                        !settings.voiceEnabled -> "off"
                        voice.note.isNotBlank() -> "unavailable"
                        voice.speaking -> "speaking"
                        voice.listening -> "listening"
                        else -> "tap to talk"
                    },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Hands-free voice", color = Sky.text, fontSize = 13.sp)
                            Text("Say 'Watcher', then a command", color = Sky.textDim, fontSize = 10.sp)
                        }
                        Switch(
                            checked = settings.voiceEnabled,
                            onCheckedChange = { on ->
                                if (!on) {
                                    c.settingsStore.update { it.copy(voiceEnabled = false) }
                                } else {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO,
                                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                    if (granted) c.settingsStore.update { it.copy(voiceEnabled = true) }
                                    else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Machine voice", color = Sky.text, fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (gender in VoiceStyles.genders) {
                            UnitChip(
                                label = gender.label,
                                selected = VoiceStyles.of(settings.voiceGender).gender == gender,
                                onClick = {
                                    c.settingsStore.update { it.copy(voiceGender = gender.name.lowercase()) }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Flat and clipped, one idea per sentence; the pitch is the only difference.",
                            color = Sky.textDim,
                            fontSize = 10.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (settings.voiceEnabled) {
                            FilterChip(
                                selected = false,
                                onClick = {
                                    c.voice.speak(
                                        VoiceStyles.sample(VoiceStyles.of(settings.voiceGender).gender),
                                    )
                                },
                                label = {
                                    Text("hear it", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                },
                                shape = RoundedCornerShape(9.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Sky.surfaceHigh,
                                    labelColor = Sky.cyan,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Spoken replies", color = Sky.text, fontSize = 13.sp)
                            Text("Watch alerts use the glitched voice", color = Sky.textDim, fontSize = 10.sp)
                        }
                        Switch(
                            checked = settings.voiceAudio,
                            onCheckedChange = { on -> c.settingsStore.update { it.copy(voiceAudio = on) } },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "It only listens between a tap on the status box under the map's clock and the " +
                            "end of the command that follows - nothing holds the microphone on between " +
                            "taps. Tap, then say: 'intel report', 'open map', 'drive', 'log', 'setup', " +
                            "'open menu', 'close menu', 'start logging', 'stop logging', 'miles', " +
                            "'kilometres', 'celsius', 'fahrenheit', 'mute', 'unmute', 'sleep'. The " +
                            "name 'Watcher' may be said first, but it is not needed. The recogniser " +
                            "and the speaker are the phone's own - nothing is recorded to a file and " +
                            "nothing is sent anywhere.",
                        color = Sky.textDim,
                        fontSize = 10.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "A phone's engine has one voice per language and a pitch and rate dial, so the " +
                            "machine is made by pacing: commas and dashes become full stops, and the " +
                            "gender is the pitch - the woman a little above the engine's natural voice, " +
                            "the man well below it. That is the honest limit of text-to-speech, not a " +
                            "clone of the Watch Dogs performance.",
                        color = Sky.textDim,
                        fontSize = 10.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "The glitched delivery is kept for the watch list in either voice: Axon, " +
                            "TASER, Flock and trackers, once per device per five minutes.",
                        color = Sky.textDim,
                        fontSize = 10.sp,
                    )
                    val note = micNote ?: voice.note
                    if (note.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(note, color = Sky.warn, fontSize = 10.sp)
                    }
                }
            }
            item {
                SectionCard(
                    title = "Intel updates",
                    trailing = if (settings.intelUpdateMinutes <= 0) "off"
                    else "every ${settings.intelUpdateMinutes} min",
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Timed intel update", color = Sky.text, fontSize = 13.sp)
                            Text(
                                "The Watcher reads its table out on its own",
                                color = Sky.textDim,
                                fontSize = 10.sp,
                            )
                        }
                        Switch(
                            checked = settings.intelUpdateMinutes > 0,
                            onCheckedChange = { on ->
                                val keep = settings.intelUpdateMinutes
                                c.settingsStore.update {
                                    it.copy(intelUpdateMinutes = if (on) (if (keep > 0) keep else 5) else 0)
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("WATCHER READS", style = SkywardThemeLabel(), color = Sky.textDim)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ModeChip("INTEL", settings.watcherReportStyle == Watcher.STYLE_INTEL) {
                            c.settingsStore.update { it.copy(watcherReportStyle = Watcher.STYLE_INTEL) }
                        }
                        ModeChip("ENTITIES", settings.watcherReportStyle == Watcher.STYLE_ENTITIES) {
                            c.settingsStore.update { it.copy(watcherReportStyle = Watcher.STYLE_ENTITIES) }
                        }
                        ModeChip("BOTH", settings.watcherReportStyle == Watcher.STYLE_BOTH) {
                            c.settingsStore.update { it.copy(watcherReportStyle = Watcher.STYLE_BOTH) }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when (settings.watcherReportStyle) {
                            Watcher.STYLE_INTEL ->
                                "The live table only: connected devices, the radios' counts and the " +
                                    "fix. Nothing from the log."
                            Watcher.STYLE_ENTITIES ->
                                "The entity table only: what the drive has caught, per layer. No " +
                                    "live counts."
                            else ->
                                "The live table, then the entity table: what is overhead now and " +
                                    "what the drive has caught."
                        },
                        color = Sky.textDim,
                        fontSize = 10.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("MINUTES BETWEEN UPDATES", style = SkywardThemeLabel(), color = Sky.textDim)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (choice in intArrayOf(3, 5, 7, 10, 30)) {
                            FilterChip(
                                selected = settings.intelUpdateMinutes == choice,
                                onClick = {
                                    c.settingsStore.update { it.copy(intelUpdateMinutes = choice) }
                                },
                                label = {
                                    Text("$choice", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                },
                                shape = RoundedCornerShape(9.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Sky.surfaceHigh,
                                    labelColor = Sky.textDim,
                                    selectedContainerColor = Sky.cyan.copy(alpha = 0.22f),
                                    selectedLabelColor = Sky.cyan,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (settings.intelUpdateMinutes <= 0) {
                            "Off. The Watcher only speaks when it is asked to."
                        } else {
                            "Every ${settings.intelUpdateMinutes} minutes it keys up with 'Watcher " +
                                "to operator, intel update', reads " +
                                (when (settings.watcherReportStyle) {
                                    Watcher.STYLE_INTEL -> "the live table"
                                    Watcher.STYLE_ENTITIES -> "the entity table"
                                    else -> "the live table then the entity table"
                                }) + ", then the watch sightings since the last update, and signs " +
                                "off 'Watcher out'. It needs hands-free voice on; on a peer link " +
                                "only the larger screen speaks it."
                        },
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
                            "body cameras, Axon TASERs, and Bluetooth item trackers - AirTag, Tile, " +
                            "SmartTag and Chipolo - which follow a person or a car rather than scan it.",
                        color = Sky.textDim,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The match is made from the name a device broadcasts, plus the Bluetooth " +
                            "manufacturer id Flock's hardware uses (XUNTONG, 0x09C8). A camera that " +
                            "keeps its name hidden, or any phone whose address rotates, cannot be " +
                            "told apart from an anonymous MAC - so a hit is a lead, not proof. " +
                            "A marker reading 'name + manufacturer id' is the one to trust. Trackers " +
                            "are the opposite case: the name is the only evidence, because the good " +
                            "ones randomise their address and encrypt what they advertise.",
                        color = Sky.textDim,
                        fontSize = 10.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    SwatchRow(color = Sky.watchFlock, label = "FLOCK", detail = "ALPR camera")
                    SwatchRow(color = Sky.watchAxon, label = "AXON", detail = "body camera")
                    SwatchRow(color = Sky.watchTaser, label = "TASER", detail = "Axon CED")
                    SwatchRow(color = Sky.watchTracker, label = "TRACKER", detail = "AirTag, Tile, SmartTag")
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
        }
        PaneLabel("DEVICES")
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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
                    MeshConfig(c, settings, wada)
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
                    Spacer(Modifier.height(10.dp))
                    // Sub-GHz is a property of the board's hardware, not of the app: a Marauder
                    // without a CC1101 fitted answers nothing, and that is why the switch only
                    // decides whether the command is sent at all.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Sub-GHz (CC1101)", color = Sky.text, fontSize = 13.sp)
                            Text(
                                "Sweep on a GhostESP or Marauder with the radio fitted",
                                color = Sky.textDim,
                                fontSize = 10.sp,
                            )
                        }
                        Switch(
                            checked = settings.subGhzEnabled,
                            onCheckedChange = { on ->
                                c.settingsStore.update { it.copy(subGhzEnabled = on) }
                                c.esp32Usb.subGhzCommand = if (on) {
                                    settings.subGhzCommand.trim().ifBlank { null }
                                } else {
                                    null
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    // The rate lives here as well as on the map's board card: it is a setting of
                    // the cable, and a board flashed at another rate answers a 115200 probe with
                    // noise, which reads exactly like a board that will not scan at all.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SERIAL SPEED", style = SkywardThemeLabel(), color = Sky.textDim)
                        Spacer(Modifier.weight(1f))
                        Text("${settings.esp32Baud}", color = Sky.text, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        UsbBoards.BAUD_RATES.forEach { rate ->
                            UnitChip("$rate", settings.esp32Baud == rate) {
                                // Reopening the port is what applies it: the bridge rate is set
                                // once, when the console is opened.
                                c.settingsStore.update { it.copy(esp32Baud = rate) }
                                c.esp32Usb.setBaud(rate)
                                c.esp32Usb.stop()
                                c.esp32Usb.start()
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "115200 suits GhostESP and Marauder at their defaults. Pick the rate the " +
                            "firmware was flashed at; the port reopens at the new speed straight away.",
                        color = Sky.textDim,
                        fontSize = 10.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    // The cable is the only way to a board that is not already running something
                    // the app understands: one console, one session at a time, poller waiting.
                    var serialOpen by remember { mutableStateOf(false) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("SERIAL", serialOpen, Sky.esp32) { serialOpen = true }
                        Chip("RESCAN", false, Sky.esp32) { c.esp32Usb.rescan() }
                    }
                    Text(
                        "Console and the board's GPS pin and baud, over USB.",
                        color = Sky.textDim,
                        fontSize = 10.sp,
                    )
                    if (serialOpen) EspSerialDialog(c, settings) { serialOpen = false }
                    Spacer(Modifier.height(8.dp))
                    Esp32Config(c, settings)
                }
            }
            item {
                SectionCard(title = "SDR", trailing = if (settings.sdrEnabled) "on" else "off") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("RTL-SDR dongle", color = Sky.text, fontSize = 13.sp)
                            Text(
                                "Spectrum on the map, recording and saved pins",
                                color = Sky.textDim,
                                fontSize = 10.sp,
                            )
                        }
                        Switch(
                            checked = settings.sdrEnabled,
                            onCheckedChange = { on ->
                                c.settingsStore.update { it.copy(sdrEnabled = on) }
                                if (on) c.sdr.start() else c.sdr.stop()
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Plugged into the USB port, an RTL-SDR (0bda:2832 or 0bda:2838) opens a " +
                            "spectrum drop-down at the top of the map. Recording saves the trace it " +
                            "drew and pins it where the fix was, so a capture can be read back later " +
                            "without the dongle. Off, the dongle is left alone and no panel is drawn.",
                        color = Sky.textDim,
                        fontSize = 10.sp,
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
                            Text("Share scans, intel and drives over BLE", color = Sky.text, fontSize = 13.sp)
                            Text(
                                "Two of your own devices, or a friend's, peer to peer: each keeps " +
                                    "wardriving on its own and both end up with the other's findings.",
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
                    Spacer(Modifier.height(10.dp))
                    Text("SHARING WITH", style = SkywardThemeLabel(), color = Sky.textDim)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ShareChip("MY OWN DEVICES", PeerProtocol.SHARE_OWN, settings) { c.settingsStore.update(it) }
                        ShareChip("A FRIEND", PeerProtocol.SHARE_FRIEND, settings) { c.settingsStore.update(it) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (settings.peerShareMode == PeerProtocol.SHARE_FRIEND) {
                            "Intel only, both ways: radios, the fix, board intel and the entity table. " +
                                "The drive library, the shared drive recording and forwarded Watcher " +
                                "commands stay on your own devices."
                        } else {
                            "A second device of yours: everything above, plus drives, the same drive " +
                                "recorded on both, and Watcher commands obeyed by both."
                        },
                        color = Sky.textDim,
                        fontSize = 10.sp,
                    )

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
                        if (peer.peerBoardWifi > 0 || peer.peerBoardBle > 0) {
                            Text(
                                "its board: wifi ${peer.peerBoardWifi} · ble ${peer.peerBoardBle}",
                                color = Sky.esp32,
                                fontSize = 10.sp,
                            )
                        }
                        Text(
                            if (peer.peerRecording) "both phones are recording" else
                                "neither phone is recording",
                            color = Sky.textDim,
                            fontSize = 10.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "A drive is shared: start one here and the other phone starts its own " +
                                "for the same stretch of road. Each phone shares what it has caught " +
                                "- satellites, aircraft, drones, mesh nodes and watch hits - so both " +
                                "maps carry both skies, and a Watcher command said to either phone " +
                                "is obeyed by both.",
                            color = Sky.textDim,
                            fontSize = 10.sp,
                        )
                        Text(
                            if (peer.connected) {
                                "Sharing intel with " + peer.peerName.ifBlank { "the other phone" } +
                                    ": " + peer.peerEntityTotal + " entities from it"
                            } else {
                                "Nothing shared yet: link the phones to exchange intel"
                            },
                            color = Sky.cyan,
                            fontSize = 10.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Bluetooth must be on for both phones, and the link is peer to peer - nothing leaves the two devices.",
                            color = Sky.textDim,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The bar over a Setup pane. Both halves are "settings", so each says what it holds.
 */
@Composable
private fun PaneLabel(text: String) {
    Text(
        text,
        style = SkywardThemeLabel(),
        color = Sky.textDim,
        fontSize = 9.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(Sky.surface)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
private fun MeshConfig(c: Container, settings: Settings, transfer: WadaTransfer) {
    var broker by remember(settings.meshBroker) { mutableStateOf(settings.meshBroker) }
    var topic by remember(settings.meshTopic) { mutableStateOf(settings.meshTopic) }
    var user by remember(settings.meshUser) { mutableStateOf(settings.meshUser) }
    var pass by remember(settings.meshPass) { mutableStateOf(settings.meshPass) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // WADAMESH "Transfer": the device shows an address and a six-digit session code, and the
        // browser page it asks you to open is only a shell around a WebSocket on /files. The app
        // speaks that session itself, so the code is typed here rather than in a browser. It is
        // minted per session, so it is never written to settings.
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val tfPhase by transfer.phase.collectAsState()
        val tfStatus by transfer.status.collectAsState()
        val tfFiles by transfer.files.collectAsState()
        var wadaHost by remember(settings.wadaHost) { mutableStateOf(settings.wadaHost) }
        var wadaPin by remember { mutableStateOf("") }

        Text("WADAMESH DIRECT TRANSFER", color = Sky.meshCore, fontSize = 10.sp)
        Text(
            "Open Transfer: type the http:// address the device shows and its six-digit code.",
            color = Sky.textDim,
            fontSize = 10.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = wadaHost,
                onValueChange = { wadaHost = it.trim().take(64) },
                label = { Text("address", fontSize = 11.sp) },
                placeholder = { Text("192.168.1.42", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1.4f),
            )
            OutlinedTextField(
                value = wadaPin,
                onValueChange = { wadaPin = it.filter { ch -> ch.isDigit() }.take(6) },
                label = { Text("session pin", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallAction(
                if (tfPhase == WadaTransfer.Phase.CONNECTED) "DISCONNECT" else "CONNECT",
                Sky.meshCore,
            ) {
                c.settingsStore.update { it.copy(wadaHost = wadaHost) }
                if (tfPhase == WadaTransfer.Phase.CONNECTED) {
                    transfer.close()
                } else {
                    scope.launch { transfer.connect(wadaHost, wadaPin) }
                }
            }
            if (tfPhase == WadaTransfer.Phase.CONNECTED) {
                SmallAction("REFRESH", Sky.textDim) { scope.launch { transfer.refresh() } }
            }
        }
        Text(
            tfStatus,
            color = if (tfPhase == WadaTransfer.Phase.ERROR) Sky.danger else Sky.textDim,
            fontSize = 9.sp,
        )
        tfFiles.take(6).forEach { entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    entry.path,
                    color = Sky.text,
                    fontSize = 10.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                SmallAction("GET", Sky.meshCore) {
                    scope.launch {
                        val got = transfer.download(entry.path) ?: return@launch
                        shareBytes(context, got.name, mimeForName(got.name), got.bytes)
                    }
                }
            }
        }
        if (tfFiles.size > 6) {
            Text("+${tfFiles.size - 6} more files on the device", color = Sky.textDim, fontSize = 9.sp)
        }

        Spacer(Modifier.height(8.dp))
        // The observer bridge is a different thing: hex adverts relayed by other people's
        // bridges, which is where the repeaters on the map and globe come from.
        var coreBroker by remember(settings.meshCoreBroker) { mutableStateOf(settings.meshCoreBroker) }
        var coreTopic by remember(settings.meshCoreTopic) { mutableStateOf(settings.meshCoreTopic) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("OBSERVER BRIDGE (MQTT)", color = Sky.text, fontSize = 11.sp)
                Text(
                    "Repeater and room-server adverts from an observer",
                    color = Sky.textDim,
                    fontSize = 10.sp,
                )
            }
            Switch(
                checked = settings.meshCoreEnabled,
                onCheckedChange = { on ->
                    c.settingsStore.update { it.copy(meshCoreEnabled = on) }
                    c.mesh.stop()
                    c.mesh.start()
                },
            )
        }
        OutlinedTextField(
            value = coreBroker,
            onValueChange = { coreBroker = it },
            label = { Text("MeshCore broker", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = coreTopic,
            onValueChange = { coreTopic = it },
            label = { Text("MeshCore topic", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SmallAction("APPLY MESHCORE", Sky.meshCore) {
            c.settingsStore.update {
                it.copy(meshCoreBroker = coreBroker, meshCoreTopic = coreTopic)
            }
            c.mesh.stop()
            c.mesh.start()
        }
        Text(
            "Observer layout: meshcore/{IATA}/{device}/packets. Repeaters without a shared " +
                "location are listed but not placed.",
            color = Sky.textDim,
            fontSize = 9.sp,
        )
        Spacer(Modifier.height(8.dp))
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
private fun ShareChip(label: String, value: String, settings: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    ModeChip(label, settings.peerShareMode == value) { onChange { it.copy(peerShareMode = value) } }
}

/** One choice out of a short set: the peer audience, or what the Watcher reads out. */
@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
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
private fun GroupChip(
    label: String,
    settings: Settings,
    /** What the chip says, when the CelesTrak group name is not the word wanted on screen. */
    name: String = label,
    onChange: ((Settings) -> Settings) -> Unit,
) {
    val selected = settings.satGroups.contains(label)
    FilterChip(
        selected = selected,
        onClick = {
            onChange { s ->
                val next = if (selected) s.satGroups - label else s.satGroups + label
                s.copy(satGroups = if (next.isEmpty()) listOf(label) else next)
            }
        },
        label = { Text(name, fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
        shape = RoundedCornerShape(9.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Sky.surfaceHigh,
            labelColor = Sky.textDim,
            selectedContainerColor = Sky.satellite.copy(alpha = 0.22f),
            selectedLabelColor = Sky.satellite,
        ),
    )
}

/** One preset for the ring colour a logged entity is drawn in, with its swatch on the chip. */
@Composable
private fun EntityColorChip(
    key: String,
    swatch: androidx.compose.ui.graphics.Color,
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
) {
    FilterChip(
        selected = settings.satEntityColor == key,
        onClick = { onChange { it.copy(satEntityColor = key) } },
        label = { Text(key.uppercase(), fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
        leadingIcon = {
            Box(
                Modifier
                    .width(8.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(swatch),
            )
        },
        shape = RoundedCornerShape(9.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Sky.surfaceHigh,
            labelColor = Sky.textDim,
            selectedContainerColor = swatch.copy(alpha = 0.22f),
            selectedLabelColor = swatch,
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
