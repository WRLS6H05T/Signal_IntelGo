package dev.skyward.rf.sky

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline mode plots the GPS constellation and nothing else, and the catalogue name is the only
 * record of which group an element set came from once it is cached.
 */
class SatFilterTest {

    @Test
    fun `gps constellation names are recognised`() {
        assertTrue(SatTracker.isGpsName("GPS BIIR-2  (PRN 13)"))
        assertTrue(SatTracker.isGpsName("GPS 71 (USA 260)"))
        assertTrue(SatTracker.isGpsName("NAVSTAR 71 (USA 260)"))
        assertTrue(SatTracker.isGpsName("gps biif-3"))
    }

    @Test
    fun `everything else in the sky is left out`() {
        assertFalse(SatTracker.isGpsName("ISS (ZARYA)"))
        assertFalse(SatTracker.isGpsName("STARLINK-1007"))
        assertFalse(SatTracker.isGpsName("NOAA 19"))
        assertFalse(SatTracker.isGpsName(""))
        // A name that merely starts with the letters is not the constellation.
        assertFalse(SatTracker.isGpsName("GPSR-2"))
    }
}
