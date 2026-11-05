package dev.skyward.rf.desktop.esp

import com.fazecast.jSerialComm.SerialPort
import dev.skyward.rf.scan.EspBootloader
import dev.skyward.rf.scan.EspSlip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The firmware image to write, matching the phone's two-image rule: a C3 build will not boot a
 * C5, and the ROM cannot always be asked which part it is, so the image is chosen, not guessed.
 */
enum class EspImage(val key: String, val asset: String, val chip: String, val label: String) {
    C3("c3", "firmware/esp32c3.bin", "ESP32-C3", "C3 Mini"),
    C5("c5", "firmware/esp32c5.bin", "ESP32-C5", "C5 Mini");

    companion object {
        fun of(key: String?): EspImage = entries.firstOrNull { it.key == key } ?: C3
    }
}

/** One COM port that might have a board on it. */
data class SerialBoard(val path: String, val label: String, val detail: String, val native: Boolean) {
    /** "COM7 · USB-SERIAL CH340 (COM7)". */
    val pretty: String get() = if (detail.isBlank()) label else "$path · $detail"
}

/**
 * Flashing and a console, over the desk's serial port.
 *
 * The frames, checksums and block arithmetic are the shared [EspBootloader] the phone uses, so
 * both write byte-identical images; what is new is only the transport. The phone has USB bulk
 * endpoints through UsbManager, the desk has a COM port through jSerialComm, and the bootloader
 * reset is the same DTR/RTS dance either way.
 */
class EspFlash {

    data class Firmware(val version: String, val bytes: Int, val sha256: String)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Every serial port the OS will admit to, for the picker. */
    fun ports(): List<SerialBoard> = runCatching {
        SerialPort.getCommPorts().map { port ->
            val detail = port.portDescription ?: ""
            SerialBoard(
                path = port.systemPortPath,
                label = port.descriptivePortName ?: port.systemPortPath,
                detail = detail,
                native = detail.contains("JTAG", true) || detail.contains("CDC", true),
            )
        }
    }.getOrDefault(emptyList())

    fun firmware(image: EspImage): Firmware? = runCatching {
        val text = resource("firmware/firmware.json")?.decodeToString() ?: return null
        val root = json.parseToJsonElement(text).jsonObject
        val version = root["version"]?.jsonPrimitive?.content ?: "?"
        val entry = root["images"]?.jsonObject?.get(image.key)?.jsonObject ?: return null
        Firmware(
            version = version,
            bytes = entry["size"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            sha256 = entry["sha256"]?.jsonPrimitive?.content ?: "",
        )
    }.getOrNull()

    /** True when this build actually carries the image. */
    fun hasImage(image: EspImage): Boolean = resource(image.asset) != null

    private fun resource(name: String): ByteArray? = runCatching {
        EspFlash::class.java.classLoader.getResourceAsStream(name)?.use { it.readBytes() }
    }.getOrNull()

    /**
     * Writes [image] to [board]. [onStep] gets a sentence and 0..1 progress; the call blocks for
     * the whole erase and write, so it belongs on an IO dispatcher.
     */
    fun flash(board: SerialBoard, image: EspImage, onStep: (String, Float) -> Unit): Result<String> {
        val bytes = resource(image.asset)
            ?: return Result.failure(IllegalStateException("This build has no ${image.label} image bundled"))
        if (bytes.size < 64) {
            return Result.failure(IllegalStateException("The ${image.label} image is only ${bytes.size} bytes"))
        }
        val port = SerialPort.getCommPort(board.path)
        port.setComPortParameters(115200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED)
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 60, 0)
        if (!port.openPort()) {
            return Result.failure(IllegalStateException("${board.path} is busy; unplug the board and try again"))
        }

        try {
            onStep("Entering the bootloader", 0.02f)
            if (!enterBootloader(port, board.native)) {
                return Result.failure(
                    IllegalStateException("No answer from the bootloader. Hold BOOT and tap RESET, then try again"),
                )
            }

            // The whole-chip erase rides in FLASH_BEGIN's first word, exactly as on the phone:
            // the C3 and C5 ROMs do not implement the standalone ERASE_FLASH command.
            val blocks = EspBootloader.blocks(bytes)
            onStep("Erasing the whole 4 MiB flash", 0.04f)
            val begin = command(
                port,
                EspBootloader.OP_FLASH_BEGIN,
                EspBootloader.flashBeginPayload(bytes.size, blocks.size, eraseBytes = WHOLE_FLASH_BYTES),
                FLASH_BEGIN_TIMEOUT_MS,
            ) ?: return Result.failure(IllegalStateException("The board stopped answering while erasing"))
            if (begin.status != 0) {
                return Result.failure(IllegalStateException("The board refused the erase (status ${begin.status})"))
            }

            blocks.forEachIndexed { index, block ->
                var wrote = false
                repeat(2) {
                    if (wrote) return@repeat
                    val reply = command(
                        port,
                        EspBootloader.OP_FLASH_DATA,
                        EspBootloader.flashDataPayload(block.size, index, block),
                        BLOCK_TIMEOUT_MS,
                    )
                    wrote = reply != null && reply.status == 0
                }
                if (!wrote) {
                    return Result.failure(IllegalStateException("The board rejected block $index of ${blocks.size}"))
                }
                val done = (index + 1).toFloat() / blocks.size
                onStep("Writing block ${index + 1} of ${blocks.size}", 0.08f + 0.88f * done)
            }

            onStep("Restarting", 0.98f)
            // FLASH_END is not answered; send it and let the board reboot.
            val end = EspSlip.encode(EspBootloader.command(EspBootloader.OP_FLASH_END, EspBootloader.flashEndPayload(true)))
            port.writeBytes(end, end.size)
            Thread.sleep(900)
            onStep("Done", 1f)
            return Result.success("${image.label} v${firmware(image)?.version ?: "?"} written, ${bytes.size / 1024} KiB")
        } catch (t: Throwable) {
            return Result.failure(t)
        } finally {
            runCatching { port.closePort() }
        }
    }

    /**
     * Native-USB boards want the USB-Serial-JTAG pulse train, a CH340/CP210x board wants the
     * classic IO0/EN sequence, and the polarity is not the same everywhere. Trying both costs
     * about a second when the first guess is right.
     */
    private fun enterBootloader(port: SerialPort, native: Boolean): Boolean {
        val order = if (native) listOf(0, 1, 0, 1) else listOf(1, 0, 1, 0)
        for (attempt in order) {
            if (attempt == 0) usbJtagReset(port) else classicReset(port)
            drain(port, 250)
            if (sync(port)) return true
        }
        return false
    }

    private fun usbJtagReset(port: SerialPort) {
        port.setDTR()
        port.setRTS()
        Thread.sleep(100)
        port.clearDTR()
        port.setRTS()
        Thread.sleep(100)
        port.setDTR()
        port.clearRTS()
        Thread.sleep(20)
        port.setDTR()
        port.setRTS()
        Thread.sleep(150)
    }

    private fun classicReset(port: SerialPort) {
        port.setDTR()
        port.clearRTS()
        Thread.sleep(100)
        port.clearDTR()
        port.setRTS()
        Thread.sleep(80)
        port.setDTR()
        port.setRTS()
        Thread.sleep(150)
    }

    private fun drain(port: SerialPort, forMs: Long) {
        val until = System.currentTimeMillis() + forMs
        val buffer = ByteArray(256)
        while (System.currentTimeMillis() < until) {
            port.readBytes(buffer, buffer.size)
        }
    }

    private fun sync(port: SerialPort): Boolean {
        repeat(7) {
            if (command(port, EspBootloader.OP_SYNC, EspBootloader.syncPayload(), SYNC_TIMEOUT_MS) != null) return true
            Thread.sleep(60)
        }
        return false
    }

    private fun command(port: SerialPort, op: Int, payload: ByteArray, timeoutMs: Long): EspBootloader.Response? {
        val frame = EspSlip.encode(EspBootloader.command(op, payload))
        if (port.writeBytes(frame, frame.size) != frame.size) return null
        val reader = EspSlip.Reader()
        val buffer = ByteArray(512)
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val read = port.readBytes(buffer, buffer.size)
            if (read <= 0) continue
            for (reply in reader.feed(buffer, read)) {
                val response = EspBootloader.response(reply) ?: continue
                if (response.op == op) return response
            }
        }
        return null
    }

    private companion object {
        /** Whatever the board's own flash is, the images are built for a 4 MiB part. */
        const val WHOLE_FLASH_BYTES = 4 * 1024 * 1024
        const val SYNC_TIMEOUT_MS = 400L
        const val FLASH_BEGIN_TIMEOUT_MS = 180_000L
        const val BLOCK_TIMEOUT_MS = 8_000L
    }
}

/**
 * A plain serial console: the board's own log, and a prompt to send it commands.
 *
 * Text out and text back, so anything the board prints over its UART is readable on the desk -
 * which is what makes a board with no GPS or a stuck scan diagnosable without a phone.
 */
class SerialConsole {

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text

    private var port: SerialPort? = null

    @Volatile
    private var running = false

    val isOpen: Boolean get() = port != null

    fun open(path: String, baud: Int = 115200): Boolean {
        close()
        val p = SerialPort.getCommPort(path)
        p.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        p.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED)
        p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 120, 0)
        if (!p.openPort()) return false
        port = p
        running = true
        _text.value = ""
        Thread {
            val buffer = ByteArray(1024)
            while (running) {
                val read = runCatching { p.readBytes(buffer, buffer.size) }.getOrDefault(0)
                if (read > 0) _text.value = (_text.value + String(buffer, 0, read)).takeLast(16_000)
            }
        }.apply { isDaemon = true }.start()
        return true
    }

    fun send(command: String): Boolean {
        val p = port ?: return false
        val bytes = (command + "\n").toByteArray()
        return p.writeBytes(bytes, bytes.size) == bytes.size
    }

    fun clear() {
        _text.value = ""
    }

    fun close() {
        running = false
        runCatching { port?.closePort() }
        port = null
    }
}
