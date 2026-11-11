package dev.skyward.rf.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubGhzParseTest {

    @Test
    fun `a carrier line with a level is a hit`() {
        val text = """
            SubGHz scan
            433.92 MHz  RSSI: -72
            315.00 MHz RSSI -88
        """.trimIndent()
        val sweep = Esp32Console.parseScan(text)

        assertEquals(2, sweep.subGhz.size)
        assertEquals(433.92, sweep.subGhz[0].freqMhz, 0.001)
        assertEquals(-72, sweep.subGhz[0].rssi)
        assertEquals(-88, sweep.subGhz[1].rssi)
        assertTrue(sweep.total == 2)
    }

    @Test
    fun `the same carrier twice keeps the stronger hearing`() {
        val text = "433.92 MHz RSSI -90\n433.92 MHz RSSI -61\n"
        val sweep = Esp32Console.parseScan(text)
        assertEquals(1, sweep.subGhz.size)
        assertEquals(-61, sweep.subGhz[0].rssi)
    }

    @Test
    fun `a frequency without a level is not a transmission`() {
        val sweep = Esp32Console.parseScan("SubGHz: 433.92 MHz supported bands 315-915\n")
        assertTrue(sweep.subGhz.isEmpty())
    }

    @Test
    fun `wi-fi and bluetooth bands are outside the sub-GHz sweep`() {
        val sweep = Esp32Console.parseScan("2400 MHz RSSI -55\n2480 MHz RSSI -60\n")
        assertTrue(sweep.subGhz.isEmpty())
    }

    @Test
    fun `a board naming a cc1101 is flagged as having the radio`() {
        assertEquals(true, Esp32Console.parse("subghz rx\nCC1101 present, 315-915 MHz").subGhz)
    }

    @Test
    fun `a board that never mentions sub-GHz is unknown, not negative`() {
        assertNull(Esp32Console.parse("ESP32 Marauder\nscanap\n").subGhz)
    }

    @Test
    fun `the sub-GHz command is appended after the sweeps, and only when set`() {
        val plain = Esp32Console.commandsFor(BoardFamily.GHOSTESP, null)
        assertEquals(listOf("stopscan\r\n", "gps\r\n", "scanap\r\n", "bledetect\r\n"), plain)

        val withRadio = Esp32Console.commandsFor(BoardFamily.GHOSTESP, "subghz rx")
        assertEquals("subghz rx\r\n", withRadio.last())

        // A board with no console gets nothing appended: there is nothing to send it to.
        assertTrue(Esp32Console.commandsFor(BoardFamily.BISCUIT, "subghz rx").isEmpty())
    }
}
