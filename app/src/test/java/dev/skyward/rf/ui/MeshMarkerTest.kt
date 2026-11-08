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
}
