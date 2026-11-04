package dev.skyward.rf.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CortanaTest {

    private fun hit(kind: WatchKind, name: String, rssi: Int = -60) = WatchView(
        kind = kind,
        id = "AA:BB:CC:$name",
        name = name,
        radio = RfKind.BLE,
        rssi = rssi,
        source = "local",
        lat = 0.0,
        lon = 0.0,
        score = 50,
    )

    @Test
    fun `the wake word survives a mishearing but not an ordinary word`() {
        assertTrue(Cortana.hasWake("Cortana, intel report"))
        assertTrue(Cortana.hasWake("hey cortona what is that"))
        assertFalse(Cortana.hasWake("what time is it"))
        assertFalse(Cortana.hasWake("open the carton"))
    }

    @Test
    fun `commands are read after the wake word`() {
        assertEquals(Cortana.Command.REPORT, Cortana.parse("Cortana, intel report"))
        assertEquals(Cortana.Command.SETUP, Cortana.parse("cortana open setup"))
        assertEquals(Cortana.Command.MAP, Cortana.parse("cortana show me the map"))
        assertEquals(Cortana.Command.LOG, Cortana.parse("cortana open the log"))
        assertEquals(Cortana.Command.OPEN_MENU, Cortana.parse("cortana show menu"))
        assertEquals(Cortana.Command.CLOSE_MENU, Cortana.parse("cortana close menu"))
        assertEquals(Cortana.Command.START, Cortana.parse("cortana start logging"))
        assertEquals(Cortana.Command.STOP, Cortana.parse("cortana stop logging"))
        assertEquals(Cortana.Command.MILES, Cortana.parse("cortana switch to miles"))
        assertEquals(Cortana.Command.FAHRENHEIT, Cortana.parse("cortana fahrenheit"))
        // "unmute" must not be read as "mute".
        assertEquals(Cortana.Command.UNMUTE, Cortana.parse("cortana unmute"))
        assertEquals(Cortana.Command.SLEEP, Cortana.parse("cortana sleep"))
    }

    @Test
    fun `the wake word alone is not a command`() {
        assertNull(Cortana.parse("cortana"))
        assertNull(Cortana.parse("Cortana,"))
        // Without the name in front, an unrelated sentence is not obeyed.
        assertNull(Cortana.parse("open the window"))
        assertNull(Cortana.parse("I had a flat white"))
    }

    @Test
    fun `the intel report names the devices before the signal`() {
        val report = Cortana.intelReport(
            Cortana.ReportInput(
                watches = listOf(hit(WatchKind.FLOCK, "Flock-1A2B"), hit(WatchKind.TRACKER, "Tile")),
                wifi = 91,
                ble = 42,
                cells = 3,
                sats = 9,
                aircraft = 14,
                gps = true,
                logging = true,
                km = 12.4,
                unit = Units.MILES,
            ),
        )
        assertTrue(report.indexOf("Connected devices") < report.indexOf("Signal intel"))
        assertTrue(report.contains("1 Flock camera and 1 Bluetooth tracker"))
        assertTrue(report.contains("91 Wi-Fi networks"))
        assertTrue(report.contains("3 cell towers"))
        assertTrue(report.contains("GPS fix held"))
        assertTrue(report.endsWith("Logging is on, 7.7 mi so far."))
    }

    @Test
    fun `an empty sky says so instead of inventing a hit`() {
        val report = Cortana.intelReport(Cortana.ReportInput(gps = false))
        assertTrue(report.contains("Nothing matched"))
        assertTrue(report.contains("No GPS fix"))
        assertTrue(report.contains("Logging is off"))
    }

    @Test
    fun `an alert names the family, and a tracker by its name`() {
        assertTrue(Cortana.alert(hit(WatchKind.FLOCK, "")).startsWith("Flock camera"))
        assertEquals(
            "Bluetooth tracker, Tile,. Signal strong.",
            Cortana.alert(hit(WatchKind.TRACKER, "Tile", -50)),
        )
        assertTrue(Cortana.alert(hit(WatchKind.AXON, "", -90)).contains("barely there"))
    }
}
