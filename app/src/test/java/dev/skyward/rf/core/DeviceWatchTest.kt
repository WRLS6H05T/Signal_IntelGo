package dev.skyward.rf.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceWatchTest {

    @Test
    fun `a Flock serial name is recognised`() {
        val v = DeviceWatch.assess(RfKind.WIFI, "Flock-1A2B", "AA:BB:CC:DD:EE:01", 0)
        assertEquals(WatchKind.FLOCK, v?.kind)
        assertEquals("SSID serial format", v?.confidence)
    }

    @Test
    fun `Flock naming and the XUNTONG company id corroborate each other`() {
        val v = DeviceWatch.assess(
            RfKind.BLE,
            "FS Ext Battery",
            "AA:BB:CC:DD:EE:02",
            DeviceWatch.XUNTONG_COMPANY_ID,
        )
        assertEquals(WatchKind.FLOCK, v?.kind)
        assertEquals("name + manufacturer id", v?.confidence)
        assertTrue(v!!.strong)
    }

    @Test
    fun `the manufacturer id alone is still a Flock match`() {
        val v = DeviceWatch.assess(RfKind.BLE, "", "AA:BB:CC:DD:EE:03", DeviceWatch.XUNTONG_COMPANY_ID)
        assertEquals(WatchKind.FLOCK, v?.kind)
        assertTrue(v!!.strong)
    }

    @Test
    fun `an Axon camera and a TASER are told apart`() {
        assertEquals(
            WatchKind.AXON,
            DeviceWatch.assess(RfKind.BLE, "AXON Body 3", "AA:BB:CC:DD:EE:04", 0)?.kind,
        )
        // Axon names its CEDs "AXON TASER 7"; the specific device is the useful label.
        assertEquals(
            WatchKind.TASER,
            DeviceWatch.assess(RfKind.BLE, "AXON TASER 7", "AA:BB:CC:DD:EE:05", 0)?.kind,
        )
        assertEquals(
            WatchKind.TASER,
            DeviceWatch.assess(RfKind.WIFI, "X26P-4411", "AA:BB:CC:DD:EE:06", 0)?.kind,
        )
    }

    @Test
    fun `a tracker is a tracker, and its maker's id only corroborates it`() {
        // Apple and Samsung ids are on every phone in the street, so a name has to match first.
        val airtag = DeviceWatch.assess(RfKind.BLE, "AirTag", "AA:BB:CC:DD:EE:10", DeviceWatch.APPLE_COMPANY_ID)
        assertEquals(WatchKind.TRACKER, airtag?.kind)
        assertEquals("name + manufacturer id", airtag?.confidence)
        assertTrue(airtag!!.strong)

        assertEquals(
            WatchKind.TRACKER,
            DeviceWatch.assess(RfKind.BLE, "Tile", "AA:BB:CC:DD:EE:11", 0)?.kind,
        )
        assertEquals(
            "name + manufacturer id",
            DeviceWatch.assess(RfKind.BLE, "Galaxy SmartTag2", "AA:BB:CC:DD:EE:12", DeviceWatch.SAMSUNG_COMPANY_ID)?.confidence,
        )
        // No name, so an iPhone's company id alone is not a tracker hit.
        assertNull(DeviceWatch.assess(RfKind.BLE, "", "AA:BB:CC:DD:EE:13", DeviceWatch.APPLE_COMPANY_ID))
        // "tile" only counts as the bare product name, not inside a cafe's SSID.
        assertNull(DeviceWatch.assess(RfKind.WIFI, "Blue Tile Coffee", "AA:BB:CC:DD:EE:14", 0))
    }

    @Test
    fun `ordinary traffic is left alone`() {
        assertNull(DeviceWatch.assess(RfKind.WIFI, "HomeNet", "AA:BB:CC:DD:EE:07", 0))
        assertNull(DeviceWatch.assess(RfKind.BLE, "", "AA:BB:CC:DD:EE:08", 0x004C))
        // "penguin" only counts as a Flock name with its prefix, so a hobby network stays out.
        assertNull(DeviceWatch.assess(RfKind.WIFI, "penguin", "AA:BB:CC:DD:EE:09", 0))
    }

    @Test
    fun `a hit is placed near the fix and stays put`() {
        val a = DeviceWatch.place("AA:BB:CC:DD:EE:01", 51.5, -0.12)
        val b = DeviceWatch.place("AA:BB:CC:DD:EE:01", 51.5, -0.12)
        assertEquals(a, b)
        assertTrue(kotlin.math.abs(a.first - 51.5) < 0.001)
        assertTrue(kotlin.math.abs(a.second - (-0.12)) < 0.001)
        // Two different addresses do not land on the same dot.
        assertTrue(a != DeviceWatch.place("AA:BB:CC:DD:EE:02", 51.5, -0.12))
    }
}
