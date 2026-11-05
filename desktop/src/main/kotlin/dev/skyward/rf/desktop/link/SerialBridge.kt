package dev.skyward.rf.desktop.link

import com.fazecast.jSerialComm.SerialPort
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The link over a USB cable, through the port the cable creates.
 *
 * A phone or a board on a cable shows up as a COM port, and the same base64 frame lines the
 * network carrier uses are read and written here at 115200. No driver beyond the one Windows
 * already installs for the device, and no helper process: [com.fazecast.jSerialComm] talks to
 * the port directly.
 *
 * The address of a cable device is `usb:<port>`, and the host treats it exactly like a socket:
 * the framing, the chunking and the reassembly are shared, so a link that moves from Wi-Fi to a
 * cable changes one word in the address and nothing else.
 */
class SerialBridge : LinkBridge {

    private val _events = MutableSharedFlow<LinkEvent>(replay = 8, extraBufferCapacity = 512)
    override val events: SharedFlow<LinkEvent> = _events

    private val sessions = ConcurrentHashMap<String, Session>()

    @Volatile
    private var running = false

    @Volatile
    override var detail: String = "no cable"
        private set

    /** The baud rate a cable device is expected to use; the phone's own link does not, but boards do. */
    var baud: Int = 115200

    /**
     * Ports the user has added. Windows exposes every cable as a port, so nothing is opened until
     * one is chosen - blindly opening a COM port that another program owns fails, and fails loudly.
     */
    private val wanted = ConcurrentHashMap.newKeySet<String>()

    fun ports(): List<SerialBoard> = runCatching {
        SerialPort.getCommPorts().map { port ->
            SerialBoard(
                path = port.systemPortPath,
                label = port.descriptivePortName ?: port.systemPortPath,
                detail = port.portDescription ?: "",
                native = false,
            )
        }
    }.getOrDefault(emptyList())

    /** Opens one more port, or closes it again if it is already open. */
    fun toggle(path: String): Boolean {
        if (sessions.containsKey(addressOf(path))) {
            closePort(path)
            return false
        }
        wanted.add(path)
        return openPort(path)
    }

    override fun start(): Boolean {
        running = true
        detail = if (sessions.isEmpty()) "watching for a cable: ${ports().size} ports" else sessions.keys.joinToString()
        emit(LinkEvent.Ready(detail))
        wanted.forEach { openPort(it) }
        return true
    }

    override fun stop() {
        running = false
        sessions.values.forEach { it.close() }
        sessions.clear()
        wanted.clear()
        detail = "no cable"
    }

    override fun scan(on: Boolean) {
        if (on && running) wanted.forEach { openPort(it) }
    }

    override fun connect(address: String): Boolean {
        val session = sessions[address] ?: return false
        emit(LinkEvent.Connected(address, session.mtu))
        return true
    }

    override fun write(address: String, characteristic: String, data: ByteArray): Boolean =
        sessions[address]?.write(data) ?: false

    // ---------------- plumbing ----------------

    private fun addressOf(path: String): String = "usb:$path"

    private fun openPort(path: String): Boolean {
        val address = addressOf(path)
        if (sessions.containsKey(address)) return true
        val port = SerialPort.getCommPort(path)
        port.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED)
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 120, 0)
        if (!port.openPort()) {
            emit(LinkEvent.Note("$path is busy or gone"))
            return false
        }
        val session = Session(address, port)
        sessions[address] = session
        detail = sessions.keys.joinToString()
        emit(LinkEvent.Advert(
            address = address,
            name = path,
            rssi = 0,
            serviceUuids = listOf(LinkBridge.PEER_SERVICE.toString()),
            manufacturer = emptyMap(),
            serviceData = mapOf(LinkBridge.PEER_SERVICE.toString() to "usb".toByteArray()),
            seenAt = System.currentTimeMillis(),
        ))
        emit(LinkEvent.Connected(address, session.mtu))
        emit(LinkEvent.Note("linked $path on the cable"))
        Thread({ readLoop(session) }, "usb-read").apply { isDaemon = true }.start()
        return true
    }

    private fun closePort(path: String) {
        val address = addressOf(path)
        wanted.remove(path)
        sessions.remove(address)?.close()
        detail = if (sessions.isEmpty()) "no cable" else sessions.keys.joinToString()
        emit(LinkEvent.Disconnected(address))
    }

    private fun readLoop(session: Session) {
        val pending = StringBuilder()
        val buffer = ByteArray(1024)
        try {
            while (running && session.isOpen) {
                val read = session.read(buffer)
                if (read <= 0) continue
                pending.append(String(buffer, 0, read, Charsets.US_ASCII))
                while (true) {
                    val cut = pending.indexOf('\n')
                    if (cut < 0) break
                    val line = pending.substring(0, cut).trim()
                    pending.delete(0, cut + 1)
                    if (line.isEmpty()) continue
                    val bytes = runCatching { Base64.getDecoder().decode(line) }.getOrNull() ?: continue
                    emit(LinkEvent.Notified(session.address, LinkBridge.PEER_SERVICE.toString(), bytes))
                }
                if (pending.length > 64_000) pending.setLength(0)
            }
        } finally {
            sessions.remove(session.address)
            session.close()
            emit(LinkEvent.Disconnected(session.address))
            emit(LinkEvent.Note("unlinked ${session.address}"))
        }
    }

    private fun emit(event: LinkEvent) {
        _events.tryEmit(event)
    }

    /** One open port, with the same write shape the network sessions have. */
    private class Session(val address: String, private val port: SerialPort) {

        val mtu: Int = 185

        val isOpen: Boolean get() = port.isOpen

        private val lock = Any()

        fun read(buffer: ByteArray): Int = runCatching { port.readBytes(buffer, buffer.size) }.getOrDefault(0)

        fun write(bytes: ByteArray): Boolean = synchronized(lock) {
            runCatching {
                val line = (Base64.getEncoder().encodeToString(bytes) + "\n").toByteArray(Charsets.US_ASCII)
                port.writeBytes(line, line.size) == line.size
            }.getOrDefault(false)
        }

        fun close() {
            runCatching { port.closePort() }
        }
    }
}

/** A port that might have a device on it, for the picker. */
data class SerialBoard(val path: String, val label: String, val detail: String, val native: Boolean) {
    val pretty: String get() = if (detail.isBlank()) label else "$path · $detail"
}
