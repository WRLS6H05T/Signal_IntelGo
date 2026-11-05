package dev.skyward.rf.scan

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The board's console, in the app.
 *
 * A wired ESP32 has one channel, and the app already speaks on it for identification and
 * sweeps. This is the same port with a human at the keyboard instead: a raw session that takes
 * the board away from the poller while it is open, so what is typed arrives intact and nothing
 * the poller sends lands in the middle of the firmware's reply.
 *
 * Nothing here interprets the lines: the firmware's own `help`, `status`, `gpspin` and `gpsbaud`
 * are the interface, so the terminal stays useful for a board running anything at all.
 */
class EspTerminal(private val usb: UsbBoards) {

    data class State(
        /** What the port is labelled as in the roster, once connected. */
        val port: String = "",
        val open: Boolean = false,
        val lines: List<String> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var session: UsbBoards.SerialSession? = null

    @Volatile
    private var running = false

    private var reader: Thread? = null

    /** Takes the board's port at [baud]. False when another session already holds it. */
    fun open(board: UsbBoards.Board, baud: Int): Boolean {
        close()
        val opened = usb.openSession(board.deviceId, baud)
        if (opened == null) {
            _state.value = _state.value.copy(open = false, error = "The port is busy; unplug the board and try again")
            return false
        }
        session = opened
        running = true
        _state.value = State(port = board.label, open = true, lines = listOf("--- ${board.label} at $baud ---"))
        // A console only speaks when spoken to. The firmware prints its banner at boot, so a
        // board that has been running for an hour shows an empty panel until it gets a line
        // ending; this hands it one, and a console that prompts reprints its prompt.
        opened.write("\r\n".toByteArray())
        reader = Thread({ readLoop(opened) }, "esp-terminal").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /** Sends one line; a bare command gets the CRLF a firmware console expects. */
    fun send(text: String) {
        if (text.isBlank()) return
        val line = if (text.endsWith("\n") || text.endsWith("\r")) text else "$text\r\n"
        session?.write(line.toByteArray())
        append("> ${text.trim()}")
    }

    /**
     * Restarts the board so the console shows its boot log.
     *
     * The banner is printed once per reset, so this is the only way to see one on a board that
     * was already running when the terminal opened.
     */
    fun reset(): Boolean {
        val s = session ?: return false
        if (!s.isOpen) return false
        append("--- reset ---")
        s.pulseReset()
        return true
    }

    fun close() {
        running = false
        session?.close()
        session = null
        reader = null
        _state.value = _state.value.copy(open = false, port = "")
    }

    private fun readLoop(s: UsbBoards.SerialSession) {
        val text = StringBuilder()
        while (running && s.isOpen) {
            val bytes = s.read(256, 200) ?: continue
            for (b in bytes) {
                val c = b.toInt() and 0xFF
                when {
                    c == 0x0A || c == 0x0D -> if (text.isNotEmpty()) {
                        append(text.toString())
                        text.setLength(0)
                    }

                    c in 0x20..0x7E || c == 0x09 -> text.append(c.toChar())
                }
            }
        }
    }

    private fun append(line: String) {
        // Bounded on purpose: a board that streams NMEA for an hour must not grow the list the
        // UI is diffing on every frame.
        val next = (_state.value.lines + line).takeLast(400)
        _state.value = _state.value.copy(lines = next)
    }
}
