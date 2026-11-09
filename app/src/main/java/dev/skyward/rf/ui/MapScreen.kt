package dev.skyward.rf.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.zIndex
import dev.skyward.rf.Container
import dev.skyward.rf.core.AircraftView
import dev.skyward.rf.core.CameraKind
import dev.skyward.rf.core.CameraView
import dev.skyward.rf.core.CellView
import dev.skyward.rf.core.CrashLog
import dev.skyward.rf.core.Geo
import dev.skyward.rf.core.DroneView
import dev.skyward.rf.core.Esp32Node
import dev.skyward.rf.core.LatLon
import dev.skyward.rf.peer.PeerProtocol
import dev.skyward.rf.scan.UsbBoards
import dev.skyward.rf.sky.SatTracker
import dev.skyward.rf.core.Layer
import dev.skyward.rf.core.LoggedEntity
import dev.skyward.rf.core.MeshNode
import dev.skyward.rf.meshcore.MeshCore
import dev.skyward.rf.core.SatView
import dev.skyward.rf.core.RfKind
import dev.skyward.rf.core.ScanPlan
import dev.skyward.rf.core.SignalScale
import dev.skyward.rf.core.Units
import dev.skyward.rf.core.WatchKind
import dev.skyward.rf.core.WatchView
import dev.skyward.rf.net.IssCrew
import dev.skyward.rf.net.compassPoint
import dev.skyward.rf.sky.AirspaceEngine
import dev.skyward.rf.ui.theme.Sky
import dev.skyward.rf.sdr.SdrCapture
import dev.skyward.rf.wardrive.LoggedSignal
import dev.skyward.rf.wardrive.WardrivePoint
import dev.skyward.rf.wardrive.WardriveSession
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    /** One of the BIRDS-3 CubeSats: gold when caught, same glyph as every other satellite. */
    val birds3: Boolean = false,
    /** Which surveillance family matched, when this marker is a watch hit. */
    val watch: WatchKind? = null,
    /** Mapped camera family, when this marker came from the web camera layer. */
    val camera: CameraKind? = null,
    /** A mapped camera's own feed: opened outside the app, null when none is mapped. */
    val feedUrl: String? = null,
    /** How many nodes this one marker stands for: see [foldCoincident]. */
    val stack: Int = 1,

    /** Caught by the linked phone rather than this one: drawn cyan and owned by neither sky. */
    val peer: Boolean = false,
    /** The printed label, cut once here instead of sixty times a second in the draw loop. */
    val short: String = title.take(18),
    /** Mercator position, computed once: see [Projection.toScreenNorm]. */
    val nx: Double = Geo.lonToNorm(lon),
    val ny: Double = Geo.latToNorm(lat.coerceIn(-85.0, 85.0)),
)

/**
 * A logged signal, ready to draw. The colour is parsed once, when the table changes:
 * thousands of colour strings must not be decoded inside the draw loop. The signal itself is
 * kept so a tap on the dot can open its card without a second lookup in the table.
 */
data class SignalDot(
    val lat: Double,
    val lon: Double,
    val color: Color,
    val signal: LoggedSignal? = null,
)

/** One piece of attached hardware, listed in the top-right status panel. */
private data class HwEntry(
    val id: String,
    val title: String,
    val detail: String,
    val lines: List<Pair<String, String>>,
    /** Board id this sweep is stored under; blank for hardware that is not a scanning radio. */
    val assignKey: String = "",
    /** What the board reports it can hear, which decides the bands offered in its card. */
    val dualBand: Boolean = false,
    val bleCap: Boolean = false,
    val gpsCap: Boolean = false,
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
                    "Sub-GHz" to when (r.subGhz) {
                        true -> "CC1101 \u00b7 ${n?.subGhzHits ?: 0} carriers"
                        false -> "no radio"
                        null -> "\u2014"
                    },
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
                assignKey = b.key,
                dualBand = n?.dualBand ?: false,
                bleCap = n?.hasBle ?: true,
                gpsCap = n?.hasGps ?: (r.gps == true),
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

/** Nothing to draw, shared: the globe has no tiles, and spinning must not churn lists. */
private val NoTiles: List<TileRef> = emptyList()

/** Globe spin speeds, in the order Settings.globeSpin uses. */
private val SpinLabels = listOf("SPIN OFF", "SPIN 1X", "SPIN 3X")

/**
 * The colours a logged satellite can take when it becomes an entity, in the order Setup draws
 * them. Purple is the original and the default; the rest are there because a globe with Starlink
 * lit up needs a ring that still reads against the rest of the sky.
 */
internal val EntityColors: Map<String, Color> = linkedMapOf(
    "purple" to Sky.logged,
    "cyan" to Sky.cyan,
    "green" to Sky.mesh,
    "amber" to Sky.warn,
    "red" to Sky.danger,
    "white" to Sky.text,
)

/** Resolves the stored name; an unknown name is the purple it has always been. */
internal fun entityColor(name: String): Color = EntityColors[name] ?: Sky.logged

/** The station is gold: one object, the one everyone looks for, so it never shares a colour. */
private val IssGold = Color(0xFFFFC53D)

/** The BIRDS-3 set takes a warmer gold when caught: three first satellites, not the station. */
private val BirdsGold = Color(0xFFFFD166)

/** NORAD 25544 is the station; the name never decides, because SWISSCUBE also contains "ISS". */
private fun isIss(m: MapMarker): Boolean = m.station

private fun markerColor(m: MapMarker, entityRing: Color = Sky.logged): Color = when {
    isIss(m) -> IssGold
    // The peer's catch is not this phone's logged purple: it is someone else's intel.
    m.peer -> Sky.cyan
    m.birds3 && m.logged -> BirdsGold
    // One ring for every logged entity. The preset used to colour satellites only, which made
    // a caught drone, tower, mesh node or camera read as a different kind of catch.
    m.logged -> entityRing
    m.layer == Layer.SATELLITE -> Sky.satellite
    m.layer == Layer.AIRCRAFT -> Sky.aircraft
    m.layer == Layer.DRONE -> Sky.drone
    m.layer == Layer.ESP32 -> Sky.esp32
    // Two families of mapped camera, two colours: a Flock plate reader is the surveillance the
    // radios already hunt for, while a CCTV camera is street furniture that happens to watch.
    m.layer == Layer.CAMERA -> if (m.camera == CameraKind.CCTV) Sky.cctv else Sky.watchFlock
    m.layer == Layer.WATCH -> when (m.watch) {
        WatchKind.FLOCK -> Sky.watchFlock
        WatchKind.AXON -> Sky.watchAxon
        WatchKind.TASER -> Sky.watchTaser
        WatchKind.POLICE_ROUTER -> Sky.watchRouter
        WatchKind.POLICE_RADIO -> Sky.watchRadio
        WatchKind.TRACKER -> Sky.watchTracker
        null -> Sky.watchFlock
    }
    // Every mesh node is the same kind of thing on the map; a repeater's role is in its card.
    else -> Sky.mesh
}

/**
 * The satellites the map actually draws.
 *
 * The catalogue is sampled with an even stride, so a shell of thousands of objects does not cost
 * a frame and every orbit is still represented. The station and the BIRDS-3 set are outside that
 * arithmetic: they are named objects rather than a sample of a shell, and the stride used to drop
 * the ISS whenever the catalogue was large enough to need one.
 */
internal fun sampleSats(sats: List<SatView>, maxSats: Int): List<SatView> {
    val named = sats.filter {
        it.noradId == SatTracker.ISS_NORAD || it.noradId in SatTracker.BIRDS3_IDS
    }
    val rest = sats.filterNot {
        it.noradId == SatTracker.ISS_NORAD || it.noradId in SatTracker.BIRDS3_IDS
    }
    val stride = if (rest.size > maxSats) (rest.size + maxSats - 1) / maxSats else 1
    return named + rest.filterIndexed { index, _ -> index % stride == 0 }.take(maxSats)
}

/**
 * How far to step through a layer that is drawn at most `budget` times.
 *
 * A layer with more entries than the budget is sampled evenly rather than truncated: at these
 * dot sizes the skipped points sit under their neighbours anyway. The stride is never zero - an
 * empty layer strides by one - because `indices step 0` does not skip anything, it throws, and
 * the draw loop catches its own failures to protect the frame, so the throw silently deleted
 * every marker after the wardrive dots: all satellites, all aircraft, and the GPS pin.
 */
internal const val DotBudget = 900

internal fun drawStride(count: Int, budget: Int): Int =
    if (count <= budget) 1 else (count + budget - 1) / budget

fun buildMarkers(
    sky: AirspaceEngine.SkyState,
    showSats: Boolean,
    showAir: Boolean,
    showDrones: Boolean,
    showMesh: Boolean,
    showCells: Boolean,
    showEsp32: Boolean,
    showWatch: Boolean,
    /** Mapped Flock and CCTV cameras: no radio hears them, they are read from a web map. */
    showCameras: Boolean = true,
    /** "km" or "mi": the marker card is the only place a distance is written down. */
    distanceUnit: String,
    minScore: Int,
    /** A worldwide feed is tens of thousands of points; the map plots the nearest slice. */
    maxAircraft: Int = 900,
    /**
     * The other crowded layer. With the Starlink shell on, the catalogue runs to thousands of
     * objects and the globe has to draw them all in one frame; the strongest slice is enough
     * to see the shell, and the table still holds every one of them.
     */
    maxSats: Int = 1_500,
    /** The other phone's logged entities: the intel half of the link. */
    peerEntities: List<dev.skyward.rf.peer.PeerProtocol.Entity> = emptyList(),
    /**
     * Mesh entities the registry holds but the live feed no longer lists. A node with a
     * position becomes an entity the moment it is heard, so the map keeps drawing it after a
     * restart, a broker restart, or the feed being switched off.
     */
    loggedMesh: List<LoggedEntity> = emptyList(),
    /** Crew aboard the station, from the roster feed; null until it answers. */
    issCrew: IssCrew.Crew? = null,
): List<MapMarker> {
    val out = ArrayList<MapMarker>(sky.sats.size + sky.aircraft.size + sky.drones.size + sky.mesh.size)
    // Satellites are the one layer the score must not filter. Their score is elevation above
    // the observer, so keeping only the strong ones kept only the ones overhead, and the globe
    // showed a knot of icons at the GPS pin instead of a sky. Truncating from the top of the
    // list is the same knot again, because the list arrives elevation-sorted: the cap is taken
    // as an even stride across the catalogue instead, which keeps every orbit and inclination.
    //
    // The station and the BIRDS-3 set are outside that arithmetic. They are named objects, not
    // a sample of a shell, and the stride was dropping them: with the Starlink catalogues on,
    // NORAD 25544 is one index in ten thousand, so whether it survived depended on the stride.
    // The ISS is the one object everyone looks for, so it is always plotted.
    val plottedSats: List<SatView> = if (showSats) sampleSats(sky.sats, maxSats) else emptyList()
    // What the other phone caught, where it caught it. Drawn in the layer the entity belongs
    // to, so a friend's aircraft is a plane and their satellite is a satellite, but coloured
    // cyan and labelled as theirs: it is not this phone's catch.
    for (e in peerEntities) {
        val layer = runCatching { Layer.valueOf(e.layer) }.getOrNull() ?: continue
        out.add(
            MapMarker(
                key = "PEER:" + e.key,
                layer = layer,
                lat = e.lat,
                lon = e.lon,
                title = e.name.ifBlank { e.id },
                subtitle = "shared by the linked phone",
                score = 0,
                logged = false,
                peer = true,
                lines = listOf(
                    "Layer" to layer.name.lowercase(),
                    "Caught by" to "the linked phone",
                    "Id" to e.id,
                ),
            ),
        )
    }
    plottedSats.forEach { s: SatView ->
        out.add(
            MapMarker(
                key = "SAT:${s.noradId}",
                station = s.noradId == SatTracker.ISS_NORAD,
                birds3 = s.noradId in SatTracker.BIRDS3_IDS,
                layer = Layer.SATELLITE,
                lat = s.lat,
                lon = s.lon,
                title = s.name,
                subtitle = when (s.noradId) {
                    SatTracker.ISS_NORAD -> "International Space Station"
                    in SatTracker.BIRDS3_IDS -> "BIRDS-3 · deployed from the ISS"
                    else -> "NORAD ${s.noradId}"
                },
                score = s.score,
                logged = s.logged,
                altM = s.altKm * 1000.0,
                lines = listOfNotNull(
                    if (s.noradId == SatTracker.ISS_NORAD) "Catalog" to SatTracker.ISS_DESIGNATOR else null,
                    // The one fact the element sets cannot give. A dash until the roster answers,
                    // never a zero: "nobody aboard" is a claim, "not known" is not.
                    if (s.noradId == SatTracker.ISS_NORAD) "Crew aboard" to (issCrew?.label ?: "—") else null,
                    if (s.noradId in SatTracker.BIRDS3_IDS) "Deployed" to "ISS, 17 Jun 2019" else null,
                    "Elevation" to String.format("%.1f°", s.elevation),
                    "Azimuth" to String.format("%.1f°", s.azimuth),
                    "Range" to Units.distance(s.rangeKm, distanceUnit, 0),
                    "Altitude" to Units.distance(s.altKm, distanceUnit, 0),
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
                // A feed that publishes a blank track sends NaN, and a NaN angle poisons the
                // whole draw transform for that frame: the pin and everything drawn after it
                // go missing, which is what a layer that "glitches" looks like.
                headingDeg = a.trackDeg.toFloat().takeIf { it.isFinite() },
                altM = a.altFt?.let { it * 0.3048 }?.takeIf { d -> d.isFinite() },
                lines = listOf(
                    "Altitude" to (a.altFt?.let { "$it ft" } ?: "—"),
                    "Ground speed" to String.format("%.0f kt", a.groundSpeedKt),
                    "Vertical" to String.format("%+d fpm", a.verticalRateFpm),
                    "Distance" to Units.distance(a.distanceKm, distanceUnit, 1),
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
                    "Distance" to (d.distanceKm?.let { Units.distance(it, distanceUnit, 2) } ?: "—"),
                    "Operator" to d.operatorId.ifBlank { "—" },
                ),
            ),
        )
    }
    if (showMesh) {
        // One marker per node, from both sources at once: the live feed has the radio detail and
        // the registry has the position a restart took away. Unioning them by id is what makes
        // "every mesh node this app knows is on the map" true by construction, instead of two
        // loops having to agree about which of them draws which id.
        val meshMarkers = LinkedHashMap<String, MapMarker>()
        sky.mesh.forEach { n: MeshNode ->
        // A node's own position, or no pin. Drawing a node that shares no location at this
        // phone's fix put fixed infrastructure on whatever street the phone was on - a
        // confident answer that was simply wrong. The MESH card counts those nodes instead, so
        // "under the map" and "never heard" stop looking the same.
        val lat = n.lat ?: return@forEach
        val lon = n.lon ?: return@forEach
        // The weak-dot filter is for layers holding thousands of entries. A node is one piece
        // of infrastructure, and dropping it because its last advert arrived faint made the
        // marker disappear while the MESH count still listed it, which reads as a node that
        // went away.
        meshMarkers[n.id] = MapMarker(
                key = "MESH:${n.id}",
                layer = Layer.MESH,
                lat = lat,
                lon = lon,
                title = n.name.ifBlank { n.id },
                // MeshCore says what a node is; Meshtastic does not, and the source is all
                // that can be claimed about it.
                subtitle = if (n.role == MeshCore.Role.UNKNOWN) {
                    "Meshtastic · ${n.source.uppercase()}"
                } else {
                    "MeshCore · ${n.role.label}"
                },
                score = n.score,
                logged = n.logged,
                altM = n.altitudeM.toDouble(),
                lines = listOfNotNull(
                    if (n.role != MeshCore.Role.UNKNOWN) "Role" to n.role.label else null,
                    "SNR" to String.format("%.1f dB", n.snr),
                    n.rssi?.let { "RSSI" to "$it dBm" },
                    "Hops" to n.hops.toString(),
                    "Battery" to if (n.batteryPct >= 0) "${n.batteryPct}%" else "—",
                    "Altitude" to "${n.altitudeM} m",
                    "Distance" to (n.distanceKm?.let { Units.distance(it, distanceUnit, 1) } ?: "—"),
                    n.pubkey.takeIf { it.isNotBlank() }?.let { "Public key" to it.take(16) + "…" },
                    "Node id" to n.id,
                ),
            )
        }
        // A node with a position is an entity from the moment it is heard, so the registry is
        // added too: a restart, a broker that dropped the node, or a feed switched off must not
        // take a node off the map this phone has already found. An id already placed above is
        // skipped - the live marker is the richer card.
        loggedMesh.forEach { e: LoggedEntity ->
            if (e.layer != Layer.MESH) return@forEach
            if (!e.lat.isFinite() || !e.lon.isFinite()) return@forEach
            // The registry keys an entity by "LAYER:id", so the id is the node id either way.
            val id = e.key.substringAfter(':')
            if (id in meshMarkers) return@forEach
            meshMarkers[id] = MapMarker(
                key = e.key,
                layer = Layer.MESH,
                lat = e.lat,
                lon = e.lon,
                title = e.name.ifBlank { id },
                subtitle = "logged · not heard now",
                score = 0,
                logged = true,
                lines = listOf(
                    "Status" to "logged entity, kept after the feed",
                    "Node id" to id,
                    "Position" to String.format(Locale.US, "%.5f, %.5f", e.lat, e.lon),
                    "Sightings" to e.sightings.toString(),
                ),
            )
        }
        out.addAll(foldCoincident(meshMarkers.values.toList()))
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
                    "Distance" to (b.distanceKm?.let { Units.distance(it, distanceUnit, 2) } ?: "\u2014"),
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
    // ---- surveillance watch: Flock ALPR, Axon cameras, TASERs ----
    // The radios carry no bearing, so these sit at the listener's fix; the marker says how the
    // match was made rather than implying a position it cannot know.
    if (showWatch) sky.watch.forEach { w: WatchView ->
        if (w.score < minScore) return@forEach
        out.add(
            MapMarker(
                key = "WATCH:${w.kind.name}:${w.id}",
                layer = Layer.WATCH,
                watch = w.kind,
                lat = w.lat,
                lon = w.lon,
                title = w.name.ifBlank { w.id },
                subtitle = w.kind.detail,
                score = w.score,
                logged = w.logged,
                lines = listOf(
                    "Signature" to w.kind.label,
                    "Confidence" to w.confidence,
                    "Radio" to if (w.radio == RfKind.WIFI) "Wi-Fi" else "BLE",
                    "RSSI" to "${w.rssi} dBm",
                    "Heard by" to w.source,
                    "Address" to w.id,
                ),
            ),
        )
    }
    // ---- mapped surveillance cameras: Flock ALPR and CCTV, from OpenStreetMap ----
    // These are the one layer no radio hears. They carry real coordinates recorded by whoever
    // mapped them, so unlike a watch hit they belong on the street they are on. The feed is
    // carried into the card and opened from there: a camera that publishes its own picture is
    // watched outside an app whose job is the map.
    if (showCameras) sky.cameras.forEach { cam: CameraView ->
        val feed = cam.liveFeed.takeIf { it.isNotBlank() }
        out.add(
            MapMarker(
                key = "CAM:${cam.kind.name}:${cam.id}",
                layer = Layer.CAMERA,
                camera = cam.kind,
                lat = cam.lat,
                lon = cam.lon,
                title = cam.name,
                subtitle = if (cam.kind == CameraKind.FLOCK) {
                    "Flock / ALPR \u00b7 mapped"
                } else {
                    "CCTV \u00b7 mapped"
                },
                // A camera read from a map has no radio score: it is either mapped or it is not,
                // so the weak-dot filter must never hide one.
                score = 100,
                logged = false,
                feedUrl = feed,
                lines = listOfNotNull(
                    "Source" to "OpenStreetMap \u00b7 ODbL",
                    "Vendor" to cam.manufacturer.ifBlank { "\u2014" },
                    "Operator" to cam.operator.ifBlank { "\u2014" },
                    "Camera" to cam.cameraType.ifBlank { "\u2014" },
                    cam.directionDeg?.let { "Facing" to String.format(Locale.US, "%.0f\u00b0", it) },
                    "Zone" to cam.zone.ifBlank { "\u2014" },
                    "Mount" to cam.mount.ifBlank { "\u2014" },
                    "Install" to cam.install.ifBlank { "\u2014" },
                    "Live feed" to (feed?.let { cam.feedKind } ?: "none mapped"),
                    "Distance" to Units.distance(cam.distanceKm, distanceUnit, 2),
                    "Position" to String.format(Locale.US, "%.5f, %.5f", cam.lat, cam.lon),
                    "OSM" to cam.id,
                ),
            ),
        )
    }
    // One non-finite coordinate would poison the frame's transform and take every marker
    // after it with it, so a bad fix is dropped here rather than in the draw loop.
    return out.filter { it.lat.isFinite() && it.lon.isFinite() }
}

/**
 * Folds markers that sit on the same spot into one marker that says how many it stands for.
 *
 * A mesh table can hold many nodes on one coordinate - a node written down where it was heard
 * from instead of where it is, a repeater whose adverts all carried the observer's fix, or a
 * table that was filled while the map was at the whole-planet zoom - and fifty dish icons on
 * one pixel look exactly like one node. That is the shape of "the count says fifty and the map
 * shows one": every marker was there and every one was drawn, on top of its neighbour. The
 * spot keeps its first marker (the live one, when the feed still lists it) and carries the
 * count, so the number is printed instead of being hidden under the glyph.
 */
internal fun foldCoincident(markers: List<MapMarker>, places: Int = 4): List<MapMarker> {
    // Four decimal places is about 11 m: two nodes closer than that cannot be told apart on the
    // map at any zoom, so they share a spot rather than a pixel each.
    val scale = 10.0.pow(places)
    val spots = LinkedHashMap<Pair<Long, Long>, MutableList<MapMarker>>()
    for (m in markers) {
        // A non-finite coordinate never reaches here (buildMarkers drops it), and rounding one
        // would claim a position it does not have.
        if (!m.lat.isFinite() || !m.lon.isFinite()) continue
        val spot = Math.round(m.lat * scale) to Math.round(m.lon * scale)
        spots.getOrPut(spot) { ArrayList(1) }.add(m)
    }
    return spots.values.map { group ->
        if (group.size == 1) {
            group[0]
        } else {
            group[0].copy(
                stack = group.size,
                lines = group[0].lines + ("At this spot" to "${group.size} nodes"),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MapScreen(c: Container, modifier: Modifier = Modifier, onOpenDrive: () -> Unit) {
    val sky by c.airspace.state.collectAsState()
    val wd by c.wardrive.state.collectAsState()
    val overlay by c.wardrive.overlay.collectAsState()
    val observer by c.observer.collectAsState()
    val settings by c.settingsStore.state.collectAsState()
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
    var showWatch by remember { mutableStateOf(true) }
    // Mapped cameras are their own layer: they are read from a web map, not heard here.
    var showCameras by remember { mutableStateOf(true) }
    // Boards are status, not a layer: they no longer have a switch in the INTEL box.
    val showEsp32 = true
    var showPath by remember { mutableStateOf(true) }
    var showSignals by remember { mutableStateOf(true) }
    var legendOpen by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<MapMarker?>(null) }
    // The signal dots are a second kind of thing to tap: they are their own card, because a
    // network is not a marker in any layer.
    var selectedSignal by remember { mutableStateOf<LoggedSignal?>(null) }
    // A saved SDR capture is a third kind of pin: it belongs to the phone that recorded it, so
    // it is drawn from the capture table rather than from any live sweep.
    var selectedCapture by remember { mutableStateOf<SdrCapture?>(null) }
    // Offline tiles: a bounded save of the ground around the current centre, with the count
    // on screen because a silent download looks like a dead button.
    val tileScope = rememberCoroutineScope()
    var offlineBusy by remember { mutableStateOf(false) }
    var offlineNote by remember { mutableStateOf<String?>(null) }

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

    // The other phone's intel, read before the marker list is built: the markers are a
    // remembered value keyed on it, so a new share has to be part of the key.
    val peerIntel by c.peerEntities.collectAsState()
    // Who is aboard the station, for the ISS card; refreshed when the roster is a day old.
    val crew by c.issCrew.state.collectAsState()
    // The registry is the map's memory for mesh: an entity outlives the feed that found it.
    val loggedEntities by c.logged.state.collectAsState()
    val loggedMesh = remember(loggedEntities) {
        loggedEntities.values.filter { it.layer == Layer.MESH }
    }

    // Built off the frame thread. A sky tick lands about once a second, and the list formats the
    // card text for every entity in it - several thousand markers on a full catalogue, most of
    // them with a String.format or two. Doing that in composition put tens of milliseconds on the
    // main thread once a second, which is the hitch that interrupted the globe's spin: the ball
    // turns from the frame clock, so a blocked frame is a stopped ball. The markers are immutable
    // data, so only the finished list crosses back.
    val markers by produceState(
        initialValue = emptyList<MapMarker>(),
        sky, showSats, showAir, showDrones, showMesh, showCells, showEsp32, showWatch,
        showCameras, settings.aircraftEnabled, settings.dropWeakDots, settings.distanceUnit,
        peerIntel,
        crew, loggedMesh,
    ) {
        value = withContext(Dispatchers.Default) {
            buildMarkers(
                sky = sky,
                showSats = showSats,
                showAir = showAir && settings.aircraftEnabled,
                showDrones = showDrones,
                showMesh = showMesh,
                showCells = showCells,
                showEsp32 = showEsp32,
                showWatch = showWatch,
                showCameras = showCameras,
                distanceUnit = settings.distanceUnit,
                minScore = if (settings.dropWeakDots) 20 else 0,
                peerEntities = peerIntel,
                loggedMesh = loggedMesh,
                issCrew = crew,
            )
        }
    }

    val loggedSignals by c.signalLog.entries.collectAsState()
    val captures by c.sdrLibrary.state.collectAsState()
    // Same reason as the markers: the log can hold thousands of rows and each one decodes a
    // colour string, and a sweep rewrites the table while it runs.
    val signalDots by produceState(initialValue = emptyList<SignalDot>(), loggedSignals) {
        value = withContext(Dispatchers.Default) {
            loggedSignals.take(3000).mapNotNull { s ->
                val argb = runCatching { android.graphics.Color.parseColor(s.color) }.getOrNull()
                    ?: return@mapNotNull null
                SignalDot(s.lat, s.lon, Color(argb), s)
            }
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
            path = if (settings.offlineMode) emptyList() else path,
            signals = if (showSignals && !settings.offlineMode) signalDots else emptyList(),
            self = observer,
            accuracyM = null,
            selectedKey = selected?.key,
            onSelect = {
                selected = it
                // One card at a time: opening a marker closes a signal's card.
                selectedSignal = null
                selectedCapture = null
                if (it?.station == true) {
                    // Tapping the station asks the roster, which goes to the network only when
                    // the cached answer is a day old: the crew changes at a launch, not a tap.
                    tileScope.launch { runCatching { c.issCrew.refreshIfStale() } }
                }
            },
            selectedSignalKey = selectedSignal?.let { "${it.kind.name}:${it.id}" },
            onSelectSignal = {
                selectedSignal = it
                if (it != null) selected = null
                selectedCapture = null
            },
            sdrPins = captures,
            selectedSdrId = selectedCapture?.id,
            onSelectSdr = { id ->
                selectedCapture = captures.firstOrNull { it.id == id }
                selected = null
                selectedSignal = null
            },
            spinSpeed = settings.globeSpin,
            entityRing = entityColor(settings.satEntityColor),
            offline = settings.offlineMode,
            modifier = Modifier.fillMaxSize(),
        )

        // The dongle's panel is anchored top centre, between the status column and the clock:
        // it is a readout of one attached thing, not a layer, so it takes the free edge.
        SdrPanel(
            c,
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                // Above the status column it overlaps: those cards are drawn after this one,
                // and a drop-down that slid underneath them would be unreadable.
                .zIndex(1f),
        )

        val peer by c.peer.state.collectAsState()
        val usbBoards by c.esp32Usb.state.collectAsState()
        val espNodes by c.esp32.state.collectAsState()
        val hardware = remember(usbBoards, espNodes, sky.mesh) {
            buildHardware(usbBoards, espNodes, sky.mesh)
        }
        var espOpen by remember { mutableStateOf(false) }
        var meshOpen by remember { mutableStateOf(false) }
        // ---- status stack: top left, PEER then ESP32 then MESH ----
        // One column, so the right edge stays free for the map's own buttons and the eye can
        // read down a single list of what is attached. INTEL is drawn after this, which is why
        // the table flows over these cards rather than shoving them aside.
        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .width(216.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (peer.running) {
                StatusCard(title = "PEER", accent = Sky.cyan) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            peer.peerName.ifBlank { "BLE peer" },
                            color = Sky.text,
                            fontSize = 10.sp,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (peer.connected) "linked" else "searching",
                            color = Sky.textDim,
                            fontSize = 9.sp,
                        )
                    }
                    // What the other radio holds. Its screen is its own business: the link
                    // carries data, so the card claims nothing about what it is showing.
                    Text(
                        "wifi " + peer.peerWifiTotal + " \u00b7 ble " + peer.peerBleTotal,
                        color = Sky.textDim,
                        fontSize = 9.sp,
                        maxLines = 1,
                    )
                    Text(
                        "gps " + (if (peer.peerGps) "yes" else "no") +
                            " \u00b7 cell " + (if (peer.peerCell) "yes" else "no"),
                        color = Sky.textDim,
                        fontSize = 9.sp,
                        maxLines = 1,
                    )
                    // The other phone's cable intel, and whether a drive is running there:
                    // both are things that can only be seen from over here.
                    if (peer.peerBoardWifi > 0 || peer.peerBoardBle > 0) {
                        Text(
                            "board wifi " + peer.peerBoardWifi + " \u00b7 ble " + peer.peerBoardBle,
                            color = Sky.esp32,
                            fontSize = 9.sp,
                            maxLines = 1,
                        )
                    }
                    if (peer.peerRecording) {
                        Text("peer recording", color = Sky.cyan, fontSize = 9.sp, maxLines = 1)
                    }
                    // The intel the other phone has shared, which is the point of the link.
                    Text(
                        "intel " + peer.peerEntityTotal + " entities",
                        color = Sky.cyan,
                        fontSize = 9.sp,
                        maxLines = 1,
                    )
                    Text(
                        if (settings.peerShareMode == PeerProtocol.SHARE_FRIEND) {
                            "a friend \u00b7 intel only"
                        } else {
                            "own devices \u00b7 full share"
                        },
                        color = Sky.textDim,
                        fontSize = 9.sp,
                        maxLines = 1,
                    )
                }
            }

            // Attached hardware: ESP32 boards on a cable and Meshtastic radios heard over BLE.
            if (hardware.isNotEmpty()) {
                StatusCard(
                    title = "ESP32",
                    accent = Sky.esp32,
                    trailing = hardware.size.toString(),
                    open = espOpen,
                    onToggle = { espOpen = !espOpen },
                ) {
                    hardware.take(3).forEach { h ->
                        Text(h.title, color = Sky.text, fontSize = 11.sp, maxLines = 1)
                        Text(h.detail, color = Sky.textDim, fontSize = 9.sp, maxLines = 1)
                        if (espOpen) {
                            h.lines.forEach { (k, v) ->
                                Text("$k  $v", color = Sky.textDim, fontSize = 9.sp, maxLines = 1)
                            }
                            if (h.assignKey.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                val assign = settings.scanAssign[h.assignKey].orEmpty()
                                    .ifBlank { ScanPlan.defaultBand(h.dualBand, h.bleCap) }
                                ScanLine(
                                    code = assign,
                                    options = ScanPlan.bandOptions(h.dualBand, h.bleCap),
                                    gpsAvailable = h.gpsCap,
                                    onPick = { code ->
                                        c.settingsStore.update {
                                            it.copy(scanAssign = it.scanAssign + (h.assignKey to code))
                                        }
                                    },
                                )
                                if (!h.dualBand) {
                                    Text(
                                        "single band: no 5 GHz radio reported",
                                        color = Sky.textDim,
                                        fontSize = 8.sp,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                    if (hardware.size > 3) {
                        Text("+${hardware.size - 3} more", color = Sky.textDim, fontSize = 9.sp)
                    }
                    if (espOpen) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("BAUD", style = SkywardThemeLabel(), color = Sky.textDim)
                            Spacer(Modifier.weight(1f))
                            Text("${settings.esp32Baud}", color = Sky.text, fontSize = 9.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        // The rates as a list to pick from, not a stepper: a board flashed at
                        // 921600 is one tap away, and the rate in use is the one drawn solid.
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            dev.skyward.rf.scan.UsbBoards.BAUD_RATES.forEach { rate ->
                                val chosen = rate == settings.esp32Baud
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (chosen) Sky.esp32.copy(alpha = 0.30f)
                                            else Sky.outline.copy(alpha = 0.30f),
                                        )
                                        .clickable(enabled = !chosen) {
                                            // Reopening the port is what applies it: the bridge
                                            // rate is set once, when the console is opened.
                                            c.settingsStore.update { it.copy(esp32Baud = rate) }
                                            c.esp32Usb.setBaud(rate)
                                            c.esp32Usb.stop()
                                            c.esp32Usb.start()
                                        }
                                        .padding(horizontal = 5.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        "$rate",
                                        color = if (chosen) Sky.esp32 else Sky.textDim,
                                        fontSize = 8.5.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Mesh: the broker's state plus the nodes, so "no nodes" can be told apart from
            // "the feed is off" without opening Setup.
            val meshBle = sky.mesh.count { it.source.equals("ble", ignoreCase = true) }
            val meshMqtt = sky.mesh.count { it.source.equals("mqtt", ignoreCase = true) }
            // MeshCore nodes are counted apart from the Meshtastic feed: a repeater is
            // infrastructure, and its count is the part of the mesh worth watching.
            val meshCore = sky.mesh.count { it.role != MeshCore.Role.UNKNOWN }
            val meshRpt = sky.mesh.count { it.role == MeshCore.Role.REPEATER }
            // Nodes heard but sharing no location: counted here, and never pinned somewhere
            // they are not, so the map's silence about them is explained rather than missing.
            val meshNoPos = sky.mesh.count { it.lat == null || it.lon == null }
            // Still no card for a feed that is switched off and empty - an empty box of zeros
            // only takes the space the INTEL table needs. A feed that is on does get one even
            // with nothing in it, because "connected and silent" must not look like "off".
            val meshFeedOn = sky.mesh.isNotEmpty() || sky.meshStatus.ok == true ||
                settings.meshMqttEnabled || settings.meshCoreEnabled
            if (meshFeedOn) StatusCard(
                title = "MESH",
                accent = Sky.mesh,
                trailing = sky.mesh.size.toString(),
                open = meshOpen,
                onToggle = { meshOpen = !meshOpen },
            ) {
                // The switch is a setting; this line is what the clients are doing. "broker on"
                // used to be printed from the setting alone, so a feed that had never connected -
                // or had dropped and not re-subscribed - claimed to be live while carrying
                // nothing, which reads exactly like a broker that is working.
                val meshPlotted = markers.count { it.layer == Layer.MESH }
                val ms = sky.meshStatus
                Text(
                    "broker " + (if (ms.ok == true) "live" else "down") +
                        " \u00b7 ble " + meshBle + " \u00b7 mqtt " + meshMqtt +
                        (if (meshCore > 0) " \u00b7 core " + meshCore + " \u00b7 rpt " + meshRpt else "") +
                        (if (meshNoPos > 0) " \u00b7 " + meshNoPos + " no pos" else "") +
                        " \u00b7 " + meshPlotted + " plotted",
                    color = Sky.textDim,
                    fontSize = 9.sp,
                    maxLines = 1,
                )
                // Why a count can be larger than the map: "down" is the feed, "no pos" is a node
                // that shares no location, and "plotted" is the dishes actually on the map -
                // including a spot standing for several nodes. Those three numbers were the
                // ones that had to be guessed at before.
                if (ms.detail.isNotBlank()) {
                    Text(
                        ms.detail,
                        color = if (ms.ok == true) Sky.textDim else Sky.danger,
                        fontSize = 9.sp,
                        maxLines = 2,
                    )
                }
                // The address itself. "Broker on" says nothing about what it is on, and the
                // topic is only half of what had to be typed to get here: a broker one letter
                // wrong looks exactly like a broker that is working.
                if (settings.meshMqttEnabled) {
                    Text(
                        "mqtt " + settings.meshBroker + "  " + settings.meshTopic,
                        color = Sky.textDim,
                        fontSize = 9.sp,
                        maxLines = 1,
                    )
                }
                if (settings.meshCoreEnabled) {
                    Text(
                        "core " + settings.meshCoreBroker + "  " + settings.meshCoreTopic,
                        color = Sky.textDim,
                        fontSize = 9.sp,
                        maxLines = 1,
                    )
                }
                sky.mesh.take(if (meshOpen) 6 else 2).forEach { n ->
                    // The role tag is what tells a repeater apart from a handheld in a list
                    // that has no room for a glyph.
                    val tag = when (n.role) {
                        MeshCore.Role.REPEATER -> "RPT "
                        MeshCore.Role.ROOM -> "ROOM "
                        MeshCore.Role.CHAT -> "CHAT "
                        MeshCore.Role.SENSOR -> "SNSR "
                        MeshCore.Role.UNKNOWN -> ""
                    }
                    Text(
                        tag + (n.name.ifBlank { n.id }).take(16) +
                            " \u00b7 " + String.format("%.1f dB", n.snr) + " \u00b7 " + n.hops + "hp",
                        color = if (n.role == MeshCore.Role.REPEATER) Sky.meshCore else Sky.text,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
                if (!meshOpen && sky.mesh.size > 2) {
                    Text("+${sky.mesh.size - 2} more", color = Sky.textDim, fontSize = 9.sp)
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
            // The header total is the whole logged table - the sky layers plus every network
            // the signal log holds - so it matches the Log tab's total, not just the sky's.
            val caughtWifi = loggedSignals.count { it.kind == RfKind.WIFI }
            val caughtBle = loggedSignals.count { it.kind == RfKind.BLE }
            val caughtSub = loggedSignals.count { it.kind == RfKind.SUBGHZ }
            val caughtTotal = sky.loggedSats + sky.loggedAircraft + sky.loggedDrones +
                sky.loggedMesh + sky.loggedCells + sky.loggedEsp32 + sky.loggedWatch +
                caughtWifi + caughtBle + caughtSub
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { legendOpen = !legendOpen },
            ) {
                Text("INTEL", style = SkywardThemeLabel(), color = Sky.textDim)
                Spacer(Modifier.width(6.dp))
                Text(
                    "$caughtTotal",
                    color = Sky.logged,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (wd.active) {
                    Spacer(Modifier.width(6.dp))
                    Text("REC", color = Sky.warn, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                // Offline is a mode the map hides everything but the GPS constellation for, and
                // an empty layer must not read as a dead feed.
                if (settings.offlineMode) {
                    Spacer(Modifier.width(6.dp))
                    Text("OFFLINE", color = Sky.warn, fontSize = 9.sp, fontWeight = FontWeight.Bold)
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
                // One table, two numbers: what is being heard now and what this drive has
                // caught, in the same row. The LIVE and LOGGED tables this replaces printed
                // every layer name twice and put the two counts a table apart.
                IntelPairHeader()
                IntelPairRow("SAT", sky.sats.size, sky.loggedSats, showSats) { showSats = !showSats }
                IntelPairRow("AIR", sky.aircraft.size, sky.loggedAircraft, showAir) { showAir = !showAir }
                IntelPairRow("UAV", sky.drones.size, sky.loggedDrones, showDrones) { showDrones = !showDrones }
                // Every positioned node is written to the registry, so the logged column is the
                // registry's count: it keeps counting a node after the feed stops listing it,
                // which is exactly what the map is still drawing.
                IntelPairRow("MESH", sky.mesh.size, loggedMesh.size, showMesh) { showMesh = !showMesh }
                IntelPairRow("CELL", sky.cells.size, sky.loggedCells, showCells) { showCells = !showCells }
                // Cameras and TASERs were heard, not triangulated: the row is still a count of
                // matched advertisers, not of confirmed installations.
                IntelPairRow("WATCH", sky.watch.size, sky.loggedWatch, showWatch) { showWatch = !showWatch }
                // Mapped, not caught: the camera layer has no LOGGED column because nothing here
                // was heard, and a dash is the honest number for it.
                IntelPairRow("CAM", sky.cameras.size, null, showCameras) { showCameras = !showCameras }
                // One table for both phones, keyed by device: a network heard by either is a
                // single row, so the two devices print the same numbers. Adding the peer's
                // total to this phone's own counted every shared network twice, which is why
                // the two tables never matched.
                val shared = c.rf.totals()
                IntelPairRow("WIFI", shared.wifi, caughtWifi, wifiOn) { c.rf.setWifiEnabled(!wifiOn) }
                IntelPairRow("BLE", shared.ble, caughtBle, bleOn) { c.rf.setBleEnabled(!bleOn) }
                // A carrier has no address, so nothing listens for one now: the live column
                // shows a dash rather than claiming a zero.
                IntelPairRow("SUB", null, caughtSub, true) { }
                IntelPairRow("PATH", path.size, null, showPath) { showPath = !showPath }
                // Individual networks, drawn at the fix where each was heard.
                IntelPairRow("SIG", signalDots.size, null, showSignals) { showSignals = !showSignals }
                // What the other phone put *into* the shared table, not a second total that
                // is already counted in WIFI and BLE above.
                IntelPairRow("PEER", c.rf.peerCount(), null, peer.connected) { }
                // The peer's own findings, posted into this table by name rather than merely
                // counted, so the host shows what the other radio is hearing right now.
                if (peer.connected) {
                    // What the other phone's radios heard, by name and with its own RSSI, so
                    // this phone reads the link's findings rather than just a count of them.
                    c.rf.peerRows(limit = 4).forEach { o ->
                        Text(
                            "\u203a " + (if (o.kind == RfKind.WIFI) "wifi " else "ble  ") +
                                (o.name.ifBlank { o.id }).take(15) + " \u00b7 " + o.rssi + " dB",
                            color = Sky.textDim,
                            fontSize = 8.sp,
                            maxLines = 1,
                        )
                    }
                }
                // The shared intel, so the whole picture is readable without opening the card.
                Text(
                    "peer intel · " + peer.peerEntityTotal + " entities",
                    color = Sky.textDim,
                    fontSize = 9.sp,
                    maxLines = 1,
                )
                // A board is a second radio, so its sweep is a row of its own beside the
                // peer's rather than being invisible in the combined counts.
                val espWifi = espNodes.sumOf { it.wifiCount }
                val espBle = espNodes.sumOf { it.bleCount }
                IntelPairRow("ESP", espWifi + espBle, sky.loggedEsp32, espNodes.isNotEmpty()) { }
                // The board's own sweep, named like the peer's: a cable is a radio on this
                // table, so what it heard belongs on the table and not only in a total.
                c.rf.boardRows(limit = 3).forEach { o ->
                    Text(
                        "\u203a " + (if (o.kind == RfKind.WIFI) "wifi " else "ble  ") +
                            (o.name.ifBlank { o.id }).take(15) + " \u00b7 " + o.rssi + " dB \u00b7 board",
                        color = Sky.esp32.copy(alpha = 0.85f),
                        fontSize = 8.sp,
                        maxLines = 1,
                    )
                }
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
                            Units.distance(wd.km, settings.distanceUnit, 2) + " logged",
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

        // ---- live clock: top right, and its drop-down is the weather at the pin ----
        // The Watcher's status box sits under the clock in the same column, so the corner answers
        // both questions together and nothing floats over the map.
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (settings.showLiveTime) {
                LiveClock(c, observer, settings.distanceUnit, settings.tempUnit, settings.clockSeconds)
            }
            if (settings.voiceEnabled) {
                if (settings.showLiveTime) Spacer(Modifier.height(6.dp))
                WatcherBox(c)
            }
        }

        // ---- actions ----
        // The bottom-right corner belongs to the folded tab button, so this stack stops above
        // it rather than putting a control underneath it.
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 62.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // One switch, two directions: on the street map this swings out to the ball, and
            // on the ball it brings the map back. A pinch is not the only way to travel -
            // the globe is a destination, and it needed a door.
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
                // Speed of the turn, cycled in place: off, slow, fast and round again.
                IconButton(
                    onClick = {
                        c.settingsStore.update { s -> s.copy(globeSpin = (s.globeSpin + 1) % 3) }
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Sky.surface.copy(alpha = 0.95f))
                        .border(1.dp, Sky.outline, RoundedCornerShape(12.dp)),
                ) {
                    Text(
                        SpinLabels[settings.globeSpin.coerceIn(0, SpinLabels.lastIndex)],
                        color = if (settings.globeSpin == 0) Sky.textDim else Sky.cyan,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else {
                IconButton(
                    onClick = { camera.centerOn(camera.lat, camera.lon, 3.0, resumeFollow = false) },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Sky.surface.copy(alpha = 0.95f))
                        .border(1.dp, Sky.outline, RoundedCornerShape(12.dp)),
                ) {
                    Text("GLOBE", color = Sky.cyan, fontSize = 8.sp, fontWeight = FontWeight.Bold)
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
            // Saves the ground around the current centre so the map still draws with no
            // network. Coarse zooms land first, so the area is readable even if stopped early.
            // Hidden while offline: saving tiles is a download.
            if (!settings.useDeviceMap && !settings.offlineMode) {
                IconButton(
                    onClick = {
                        if (!offlineBusy) {
                            offlineBusy = true
                            offlineNote = "SAVING…"
                            tileScope.launch {
                                val saved = tileStore.downloadArea(
                                    source = settings.tileSource,
                                    lat = camera.lat,
                                    lon = camera.lon,
                                    maxZoom = camera.zoom.toInt().coerceIn(13, 16),
                                ) { done, total -> offlineNote = "SAVE $done/$total" }
                                offlineNote = "OFFLINE $saved tiles"
                                offlineBusy = false
                            }
                        }
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Sky.surface.copy(alpha = 0.95f))
                        .border(1.dp, Sky.outline, RoundedCornerShape(12.dp)),
                ) {
                    Text(if (offlineBusy) "…" else "⇩", color = Sky.cyan, fontSize = 15.sp)
                }
                offlineNote?.let {
                    Text(it, style = SkywardThemeLabel(), fontSize = 8.sp, color = Sky.textDim)
                }
            }
        }

        selected?.let { m ->
            MarkerCard(
                marker = m,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp, start = 12.dp, end = 12.dp),
                entityRing = entityColor(settings.satEntityColor),
                onClose = { selected = null },
            )
        }

        selectedSignal?.let { s ->
            SignalCard(
                signal = s,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp, start = 12.dp, end = 12.dp),
                onClose = { selectedSignal = null },
            )
        }

        selectedCapture?.let { cap ->
            SdrCard(
                capture = cap,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp, start = 12.dp, end = 12.dp),
                onClose = { selectedCapture = null },
            )
        }
    }
}

/**
 * One box in the top-left status stack.
 *
 * PEER, ESP32 and MESH share it so they line up at one width, and each header opens to show
 * its detail. The cards are drawn before the INTEL table, so the table flows over them when
 * it is open instead of the stack and the table fighting for the same corner.
 */
@Composable
private fun StatusCard(
    title: String,
    accent: Color,
    trailing: String? = null,
    open: Boolean = false,
    onToggle: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Sky.surface.copy(alpha = 0.94f))
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .then(if (onToggle != null) Modifier.clickable { onToggle() } else Modifier)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = SkywardThemeLabel(), color = accent)
            Spacer(Modifier.weight(1f))
            if (trailing != null) {
                Text(trailing, color = Sky.textDim, fontSize = 9.sp)
            }
            if (onToggle != null) {
                Spacer(Modifier.width(5.dp))
                Text(if (open) "▼" else "▲", color = Sky.textDim, fontSize = 9.sp)
            }
        }
        content()
    }
}

/**
 * One assigned sweep, with a drop-down to change it.
 *
 * Bands the radio cannot hear are not offered at all, and GPS is a separate switch because it
 * belongs to every band - a board without a module simply never sees it.
 */
@Composable
private fun ScanLine(
    code: String,
    options: List<String>,
    gpsAvailable: Boolean,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("SCAN", style = SkywardThemeLabel(), fontSize = 8.sp, color = Sky.textDim)
        Spacer(Modifier.weight(1f))
        Box {
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Sky.surfaceHigh)
                    .clickable { open = true }
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(ScanPlan.label(code), color = Sky.cyan, fontSize = 8.sp, maxLines = 1)
                Spacer(Modifier.width(4.dp))
                Text("▾", color = Sky.textDim, fontSize = 8.sp)
            }
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(ScanPlan.label(option), color = Sky.text, fontSize = 11.sp) },
                        onClick = {
                            open = false
                            // Keep the GPS switch as it was when only the band changes.
                            if (ScanPlan.band(option) != ScanPlan.band(code)) {
                                onPick(ScanPlan.withGps(option, ScanPlan.hasGps(code)))
                            }
                        },
                    )
                }
            }
        }
    }
    if (gpsAvailable) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("GPS", style = SkywardThemeLabel(), fontSize = 8.sp, color = Sky.textDim)
            Spacer(Modifier.weight(1f))
            ToggleChip("GPS", null, Sky.mesh, ScanPlan.hasGps(code)) {
                onPick(ScanPlan.withGps(code, !ScanPlan.hasGps(code)))
            }
        }
    }
}

@Composable
private fun MarkerCard(
    marker: MapMarker,
    modifier: Modifier = Modifier,
    /** The colour this build uses for a logged satellite, chosen in Setup. */
    entityRing: Color = Sky.logged,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Sky.surface)
            .border(1.dp, markerColor(marker, entityRing).copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(markerColor(marker, entityRing), 9)
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
        marker.feedUrl?.let { url ->
            Spacer(Modifier.height(10.dp))
            // The feed is opened where it can actually play. This app draws a map, not a video
            // surface, and a mapped feed may be HLS, MJPEG or a vendor page behind a login.
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(Sky.cyan)
                    .clickable { openFeed(context, url) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "OPEN LIVE FEED",
                    color = Sky.bg,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * Hands a mapped camera feed to whatever app can play it.
 *
 * A link with no handler is a toast rather than a crash: the camera is still worth knowing about
 * when the phone has nothing installed that opens it.
 */
private fun openFeed(context: android.content.Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "No app can open that feed", Toast.LENGTH_SHORT).show()
    }
}

/**
 * What a tapped logged signal is.
 *
 * These dots are the one map object drawn from the signal table rather than the live sweep, so
 * the card says which radio heard it and when as well as what it was: a network logged by the
 * other phone belongs to the street that phone was on, not to this one.
 */
@Composable
private fun SignalCard(signal: LoggedSignal, modifier: Modifier = Modifier, onClose: () -> Unit) {
    val color = runCatching { android.graphics.Color.parseColor(signal.color) }.getOrNull()
        ?.let { Color(it) } ?: Sky.logged
    val heard = remember(signal.at) {
        SimpleDateFormat("d MMM HH:mm:ss", Locale.US).format(Date(signal.at))
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Sky.surface)
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(color, 9)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    signal.name.ifBlank { signal.id },
                    color = Sky.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when (signal.kind) {
                        RfKind.WIFI -> "Logged Wi-Fi network"
                        RfKind.BLE -> "Logged Bluetooth device"
                        RfKind.SUBGHZ -> "Logged sub-GHz carrier"
                    },
                    color = Sky.textDim,
                    fontSize = 11.sp,
                )
            }
            Text("✕", color = Sky.textDim, modifier = Modifier.clickable { onClose() })
        }
        Spacer(Modifier.height(8.dp))
        if (signal.kind == RfKind.SUBGHZ) {
            // A carrier has no address: its identity is the frequency the CC1101 heard.
            KeyValueRow(
                "Frequency",
                String.format(Locale.US, "%.3f MHz", signal.id.toDoubleOrNull() ?: 0.0),
                color,
            )
        } else {
            KeyValueRow("Address", signal.id)
        }
        KeyValueRow("Signal", "${signal.rssi} dBm · score ${signal.score} · bin ${signal.bin}", color)
        if (signal.channel > 0) KeyValueRow("Channel", signal.channel.toString())
        KeyValueRow(
            "Heard by",
            when (signal.via) {
                "peer" -> "the other phone"
                "board" -> "a scanning board"
                else -> "this phone"
            },
        )
        KeyValueRow("Last heard", heard)
        KeyValueRow(
            "Position",
            String.format(Locale.US, "%.5f, %.5f", signal.lat, signal.lon),
        )
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
    /** The tapped logged signal as "KIND:id", or null when none is open. */
    selectedSignalKey: String? = null,
    onSelectSignal: (LoggedSignal?) -> Unit = {},
    /** Saved SDR captures: a pennant where each recording was started. */
    sdrPins: List<SdrCapture> = emptyList(),
    selectedSdrId: String? = null,
    onSelectSdr: (String?) -> Unit = {},
    /** 0 off, 1 = 1x, 2 = 3x: how fast the ball turns on its own axis. */
    spinSpeed: Int,
    /** The ring colour a logged entity is drawn in, from Settings.satEntityColor. */
    entityRing: Color = Sky.logged,
    /** Offline mode: cached tiles only, nothing is downloaded. */
    offline: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val appContext = LocalContext.current
    // The tap detector must not be keyed on the marker list: it is rebuilt on every tick, and
    // restarting the pointer handler mid-gesture is what breaks a pinch half way out.
    val latestMarkers by rememberUpdatedState(markers)
    // Same reason as the markers: the tap handler is keyed on Unit and would otherwise hold the
    // signal list from the first frame, when nothing had been logged yet.
    val latestSignals by rememberUpdatedState(signals)
    val latestSdr by rememberUpdatedState(sdrPins)
    var tileVersion by remember { mutableStateOf(0) }
    // Paths and scratch buffers for the sphere, allocated once: rebuilding four Paths per
    // frame put a few hundred kilobytes an hour through the collector for no reason.
    val globeInk = remember { GlobeInk() }

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
        // The frame clock turns the ball. A still globe this size reads as a drawing, and the
        // tilt only becomes visible once it moves, so the two changes need each other. Driven
        // per frame rather than per second: a timer would step the longitude in visible jumps.
        LaunchedEffect(globeMode, spinSpeed) {
            if (!globeMode || spinSpeed <= 0) return@LaunchedEffect
            // 1x and 3x of the same base turn: two speeds that read as slow and fast on a
            // sphere, rather than four settings where two of them are near enough the same.
            val degPerSecond = 4.5 * spinSpeed
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    if (last != 0L) {
                        val dt = ((now - last) / 1_000_000_000.0).coerceIn(0.0, 0.1)
                        camera.spinGlobe(degPerSecond * dt)
                    }
                    last = now
                }
            }
        }
        // Pointer handlers are keyed on Unit, so they keep the first value they ever saw.
        val globeModeNow by rememberUpdatedState(globeMode)
        // Device-map mode: no tile requests at all, the phone's map app draws the streets.
        val showTiles = !globeMode && !useDeviceMap
        // The tile list is the only thing that needs the camera's position, and on the ball
        // there are no tiles at all. Reading lat/lon unconditionally made every frame of the
        // globe's spin invalidate this whole screen - the ball turned at the rate of a full
        // recomposition, which is why it crawled. Behind the branch below the spin only touches
        // the draw phase, where it belongs.
        val tileLat = if (showTiles) camera.lat else 0.0
        val tileLon = if (showTiles) camera.lon else 0.0
        val visible = remember(tileSource, camera.zoom, tileLat, tileLon, w, h, showTiles) {
            if (showTiles) {
                tileStore.visible(tileSource, camera.lat, camera.lon, camera.zoom, w, h, viewShiftX(w))
            } else {
                // One shared empty list: a fresh one each frame would restart the loader
                // effect once per frame while the globe spins.
                NoTiles
            }
        }

        LaunchedEffect(visible, offline) {
            // Offline: the tiles already on the phone are drawn, and nothing is asked for.
            if (offline) return@LaunchedEffect
            // A pan rewrites the visible list on every touch event, and each rewrite used to
            // restart this loop mid-download. Waiting for the finger to settle keeps one load
            // pass instead of one per frame of the gesture.
            delay(150)
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
                        // A dot is a fraction of a marker's size, so it is hit-tested on its
                        // own and only wins when it is nearer than any icon under the tap.
                        var bestSignal: LoggedSignal? = null
                        var bestSignalDist = 26f * density
                        if (!globeModeNow) {
                            val sink = ProjectedSink()
                            for (s in latestSignals) {
                                val sig = s.signal ?: continue
                                vp.surfaceInto(s.lat, s.lon, sink)
                                val d = sqrt((sink.x - tap.x).pow(2) + (sink.y - tap.y).pow(2))
                                if (d < bestSignalDist) {
                                    bestSignalDist = d
                                    bestSignal = sig
                                }
                            }
                        }
                        // A pennant is smaller than either of the others, so it is checked
                        // last and only wins when the tap is nearer to it than to anything else.
                        var bestSdr: SdrCapture? = null
                        var bestSdrDist = 18f * density
                        if (!globeModeNow) {
                            val sink = ProjectedSink()
                            for (cap in latestSdr) {
                                vp.surfaceInto(cap.lat, cap.lon, sink)
                                val d = sqrt((sink.x - tap.x).pow(2) + (sink.y - tap.y).pow(2))
                                if (d < bestSdrDist) {
                                    bestSdrDist = d
                                    bestSdr = cap
                                }
                            }
                        }
                        when {
                            bestSdr != null && bestSdrDist <= bestSignalDist && bestSdrDist <= bestDist ->
                                onSelectSdr(bestSdr.id)
                            bestSignal != null && bestSignalDist <= bestDist -> onSelectSignal(bestSignal)
                            else -> onSelect(best)
                        }
                    }
                },
        ) {
            try {
            tileVersion // read state so new tiles repaint
            val proj = Projection(
                camera.lat,
                camera.lon,
                camera.zoom,
                size.width,
                size.height,
                viewShiftX(size.width),
            )

            // ---- basemap: flat tiles near the ground, a lit sphere from orbit ----
            drawRect(color = Sky.bg)
            val z = camera.zoom.toInt().coerceIn(2, 18)
            val tileSize = proj.tileSize(z)
            for (t in visible) {
                        val (sx, sy) = proj.tileOrigin(t.z, t.x, t.y)
                        val px = tileSize.roundToInt()
                        if (px <= 0) continue
                        val dst = IntOffset(sx.roundToInt(), sy.roundToInt())
                        val dstSize = IntSize(px + 1, px + 1)
                        val exact = tileStore.cachedImage(t.z, t.x, t.y)
                        if (exact != null) {
                            drawImage(
                                image = exact,
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
                            val parent = tileStore.ancestorImage(t.z, t.x, t.y) ?: continue
                            drawImage(
                                image = parent.image,
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
                    center = Offset(size.width / 2f + viewShiftX(size.width), size.height / 2f),
                    tiltDeg = GLOBE_TILT_DEG,
                    // Same radius the markers are projected with, or icons drift off the coast.
                    // From the canvas itself, so the disc and the markers cannot disagree.
                    radius = globeRadius(camera.zoom, size.width, size.height),
                    ink = globeInk,
                )
            }

            val vp = viewProjection(camera, size.width, size.height, globeT)
            val cull = globeMode
            val labels = camera.zoom >= 9.0 && markers.size <= 60
            // One of each per frame: a logged globe draws hundreds of rings, and a fresh
            // Stroke for every one of them was the same allocation problem as the projections.
            val ringStroke = Stroke(width = 1.2f * density)
            val selectStroke = Stroke(width = 1.6f * density)

            // Two reusable projections for the whole frame: see [ProjectedSink].
            val pa = ProjectedSink()
            val pb = ProjectedSink()

            // ---- wardrive path: dots only, 5-bin red -> green, weak to strong ----
            // The segments used to be painted as a continuous line. A line that wide covers the
            // network dots along it and eats the taps meant for them, so the drive is shown as
            // its own points instead: each fix is a dot, and nothing is painted between them.
            // Drawn at every zoom now, because the dots are the whole track rather than an
            // extra over the line.
            // An even sample of the track once it is dense. A drive can hold thousands of
            // fixes, each dot is under 3dp across, and past a thousand they are painting the
            // same few hundred pixels over and over: the skipped dots sit underneath the kept
            // ones, so nothing visible is lost and the frame stops paying for them.
            val pathStep = drawStride(path.size, DotBudget)
            for (i in path.indices step pathStep) {
                val p = path[i]
                vp.surfaceInto(p.lat, p.lon, pa)
                if (cull && pa.front <= 0.02f) continue
                if (pa.x < -20f || pa.y < -20f || pa.x > size.width + 20f || pa.y > size.height + 20f) continue
                drawCircle(
                    color = Color(SignalScale.colorOfScore(p.score)),
                    radius = 2.4f * density,
                    center = Offset(pa.x, pa.y),
                )
            }

            // ---- logged signals: each network where it was actually heard ----
            // Flat map only: on the ball a dot per network would be indistinguishable from
            // the logged-entity halos, and the sphere is for the sky, not the streets.
            if (signals.isNotEmpty() && !globeMode) {
                // The same budget as the track, except while a dot is selected: its ring has
                // to be drawn on the dot that was actually tapped.
                val signalStep =
                    if (selectedSignalKey != null) 1 else drawStride(signals.size, DotBudget)
                for (si in signals.indices step signalStep) {
                    val s = signals[si]
                    vp.surfaceInto(s.lat, s.lon, pb)
                    if (pb.x < -20f || pb.y < -20f ||
                        pb.x > size.width + 20f || pb.y > size.height + 20f
                    ) {
                        continue
                    }
                    val p = Offset(pb.x, pb.y)
                    drawCircle(color = s.color.copy(alpha = 0.20f), radius = 3.6f * density, center = p)
                    drawCircle(color = s.color, radius = 1.7f * density, center = p)
                    val sig = s.signal
                    if (sig != null && selectedSignalKey == sig.kind.name + ":" + sig.id) {
                        drawCircle(color = Sky.cyan, radius = 6.5f * density, center = p, style = ringStroke)
                    }
                }
            }

            // ---- saved SDR captures: a pennant on a mast, never a dot ----
            if (sdrPins.isNotEmpty() && !globeMode) {
                for (cap in sdrPins) {
                    vp.surfaceInto(cap.lat, cap.lon, pa)
                    val p = Offset(pa.x, pa.y)
                    val mast = 10f * density
                    drawLine(
                        color = Sky.sdr,
                        start = Offset(p.x, p.y),
                        end = Offset(p.x, p.y - mast),
                        strokeWidth = 1.3f * density,
                    )
                    drawRect(
                        color = Sky.sdr.copy(alpha = 0.85f),
                        topLeft = Offset(p.x, p.y - mast),
                        size = androidx.compose.ui.geometry.Size(7f * density, 6f * density),
                    )
                    if (cap.id == selectedSdrId) {
                        drawCircle(color = Sky.cyan, radius = 8f * density, center = p, style = ringStroke)
                    }
                }
            }

            // ---- entities: lifted off the globe by their own altitude ----
            for (m in markers) {
                // Aircraft belong to the flat map. They are the densest layer by far, and on the
                // ball a few hundred airliner triangles are indistinguishable from the logged
                // rings they sit among - while each still costs a projection and a glyph. The
                // skip is before the projection so the globe does not pay for them at all.
                if (cull && m.layer == Layer.AIRCRAFT) continue
                vp.elevatedNormInto(m.lat, m.nx, m.ny, m.altM, pa)
                // whatever has rotated round the back of the sphere is not drawn
                if (cull && pa.front <= 0.02f) continue
                if (pa.x < -80f || pa.y < -80f || pa.x > size.width + 80f || pa.y > size.height + 80f) continue
                val a = if (cull) smoothstep(pa.front) else 1f
                if (a <= 0.02f) continue

                val base = markerColor(m, entityRing)
                val color = base.copy(alpha = a)
                val p = Offset(pa.x, pa.y)

                // tether down to the ground point: the height cue that makes it airborne
                // A tether is an altitude cue for things that belong to the ground: crewed
                // traffic, a drone, a node on a mast. It is not one for a satellite catalogue -
                // once the whole shell is plotted, hundreds of lines from sub-point to icon read
                // as scratches across the ball rather than as height.
                if (pa.lift > 0.004f && m.layer != Layer.AIRCRAFT && m.layer != Layer.SATELLITE) {
                    vp.surfaceInto(m.lat, m.lon, pb)
                    val g = pb
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

                // Mesh has a size of its own, and it is half the entity size: a feed can hold
                // dozens of nodes at once, and at full weight they crowded out the aircraft and
                // the watch hits instead of sitting behind them as infrastructure. It grows
                // back on the ball instead of shrinking with the zoom: at planet scale the whole
                // mesh is one city on a continent, and a dish drawn at street weight is three
                // pixels - which is why zooming out made the nodes look like they had gone.
                val r = when {
                    m.layer == Layer.MESH && cull -> 7.2f * density
                    m.layer == Layer.MESH && m.logged -> 4.6f * density
                    m.layer == Layer.MESH -> 4.1f * density
                    m.logged -> 5.4f * density
                    else -> 4.4f * density
                }
                // The station is drawn a size up: it is one object, and it should read as one.
                val iconR = if (isIss(m)) r * 1.9f else r
                // The station and the BIRDS-3 set are the two gold objects on the map; both
                // glow when caught instead of taking the purple every other logged entity uses.
                if ((isIss(m) || m.birds3) && m.logged) {
                    val goldTint = if (m.birds3) BirdsGold else IssGold
                    drawCircle(
                        brush = androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(goldTint.copy(alpha = 0.55f * a), Color.Transparent),
                            center = p,
                            radius = iconR * 5.5f,
                        ),
                        radius = iconR * 5.5f,
                        center = p,
                    )
                } else if (m.logged) {
                    // The entity circle, in whichever colour was picked in Setup: every layer
                    // takes it now, so "caught" reads the same in all of them.
                    val ring = entityRing
                    drawCircle(color = ring.copy(alpha = 0.18f * a), radius = r * 3.2f, center = p)
                    drawCircle(
                        color = ring.copy(alpha = a),
                        radius = r * 2.4f,
                        center = p,
                        style = ringStroke,
                    )
                }
                if (isIss(m)) {
                    // A gold halo under the glyph, so the station reads gold logged or not.
                    drawCircle(color = IssGold.copy(alpha = 0.22f * a), radius = iconR * 1.8f, center = p)
                }
                if (m.layer == Layer.MESH && cull) {
                    // On the ball the node sits on a continent painted in its own colour family:
                    // a dark backing is what keeps a green dish off a green landmass, and the
                    // wider halo is what gives it weight at planet scale.
                    drawCircle(color = Sky.bg.copy(alpha = 0.85f * a), radius = r * 1.15f, center = p)
                    drawCircle(color = base.copy(alpha = 0.30f * a), radius = r * 1.55f, center = p)
                } else if (m.layer == Layer.MESH || m.layer == Layer.CAMERA) {
                    // A disc under the glyph: on the ball a dish is a few thin lines over a
                    // continent, and an unlogged node has no ring to give it any weight.
                    drawCircle(color = base.copy(alpha = 0.18f * a), radius = r * 1.75f, center = p)
                }
                when (m.layer) {
                    Layer.AIRCRAFT -> drawPlaneArrow(p, m.headingDeg ?: 0f, r, color)
                    Layer.SATELLITE -> if (isIss(m)) drawStationIcon(p, iconR, color) else drawSatelliteIcon(p, r, color)
                    Layer.CELL -> drawTowerIcon(p, r, color)
                    // One shape for the whole layer: a repeater is a node that repeats, and its
                    // role is in its card rather than in a second glyph this small. On the ball
                    // the dish's thin lines are lost, so it becomes a filled core in a ring:
                    // the same node, legible at any zoom.
                    Layer.MESH -> if (cull) {
                        drawCircle(color = color, radius = r * 0.45f, center = p)
                        drawCircle(color = color, radius = r * 0.95f, center = p, style = ringStroke)
                    } else {
                        drawDishIcon(p, r, color)
                    }
                    Layer.ESP32 -> drawChipIcon(p, r, color)
                    // A plate reader keeps the lens-on-a-bracket the watch layer uses; a CCTV
                    // dome gets its own glyph so the two do not read as the same box.
                    Layer.CAMERA -> if (m.camera == CameraKind.CCTV) {
                        drawCctvIcon(p, r, color)
                    } else {
                        drawCameraIcon(p, r, color)
                    }
                    Layer.DRONE -> {
                        // A drone with a track is a triangle, but a smaller one than an airliner:
                        // the two must not read as the same weight in the sky. Without a
                        // heading there is nothing to point, so it gets the quadcopter glyph.
                        val heading = m.headingDeg
                        if (heading != null) {
                            drawPlaneArrow(p, heading, r * 0.62f, color)
                        } else {
                            drawDroneIcon(p, r * 0.8f, color)
                        }
                    }
                    // A camera and a TASER are different things to see on a street; the
                    // glyph, not just the colour, says which one this is.
                    Layer.WATCH -> when (m.watch) {
                        WatchKind.TASER -> drawTaserIcon(p, r, color)
                        WatchKind.POLICE_ROUTER -> drawRouterIcon(p, r, color)
                        WatchKind.POLICE_RADIO -> drawRadioIcon(p, r, color)
                        else -> drawCameraIcon(p, r, color)
                    }
                }
                // A spot standing for several nodes must say so: drawing the dish alone is what
                // made a whole logged mesh read as one node on the map.
                if (m.stack > 1 && a > 0.4f) {
                    labelPaint.color = color.toArgb()
                    labelPaint.textSize = 9f * density
                    drawContext.canvas.nativeCanvas.drawText(
                        "\u00d7${m.stack}",
                        p.x - iconR * 0.5f,
                        p.y - iconR * 2.1f,
                        labelPaint,
                    )
                }
                if (m.key == selectedKey) {
                    drawCircle(
                        color = Sky.cyan.copy(alpha = a),
                        radius = r * 3.4f,
                        center = p,
                        style = selectStroke,
                    )
                }
                if (labels && a > 0.6f) {
                    labelPaint.color = color.toArgb()
                    labelPaint.textSize = 9.5f * density
                    drawContext.canvas.nativeCanvas.drawText(m.short, p.x + iconR * 2.2f, p.y - iconR, labelPaint)
                }
            }

            // ---- own position ----
            if (self != null) {
                vp.surfaceInto(self.lat, self.lon, pa)
                val p = Offset(pa.x, pa.y)
                // On the globe the pin is one point on a whole planet: at street size its halo
                // alone covered a country, so the reticle and its label shrink with the view
                // rather than competing with the continents behind them.
                val gpsR = (if (globeMode) 3.4f else 7.5f) * density
                drawGpsLogo(p, gpsR, Sky.cyan)
                labelPaint.color = Sky.cyan.toArgb()
                labelPaint.textSize = (if (globeMode) 7f else 9.5f) * density
                drawContext.canvas.nativeCanvas.drawText(
                    "GPS",
                    p.x + (if (globeMode) 7f else 13f) * density,
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

/**
 * The clock in the top-right corner, and the weather behind it.
 *
 * The time is the phone's own and ticks once a second. Tapping it opens the weather at the GPS
 * pin: temperature, what it feels like, cloud, wind and gusts, rain, pressure and the terrain
 * height, from Open-Meteo. [dev.skyward.rf.net.WeatherFeed] throttles the reads to one per ten
 * minutes and one per two kilometres, so opening the drop-down costs nothing when it is current.
 */
@Composable
private fun LiveClock(
    c: Container,
    observer: LatLon?,
    distanceUnit: String,
    tempUnit: String,
    /** Seconds under the clock; off, the hour and minute take their space instead. */
    showSeconds: Boolean,
    modifier: Modifier = Modifier,
) {
    val weather by c.weather.state.collectAsState()
    val scope = rememberCoroutineScope()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var open by remember { mutableStateOf(false) }
    // With seconds off the hour and minute keep the corner but grow into the space they free.
    val timeFmt = remember(showSeconds) {
        SimpleDateFormat(if (showSeconds) "HH:mm:ss" else "HH:mm", Locale.getDefault())
    }
    val dayFmt = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    Box(modifier) {
        Column(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Sky.surface.copy(alpha = 0.95f))
                .border(1.dp, Sky.outline, RoundedCornerShape(12.dp))
                .clickable {
                    open = true
                    // A tap is the one moment a fresh read is worth asking for; the feed
                    // decides whether it has anything to fetch.
                    observer?.let { pin -> scope.launch { runCatching { c.weather.refresh(pin.lat, pin.lon) } } }
                }
                .padding(horizontal = 15.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                timeFmt.format(Date(now)),
                color = Sky.cyan,
                fontFamily = FontFamily.Monospace,
                // Sized for a glance from a dash mount: the time is the one thing on this
                // screen that is read at arm's length, so it gets the room.
                fontSize = if (showSeconds) 25.sp else 34.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                dayFmt.format(Date(now)) + " \u25BE",
                color = Sky.textDim,
                fontSize = 10.sp,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val w = weather
            val pin = observer
            Column(
                Modifier
                    .width(232.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    if (pin == null) "GPS PIN" else String.format("GPS PIN %.5f, %.5f", pin.lat, pin.lon),
                    style = SkywardThemeLabel(),
                    color = Sky.textDim,
                )
                Spacer(Modifier.height(6.dp))
                when {
                    pin == null -> Text(
                        "No fix yet, so there is no pin to forecast.",
                        color = Sky.textDim,
                        fontSize = 10.sp,
                    )
                    w == null -> Text(
                        "Reading the sky\u2026",
                        color = Sky.textDim,
                        fontSize = 10.sp,
                    )
                    else -> {
                        Text(w.condition, color = Sky.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        WeatherLine("Temperature", Units.temperature(w.tempC, tempUnit))
                        WeatherLine("Feels like", Units.temperature(w.feelsC, tempUnit))
                        WeatherLine("Humidity", w.humidity?.let { "$it%" } ?: "\u2014")
                        WeatherLine("Cloud", w.cloud?.let { "$it%" } ?: "\u2014")
                        WeatherLine(
                            "Wind",
                            w.windKmh?.let { v ->
                                listOf(Units.wind(v, distanceUnit), compassPoint(w.windDir))
                                    .filter { it.isNotBlank() }
                                    .joinToString(" ")
                            } ?: "\u2014",
                        )
                        WeatherLine("Gusts", Units.wind(w.gustsKmh, distanceUnit))
                        WeatherLine("Rain now", Units.rain(w.precipMm, distanceUnit))
                        WeatherLine("Pressure", w.pressureHpa?.let { String.format("%.0f hPa", it) } ?: "\u2014")
                        WeatherLine("Terrain", Units.elevation(w.elevationM, distanceUnit))
                        Spacer(Modifier.height(6.dp))
                        Text(
                            w.error?.let { "offline: $it" }
                                ?: ("updated " + timeFmt.format(Date(w.at)) + " \u00b7 Open-Meteo"),
                            color = if (w.error != null) Sky.warn else Sky.textDim,
                            fontSize = 9.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WeatherLine(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(key, color = Sky.textDim, fontSize = 10.sp, modifier = Modifier.width(82.dp))
        Text(value, color = Sky.text, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

