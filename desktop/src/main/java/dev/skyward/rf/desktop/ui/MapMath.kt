package dev.skyward.rf.desktop.ui

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/**
 * Where the globe becomes a street map, and at what scale.
 *
 * The first version faded one projection into the other while each was drawn at its own scale -
 * a ball a few hundred pixels across next to a Mercator world hundreds of thousands of pixels
 * wide. Every marker then travelled between two unrelated positions, so the middle of the
 * transition looked like icons swimming across the screen rather than a map curling.
 *
 * The fix is that the ball's size is *derived from the same zoom* as the map's scale, and the
 * hand-off is placed where the two agree: the ball's own surface scale at its centre is
 * `radius * pi / 180` pixels per degree, the flat map's is `worldSize / 360`. Setting the ball's
 * radius so those are equal at [HANDOFF] makes the two projections coincide at the centre of the
 * screen, which is the point the operator is looking at, and leaves only the curvature to
 * dissolve.
 *
 * So:
 *  - the ball grows with zoom, `0.9 * minSide` at the hand-off;
 *  - the flat map runs at `zoom + offset`, where the offset is exactly the difference between
 *    the ball's scale and the map's at that zoom;
 *  - the dissolve is [BAND] zoom levels wide, short enough to read as one motion.
 */
object MapMath {

    /** Zoom at which the ball fills the viewport and the map takes over. */
    const val HANDOFF: Double = 5.5

    /** Zoom levels the dissolve spans. Wide enough to see, narrow enough to be one motion. */
    const val BAND: Double = 0.6

    /** Ball radius at the hand-off, as a fraction of the shorter side of the map area. */
    const val BALL_AT_HANDOFF: Double = 0.9

    const val MIN_ZOOM: Double = 2.0
    const val MAX_ZOOM: Double = 17.0

    /** 1 while the ball is the map, 0 once the flat map is, ramping across [BAND]. */
    fun globeBlend(zoom: Double): Float =
        ((HANDOFF + BAND / 2 - zoom) / BAND).coerceIn(0.0, 1.0).toFloat()

    /** Width of the whole Mercator world in pixels at a flat-map zoom. */
    fun worldSize(zoom: Double): Double = 256.0 * 2.0.pow(zoom)

    /** The ball's radius in pixels, which is what makes the two scales meet at [HANDOFF]. */
    fun globeRadius(zoom: Double, minSide: Float): Float =
        (BALL_AT_HANDOFF * minSide * 2.0.pow(zoom - HANDOFF)).toFloat()

    /**
     * How far the map's own zoom sits from the control zoom.
     *
     * It is the number of zoom levels between "a 256 px tile world" and "a ball of this radius at
     * the same surface scale": `log2(2 * pi * radius / 256) - HANDOFF`.
     */
    fun handoffOffset(minSide: Float): Double =
        ln(2.0 * PI * BALL_AT_HANDOFF * minSide / 256.0) / ln(2.0) - HANDOFF

    /** The flat zoom that belongs to a control zoom: matched to the ball at the hand-off. */
    fun mapZoom(zoom: Double, minSide: Float): Double = zoom + handoffOffset(minSide)

    /**
     * The tiles that cover the viewport, and where the first one goes.
     *
     * `origin` is the screen position of the world's top-left corner: every tile is
     * `origin + index * size`, so a tile that is already cached draws immediately and the index
     * arithmetic never has to be repeated per tile.
     */
    data class TileView(
        val z: Int,
        val count: Int,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        val size: Float,
        val originX: Float,
        val originY: Float,
    ) {
        val tiles: Int get() = (maxX - minX + 1) * (maxY - minY + 1)
    }

    fun tileView(zoom: Double, camNx: Double, camNy: Double, width: Float, height: Float): TileView {
        val z = floor(zoom).toInt().coerceIn(2, 18)
        val n = 1 shl z
        val world = worldSize(zoom)
        val size = (world / n).toFloat()
        val originX = (width / 2.0 - camNx * world).toFloat()
        val originY = (height / 2.0 - camNy * world).toFloat()
        val minX = floor((0.0 - originX) / size).toInt().coerceIn(0, n - 1)
        val maxX = floor((width - originX) / size).toInt().coerceIn(0, n - 1)
        val minY = floor((0.0 - originY) / size).toInt().coerceIn(0, n - 1)
        val maxY = floor((height - originY) / size).toInt().coerceIn(0, n - 1)
        return TileView(z, n, minX, maxX, minY, maxY, size, originX, originY)
    }
}
