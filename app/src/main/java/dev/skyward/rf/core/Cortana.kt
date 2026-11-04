package dev.skyward.rf.core

import java.util.Locale

/**
 * What Cortana hears and what she says back.
 *
 * The parsing is deliberately small and explicit: a grammar of a dozen intents that a driver
 * can say without looking at the screen, not a language model. Every phrase is matched against
 * the words after the wake word, so "Cortana, open setup" and "open setup" both work once she is
 * awake, and nothing here touches Android - the microphone and the speaker live in
 * `voice/CortanaVoice`, which makes every rule below testable without a phone.
 */
object Cortana {

    const val WAKE = "cortana"

    /** The four screens the voice can open, by the tab index the scaffold uses. */
    enum class Tab(val index: Int) { MAP(0), DRIVE(1), LOG(2), SETUP(3) }

    enum class Command {
        REPORT, HELP,
        MAP, DRIVE, LOG, SETUP, OPEN_MENU, CLOSE_MENU,
        START, STOP,
        MILES, KILOMETRES, CELSIUS, FAHRENHEIT,
        MUTE, UNMUTE, SLEEP,
    }

    /** A screen change asked for by voice; the UI consumes it and clears the flow. */
    sealed interface Nav {
        data class Go(val tab: Tab) : Nav
        object OpenMenu : Nav
        object CloseMenu : Nav
    }

    /** Everything the intel report needs, taken from the live state at the moment it is asked. */
    data class ReportInput(
        val watches: List<WatchView> = emptyList(),
        val wifi: Int = 0,
        val ble: Int = 0,
        val cells: Int = 0,
        val mesh: Int = 0,
        val sats: Int = 0,
        val aircraft: Int = 0,
        val gps: Boolean = false,
        val logging: Boolean = false,
        val km: Double = 0.0,
        val unit: String = Units.KM,
    )

    /**
     * Recognisers return "Cortana", "cortana," or a plausible mishearing of it: a British voice
     * often lands on "cortona". All of them wake her; a word has to be at least six letters and
     * one edit away, so "carton" and "curtains" stay out.
     */
    private val WAKE_FORMS = setOf("cortana", "cortanna", "cortona", "kortana", "quartana", "cortada")

    fun normalise(raw: String): String = raw.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex(" +"), " ")
        .trim()

    fun isWakeWord(word: String): Boolean =
        word in WAKE_FORMS || (word.length >= 6 && edits(word, WAKE) <= 1)

    fun hasWake(text: String): Boolean =
        normalise(text).split(' ').any { it.isNotEmpty() && isWakeWord(it) }

    /** The words after the wake word: what is left is the command. */
    fun wakeOnly(text: String): String {
        val words = normalise(text).split(' ').filter { it.isNotEmpty() }
        val at = words.indexOfFirst { isWakeWord(it) }
        if (at < 0) return words.joinToString(" ")
        return words.drop(at + 1).joinToString(" ")
    }

    /**
     * One command, or null when the sentence is not one. Order matters: "start logging" must not
     * reach the log screen, and "unmute" must not be read as "mute".
     */
    fun parse(spoken: String): Command? {
        val n = wakeOnly(spoken)
        if (n.isEmpty()) return null

        fun has(vararg keys: String) = keys.any { n.contains(it) }

        return when {
            has("intel report", "intelligence report", "status report", "situation report", "sitrep", "sit rep") ->
                Command.REPORT
            n == "report" || n.startsWith("report ") || n.endsWith(" report") -> Command.REPORT
            has("help", "what can you do", "commands") -> Command.HELP

            has("unmute", "audio on", "sound on") -> Command.UNMUTE
            has("mute", "audio off", "sound off", "be quiet", "quiet") -> Command.MUTE

            has("start log", "start record", "start drive", "begin log", "begin record", "begin drive") ->
                Command.START
            n == "start" || n == "record" || n == "begin" -> Command.START
            has("stop log", "stop record", "stop drive", "end log", "end drive", "stop recording") ->
                Command.STOP
            n == "stop" || n == "end" -> Command.STOP

            has("open menu", "show menu", "open tabs", "show tabs", "bring up the menu") -> Command.OPEN_MENU
            has("close menu", "hide menu", "close tabs", "hide the menu", "close the bar", "hide the bar") ->
                Command.CLOSE_MENU
            n == "menu" || n == "tabs" -> Command.OPEN_MENU
            n == "close" || n == "hide" -> Command.CLOSE_MENU

            has("fahrenheit") -> Command.FAHRENHEIT
            has("celsius", "centigrade") -> Command.CELSIUS
            has("miles", "imperial") -> Command.MILES
            has("kilometre", "kilometer", "metric") -> Command.KILOMETRES

            has("setup", "setting", "configure") -> Command.SETUP
            has("map", "globe") -> Command.MAP
            has("drive", "scan screen", "dashboard") -> Command.DRIVE
            has("log") -> Command.LOG

            has("sleep", "stand down", "goodbye") -> Command.SLEEP
            else -> null
        }
    }

    fun count(n: Int, one: String, many: String = one + "s"): String =
        if (n == 1) "1 $one" else "$n $many"

    private fun joinWords(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    fun strength(rssi: Int): String = when {
        rssi >= -55 -> "strong"
        rssi >= -70 -> "moderate"
        rssi >= -85 -> "weak"
        else -> "barely there"
    }

    /** One line for a watch hit, spoken in the glitched voice rather than Cortana's own. */
    fun alert(hit: WatchView): String {
        val who = hit.kind.spoken
        val name = if (hit.kind == WatchKind.TRACKER && hit.name.isNotBlank()) ", ${hit.name}," else ""
        return "$who$name. Signal ${strength(hit.rssi)}."
    }

    /**
     * The intel report, in the order it was asked for: what is connected and being watched first,
     * then the radio picture, then whether anything is being recorded.
     */
    fun intelReport(r: ReportInput): String {
        val sb = StringBuilder("Cortana. ")
        val groups = WatchKind.values().mapNotNull { kind ->
            val n = r.watches.count { it.kind == kind }
            if (n == 0) null else count(n, kind.spoken)
        }

        sb.append("Connected devices. ")
        if (groups.isEmpty()) {
            sb.append("Nothing matched. ")
        } else {
            sb.append("I have ${joinWords(groups)}. ")
            val names = r.watches.sortedByDescending { it.rssi }.take(4).map { it.name.ifBlank { it.id } }
            sb.append("Nearest: ${names.joinToString(", ")}. ")
        }

        sb.append("Signal intel. ")
        sb.append(count(r.wifi, "Wi-Fi network"))
        sb.append(", ${count(r.ble, "Bluetooth device")}")
        if (r.cells > 0) sb.append(", ${count(r.cells, "cell tower")}")
        if (r.mesh > 0) sb.append(", ${count(r.mesh, "mesh node")}")
        sb.append(", ${count(r.sats, "satellite")} in range, ${count(r.aircraft, "aircraft")}. ")
        sb.append(if (r.gps) "GPS fix held. " else "No GPS fix. ")
        sb.append(
            if (r.logging) "Logging is on, ${Units.distance(r.km, r.unit, 1)} so far."
            else "Logging is off.",
        )
        return sb.toString()
    }

    fun help(): String =
        "Say Cortana, then: intel report, open map, drive, log, setup, open menu, close menu, " +
            "start logging, stop logging, miles, kilometres, celsius, fahrenheit, mute, unmute, sleep."

    /** Levenshtein distance, capped early: only used on single words. */
    private fun edits(a: String, b: String): Int {
        if (a == b) return 0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }
}
