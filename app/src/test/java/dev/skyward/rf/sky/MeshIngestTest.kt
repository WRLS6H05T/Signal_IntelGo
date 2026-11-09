package dev.skyward.rf.sky

import dev.skyward.rf.core.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mesh JSON, as the bridges actually publish it.
 *
 * Some gateways and observer bridges quote every number in the packet. kotlinx's `doubleOrNull`
 * answers null for a JSON *string*, so those packets created a node and then never gave it a
 * position: listed in the table, counted in INTEL, and absent from the map.
 */
class MeshIngestTest {

    private fun source() = MeshSource { Settings() }

    @Test
    fun `a fixed-point position quoted as a string still places the node`() {
        val s = source()
        s.ingestJson(
            """{"sender":"!abc123","type":"position","payload":{"latitude_i":"515000000","longitude_i":"-1000000"}}""",
        )

        val n = s.nodesFlow.value.getValue("!abc123")
        assertEquals(51.5, n.lat ?: 0.0, 1e-7)
        assertEquals(-0.1, n.lon ?: 0.0, 1e-7)
    }

    @Test
    fun `a float position quoted as a string still places the node`() {
        val s = source()
        s.ingestJson(
            """{"sender":"!def456","type":"position","payload":{"latitude":"51.5074","longitude":"-0.1278"}}""",
        )

        val n = s.nodesFlow.value.getValue("!def456")
        assertEquals(51.5074, n.lat ?: 0.0, 1e-7)
    }

    @Test
    fun `a plain number position is unaffected`() {
        val s = source()
        s.ingestJson(
            """{"sender":"!123","type":"position","payload":{"latitude_i":515000000,"longitude_i":-1000000}}""",
        )

        val n = s.nodesFlow.value.getValue("!123")
        assertEquals(51.5, n.lat ?: 0.0, 1e-7)
    }

    @Test
    fun `a node with no position is still no position`() {
        val s = source()
        s.ingestJson("""{"sender":"!nope","type":"nodeinfo","payload":{"longname":"Roof node"}}""")

        val n = s.nodesFlow.value.getValue("!nope")
        assertNull(n.lat)
        assertEquals("Roof node", n.name)
    }

    @Test
    fun `a quoted SNR is read as a number`() {
        val s = source()
        s.ingestJson(
            """{"sender":"!snr","type":"position","snr":"7.5",""" +
                """"payload":{"latitude_i":"515000000","longitude_i":"-1000000"}}""",
        )

        val n = s.nodesFlow.value.getValue("!snr")
        assertTrue(n.snr > 7.0)
    }
}
