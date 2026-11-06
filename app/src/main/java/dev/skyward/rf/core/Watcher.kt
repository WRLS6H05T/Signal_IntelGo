package dev.skyward.rf.core

import java.util.Locale

/**
 * What the Watcher hears and what it says back.
 *
 * The parsing is deliberately small and explicit: a grammar of a dozen intents that a driver can
 * say without looking at the screen, not a language model. Every phrase is matched against the
 * words after the wake word, so "Watcher, open setup" and "open setup" both work once it is awake,
 * and nothing here touches Android - the microphone and the speaker live in `voice/WatcherVoice`,
 * which makes every rule below testable without a phone.
 */
object Watcher {

    const val WAKE = "watcher"

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
        val drones: Int = 0,
        val gps: Boolean = false,
        val logging: Boolean = false,
        val km: Double = 0.0,
        val unit: String = Units.KM,
        /** What the log has caught, which is what the report is about. */
        val logged: Logged = Logged(),
        /** Watch hits heard since the last timed update; the on-demand report ignores this. */
        val sightings: List<WatchView> = emptyList(),
    )

    /**
     * The Log tab's table, as counts the report can read out.
     *
     * Wi-Fi and Bluetooth are entities here as much as a satellite is: every network heard with
     * a fix is one row in the table and one dot on the map, so the report must not leave them
     * out of the total it announces.
     */
    data class Logged(
        val sats: Int = 0,
        val aircraft: Int = 0,
        val drones: Int = 0,
        val mesh: Int = 0,
        val cells: Int = 0,
        val esp32: Int = 0,
        val watch: Int = 0,
        val wifi: Int = 0,
        val ble: Int = 0,
    ) {
        val total: Int get() = sats + aircraft + drones + mesh + cells + esp32 + watch + wifi + ble
    }

    /**
     * Recognisers return "Watcher", "watchers," or a plausible mishearing: "wotcher", "watcha",
     * and - very often for a flat British "watcher" - the two words "watch her". All of them wake
     * it; a word has to be at least six letters and one edit away, so "watchman" and "watching"
     * stay out.
     */
    private val WAKE_FORMS = setOf("watcher", "watchers", "wotcher", "watcha", "wacher")

    fun normalise(raw: String): String = raw.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex(" +"), " ")
        .trim()

    fun isWakeWord(word: String): Boolean =
        word in WAKE_FORMS || (word.length >= 6 && edits(word, WAKE) <= 1)

    /** "watch her" is how the recogniser hears a flat "watcher"; both count. */
    private fun isWatchHer(a: String, b: String): Boolean =
        a == "watch" && (b == "her" || b == "er")

    fun hasWake(text: String): Boolean {
        val words = normalise(text).split(' ').filter { it.isNotEmpty() }
        if (words.any { isWakeWord(it) }) return true
        return words.zipWithNext().any { (a, b) -> isWatchHer(a, b) }
    }

    /** The words after the wake word: what is left is the command. */
    fun wakeOnly(text: String): String {
        val words = normalise(text).split(' ').filter { it.isNotEmpty() }
        val at = words.indexOfFirst { isWakeWord(it) }
        if (at >= 0) return words.drop(at + 1).joinToString(" ")
        val pair = words.zipWithNext().indexOfFirst { (a, b) -> isWatchHer(a, b) }
        if (pair >= 0) return words.drop(pair + 2).joinToString(" ")
        return words.joinToString(" ")
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

    /** One line for a watch hit, spoken in the glitched delivery rather than the plain one. */
    fun alert(hit: WatchView): String {
        val who = hit.kind.spoken
        val name = if (hit.kind == WatchKind.TRACKER && hit.name.isNotBlank()) ", ${hit.name}," else ""
        return "$who$name. Signal ${strength(hit.rssi)}."
    }

    /**
     * The intel report, in the order it was asked for: what is connected and being watched first,
     * then the radio picture, then what the log has caught.
     *
     * Whether recording is armed is not part of it. "Logging is off" three words before the
     * sign-off was heard as a second goodbye, and what has been collected is the news anyway.
     */
    fun intelReport(r: ReportInput): String {
        // No name in the text: the voice pacing is the delivery, and the Watcher is the only one.
        val sb = StringBuilder("Intel report. ")
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
        sb.append("Logged entities. ")
        if (r.logged.total == 0) {
            sb.append("Nothing logged yet.")
        } else {
            val caught = listOfNotNull(
                r.logged.sats.takeIf { it > 0 }?.let { count(it, "satellite") },
                r.logged.aircraft.takeIf { it > 0 }?.let { count(it, "aircraft", "aircraft") },
                r.logged.drones.takeIf { it > 0 }?.let { count(it, "drone") },
                r.logged.mesh.takeIf { it > 0 }?.let { count(it, "mesh node") },
                r.logged.cells.takeIf { it > 0 }?.let { count(it, "cell tower") },
                r.logged.esp32.takeIf { it > 0 }?.let { count(it, "ESP32 board") },
                r.logged.watch.takeIf { it > 0 }?.let { count(it, "watch match", "watch matches") },
                r.logged.wifi.takeIf { it > 0 }?.let { count(it, "Wi-Fi network") },
                r.logged.ble.takeIf { it > 0 }?.let { count(it, "Bluetooth device") },
            )
            sb.append(count(r.logged.total, "entity", "entities")).append(". ")
            sb.append("I have ${joinWords(caught)}.")
        }
        return sb.toString()
    }

    /**
     * The timed intel update: the tab read down, then what has been sighted since the last one.
     *
     * The call - "Watcher to operator, intel update" - is radio framing rather than text, so this
     * begins at the tab itself. Every row the INTEL box shows is spoken, because the point of the
     * update is to hear what is on screen without looking at it, and the sightings are the part
     * that has changed since the last time it spoke.
     */
    fun intelUpdate(
        r: ReportInput,
        windowMinutes: Int,
        sightings: List<WatchView> = r.sightings,
    ): String {
        val sb = StringBuilder("Intel tab. ")
        sb.append(count(r.watches.size, "watch match", "watch matches")).append(". ")
        sb.append("Satellites ${r.sats}, aircraft ${r.aircraft}, drones ${r.drones}, ")
        sb.append("mesh nodes ${r.mesh}, cell towers ${r.cells}. ")
        sb.append("Wi-Fi networks ${r.wifi}, Bluetooth devices ${r.ble}. ")
        sb.append(if (r.gps) "GPS fix held. " else "No GPS fix. ")
        sb.append(
            if (r.logging) "Logging on, ${Units.distance(r.km, r.unit, 1)} so far. "
            else "Logging off. ",
        )

        val window = count(windowMinutes, "minute")
        sb.append("Alert sightings. ")
        if (sightings.isEmpty()) {
            sb.append("Nothing new in the last $window.")
        } else {
            sb.append(count(sightings.size, "sighting")).append(" in the last $window. ")
            val lines = sightings.take(3).map { "${it.kind.spoken}, ${strength(it.rssi)}" }
            sb.append(lines.joinToString(", ")).append('.')
        }
        return sb.toString()
    }

    fun help(): String =
        "Say Watcher, then: intel report, open map, drive, log, setup, open menu, close menu, " +
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
