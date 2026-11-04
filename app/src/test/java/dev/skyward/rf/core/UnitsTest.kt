package dev.skyward.rf.core

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {

    @Test
    fun `kilometres stay kilometres`() {
        assertEquals("12.4 km", Units.distance(12.4, Units.KM, 1))
        assertEquals("150 km", Units.distance(150.0, Units.KM, 0))
    }

    @Test
    fun `miles are converted only at the point of display`() {
        assertEquals("7.7 mi", Units.distance(12.4, Units.MILES, 1))
        assertEquals("93 mi", Units.distance(150.0, Units.MILES, 0))
    }

    @Test
    fun `temperature follows its own setting`() {
        assertEquals("12.4 \u00b0C", Units.temperature(12.4, Units.CELSIUS))
        assertEquals("54.3 \u00b0F", Units.temperature(12.4, Units.FAHRENHEIT))
        assertEquals("32.0 \u00b0F", Units.temperature(0.0, Units.FAHRENHEIT))
    }

    @Test
    fun `wind, height and rain follow the distance unit`() {
        assertEquals("18 km/h", Units.wind(18.0, Units.KM))
        assertEquals("11 mph", Units.wind(18.0, Units.MILES))
        assertEquals("34 m", Units.elevation(34.0, Units.KM))
        assertEquals("112 ft", Units.elevation(34.0, Units.MILES))
        assertEquals("0.2 mm", Units.rain(0.2, Units.KM))
        assertEquals("0.01 in", Units.rain(0.2, Units.MILES))
    }

    @Test
    fun `a missing value is a dash, never a confident zero`() {
        assertEquals("\u2014", Units.temperature(null, Units.CELSIUS))
        assertEquals("\u2014", Units.wind(null, Units.MILES))
        assertEquals("\u2014", Units.elevation(null, Units.KM))
    }
}
