package dev.skyward.rf.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyward.rf.core.AircraftView
import dev.skyward.rf.core.DroneView
import dev.skyward.rf.core.Geo
import dev.skyward.rf.core.MeshNode
import dev.skyward.rf.core.LatLon
import dev.skyward.rf.core.SatView
import dev.skyward.rf.core.SignalScale
import dev.skyward.rf.desktop.hardware.BoardState
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Which layers are drawn. The INTEL rows switch them, exactly as the app's table does. */
data class GlobeLayers(
    val sats: Boolean = true,
    val aircraft: Boolean = true,
    val boards: Boolean = true,
    val wifi: Boolean = true,
    val ble: Boolean = true,
    /** The mesh feeds cost a broker connection, so they are off until asked for. */
    val mesh: Boolean = false,
    /** BLE Remote ID costs a scanner process on this machine; also off by default. */
    val drones: Boolean = false,
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
            val stream = WorldLand::class.java.getResourceAsStream("/world_land.txt") ?: return emptyArray()
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
 * The map: a globe at world zoom, a street map when you zoom in.
 *
 * It is one camera and one set of markers. The ball's radius is derived from the same zoom as
 * the map's scale and the two meet at [MapMath.HANDOFF], so the projection can be blended on
 * every point without anything jumping: the sphere and the plane agree on pixels per degree at
 * the centre of the screen, and only the curvature dissolves.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MapView(
    modifier: Modifier,
    sats: List<SatView>,
    aircraft: List<AircraftView>,
    mesh: List<MeshNode>,
    drones: List<DroneView>,
    boards: List<BoardState>,
    observer: LatLon?,
    layers: GlobeLayers,
    onLayer: (String, Boolean) -> Unit,
    spin: Int,
    onSpin: () -> Unit,
) {
    var camLat by remember { mutableStateOf(20.0) }
    var camLon by remember { mutableStateOf(0.0) }
    // 4.0 puts the ball at a comfortable size: the hand-off is 5.5, where it fills the viewport.
    var zoom by remember { mutableStateOf(4.0) }
    val rings = remember { WorldLand.rings() }
    val scope = rememberCoroutineScope()
    val tiles = remember { TileStore(scope) }
    val generation = tiles.generation

    // A spinning ball cannot also be pinned to a fix, and a drag is the operator taking hold of
    // it: both stop the spin for as long as they last, as the phone's camera does.
    var dragging by remember { mutableStateOf(false) }
    val globeMode = MapMath.globeBlend(zoom) > 0.5f
    LaunchedEffect(spin, globeMode) {
        if (spin <= 0 || !globeMode) return@LaunchedEffect
        // Same rate as the phone: 1x and 3x of one base turn, so the two speeds read as slow and
        // fast on a sphere rather than as four settings that are nearly the same.
        val degreesPerSecond = 4.5 * spin
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L && !dragging) {
                    val dt = ((now - last) / 1_000_000_000.0).coerceIn(0.0, 0.1)
                    camLon = wrapLon(camLon + degreesPerSecond * dt)
                }
                last = now
            }
        }
    }

    // The first real fix puts the map where the board is, once. After that the camera belongs
    // to the operator: a map that recentres itself on every fix cannot be panned.
    var centred by remember { mutableStateOf(false) }
    LaunchedEffect(observer?.lat, observer?.lon) {
        val where = observer
        if (!centred && where != null) {
            camLat = where.lat
            camLon = where.lon
            centred = true
        }
    }

    Box(modifier.background(Color(0xFF070C12))) {
        Canvas(
            Modifier
                .fillMaxSize()
                // Compose does not clip a canvas to its bounds, and a tile at a negative offset
                // was being drawn over the sensors rail and the title bar.
                .clipToBounds()
                .pointerInput(Unit) {
                    // One loop for both gestures, because they must never be confused: with
                    // detectDragGestures a wheel notch zoomed the map *and* dragged it, since a
                    // scroll can open a drag on the desktop. Scroll only zooms here, and a drag
                    // needs the primary button actually held.
                    awaitPointerEventScope {
                        // A drag is anchored to real press and release events. The wheel on this
                        // platform arrives as Move changes that claim to be pressed, which is how
                        // zooming the map kept dragging it across the world.
                        var pressedAt = 0L
                        var last = Offset.Zero
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Press -> {
                                    dragging = true
                                    pressedAt = System.nanoTime()
                                    last = event.changes.firstOrNull()?.position ?: Offset.Zero
                                }

                                PointerEventType.Release -> dragging = false

                                PointerEventType.Scroll -> {
                                    val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                    // Wheel-up arrives as a negative delta in Compose on Windows,
                                    // so subtracting it means "wheel up zooms in", as on the phone.
                                    if (delta != 0f) {
                                        zoom = (zoom - delta * 0.5).coerceIn(MapMath.MIN_ZOOM, MapMath.MAX_ZOOM)
                                    }
                                }

                                PointerEventType.Move -> {
                                    val change = event.changes.firstOrNull() ?: continue
                                    val drag = change.position - last
                                    last = change.position
                                    // Windows reports a wheel notch as a press plus one large jump,
                                    // which panned the world while zooming it. A hand dragging the
                                    // map holds the button for longer than a frame and moves in
                                    // small steps, so both of those are required here.
                                    val heldMs = (System.nanoTime() - pressedAt) / 1_000_000L
                                    if (!dragging || heldMs < 120L) continue
                                    if (abs(drag.x) > 40f || abs(drag.y) > 40f) continue
                                    if (drag.x == 0f && drag.y == 0f) continue
                                    change.consume()
                                    if (MapMath.globeBlend(zoom) > 0.5f) {
                                        // On the ball a drag turns it; the rate slows as you zoom
                                        // in, so the surface moves with the cursor.
                                        val rate = 0.22 * (4.0 / zoom)
                                        camLon = wrapLon(camLon - drag.x * rate)
                                        camLat = (camLat + drag.y * rate).coerceIn(-80.0, 80.0)
                                    } else {
                                        val world = MapMath.worldSize(zoom)
                                        camLon = wrapLon(camLon - drag.x / world * 360.0)
                                        camLat = Geo.normToLat((Geo.latToNorm(camLat) - drag.y / world).coerceIn(0.0, 1.0))
                                    }
                                }

                                else -> Unit
                            }
                        }
                    }
                },
        ) {
            // Reading the tile counter here is what redraws the frame in which a new tile lands.
            drawMap(
                rings = rings,
                tiles = tiles,
                generation = generation,
                camLat = camLat,
                camLon = camLon,
                zoom = zoom,
                sats = if (layers.sats) sats else emptyList(),
                aircraft = if (layers.aircraft) aircraft else emptyList(),
                mesh = if (layers.mesh) mesh else emptyList(),
                drones = if (layers.drones) drones else emptyList(),
                boards = boards,
                observer = observer,
                layers = layers,
            )
        }

        Text(
            "© OpenStreetMap contributors",
            color = Desk.dim.copy(alpha = 0.8f),
            fontSize = 9.sp,
            fontFamily = Desk.mono,
            modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
        )

        Row(
            Modifier.align(Alignment.BottomEnd).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                (if (MapMath.globeBlend(zoom) > 0.5f) "GLOBE" else "MAP") +
                    " ${String.format("%.1f", zoom)} · ${String.format("%.3f, %.3f", camLat, camLon)}",
                color = Desk.dim,
                fontSize = 10.sp,
                fontFamily = Desk.mono,
            )
            Spacer(Modifier.width(8.dp))
            // The ball and the plane are one zoom, so the toggle only has to land on the far side of
            // the hand-off; the camera stays where the operator left it either way.
            MapButton(if (MapMath.globeBlend(zoom) > 0.5f) "MAP" else "GLOBE") {
                zoom = if (MapMath.globeBlend(zoom) > 0.5f) MapMath.HANDOFF + 1.0 else 4.0
            }
            Spacer(Modifier.width(6.dp))
            MapButton(if (spin > 0) "SPIN ${if (spin == 2) "3X" else "1X"}" else "SPIN") { onSpin() }
            Spacer(Modifier.width(6.dp))
            MapButton("−") { zoom = (zoom - 1.0).coerceIn(MapMath.MIN_ZOOM, MapMath.MAX_ZOOM) }
            Spacer(Modifier.width(6.dp))
            MapButton("+") { zoom = (zoom + 1.0).coerceIn(MapMath.MIN_ZOOM, MapMath.MAX_ZOOM) }
            Spacer(Modifier.width(6.dp))
            MapButton("◎") {
                zoom = 4.0
                camLat = 20.0
                camLon = 0.0
            }
        }
    }
}

@Composable
private fun MapButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .background(Desk.panel.copy(alpha = 0.92f), RoundedCornerShape(6.dp))
            .border(1.dp, Desk.line, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, color = Desk.text, fontSize = 12.sp, fontFamily = Desk.mono)
    }
}


private fun wrapLon(lon: Double): Double = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

private fun DrawScope.drawMap(
    rings: Array<FloatArray>,
    tiles: TileStore,
    generation: Int,
    camLat: Double,
    camLon: Double,
    zoom: Double,
    sats: List<SatView>,
    aircraft: List<AircraftView>,
    mesh: List<MeshNode>,
    drones: List<DroneView>,
    boards: List<BoardState>,
    observer: LatLon?,
    layers: GlobeLayers,
) {
    @Suppress("UNUSED_EXPRESSION")
    generation

    val t = MapMath.globeBlend(zoom)
    val width = size.width
    val height = size.height
    // The ball and the plane are two views of one zoom: the ball's radius follows it, and the
    // map runs at the zoom whose scale matches the ball's surface at the hand-off.
    val minSide = min(width, height)
    val ball = MapMath.globeRadius(zoom, minSide)
    val flatZoom = MapMath.mapZoom(zoom, minSide)
    val world = MapMath.worldSize(flatZoom)
    val camNx = Geo.lonToNorm(camLon)
    val camNy = Geo.latToNorm(camLat)

    drawRect(if (t > 0.5f) Color(0xFF04090F) else Color(0xFF0B1219))

    // ---- the flat map: OSM tiles, faded in as the ball turns into a plane ----
    if (t < 0.999f) {
        val view = MapMath.tileView(flatZoom, camNx, camNy, width, height)
        val sizePx = view.size.roundToInt().coerceAtLeast(1)
        val alpha = 1f - t
        for (ty in view.minY..view.maxY) {
            for (tx in view.minX..view.maxX) {
                val image = tiles.image(view.z, tx, ty) ?: continue
                drawImage(
                    image = image,
                    dstOffset = IntOffset(
                        (view.originX + tx * view.size).roundToInt(),
                        (view.originY + ty * view.size).roundToInt(),
                    ),
                    dstSize = IntSize(sizePx, sizePx),
                    alpha = alpha,
                )
            }
        }
        // A grid where tiles have not arrived yet, so an empty map reads as "not downloaded
        // yet" rather than "nothing there".
        if (alpha > 0.3f) {
            val grid = Path()
            var gx = view.originX
            while (gx < width) { grid.moveTo(gx, 0f); grid.lineTo(gx, height); gx += view.size }
            var gy = view.originY
            while (gy < height) { grid.moveTo(0f, gy); grid.lineTo(width, gy); gy += view.size }
            drawPath(grid, Color(0xFF16212C).copy(alpha = 0.5f * alpha), style = Stroke(1f))
        }
    }

    if (t > 0.02f) {
        drawGlobeSphere(rings, camLat, camLon, width, height, ball, t)
    }

    // ---- one projection for every marker, blended by the same t ----
    val sinLat0 = sin(Math.toRadians(camLat))
    val cosLat0 = cos(Math.toRadians(camLat))
    val lon0 = Math.toRadians(camLon)
    val tilt = Math.toRadians(20.0)
    val cosTilt = cos(tilt)
    val sinTilt = sin(tilt)
    val radius = ball
    val center = Offset(width * 0.5f, height * 0.5f)
    val pt = FloatArray(3)

    fun place(lat: Double, lon: Double, lift: Double): Boolean {
        val la = Math.toRadians(lat.coerceIn(-89.9, 89.9))
        val dLon = Math.toRadians(lon) - lon0
        val cosLat = cos(la)
        val cosc = sinLat0 * sin(la) + cosLat0 * cosLat * cos(dLon)
        val x3 = cosLat * sin(dLon)
        val y3 = cosLat0 * sin(la) - sinLat0 * cosLat * cos(dLon)
        val front = (y3 * sinTilt + cosc * cosTilt)
        val k = radius * (1.0 + lift)
        val gx = (center.x + k * x3).toFloat()
        val gy = (center.y - k * (y3 * cosTilt - cosc * sinTilt)).toFloat()
        val fx = (width / 2.0 + (Geo.lonToNorm(lon) - camNx) * world).toFloat()
        val fy = (height / 2.0 + (Geo.latToNorm(lat.coerceIn(-85.0, 85.0)) - camNy) * world).toFloat()
        pt[0] = fx + (gx - fx) * t
        pt[1] = fy + (gy - fy) * t
        pt[2] = if (t <= 0.02f) 1f else (1f - (1f - front.coerceIn(0.0, 1.0).toFloat()) * t)
        // The whole catalogue is fifteen thousand markers; one that lands outside the canvas
        // still costs a draw call, and that is the difference between a spinning globe and a
        // slideshow. The margin keeps arrows and links that start just off the edge intact.
        if (pt[0] < -60f || pt[0] > width + 60f || pt[1] < -60f || pt[1] > height + 60f) return false
        return pt[2] > 0.02f
    }

    // ---- observer: the pin is the operator's own fix, so it does not belong to a layer ----
    if (observer != null && place(observer.lat, observer.lon, 0.0)) {
        val tip = Offset(pt[0], pt[1])
        val headR = 5.5.dp.toPx()
        val headY = tip.y - 11.dp.toPx()
        // A pin, not a dot: at a glance the fix has to be distinguishable from the markers around
        // it, and its point sits on the position while the head carries the hole.
        val pin = Path().apply {
            moveTo(tip.x, tip.y)
            cubicTo(tip.x - headR * 0.9f, tip.y - headR * 1.1f, tip.x - headR, headY + headR * 0.35f, tip.x - headR, headY)
            arcTo(Rect(tip.x - headR, headY - headR, tip.x + headR, headY + headR), 180f, 180f, false)
            cubicTo(tip.x + headR, headY + headR * 0.35f, tip.x + headR * 0.9f, tip.y - headR * 1.1f, tip.x, tip.y)
            close()
        }
        drawPath(pin, Desk.accent)
        drawCircle(Desk.panel, radius = headR * 0.42f, center = Offset(tip.x, headY))
        drawCircle(
            Desk.accent.copy(alpha = 0.3f),
            radius = 13.dp.toPx(),
            center = tip,
            style = Stroke(1.dp.toPx()),
        )
    }

    // ---- the Wi-Fi and BLE the boards hear, fanned around the fix that heard them ----
    if ((layers.wifi || layers.ble) && observer != null) {
        val spots = ArrayList<Int>()
        if (layers.wifi) boards.forEach { b -> b.sweep.aps.forEach { spots.add(it.rssi) } }
        if (layers.ble) boards.forEach { b -> b.sweep.devices.forEach { spots.add(it.rssi) } }
        if (place(observer.lat, observer.lon, 0.0)) {
            val baseX = pt[0]
            val baseY = pt[1]
            spots.take(160).forEachIndexed { index, rssi ->
                val angle = index * 2.399963
                val r = 4.0 + (index % 7) * 1.6
                val x = baseX + (cos(angle) * r).toFloat()
                val y = baseY + (sin(angle) * r).toFloat()
                drawCircle(Color(SignalScale.colorOfDbm(rssi)), radius = 1.6.dp.toPx(), center = Offset(x, y))
            }
        }
    }

    // ---- mesh nodes: one square per node, coloured by the link the way the phone colours it ----
    mesh.forEach { node ->
        val lat = node.lat ?: return@forEach
        val lon = node.lon ?: return@forEach
        if (!place(lat, lon, 0.0)) return@forEach
        val at = Offset(pt[0], pt[1])
        val color = Color(SignalScale.colorOfScore(node.score))
        drawRect(color, topLeft = Offset(at.x - 2.5f, at.y - 2.5f), size = Size(5f, 5f))
        // A relayed node is drawn open: it is on the mesh, but not on the air this desk can hear.
        if (node.hops > 0) {
            drawCircle(color.copy(alpha = 0.45f), radius = 5.5.dp.toPx(), center = at, style = Stroke(1f))
        }
    }

    // ---- drones: a triangle along its track, plus the operator it is broadcasting ----
    drones.forEach { drone ->
        val lat = drone.lat ?: return@forEach
        val lon = drone.lon ?: return@forEach
        if (!place(lat, lon, 0.0)) return@forEach
        val at = Offset(pt[0], pt[1])
        val heading = Math.toRadians(drone.headingDeg ?: 0.0)
        val nose = 6.5.dp.toPx()
        val wing = 4.0.dp.toPx()
        val tri = Path().apply {
            moveTo(at.x + cos(heading).toFloat() * nose, at.y + sin(heading).toFloat() * nose)
            lineTo(at.x + cos(heading + 2.5).toFloat() * wing, at.y + sin(heading + 2.5).toFloat() * wing)
            lineTo(at.x + cos(heading - 2.5).toFloat() * wing, at.y + sin(heading - 2.5).toFloat() * wing)
            close()
        }
        drawPath(tri, Desk.warn)
    }

    // ---- aircraft ----
    aircraft.forEach { plane ->
        val lift = (ln(1.0 + ((plane.altFt ?: 0) * 0.3048) / 500.0) * 0.022).coerceIn(0.0, 0.30)
        if (!place(plane.lat, plane.lon, lift)) return@forEach
        val at = Offset(pt[0], pt[1])
        if (t > 0.5f && place(plane.lat, plane.lon, 0.0)) {
            drawLine(Desk.text.copy(alpha = 0.18f), Offset(pt[0], pt[1]), at, 1f)
        }
        drawCircle(Desk.text, radius = 1.8.dp.toPx(), center = at)
        val heading = Math.toRadians(plane.trackDeg)
        drawLine(
            Desk.text.copy(alpha = 0.8f),
            at,
            Offset(at.x + (cos(heading) * 6).toFloat(), at.y + (sin(heading) * 6).toFloat()),
            strokeWidth = 1.2.dp.toPx(),
        )
    }

    // ---- satellites, lifted by their altitude on the ball and flat on the map ----
    // Above a few thousand the catalogue is sampled rather than drawn whole, the way the phone's
    // map samples it: the count stays the true total, and the picture stays a picture. The step is
    // taken in catalogue order, so the same satellites are plotted on every tick instead of a
    // different random subset popping in and out.
    // The subset is picked by hashing each satellite's catalogue number, not by taking every nth
    // one. A constellation is evenly phased, so a stride samples it evenly and the marks line up in
    // rows - a checkered sheet laid over the globe. A hash scatters them, and because the hash is
    // fixed the same satellites are plotted on every tick instead of blinking in and out.
    // The step is quantised to a power of two. A plain size/450 moved every time the catalogue
    // changed by a single satellite, and each move re-picked the whole subset at once, so the grid
    // of marks blinked out and back. A doubling boundary is crossed once in a very long while.
    val every = if (sats.size <= 3_000) 1 else Integer.highestOneBit((sats.size / 450).coerceAtLeast(1))
    // The line down to the ground is what makes the ball read as a shell rather than a sticker,
    // but one per satellite crossed into a lattice of wires that looked like a checkered texture
    // laid over the map. A share of them carries the same sense of depth with no visible grid.
    val tetherStep = if (every > 1) 5 else 1
    sats.forEach { sat ->
        if (every > 1 && Math.floorMod(sat.noradId.hashCode(), every) != 0) return@forEach
        val lift = (ln(1.0 + sat.altKm * 1000.0 / 500.0) * 0.022).coerceIn(0.0, 0.30)
        if (!place(sat.lat, sat.lon, lift)) return@forEach
        val at = Offset(pt[0], pt[1])
        if (t > 0.5f && Math.floorMod(sat.noradId.hashCode(), every * tetherStep) == 0 &&
            place(sat.lat, sat.lon, 0.0)
        ) {
            drawLine(Desk.cyan.copy(alpha = 0.18f), Offset(pt[0], pt[1]), at, 1f)
        }
        // A satellite reads as a satellite rather than as a dot: the layer glyph's body and two
        // wings. Fading towards the limb keeps the marks near the edge from drawing a hard ring of
        // identical squares, which is what made the sky look tiled.
        val face = pt[2].coerceIn(0f, 1f)
        drawSatGlyph(at, 6.dp.toPx(), Desk.cyan.copy(alpha = 0.45f + 0.55f * face))
    }
}

/**
 * The satellite mark, copied from the phone's marker: a flat body with two solar wings and one
 * notch. Square-on, no shading - a few hundred of these are on the ball at once.
 */
private fun DrawScope.drawSatGlyph(at: Offset, size: Float, color: Color) {
    val r = size * 0.42f
    val wingW = r * 1.5f
    val wingH = r * 0.66f
    val bodyW = r * 0.9f
    val bodyH = r * 1.1f
    drawRect(color, Offset(at.x - bodyW / 2f - wingW, at.y - wingH / 2f), Size(wingW, wingH))
    drawRect(color, Offset(at.x + bodyW / 2f, at.y - wingH / 2f), Size(wingW, wingH))
    drawRoundRect(
        color = color,
        topLeft = Offset(at.x - bodyW / 2f, at.y - bodyH / 2f),
        size = Size(bodyW, bodyH),
        cornerRadius = CornerRadius(r * 0.16f),
    )
    drawRect(
        color = color.copy(alpha = color.alpha * 0.45f),
        topLeft = Offset(at.x - bodyW * 0.22f, at.y - bodyH * 0.16f),
        size = Size(bodyW * 0.44f, bodyH * 0.32f),
    )
}

/** The sphere only: ocean, graticule, continents, rim. Markers are drawn by the caller. */
private fun DrawScope.drawGlobeSphere(
    rings: Array<FloatArray>,
    camLat: Double,
    camLon: Double,
    width: Float,
    height: Float,
    radius: Float,
    alpha: Float,
) {
    // The phone's renderer itself, not a copy of its look: one sphere implementation, so the desk
    // and the handset cannot drift apart again. The scratch geometry is kept between frames
    // because this runs while the globe spins.
    drawGlobeWorld(
        rings = rings,
        alpha = alpha,
        centerLat = camLat,
        centerLon = camLon,
        center = Offset(width * 0.5f, height * 0.5f),
        radius = radius,
        tiltDeg = 20.0,
        ink = globeInk,
    )
}

private val globeInk = GlobeInk()

