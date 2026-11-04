package dev.skyward.rf.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceStyleTest {

    @Test
    fun `an unknown or missing role is Cortana`() {
        assertEquals(VoiceRole.CORTANA, VoiceStyles.of(null).role)
        assertEquals(VoiceRole.CORTANA, VoiceStyles.of("").role)
        assertEquals(VoiceRole.CORTANA, VoiceStyles.of("something-else").role)
    }

    @Test
    fun `each voice has its own pitch, rate and accent`() {
        val cortana = VoiceStyles.of("cortana")
        val watcher = VoiceStyles.of("watcher")
        val chappie = VoiceStyles.of("chappie")

        assertEquals("en-US", cortana.locale)
        assertEquals("en-GB", watcher.locale)
        assertEquals("en-ZA", chappie.locale)
        assertNotEquals(cortana.pitch, watcher.pitch)
        assertNotEquals(watcher.pitch, chappie.pitch)
        // The Watcher is the low one and Chappie the high one, by design.
        assertTrue(watcher.pitch < cortana.pitch)
        assertTrue(chappie.pitch > cortana.pitch)
    }

    @Test
    fun `the glitched delivery is lower and slower in every voice`() {
        for (role in VoiceStyles.roles) {
            val style = VoiceStyles.of(role.name)
            assertTrue(style.alertPitch < style.pitch)
            assertTrue(style.alertRate < style.rate)
        }
    }

    @Test
    fun `Cortana speaks the line as written and stutters when glitched`() {
        val style = VoiceStyles.of("cortana")
        val line = "Flock camera. Signal strong."
        assertEquals(line, style.render(line))
        assertEquals("Flock, Flock camera. Signal strong.", style.render(line, glitch = true))
    }

    @Test
    fun `the Watcher clips a report into one idea per sentence`() {
        val style = VoiceStyles.of("watcher")
        assertEquals(
            "Two trackers. Logging off. GPS fix held.",
            style.render("Two trackers, logging off; GPS fix held."),
        )
        // A glitched alert repeats the first word of the clipped line.
        assertTrue(style.render("Flock camera, strong.", glitch = true).startsWith("Flock, Flock camera"))
    }

    @Test
    fun `Chappie wraps the line in his own words`() {
        val style = VoiceStyles.of("chappie")
        assertEquals("Ja. Logging is off. Okay?", style.render("Logging is off."))
        assertEquals("Hey. Tracker, Tracker spotted. Okay?", style.render("Tracker spotted.", glitch = true))
    }

    @Test
    fun `a sample line exists for every voice and is not empty`() {
        for (role in VoiceStyles.roles) {
            val spoken = VoiceStyles.of(role.name).render(VoiceStyles.sample(role))
            assertTrue(spoken.isNotBlank())
        }
    }
}
