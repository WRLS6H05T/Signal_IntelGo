package dev.skyward.rf.scan

import android.os.SystemClock
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
 *
 * Two things about real hardware are handled here because they read as a dead port otherwise: a
 * boot log that ends without a newline (flushed after a pause), and a chip that resets itself off
 * the bus and returns under a new device id (reopened, which is what makes the RESET button
 * useful - the banner is printed by the chip that comes back).
 */
class EspTerminal(private val usb: UsbBoards) {

    data class State(
        /** What the port is labelled as in the roster, once connected. */
        val port: String = "",
        val open: Boolean = false,
        val lines: List<String> = emptyList(),
        val error: String? = null,
        /** Bytes received since connecting. Zero means nothing is arriving at all. */
        val bytes: Int = 0,
        /** Interfaces on the device that could be a console, and which one is being read. */
        val ports: Int = 1,
        val portIndex: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var session: UsbBoards.SerialSession? = null

    @Volatile
    private var running = false

    private var reader: Thread? = null

    private var board: UsbBoards.Board? = null

    private var baud = 115_200

    private var portIndex = 0

    private var received = 0

    /** Takes the board's port at [baud]. False when another session already holds it. */
    fun open(board: UsbBoards.Board, baud: Int) {
        close()
        this.baud = baud
        portIndex = 0
        if (!connect(board, 0)) {
            _state.value = State(open = false, error = "The port is busy; unplug the board and try again")
        }
    }

    /** Reads the next candidate interface on the device, for a device that has more than one. */
    fun nextPort() {
        val current = board ?: return
        val count = usb.portCount(current.deviceId)
        if (count < 2) return
        val next = (portIndex + 1) % count
        close()
        if (!connect(current, next)) {
            _state.value = State(open = false, error = "The port is busy; unplug the board and try again")
        }
    }

    private fun connect(target: UsbBoards.Board, index: Int): Boolean {
        val count = usb.portCount(target.deviceId).coerceAtLeast(1)
        val opened = usb.openSession(target.deviceId, baud, index.coerceIn(0, count - 1)) ?: return false
        session = opened
        board = target
        portIndex = index.coerceIn(0, count - 1)
        running = true
        received = 0
        _state.value = State(
            port = target.label,
            open = true,
            lines = listOf("--- ${target.label} at $baud, interface ${portIndex + 1} of $count ---"),
            ports = count,
            portIndex = portIndex,
        )
        // A console only speaks when spoken to. The firmware prints its banner at boot, so a
        // board that has been running for an hour shows an empty panel until it gets a line
        // ending; this hands it one, and a console that prompts reprints its prompt.
        opened.write("\r\n".toByteArray())
        reader = Thread({ readLoop(opened, target) }, "esp-terminal").apply {
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
     * was already running when the terminal opened. On a chip with its own USB peripheral the
     * reset also takes it off the bus, and [readLoop] picks the new device back up.
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

    private fun readLoop(first: UsbBoards.SerialSession, firstBoard: UsbBoards.Board) {
        var s = first
        var target = firstBoard
        val text = StringBuilder()
        var lastByte = SystemClock.elapsedRealtime()
        var empty = 0
        while (running) {
            if (!s.isOpen) break
            val bytes = s.read(256, 200)
            if (bytes == null) {
                empty++
                // A chip on its own USB peripheral leaves the bus when it resets and comes back
                // as a new device id: the port this loop holds is gone and nothing will ever
                // arrive on it again, however long it waits.
                if (empty >= 2 && !usb.isAttached(target.deviceId)) {
                    val next = reopen(target)
                    if (next == null) {
                        append("--- the board left the cable ---")
                        // Nothing is attached under that id and no replacement appeared, so the
                        // panel has to stop calling itself open: it read as a live connection to
                        // a board that was already in a drawer.
                        close()
                        _state.value = _state.value.copy(error = "board left the cable")
                        return
                    }
                    append("--- the board reset and came back; reading it again ---")
                    s = next.first
                    target = next.second
                    empty = 0
                    continue
                }
                // Text without a line ending still has to show up, or a boot banner that ends in
                // a prompt reads as a dead port.
                if (text.isNotEmpty() && SystemClock.elapsedRealtime() - lastByte > 250) {
                    append(text.toString().trimEnd())
                    text.setLength(0)
                }
                continue
            }
            empty = 0
            lastByte = SystemClock.elapsedRealtime()
            received += bytes.size
            _state.value = _state.value.copy(bytes = received)
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

    /** Waits for the device that replaced this one and opens it on the same interface. */
    private fun reopen(board: UsbBoards.Board): Pair<UsbBoards.SerialSession, UsbBoards.Board>? {
        session?.close()
        session = null
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (running && SystemClock.elapsedRealtime() < deadline) {
            val next = usb.replacementFor(board.vendorId, board.productId, board.deviceId)
            if (next != null) {
                val count = usb.portCount(next.deviceId).coerceAtLeast(1)
                val opened = usb.openSession(next.deviceId, baud, portIndex.coerceIn(0, count - 1))
                if (opened != null) {
                    session = opened
                    this.board = next
                    _state.value = _state.value.copy(port = next.label, ports = count, open = true)
                    return opened to next
                }
            }
            Thread.sleep(150)
        }
        return null
    }

    private fun append(line: String) {
        // Bounded on purpose: a board that streams NMEA for an hour must not grow the list the
        // UI is diffing on every frame.
        val next = (_state.value.lines + line).takeLast(400)
        _state.value = _state.value.copy(lines = next)
    }
}
