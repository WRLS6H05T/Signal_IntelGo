package dev.skyward.rf.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/** The framing is the part of the voice that is audibly wrong when it changes, so it is pinned. */
class RadioFrameTest {

    @Test
    fun `an intel update opens with its own call and still signs off`() {
        assertEquals(
            "Watcher to operator. Intel update. Intel tab. Watcher out.",
            Radio.frame("Intel tab.", intel = true),
        )
    }

    @Test
    fun `a reply is just the transmission and an alert is a hail`() {
        assertEquals("Stopped. Watcher out.", Radio.frame("Stopped."))
        assertEquals("Watcher to operator. Flock camera. Watcher out.", Radio.frame("Flock camera.", alert = true))
    }

    @Test
    fun `an empty line is not framed into a call`() {
        assertEquals("", Radio.frame("   ", intel = true))
    }
}
