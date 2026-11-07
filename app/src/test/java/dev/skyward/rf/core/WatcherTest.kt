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
                logged = Watcher.Logged(
                    sats = 9,
                    aircraft = 14,
                    watch = 2,
                    wifi = 91,
                    ble = 42,
                ),
            ),
        )
        assertTrue(report.indexOf("Connected devices") < report.indexOf("Signal intel"))
        assertTrue(report.contains("1 Flock camera and 1 Bluetooth tracker"))
        assertTrue(report.contains("91 Wi-Fi networks"))
        assertTrue(report.contains("3 cell towers"))
        assertTrue(report.contains("GPS fix held"))
        // The report ends on what was caught, not on whether recording is armed: "logging off"
        // followed by the sign-off was heard as two goodbyes.
        assertTrue(report.contains("158 entities"))
        assertTrue(
            report.endsWith(
                "I have 9 satellites, 14 aircraft, 2 watch matches, 91 Wi-Fi networks and " +
                    "42 Bluetooth devices.",
            ),
        )
        assertFalse(report.contains("Logging is"))
    }

    @Test
    fun `an empty sky says so instead of inventing a hit`() {
        val report = Watcher.intelReport(Watcher.ReportInput(gps = false))
        assertTrue(report.contains("Nothing matched"))
        assertTrue(report.contains("No GPS fix"))
        assertTrue(report.contains("Nothing logged yet"))
        assertFalse(report.contains("Logging"))
    }

    @Test
    fun `the timed update reads the entity table and then the sightings`() {
        val update = Watcher.intelUpdate(
            Watcher.ReportInput(
                gps = true,
                logging = true,
                km = 12.4,
                unit = Units.KM,
                logged = Watcher.Logged(
                    sats = 9,
                    aircraft = 14,
                    drones = 2,
                    mesh = 1,
                    cells = 3,
                    watch = 1,
                    wifi = 91,
                    ble = 42,
                ),
                sightings = listOf(
                    hit(WatchKind.FLOCK, "Flock-1A2B", -50),
                    hit(WatchKind.TRACKER, "Tile", -72),
                ),
            ),
            5,
        )
        // Both tables by default, live first, then what has been caught.
        assertTrue(update.startsWith("Intel and entity tables."))
        assertTrue(update.indexOf("Signal intel") < update.indexOf("Entity table."))
        assertTrue(update.contains("163 entities"))
        assertTrue(update.contains("9 satellites"))
        assertTrue(update.contains("2 drones"))
        assertTrue(update.contains("1 watch match"))
        assertTrue(update.contains("91 Wi-Fi networks"))
        assertTrue(update.contains("2 sightings in the last 5 minutes"))
        assertTrue(update.contains("Flock camera, strong"))
        assertTrue(update.contains("Bluetooth tracker, weak"))
        // The call and the sign-off are the radio's, not the text's.
        assertFalse(update.contains("Watcher out"))
    }

    @Test
    fun `the report style decides which table is read`() {
        val input = Watcher.ReportInput(
            wifi = 91,
            ble = 42,
            gps = true,
            logged = Watcher.Logged(wifi = 91, ble = 42),
        )
        val intel = Watcher.intelReport(input, Watcher.STYLE_INTEL)
        assertTrue(intel.contains("Signal intel"))
        assertFalse(intel.contains("Logged entities"))

        val entities = Watcher.intelReport(input, Watcher.STYLE_ENTITIES)
        assertTrue(entities.contains("Logged entities"))
        assertFalse(entities.contains("Signal intel"))
        assertTrue(entities.contains("91 Wi-Fi networks and 42 Bluetooth devices"))

        val both = Watcher.intelReport(input, Watcher.STYLE_BOTH)
        assertTrue(both.indexOf("Signal intel") < both.indexOf("Logged entities"))
        // A settings file written before this choice existed reads as both, not as silence.
        assertEquals(both, Watcher.intelReport(input, "some future style"))

        // The timed update obeys the same setting, and names the one table it read.
        val live = Watcher.intelUpdate(input, 5, style = Watcher.STYLE_INTEL)
        assertTrue(live.startsWith("Intel table."))
        assertFalse(live.contains("Entity table"))

        val caught = Watcher.intelUpdate(input, 5, style = Watcher.STYLE_ENTITIES)
        assertTrue(caught.startsWith("Entity table."))
        assertFalse(caught.contains("Signal intel"))
        assertTrue(caught.contains("133 entities"))
    }

    @Test
    fun `an update with nothing new says so in the window it was asked about`() {
        val update = Watcher.intelUpdate(Watcher.ReportInput(gps = false), 30)
        assertTrue(update.contains("Nothing new in the last 30 minutes"))
        assertTrue(update.contains("No GPS fix"))
        assertTrue(update.contains("Nothing caught yet"))
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
