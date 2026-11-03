package dev.skyward.rf.sky

import org.junit.Assert.assertTrue
import org.junit.Test
import org.orekit.propagation.analytical.tle.TLE
import org.orekit.propagation.analytical.tle.TLEPropagator
import org.orekit.time.AbsoluteDate
import org.orekit.time.TimeScalesFactory
import java.util.Date
import kotlin.math.abs

/**
 * Numerical check of the satellite pipeline.
 *
 * Reference values come from satellite.js 5.x (the de-facto standard JS SGP4) for the ISS
 * element set below, propagated to the TLE epoch and +10 / +45 minutes, then converted to
 * WGS-84 geodetic. Orekit's SGP4 plus our TEME->ECEF and geodetic solver must land on the
 * same ground point.
 */
class OrbitMathTest {

    private val line1 = "1 25544U 98067A   26306.75682168  .00005437  00000+0  10620-3 0  9990"
    private val line2 = "2 25544  51.6331 334.3796 0007124 340.7366  19.3352 15.49154261593232"

    private val epochMs = 1_793_642_989_393L

    private data class Ref(val dtMin: Int, val lat: Double, val lon: Double, val altKm: Double)

    private val refs = listOf(
        Ref(0, -0.000017600867369030682, 19.894515801004683, 415.37053393773294),
        Ref(10, 29.58720752159799, 43.903480553052745, 418.1065120078056),
        Ref(45, 4.300054341308326, -174.93452657257572, 424.1886483914841),
    )

    @Test
    fun gmstMatchesReferenceImplementation() {
        val expected = 5.488799926011218
        val actual = OrbitMath.gmst(epochMs)
        assertTrue("gmst $actual vs $expected", abs(actual - expected) < 1e-6)
    }

    @Test
    fun geodeticRoundTrip() {
        val ecef = OrbitMath.geodeticToEcef(51.4779, -0.0015, 0.046)
        val back = OrbitMath.ecefToGeodetic(ecef[0], ecef[1], ecef[2])
        assertTrue(abs(back[0] - 51.4779) < 1e-6)
        assertTrue(abs(back[1] - (-0.0015)) < 1e-6)
        assertTrue(abs(back[2] - 0.046) < 1e-4)
    }

    @Test
    fun lookAnglesStraightUp() {
        // A target directly overhead should read ~90 degrees elevation.
        val overhead = OrbitMath.geodeticToEcef(10.0, 20.0, 500.0)
        val look = OrbitMath.lookAngles(10.0, 20.0, overhead)
        assertTrue("elevation ${look[0]}", abs(look[0] - 90.0) < 0.01)
        assertTrue("range ${look[2]}", abs(look[2] - 500.0) < 1.0)
    }

    @Test
    fun sgp4ChainMatchesSatelliteJs() {
        // Same convention as the app: UTC wall clock read in the TAI scale (no IERS data).
        val propagator = TLEPropagator.selectExtrapolator(TLE(line1, line2, TimeScalesFactory.getTAI()))
        for (ref in refs) {
            val ms = epochMs + ref.dtMin * 60_000L
            val pv = propagator.propagate(AbsoluteDate(Date(ms), TimeScalesFactory.getTAI()))
            val p = pv.position
            val ecef = OrbitMath.temeToEcef(p.x, p.y, p.z, ms)
            val geo = OrbitMath.ecefToGeodetic(ecef[0], ecef[1], ecef[2])

            assertTrue("lat ${geo[0]} vs ${ref.lat} @+${ref.dtMin}", abs(geo[0] - ref.lat) < 0.05)
            val lonDelta = abs(((geo[1] - ref.lon + 540.0) % 360.0) - 180.0)
            assertTrue("lon ${geo[1]} vs ${ref.lon} @+${ref.dtMin}", lonDelta < 0.05)
            assertTrue("alt ${geo[2]} vs ${ref.altKm} @+${ref.dtMin}", abs(geo[2] - ref.altKm) < 5.0)
        }
    }
}
