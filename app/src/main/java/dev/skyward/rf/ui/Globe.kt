package dev.skyward.rf.ui

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/** Screen position of one entity, plus how much it faces the viewer and how high it sits. */
data class Projected(val x: Float, val y: Float, val front: Float, val lift: Float)

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
) {
    private val cx = flat.width / 2f
    private val cy = flat.height / 2f
    private val sinLat0 = sin(Math.toRadians(centerLat))
    private val cosLat0 = cos(Math.toRadians(centerLat))
    private val lon0 = Math.toRadians(centerLon)

    fun surface(lat: Double, lon: Double): Projected = project(lat, lon, 0.0)

    /** Ground point pushed radially outward by the entity's altitude: the 3-D cue. */
    fun elevated(lat: Double, lon: Double, altM: Double?): Projected =
        project(lat, lon, liftFor(altM))

    private fun project(lat: Double, lon: Double, lift: Double): Projected {
        val la = Math.toRadians(lat.coerceIn(-89.9, 89.9))
        val dLon = Math.toRadians(lon) - lon0
        val cosLat = cos(la)
        val cosc = sinLat0 * sin(la) + cosLat0 * cosLat * cos(dLon)
        val m = flat.toScreen(lat, lon)
        if (t <= 0.001f) return Projected(m.x, m.y, 1f, 0f)

        val x3 = cosLat * sin(dLon)
        val y3 = cosLat0 * sin(la) - sinLat0 * cosLat * cos(dLon)
        val k = radius * (1.0 + lift)
        val gx = (cx + k * x3).toFloat()
        val gy = (cy - k * y3).toFloat()
        return Projected(
            x = m.x + (gx - m.x) * t,
            y = m.y + (gy - m.y) * t,
            front = 1f - (1f - cosc.coerceIn(0.0, 1.0).toFloat()) * t,
            lift = (lift * t).toFloat(),
        )
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
    flat = Projection(camera.lat, camera.lon, camera.zoom, w, h),
    centerLat = camera.lat,
    centerLon = camera.lon,
    radius = globeRadius(camera.zoom, w, h),
    t = t,
)
