package dev.skyward.rf.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Esp32SweepTest {

    @Test
    fun `ghostesp sweep lines become access points`() {
        val text = """
            scanap
            [0] SSID: HomeNet, BSSID: AA:BB:CC:DD:EE:01, RSSI: -51, Channel: 6, Encryption: WPA2
            [1] SSID: Cafe, BSSID: AA:BB:CC:DD:EE:02, RSSI: -77, Channel: 11, Encryption: OPEN
        """.trimIndent()
        val s = Esp32Console.parseScan(text)
        assertEquals(2, s.aps.size)
        assertEquals("HomeNet", s.aps[0].ssid)
        assertEquals("AA:BB:CC:DD:EE:01", s.aps[0].bssid)
        assertEquals(-51, s.aps[0].rssi)
        assertEquals(6, s.aps[0].channel)
        assertEquals(-77, s.aps[1].rssi)
        assertTrue(s.devices.isEmpty())
    }

    @Test
    fun `marauder sweep lines become access points`() {
        val text = "APs: -70 Ch: 6 AA:BB:CC:DD:EE:03 ESSID: DriveBy"
        val s = Esp32Console.parseScan(text)
        assertEquals(1, s.aps.size)
        assertEquals("DriveBy", s.aps[0].ssid)
        assertEquals(6, s.aps[0].channel)
        assertEquals(-70, s.aps[0].rssi)
    }

    @Test
    fun `ble lines stay devices and keep their names`() {
        val text = """
            -55 AA:BB:CC:DD:EE:04 My Speaker
            [1] Name: Buds, MAC: AA:BB:CC:DD:EE:05, RSSI: -62
        """.trimIndent()
        val s = Esp32Console.parseScan(text)
        assertTrue(s.aps.isEmpty())
        assertEquals(2, s.devices.size)
        assertEquals(-55, s.devices[0].rssi)
        assertEquals("My Speaker", s.devices[0].name)
        assertEquals("Buds", s.devices[1].name)
    }

    @Test
    fun `boot noise names nothing`() {
        val text = "ESP-ROM:esp32s3-20210327\nrst:0x1 (POWERON)\nscanap\nAPs found: 3"
        val s = Esp32Console.parseScan(text)
        assertTrue(s.isEmpty)
        assertEquals(0, s.total)
    }

    @Test
    fun `a repeated address is one finding, the newest line winning`() {
        val text = """
            -80 Ch: 1 AA:BB:CC:DD:EE:06 Office
            -61 Ch: 1 AA:BB:CC:DD:EE:06 Office
        """.trimIndent()
        val s = Esp32Console.parseScan(text)
        assertEquals(1, s.aps.size)
        assertEquals(-61, s.aps[0].rssi)
    }

    @Test
    fun `a board that says it has no gps is marked as having none`() {
        assertEquals(false, Esp32Console.parse("GhostESP v1.9\nGPS: not detected").gps)
        assertEquals(false, Esp32Console.parse("Marauder\nNo GPS").gps)
    }

    @Test
    fun `nmea proves the board has a gps`() {
        val r = Esp32Console.parse(
            "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47",
        )
        assertEquals(true, r.gps)
        assertEquals(8, r.sats)
    }

    @Test
    fun `a board with a fix but no nmea still counts as having a gps`() {
        val r = Esp32Console.parse("GPS: Fix 3D, Sats: 9")
        assertEquals(true, r.gps)
        assertEquals(9, r.sats)
    }

    @Test
    fun `silence leaves gps unknown rather than absent`() {
        assertNull(Esp32Console.parse("ESP-ROM:esp32s3-20210327").gps)
    }

    @Test
    fun `commands stop the previous sweep before starting a new one`() {
        val ghost = Esp32Console.commands(BoardFamily.GHOSTESP)
        assertEquals("stopscan\r\n", ghost.first())
        assertTrue(ghost.any { it.startsWith("scanap") })
        assertTrue(ghost.any { it.startsWith("bledetect") })
        val marauder = Esp32Console.commands(BoardFamily.MARAUDER)
        assertTrue(marauder.any { it.startsWith("blescan") })
        assertTrue(Esp32Console.commands(BoardFamily.BISCUIT).isEmpty())
        assertFalse(Esp32Console.commands(BoardFamily.ESP32).any { it.startsWith("scan") })
    }
}
