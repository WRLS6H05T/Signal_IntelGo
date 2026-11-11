package dev.skyward.rf.core

import dev.skyward.rf.scan.BoardFamily
import dev.skyward.rf.scan.UsbBoards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A C5 hears 5 GHz; a C3 does not. The USB product string on both is the same bridge or JTAG
 * unit, so the radio was being classified by the wrong string and a C5 was only ever offered
 * 2.4 GHz and BLE.
 */
class Esp32RadioTest {

    private fun node(silicon: String) = Esp32Node(
        id = "usb:1",
        name = "SignalSensor",
        lat = null,
        lon = null,
        board = "ESP32",
        chip = "USB JTAG/serial debug unit",
        firmware = "1.1.0",
        silicon = silicon,
    )

    @Test
    fun `a C5 is dual band even when the USB string does not name the chip`() {
        val c5 = node("ESP32-C5")
        assertTrue(c5.dualBand)
        assertTrue(c5.hasBle)
    }

    @Test
    fun `a C3 stays single band`() {
        assertFalse(node("ESP32-C3").dualBand)
    }

    @Test
    fun `the board reads the silicon out of its own boot banner`() {
        val board = UsbBoards.Board(
            deviceId = 1,
            vendorId = 0x303A,
            productId = 0x1001,
            label = "ESP32 board",
            family = BoardFamily.ESP32,
            chip = "USB JTAG/serial debug unit",
            raw = "Signal IntelGo sensor v1.1.0 esp32 (ESP32-C5) GPS pin: 4 baud: 9600",
        )
        assertEquals("ESP32-C5", board.silicon)
    }

    @Test
    fun `a poll transcript does not erase the silicon the banner named`() {
        // The card's 5 GHz choices come from the banner, and the poll replaces raw with a sweep
        // listing every few seconds: the banner has to be kept apart from it.
        val board = UsbBoards.Board(
            deviceId = 1,
            vendorId = 0x303A,
            productId = 0x1001,
            label = "SignalSensor v1.2.0",
            family = BoardFamily.SIGNALSENSOR,
            chip = "USB JTAG/serial debug unit",
            banner = "Signal IntelGo sensor v1.2.0 esp32 (ESP32-C5)",
            raw = "APs found: 12 AP 3C:84:6A:11:22:33 RSSI -47 CH 6 SSID=home",
        )
        assertEquals("ESP32-C5", board.silicon)
    }

    @Test
    fun `a chip that does not appear in the banner is not guessed at`() {
        val board = UsbBoards.Board(
            deviceId = 2,
            vendorId = 0x1A86,
            productId = 0x7523,
            label = "ESP32 board",
            family = BoardFamily.ESP32,
            chip = "USB Serial",
            raw = "ets Jul 29 2019 12:21:46",
        )
        assertEquals("", board.silicon)
    }
}
