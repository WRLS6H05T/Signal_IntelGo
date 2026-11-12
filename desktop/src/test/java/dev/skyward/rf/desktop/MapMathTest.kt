package dev.skyward.rf.desktop

import dev.skyward.rf.core.Geo
import dev.skyward.rf.desktop.ui.MapMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * The hand-off between the ball and the street map.
 *
 * The one assertion that matters is the scale one: at the hand-off zoom the ball's surface and
 * the flat map must report the same pixels per degree at the centre of the screen. That equality
 * is what stops markers swimming between two unrelated projections as the map curls.
 */
class MapMathTest {

    private val minSide = 757f

    @Test
    fun `the ball and the map agree on scale where they hand over`() {
        val radius = MapMath.globeRadius(MapMath.HANDOFF, minSide)
        val ballPxPerDegree = radius * PI / 180.0
        val mapPxPerDegree = MapMath.worldSize(MapMath.mapZoom(MapMath.HANDOFF, minSide)) / 360.0
        assertEquals(ballPxPerDegree, mapPxPerDegree, ballPxPerDegree * 0.01)
    }

    @Test
    fun `the ball fills the viewport at the hand-off and doubles each zoom before it`() {
        assertEquals(0.9 * minSide, MapMath.globeRadius(MapMath.HANDOFF, minSide).toDouble(), 0.01)
        val at4 = MapMath.globeRadius(4.0, minSide)
        val at5 = MapMath.globeRadius(5.0, minSide)
        assertEquals(at4 * 2.0, at5.toDouble(), 0.5)
        assertTrue("grows monotonically", MapMath.globeRadius(3.0, minSide) < MapMath.globeRadius(4.0, minSide))
    }

    @Test
    fun `the dissolve is a short band around the hand-off`() {
        assertEquals(1f, MapMath.globeBlend(MapMath.HANDOFF - MapMath.BAND), 0.0001f)
        assertEquals(0f, MapMath.globeBlend(MapMath.HANDOFF + MapMath.BAND), 0.0001f)
        assertEquals(0.5f, MapMath.globeBlend(MapMath.HANDOFF), 0.0001f)
        assertEquals(1f, MapMath.globeBlend(2.0), 0.0001f)
        assertEquals(0f, MapMath.globeBlend(12.0), 0.0001f)
    }

    @Test
    fun `the map keeps a usable zoom range after the offset`() {
        val offset = MapMath.handoffOffset(minSide)
        assertTrue("the map starts near world zoom: $offset", offset in -3.0..0.0)
        // Street level is still reachable inside the control zoom's range.
        assertTrue(MapMath.mapZoom(MapMath.MAX_ZOOM, minSide) > 14.0)
        assertTrue(MapMath.mapZoom(MapMath.MIN_ZOOM, minSide) > 0.0)
    }

    @Test
    fun `mercator scale doubles per zoom level`() {
        assertEquals(256.0, MapMath.worldSize(0.0), 0.0001)
        assertEquals(512.0, MapMath.worldSize(1.0), 0.0001)
    }

    @Test
    fun `the tile range covers the viewport and never leaves the world`() {
        val view = MapMath.tileView(zoom = 12.0, camNx = 0.5, camNy = 0.5, width = 1000f, height = 800f)
        assertEquals(12, view.z)
        assertTrue("left edge covered", view.minX * view.size + view.originX <= 0.5f)
        assertTrue("right edge covered", (view.maxX + 1) * view.size + view.originX >= 999.5f)
        assertTrue("top edge covered", view.minY * view.size + view.originY <= 0.5f)
        assertTrue("bottom edge covered", (view.maxY + 1) * view.size + view.originY >= 799.5f)
        assertTrue("a screen needs a handful of tiles", view.tiles in 1..64)
    }

    @Test
    fun `the centre of the viewport is the camera position`() {
        val view = MapMath.tileView(zoom = 10.0, camNx = Geo.lonToNorm(-0.12), camNy = Geo.latToNorm(51.5), width = 900f, height = 700f)
        val world = MapMath.worldSize(10.0)
        val x = view.originX + Geo.lonToNorm(-0.12) * world
        val y = view.originY + Geo.latToNorm(51.5) * world
        assertEquals(450.0, x, 0.01)
        assertEquals(350.0, y, 0.01)
    }
}
