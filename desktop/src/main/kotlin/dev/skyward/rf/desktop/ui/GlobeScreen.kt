package dev.skyward.rf.desktop.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyward.rf.core.LatLon
import dev.skyward.rf.core.SatView
import dev.skyward.rf.desktop.sky.SkyState
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/*
 * The globe, on the desk.
 *
 * The geometry is the phone's: an orthographic sphere tipped 20 degrees toward the viewer, with
 * altitude compressed logarithmically so a low-orbit satellite floats clear of the ball and a
 * geostationary one stays on screen. What is different is only the input - a mouse drag instead
 * of a finger, and a slow automatic spin so a window left open keeps moving.
 */

private const val TILT_DEG = 20.0

/** Same compression as the phone: true scale would make altitude invisible. */
private fun liftFor(altKm: Double): Double {
    if (altKm <= 0.0) return 0.0
    return (ln(1.0 + altKm * 1000.0 / 500.0) * 0.022).coerceIn(0.0, 0.30)
}

/** One point on screen, plus how squarely it faces the viewer. */
private class Sphere(
    private val centerLat: Double,
    private val centerLon: Double,
    private val radius: Float,
    private val cx: Float,
    private val cy: Float,
) {
    private val sinLat0 = sin(Math.toRadians(centerLat))
    private val cosLat0 = cos(Math.toRadians(centerLat))
    private val lon0 = Math.toRadians(centerLon)
    private val cosTilt = cos(Math.toRadians(TILT_DEG))
    private val sinTilt = sin(Math.toRadians(TILT_DEG))

    /** Reused so drawing thousands of points does not allocate thousands of triples. */
    private val out = FloatArray(3)

    /** Returns x, y, front for the given ground point, pushed out by [lift]. */
    fun project(lat: Double, lon: Double, lift: Double = 0.0): FloatArray {
        val la = Math.toRadians(lat.coerceIn(-89.9, 89.9))
        val dLon = Math.toRadians(lon) - lon0
        val cosLat = cos(la)
        val cosc = sinLat0 * sin(la) + cosLat0 * cosLat * cos(dLon)
        val x3 = cosLat * sin(dLon)
        val y3 = cosLat0 * sin(la) - sinLat0 * cosLat * cos(dLon)
        val ty = y3 * cosTilt - cosc * sinTilt
        val tz = y3 * sinTilt + cosc * cosTilt
        val k = radius * (1.0 + lift)
        out[0] = (cx + k * x3).toFloat()
        out[1] = (cy - k * ty).toFloat()
        out[2] = tz.coerceIn(0.0, 1.0).toFloat()
        return out
    }
}

@Composable
fun GlobeScreen(
    sky: SkyState,
    observer: LatLon?,
    land: Array<FloatArray>,
    modifier: Modifier = Modifier,
) {
    var centerLon by remember { mutableStateOf(-10.0) }
    var centerLat by remember { mutableStateOf(18.0) }
    var spinning by remember { mutableStateOf(true) }

    LaunchedEffect(spinning) {
        while (spinning) {
            withFrameNanos { }
            centerLon = ((centerLon + 0.02 + 180.0) % 360.0) - 180.0
            // The frame clock only advances when something repaints; the state write above is
            // what keeps it moving.
        }
    }

    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { spinning = false },
                        onDragEnd = { spinning = true },
                    ) { change, drag ->
                        change.consume()
                        centerLon = ((centerLon - drag.x * 0.25 + 180.0) % 360.0) - 180.0
                        centerLat = (centerLat + drag.y * 0.2).coerceIn(-70.0, 70.0)
                    }
                },
        ) {
            drawGlobe(centerLat, centerLon, land, sky.sats, observer)
        }

        Column(
            Modifier.align(Alignment.TopStart).padding(18.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Panel.copy(alpha = 0.88f))
                .border(1.dp, Line, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                "SIGNAL_INTELGO",
                color = Sky,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.height(3.dp))
            Row {
                Text("SAT ${sky.sats.size}", color = Sky, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(10.dp))
                Text(sky.detail, color = TextDim, fontSize = 11.sp)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                if (observer != null) "observer %.3f, %.3f".format(observer.lat, observer.lon)
                else "no observer: waiting for a device fix",
                color = TextDim,
                fontSize = 10.sp,
            )
        }

        Text(
            "drag to spin",
            color = TextDim.copy(alpha = 0.7f),
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(18.dp),
        )
    }
}

private fun DrawScope.drawGlobe(
    centerLat: Double,
    centerLon: Double,
    land: Array<FloatArray>,
    sats: List<SatView>,
    observer: LatLon?,
) {
    val radius = min(size.width, size.height) * 0.44f
    val cx = size.width * 0.52f
    val cy = size.height * 0.52f
    val sphere = Sphere(centerLat, centerLon, radius, cx, cy)
    val center = Offset(cx, cy)

    // Ocean, lit from the upper left so the ball reads as a ball rather than a disc.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF16324C), Color(0xFF0A1622), Color(0xFF04090F)),
            center = Offset(cx - radius * 0.35f, cy - radius * 0.4f),
            radius = radius * 2.0f,
        ),
        radius = radius,
        center = center,
    )

    // Graticule: 15 degrees, faint, only on the near side.
    val grid = Color(0xFF24405A)
    for (lon in -180 until 180 step 15) {
        val path = meshPath(sphere) { lat -> sphere.project(lat, lon.toDouble()) }
        drawPath(path, grid, style = Stroke(width = 1f))
    }
    for (lat in -75..75 step 15) {
        val path = meshPath(sphere) { lon -> sphere.project(lat.toDouble(), lon) }
        drawPath(path, grid, style = Stroke(width = 1f))
    }

    // Land: one path per ring, broken at the limb so the far hemisphere never shows through.
    for (ring in land) {
        val path = Path()
        var open = false
        var i = 0
        while (i + 1 < ring.size) {
            val p = sphere.project(ring[i].toDouble(), ring[i + 1].toDouble())
            if (p[2] <= 0f) {
                open = false
            } else {
                if (open) path.lineTo(p[0], p[1]) else path.moveTo(p[0], p[1])
                open = true
            }
            i += 2
        }
        drawPath(path, Color(0xFF2C4C68))
        drawPath(path, Color(0xFF86BCDF).copy(alpha = 0.55f), style = Stroke(width = 1f))
    }

    // Atmosphere.
    drawCircle(color = Color(0xFF5AC8FF).copy(alpha = 0.35f), radius = radius, center = center, style = Stroke(width = 2f))

    // Satellites, lifted along their radius by altitude.
    for (sat in sats) {
        val lift = liftFor(sat.altKm)
        val p = sphere.project(sat.lat, sat.lon, lift)
        if (p[2] <= 0.02f) continue
        val x = p[0]
        val y = p[1]
        val body = Color(0xFF38BDF8)
        drawCircle(color = body, radius = 1.7f, center = Offset(x, y))
        drawLine(body.copy(alpha = 0.8f), Offset(x - 4f, y), Offset(x - 1.8f, y), strokeWidth = 1f)
        drawLine(body.copy(alpha = 0.8f), Offset(x + 1.8f, y), Offset(x + 4f, y), strokeWidth = 1f)
    }

    // The observer, if a device on the link has a fix.
    if (observer != null) {
        val p = sphere.project(observer.lat, observer.lon)
        if (p[2] > 0.05f) {
            drawCircle(Color(0xFF5BE49B), radius = 3f, center = Offset(p[0], p[1]))
            drawCircle(
                Color(0xFF5BE49B).copy(alpha = 0.7f),
                radius = 9f,
                center = Offset(p[0], p[1]),
                style = Stroke(width = 1.2f),
            )
        }
    }
}

/** Samples a line across the sphere, lifting the pen wherever it crosses the limb. */
private inline fun meshPath(sphere: Sphere, sample: (Double) -> FloatArray): Path {
    val path = Path()
    var open = false
    var v = -180.0
    while (v <= 180.0) {
        val p = sample(v)
        if (p[2] <= 0f) {
            open = false
        } else {
            if (open) path.lineTo(p[0], p[1]) else path.moveTo(p[0], p[1])
            open = true
        }
        v += 3.0
    }
    return path
}

/** Kept so the tooltip-free hint above can be measured against the ball. */
private fun distanceFromCentre(p: FloatArray, cx: Float, cy: Float): Float = hypot(p[0] - cx, p[1] - cy)
