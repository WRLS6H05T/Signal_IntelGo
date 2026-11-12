package dev.skyward.rf.desktop.hardware

import com.fazecast.jSerialComm.SerialPort

/** A serial port the operating system is offering, whether or not a board is behind it. */
data class PortInfo(
    val path: String,
    val description: String,
    val vendorId: Int,
    val productId: Int,
) {
    val isUsb: Boolean get() = vendorId != 0 || productId != 0

    /** What to print under the port name: a USB id is proof, a description is a claim. */
    val detail: String
        get() = if (isUsb) String.format("USB %04X:%04X", vendorId, productId) else description.ifBlank { "serial" }
}

object Ports {

    /** Every port on the machine, by name. Cheap enough to call on a timer. */
    fun list(): List<PortInfo> = SerialPort.getCommPorts()
        .map { p ->
            PortInfo(
                path = p.systemPortName,
                description = p.portDescription ?: "",
                vendorId = runCatching { p.vendorID }.getOrDefault(0),
                productId = runCatching { p.productID }.getOrDefault(0),
            )
        }
        .sortedBy { it.path }
}

/**
 * One open board console.
 *
 * The desk keeps the port open for the whole session rather than reopening it per poll: a board
 * on its own USB peripheral re-enumerates when the port is closed, and a port that disappears
 * and comes back loses the banner it was about to print.
 */
class SerialLink(private val path: String) : AutoCloseable {

    private var port: SerialPort? = null

    fun open(): Boolean {
        close()
        val p = SerialPort.getCommPort(path)
        p.setComPortParameters(115200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        // Semi-blocking with a short read timeout: a poll wants everything the board said for the
        // next few seconds and then its thread back.
        p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 200, 0)
        if (!p.openPort()) return false
        // A CDC console transmits only while the host holds DTR. RTS is left alone because some
        // boards read the modem control lines as a reset request and restart mid-poll.
        runCatching { p.setDTR() }
        runCatching { p.clearRTS() }
        port = p
        return true
    }

    fun write(text: String): Boolean {
        val p = port ?: return false
        val bytes = text.toByteArray(Charsets.US_ASCII)
        return p.writeBytes(bytes, bytes.size) == bytes.size
    }

    /** Reads until [millis] have passed, handing every chunk to [onBytes]. */
    fun readFor(millis: Long, onBytes: (ByteArray) -> Unit) {
        val p = port ?: return
        val buf = ByteArray(1024)
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) {
            val n = p.readBytes(buf, buf.size)
            if (n > 0) onBytes(buf.copyOf(n))
        }
    }

    override fun close() {
        port?.let { runCatching { it.closePort() } }
        port = null
    }
}
