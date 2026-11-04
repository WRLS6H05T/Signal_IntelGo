package dev.skyward.rf.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerLogsTest {

    private fun session(points: Int): String {
        val sb = StringBuilder("""{"id":"1700000000000","name":"Drive","startedAt":1700000000000,"points":[""")
        repeat(points) { i ->
            if (i > 0) sb.append(',')
            sb.append(
                """{"lat":51.5001,"lon":-0.1201,"t":${1700000000000 + i * 3000},"score":72,""" +
                    """"lastWifi":"BT-Home-2G","bestWifiRssi":-61,"networks":[{"id":"aa:bb:cc:dd:ee:${i % 40}",""" +
                    """"name":"BT-Home-2G","rssi":-61}]}""",
            )
        }
        sb.append("]}")
        return sb.toString()
    }

    @Test
    fun `a drive survives the round trip`() {
        val json = session(40)
        val parts = PeerLogs.pack(json)

        assertTrue(parts.isNotEmpty())
        assertTrue("parts must fit one message", parts.all { it.length <= PeerLogs.PART_CHARS })
        assertEquals(json, PeerLogs.unpack(parts))
    }

    @Test
    fun `a drive is only rebuilt from parts in the right order`() {
        // Letters with no pattern, so compression cannot fold the payload into one part: this
        // test needs a real multi-part transfer to say anything about ordering.
        val random = java.util.Random(11)
        val json = buildString { repeat(6_000) { append(('a'.code + random.nextInt(26)).toChar()) } }
        val parts = PeerLogs.pack(json)
        assertTrue("expected a multi-part transfer, got ${parts.size}", parts.size > 1)

        // The receiver keeps each slice by index, so a set that arrives out of order (or is
        // missing a slice) must never be mistaken for the file.
        val rotated = parts.drop(1) + parts.first()
        assertNotEquals(json, PeerLogs.unpack(rotated))
        assertNull(PeerLogs.unpack(parts.drop(1)))
        assertEquals(json, PeerLogs.unpack(parts))
    }

    @Test
    fun `compression makes a long drive fit the link`() {
        val json = session(600)
        val parts = PeerLogs.pack(json)

        assertTrue("a 600-fix drive must not exceed the part cap", parts.size < PeerLogs.MAX_PARTS)
        assertTrue(
            "gzips to a fraction of its size, was ${json.length} chars",
            parts.sumOf { it.length } < json.length / 2,
        )
        assertEquals(json, PeerLogs.unpack(parts))
    }

    @Test
    fun `rubbish is refused instead of decoded`() {
        assertNull(PeerLogs.unpack(emptyList()))
        assertNull(PeerLogs.unpack(listOf("")))
        assertNull(PeerLogs.unpack(listOf("not base64 at all!!")))
        assertNull(PeerLogs.pack("").firstOrNull())
    }
}
