package dev.skyward.rf.sky

import dev.skyward.rf.core.FeedStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mesh line on the map.
 *
 * "Broker on" is a setting, and it was printed as one: a feed that had never connected - or had
 * dropped and never re-subscribed - claimed to be working while carrying nothing. These tests
 * pin what the line is allowed to say, and that one dead feed does not hide a live one.
 */
class MeshStatusTest {

    private val now = 1_000_000L

    private fun merge(
        mqtt: FeedStatus = FeedStatus(null, "idle", 0L, 0),
        core: FeedStatus = FeedStatus(null, "idle", 0L, 0),
        mqttEnabled: Boolean = true,
        coreEnabled: Boolean = false,
        messages: Long = 0L,
        lastMessageAt: Long = 0L,
    ) = mergeMeshStatus(mqtt, core, mqttEnabled, coreEnabled, messages, lastMessageAt, 51, now)

    @Test
    fun `a broker that never connected is not live`() {
        val s = merge(mqtt = FeedStatus(false, "Connection refused", 0L, 51))

        assertEquals(false, s.ok)
        assertTrue(s.detail.contains("Connection refused"))
        assertFalse(s.detail.contains("live"))
    }

    @Test
    fun `both feeds off say so instead of claiming a broker`() {
        val s = merge(mqttEnabled = false, coreEnabled = false)

        assertEquals(false, s.ok)
        assertEquals("mesh feed off", s.detail)
    }

    @Test
    fun `a connected feed that has delivered nothing says so`() {
        val s = merge(mqtt = FeedStatus(true, "mesh live · broker · msh/#", now, 51))

        assertEquals(true, s.ok)
        assertTrue(s.detail.contains("mqtt live"))
        assertTrue(s.detail.contains("no data yet"))
    }

    @Test
    fun `a delivering feed reports how long ago the last message was`() {
        val s = merge(
            mqtt = FeedStatus(true, "mesh live", now, 51),
            messages = 1_240L,
            lastMessageAt = now - 4_000L,
        )

        assertTrue(s.detail.contains("1240 msgs"))
        assertTrue(s.detail.contains("4s ago"))
    }

    @Test
    fun `a dead meshtastic feed does not hide a live observer bridge`() {
        val s = merge(
            mqtt = FeedStatus(false, "connection lost", now, 51),
            core = FeedStatus(true, "core live", now, 51),
            coreEnabled = true,
            messages = 3L,
            lastMessageAt = now - 1_000L,
        )

        assertEquals(true, s.ok)
        assertTrue(s.detail.contains("core live"))
        assertTrue(s.detail.contains("connection lost"))
    }

    @Test
    fun `a subscription the broker refused is a failure, not a live feed`() {
        val s = merge(mqtt = FeedStatus(false, "mqtt refused msh/US/2/json/#", now, 51))

        assertEquals(false, s.ok)
        assertTrue(s.detail.contains("refused"))
    }

    @Test
    fun `an enabled feed nobody started is reported as down`() {
        val s = merge(mqtt = FeedStatus(null, "stopped", now, 0))

        assertEquals(false, s.ok)
        assertTrue(s.detail.contains("stopped"))
    }
}
