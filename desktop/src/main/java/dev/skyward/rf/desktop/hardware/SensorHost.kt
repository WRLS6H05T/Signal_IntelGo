package dev.skyward.rf.desktop.hardware

import dev.skyward.rf.core.ScanPlan
import dev.skyward.rf.scan.Esp32Console
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** One line of a board's console, either sent to it or printed by it. */
data class ConsoleLine(val stamp: String, val text: String, val outbound: Boolean)

/**
 * A board as the desk last saw it.
 *
 * Every field is a snapshot, so the screen renders whatever the last poll found and never
 * recomputes a parse. Counts are unknown (empty) rather than zero until the board has actually
 * answered, because a confident zero on a board that has not spoken is the one reading that
 * sends an operator chasing a radio that was never asked anything.
 */
data class BoardState(
    val path: String,
    val detail: String = "",
    val connected: Boolean = false,
    val identity: SensorIdentity? = null,
    val plan: String = ScanPlan.W24,
    val gps: Boolean = false,
    val reading: Esp32Console.Reading = Esp32Console.Reading(),
    val sweep: Esp32Console.Sweep = Esp32Console.Sweep(),
    val lastLine: String = "",
    val polls: Int = 0,
    val note: String = "",
) {
    val dualBand: Boolean get() = identity?.dualBand == true
    val hasBle: Boolean get() = identity?.hasBle ?: true

    /** What this board can be told to sweep, from the silicon it named. */
    val options: List<String> get() = ScanPlan.bandOptions(dualBand, hasBle)

    val planLabel: String get() = ScanPlan.label(plan)

    val aps: Int get() = sweep.aps.size
    val bles: Int get() = sweep.devices.size

    /** The number that proves the C5's second pass ran: no single-band radio prints a 5 GHz AP. */
    val ghz5: Int get() = sweep.aps.count { Bands.of(it.channel) == Bands.W5 }

    val chip: String get() = identity?.chip ?: ""
}

/**
 * The desk's host: it owns the cables.
 *
 * A phone polls a board between other work; the desk's whole job is the boards, so each one gets
 * its own coroutine, its own open port and its own poll cycle. What goes down the wire is not
 * chosen here - the verbs come from the same [Esp32Console.commandsFor] the handset uses, from
 * the same plan strings - so a sweep asked for on the desk is the sweep asked for on the phone.
 */
class SensorHost(
    private val scope: CoroutineScope,
    private val onSweep: (BoardState) -> Unit = {},
) {

    private val _ports = MutableStateFlow<List<PortInfo>>(emptyList())
    val ports: StateFlow<List<PortInfo>> = _ports

    private val _boards = MutableStateFlow<Map<String, BoardState>>(emptyMap())
    val boards: StateFlow<Map<String, BoardState>> = _boards

    private val _console = MutableStateFlow<Map<String, List<ConsoleLine>>>(emptyMap())
    val console: StateFlow<Map<String, List<ConsoleLine>>> = _console

    private val links = HashMap<String, SerialLink>()
    private val jobs = HashMap<String, Job>()
    private val gates = HashMap<String, Mutex>()

    /** Ports on this machine that were not there before appear as rows, unconnected. */
    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val found = runCatching { Ports.list() }.getOrDefault(emptyList())
            _ports.value = found
            _boards.update { current ->
                val next = current.toMutableMap()
                for (port in found) {
                    val known = next[port.path]
                    next[port.path] = if (known == null) {
                        BoardState(path = port.path, detail = port.detail)
                    } else {
                        known.copy(detail = port.detail)
                    }
                }
                // A port that vanished while disconnected is not a board any more.
                val paths = found.map { it.path }.toSet()
                for ((path, board) in current) {
                    if (path !in paths && !board.connected) next.remove(path)
                }
                next
            }
        }
    }

    fun connect(path: String) {
        if (links.containsKey(path)) return
        val link = SerialLink(path)
        links[path] = link
        gates[path] = Mutex()
        jobs[path] = scope.launch(Dispatchers.IO) {
            val opened = link.open()
            updateBoard(path) { it.copy(connected = opened, note = if (opened) "open" else "port did not open") }
            if (!opened) {
                links.remove(path)
                gates.remove(path)
                return@launch
            }
            // A bare newline is answered with the banner: the firmware reprints it for exactly
            // this, and it is the only greeting that works on a board that booted long ago.
            link.write("\r\n")
            loop(path, link)
        }
    }

    fun disconnect(path: String) {
        jobs.remove(path)?.cancel()
        links.remove(path)?.close()
        gates.remove(path)
        updateBoard(path) { it.copy(connected = false, note = "disconnected") }
    }

    fun setPlan(path: String, code: String) {
        updateBoard(path) { it.copy(plan = code) }
    }

    fun setGps(path: String, on: Boolean) {
        updateBoard(path) { it.copy(gps = on) }
    }

    /** A line typed into the console: sent as-is, and read back for a couple of seconds. */
    fun send(path: String, verb: String) {
        val link = links[path] ?: return
        val gate = gates[path] ?: return
        val line = verb.trim()
        if (line.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            gate.withLock {
                if (link.write(line + "\r\n")) log(path, line, outbound = true)
                val text = drain(link, MANUAL_READ_MS)
                forText(text) { log(path, it, outbound = false) }
                applyReply(path, text)
            }
        }
    }

    private suspend fun loop(path: String, link: SerialLink) {
        var first = true
        while (currentCoroutineContext().isActive) {
            val board = _boards.value[path] ?: break
            val verbs = verbsFor(board, first)
            first = false
            val text = withContext(Dispatchers.IO) {
                gates[path]?.withLock { exchange(path, link, verbs) }
            } ?: ""
            if (text.isNotBlank()) applyReply(path, text)
            else updateBoard(path) { it.copy(polls = it.polls + 1, note = "no reply") }
            delay(POLL_GAP_MS)
        }
    }

    private fun verbsFor(board: BoardState, first: Boolean): List<String> {
        if (first) return listOf("status\r\n", "help\r\n")
        val identity = board.identity ?: return listOf("status\r\n", "help\r\n")
        val plan = if (board.gps) ScanPlan.withGps(board.plan, true) else board.plan
        return Esp32Console.commandsFor(identity.family, null, plan)
    }

    private fun exchange(path: String, link: SerialLink, verbs: List<String>): String {
        for (verb in verbs) {
            if (link.write(verb)) log(path, verb.trim(), outbound = true)
            Thread.sleep(120)
        }
        val text = drain(link, SWEEP_READ_MS)
        forText(text) { log(path, it, outbound = false) }
        return text
    }

    private fun drain(link: SerialLink, millis: Long): String {
        val out = ByteArrayOutputStream()
        link.readFor(millis) { out.write(it) }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun forText(text: String, action: (String) -> Unit) {
        text.lineSequence().forEach { raw ->
            val line = raw.replace("\u0000", "").trim()
            if (line.isNotEmpty()) action(line.take(200))
        }
    }

    /**
     * Folds one poll's transcript into the board's row.
     *
     * The identity is only searched for while it is unknown: once a board has said what it is,
     * a help text that happens to mention another chip must not rename it.
     */
    private fun applyReply(path: String, text: String) {
        val base = _boards.value[path] ?: return
        val identity = base.identity ?: SensorIdentity.from(text)
        val sweep = Esp32Console.parseScan(text)
        val reading = Esp32Console.parse(text)
        val last = text.lineSequence().lastOrNull { it.isNotBlank() }?.trim()?.take(160) ?: base.lastLine
        val next = base.copy(
            identity = identity,
            // The band menu follows silicon. A board that has just named itself gets the sweep a
            // board nobody assigned should be doing, instead of being left on a stale default.
            plan = if (base.identity == null && identity != null) ScanPlan.defaultBand(identity.dualBand) else base.plan,
            reading = base.reading.merge(reading),
            // An empty listing means the board had nothing to say this cycle, not that the
            // networks left: only a sweep that named something replaces the table.
            sweep = if (sweep.isEmpty) base.sweep else sweep,
            lastLine = last,
            polls = base.polls + 1,
            note = if (identity == null) "unidentified" else "ok",
        )
        _boards.update { it + (path to next) }
        if (!sweep.isEmpty) onSweep(next)
    }

    private fun updateBoard(path: String, change: (BoardState) -> BoardState) {
        _boards.update { current ->
            val board = current[path] ?: return@update current
            current + (path to change(board))
        }
    }

    private fun log(path: String, text: String, outbound: Boolean) {
        val line = ConsoleLine(LocalTime.now().format(STAMP), text, outbound)
        _console.update { current ->
            val lines = current[path].orEmpty() + line
            current + (path to lines.takeLast(MAX_LINES))
        }
    }

    companion object {
        /** Long enough for a dual-band C5 pass, which is two scans with a deadline each. */
        const val SWEEP_READ_MS: Long = 6_000

        const val MANUAL_READ_MS: Long = 2_000

        /** A poll is a command, not a loop: the desk reads what the board says and asks again. */
        const val POLL_GAP_MS: Long = 1_200

        const val MAX_LINES: Int = 400

        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
