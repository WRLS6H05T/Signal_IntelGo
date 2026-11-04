package dev.skyward.rf.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import dev.skyward.rf.core.Geo
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.pow

class MapCameraState(
    startLat: Double = 39.5,
    startLon: Double = -98.35,
    startZoom: Double = 4.0,
) {
    var lat by mutableStateOf(startLat)
    var lon by mutableStateOf(startLon)
    var zoom by mutableStateOf(startZoom)
    var follow by mutableStateOf(false)

    /** True once the map has jumped to the first location fix. */
    var centredOnce by mutableStateOf(false)

    companion object {
        const val MIN_ZOOM = 2.0
        const val MAX_ZOOM = 17.0
    }

    fun panPixels(dx: Float, dy: Float) {
        if (!dx.isFinite() || !dy.isFinite()) return
        val world = 256.0 * 2.0.pow(zoom)
        val cx = Geo.lonToNorm(lon) * world - dx
        val cy = Geo.latToNorm(lat) * world - dy
        lon = Geo.normToLon(cx / world)
        lat = Geo.normToLat((cy / world).coerceIn(0.0001, 0.9999))
        follow = false
    }

    fun zoomBy(factor: Float, focusX: Float, focusY: Float, width: Float, height: Float) {
        // NaN fails every comparison, so a degenerate pinch would otherwise slip through and
        // poison the camera: 2^NaN is NaN, and rounding NaN to a pixel throws.
        if (!factor.isFinite() || factor <= 0f) return
        if (!focusX.isFinite() || !focusY.isFinite()) return
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return
        val world = 256.0 * 2.0.pow(zoom)
        val cx = Geo.lonToNorm(lon) * world
        val cy = Geo.latToNorm(lat) * world
        val geoX = (cx + (focusX - width / 2f)) / world
        val geoY = (cy + (focusY - height / 2f)) / world
        val next = (zoom + log2(factor.toDouble())).coerceIn(MapCameraState.MIN_ZOOM, MapCameraState.MAX_ZOOM)
        if (!next.isFinite()) return
        zoom = next
        val world2 = 256.0 * 2.0.pow(next)
        val ncx = geoX * world2 - (focusX - width / 2f)
        val ncy = geoY * world2 - (focusY - height / 2f)
        lon = Geo.normToLon(ncx / world2)
        lat = Geo.normToLat((ncy / world2).coerceIn(0.0001, 0.9999))
    }

    /**
     * Drags the sphere rather than the flat map: pixels become rotation, so the globe turns
     * under the finger. Sensitivity is ~60 degrees per sphere radius.
     */
    fun rotateGlobe(dx: Float, dy: Float, radiusPx: Float) {
        if (!dx.isFinite() || !dy.isFinite() || !radiusPx.isFinite()) return
        if (radiusPx <= 1f) return
        val degPerPx = 60.0 / radiusPx
        val cosLat = cos(lat * Math.PI / 180.0).coerceAtLeast(0.22)
        val nextLon = lon - dx * degPerPx / cosLat
        lon = ((nextLon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        lat = (lat + dy * degPerPx).coerceIn(-85.0, 85.0)
        follow = false
    }

    /**
     * Turns the sphere one step under its own momentum. Separate from [rotateGlobe] so the
     * auto-spin is not mistaken for the user's finger having moved: it never takes a pixel
     * amount and it leaves the zoom alone.
     */
    fun spinGlobe(degrees: Double) {
        if (!degrees.isFinite() || degrees == 0.0) return
        val next = lon + degrees
        lon = ((next + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        // A spinning ball cannot also be pinned to a fix: following would snap it back
        // every second and the spin would look like a stutter.
        if (follow) follow = false
    }

    fun centerOn(latitude: Double, longitude: Double, targetZoom: Double? = null, resumeFollow: Boolean = true) {
        if (!latitude.isFinite() || !longitude.isFinite()) return
        lat = latitude
        lon = longitude
        if (targetZoom != null) zoom = targetZoom.coerceIn(MapCameraState.MIN_ZOOM, MapCameraState.MAX_ZOOM)
        if (resumeFollow) follow = true
    }
}

/** Screen <-> geographic projection for one frame of the map. */
class Projection(
    val centerLat: Double,
    val centerLon: Double,
    val zoom: Double,
    val width: Float,
    val height: Float,
    /**
     * Pixels to push the centre right of the canvas centre. The status stack owns the left
     * edge, so the thing being looked at sits where the open screen is, not in the middle
     * of the glass.
     */
    val shiftX: Float = 0f,
) {
    private val safeZoom = if (zoom.isFinite()) zoom.coerceIn(0.0, 22.0) else 4.0
    private val safeShift = if (shiftX.isFinite()) shiftX else 0f
    private val world = 256.0 * 2.0.pow(safeZoom)
    private val cx = Geo.lonToNorm(centerLon) * world
    private val cy = Geo.latToNorm(centerLat) * world

    fun toScreen(lat: Double, lon: Double): Offset {
        val wx = Geo.lonToNorm(lon) * world
        val wy = Geo.latToNorm(lat.coerceIn(-85.0, 85.0)) * world
        return Offset(
            (wx - cx + width / 2.0 + safeShift).toFloat(),
            (wy - cy + height / 2.0).toFloat(),
        )
    }

    fun toGeo(x: Float, y: Float): Pair<Double, Double> {
        val wx = cx + (x - width / 2f - safeShift)
        val wy = cy + (y - height / 2f)
        return Geo.normToLat((wy / world).coerceIn(0.0, 1.0)) to Geo.normToLon(wx / world)
    }

    fun tileOrigin(z: Int, tx: Int, ty: Int): Pair<Float, Float> {
        val n = 1 shl z
        val tileWorld = world / n
        val sx = (tx * tileWorld - cx + width / 2.0 + safeShift).toFloat()
        val sy = (ty * tileWorld - cy + height / 2.0).toFloat()
        return sx to sy
    }

    fun tileSize(z: Int): Float = (world / (1 shl z)).toFloat()
}
