package dev.skyward.rf.ui

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Low-zoom basemap.
 *
 * Raster tiles are useless once you are looking at a whole hemisphere, so below roughly 5x
 * they give way to a lit sphere: a radial-gradient ocean, every landmass filled with the same
 * curved shading, and a 15-degree graticule. The outline is Natural Earth 110m land,
 * quantised to 0.01 degrees and bundled as a small text asset (no network, no mapping SDK).
 *
 * Asset format: a header line, then one ring per line as `lonC latC lonC latC ...` in
 * hundredths of a degree.
 *
 * Everything here is allocation-free per point on purpose. The whole catalogue is on screen at
 * globe zoom, so a per-point FloatArray or a draw call per grid segment is enough to make the
 * renderer thrash: one scratch buffer, one path per ink colour.
 */

private const val OCEAN_LIT = 0xFF12283F
private const val OCEAN_MID = 0xFF08131E
private const val OCEAN_DARK = 0xFF04090F
private const val LAND_LIT = 0xFF2C4C68
private const val LAND_MID = 0xFF3C5F7A
private const val LAND_DARK = 0xFF0D1B29
private const val COAST = 0xFF86BCDF
private const val GRID_WATER = 0xFF24405A
private const val RIM = 0xFF5AC8FF

object WorldOutline {

    const val ASSET = "world_land.txt"

    private var rings: Array<FloatArray> = emptyArray()

    @Volatile
    private var loaded = false

    fun load(context: Context): Array<FloatArray> {
        if (loaded) return rings
        synchronized(this) {
            if (loaded) return rings
            rings = runCatching {
                context.assets.open(ASSET).bufferedReader().useLines { lines ->
                    val out = ArrayList<FloatArray>(160)
                    var lineNo = 0
                    for (raw in lines) {
                        lineNo++
                        if (lineNo == 1) continue // header
                        val text = raw.trim()
                        if (text.length < 11) continue
                        val parts = text.split(' ')
                        if (parts.size < 6 || parts.size % 2 != 0) continue
                        val arr = FloatArray(parts.size)
                        var ok = true
                        for (i in parts.indices) {
                            val v = parts[i].toFloatOrNull()
                            if (v == null) {
                                ok = false
                                break
                            }
                            arr[i] = v / 100f
                        }
                        if (ok) out.add(arr)
                    }
                    out.toTypedArray()
                }
            }.getOrDefault(emptyArray())
            loaded = true
        }
        return rings
    }

    fun ringCount(): Int = rings.size
}

/**
 * Draws the sphere: ocean, graticule, continents and an atmospheric rim.
 *
 * [alpha] fades the whole thing in as the map turns from flat to ball, so no geometry ever
 * pops. Land on the far hemisphere is dropped instead of being drawn through the globe.
 */
fun DrawScope.drawGlobeWorld(
    rings: Array<FloatArray>,
    alpha: Float,
    centerLat: Double,
    centerLon: Double,
    center: Offset,
    radius: Float,
) {
    if (alpha <= 0.02f || radius <= 2f) return

    val sinLat0 = sin(Math.toRadians(centerLat))
    val cosLat0 = cos(Math.toRadians(centerLat))
    val lon0 = Math.toRadians(centerLon)

    // Single 3-slot buffer: x, y, facing. Projecting a point allocates nothing.
    val pt = FloatArray(3)
    fun project(latDeg: Double, lonDeg: Double) {
        val la = Math.toRadians(latDeg)
        val dLon = Math.toRadians(lonDeg) - lon0
        val cosLat = cos(la)
        val cosc = sinLat0 * sin(la) + cosLat0 * cosLat * cos(dLon)
        pt[2] = cosc.toFloat()
        pt[0] = (center.x + radius * (cosLat * sin(dLon))).toFloat()
        pt[1] = (center.y - radius * (cosLat0 * sin(la) - sinLat0 * cosLat * cos(dLon))).toFloat()
    }

    // Evenly lit disc. The old radial highlight - however subtly offset - read as a second,
    // smaller ball inside the globe, so the ocean is one flat tone now.
    drawCircle(color = Color(OCEAN_MID), radius = radius, center = center, alpha = alpha)

    val disc = Path().apply {
        addOval(Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius))
    }

    clipPath(disc) {
        val coast = Color(COAST).copy(alpha = 0.8f * alpha)
        val coastWidth = 0.8.dp.toPx()



        // ---- water grid ----
        // Drawn before the land, so it reads as a graticule over open water only: the solid
        // landmass is painted straight over it.
        val gridPath = Path()
        val gridTone = Color(GRID_WATER).copy(alpha = 0.5f * alpha)
        val gridWidth = 0.5.dp.toPx()

        fun gridLine(
            latA: Double,
            lonA: Double,
            latB: Double,
            lonB: Double,
            steps: Int,
        ) {
            var open = false
            for (step in 0..steps) {
                val f = step.toDouble() / steps
                project(latA + (latB - latA) * f, lonA + (lonB - lonA) * f)
                if (pt[2] > 0.02f) {
                    if (open) {
                        gridPath.lineTo(pt[0], pt[1])
                    } else {
                        gridPath.moveTo(pt[0], pt[1])
                        open = true
                    }
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
        drawPath(gridPath, gridTone, style = Stroke(width = gridWidth))

        // ---- continents ----
        //
        // Each ring is clipped to the visible hemisphere honestly: a contiguous run of
        // near-side points is filled on its own and closed along the horizon circle, never with
        // a straight chord. A ring entirely on the far side contributes nothing at all. That is
        // what used to flip the globe's colours as it turned: every far-side ring was clamped
        // onto the limb and closed across it, painting a lens of land-coloured ocean near the
        // rim, and the clamped out-and-back points made rings self-intersect so winding
        // cancelled in patches that appeared and vanished with the camera.
        val landPath = Path()
        val coastPath = Path()
        val discRect = Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)

        var rx = FloatArray(512)
        var ry = FloatArray(512)
        var rf = BooleanArray(512)

        fun emitRun(n: Int, rot: Int, from: Int, to: Int, whole: Boolean) {
            fun px(m: Int) = rx[(rot + m) % n]
            fun py(m: Int) = ry[(rot + m) % n]

            landPath.reset()
            if (whole) {
                // Fully visible: an ordinary closed polygon.
                landPath.moveTo(px(0), py(0))
                for (m in 1 until n) landPath.lineTo(px(m), py(m))
                landPath.close()
                drawPath(landPath, Color(LAND_MID), alpha = alpha)

                coastPath.moveTo(px(0), py(0))
                for (m in 1 until n) coastPath.lineTo(px(m), py(m))
                coastPath.close()
                return
            }

            // The run's two boundary points sit on the limb, so the arc meets them exactly.
            var x0 = px(from)
            var y0 = py(from)
            var dx = x0 - center.x
            var dy = y0 - center.y
            var len = sqrt(dx * dx + dy * dy)
            if (len > 0.001f) {
                x0 = center.x + dx / len * radius
                y0 = center.y + dy / len * radius
            }
            var x1 = px(to)
            var y1 = py(to)
            dx = x1 - center.x
            dy = y1 - center.y
            len = sqrt(dx * dx + dy * dy)
            if (len > 0.001f) {
                x1 = center.x + dx / len * radius
                y1 = center.y + dy / len * radius
            }

            val a0 = Math.toDegrees(
                atan2((y0 - center.y).toDouble(), (x0 - center.x).toDouble()),
            ).toFloat()
            val a1 = Math.toDegrees(
                atan2((y1 - center.y).toDouble(), (x1 - center.x).toDouble()),
            ).toFloat()
            // Short way round the rim: the hidden sliver of land is always the smaller sweep.
            var sweep = a1 - a0
            while (sweep > 180f) sweep -= 360f
            while (sweep < -180f) sweep += 360f

            landPath.moveTo(x0, y0)
            for (m in from until to) landPath.lineTo(px(m), py(m))
            landPath.lineTo(x1, y1)
            // Follow the horizon rather than cutting a chord across the disc.
            landPath.arcTo(discRect, a0, sweep, false)
            landPath.close()
            drawPath(landPath, Color(LAND_MID), alpha = alpha)

            coastPath.moveTo(px(from), py(from))
            for (m in from + 1..to) coastPath.lineTo(px(m), py(m))
        }

        for (ring in rings) {
            val n = ring.size / 2
            if (n < 3) continue
            if (rx.size < n) {
                rx = FloatArray(n)
                ry = FloatArray(n)
                rf = BooleanArray(n)
            }
            var front = 0
            for (k in 0 until n) {
                project(ring[2 * k + 1].toDouble(), ring[2 * k].toDouble())
                rx[k] = pt[0]
                ry[k] = pt[1]
                rf[k] = pt[2] > 0.02f
                if (rf[k]) front++
            }
            if (front == 0) continue
            if (front == n) {
                emitRun(n, 0, 0, n - 1, true)
                continue
            }
            // Begin at a run boundary, so no run wraps past the end of the array.
            var rot = -1
            for (k in 0 until n) {
                if (rf[k] && !rf[(k - 1 + n) % n]) {
                    rot = k
                    break
                }
            }
            if (rot < 0) continue
            var m = 0
            while (m < n) {
                if (!rf[(rot + m) % n]) {
                    m++
                    continue
                }
                val from = m
                while (m < n && rf[(rot + m) % n]) m++
                emitRun(n, rot, from, m - 1, false)
            }
        }
        drawPath(coastPath, coast, style = Stroke(width = coastWidth))
    }

    // atmosphere: one thin rim only. The old wide outer glow ring read as a second globe.
    drawCircle(
        color = Color(RIM).copy(alpha = 0.28f * alpha),
        radius = radius,
        center = center,
        style = Stroke(width = 1.6.dp.toPx()),
    )
}
