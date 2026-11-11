package dev.skyward.rf.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Esp32ConsoleTest {

    @Test
    fun `nmea gga gives position satellites hdop and altitude`() {
        val text = "\$GNGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
        val r = Esp32Console.parse(text)
        assertEquals(true, r.fix)
        assertEquals(8, r.sats)
        assertEquals(0.9, r.hdop!!, 0.001)
        assertEquals(545.4, r.altM!!, 0.001)
        assertEquals(48.1173, r.lat!!, 0.001)
        assertEquals(11.5167, r.lon!!, 0.001)
    }

    @Test
    fun `southern and western hemispheres keep their sign`() {
        val text = "\$GPRMC,123519,A,3352.000,S,15112.000,W,022.4,084.4,230394,003.1,W*6A"
        val r = Esp32Console.parse(text)
        assertEquals(true, r.fix)
        assertEquals(-33.8667, r.lat!!, 0.001)
        assertEquals(-151.2, r.lon!!, 0.001)
    }

    @Test
    fun `zero quality gga reports no fix but still counts satellites`() {
        val text = "\$GNGGA,123519,,,,,0,03,1.4,,M,,M,,*4B"
        val r = Esp32Console.parse(text)
        assertEquals(false, r.fix)
        assertEquals(3, r.sats)
        assertNull(r.lat)
    }

    @Test
    fun `ghostesp labelled output gives counts and the fix`() {
        val text = """
            GPS: fix
            Lat: 51.5074
            Lon: -0.1278
            Sats: 9
            HDOP: 0.8
            APs found: 12
            BLE devices: 5
        """.trimIndent()
        val r = Esp32Console.parse(text)
        assertEquals(true, r.fix)
        assertEquals(9, r.sats)
        assertEquals(0.8, r.hdop!!, 0.001)
        assertEquals(51.5074, r.lat!!, 0.0001)
        assertEquals(-0.1278, r.lon!!, 0.0001)
        assertEquals(12, r.wifi)
        assertEquals(5, r.ble)
    }

    @Test
    fun `marauder style sweep lines are counted`() {
        val text = "Found 24 access points\n8 BLE devices\nSatellites: 11"
        val r = Esp32Console.parse(text)
        assertEquals(24, r.wifi)
        assertEquals(8, r.ble)
        assertEquals(11, r.sats)
    }

    @Test
    fun `a silent console reports nothing rather than zero`() {
        val r = Esp32Console.parse("ESP-ROM:esp32s3-20210327\nboot:0x8 (SPI_FAST_FLASH_BOOT)")
        assertTrue(r.isEmpty)
        assertNull(r.wifi)
        assertNull(r.ble)
        assertNull(r.lat)
    }

    @Test
    fun `merge keeps what the new poll did not mention`() {
        val first = Esp32Console.parse("APs found: 12\nBLE devices: 5")
        val second = Esp32Console.parse("Sats: 9\nLat: 51.5\nLon: -0.12")
        val merged = first.merge(second)
        assertEquals(12, merged.wifi)
        assertEquals(5, merged.ble)
        assertEquals(9, merged.sats)
        assertEquals(51.5, merged.lat!!, 0.0001)
    }

    @Test
    fun `clis that can sweep get commands and bare bridges do not`() {
        assertTrue(Esp32Console.commands(BoardFamily.GHOSTESP).any { it.startsWith("scanap") })
        assertTrue(Esp32Console.commands(BoardFamily.MARAUDER).any { it.startsWith("gps") })
        // A board that has not said what it is gets asked that, and nothing it might not answer:
        // ordering a sweep from an unknown CLI only fills the transcript with errors.
        val unknown = Esp32Console.commands(BoardFamily.ESP32)
        assertTrue(unknown.any { it.startsWith("status") })
        assertTrue(unknown.none { it.startsWith("scan") })
    }
}
