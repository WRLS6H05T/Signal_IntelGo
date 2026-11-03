package dev.skyward.rf.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbBoardMatchTest {

    @Test
    fun `marauder boot banner is recognised`() {
        val text = """
            ----------------------------------
             ESP32 Marauder
             v1.10.0
             By: justcallmekoko
            ----------------------------------
            >
        """.trimIndent()
        val hit = UsbBoardMatch.classify(text, dualPort = false)
        assertEquals(BoardFamily.MARAUDER, hit?.family)
        assertEquals("1.10.0", hit?.version)
    }

    @Test
    fun `marauder machine protocol line is definitive`() {
        val text = """@MARAUDER:{"protocol":1,"tx":"sig1","status":"ok"}"""
        val hit = UsbBoardMatch.classify(text, dualPort = false)
        assertEquals(BoardFamily.MARAUDER, hit?.family)
    }

    @Test
    fun `ghostesp help output is recognised by its verb vocabulary`() {
        val text = """
            > help
            scanap - scan access points
            bledetect - detect ble devices
            badble - ble hid
            karma - karma attack
        """.trimIndent()
        val hit = UsbBoardMatch.classify(text, dualPort = false)
        assertEquals(BoardFamily.GHOSTESP, hit?.family)
    }

    @Test
    fun `ghostesp version output carries the name and a version`() {
        val text = "> version\nGhostESP v2.4.1 e0d1c2 (ESP-IDF v5.3.1)"
        val hit = UsbBoardMatch.classify(text, dualPort = false)
        assertEquals(BoardFamily.GHOSTESP, hit?.family)
        assertEquals("2.4.1", hit?.version)
    }

    @Test
    fun `silent console on a single port is not a known firmware`() {
        assertNull(UsbBoardMatch.classify("", dualPort = false))
        assertNull(UsbBoardMatch.classify("\u0000\u0000\u0000", dualPort = false))
    }

    @Test
    fun `silent console on a dual-port device is the biscuit hardware signature`() {
        val hit = UsbBoardMatch.classify("", dualPort = true)
        assertEquals(BoardFamily.BISCUIT, hit?.family)
    }

    @Test
    fun `plain esp-idf boot log is a generic esp32`() {
        val text = "ESP-ROM:esp32c5-20240112\nI (312) boot: ESP-IDF v5.3 2nd stage bootloader"
        val hit = UsbBoardMatch.classify(text, dualPort = false)
        assertEquals(BoardFamily.ESP32, hit?.family)
    }

    @Test
    fun `a satellite name is never mistaken for a board or a version`() {
        // SWISSCUBE contains "ISS", which is why the map's station match is id-based.
        assertNull(UsbBoardMatch.classify("SWISSCUBE", dualPort = false))
    }

    @Test
    fun `usb product strings name the firmware without the console`() {
        assertEquals(
            BoardFamily.GHOSTESP,
            UsbBoardMatch.classify("", dualPort = false, descriptor = "GhostESP USB Serial")?.family,
        )
        assertEquals(
            BoardFamily.MARAUDER,
            UsbBoardMatch.classify("", dualPort = false, descriptor = "ESP32 Marauder")?.family,
        )
    }

    @Test
    fun `ghostesp hostname banner is enough to identify it`() {
        val hit = UsbBoardMatch.classify("> ghostesp.local  192.168.4.1", dualPort = false)
        assertEquals(BoardFamily.GHOSTESP, hit?.family)
    }
}
