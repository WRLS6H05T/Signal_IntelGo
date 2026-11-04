package dev.skyward.rf.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatcherTest {

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
    fun `the wake word survives the forms a recogniser returns`() {
        assertTrue(Watcher.hasWake("Watcher, intel report"))
        assertTrue(Watcher.hasWake("wotcher what is that"))
        // A flat British "watcher" very often comes back as two words.
        assertTrue(Watcher.hasWake("watch her, intel report"))
        assertFalse(Watcher.hasWake("what time is it"))
        assertFalse(Watcher.hasWake("I am watching the road"))
    }

    @Test
    fun `commands are read after the wake word`() {
        assertEquals(Watcher.Command.REPORT, Watcher.parse("Watcher, intel report"))
        assertEquals(Watcher.Command.SETUP, Watcher.parse("watcher open setup"))
        assertEquals(Watcher.Command.MAP, Watcher.parse("watcher show me the map"))
        assertEquals(Watcher.Command.LOG, Watcher.parse("watcher open the log"))
        assertEquals(Watcher.Command.OPEN_MENU, Watcher.parse("watcher show menu"))
        assertEquals(Watcher.Command.CLOSE_MENU, Watcher.parse("watcher close menu"))
        assertEquals(Watcher.Command.START, Watcher.parse("watcher start logging"))
        assertEquals(Watcher.Command.STOP, Watcher.parse("watcher stop logging"))
        assertEquals(Watcher.Command.MILES, Watcher.parse("watcher switch to miles"))
        assertEquals(Watcher.Command.FAHRENHEIT, Watcher.parse("watcher fahrenheit"))
        // "unmute" must not be read as "mute".
        assertEquals(Watcher.Command.UNMUTE, Watcher.parse("watcher unmute"))
        assertEquals(Watcher.Command.SLEEP, Watcher.parse("watcher sleep"))
    }

    @Test
    fun `the two-word wake phrase is stripped like the one-word one`() {
        assertEquals(Watcher.Command.REPORT, Watcher.parse("watch her intel report"))
        assertEquals("intel report", Watcher.wakeOnly("watch her intel report"))
    }

    @Test
    fun `a tapped session needs no wake word at all`() {
        assertEquals(Watcher.Command.REPORT, Watcher.parse("intel report"))
        assertEquals(Watcher.Command.SETUP, Watcher.parse("open setup"))
        assertEquals(Watcher.Command.START, Watcher.parse("start logging"))
    }

    @Test
    fun `the wake word alone is not a command`() {
        assertNull(Watcher.parse("watcher"))
        assertNull(Watcher.parse("Watcher,"))
        assertNull(Watcher.parse("open the window"))
        assertNull(Watcher.parse("I had a flat white"))
    }

    @Test
    fun `the intel report names the devices before the signal`() {
        val report = Watcher.intelReport(
            Watcher.ReportInput(
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
        val report = Watcher.intelReport(Watcher.ReportInput(gps = false))
        assertTrue(report.contains("Nothing matched"))
        assertTrue(report.contains("No GPS fix"))
        assertTrue(report.contains("Logging is off"))
    }

    @Test
    fun `an alert names the family, and a tracker by its name`() {
        assertTrue(Watcher.alert(hit(WatchKind.FLOCK, "")).startsWith("Flock camera"))
        assertEquals(
            "Bluetooth tracker, Tile,. Signal strong.",
            Watcher.alert(hit(WatchKind.TRACKER, "Tile", -50)),
        )
        assertTrue(Watcher.alert(hit(WatchKind.AXON, "", -90)).contains("barely there"))
    }
}
