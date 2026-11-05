package dev.skyward.rf.scan

import android.content.Context
import android.os.SystemClock
import dev.skyward.rf.core.CrashLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The firmware image a board gets.
 *
 * Two images, because a C3 build will not boot a C5 and the ROM cannot be asked which one it is
 * (esptool 5.6 marks both parts as not publishing a chip magic). So the image is chosen, not
 * guessed, and the dialog shows exactly which one is about to be written.
 */
enum class EspImage(val key: String, val asset: String, val chip: String, val label: String) {
    C3("c3", "firmware/esp32c3.bin", "ESP32-C3", "C3 Mini"),
    C5("c5", "firmware/esp32c5.bin", "ESP32-C5", "C5 Mini");

    companion object {
        fun of(key: String?): EspImage = entries.firstOrNull { it.key == key } ?: C3
    }
}

/** What the manifest beside the images says about one of them. */
data class EspFirmware(val image: EspImage, val version: String, val bytes: Int, val sha256: String)

/** Everything the confirm dialog shows before a chip is erased. */
data class FlashDetails(
    val port: String,
    val usbId: String,
    val transport: String,
    val chip: String,
    val image: EspImage,
    val firmware: EspFirmware?,
    val missing: Boolean,
)

/**
 * Writes the bundled sensor firmware over the cable, from the phone, with no PC involved.
 *
 * This speaks the ROM bootloader's own protocol (see [EspBootloader]) rather than shelling out
 * to esptool, which cannot exist on a phone: reset into download mode, SYNC, FLASH_BEGIN with the
 * image's own span as its erase size, a FLASH_DATA per 1 KiB block, FLASH_END. No stub is uploaded, so a board with nothing but its
 * ROM - a fresh C3 or C5 mini - is enough.
 *
 * The path is unverified without hardware. What is checked here is the part that can be: the
 * frames, checksums, block arithmetic and image manifest all have JVM tests. What happens on the
 * wire is recorded in [wire], so a board that misbehaves leaves something to read rather than
 * another report of a hang.
 */
class EspFlasher(private val context: Context, private val usb: UsbBoards) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * What went over the wire, oldest first.
     *
     * Three attempts at this ended in "it stops at erasing flash", which is the one sentence the
     * wire can never be asked about after the fact. This keeps the exchange - every frame sent,
     * every byte read, every line state and every timeout - so the dialog can show it and a
     * failed flash can put it in the crash report.
     */
    private val _wire = MutableStateFlow<List<String>>(emptyList())
    val wire: StateFlow<List<String>> = _wire

    private fun note(line: String) {
        _wire.value = (_wire.value + line).takeLast(WIRE_LINES)
    }

    private fun wireTail(): String = _wire.value.takeLast(40).joinToString("\n")

    /** Reads `assets/firmware/firmware.json`; null when the build has no images bundled. */
    fun firmware(image: EspImage): EspFirmware? = runCatching {
        val manifest = context.assets.open(MANIFEST).use { it.readBytes().decodeToString() }
        val root = json.parseToJsonElement(manifest).jsonObject
        val version = root["version"]?.jsonPrimitive?.content ?: "?"
        val entry = root["images"]?.jsonObject?.get(image.key)?.jsonObject ?: return null
        val size = entry["size"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        val sha = entry["sha256"]?.jsonPrimitive?.content ?: ""
        EspFirmware(image, version, size, sha)
    }.getOrNull()

    /** True when the build actually contains an image to write. */
    fun hasImage(image: EspImage): Boolean = runCatching {
        context.assets.open(image.asset).use { it.read() }
        true
    }.getOrDefault(false)

    fun details(board: UsbBoards.Board, image: EspImage): FlashDetails = FlashDetails(
        port = board.label,
        usbId = String.format("%04X:%04X", board.vendorId, board.productId),
        transport = board.transport.label,
        chip = image.chip,
        image = image,
        firmware = firmware(image),
        missing = !hasImage(image),
    )

    /**
     * Flashes [image] to [board]. [onStep] gets a human sentence and 0..1 progress; the call is
     * blocking, so it belongs on an IO dispatcher.
     */
    fun flash(board: UsbBoards.Board, image: EspImage, onStep: (String, Float) -> Unit): Result<String> {
        val bytes = runCatching { context.assets.open(image.asset).use { it.readBytes() } }.getOrNull()
            ?: return Result.failure(IllegalStateException("This build has no ${image.label} image bundled"))
        if (bytes.size < 64) {
            return Result.failure(IllegalStateException("The ${image.label} image is only ${bytes.size} bytes"))
        }

        _wire.value = emptyList()
        note("${image.label} ${bytes.size} B on ${board.label} (${board.transport.label})")
        val session = usb.openSession(board.deviceId, BAUD)
            ?: return Result.failure(IllegalStateException("The port is busy; unplug the board and try again"))

        try {
            onStep("Entering the bootloader", 0.02f)
            if (!enterBootloader(session, board)) {
                CrashLog.recordQuiet(context, "esp-flash-sync", IllegalStateException(wireTail()))
                return Result.failure(
                    IllegalStateException(
                        "The board never answered a SYNC, after a reset on every line shape. Hold BOOT, " +
                            "tap RESET, release BOOT, then flash again.",
                    ),
                )
            }

            val blocks = EspBootloader.blocks(bytes)
            val eraseBytes = EspBootloader.eraseSize(bytes.size)
            val label = "Erasing ${eraseBytes / 1024} KiB"
            onStep(label, 0.04f)

            // esptool's own shape: one FLASH_BEGIN that asks for the image's span and answers only
            // once the erase is done. The budget is 40 s per megabyte with a floor, and every read
            // inside it waits on the whole remaining time rather than timing out and cancelling -
            // a cancelled USB transfer is how an answer that arrived gets thrown away.
            val eraseTimeoutMs = (bytes.size / 1_000_000.0 * 40_000.0).toLong()
                .coerceAtLeast(ERASE_MIN_TIMEOUT_MS)
            val beginExtended = EspBootloader.flashBeginPayload(
                imageSize = bytes.size,
                blocks = blocks.size,
                eraseBytes = eraseBytes,
                extended = true,
            )
            val beginLegacy = EspBootloader.flashBeginPayload(
                imageSize = bytes.size,
                blocks = blocks.size,
                eraseBytes = eraseBytes,
                extended = false,
            )
            val begin = ticking(label, onStep) {
                command(session, EspBootloader.OP_FLASH_BEGIN, beginExtended, eraseTimeoutMs)
                    ?: run {
                        // A fresh SYNC, not a resent command: a chip that is still erasing ignores
                        // both, but a chip that lost its framing is put back in step by the SYNC.
                        note("erase unanswered after ${eraseTimeoutMs / 1000}s; re-syncing")
                        sync(session, "before the second erase attempt")
                        command(session, EspBootloader.OP_FLASH_BEGIN, beginExtended, eraseTimeoutMs)
                    }
                    ?: run {
                        sync(session, "before the four-word erase attempt")
                        command(session, EspBootloader.OP_FLASH_BEGIN, beginLegacy, eraseTimeoutMs)
                    }
            }

            if (begin == null || begin.status != 0) {
                note("whole-span erase failed (status ${begin?.status}); erasing in chunks")
                val chunked = ticking(label, onStep) { eraseInChunks(session, eraseBytes, onStep) }
                if (!chunked) {
                    CrashLog.recordQuiet(context, "esp-flash-erase", IllegalStateException(wireTail()))
                    return Result.failure(
                        IllegalStateException(
                            "The board never answered the erase, in one span or in chunks. Unplug it, hold " +
                                "BOOT, plug it back in, then flash again - the wire log in this dialog says " +
                                "what came back.",
                        ),
                    )
                }
                // The flash is erased, so the write's own FLASH_BEGIN must not ask for another:
                // this one is answered at once and cannot look like a hang.
                val empty = EspBootloader.flashBeginPayload(
                    imageSize = bytes.size,
                    blocks = blocks.size,
                    eraseBytes = 0,
                )
                val opened = command(session, EspBootloader.OP_FLASH_BEGIN, empty, BEGIN_TIMEOUT_MS)
                    ?: run {
                        sync(session, "before the write")
                        command(session, EspBootloader.OP_FLASH_BEGIN, empty, BEGIN_TIMEOUT_MS)
                    }
                if (opened == null || opened.status != 0) {
                    CrashLog.recordQuiet(context, "esp-flash-begin", IllegalStateException(wireTail()))
                    return Result.failure(IllegalStateException("The board stopped answering after the erase."))
                }
            }

            blocks.forEachIndexed { index, block ->
                val payload = EspBootloader.flashDataPayload(block.size, index, block)
                var wrote = false
                // One retry: a USB hiccup mid-image is worth repeating the block for rather
                // than failing an erase that has already happened.
                repeat(2) {
                    if (wrote) return@repeat
                    val reply = command(session, EspBootloader.OP_FLASH_DATA, payload, BLOCK_TIMEOUT_MS)
                    wrote = reply != null && reply.status == 0
                }
                if (!wrote) {
                    CrashLog.recordQuiet(context, "esp-flash-block", IllegalStateException(wireTail()))
                    return Result.failure(IllegalStateException("The board rejected block $index of ${blocks.size}"))
                }
                val done = (index + 1).toFloat() / blocks.size
                onStep("Writing block ${index + 1} of ${blocks.size}", 0.08f + 0.88f * done)
            }

            onStep("Restarting", 0.98f)
            // The ROM does not answer FLASH_END, so this is sent and the reboot is allowed to
            // happen rather than waited on.
            session.write(
                EspSlip.encode(
                    EspBootloader.command(EspBootloader.OP_FLASH_END, EspBootloader.flashEndPayload(true)),
                ),
            )
            note("FLASH_END sent; the board reboots into the new image")
            Thread.sleep(900)
            onStep("Done", 1f)
            return Result.success("${image.label} v${firmware(image)?.version ?: "?"} written, ${bytes.size / 1024} KiB")
        } catch (t: Throwable) {
            CrashLog.recordQuiet(context, "esp-flash", t)
            return Result.failure(t)
        } finally {
            session.close()
        }
    }

    /**
     * Runs [block] while a step line counts the seconds.
     *
     * A ROM erase is a long silence on the wire by design: the board erases everything the
     * command asked for before it answers. Without this, a working erase and a dead cable look
     * identical on the phone, which is exactly how the flasher was being reported.
     */
    private fun <T> ticking(label: String, onStep: (String, Float) -> Unit, block: () -> T): T {
        val started = SystemClock.elapsedRealtime()
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val ticker = Thread {
            while (running.get()) {
                if (runCatching { Thread.sleep(1_000) }.isFailure) break
                if (!running.get()) break
                val seconds = (SystemClock.elapsedRealtime() - started) / 1_000
                runCatching { onStep("$label, ${seconds}s", 0.04f) }
            }
        }.apply {
            isDaemon = true
            start()
        }
        return try {
            block()
        } finally {
            running.set(false)
            ticker.interrupt()
        }
    }

    /**
     * The same erase, in 128 KiB pieces.
     *
     * A ROM that will not answer one span will answer a small, sector-aligned piece in a fraction
     * of a second, and each piece is closed with FLASH_END asking to stay in the loader so the
     * next one can be asked for. Chunked like this the erase cannot be mistaken for a dead cable:
     * every piece either answers or does not, within seconds.
     */
    private fun eraseInChunks(
        session: UsbBoards.SerialSession,
        eraseBytes: Int,
        onStep: (String, Float) -> Unit,
    ): Boolean {
        var offset = 0
        for (size in EspBootloader.erasePlan(eraseBytes, EspBootloader.ERASE_CHUNK)) {
            val payload = EspBootloader.flashBeginPayload(
                imageSize = 0,
                blocks = 0,
                offset = offset,
                eraseBytes = size,
            )
            var reply = command(session, EspBootloader.OP_FLASH_BEGIN, payload, CHUNK_TIMEOUT_MS)
            if (reply == null) {
                sync(session, "before the erase chunk at $offset")
                reply = command(session, EspBootloader.OP_FLASH_BEGIN, payload, CHUNK_TIMEOUT_MS)
            }
            if (reply == null || reply.status != 0) {
                note("erase chunk at $offset of $size refused (status ${reply?.status})")
                return false
            }
            session.write(
                EspSlip.encode(
                    EspBootloader.command(EspBootloader.OP_FLASH_END, EspBootloader.flashEndPayload(reboot = false)),
                ),
            )
            Thread.sleep(CHUNK_GAP_MS)
            offset += size
            onStep("Erasing ${offset / 1024} of ${eraseBytes / 1024} KiB", 0.05f)
        }
        note("erased $eraseBytes B in chunks")
        return true
    }

    /** The payload as hex, for a failure report that can be read without the board attached. */
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** One DTR/RTS state, held for [holdMs] before the next one is set. */
    private data class Line(val dtr: Boolean, val rts: Boolean, val holdMs: Long)

    /**
     * esptool's `ClassicReset`, for a board behind a USB-UART bridge.
     *
     * DTR drives IO0 and RTS drives EN on those boards: hold the chip in reset with the boot pin
     * low, then release EN - the chip samples IO0 low and comes up in the download mode. The last
     * state releases both lines, because a chip left with EN asserted is a chip held in reset,
     * and that one line was enough to make every reset in this flasher a no-op.
     */
    private val classicResetLines = listOf(
        Line(dtr = false, rts = true, holdMs = 100),
        Line(dtr = true, rts = false, holdMs = 60),
        Line(dtr = false, rts = false, holdMs = 140),
    )

    /**
     * esptool's `USBJTAGSerialReset`, for a chip talking over its own USB peripheral.
     *
     * The internal bridge reads these transitions itself rather than driving strap pins, and the
     * trailing released state is required here too: the peripheral treats RTS as a core reset.
     */
    private val usbJtagResetLines = listOf(
        Line(dtr = false, rts = false, holdMs = 100),
        Line(dtr = true, rts = false, holdMs = 100),
        Line(dtr = false, rts = true, holdMs = 100),
        Line(dtr = false, rts = false, holdMs = 200),
    )

    /** A plain EN pulse, for a board whose boot pin is already strapped to the download mode. */
    private val enPulseLines = listOf(
        Line(dtr = false, rts = true, holdMs = 120),
        Line(dtr = false, rts = false, holdMs = 200),
    )

    /**
     * Gets a board into its bootloader and talking.
     *
     * Four things are tried, cheapest first. A board already sitting in the download mode - left
     * there by a failed flash, or because BOOT was held at power-up - answers a SYNC untouched,
     * and pulsing its lines is the one way to lose a session that was already good. After that
     * the resets run in the order the transport makes likely, and each ends with both lines
     * released so the chip is actually running when the SYNC is sent.
     */
    private fun enterBootloader(session: UsbBoards.SerialSession, board: UsbBoards.Board): Boolean {
        drain(session, 80)
        if (sync(session, "before touching the lines")) return true

        val native = board.transport == UsbBoards.Transport.NATIVE_USB ||
            board.transport == UsbBoards.Transport.CDC_ACM
        val shapes = if (native) {
            listOf(
                "usb-jtag reset" to usbJtagResetLines,
                "DTR/RTS reset" to classicResetLines,
                "EN pulse" to enPulseLines,
            )
        } else {
            listOf(
                "DTR/RTS reset" to classicResetLines,
                "EN pulse" to enPulseLines,
                "usb-jtag reset" to usbJtagResetLines,
            )
        }
        for ((name, shape) in shapes) {
            note("reset: $name")
            applyLines(session, shape)
            drain(session, 250)
            if (sync(session, "after a $name")) return true
        }
        return false
    }

    private fun applyLines(session: UsbBoards.SerialSession, shape: List<Line>) {
        for (line in shape) {
            send(session, line.dtr, line.rts)
            Thread.sleep(line.holdMs)
        }
        // Whatever the shape ended on, the chip has to be out of reset before anything else.
        send(session, dtr = false, rts = false)
    }

    private fun send(session: UsbBoards.SerialSession, dtr: Boolean, rts: Boolean) {
        session.setLines(dtr = dtr, rts = rts)
        note("lines dtr=${if (dtr) 1 else 0} rts=${if (rts) 1 else 0}")
    }

    private fun drain(session: UsbBoards.SerialSession, forMs: Long) {
        val until = SystemClock.elapsedRealtime() + forMs
        while (SystemClock.elapsedRealtime() < until) {
            session.read(256, 60) ?: continue
        }
    }

    private fun sync(session: UsbBoards.SerialSession, why: String = ""): Boolean {
        repeat(7) { attempt ->
            if (command(session, EspBootloader.OP_SYNC, EspBootloader.syncPayload(), SYNC_TIMEOUT_MS) != null) {
                note("synced on attempt ${attempt + 1} ${if (why.isEmpty()) "" else "($why)"}".trim())
                return true
            }
            Thread.sleep(60)
        }
        note("no sync ${if (why.isEmpty()) "" else "($why)"}".trim())
        return false
    }

    /** Sends a command and waits for the reply that echoes its opcode. */
    private fun command(
        session: UsbBoards.SerialSession,
        op: Int,
        payload: ByteArray,
        timeoutMs: Long,
    ): EspBootloader.Response? {
        if (!session.write(EspSlip.encode(EspBootloader.command(op, payload)))) {
            note("op 0x%02x could not be written".format(op))
            return null
        }
        note("tx 0x%02x %d B %s".format(op, payload.size, hex(payload).take(64)))
        val reader = EspSlip.Reader()
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val left = until - SystemClock.elapsedRealtime()
            if (left <= 0) {
                note("op 0x%02x: nothing in $timeoutMs ms".format(op))
                return null
            }
            // One transfer that spans whatever is left. A read that times out cancels the pending
            // USB transfer on Android, and a reply that arrives inside that cancellation is gone
            // for good - which is precisely the reply the erase sends after its silence.
            val chunk = session.read(512, left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) ?: continue
            note("rx %d B %s".format(chunk.size, hex(chunk).take(64)))
            for (frame in reader.feed(chunk)) {
                val response = EspBootloader.response(frame) ?: continue
                if (response.op == op) {
                    note("op 0x%02x status %d".format(op, response.status))
                    return response
                }
            }
        }
    }

    private companion object {
        const val MANIFEST = "firmware/firmware.json"
        const val BAUD = 115_200
        const val SYNC_TIMEOUT_MS = 400L

        /**
         * Never wait less than this for an erase, however small the image is. A 4 MiB module
         * erasing a 1.3 MiB span has been seen to take well past half a minute, and the ROM is
         * silent for all of it.
         */
        const val ERASE_MIN_TIMEOUT_MS = 45_000L

        /** A FLASH_BEGIN asking for no erase is answered at once; this is slack, not a wait. */
        const val BEGIN_TIMEOUT_MS = 3_000L

        /** One 128 KiB erase, which a ROM answers in well under a second. */
        const val CHUNK_TIMEOUT_MS = 6_000L

        /** FLASH_END is not answered in ROM mode; the pause is for the chip to close the session. */
        const val CHUNK_GAP_MS = 60L
        const val BLOCK_TIMEOUT_MS = 8_000L
        const val WIRE_LINES = 160
    }
}
