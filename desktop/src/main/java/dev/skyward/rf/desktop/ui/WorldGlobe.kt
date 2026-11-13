package dev.skyward.rf.desktop.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
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
 * Copied from the phone's renderer (app/ui/WorldOutline.kt) so the desk and the handset draw the
 * globe from one implementation instead of two that drift apart.
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
    internal var depth: FloatArray = FloatArray(512)
    internal var front: BooleanArray = BooleanArray(512)
    /**
     * sin/cos of every land vertex, four floats per point. The coastline does not move, so the
     * sines are computed once and the frame only rotates the longitude: that takes every
     * trigonometry call out of the per-point path, which is where a spinning globe spends its
     * frame. [trigFor] is the ring array the table was built from, matched by identity so a
     * different world rebuilds it.
     */
    internal var trigFor: Array<FloatArray>? = null
    internal var trig: Array<FloatArray> = emptyArray()
    /**
     * Land sampled once from the coastline rings, four floats per point: cos(lat), sin(lat),
     * cos(lon), sin(lon). See [landCloud] for why the fill is sampled rather than filled.
     */
    internal var cloudFor: Array<FloatArray>? = null
    internal var cloud: FloatArray = FloatArray(0)
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

    // Built once per world: see [GlobeInk.trig]. Rebuilding it every frame meant two sin/cos per
    // vertex per frame for geometry that never changes.
    if (ink.trigFor !== rings) {
        ink.trig = Array(rings.size) { i ->
            val ring = rings[i]
            val n = ring.size / 2
            val t = FloatArray(n * 4)
            for (k in 0 until n) {
                val la = Math.toRadians(ring[2 * k + 1].toDouble())
                val lo = Math.toRadians(ring[2 * k].toDouble())
                t[4 * k] = cos(la).toFloat()
                t[4 * k + 1] = sin(la).toFloat()
                t[4 * k + 2] = cos(lo).toFloat()
                t[4 * k + 3] = sin(lo).toFloat()
            }
            t
        }
        ink.trigFor = rings
    }
    val lonCos0 = cos(lon0)
    val lonSin0 = sin(lon0)

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

    val sinLat0F = sinLat0.toFloat()
    val cosLat0F = cosLat0.toFloat()
    val lonCos0F = lonCos0.toFloat()
    val lonSin0F = lonSin0.toFloat()
    val cosTiltF = cosTilt.toFloat()
    val sinTiltF = sinTilt.toFloat()
    if (ink.cloudFor !== rings) {
        ink.cloud = landCloud(rings)
        ink.cloudFor = rings
    }
    val cloud = ink.cloud

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
        var rd = ink.depth
        var rf = ink.front
        // Far enough past the rim that every closing edge is clipped, close enough that the
        // edge from a crossing point stays within a pixel of the limb.
        // A vertex in front of the camera: past this the projection mirrors, so it is behind.
        val HORIZON = 0.02f

        // The same maths as [project], with the sines taken from the cache and the longitude
        // rotation folded into two constants. The ten thousand coastline vertices are the
        // frame's real cost on the globe, so this is the one loop worth hand-optimising.
        fun projectIndexed(t: FloatArray, k: Int) {
            val i = k * 4
            val cLat = t[i]
            val sLat = t[i + 1]
            val cosD = t[i + 2] * lonCos0 + t[i + 3] * lonSin0
            val sinD = t[i + 3] * lonCos0 - t[i + 2] * lonSin0
            val cosc = sinLat0 * sLat + cosLat0 * cLat * cosD
            val y3 = cosLat0 * sLat - sinLat0 * cLat * cosD
            pt[2] = (y3 * sinTilt + cosc * cosTilt).toFloat()
            pt[0] = (center.x + radius * (cLat * sinD)).toFloat()
            pt[1] = (center.y - radius * (y3 * cosTilt - cosc * sinTilt)).toFloat()
        }

        for (ri in rings.indices) {
            val ring = rings[ri]
            val tri = ink.trig[ri]
            val n = ring.size / 2
            if (n < 3) continue
            if (rx.size < n) {
                rx = FloatArray(n)
                ry = FloatArray(n)
                rd = FloatArray(n)
                rf = BooleanArray(n)
                ink.x = rx
                ink.y = ry
                ink.depth = rd
                ink.front = rf
            }
            var front = 0
            for (k in 0 until n) {
                projectIndexed(tri, k)
                // Screen position and facing of every vertex are kept as they are: the fill clips
                // the ring against the horizon itself, so it needs the real point rather than one
                // pushed out past the rim.
                rx[k] = pt[0]
                ry[k] = pt[1]
                rd[k] = pt[2]
                if (pt[2] > HORIZON) front++
            }
            if (front == 0) continue
            for (k in 0 until n) rf[k] = rd[k] > HORIZON

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
        // The land, as points sampled once from the rings.
        //
        // Filling the rings as polygons needs a closure where a ring crosses the limb, and
        // every closure on offer - pushing hidden points outside the disc, an arc along the rim,
        // a chord, a threshold on how far apart the crossings are - flips side as the crossings
        // move. Each flip swapped blocks of land and water, which is the alternating pattern.
        // A sample is land or it is not, so there is nothing to choose and nothing to flip; the
        // only motion is the cells turning, and at half a degree that is under two pixels.
        if (cloud.isNotEmpty()) {
            val marks = ArrayList<Offset>(cloud.size / 4)
            var i = 0
            while (i < cloud.size) {
                val cLat = cloud[i]
                val sLat = cloud[i + 1]
                val cLon = cloud[i + 2]
                val sLon = cloud[i + 3]
                i += 4
                val cosD = cLon * lonCos0F + sLon * lonSin0F
                val sinD = sLon * lonCos0F - cLon * lonSin0F
                val cosc = sinLat0F * sLat + cosLat0F * cLat * cosD
                val y3 = cosLat0F * sLat - sinLat0F * cLat * cosD
                if (y3 * sinTiltF + cosc * cosTiltF <= 0f) continue
                marks.add(
                    Offset(
                        center.x + radius * (cLat * sinD),
                        center.y - radius * (y3 * cosTiltF - cosc * sinTiltF),
                    ),
                )
            }
            drawPoints(
                points = marks,
                pointMode = PointMode.Points,
                color = Color(LAND_MID),
                strokeWidth = radius * 0.0102f,
                cap = StrokeCap.Square,
                alpha = alpha,
            )
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

/**
 * Land sampled on a half-degree grid, as unit-sphere cosines and sines.
 *
 * Each row of latitude is crossed against every ring edge and filled even-odd, which is exact for
 * the bundled coastline and costs one pass over the geometry; the frame then only projects the
 * points. A cell centre is either inside the land or outside it, so no closure is ever needed and
 * the fill cannot flip between frames.
 */
private fun landCloud(rings: Array<FloatArray>): FloatArray {
    val cellDeg = 0.5
    val rows = 360
    val out = ArrayList<Float>(160_000)
    var crossings = DoubleArray(1 shl 13)
    for (gy in 0 until rows) {
        val lat = -90.0 + (gy + 0.5) * cellDeg
        var count = 0
        for (ring in rings) {
            val n = ring.size / 2
            if (n < 3) continue
            var lonA = ring[(n - 1) * 2].toDouble()
            var latA = ring[(n - 1) * 2 + 1].toDouble()
            for (k in 0 until n) {
                val lonB = ring[k * 2].toDouble()
                val latB = ring[k * 2 + 1].toDouble()
                if ((latA > lat) != (latB > lat)) {
                    val t = (lat - latA) / (latB - latA)
                    var lon = lonA + (lonB - lonA) * t
                    if (lon > 180.0) lon -= 360.0
                    if (lon < -180.0) lon += 360.0
                    if (count == crossings.size) crossings = crossings.copyOf(crossings.size * 2)
                    crossings[count++] = lon
                }
                lonA = lonB
                latA = latB
            }
        }
        if (count < 2) continue
        val sorted = crossings.copyOf(count)
        sorted.sort()
        val sinLat = Math.sin(Math.toRadians(lat))
        val cosLat = Math.cos(Math.toRadians(lat))
        var i = 0
        while (i + 1 < count) {
            var lon = sorted[i]
            val to = sorted[i + 1]
            while (lon < to) {
                val lonR = Math.toRadians(lon)
                out.add(cosLat.toFloat())
                out.add(sinLat.toFloat())
                out.add(Math.cos(lonR).toFloat())
                out.add(Math.sin(lonR).toFloat())
                lon += cellDeg
            }
            i += 2
        }
    }
    val arr = FloatArray(out.size)
    for (i in out.indices) arr[i] = out[i]
    return arr
}
