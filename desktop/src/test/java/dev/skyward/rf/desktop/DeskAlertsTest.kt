package dev.skyward.rf.desktop

import dev.skyward.rf.core.RfKind
import dev.skyward.rf.core.WatchHit
import dev.skyward.rf.core.WatchKind
import dev.skyward.rf.desktop.alerts.DeskAlerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's alert etiquette, which the desk inherited whole: one alert at a time, eight seconds
 * apart, and no device announced twice inside five minutes. A wardrive down a street of Flock
 * hardware is the case these exist for.
 */
class DeskAlertsTest {

    private fun hit(id: String, rssi: Int = -50, confidence: String = "SSID serial format") =
        WatchHit(
            kind = WatchKind.FLOCK,
            id = id,
            name = "Flock-$id",
            rssi = rssi,
            radio = RfKind.WIFI,
            source = "COM3",
            at = 0L,
            confidence = confidence,
        )

    @Test
    fun `alerts come one at a time, eight seconds apart`() {
        val desk = DeskAlerts()
        desk.scan(listOf(hit("A"), hit("B")), 1_000_000)
        assertEquals(1, desk.alerts.size)

        // Inside the gap: the second family keeps until the first has been read.
        desk.scan(listOf(hit("A"), hit("B")), 1_004_000)
        assertEquals(1, desk.alerts.size)

        desk.scan(listOf(hit("A"), hit("B")), 1_009_000)
        assertEquals(2, desk.alerts.size)
        // Newest first: the second family, once the gap for the first had passed.
        assertEquals("B", desk.alerts.first().id)
    }

    @Test
    fun `a device is not announced again for five minutes`() {
        val desk = DeskAlerts()
        desk.scan(listOf(hit("A")), 1_000_000)
        desk.scan(listOf(hit("A")), 1_070_000) // a minute later, well clear of the gap
        assertEquals(1, desk.alerts.size)

        desk.scan(listOf(hit("A")), 1_310_000) // past the five-minute repeat window
        assertEquals(2, desk.alerts.size)
    }

    @Test
    fun `the ledger drops what is older than an hour`() {
        val desk = DeskAlerts()
        desk.scan(listOf(hit("A")), 1_000_000)
        desk.scan(listOf(hit("B")), 2_400_000) // twenty minutes on: two alerts in the hour
        assertEquals(2, desk.alerts.size)

        desk.scan(emptyList(), 4_700_000) // over an hour after the first, under an hour after B
        assertEquals(1, desk.alerts.size)
        assertEquals("B", desk.alerts.first().id)
    }

    @Test
    fun `the strip closes itself after the banner window`() {
        val desk = DeskAlerts()
        desk.scan(listOf(hit("A")), 1_000_000)
        assertTrue(desk.bannerOpen(1_005_000))
        assertTrue(!desk.bannerOpen(1_020_000))

        desk.dismiss()
        assertTrue(desk.latest == null)
    }
}
