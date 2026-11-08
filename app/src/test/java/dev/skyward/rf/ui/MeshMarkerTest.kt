package dev.skyward.rf.ui

import dev.skyward.rf.core.LatLon
import dev.skyward.rf.core.Layer
import dev.skyward.rf.core.LoggedEntity
import dev.skyward.rf.core.MeshNode
import dev.skyward.rf.sky.AirspaceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mesh layer on the map.
 *
 * A node is one piece of infrastructure, not a dot in a crowded layer: it must survive the
 * weak-dot filter and it must be plotted in the MESH layer. A node without a position has
 * nowhere to be drawn and is the only thing the map may leave out - what it must never do is
 * invent a position for it, which is what pinning it at this phone's fix was.
 */
class MeshMarkerTest {

    private fun markers(
        nodes: List<MeshNode>,
        minScore: Int = 20,
        observer: LatLon? = null,
        loggedMesh: List<LoggedEntity> = emptyList(),
    ): List<MapMarker> = buildMarkers(
        sky = AirspaceEngine.SkyState(mesh = nodes, observer = observer),
        showSats = false,
        showAir = false,
        showDrones = false,
        showMesh = true,
        showCells = false,
        showEsp32 = false,
        showWatch = false,
        distanceUnit = "km",
        minScore = minScore,
        loggedMesh = loggedMesh,
    )

    private fun node(id: String, lat: Double?, lon: Double?, score: Int) = MeshNode(
        id = id,
        name = "Roof $id",
        lat = lat,
        lon = lon,
        score = score,
    )

    private fun logged(id: String, lat: Double, lon: Double) = LoggedEntity(
        key = "MESH:$id",
        layer = Layer.MESH,
        name = "Roof $id",
        lat = lat,
        lon = lon,
        firstSeen = 1_000L,
        lastSeen = 2_000L,
        sightings = 3,
    )

    @Test
    fun `a faint node stays on the map`() {
        val out = markers(listOf(node("!ab12", 51.5, -0.1, score = 3)))
        assertEquals(1, out.size)
        assertEquals(Layer.MESH, out[0].layer)
        assertEquals("MESH:!ab12", out[0].key)
    }

    @Test
    fun `every node with a position is plotted`() {
        val out = markers(
            listOf(
                node("!a", 51.5, -0.1, score = 0),
                node("!b", 51.6, -0.2, score = 90),
                node("!c", 51.7, -0.3, score = 40),
            ),
        )
        assertEquals(3, out.size)
        assertTrue(out.all { it.layer == Layer.MESH })
    }

    @Test
    fun `a node with no position is not pinned at your fix`() {
        // Known no position, observer known: the node must stay off the map rather than appear
        // on the street the phone is standing on. That was a confident, wrong position.
        val out = markers(listOf(node("!nofix", null, null, score = 80)), observer = LatLon(51.5, -0.1))
        assertEquals(0, out.size)
    }

    @Test
    fun `a logged node stays after the feed drops it`() {
        val out = markers(emptyList(), loggedMesh = listOf(logged("!gone", 51.4, -0.3)))
        assertEquals(1, out.size)
        assertEquals("MESH:!gone", out[0].key)
        assertTrue(out[0].logged)
        assertEquals(51.4, out[0].lat, 1e-9)
    }

    @Test
    fun `every logged node is drawn, not just the first few`() {
        // The registry can hold a whole mesh, and the map must draw all of it: a live feed that
        // lists a node without a position used to hide the registry's copy and take it off the
        // screen, which is how a logged mesh read as partial.
        val entries = (1..25).map { i -> logged("!n$i", 50.0 + i / 1000.0, -1.0 - i / 1000.0) }
        val out = markers(emptyList(), loggedMesh = entries)
        assertEquals(25, out.size)
        assertEquals(25, out.map { it.key }.toSet().size)
    }

    @Test
    fun `a node heard again without a position keeps its logged place`() {
        // After a restart the first packet from a node is often a nodeinfo or an advert with no
        // location. The node is in the live feed, but an id in the live list is not a position on
        // the map: the registry still knows where this node is, so it must still be drawn there.
        val out = markers(
            listOf(node("!gone", null, null, score = 40)),
            loggedMesh = listOf(logged("!gone", 51.4, -0.3)),
        )
        assertEquals(1, out.size)
        assertEquals("MESH:!gone", out[0].key)
        assertEquals(51.4, out[0].lat, 1e-9)
        assertTrue(out[0].logged)
    }

    @Test
    fun `a node that is live and logged is drawn once`() {
        val out = markers(
            listOf(node("!gone", 51.4, -0.3, score = 50)),
            loggedMesh = listOf(logged("!gone", 51.4, -0.3)),
        )
        assertEquals(1, out.size)
        // The live marker is the one drawn: its card carries the radio detail, not the tombstone.
        assertFalse(out[0].subtitle.startsWith("logged"))
    }

    @Test
    fun `nodes stacked on one spot say how many they stand for`() {
        // Fifty dishes on one pixel look like one node: every marker was drawn, on top of its
        // neighbour. The spot has to carry the count, or the map contradicts the table.
        val entries = (1..50).map { i -> logged("!s$i", 51.5, -0.1) }
        val out = markers(emptyList(), loggedMesh = entries)
        assertEquals(1, out.size)
        assertEquals(50, out[0].stack)
        assertTrue(out[0].lines.any { it.second == "50 nodes" })
    }

    @Test
    fun `nodes at their own positions are not folded together`() {
        val entries = (1..50).map { i -> logged("!p$i", 51.0 + i / 100.0, -0.1) }
        val out = markers(emptyList(), loggedMesh = entries)
        assertEquals(50, out.size)
        assertTrue(out.all { it.stack == 1 })
    }

    @Test
    fun `a live node and a logged one on the same spot count as two`() {
        val out = markers(
            listOf(node("!here", 51.5, -0.1, score = 50)),
            loggedMesh = listOf(logged("!other", 51.5, -0.1)),
        )
        assertEquals(1, out.size)
        assertEquals(2, out[0].stack)
        // The live marker is the one kept: its card carries the radio detail.
        assertFalse(out[0].subtitle.startsWith("logged"))
    }
}
