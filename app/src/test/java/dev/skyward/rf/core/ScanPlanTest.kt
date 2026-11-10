package dev.skyward.rf.core

import dev.skyward.rf.scan.BoardFamily
import dev.skyward.rf.scan.Esp32Console
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A board's sweep assignment, and the verbs it turns into.
 *
 * The menu and the poll have to agree: an option that shows in the card but never reaches the
 * board is a switch that does nothing, and a verb sent for a radio the assignment left out is
 * the poll window being spent on the wrong sweep.
 */
class ScanPlanTest {

    @Test
    fun `a dual band board is offered the four sweeps it can do`() {
        assertEquals(
            listOf(ScanPlan.BLE, ScanPlan.W24, ScanPlan.DUAL, ScanPlan.DUAL + ScanPlan.BLE_SUFFIX),
            ScanPlan.bandOptions(dualBand = true, ble = true),
        )
    }

    @Test
    fun `a single band board is never offered 5 GHz`() {
        assertEquals(
            listOf(ScanPlan.BLE, ScanPlan.W24),
            ScanPlan.bandOptions(dualBand = false, ble = true),
        )
        assertEquals(listOf(ScanPlan.W24), ScanPlan.bandOptions(dualBand = false, ble = false))
    }

    @Test
    fun `ble beside both bands keeps both`() {
        val code = ScanPlan.DUAL + ScanPlan.BLE_SUFFIX
        assertTrue(ScanPlan.hasBle(code))
        assertTrue(ScanPlan.hasWifi(code))
        assertEquals(ScanPlan.DUAL, ScanPlan.band(code))
        assertEquals("WI-FI 2.4+5 + BLE", ScanPlan.label(code))
    }

    @Test
    fun `a ble only code sweeps no wi-fi`() {
        assertTrue(ScanPlan.hasBle(ScanPlan.BLE))
        assertFalse(ScanPlan.hasWifi(ScanPlan.BLE))
        assertEquals("BLE", ScanPlan.label(ScanPlan.BLE))
    }

    @Test
    fun `the gps toggle leaves the ble part of a mode alone`() {
        val on = ScanPlan.withGps(ScanPlan.DUAL + ScanPlan.BLE_SUFFIX, true)
        assertTrue(ScanPlan.hasGps(on))
        assertTrue(ScanPlan.hasBle(on))
        assertEquals("WI-FI 2.4+5 + BLE + GPS", ScanPlan.label(on))
        val off = ScanPlan.withGps(on, false)
        assertFalse(ScanPlan.hasGps(off))
        assertTrue(ScanPlan.hasBle(off))
    }

    @Test
    fun `an assignment saved before ble had its own mode still reads`() {
        assertEquals("WI-FI 5", ScanPlan.label(ScanPlan.W5))
    }

    @Test
    fun `a ble only assignment asks the board for ble and a fix`() {
        assertEquals(
            listOf("gps\r\n", "blescan\r\n"),
            Esp32Console.commandsFor(BoardFamily.SIGNALSENSOR, null, ScanPlan.BLE),
        )
    }

    @Test
    fun `a wi-fi assignment asks for no ble sweep`() {
        assertEquals(
            listOf("gps\r\n", "scanap\r\n"),
            Esp32Console.commandsFor(BoardFamily.SIGNALSENSOR, null, ScanPlan.W24),
        )
    }

    @Test
    fun `ble beside both bands asks for both sweeps`() {
        val cmds = Esp32Console.commandsFor(
            BoardFamily.SIGNALSENSOR,
            null,
            ScanPlan.DUAL + ScanPlan.BLE_SUFFIX,
        )
        assertTrue(cmds.any { it.startsWith("scanap") })
        assertTrue(cmds.any { it.startsWith("blescan") })
    }

    @Test
    fun `a ghostesp board still stops its last sweep before a ble only poll`() {
        val cmds = Esp32Console.commandsFor(BoardFamily.GHOSTESP, null, ScanPlan.BLE)
        assertEquals("stopscan\r\n", cmds.first())
        assertTrue(cmds.any { it.startsWith("bledetect") })
        assertFalse(cmds.any { it.startsWith("scanap") })
    }

    @Test
    fun `no assignment keeps every verb the board family answers`() {
        assertEquals(
            Esp32Console.commands(BoardFamily.SIGNALSENSOR),
            Esp32Console.commandsFor(BoardFamily.SIGNALSENSOR, null, null),
        )
    }
}
