package dev.skyward.rf.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyward.rf.core.AircraftView
import dev.skyward.rf.core.LatLon
import dev.skyward.rf.core.SatView
import dev.skyward.rf.core.SignalScale
import dev.skyward.rf.desktop.hardware.BoardState
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Which layers are drawn. The INTEL rows switch them, exactly as the app's table does. */
data class GlobeLayers(
    val sats: Boolean = true,
    val aircraft: Boolean = true,
    val boards: Boolean = true,
    val wifi: Boolean = true,
    val ble: Boolean = true,
)

object WorldLand {

    private var rings: Array<FloatArray> = emptyArray()

    /**
     * Natural Earth 110m land, the same asset the phone bundles.
     *
     * On the phone it is an Android asset; here it is a classpath resource, so the desk ships
     * the identical world outline instead of a second, differently simplified one.
     */
    fun rings(): Array<FloatArray> {
        if (rings.isNotEmpty()) return rings
        rings = runCatching {
            val stream = WorldLand::class.java.getResourceAsStream("/world_land.txt")
                ?: return emptyArray()
            stream.bufferedReader().useLines { lines ->
                val out = ArrayList<FloatArray>(160)
                var first = true
                for (raw in lines) {
                    if (first) { first = false; continue }
                    val parts = raw.trim().split(' ')
                    if (parts.size < 6 || parts.size % 2 != 0) continue
                    val arr = FloatArray(parts.size)
                    var ok = true
                    for (i in parts.indices) {
                        val v = parts[i].toFloatOrNull()
                        if (v == null) { ok = false; break }
                        arr[i] = v / 100f
                    }
                    if (ok) out.add(arr)
                }
                out.toTypedArray()
            }
        }.getOrDefault(emptyArray())
        return rings
    }
}

/**
 * The globe on the desk.
 *
 * Orthographic, one hemisphere at a time, tilted 20 degrees so the poles read as poles. Drag to
 * spin it, and the zoom buttons change the disc's size. Everything drawn on it comes from the
 * app's own maths: the same OSM-free land outline, the same altitude lift, the same signal
 * colours - a satellite here is at the position the phone would put it.
 */
@Composable
fun GlobeView(
    modifier: Modifier,
    sats: List<SatView>,
    aircraft: List<AircraftView>,
    boards: List<BoardState>,
    observer: LatLon?,
    layers: GlobeLayers,
    onLayer: (String, Boolean) -> Unit,
    satNote: String,
    airNote: String,
) {
    var centerLat by remember { mutableStateOf(20.0) }
    var centerLon by remember { mutableStateOf(0.0) }
    var zoom by remember { mutableStateOf(1f) }
    val rings = remember { WorldLand.rings() }

    Box(modifier.background(Color(0xFF04090F))) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    // Longitude is free to spin; latitude stops at the poles, because a globe
                    // that tips past them shows the back of its own axis.
                    centerLon -= drag.x * 0.22
                    centerLat = (centerLat + drag.y * 0.22).coerceIn(-80.0, 80.0)
                }
            },
        ) {
            drawGlobe(
                rings = rings,
                centerLat = centerLat,
                centerLon = centerLon,
                zoom = zoom,
                sats = if (layers.sats) sats else emptyList(),
                aircraft = if (layers.aircraft) aircraft else emptyList(),
                boards = boards,
                observer = observer,
                layers = layers,
            )
        }

        IntelOverlay(
            modifier = Modifier.align(Alignment.TopStart).padding(14.dp),
            sats = sats.size,
            aircraft = aircraft.size,
            boards = boards.size,
            wifi = boards.sumOf { it.aps },
            ble = boards.sumOf { it.bles },
            layers = layers,
            onLayer = onLayer,
            satNote = satNote,
            airNote = airNote,
        )

        Row(
            Modifier.align(Alignment.BottomEnd).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZoomButton("−") { zoom = (zoom - 0.15f).coerceIn(0.45f, 2.2f) }
            Spacer(Modifier.width(6.dp))
            ZoomButton("+") { zoom = (zoom + 0.15f).coerceIn(0.45f, 2.2f) }
            Spacer(Modifier.width(6.dp))
            ZoomButton("◎") { zoom = 1f; centerLat = 20.0; centerLon = 0.0 }
        }
    }
}

@Composable
private fun ZoomButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .background(Desk.panel.copy(alpha = 0.9f), RoundedCornerShape(6.dp))
            .border(1.dp, Desk.line, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, color = Desk.text, fontSize = 12.sp, fontFamily = Desk.mono)
    }
}

@Composable
private fun IntelOverlay(
    modifier: Modifier,
    sats: Int,
    aircraft: Int,
    boards: Int,
    wifi: Int,
    ble: Int,
    layers: GlobeLayers,
    onLayer: (String, Boolean) -> Unit,
    satNote: String,
    airNote: String,
) {
    Column(
        modifier
            .width(250.dp)
            .background(Desk.panel.copy(alpha = 0.92f), RoundedCornerShape(10.dp))
            .border(1.dp, Desk.line, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("INTEL", color = Desk.text, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, fontFamily = Desk.mono)
            Spacer(Modifier.weight(1f))
            Text("LIVE", color = Desk.accent, fontSize = 9.sp, letterSpacing = 1.sp, fontFamily = Desk.mono)
        }
        Spacer(Modifier.height(8.dp))
        IntelRow("SAT", sats, Desk.cyan, layers.sats) { onLayer("sat", it) }
        IntelRow("AIR", aircraft, Desk.text, layers.aircraft) { onLayer("air", it) }
        IntelRow("ESP", boards, Desk.accent, layers.boards) { onLayer("boards", it) }
        IntelRow("WIFI", wifi, Desk.warn, layers.wifi) { onLayer("wifi", it) }
        IntelRow("BLE", ble, Desk.violet, layers.ble) { onLayer("ble", it) }
        Spacer(Modifier.height(8.dp))
        Text(satNote.ifBlank { "—" }, color = Desk.dim, fontSize = 9.sp, fontFamily = Desk.mono, maxLines = 2)
        Text(airNote.ifBlank { "—" }, color = Desk.dim, fontSize = 9.sp, fontFamily = Desk.mono, maxLines = 2)
    }
}

@Composable
private fun IntelRow(label: String, count: Int, color: Color, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle(!on) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(7.dp)
                .height(7.dp)
                .background(if (on) color else color.copy(alpha = 0.25f), RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = if (on) Desk.text else Desk.dim,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            fontFamily = Desk.mono,
            modifier = Modifier.weight(1f),
        )
        Text(
            count.toString(),
            color = if (on) Desk.text else Desk.dim.copy(alpha = 0.6f),
            fontSize = 12.sp,
            fontFamily = Desk.mono,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun DrawScope.drawGlobe(
    rings: Array<FloatArray>,
    centerLat: Double,
    centerLon: Double,
    zoom: Float,
    sats: List<SatView>,
    aircraft: List<AircraftView>,
    boards: List<BoardState>,
    observer: LatLon?,
    layers: GlobeLayers,
) {
    val radius = min(size.width, size.height) * 0.44f * zoom
    val center = Offset(size.width * 0.55f, size.height / 2f)
    val tilt = Math.toRadians(20.0)
    val sinLat0 = sin(Math.toRadians(centerLat))
    val cosLat0 = cos(Math.toRadians(centerLat))
    val lon0 = Math.toRadians(centerLon)
    val cosTilt = cos(tilt)
    val sinTilt = sin(tilt)
    val pt = FloatArray(3)

    /** Orthographic projection with the axis tipped toward the viewer; pt[2] is facing. */
    fun project(latDeg: Double, lonDeg: Double, lift: Double) {
        val la = Math.toRadians(latDeg.coerceIn(-89.9, 89.9))
        val dLon = Math.toRadians(lonDeg) - lon0
        val cosLat = cos(la)
        val cosc = sinLat0 * sin(la) + cosLat0 * cosLat * cos(dLon)
        val x3 = cosLat * sin(dLon)
        val y3 = cosLat0 * sin(la) - sinLat0 * cosLat * cos(dLon)
        pt[2] = (y3 * sinTilt + cosc * cosTilt).toFloat()
        val k = radius * (1.0 + lift)
        pt[0] = (center.x + k * x3).toFloat()
        pt[1] = (center.y - k * (y3 * cosTilt - cosc * sinTilt)).toFloat()
    }

    drawCircle(Color(0xFF0A1826), radius = radius, center = center)

    val disc = Path().apply { addOval(Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)) }

    clipPath(disc) {
        // ---- graticule, 30 degrees, drawn before the land so it only reads over water ----
        val grid = Path()
        fun line(latA: Double, lonA: Double, latB: Double, lonB: Double, steps: Int) {
            var open = false
            for (step in 0..steps) {
                val f = step.toDouble() / steps
                project(latA + (latB - latA) * f, lonA + (lonB - lonA) * f, 0.0)
                if (pt[2] > 0.02f) {
                    if (open) grid.lineTo(pt[0], pt[1]) else { grid.moveTo(pt[0], pt[1]); open = true }
                } else {
                    open = false
                }
            }
        }
        var meridian = -180.0
        while (meridian <= 180.0) { line(-84.0, meridian, 84.0, meridian, 56); meridian += 30.0 }
        var parallel = -60.0
        while (parallel <= 60.0) { line(parallel, -180.0, parallel, 180.0, 96); parallel += 30.0 }
        drawPath(grid, Color(0xFF1E3548), style = Stroke(width = 0.7.dp.toPx()))

        // ---- continents ----
        val land = Path()
        val coast = Path()
        val outside = radius * 1.02f
        for (ring in rings) {
            val n = ring.size / 2
            if (n < 3) continue
            val xs = FloatArray(n)
            val ys = FloatArray(n)
            val front = BooleanArray(n)
            var frontCount = 0
            for (k in 0 until n) {
                project(ring[2 * k + 1].toDouble(), ring[2 * k].toDouble(), 0.0)
                val dx = pt[0] - center.x
                val dy = pt[1] - center.y
                val len = sqrt(dx * dx + dy * dy)
                val near = pt[2] > 0.02f
                front[k] = near
                if (near) {
                    xs[k] = pt[0]; ys[k] = pt[1]; frontCount++
                } else {
                    // Behind the ball: pushed outside the clip so a closing edge never crosses
                    // the visible hemisphere and fills the complement with land-coloured water.
                    val a = if (len > 0.001f) atan2(dy.toDouble(), dx.toDouble()) else 0.0
                    xs[k] = center.x + outside * cos(a).toFloat()
                    ys[k] = center.y + outside * sin(a).toFloat()
                }
            }
            if (frontCount == 0) continue
            land.reset()
            land.moveTo(xs[0], ys[0])
            for (k in 1 until n) land.lineTo(xs[k], ys[k])
            land.close()
            drawPath(land, Color(0xFF2C4C68))

            var pen = false
            for (k in 0 until n) {
                val m = (k + 1) % n
                if (front[k] && front[m]) {
                    if (!pen) { coast.moveTo(xs[k], ys[k]); pen = true }
                    coast.lineTo(xs[m], ys[m])
                } else {
                    pen = false
                }
            }
        }
        drawPath(coast, Color(0xFF86BCDF).copy(alpha = 0.8f), style = Stroke(width = 0.8.dp.toPx()))
    }

    // ---- the observer ----
    if (layers.boards && observer != null) {
        project(observer.lat, observer.lon, 0.0)
        if (pt[2] > 0f) {
            drawCircle(Desk.accent, radius = 3.5.dp.toPx(), center = Offset(pt[0], pt[1]))
            drawCircle(Desk.accent.copy(alpha = 0.35f), radius = 9.dp.toPx(), center = Offset(pt[0], pt[1]), style = Stroke(1.dp.toPx()))
        }
    }

    // ---- Wi-Fi and BLE heard by the desk's boards, drawn at the board's own fix ----
    if ((layers.wifi || layers.ble) && observer != null) {
        val spots = ArrayList<Pair<Int, Int>>()
        if (layers.wifi) boards.forEach { b -> b.sweep.aps.forEach { spots.add(it.rssi to (it.channel % 7)) } }
        if (layers.ble) boards.forEach { b -> b.sweep.devices.forEach { spots.add(it.rssi to 3) } }
        // A board hears many networks from one place, so they are fanned around the fix rather
        // than stacked on it: a dot per network is the count made visible.
        spots.take(160).forEachIndexed { index, (rssi, _) ->
            val angle = index * 2.399963
            val r = 4.0 + (index % 7) * 1.6
            project(observer.lat, observer.lon, 0.0)
            if (pt[2] <= 0f) return@forEachIndexed
            val x = pt[0] + (cos(angle) * r * radius / 180.0).toFloat()
            val y = pt[1] + (sin(angle) * r * radius / 180.0).toFloat()
            drawCircle(Color(SignalScale.colorOfDbm(rssi)), radius = 1.6.dp.toPx(), center = Offset(x, y))
        }
    }

    // ---- aircraft ----
    aircraft.forEach { plane ->
        val lift = (ln(1.0 + ((plane.altFt ?: 0) * 0.3048) / 500.0) * 0.022).coerceIn(0.0, 0.30)
        project(plane.lat, plane.lon, lift)
        if (pt[2] <= 0.02f) return@forEach
        val at = Offset(pt[0], pt[1])
        project(plane.lat, plane.lon, 0.0)
        if (pt[2] > 0.02f) drawLine(Desk.text.copy(alpha = 0.18f), Offset(pt[0], pt[1]), at, 1f)
        drawCircle(Desk.text, radius = 1.8.dp.toPx(), center = at)
        val heading = Math.toRadians(plane.trackDeg)
        drawLine(
            Desk.text.copy(alpha = 0.8f),
            at,
            Offset(at.x + (cos(heading) * 6).toFloat(), at.y + (sin(heading) * 6).toFloat()),
            strokeWidth = 1.2.dp.toPx(),
        )
    }

    // ---- satellites, lifted by their own altitude ----
    sats.forEach { sat ->
        // Altitude is compressed the same way the phone does it: a low-orbit satellite floats
        // clear of the ball, a geostationary one stays on screen.
        val lift = (ln(1.0 + sat.altKm * 1000.0 / 500.0) * 0.022).coerceIn(0.0, 0.30)
        project(sat.lat, sat.lon, lift)
        if (pt[2] <= 0.02f) return@forEach
        val at = Offset(pt[0], pt[1])
        project(sat.lat, sat.lon, 0.0)
        if (pt[2] > 0.02f) drawLine(Desk.cyan.copy(alpha = 0.20f), Offset(pt[0], pt[1]), at, 1f)
        drawCircle(Desk.cyan, radius = 1.7.dp.toPx(), center = at)
    }

    drawCircle(Desk.cyan.copy(alpha = 0.22f), radius = radius, center = center, style = Stroke(1.4.dp.toPx()))
    // A quiet rim so the ball reads as a body against the black rather than a flat disc.
    drawCircle(Color(0xFF5AC8FF).copy(alpha = 0.10f), radius = radius * 1.01f, center = center, style = Stroke(3.dp.toPx()))
}
