package dev.skyward.rf.core

/**
 * The three voices, and what each one is allowed to do to a sentence.
 *
 * A phone's text-to-speech engine cannot clone a performance: it has one voice per locale and a
 * pitch and rate dial. So a "voice" here is a combination of pitch, rate, accent and delivery -
 * the cadence the character actually talks in - rather than an imitation of the actor. Halo 4's
 * Cortana is calm and close-miked; Watch Dogs' ctOS is a machine reading clipped fragments;
 * Chappie is a young South African robot who answers everything with "ja" and "okay?".
 *
 * The glitched delivery is applied on top of whichever voice is chosen, because the watch alerts
 * were asked for in the broken voice regardless of who is speaking.
 */
enum class VoiceRole(val label: String, val detail: String) {
    CORTANA("CORTANA", "Halo 4: calm, close and measured"),
    WATCHER("WATCHER", "Watch Dogs: flat, clipped, machine"),
    CHAPPIE("CHAPPIE", "Chappie: young, South African, chatty"),
}

data class VoiceStyle(
    val role: VoiceRole,
    val pitch: Float,
    val rate: Float,
    val alertPitch: Float,
    val alertRate: Float,
    /** BCP-47 tag; the engine falls back to en-US when the accent is not installed. */
    val locale: String,
) {

    /** The line as this voice would say it. */
    fun render(text: String, glitch: Boolean = false): String = when (role) {
        VoiceRole.CORTANA -> if (glitch) stutter(text) else text
        VoiceRole.WATCHER -> if (glitch) stutter(clipped(text)) else clipped(text)
        VoiceRole.CHAPPIE ->
            if (glitch) "Hey. ${trimmed(stutter(text))}. Okay?"
            else "Ja. ${trimmed(text)}. Okay?"
    }

    private fun trimmed(text: String): String =
        text.trim().trimEnd('.', '!', ',', ';', ':')

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

    val roles: List<VoiceRole> = VoiceRole.values().toList()

    /**
     * Pitch and rate are the whole performance available, so the numbers are deliberate:
     * Cortana sits a little above the engine's natural voice and speaks slowly; the Watcher
     * drops below it and drags; Chappie is pushed high and hurried.
     */
    fun of(name: String?): VoiceStyle = when (name?.lowercase()) {
        "watcher" -> VoiceStyle(VoiceRole.WATCHER, 0.70f, 0.88f, 0.50f, 0.72f, "en-GB")
        "chappie" -> VoiceStyle(VoiceRole.CHAPPIE, 1.50f, 1.08f, 1.20f, 0.92f, "en-ZA")
        else -> VoiceStyle(VoiceRole.CORTANA, 1.24f, 0.94f, 0.55f, 0.78f, "en-US")
    }

    /** One line, so a voice can be heard before it is trusted with the interface. */
    fun sample(role: VoiceRole): String = when (role) {
        VoiceRole.CORTANA -> "Signal intel online. Two trackers nearby, logging is off."
        VoiceRole.WATCHER -> "Watcher active. Two trackers. Logging off."
        VoiceRole.CHAPPIE -> "Two trackers nearby, and logging is off"
    }
}
