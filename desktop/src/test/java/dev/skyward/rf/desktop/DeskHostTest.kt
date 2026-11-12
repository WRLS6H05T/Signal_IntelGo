package dev.skyward.rf.desktop

import dev.skyward.rf.desktop.hardware.Bands
import dev.skyward.rf.desktop.hardware.SensorIdentity
import dev.skyward.rf.desktop.session.SessionRecorder
import dev.skyward.rf.scan.BoardFamily
import dev.skyward.rf.scan.Esp32Console
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desk reads a board with the phone's own parser, so these tests are about the two things
 * the desk adds on top of it: who the board is, and which band a sweep channel came from.
 */
class DeskHostTest {

    private val c5Banner = "Signal IntelGo sensor v1.3.0 esp32 (ESP32-C5)"

    @Test
    fun `a C5 banner names the chip and its firmware`() {
        val id = SensorIdentity.from(c5Banner)
        assertNotNull(id)
        assertEquals(BoardFamily.SIGNALSENSOR, id!!.family)
        assertEquals("ESP32-C5", id.chip)
        assertEquals("1.3.0", id.firmware)
        assertTrue(id.dualBand)
        assertTrue(id.hasBle)
    }

    @Test
    fun `a C3 banner is the same firmware without 5 GHz`() {
        val id = SensorIdentity.from("Signal IntelGo sensor v1.3.0 esp32 (ESP32-C3)")
        assertNotNull(id)
        assertEquals("ESP32-C3", id!!.chip)
        assertFalse(id.dualBand)
    }

    @Test
    fun `somebody else's firmware is not called a SignalSensor`() {
        val ghost = SensorIdentity.from("GhostESP v2.1 · scanap bledetect badble karma")
        assertEquals(BoardFamily.GHOSTESP, ghost!!.family)
        val marauder = SensorIdentity.from("ESP32 Marauder 1.10.0 By: justcallmekoko")
        assertEquals(BoardFamily.MARAUDER, marauder!!.family)
    }

    @Test
    fun `a silent console is not an identity`() {
        assertNull(SensorIdentity.from(""))
        assertNull(SensorIdentity.from("              "))
        assertNull(SensorIdentity.from("random boot noise with no chip name"))
    }

    @Test
    fun `5 GHz is told apart by channel, which is the only proof the second pass ran`() {
        assertEquals(Bands.W24, Bands.of(1))
        assertEquals(Bands.W24, Bands.of(6))
        assertEquals(Bands.W24, Bands.of(14))
        assertEquals(Bands.W5, Bands.of(36))
        assertEquals(Bands.W5, Bands.of(149))
        assertEquals(36, Bands.channelOf(5180))
        assertEquals(6, Bands.channelOf(2437))
    }

    @Test
    fun `a session records both bands and exports what it recorded`() {
        val recorder = SessionRecorder()
        recorder.start("desk-test", atMillis = 1_000)

        val sweep = Esp32Console.Sweep(
            aps = listOf(
                Esp32Console.Ap("AA:BB:CC:DD:EE:01", "corner-5g", -61, 44),
                Esp32Console.Ap("AA:BB:CC:DD:EE:02", "corner-24", -48, 6),
            ),
            devices = listOf(Esp32Console.Device("11:22:33:44:55:66", "tag", -72)),
        )
        val reading = Esp32Console.Reading(lat = 51.5, lon = -0.12, sats = 9, fix = true)

        recorder.recordSweep("COM9", "ESP32-C5", sweep, reading, atMillis = 2_000)
        val summary = recorder.stop(atMillis = 9_000)!!

        assertEquals(2, summary.aps)
        assertEquals(1, summary.ble)
        assertEquals(1, summary.ghz5)
        assertEquals(8_000, summary.durationMs)

        val csv = recorder.csv()
        assertTrue(csv.startsWith("time_iso,time_ms,board,chip,kind,address,name,rssi,channel,band,lat,lon"))
        assertTrue(csv.contains("corner-5g"))
        assertTrue(csv.contains(",5G,"))
        // One header plus three sightings; `lines()` keeps the empty tail after the last \n.
        assertEquals(4, csv.lines().count { it.isNotBlank() })
        // The board's own fix travels with its rows; nothing invents a position.
        assertTrue(csv.contains("51.5"))
    }

    @Test
    fun `nothing is recorded outside a session, and a board with no fix records blank coordinates`() {
        val recorder = SessionRecorder()
        val sweep = Esp32Console.Sweep(
            aps = listOf(Esp32Console.Ap("AA:BB:CC:DD:EE:03", "late", -70, 11)),
        )
        recorder.recordSweep("COM9", "ESP32-C5", sweep, Esp32Console.Reading())
        assertEquals(0, recorder.count)

        recorder.start("desk-test", atMillis = 1_000)
        recorder.recordSweep("COM9", "ESP32-C5", sweep, Esp32Console.Reading())
        assertEquals(1, recorder.count)
        val json = recorder.json()
        assertTrue(json.contains("\"lat\": null"))
        assertTrue(json.contains("\"band\": \"2.4G\""))
    }
}
