package dev.skyward.rf.ui

import dev.skyward.rf.core.Layer
import dev.skyward.rf.core.MeshNode
import dev.skyward.rf.sky.AirspaceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mesh layer on the map.
 *
 * A node is one piece of infrastructure, not a dot in a crowded layer: it must survive the
 * weak-dot filter and it must be plotted in the MESH layer. A node without a position has
 * nowhere to be drawn and is the only thing the map may leave out.
 */
class MeshMarkerTest {

    private fun markers(
        nodes: List<MeshNode>,
        minScore: Int = 20,
    ): List<MapMarker> = buildMarkers(
        sky = AirspaceEngine.SkyState(mesh = nodes),
        showSats = false,
        showAir = false,
        showDrones = false,
        showMesh = true,
        showCells = false,
        showEsp32 = false,
        showWatch = false,
        distanceUnit = "km",
        minScore = minScore,
    )

    private fun node(id: String, lat: Double?, lon: Double?, score: Int) = MeshNode(
        id = id,
        name = "Roof $id",
        lat = lat,
        lon = lon,
        score = score,
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
    fun `a node with no position has nowhere to be drawn`() {
        // Meshtastic and MeshCore both allow a node that shares no location; the MESH card
        // lists it, the map cannot place it.
        val out = markers(listOf(node("!nofix", null, null, score = 80)))
        assertEquals(0, out.size)
    }
}
