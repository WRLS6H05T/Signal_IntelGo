package dev.skyward.rf.sky

import dev.skyward.rf.core.SatView
import dev.skyward.rf.ui.sampleSats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map samples a large catalogue so the globe stays drawable, but the station is not a sample
 * of anything: it has to be on the map whether or not the stride happens to land on its index.
 */
class SatPlotTest {

    private fun sat(norad: String, name: String) = SatView(
        noradId = norad,
        name = name,
        lat = 0.0,
        lon = 0.0,
        altKm = 400.0,
        elevation = 10.0,
        azimuth = 0.0,
        rangeKm = 1_000.0,
        score = 50,
    )

    @Test
    fun `the station is plotted even when the catalogue needs a stride`() {
        val sats = (1..3_000).map { sat("9000$it", "STARLINK-$it") } + sat("25544", "ISS (ZARYA)")
        val plotted = sampleSats(sats, 500)
        assertTrue("the station must survive the sampling", plotted.any { it.noradId == "25544" })
        assertEquals("the cap still applies to everything else", 501, plotted.size)
    }

    @Test
    fun `a catalogue under the cap is plotted whole`() {
        val sats = (1..100).map { sat("8000$it", "SAT-$it") }
        assertEquals(100, sampleSats(sats, 1_500).size)
    }
}
