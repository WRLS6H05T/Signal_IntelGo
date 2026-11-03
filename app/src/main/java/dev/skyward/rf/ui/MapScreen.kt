package dev.skyward.rf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyward.rf.Container
import dev.skyward.rf.core.AircraftView
import dev.skyward.rf.core.CellView
import dev.skyward.rf.core.CrashLog
import dev.skyward.rf.core.DroneView
import dev.skyward.rf.core.Esp32Node
import dev.skyward.rf.core.LatLon
import dev.skyward.rf.peer.PeerProtocol
import dev.skyward.rf.scan.UsbBoards
import dev.skyward.rf.sky.SatTracker
import dev.skyward.rf.core.Layer
import dev.skyward.rf.core.MeshNode
import dev.skyward.rf.core.SatView
import dev.skyward.rf.core.SignalScale
import dev.skyward.rf.sky.AirspaceEngine
import dev.skyward.rf.ui.theme.Sky
import dev.skyward.rf.wardrive.WardrivePoint
import dev.skyward.rf.wardrive.WardriveSession
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One plottable entity. */
data class MapMarker(
    val key: String,
    val layer: Layer,
    val lat: Double,
    val lon: Double,
    val title: String,
    val subtitle: String,
    val score: Int,
    val logged: Boolean,
    val headingDeg: Float? = null,
    /** Metres above sea level: drives the 3-D lift of the icon off the globe. */
    val altM: Double? = null,
    val lines: List<Pair<String, String>> = emptyList(),
    /** Decided once in buildMarkers: the draw loop must not do string work per frame. */
    val station: Boolean = false,
)

/**
 * A logged signal, ready to draw. The colour is parsed once, when the table changes:
 * thousands of colour strings must not be decoded inside the draw loop.
 */
data class SignalDot(val lat: Double, val lon: Double, val color: Color)

/** One piece of attached hardware, listed in the top-right status panel. */
private data class HwEntry(
    val id: String,
    val title: String,
    val detail: String,
    val lines: List<Pair<String, String>>,
)

/**
 * Hardware you actually attached: ESP32 boards on a cable, plus Meshtastic devices heard
 * directly over BLE. MQTT mesh nodes are remote reports rather than attached devices, so they
 * stay out of this list.
 */
private fun buildHardware(
    boards: List<UsbBoards.Board>,
    nodes: List<Esp32Node>,
    mesh: List<MeshNode>,
): List<HwEntry> {
    val out = ArrayList<HwEntry>(boards.size + mesh.size)
    for (b in boards) {
        val n = nodes.firstOrNull { it.id == b.key }
        // A wired board is sounded for identity, not polled for counts, so AP/BLE/GNSS
        // read as unknown instead of as a confident zero.
        // Counts and the fix now come from the board's own console poll, so they are shown
        // as soon as it answers one and stay unknown until then.
        val r = b.reading
        val answered = !r.isEmpty || (n != null && n.source != "usb")
        val telemetry = answered
        fun seen(value: Int): String = if (!telemetry) "\u2014" else value.toString()
        val gnss = when {
            !telemetry -> "\u2014"
            (n?.sats ?: 0) > 0 -> "${n?.sats ?: 0} sats" + (n?.hdop?.let { String.format(" \u00b7 hdop %.1f", it) } ?: "")
            else -> "no fix"
        }
        out.add(
            HwEntry(
                id = b.key,
                title = b.label,
                detail = "USB \u00b7 ${b.transport.label}",
                lines = listOf(
                    "Firmware" to b.version.ifBlank { "\u2014" },
                    "Chip" to b.chip.ifBlank { String.format("%04x:%04x", b.vendorId, b.productId) },
                    "APs seen" to seen(n?.wifiCount ?: 0),
                    "BLE seen" to seen(n?.bleCount ?: 0),
                    // Only one wired board holds the cable at a time; the rest are listed but
                    // waiting, which is what stops two consoles fighting over one port.
                    "Link" to if (b.active) "active" else "waiting",
                    "GNSS" to gnss,
                    // Whether the board has a GPS module at all, which decides whether its
                    // position can be believed or borrowed as the observer.
                    "GPS" to when (r.gps) {
                        true -> "yes"
                        false -> "no module"
                        null -> if (r.lat != null) "yes" else "—"
                    },
                    "Position" to (if (r.lat != null && r.lon != null)
                        String.format("%.5f, %.5f", r.lat, r.lon) else "unknown"),
                    "Battery" to (n?.batteryPct?.takeIf { it >= 0 }?.let { "$it%" } ?: "\u2014"),
                    "Console" to b.raw.ifBlank { "\u2014" },
                ),
            ),
        )
    }
    for (m in mesh) {
        if (!m.source.equals("ble", ignoreCase = true)) continue
        out.add(
            HwEntry(
                id = "mesh:${m.id}",
                title = m.name.ifBlank { m.id },
                detail = "Meshtastic \u00b7 BLE",
                lines = listOf(
                    "SNR" to String.format("%.1f dB", m.snr),
                    "Hops" to m.hops.toString(),
                    "Battery" to (if (m.batteryPct >= 0) "${m.batteryPct}%" else "\u2014"),
                    "Altitude" to "${m.altitudeM} m",
                    "Node id" to m.id,
                ),
            ),
        )
    }
    return out
}

/**
 * Desaturates the basemap: the live layers are the only colour on the map, so a coloured icon
 * is never competing with a coloured street or a landmass behind it.
 */
private val GreyscaleFilter: ColorFilter =
    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/** The station gets its own colour so it stands out even in a crowded sky. */
private val IssOrange = Color(0xFFFF8C1A)

/** NORAD 25544 is the station; the name never decides, because SWISSCUBE also contains "ISS". */
private fun isIss(m: MapMarker): Boolean = m.station

private fun markerColor(m: MapMarker): Color = when {
    isIss(m) -> IssOrange
    m.logged -> Sky.logged
    m.layer == Layer.SATELLITE -> Sky.satellite
    m.layer == Layer.AIRCRAFT -> Sky.aircraft
    m.layer == Layer.DRONE -> Sky.drone
    m.layer == Layer.ESP32 -> Sky.esp32
    else -> Sky.mesh
}

fun buildMarkers(
    sky: AirspaceEngine.SkyState,
    showSats: Boolean,
    showAir: Boolean,
    showDrones: Boolean,
    showMesh: Boolean,
    showCells: Boolean,
    showEsp32: Boolean,
    minScore: Int,
    /** A worldwide feed is tens of thousands of points; the map plots the nearest slice. */
    maxAircraft: Int = 900,
): List<MapMarker> {
    val out = ArrayList<MapMarker>(sky.sats.size + sky.aircraft.size + sky.drones.size + sky.mesh.size)
    if (showSats) sky.sats.forEach { s: SatView ->
        if (s.score < minScore) return@forEach
        out.add(
            MapMarker(
                key = "SAT:${s.noradId}",
                station = s.noradId == SatTracker.ISS_NORAD,
                layer = Layer.SATELLITE,
                lat = s.lat,
                lon = s.lon,
                title = s.name,
                subtitle = if (s.noradId == SatTracker.ISS_NORAD) "International Space Station" else "NORAD ${s.noradId}",
                score = s.score,
                logged = s.logged,
                altM = s.altKm * 1000.0,
                lines = listOfNotNull(
                    if (s.noradId == SatTracker.ISS_NORAD) "Catalog" to SatTracker.ISS_DESIGNATOR else null,
                    "Elevation" to String.format("%.1f°", s.elevation),
                    "Azimuth" to String.format("%.1f°", s.azimuth),
                    "Range" to String.format("%.0f km", s.rangeKm),
                    "Altitude" to String.format("%.0f km", s.altKm),
                    "Predicted" to "${s.score}% link",
                ),
            ),
        )
    }
    // The engine keeps only what is inside 30 miles, so this is the whole picture rather
    // than a slice of it: nothing further out is plotted, counted or logged.
    if (showAir) sky.aircraft.take(maxAircraft).forEach { a: AircraftView ->
        if (a.score < minScore) return@forEach
        out.add(
            MapMarker(
                key = "AIR:${a.hex}",
                layer = Layer.AIRCRAFT,
                lat = a.lat,
                lon = a.lon,
                title = a.callSign.ifBlank { a.reg.ifBlank { a.hex.uppercase() } },
                subtitle = listOf(a.type, a.reg).filter { it.isNotBlank() }.joinToString(" · "),
                score = a.score,
                logged = a.logged,
                headingDeg = a.trackDeg.toFloat(),
                altM = a.altFt?.let { it * 0.3048 },
                lines = listOf(
                    "Altitude" to (a.altFt?.let { "$it ft" } ?: "—"),
                    "Ground speed" to String.format("%.0f kt", a.groundSpeedKt),
                    "Vertical" to String.format("%+d fpm", a.verticalRateFpm),
                    "Distance" to String.format("%.1f km", a.distanceKm),
                    "Squawk" to a.squawk.ifBlank { "—" },
                    "ICAO" to a.hex.uppercase(),
                ),
            ),
        )
    }
    if (showDrones) sky.drones.forEach { d: DroneView ->
        val lat = d.lat
        val lon = d.lon
        if (lat == null || lon == null) return@forEach
        if (d.score < minScore) return@forEach
        out.add(
            MapMarker(
                key = "UAV:${d.mac}",
                layer = Layer.DRONE,
                lat = lat,
                lon = lon,
                title = d.uasId,
                subtitle = "Remote ID · ${d.mac}",
                score = d.score,
                logged = d.logged,
                headingDeg = d.headingDeg?.toFloat(),
                altM = d.altM,
                lines = listOf(
                    "BLE RSSI" to "${d.rssi} dBm",
                    "Altitude" to (d.altM?.let { String.format("%.0f m", it) } ?: "—"),
                    "Height AGL" to (d.heightM?.let { String.format("%.0f m", it) } ?: "—"),
                    "Speed" to (d.speedMs?.let { String.format("%.1f m/s", it) } ?: "—"),
                    "Distance" to (d.distanceKm?.let { String.format("%.2f km", it) } ?: "—"),
                    "Operator" to d.operatorId.ifBlank { "—" },
                ),
            ),
        )
    }
    if (showMesh) sky.mesh.forEach { n: MeshNode ->
        val lat = n.lat
        val lon = n.lon
        if (lat == null || lon == null) return@forEach
        if (n.score < minScore) return@forEach
        out.add(
            MapMarker(
                key = "MESH:${n.id}",
                layer = Layer.MESH,
                lat = lat,
                lon = lon,
                title = n.name.ifBlank { n.id },
                subtitle = "Meshtastic · ${n.source.uppercase()}",
                score = n.score,
                logged = n.logged,
                altM = n.altitudeM.toDouble(),
                lines = listOf(
                    "SNR" to String.format("%.1f dB", n.snr),
                    "Hops" to n.hops.toString(),
                    "Battery" to if (n.batteryPct >= 0) "${n.batteryPct}%" else "—",
                    "Altitude" to "${n.altitudeM} m",
                    "Distance" to (n.distanceKm?.let { String.format("%.1f km", it) } ?: "—"),
                    "Node id" to n.id,
                ),
            ),
        )
    }
    if (showEsp32) sky.esp32.forEach { b ->
        val lat = b.lat
        val lon = b.lon
        if (lat == null || lon == null) return@forEach
        if (b.score < minScore) return@forEach
        out.add(
            MapMarker(
                key = "ESP:${b.id}",
                layer = Layer.ESP32,
                lat = lat,
                lon = lon,
                title = b.name,
                subtitle = "ESP32 \u00b7 ${b.source.uppercase()}",
                score = b.score,
                logged = b.logged,
                altM = b.altM?.toDouble(),
                lines = listOf(
                    "GPS" to if (b.sats > 0) "${b.sats} sats" else "no fix",
                    "HDOP" to (b.hdop?.let { String.format("%.1f", it) } ?: "\u2014"),
                    "Altitude" to (b.altM?.let { "$it m" } ?: "\u2014"),
                    "Battery" to (if (b.batteryPct >= 0) "${b.batteryPct}%" else "\u2014"),
                    "Wi-Fi seen" to b.wifiCount.toString(),
                    "BLE seen" to b.bleCount.toString(),
                    "Moving" to if (b.moving) "yes" else "no",
                    "Board" to b.board.ifBlank { "\u2014" },
                    "Firmware" to b.firmware.ifBlank { "\u2014" },
                    "RSSI" to (b.rssi?.let { "$it dBm" } ?: "\u2014"),
                    "Distance" to (b.distanceKm?.let { String.format("%.2f km", it) } ?: "\u2014"),
                ),
            ),
        )
    }
    if (showCells) sky.cells.forEach { t: CellView ->
        if (t.score < minScore) return@forEach
        out.add(
            MapMarker(
                key = "CELL:${t.key}",
                layer = Layer.CELL,
                lat = t.lat,
                lon = t.lon,
                title = t.operator.ifBlank { t.radio },
                subtitle = "${t.radio} · ${t.dbm} dBm",
                score = t.score,
                logged = t.logged,
                altM = 0.0,
                lines = listOf(
                    "Radio" to t.radio,
                    "Signal" to "${t.dbm} dBm",
                    "MCC / MNC" to "${t.mcc} / ${t.mnc}",
                    "Area / cell" to "${t.area} / ${t.cellId}",
                    "Registered" to if (t.registered) "yes" else "no",
                    "Operator" to t.operator.ifBlank { "—" },
                ),
            ),
        )
    }
    return out
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MapScreen(c: Container, modifier: Modifier = Modifier, onOpenDrive: () -> Unit) {
    val sky by c.airspace.state.collectAsState()
    val wd by c.wardrive.state.collectAsState()
    val overlay by c.wardrive.overlay.collectAsState()
    val observer by c.observer.collectAsState()
    val settings by c.settingsStore.state.collectAsState()
    val rf by c.airspace.rfSnapshot.collectAsState()
    val wifiOn by c.rf.wifiEnabled.collectAsState()
    val bleOn by c.rf.bleEnabled.collectAsState()
    val context = LocalContext.current
    val worldRings = remember { WorldOutline.load(context) }

    // Open on the street map, not in the globe transition: 4.0 used to start the app inside
    // the morph zone, which looked like a half-drawn globe with no basemap.
    val camera = remember { MapCameraState(startZoom = 11.0) }
    val tileStore = remember { TileStore(context, c.http) }

    var showSats by remember { mutableStateOf(true) }
    var showAir by remember { mutableStateOf(true) }
    var showDrones by remember { mutableStateOf(true) }
    var showMesh by remember { mutableStateOf(true) }
    var showCells by remember { mutableStateOf(true) }
    // Boards are status, not a layer: they no longer have a switch in the INTEL box.
    val showEsp32 = true
    var showPath by remember { mutableStateOf(true) }
    var showSignals by remember { mutableStateOf(true) }
    var legendOpen by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<MapMarker?>(null) }

    // Follow the phone on the first fix and whenever the user re-enables follow.
    LaunchedEffect(observer) {
        val obs = observer ?: return@LaunchedEffect
        if (camera.follow) {
            // Keep the observer centred without fighting a deliberate zoom-out.
            camera.centerOn(obs.lat, obs.lon, resumeFollow = true)
        } else if (!camera.centredOnce) {
            camera.centredOnce = true
            camera.centerOn(obs.lat, obs.lon, 13.0, resumeFollow = false)
        }
    }

    val markers = remember(
        sky, showSats, showAir, showDrones, showMesh, showCells, showEsp32, settings.aircraftEnabled,
        settings.dropWeakDots,
    ) {
        buildMarkers(
            sky = sky,
            showSats = showSats,
            showAir = showAir && settings.aircraftEnabled,
            showDrones = showDrones,
            showMesh = showMesh,
            showCells = showCells,
            showEsp32 = showEsp32,
            minScore = if (settings.dropWeakDots) 20 else 0,
        )
    }

    val loggedSignals by c.signalLog.entries.collectAsState()
    val signalDots = remember(loggedSignals) {
        loggedSignals.take(3000).mapNotNull { s ->
            val argb = runCatching { android.graphics.Color.parseColor(s.color) }.getOrNull()
                ?: return@mapNotNull null
            SignalDot(s.lat, s.lon, Color(argb))
        }
    }

    val session: WardriveSession? = if (wd.active) {
        WardriveSession(
            id = wd.sessionId ?: "live",
            name = wd.name,
            startedAt = wd.startedAt,
            points = wd.points,
        )
    } else {
        overlay
    }
    val path: List<WardrivePoint> = if (showPath) session?.points.orEmpty() else emptyList()

    Box(modifier.fillMaxSize().background(Sky.bg)) {
        TacticalMap(
            camera = camera,
            tileSource = settings.tileSource,
            useDeviceMap = settings.useDeviceMap,
            tileStore = tileStore,
            markers = markers,
            world = worldRings,
            path = path,
            signals = if (showSignals) signalDots else emptyList(),
            self = observer,
            accuracyM = null,
            selectedKey = selected?.key,
            onSelect = { selected = it },
            modifier = Modifier.fillMaxSize(),
        )

        val peer by c.peer.state.collectAsState()

        // ---- BLE peer: top left, the same width as the INTEL box ----
        if (peer.running) {
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .width(216.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Sky.surface.copy(alpha = 0.94f))
                    .border(1.dp, Sky.cyan.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("PEER", style = SkywardThemeLabel(), color = Sky.cyan)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (peer.connected) "linked" else "searching",
                        color = Sky.textDim,
                        fontSize = 9.sp,
                    )
                }
                // The card describes the other phone, so it names the other phone's screen:
                // this phone's role is already obvious from what is on it.
                Text(
                    peer.peerName.ifBlank { "BLE peer" } + " · " + PeerProtocol.roleLabel(peer.peerRole),
                    color = Sky.text,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
                Text(
                    "wifi ${peer.peerWifiTotal} · ble ${peer.peerBleTotal} · up ${peer.sent} down ${peer.received}",
                    color = Sky.textDim,
                    fontSize = 9.sp,
                    maxLines = 1,
                )
            }
        }

        // ---- attached hardware: top right, only while something is plugged in ----
        val usbBoards by c.esp32Usb.state.collectAsState()
        val espNodes by c.esp32.state.collectAsState()
        val hardware = remember(usbBoards, espNodes, sky.mesh) {
            buildHardware(usbBoards, espNodes, sky.mesh)
        }
        if (hardware.isNotEmpty()) {
            var hwOpen by remember { mutableStateOf(false) }
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .width(216.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Sky.surface.copy(alpha = 0.94f))
                    .border(1.dp, Sky.esp32.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                    .clickable { hwOpen = !hwOpen }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("HARDWARE", style = SkywardThemeLabel(), color = Sky.esp32)
                    Spacer(Modifier.weight(1f))
                    Text("${hardware.size}", color = Sky.textDim, fontSize = 9.sp)
                    Spacer(Modifier.width(5.dp))
                    Text(if (hwOpen) "\u25BC" else "\u25B2", color = Sky.textDim, fontSize = 9.sp)
                }
                hardware.take(3).forEach { h ->
                    Spacer(Modifier.height(4.dp))
                    Text(h.title, color = Sky.text, fontSize = 11.sp, maxLines = 1)
                    Text(h.detail, color = Sky.textDim, fontSize = 9.sp, maxLines = 1)
                    if (hwOpen) {
                        h.lines.forEach { (k, v) ->
                            Text("$k  $v", color = Sky.textDim, fontSize = 9.sp, maxLines = 1)
                        }
                    }
                }
                if (hardware.size > 3) {
                    Spacer(Modifier.height(3.dp))
                    Text("+${hardware.size - 3} more", color = Sky.textDim, fontSize = 9.sp)
                }
            }
        }

        // ---- one collapsible box: counts, layer toggles and the legend ----
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 10.dp, bottom = 10.dp)
                .width(216.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Sky.surface.copy(alpha = 0.93f))
                .border(1.dp, Sky.outline, RoundedCornerShape(12.dp))
                .padding(10.dp),
        ) {
            // Header always shows the logged count; everything else lives behind the arrow.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { legendOpen = !legendOpen },
            ) {
                Text("INTEL", style = SkywardThemeLabel(), color = Sky.textDim)
                Spacer(Modifier.width(6.dp))
                Text(
                    "${sky.loggedSats + sky.loggedAircraft + sky.loggedDrones + sky.loggedMesh + sky.loggedCells + sky.loggedEsp32}",
                    color = Sky.logged,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (wd.active) {
                    Spacer(Modifier.width(6.dp))
                    Text("REC", color = Sky.warn, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                CrashPill()
                Spacer(Modifier.width(6.dp))
                Text(if (legendOpen) "▼" else "▲", color = Sky.textDim, fontSize = 10.sp)
            }
            if (legendOpen) {
                Spacer(Modifier.height(8.dp))
                // A plain table of counts. Tapping a row still switches that layer or radio;
                // the number stays visible when a layer is off, so "hidden" is not "zero".
                IntelRow("SAT", sky.sats.size, showSats) { showSats = !showSats }
                IntelRow("AIR", sky.aircraft.size, showAir) { showAir = !showAir }
                IntelRow("UAV", sky.drones.size, showDrones) { showDrones = !showDrones }
                IntelRow("MESH", sky.mesh.size, showMesh) { showMesh = !showMesh }
                IntelRow("CELL", sky.cells.size, showCells) { showCells = !showCells }
                IntelRow("WIFI", rf.wifi.size, wifiOn) { c.rf.setWifiEnabled(!wifiOn) }
                IntelRow("BLE", rf.ble.size, bleOn) { c.rf.setBleEnabled(!bleOn) }
                IntelRow("PATH", path.size, showPath) { showPath = !showPath }
                // Individual networks, drawn at the fix where each was heard.
                IntelRow("SIG", signalDots.size, showSignals) { showSignals = !showSignals }
                // What the other phone is hearing, so the table shows both devices' sweeps.
                IntelRow("PEER", peer.peerWifiTotal + peer.peerBleTotal, peer.connected) { }
                if (!wd.active) {
                    Spacer(Modifier.height(8.dp))
                    // The primary action lives in the box now, not in a floating button.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(Sky.cyan)
                            .clickable { onOpenDrive() }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "START DRIVE",
                            color = Sky.bg,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Sky.surfaceHigh)
                            .clickable { c.airspace.captureNow() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("⊙", color = Sky.logged, fontSize = 14.sp)
                    }
                    if (wd.active) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            String.format("%.2f km logged", wd.km),
                            color = Sky.warn,
                            fontSize = 10.sp,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                SwatchRow(color = Sky.logged, label = "Logged entity", detail = "sat · air · uav")
                Spacer(Modifier.height(6.dp))
                SwatchRow(color = Sky.warn, label = "Cell tower", detail = "observed")
            }
            Spacer(Modifier.height(6.dp))
            // Only credit a tile source that is actually being used.
            if (!settings.useDeviceMap) {
                Text(
                    TileSources.attribution(settings.tileSource),
                    style = SkywardThemeLabel(),
                    color = Sky.textDim,
                )
            }
        }

        // ---- actions ----
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The globe has no pinch-in, so this is the explicit way back to the flat map.
            // Same threshold the map uses to switch to the sphere.
            if (camera.zoom < 4.35) {
                IconButton(
                    onClick = { camera.centerOn(camera.lat, camera.lon, 9.0, resumeFollow = false) },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Sky.surface.copy(alpha = 0.95f))
                        .border(1.dp, Sky.outline, RoundedCornerShape(12.dp)),
                ) {
                    Text("MAP", color = Sky.cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            IconButton(
                onClick = {
                    val obs = observer
                    if (obs != null) camera.centerOn(obs.lat, obs.lon, 14.0) else camera.follow = true
                },
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Sky.surface.copy(alpha = 0.95f))
                    .border(1.dp, Sky.outline, RoundedCornerShape(12.dp)),
            ) {
                Text("◎", color = Sky.cyan, fontSize = 18.sp)
            }
        }

        selected?.let { m ->
            MarkerCard(
                marker = m,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp, start = 12.dp, end = 12.dp),
                onClose = { selected = null },
            )
        }
    }
}

@Composable
private fun MarkerCard(marker: MapMarker, modifier: Modifier = Modifier, onClose: () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Sky.surface)
            .border(1.dp, markerColor(marker).copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(markerColor(marker), 9)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(marker.title, color = Sky.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(marker.subtitle, color = Sky.textDim, fontSize = 11.sp)
            }
            if (marker.logged) {
                Text("LOGGED", style = SkywardThemeLabel(), color = Sky.logged)
                Spacer(Modifier.width(8.dp))
            }
            Text("✕", color = Sky.textDim, modifier = Modifier.clickable { onClose() })
        }
        Spacer(Modifier.height(8.dp))
        for ((k, v) in marker.lines) KeyValueRow(k, v)
    }
}

@Composable
private fun TacticalMap(
    camera: MapCameraState,
    tileSource: String,
    useDeviceMap: Boolean,
    tileStore: TileStore,
    markers: List<MapMarker>,
    world: Array<FloatArray>,
    path: List<WardrivePoint>,
    signals: List<SignalDot>,
    self: LatLon?,
    accuracyM: Float?,
    selectedKey: String?,
    onSelect: (MapMarker?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val appContext = LocalContext.current
    // The tap detector must not be keyed on the marker list: it is rebuilt on every tick, and
    // restarting the pointer handler mid-gesture is what breaks a pinch half way out.
    val latestMarkers by rememberUpdatedState(markers)
    var tileVersion by remember { mutableStateOf(0) }

    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        // A single switch, no cross-fade. Every visual fault this screen has had came from the
        // two projections coexisting - ghost balls, torn continents, markers floating between
        // two maps - so at one zoom you are on the flat map and one step further out you are
        // on the globe, each drawn complete and opaque. Hysteresis only keeps a pinch sitting
        // exactly on the boundary from flickering between the two.
        var globeMode by remember { mutableStateOf(camera.zoom < 4.2) }
        LaunchedEffect(camera.zoom) {
            globeMode = if (globeMode) camera.zoom < 4.35 else camera.zoom < 4.05
        }
        val globeT = if (globeMode) 1f else 0f
        // Pointer handlers are keyed on Unit, so they keep the first value they ever saw.
        val globeModeNow by rememberUpdatedState(globeMode)
        // Device-map mode: no tile requests at all, the phone's map app draws the streets.
        val showTiles = !globeMode && !useDeviceMap
        val visible = remember(tileSource, camera.zoom, camera.lat, camera.lon, w, h, showTiles) {
            if (showTiles) tileStore.visible(tileSource, camera.lat, camera.lon, camera.zoom, w, h) else emptyList()
        }

        LaunchedEffect(visible) {
            var loaded = false
            for (t in visible) {
                if (tileStore.load(tileSource, t.z, t.x, t.y)) loaded = true
            }
            // Pull one level out as well, so the next zoom step already has pixels to show.
            for (t in visible) {
                if (t.z > 2) tileStore.load(tileSource, t.z - 1, t.x shr 1, t.y shr 1)
            }
            if (loaded) tileVersion++
        }

        val labelPaint = remember {
            android.graphics.Paint().apply {
                isAntiAlias = true
                typeface = android.graphics.Typeface.MONOSPACE
            }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (!pan.x.isFinite() || !pan.y.isFinite()) return@detectTransformGestures
                        // On the ball a drag rotates the sphere; on the flat map it pans.
                        if (globeModeNow) {
                            val radius = globeRadius(
                                camera.zoom,
                                size.width.toFloat(),
                                size.height.toFloat(),
                            )
                            camera.rotateGlobe(pan.x, pan.y, radius)
                        } else {
                            camera.panPixels(pan.x, pan.y)
                        }
                        // Two fingers landing on the same point report NaN; that must never
                        // reach the camera, because every projection downstream inherits it.
                        if (zoom.isFinite() && zoom != 1f &&
                            centroid.x.isFinite() && centroid.y.isFinite()
                        ) {
                            // The globe is turn-only: its size is fixed, so a pinch on the
                            // ball does nothing instead of fighting the flat map's zoom.
                            // On the ball a pinch-out brings the flat map back; a
                            // pinch-in has nothing to shrink, because the globe's size is
                            // fixed. Without this the globe was a one-way view.
                            if (!globeModeNow || zoom > 1f) {
                                camera.zoomBy(zoom, centroid.x, centroid.y, w, h)
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        val gt = if (globeModeNow) 1f else 0f
                        val vp = viewProjection(camera, size.width.toFloat(), size.height.toFloat(), globeT)
                        var best: MapMarker? = null
                        var bestDist = 34f * density
                        for (m in latestMarkers) {
                            val pp = vp.elevated(m.lat, m.lon, m.altM)
                            if (gt > 0.5f && pp.front < 0.2f) continue
                            val p = Offset(pp.x, pp.y)
                            if (p.x < -40f || p.y < -40f || p.x > w + 40f || p.y > h + 40f) continue
                            val d = sqrt((p.x - tap.x) * (p.x - tap.x) + (p.y - tap.y) * (p.y - tap.y))
                            if (d < bestDist) {
                                bestDist = d
                                best = m
                            }
                        }
                        onSelect(best)
                    }
                },
        ) {
            try {
            tileVersion // read state so new tiles repaint
            val proj = Projection(camera.lat, camera.lon, camera.zoom, size.width, size.height)

            // ---- basemap: flat tiles near the ground, a lit sphere from orbit ----
            drawRect(color = Sky.bg)
            val z = camera.zoom.toInt().coerceIn(2, 18)
            val tileSize = proj.tileSize(z)
            for (t in visible) {
                        val (sx, sy) = proj.tileOrigin(t.z, t.x, t.y)
                        val px = tileSize.roundToInt()
                        if (px <= 0) continue
                        val exact = tileStore.cached(t.z, t.x, t.y)
                        val dst = IntOffset(sx.roundToInt(), sy.roundToInt())
                        val dstSize = IntSize(px + 1, px + 1)
                        if (exact != null) {
                            drawImage(
                                image = exact.asImageBitmap(),
                                srcOffset = IntOffset.Zero,
                                srcSize = IntSize(exact.width, exact.height),
                                dstOffset = dst,
                                dstSize = dstSize,
                                colorFilter = GreyscaleFilter,
                                filterQuality = FilterQuality.Low,
                            )
                        } else {
                            // Not downloaded yet for this zoom: stretch the nearest cached parent
                            // over the same ground, so zooming never leaves the map blank.
                            val parent = tileStore.ancestor(t.z, t.x, t.y) ?: continue
                            drawImage(
                                image = parent.bitmap.asImageBitmap(),
                                srcOffset = IntOffset(parent.srcX, parent.srcY),
                                srcSize = IntSize(parent.srcSize, parent.srcSize),
                                dstOffset = dst,
                                dstSize = dstSize,
                                colorFilter = GreyscaleFilter,
                                filterQuality = FilterQuality.Low,
                            )
                        }
                    }
            // Dark mode is a scrim over the keyless OSM tiles, not a separate paid tile source.
            if (tileSource == TileSources.DARK && showTiles) {
                drawRect(color = Color(0xFF050B13).copy(alpha = 0.58f))
            }
            if (globeMode) {
                drawGlobeWorld(
                    rings = world,
                    alpha = 1f,
                    centerLat = camera.lat,
                    centerLon = camera.lon,
                    center = Offset(size.width / 2f, size.height / 2f),
                    // Same radius the markers are projected with, or icons drift off the coast.
                    // From the canvas itself, so the disc and the markers cannot disagree.
                    radius = globeRadius(camera.zoom, size.width, size.height),
                )
            }

            val vp = viewProjection(camera, size.width, size.height, globeT)
            val cull = globeMode
            val labels = camera.zoom >= 9.0 && markers.size <= 60
            // Above this the sky is too dense for detail: one wedge each, no tethers.
            val plottedAircraft = markers.count { it.layer == Layer.AIRCRAFT }
            val denseAircraft = plottedAircraft > 300

            // ---- wardrive path: 5-bin red -> green, weak to strong ----
            if (path.size > 1) {
                for (i in 1 until path.size) {
                    val a = vp.surface(path[i - 1].lat, path[i - 1].lon)
                    val b = vp.surface(path[i].lat, path[i].lon)
                    if (cull && (a.front <= 0.02f || b.front <= 0.02f)) continue
                    if (a.x < -200f || a.y < -200f || a.x > size.width + 200f || a.y > size.height + 200f) continue
                    drawLine(
                        color = Color.Black.copy(alpha = 0.55f),
                        start = Offset(a.x, a.y),
                        end = Offset(b.x, b.y),
                        strokeWidth = 9f * density,
                    )
                }
                for (i in 1 until path.size) {
                    val a = vp.surface(path[i - 1].lat, path[i - 1].lon)
                    val b = vp.surface(path[i].lat, path[i].lon)
                    if (cull && (a.front <= 0.02f || b.front <= 0.02f)) continue
                    if (a.x < -200f || a.y < -200f || a.x > size.width + 200f || a.y > size.height + 200f) continue
                    drawLine(
                        color = Color(SignalScale.colorOfScore(path[i].score)),
                        start = Offset(a.x, a.y),
                        end = Offset(b.x, b.y),
                        strokeWidth = 5.5f * density,
                    )
                }
                // dots make the bin colour readable at street zoom
                if (camera.zoom >= 14.0) {
                    for (p in path) {
                        val s = vp.surface(p.lat, p.lon)
                        drawCircle(
                            color = Color(SignalScale.colorOfScore(p.score)),
                            radius = 2.4f * density,
                            center = Offset(s.x, s.y),
                        )
                    }
                }
            }

            // ---- logged signals: each network where it was actually heard ----
            // Flat map only: on the ball a dot per network would be indistinguishable from
            // the logged-entity halos, and the sphere is for the sky, not the streets.
            if (signals.isNotEmpty() && !globeMode) {
                for (s in signals) {
                    val pr = vp.surface(s.lat, s.lon)
                    if (pr.x < -20f || pr.y < -20f ||
                        pr.x > size.width + 20f || pr.y > size.height + 20f
                    ) {
                        continue
                    }
                    val p = Offset(pr.x, pr.y)
                    drawCircle(color = s.color.copy(alpha = 0.20f), radius = 3.6f * density, center = p)
                    drawCircle(color = s.color, radius = 1.7f * density, center = p)
                }
            }

            // ---- entities: lifted off the globe by their own altitude ----
            for (m in markers) {
                val pr = vp.elevated(m.lat, m.lon, m.altM)
                // whatever has rotated round the back of the sphere is not drawn
                if (cull && pr.front <= 0.02f) continue
                if (pr.x < -80f || pr.y < -80f || pr.x > size.width + 80f || pr.y > size.height + 80f) continue
                val a = if (cull) smoothstep(pr.front) else 1f
                if (a <= 0.02f) continue

                val base = markerColor(m)
                val color = base.copy(alpha = a)
                val p = Offset(pr.x, pr.y)

                // tether down to the ground point: the height cue that makes it airborne
                if (pr.lift > 0.004f && m.layer != Layer.AIRCRAFT) {
                    val g = vp.surface(m.lat, m.lon)
                    drawLine(
                        color = base.copy(alpha = 0.28f * a),
                        start = Offset(g.x, g.y),
                        end = p,
                        strokeWidth = 0.9f * density,
                    )
                    drawCircle(
                        color = base.copy(alpha = 0.4f * a),
                        radius = 1.7f * density,
                        center = Offset(g.x, g.y),
                    )
                }

                val r = (if (m.logged) 5.4f else 4.4f) * density
                if (m.logged) {
                    drawCircle(color = Sky.logged.copy(alpha = 0.18f * a), radius = r * 3.2f, center = p)
                    drawCircle(
                        color = Sky.logged.copy(alpha = a),
                        radius = r * 2.4f,
                        center = p,
                        style = Stroke(width = 1.2f * density),
                    )
                }
                // The station is drawn a size up: it is one object, and it should read as one.
                val iconR = if (isIss(m)) r * 1.9f else r
                when (m.layer) {
                    Layer.AIRCRAFT -> drawPlaneArrow(p, m.headingDeg ?: 0f, r, color, simple = denseAircraft)
                    Layer.SATELLITE -> if (isIss(m)) drawStationIcon(p, iconR, color) else drawSatelliteIcon(p, r, color)
                    Layer.CELL -> drawTowerIcon(p, r, color)
                    Layer.MESH -> drawDishIcon(p, r, color)
                    Layer.ESP32 -> drawChipIcon(p, r, color)
                    Layer.DRONE -> {
                        // A drone with a track is an arrow, but a smaller one than an airliner:
                        // the two must not read as the same weight in the sky. Without a
                        // heading there is nothing to point, so it gets the quadcopter glyph.
                        val heading = m.headingDeg
                        if (heading != null) {
                            drawPlaneArrow(p, heading, r * 0.62f, color, simple = true)
                        } else {
                            drawDroneIcon(p, r * 0.8f, color)
                        }
                    }
                }
                if (m.key == selectedKey) {
                    drawCircle(
                        color = Sky.cyan.copy(alpha = a),
                        radius = r * 3.4f,
                        center = p,
                        style = Stroke(width = 1.6f * density),
                    )
                }
                if (labels && a > 0.6f) {
                    labelPaint.color = color.toArgb()
                    labelPaint.textSize = 9.5f * density
                    drawContext.canvas.nativeCanvas.drawText(m.title.take(18), p.x + iconR * 2.2f, p.y - iconR, labelPaint)
                }
            }

            // ---- own position ----
            if (self != null) {
                val sp = vp.surface(self.lat, self.lon)
                val p = Offset(sp.x, sp.y)
                drawGpsLogo(p, 7.5f * density, Sky.cyan)
                labelPaint.color = Sky.cyan.toArgb()
                labelPaint.textSize = 9.5f * density
                drawContext.canvas.nativeCanvas.drawText(
                    "GPS",
                    p.x + 13f * density,
                    p.y + 3.5f * density,
                    labelPaint,
                )
            }
            } catch (t: Throwable) {
                // A bad projection or a driver hiccup must cost one frame, not the whole app.
                CrashLog.recordQuiet(appContext, "map-draw", t)
            }
        }
    }
}

private fun smoothstep(v: Float): Float {
    val t = v.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * Appears only after a crash: the report is written to external app storage, so it can be
 * read and shared from the phone when nothing is attached over ADB.
 */
@Composable
private fun CrashPill() {
    val context = LocalContext.current
    var text by remember { mutableStateOf(CrashLog.latest(context)) }
    val report = text ?: return
    var open by remember { mutableStateOf(false) }

    Pill("CRASH", Sky.warn, Modifier.clickable { open = true })
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Crash report", color = Sky.text, fontSize = 14.sp) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(
                    report,
                    color = Sky.textDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { shareExport(context, "Signal_IntelGo-crash.txt", "text/plain", report) },
            ) { Text("SHARE", color = Sky.cyan) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    CrashLog.clear(context)
                    text = null
                    open = false
                },
            ) { Text("DISMISS", color = Sky.textDim) }
        },
        containerColor = Sky.surface,
    )
}

