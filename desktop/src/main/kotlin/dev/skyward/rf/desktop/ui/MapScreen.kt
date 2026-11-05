package dev.skyward.rf.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyward.rf.core.Geo
import dev.skyward.rf.core.LatLon
import dev.skyward.rf.core.SatView
import dev.skyward.rf.core.SignalScale
import dev.skyward.rf.wardrive.WardriveSession
import dev.skyward.rf.desktop.sky.SkyState
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh

/*
 * The map, on the desk - the same two views the phone has.
 *
 * Below [GLOBE_BELOW_ZOOM] the screen is the lit sphere, drawn by the same algorithm the phone
 * uses: one filled polygon per ring, with every point behind the ball pushed just outside the
 * disc and the ring closed out there, where the circular clip throws the closure away. Splitting
 * rings at the limb instead - which this file used to do - picks the wrong closure the moment a
 * visible run spans more than half the horizon, and fills the complement: land where water is,
 * water where land is, and whole continents gone.
 *
 * Above it, the same map the phone falls back to: OpenStreetMap tiles from a disk cache, panned
 * with a drag and zoomed with the wheel.
 */

private const val TILT_DEG = 20.0
private const val TILE = 256.0
private const val GLOBE_BELOW_ZOOM = 4.2

private const val OCEAN_MID = 0xFF08131E
private const val LAND_MID = 0xFF3C5F7A
private const val COAST = 0xFF86BCDF
private const val GRID_WATER = 0xFF24405A
private const val RIM = 0xFF5AC8FF

/** Altitude compression: true scale would make a 400 km orbit invisible. */
private fun liftFor(altKm: Double): Double {
    if (altKm <= 0.0) return 0.0
    return (ln(1.0 + altKm * 1000.0 / 500.0) * 0.022).coerceIn(0.0, 0.30)
}

private fun latFromNorm(ny: Double): Double =
    Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * ny)))).coerceIn(-84.0, 84.0)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MapScreen(
    sky: SkyState,
    observer: LatLon?,
    land: Array<FloatArray>,
    tiles: TileStore,
    drive: WardriveSession? = null,
    spin: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var centerLat by remember { mutableStateOf(observer?.lat ?: 20.0) }
    var centerLon by remember { mutableStateOf(observer?.lon ?: -10.0) }
    var zoom by remember { mutableStateOf(2.6) }
    var dragging by remember { mutableStateOf(false) }

    // Pull the view onto the first fix the link produces, once.
    var followed by remember { mutableStateOf(false) }
    LaunchedEffect(observer) {
        if (!followed && observer != null) {
            centerLat = observer.lat
            centerLon = observer.lon
            followed = true
        }
    }

    val tileVersion by tiles.version.collectAsState()

    // A globe left alone should turn; dragging takes it, releasing gives it back.
    LaunchedEffect(dragging, zoom, spin) {
        while (!dragging && zoom < GLOBE_BELOW_ZOOM && spin) {
            withFrameNanos { }
            centerLon = ((centerLon + 0.02 + 180.0) % 360.0) - 180.0
        }
    }

    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize()
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    if (dy != 0f) zoom = (zoom - dy * 0.35).coerceIn(1.5, 17.0)
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, drag ->
                        change.consume()
                        if (zoom < GLOBE_BELOW_ZOOM) {
                            centerLon = ((centerLon - drag.x * 0.25 + 180.0) % 360.0) - 180.0
                            centerLat = (centerLat + drag.y * 0.2).coerceIn(-70.0, 70.0)
                        } else {
                            val worldPx = TILE * 2.0.pow(zoom)
                            val nx = Geo.lonToNorm(centerLon) - drag.x / worldPx
                            val ny = Geo.latToNorm(centerLat) - drag.y / worldPx
                            centerLon = ((nx - floor(nx)) * 360.0) - 180.0
                            centerLat = latFromNorm(ny.coerceIn(0.001, 0.999))
                        }
                    }
                },
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = min(size.width, size.height) * 0.42f
            if (zoom < GLOBE_BELOW_ZOOM) {
                drawGlobeWorld(land, centerLat, centerLon, center, radius)
                drawGlobeSats(sky.sats, centerLat, centerLon, center, radius)
                drawDriveGlobe(drive, centerLat, centerLon, center, radius)
            } else {
                drawTiles(tiles, centerLat, centerLon, zoom, tileVersion)
                drawMappedSats(sky.sats, centerLat, centerLon, zoom)
                drawDriveMap(drive, centerLat, centerLon, zoom)
            }
            drawObserver(observer, centerLat, centerLon, zoom)
        }

        Column(
            Modifier.align(Alignment.TopStart).padding(18.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Panel.copy(alpha = 0.9f))
                .border(1.dp, Line, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text("SIGNAL_INTELGO", color = Sky, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.height(3.dp))
            Row {
                Text("SAT ${sky.sats.size}", color = Sky, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(10.dp))
                Text(sky.detail, color = TextDim, fontSize = 11.sp)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "z %.1f  ·  %s".format(
                    zoom,
                    if (zoom < GLOBE_BELOW_ZOOM) "globe" else "tiles",
                ),
                color = TextDim,
                fontSize = 10.sp,
            )
            Text(
                if (observer != null) "observer %.4f, %.4f".format(observer.lat, observer.lon)
                else "no observer: waiting for a device fix",
                color = TextDim,
                fontSize = 10.sp,
            )
            if (drive != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "drive ${drive.name}  ·  ${drive.points.size} fixes",
                    color = Mint,
                    fontSize = 10.sp,
                )
            }
        }

        Text(
            "drag to pan  ·  wheel to zoom",
            color = TextDim.copy(alpha = 0.7f),
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(18.dp),
        )
    }
}

// ---------------- globe ----------------

/**
 * The sphere, exactly as the phone draws it.
 *
 * Kept point-for-point in step with `WorldOutline.drawGlobeWorld` because the closure trick is
 * not obvious and a "cleaner" rewrite is what produced missing continents: a ring is never split,
 * its back-side vertices are pushed outside the disc, and the disc clip does the rest.
 */
private fun DrawScope.drawGlobeWorld(
    rings: Array<FloatArray>,
    centerLat: Double,
    centerLon: Double,
    center: Offset,
    radius: Float,
    tiltDeg: Double = TILT_DEG,
) {
    if (radius <= 2f) return
    val sinLat0 = sin(Math.toRadians(centerLat))
    val cosLat0 = cos(Math.toRadians(centerLat))
    val lon0 = Math.toRadians(centerLon)
    val cosTilt = cos(Math.toRadians(tiltDeg))
    val sinTilt = sin(Math.toRadians(tiltDeg))

    val pt = FloatArray(3)
    fun project(latDeg: Double, lonDeg: Double) {
        val la = Math.toRadians(latDeg)
        val dLon = Math.toRadians(lonDeg) - lon0
        val cosLat = cos(la)
        val cosc = sinLat0 * sin(la) + cosLat0 * cosLat * cos(dLon)
        val y3 = cosLat0 * sin(la) - sinLat0 * cosLat * cos(dLon)
        pt[2] = (y3 * sinTilt + cosc * cosTilt).toFloat()
        pt[0] = (center.x + radius * (cosLat * sin(dLon))).toFloat()
        pt[1] = (center.y - radius * (y3 * cosTilt - cosc * sinTilt)).toFloat()
    }

    // One flat tone: a radial highlight reads as a second, smaller ball inside the globe.
    drawCircle(color = Color(OCEAN_MID), radius = radius, center = center)

    val disc = Path().apply {
        addOval(Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius))
    }

    clipPath(disc) {
        val coast = Color(COAST).copy(alpha = 0.8f)
        val coastWidth = 0.8.dp.toPx()

        // Water grid, drawn under the land so it only shows over open ocean.
        val gridPath = Path()
        fun gridLine(latA: Double, lonA: Double, latB: Double, lonB: Double, steps: Int) {
            var open = false
            for (step in 0..steps) {
                val f = step.toDouble() / steps
                project(latA + (latB - latA) * f, lonA + (lonB - lonA) * f)
                if (pt[2] > 0.02f) {
                    if (open) gridPath.lineTo(pt[0], pt[1]) else { gridPath.moveTo(pt[0], pt[1]); open = true }
                } else {
                    open = false
                }
            }
        }
        var meridian = -180.0
        while (meridian <= 180.0) {
            gridLine(-84.0, meridian, 84.0, meridian, 56)
            meridian += 30.0
        }
        var parallel = -60.0
        while (parallel <= 60.0) {
            gridLine(parallel, -180.0, parallel, 180.0, 96)
            parallel += 30.0
        }
        drawPath(gridPath, Color(GRID_WATER).copy(alpha = 0.5f), style = Stroke(width = 0.5.dp.toPx()))

        val landPath = Path()
        val coastPath = Path()
        var rx = FloatArray(512)
        var ry = FloatArray(512)
        var rf = BooleanArray(512)
        val outside = radius * 1.02f

        for (ring in rings) {
            val n = ring.size / 2
            if (n < 3) continue
            if (rx.size < n) {
                rx = FloatArray(n)
                ry = FloatArray(n)
                rf = BooleanArray(n)
            }
            var front = 0
            var lastAngle = 0.0
            for (k in 0 until n) {
                project(ring[2 * k + 1].toDouble(), ring[2 * k].toDouble())
                val dx = pt[0] - center.x
                val dy = pt[1] - center.y
                val len = kotlin.math.sqrt(dx * dx + dy * dy)
                val near = pt[2] > 0.02f
                rf[k] = near
                if (near) {
                    rx[k] = pt[0]
                    ry[k] = pt[1]
                    front++
                    if (len > 0.001f) lastAngle = Math.atan2(dy.toDouble(), dx.toDouble())
                } else {
                    val a = if (len > 0.001f) Math.atan2(dy.toDouble(), dx.toDouble()) else lastAngle
                    rx[k] = center.x + outside * cos(a).toFloat()
                    ry[k] = center.y + outside * sin(a).toFloat()
                }
            }
            if (front == 0) continue

            landPath.reset()
            landPath.moveTo(rx[0], ry[0])
            for (k in 1 until n) landPath.lineTo(rx[k], ry[k])
            landPath.close()
            drawPath(landPath, Color(LAND_MID))

            var pen = false
            for (k in 0 until n) {
                val m = (k + 1) % n
                if (rf[k] && rf[m]) {
                    if (!pen) {
                        coastPath.moveTo(rx[k], ry[k])
                        pen = true
                    }
                    coastPath.lineTo(rx[m], ry[m])
                } else {
                    pen = false
                }
            }
        }
        drawPath(coastPath, coast, style = Stroke(width = coastWidth))
    }

    drawCircle(Color(RIM).copy(alpha = 0.28f), radius = radius, center = center, style = Stroke(width = 1.6.dp.toPx()))
}

private fun DrawScope.drawGlobeSats(
    sats: List<SatView>,
    centerLat: Double,
    centerLon: Double,
    center: Offset,
    radius: Float,
) {
    val sinLat0 = sin(Math.toRadians(centerLat))
    val cosLat0 = cos(Math.toRadians(centerLat))
    val lon0 = Math.toRadians(centerLon)
    val cosTilt = cos(Math.toRadians(TILT_DEG))
    val sinTilt = sin(Math.toRadians(TILT_DEG))
    for (sat in sats) {
        val lift = liftFor(sat.altKm)
        val la = Math.toRadians(sat.lat.coerceIn(-89.9, 89.9))
        val dLon = Math.toRadians(sat.lon) - lon0
        val cosLat = cos(la)
        val cosc = sinLat0 * sin(la) + cosLat0 * cosLat * cos(dLon)
        val y3 = cosLat0 * sin(la) - sinLat0 * cosLat * cos(dLon)
        val front = y3 * sinTilt + cosc * cosTilt
        if (front <= 0.02) continue
        val k = radius * (1.0 + lift)
        val x = (center.x + k * (cosLat * sin(dLon))).toFloat()
        val y = (center.y - k * (y3 * cosTilt - cosc * sinTilt)).toFloat()
        val body = Color(0xFF38BDF8)
        drawCircle(body, radius = 1.7f, center = Offset(x, y))
        drawLine(body.copy(alpha = 0.75f), Offset(x - 4f, y), Offset(x - 1.8f, y), strokeWidth = 1f)
        drawLine(body.copy(alpha = 0.75f), Offset(x + 1.8f, y), Offset(x + 4f, y), strokeWidth = 1f)
    }
}

// ---------------- flat map ----------------

private fun DrawScope.drawTiles(tiles: TileStore, centerLat: Double, centerLon: Double, zoom: Double, version: Int) {
    // `version` is read only so a finished tile load invalidates this canvas.
    val z = zoom.toInt().coerceIn(2, 19)
    val scaled = TILE * 2.0.pow(zoom - z)
    val worldPx = TILE * 2.0.pow(z.toDouble())
    val count = 1 shl z
    val centerX = Geo.lonToNorm(centerLon) * worldPx
    val centerY = Geo.latToNorm(centerLat) * worldPx
    val halfW = size.width / 2.0
    val halfH = size.height / 2.0

    val minX = floor((centerX - halfW) / scaled).toInt()
    val maxX = floor((centerX + halfW) / scaled).toInt()
    val minY = floor((centerY - halfH) / scaled).toInt()
    val maxY = floor((centerY + halfH) / scaled).toInt()
    val sizePx = scaled.roundToInt().coerceAtLeast(1)

    for (tx in minX..maxX) {
        for (ty in minY..maxY) {
            if (ty < 0 || ty >= count) continue
            val wrapped = ((tx % count) + count) % count
            val bitmap = tiles.cached(z, wrapped, ty)
            if (bitmap != null) {
                drawImage(
                    image = bitmap,
                    dstOffset = IntOffset(
                        (tx * scaled - centerX + halfW).roundToInt(),
                        (ty * scaled - centerY + halfH).roundToInt(),
                    ),
                    dstSize = IntSize(sizePx, sizePx),
                )
            } else {
                tiles.request(z, wrapped, ty)
            }
        }
    }
    if (version < 0) return
}

private fun DrawScope.drawMappedSats(sats: List<SatView>, centerLat: Double, centerLon: Double, zoom: Double) {
    val worldPx = TILE * 2.0.pow(zoom)
    val cx = Geo.lonToNorm(centerLon) * worldPx
    val cy = Geo.latToNorm(centerLat) * worldPx
    for (sat in sats) {
        val x = Geo.lonToNorm(sat.lon) * worldPx - cx + size.width / 2.0
        val y = Geo.latToNorm(sat.lat) * worldPx - cy + size.height / 2.0
        if (x < -20 || y < -20 || x > size.width + 20 || y > size.height + 20) continue
        val body = Color(0xFF38BDF8)
        drawCircle(body, radius = 2.2f, center = Offset(x.toFloat(), y.toFloat()))
    }
}

private fun DrawScope.drawObserver(observer: LatLon?, centerLat: Double, centerLon: Double, zoom: Double) {
    if (observer == null) return
    if (zoom < GLOBE_BELOW_ZOOM) return
    val worldPx = TILE * 2.0.pow(zoom)
    val cx = Geo.lonToNorm(centerLon) * worldPx
    val cy = Geo.latToNorm(centerLat) * worldPx
    val x = (Geo.lonToNorm(observer.lon) * worldPx - cx + size.width / 2.0).toFloat()
    val y = (Geo.latToNorm(observer.lat) * worldPx - cy + size.height / 2.0).toFloat()
    drawCircle(Color(0xFF5BE49B), radius = 3.5f, center = Offset(x, y))
    drawCircle(Color(0xFF5BE49B).copy(alpha = 0.7f), radius = 10f, center = Offset(x, y), style = Stroke(width = 1.2f))
}

// ---------------- drive overlay ----------------

/**
 * A recorded drive on the sphere: the track the phone draws on its globe.
 *
 * Each segment takes the colour of the fix that ended it, on the same 5-bin red->green scale, so
 * a drive reads at a glance: green where the radios were loud, red where they went quiet.
 *
 * The projection is the globe's, and a segment whose end is over the horizon is dropped rather
 * than clipped - drawing it to a pushed-out point would paint a line across the back of the ball.
 */
private fun DrawScope.drawDriveGlobe(
    session: WardriveSession?,
    centerLat: Double,
    centerLon: Double,
    center: Offset,
    radius: Float,
) {
    if (session == null || session.points.isEmpty()) return
    val sinLat0 = sin(Math.toRadians(centerLat))
    val cosLat0 = cos(Math.toRadians(centerLat))
    val lon0 = Math.toRadians(centerLon)
    val cosTilt = cos(Math.toRadians(TILT_DEG))
    val sinTilt = sin(Math.toRadians(TILT_DEG))
    val disc = Path().apply {
        addOval(Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius))
    }
    clipPath(disc) {
        var prev: Offset? = null
        for (point in session.points) {
            val la = Math.toRadians(point.lat)
            val dLon = Math.toRadians(point.lon) - lon0
            val cosLat = cos(la)
            val cosc = sinLat0 * sin(la) + cosLat0 * cosLat * cos(dLon)
            val y3 = cosLat0 * sin(la) - sinLat0 * cosLat * cos(dLon)
            val front = y3 * sinTilt + cosc * cosTilt
            if (front <= 0.02) {
                prev = null
                continue
            }
            val current = Offset(
                (center.x + radius * (cosLat * sin(dLon))).toFloat(),
                (center.y - radius * (y3 * cosTilt - cosc * sinTilt)).toFloat(),
            )
            val from = prev
            val colour = Color(SignalScale.binArgb[SignalScale.binOfScore(point.score)])
            if (from != null) drawLine(colour, from, current, strokeWidth = 2.6f)
            drawCircle(colour, radius = 2.2f, center = current)
            prev = current
        }
    }
}

/** A recorded drive on the flat map, in the map's own pixel space. */
private fun DrawScope.drawDriveMap(
    session: WardriveSession?,
    centerLat: Double,
    centerLon: Double,
    zoom: Double,
) {
    if (session == null || session.points.isEmpty()) return
    val worldPx = TILE * 2.0.pow(zoom)
    val originX = Geo.lonToNorm(centerLon) * worldPx
    val originY = Geo.latToNorm(centerLat) * worldPx
    var prev: Offset? = null
    for (point in session.points) {
        val current = Offset(
            (Geo.lonToNorm(point.lon) * worldPx - originX + size.width / 2.0).toFloat(),
            (Geo.latToNorm(point.lat) * worldPx - originY + size.height / 2.0).toFloat(),
        )
        val colour = Color(SignalScale.binArgb[SignalScale.binOfScore(point.score)])
        val from = prev
        if (from != null) drawLine(colour, from, current, strokeWidth = 3.2f)
        prev = current
    }
}
