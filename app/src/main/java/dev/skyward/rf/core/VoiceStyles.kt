package dev.skyward.rf.core

/**
 * The Watcher's two voices, and what each one is allowed to do to a sentence.
 *
 * There is one character - Watch Dogs' ctOS - and it is a machine in either gender. A phone's
 * text-to-speech engine cannot clone a performance: it has one voice per locale and a pitch and
 * rate dial, so the machine-ness is carried by pacing rather than by the engine: every comma,
 * semicolon and dash becomes a full stop, and a long report is read as a list of findings, one
 * idea per sentence. The gender is the pitch, low for the man and above the engine's natural
 * voice for the woman, which is all the control a TTS engine actually offers.
 *
 * The glitched delivery is applied on top of whichever voice is chosen, because the watch alerts
 * were asked for in the broken voice regardless of who is speaking.
 */
enum class WatcherGender(val label: String) {
    FEMALE("WOMAN"),
    MALE("MAN"),
}

data class VoiceStyle(
    val gender: WatcherGender,
    val pitch: Float,
    val rate: Float,
    val alertPitch: Float,
    val alertRate: Float,
    /** BCP-47 tag; the engine falls back to en-US when the accent is not installed. */
    val locale: String = "en-US",
) {

    /** The line as this voice would say it. */
    fun render(text: String, glitch: Boolean = false): String =
        if (glitch) stutter(clipped(text)) else clipped(text)

    /**
     * Watch Dogs' cadence: one idea per sentence. Commas, semicolons and dashes become full
     * stops, so a long report reads as a machine ticking through findings instead of a person
     * running out of breath.
     */
    private fun clipped(text: String): String {
        val parts = text
            .split(Regex("(?<=[.!?])\\s+|,\\s*|;\\s*|\\s+-\\s+"))
            .map { it.trim().trimEnd('.', '!', ',', ';', ':') }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return text
        return parts.joinToString(". ") { it.replaceFirstChar { c -> c.uppercaseChar() } } + "."
    }

    /** A corrupted fragment: the first word arrives twice before the line settles. */
    private fun stutter(text: String): String {
        val first = text.substringBefore(' ').trimEnd('.', ',', '!', '?', ':')
        if (first.isEmpty()) return text
        return "$first, $text"
    }
}

object VoiceStyles {

    val genders: List<WatcherGender> = WatcherGender.values().toList()

    /**
     * Pitch and rate are the whole performance available. The woman sits a little above the
     * engine's natural voice; the man well below it. Both speak slowly enough to sound like a
     * machine reading, not a person talking.
     */
    fun of(name: String?): VoiceStyle = when (name?.lowercase()) {
        "male", "man", "m" -> VoiceStyle(WatcherGender.MALE, 0.52f, 0.86f, 0.40f, 0.70f)
        else -> VoiceStyle(WatcherGender.FEMALE, 1.12f, 0.92f, 0.72f, 0.74f)
    }

    /** One line, so a voice can be heard before it is trusted with the interface. */
    fun sample(gender: WatcherGender): String = when (gender) {
        WatcherGender.FEMALE -> "Watcher active. Two trackers. Logging off."
        WatcherGender.MALE -> "Watcher active. Two trackers. Logging off."
    }
}
