package dev.skyward.rf.ui

import dev.skyward.rf.core.Geo
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/** Screen position of one entity, plus how much it faces the viewer and how high it sits. */
data class Projected(val x: Float, val y: Float, val front: Float, val lift: Float)

/**
 * The same four numbers, written into an object the caller keeps.
 *
 * The draw loop projects every entity on every frame. On a full sky that was a few hundred
 * `Projected` objects sixty times a second - tens of thousands a second, none of them outliving
 * the frame - and the collector's pauses were the regular dip in the globe's spin, arriving
 * sooner the more of the catalogue was plotted. A sink is allocated once and refilled in place.
 */
class ProjectedSink {
    var x: Float = 0f
    var y: Float = 0f
    var front: Float = 1f
    var lift: Float = 0f
}

/**
 * Blended projection: Web-Mercator while zoomed in, orthographic sphere when zoomed out.
 *
 * Instead of switching projections, every point is projected both ways and interpolated by
 * [t], so the flat map curls into the ball while the markers, path and observer ride with it.
 * Orthographic is the honest "ball" view: you see one hemisphere, and anything on the far
 * side has front = 0 and is dropped.
 */
class ViewProjection(
    private val flat: Projection,
    centerLat: Double,
    centerLon: Double,
    private val radius: Float,
    private val t: Float,
    private val tiltDeg: Double = 0.0,
) {
    private val cx = flat.width / 2f + flat.shiftX
    private val cy = flat.height / 2f
    private val cosTilt = cos(Math.toRadians(tiltDeg))
    private val sinTilt = sin(Math.toRadians(tiltDeg))
    private val sinLat0 = sin(Math.toRadians(centerLat))
    private val cosLat0 = cos(Math.toRadians(centerLat))
    private val lon0 = Math.toRadians(centerLon)

    fun surface(lat: Double, lon: Double): Projected = project(lat, lon, 0.0)

    /** Ground point pushed radially outward by the entity's altitude: the 3-D cue. */
    fun elevated(lat: Double, lon: Double, altM: Double?): Projected =
        project(lat, lon, liftFor(altM))

    /** [surface] into a sink: the draw loop may not allocate per entity per frame. */
    fun surfaceInto(lat: Double, lon: Double, out: ProjectedSink) = projectInto(lat, lon, 0.0, out)

    /** [elevated] into a sink. */
    fun elevatedInto(lat: Double, lon: Double, altM: Double?, out: ProjectedSink) =
        projectInto(lat, lon, liftFor(altM), out)

    /**
     * [elevatedInto] with the marker's Mercator position already computed.
     *
     * The draw loop calls this for every entity on every frame, and a marker's normalised
     * position never changes: re-deriving it per frame was a logarithm per icon per frame.
     */
    fun elevatedNormInto(lat: Double, nx: Double, ny: Double, altM: Double?, out: ProjectedSink) =
        projectNormInto(lat, nx, ny, liftFor(altM), out)

    private fun project(lat: Double, lon: Double, lift: Double): Projected {
        val out = ProjectedSink()
        projectInto(lat, lon, lift, out)
        return Projected(out.x, out.y, out.front, out.lift)
    }

    private fun projectInto(lat: Double, lon: Double, lift: Double, out: ProjectedSink) =
        projectNormInto(lat, Geo.lonToNorm(lon), Geo.latToNorm(lat.coerceIn(-85.0, 85.0)), lift, out)

    private fun projectNormInto(lat: Double, nx: Double, ny: Double, lift: Double, out: ProjectedSink) {
        val m = flat.toScreenNorm(nx, ny)
        // Flat map: no sphere geometry is needed, so none of its trigonometry is run. This used
        // to be computed before the branch, which put a sin, a cos and two radian conversions
        // on every marker on every street-level frame for nothing.
        if (t <= 0.001f) {
            out.x = m.x
            out.y = m.y
            out.front = 1f
            out.lift = 0f
            return
        }
        val la = Math.toRadians(lat.coerceIn(-89.9, 89.9))
        val dLon = Math.toRadians(nx * 360.0 - 180.0) - lon0

        val cosLat = cos(la)
        val cosc = sinLat0 * sin(la) + cosLat0 * cosLat * cos(dLon)
        val x3 = cosLat * sin(dLon)
        val y3 = cosLat0 * sin(la) - sinLat0 * cosLat * cos(dLon)
        // Tilt the ball toward the viewer about the screen's horizontal axis. An orthographic
        // sphere is still a circle under that rotation, so nothing changes shape: the poles tip
        // in, the graticule curves like a globe on a stand, and a spinning longitude reads as a
        // turning ball rather than a flat disc sliding sideways.
        val ty = y3 * cosTilt - cosc * sinTilt
        val tz = y3 * sinTilt + cosc * cosTilt
        val k = radius * (1.0 + lift)
        val gx = (cx + k * x3).toFloat()
        val gy = (cy - k * ty).toFloat()
        out.x = m.x + (gx - m.x) * t
        out.y = m.y + (gy - m.y) * t
        out.front = 1f - (1f - tz.coerceIn(0.0, 1.0).toFloat()) * t
        out.lift = (lift * t).toFloat()
    }
}

/**
 * How far a ground point is pushed out from the sphere centre for a given altitude.
 *
 * True scale would make aircraft invisible (11 km is 0.17 % of Earth's radius), so altitude is
 * compressed logarithmically: drones barely lift, airliners clearly do, and a low-Earth-orbit
 * satellite floats well clear of the surface with its tether visible. Capped so geostationary
 * satellites stay on screen.
 */
private fun liftFor(altM: Double?): Double {
    val alt = altM ?: return 0.0
    if (alt <= 1.0) return 0.0
    return (ln(1.0 + alt / 500.0) * 0.022).coerceIn(0.0, 0.30)
}

/**
 * Sphere radius in pixels: fixed.
 *
 * The globe turns; it does not scale. Tying its radius to zoom meant one pinch both tried to
 * resize the ball and shared its number with the flat map's zoom, so the two views pulled
 * against each other. The ball is now the same size for a given screen no matter what, and
 * zoom only decides which view you are in: pinch in past the hand-off and the flat map comes
 * back, where zoom works normally.
 */
@Suppress("UNUSED_PARAMETER")
fun globeRadius(zoom: Double, w: Float, h: Float): Float {
    val safeW = if (w.isFinite() && w > 0f) w else 1080f
    val safeH = if (h.isFinite() && h > 0f) h else 1920f
    // Just under half the short side: the ball nearly fills the narrow edge on any phone.
    // Callers must pass the size they actually draw at, so the disc and the markers share
    // one radius - the only way they stay welded together when the view resizes.
    return min(safeW, safeH) * 0.46f
}

/**
 * [t] comes from the caller: the sphere that is drawn and the positions the markers are
 * projected to must come from the same switch, or icons end up beside the globe.
 */
fun viewProjection(camera: MapCameraState, w: Float, h: Float, t: Float): ViewProjection = ViewProjection(
    flat = Projection(camera.lat, camera.lon, camera.zoom, w, h, viewShiftX(w)),
    centerLat = camera.lat,
    centerLon = camera.lon,
    radius = globeRadius(camera.zoom, w, h),
    t = t,
    tiltDeg = GLOBE_TILT_DEG,
)

/**
 * Degrees the sphere's axis tips toward the viewer.
 *
 * Fixed rather than adjustable: a tilt needs a turning ball to be visible at all, and 20
 * degrees is the point where the poles are clearly in view without the disc turning into an
 * ellipse of continents near the limb.
 */
const val GLOBE_TILT_DEG: Double = 20.0

/**
 * Where the map and the globe are centred: slightly right of the canvas centre.
 *
 * The PEER / ESP32 / MESH stack now owns the left edge in globe mode, so a dead-centre ball
 * sits under the cards. A few percent of the width is enough to clear them without pushing
 * the far edge of the world off screen.
 */
fun viewShiftX(widthPx: Float): Float =
    if (widthPx.isFinite() && widthPx > 0f) widthPx * 0.06f else 0f
