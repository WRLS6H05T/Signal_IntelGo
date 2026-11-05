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

/**
 * The sphere's Paths and buffers, kept between frames.
 *
 * The globe is drawn every frame while it spins, and each of those frames needs four paths that
 * are discarded a moment later. Holding them here means the draw loop resets instead of
 * allocating, which is the difference between smooth and a stutter on a mid-range phone.
 */
class GlobeInk {
    internal val disc: Path = Path()
    internal val grid: Path = Path()
    internal val land: Path = Path()
    internal val coast: Path = Path()
    internal var x: FloatArray = FloatArray(512)
    internal var y: FloatArray = FloatArray(512)
    internal var front: BooleanArray = BooleanArray(512)
}

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
    /** Same tilt the markers use: the disc and the icons have to rotate together. */
    tiltDeg: Double = 0.0,
    /** Reused geometry; a fresh one is only created by callers outside the draw loop. */
    ink: GlobeInk = GlobeInk(),
) {
    if (alpha <= 0.02f || radius <= 2f) return

    val sinLat0 = sin(Math.toRadians(centerLat))
    val cosLat0 = cos(Math.toRadians(centerLat))
    val lon0 = Math.toRadians(centerLon)
    val cosTilt = cos(Math.toRadians(tiltDeg))
    val sinTilt = sin(Math.toRadians(tiltDeg))

    // Single 3-slot buffer: x, y, facing. Projecting a point allocates nothing.
    val pt = FloatArray(3)
    fun project(latDeg: Double, lonDeg: Double) {
        val la = Math.toRadians(latDeg)
        val dLon = Math.toRadians(lonDeg) - lon0
        val cosLat = cos(la)
        val cosc = sinLat0 * sin(la) + cosLat0 * cosLat * cos(dLon)
        // Rotate about the screen's horizontal axis before projecting, which is what tips the
        // poles toward the viewer. Facing is the rotated z, or land near the limb would be
        // drawn as if it were still on the near side.
        val y3 = cosLat0 * sin(la) - sinLat0 * cosLat * cos(dLon)
        pt[2] = (y3 * sinTilt + cosc * cosTilt).toFloat()
        pt[0] = (center.x + radius * (cosLat * sin(dLon))).toFloat()
        pt[1] = (center.y - radius * (y3 * cosTilt - cosc * sinTilt)).toFloat()
    }

    // Evenly lit disc. The old radial highlight - however subtly offset - read as a second,
    // smaller ball inside the globe, so the ocean is one flat tone now.
    drawCircle(color = Color(OCEAN_MID), radius = radius, center = center, alpha = alpha)

    val disc = ink.disc
    disc.reset()
    disc.addOval(Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius))

    clipPath(disc) {
        val coast = Color(COAST).copy(alpha = 0.8f * alpha)
        val coastWidth = 0.8.dp.toPx()



        // ---- water grid ----
        // Drawn before the land, so it reads as a graticule over open water only: the solid
        // landmass is painted straight over it.
        val gridPath = ink.grid
        gridPath.reset()
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
        // One filled polygon per ring, with every point that is behind the sphere pushed just
        // outside the disc and the ring closed out there, where the circular clip throws the
        // closure away. The old code instead guessed where a ring re-entered the visible
        // hemisphere and joined the two crossings with an arc along the rim, picking whichever
        // sweep was shorter. That guess is wrong the moment a visible run spans more than half
        // the horizon - which is most large landmasses as they turn past the limb - and a wrong
        // sweep fills the complement: land-coloured water and water-coloured land, meeting along
        // a hard edge that flickers into a different shape every frame as the globe rotates.
        // Closing outside the disc removes the guess: the only thing that can appear inside the
        // circle is the genuine near-side outline.
        val landPath = ink.land
        val coastPath = ink.coast
        landPath.reset()
        coastPath.reset()
        var rx = ink.x
        var ry = ink.y
        var rf = ink.front
        // Far enough past the rim that every closing edge is clipped, close enough that the
        // edge from a crossing point stays within a pixel of the limb.
        val outside = radius * 1.02f

        for (ring in rings) {
            val n = ring.size / 2
            if (n < 3) continue
            if (rx.size < n) {
                rx = FloatArray(n)
                ry = FloatArray(n)
                rf = BooleanArray(n)
                ink.x = rx
                ink.y = ry
                ink.front = rf
            }
            var front = 0
            var lastAngle = 0.0
            for (k in 0 until n) {
                project(ring[2 * k + 1].toDouble(), ring[2 * k].toDouble())
                val dx = pt[0] - center.x
                val dy = pt[1] - center.y
                val len = sqrt(dx * dx + dy * dy)
                val near = pt[2] > 0.02f
                rf[k] = near
                if (near) {
                    rx[k] = pt[0]
                    ry[k] = pt[1]
                    front++
                    if (len > 0.001f) lastAngle = atan2(dy.toDouble(), dx.toDouble())
                } else {
                    // Directly behind the centre the direction is undefined, so the previous
                    // point's angle is reused rather than letting a vertex snap to the middle.
                    val a = if (len > 0.001f) atan2(dy.toDouble(), dx.toDouble()) else lastAngle
                    rx[k] = center.x + outside * cos(a).toFloat()
                    ry[k] = center.y + outside * sin(a).toFloat()
                }
            }
            if (front == 0) continue

            landPath.reset()
            landPath.moveTo(rx[0], ry[0])
            for (k in 1 until n) landPath.lineTo(rx[k], ry[k])
            landPath.close()
            drawPath(landPath, Color(LAND_MID), alpha = alpha)

            // Coastline only where both ends of a segment are genuinely on the near side, so no
            // stroke ever runs along the clipped closure.
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

    // atmosphere: one thin rim only. The old wide outer glow ring read as a second globe.
    drawCircle(
        color = Color(RIM).copy(alpha = 0.28f * alpha),
        radius = radius,
        center = center,
        style = Stroke(width = 1.6.dp.toPx()),
    )
}
