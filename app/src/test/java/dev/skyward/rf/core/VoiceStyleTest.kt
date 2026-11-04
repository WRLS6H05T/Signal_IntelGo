package dev.skyward.rf.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceStyleTest {

    @Test
    fun `the woman's machine voice is the default`() {
        assertEquals(WatcherGender.FEMALE, VoiceStyles.of(null).gender)
        assertEquals(WatcherGender.FEMALE, VoiceStyles.of("").gender)
        assertEquals(WatcherGender.FEMALE, VoiceStyles.of("female").gender)
        assertEquals(WatcherGender.FEMALE, VoiceStyles.of("something-else").gender)
        assertEquals(WatcherGender.MALE, VoiceStyles.of("male").gender)
        assertEquals(WatcherGender.MALE, VoiceStyles.of("man").gender)
    }

    @Test
    fun `the man is pitched below the woman`() {
        val woman = VoiceStyles.of("female")
        val man = VoiceStyles.of("male")
        assertTrue(man.pitch < woman.pitch)
        assertTrue(man.rate <= woman.rate)
        assertEquals("en-US", woman.locale)
        assertEquals("en-US", man.locale)
    }

    @Test
    fun `the glitched delivery is lower and slower in both voices`() {
        for (gender in VoiceStyles.genders) {
            val style = VoiceStyles.of(gender.name)
            assertTrue(style.alertPitch < style.pitch)
            assertTrue(style.alertRate < style.rate)
        }
    }

    @Test
    fun `a report is clipped into one idea per sentence`() {
        val style = VoiceStyles.of("female")
        assertEquals(
            "Two trackers. Logging off. GPS fix held.",
            style.render("Two trackers, logging off; GPS fix held."),
        )
    }

    @Test
    fun `a glitched alert repeats the first word`() {
        val style = VoiceStyles.of("male")
        assertEquals(
            "Flock, Flock camera. Signal strong.",
            style.render("Flock camera. Signal strong.", glitch = true),
        )
    }

    @Test
    fun `both voices render a sample`() {
        for (gender in VoiceStyles.genders) {
            val spoken = VoiceStyles.of(gender.name).render(VoiceStyles.sample(gender))
            assertTrue(spoken.isNotBlank())
            assertTrue(spoken.contains("Watcher active"))
        }
    }
}
