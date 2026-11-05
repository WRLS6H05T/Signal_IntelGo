package dev.skyward.rf.scan

import android.content.Context
import android.os.SystemClock
import dev.skyward.rf.core.CrashLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream

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
 * Two things about the hardware are handled here and nothing else can be. A chip that owns its
 * USB peripheral leaves the bus when EN pulses and returns as a *different* device id, so a lost
 * device is reopened rather than mistaken for a silent one. And the ROM's own console is read
 * before anything is sent: a chip that prints "waiting for download" is already in the mode the
 * flash needs, and a reset at that point can only lose it.
 *
 * What happens on the wire is recorded in [wire], so a board that misbehaves leaves something to
 * read rather than another report of a hang.
 */
class EspFlasher(private val context: Context, private val usb: UsbBoards) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * What went over the wire, oldest first.
     *
     * Three attempts at this ended in "it stops at erasing flash", which is the one sentence the
     * wire can never be asked about after the fact. This keeps the exchange - every frame sent,
     * every byte read, the ROM's own console text, every line state and every timeout - so the
     * dialog can show it and a failed flash can put it in the crash report.
     */
    private val _wire = MutableStateFlow<List<String>>(emptyList())
    val wire: StateFlow<List<String>> = _wire

    /**
     * Bytes received since this attempt began.
     *
     * The difference between a board that refuses a command and a port that is not connected to
     * the chip at all is this number: zero after a reset means the device re-enumerated and the
     * old port is a dead end, whatever the writes say.
     */
    private var received = 0

    /** Printable text the chip printed on its own, before or between commands. */
    private val console = StringBuilder()

    private fun note(line: String) {
        _wire.value = (_wire.value + line).takeLast(WIRE_LINES)
    }

    private fun wireTail(): String = _wire.value.takeLast(40).joinToString("\n")

    /** The device went away mid-session: a chip that resets re-enumerates. */
    private class BoardLost : Exception("the board left the bus and came back as a new device")

    /** The port never sent a single byte: wrong interface, or nothing is listening on it. */
    private class PortSilent(message: String) : Exception(message)

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
     *
     * The outer loop is the part that makes a self-powered USB board work: an attempt either
     * finishes, fails for a reason worth reporting, or loses the device to a reset - and only the
     * last two are retried, on the new id or the next interface.
     */
    fun flash(board: UsbBoards.Board, image: EspImage, onStep: (String, Float) -> Unit): Result<String> {
        val bytes = runCatching { context.assets.open(image.asset).use { it.readBytes() } }.getOrNull()
            ?: return Result.failure(IllegalStateException("This build has no ${image.label} image bundled"))
        if (bytes.size < 64) {
            return Result.failure(IllegalStateException("The ${image.label} image is only ${bytes.size} bytes"))
        }

        _wire.value = emptyList()
        note("${image.label} ${bytes.size} B on ${board.label} (${board.transport.label})")
        var target = board
        var index = 0
        var relinks = 0
        var silent = "The board never sent a single byte, on any interface of the device."
        while (index < usb.portCount(target.deviceId).coerceAtLeast(1)) {
            val session = usb.openSession(target.deviceId, BAUD, index)
            if (session == null) {
                note("interface ${index + 1} could not be opened")
                index++
                continue
            }
            var lost = false
            try {
                return Result.success(attempt(target, session, image, bytes, onStep))
            } catch (e: BoardLost) {
                lost = true
            } catch (e: PortSilent) {
                silent = e.message ?: silent
                note("interface ${index + 1} never sent a byte")
            } catch (t: Throwable) {
                CrashLog.recordQuiet(context, "esp-flash", t)
                return Result.failure(t)
            } finally {
                session.close()
            }
            if (lost) {
                if (relinks >= MAX_RELINKS) {
                    return Result.failure(
                        IllegalStateException(
                            "The board kept dropping off the cable: it resets itself away from the USB port " +
                                "it was opened on. Unplug it, hold BOOT, plug it back in, then flash again.",
                        ),
                    )
                }
                relinks++
                note("the board left the bus; waiting for it to come back")
                target = reopen(target) ?: return Result.failure(
                    IllegalStateException(
                        "The board reset and never came back. Unplug it, plug it in again, then flash.",
                    ),
                )
                note("the board is back as ${target.label}; retrying on interface ${index + 1}")
                // Same interface index, new device: the reset that caused this already ran.
                continue
            }
            index++
        }
        CrashLog.recordQuiet(context, "esp-flash-sync", IllegalStateException(wireTail()))
        return Result.failure(IllegalStateException(silent))
    }

    /** Waits for the reset device to re-appear, up to [REATTACH_MS]. */
    private fun reopen(board: UsbBoards.Board): UsbBoards.Board? {
        val deadline = SystemClock.elapsedRealtime() + REATTACH_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val next = usb.replacementFor(board.vendorId, board.productId, board.deviceId)
            if (next != null) return next
            Thread.sleep(120)
        }
        return null
    }

    /** One full flash over one open port. Throws [BoardLost] or [PortSilent] for the outer loop. */
    private fun attempt(
        board: UsbBoards.Board,
        session: UsbBoards.SerialSession,
        image: EspImage,
        bytes: ByteArray,
        onStep: (String, Float) -> Unit,
    ): String {
        received = 0
        console.setLength(0)
        onStep("Entering the bootloader", 0.02f)
        if (!enterBootloader(session, board)) {
            val said = consoleText()
            if (received == 0) {
                throw PortSilent(
                    "Nothing was received from the board at all, on any interface. Its USB device is on " +
                        "the cable but its serial side is not talking to this phone.",
                )
            }
            throw IllegalStateException(
                if (said.isNotBlank()) {
                    "The board's console says \"${said.take(140)}\", so it is in download mode, but it does " +
                        "not answer a SYNC on this interface. Try the PORT button in the serial panel, or " +
                        "flash with the board unplugged and reconnected while BOOT is held."
                } else {
                    "The board answered nothing after a reset on every line shape. Hold BOOT, tap RESET, " +
                        "release BOOT, then flash again."
                },
            )
        }

        val blocks = EspBootloader.blocks(bytes)
        val eraseBytes = EspBootloader.eraseSize(bytes.size)
        val label = "Erasing ${eraseBytes / 1024} KiB"
        onStep(label, 0.04f)

        // esptool's own shape: one FLASH_BEGIN that asks for the image's span and answers only
        // once the erase is done. The budget is 40 s per megabyte with a floor, and every read
        // inside it waits on the whole remaining time rather than timing out and cancelling - a
        // cancelled USB transfer is how an answer that arrived gets thrown away.
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
                throw IllegalStateException(
                    "The board never answered the erase, in one span or in chunks. Unplug it, hold BOOT, " +
                        "plug it back in, then flash again - the wire log in this dialog says what came back.",
                )
            }
            // The flash is erased, so the write's own FLASH_BEGIN must not ask for another: this
            // one is answered at once and cannot look like a hang.
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
                throw IllegalStateException("The board stopped answering after the erase.")
            }
        }

        blocks.forEachIndexed { index, block ->
            val payload = EspBootloader.flashDataPayload(block.size, index, block)
            var wrote = false
            // One retry: a USB hiccup mid-image is worth repeating the block for rather than
            // failing an erase that has already happened.
            repeat(2) {
                if (wrote) return@repeat
                val reply = command(session, EspBootloader.OP_FLASH_DATA, payload, BLOCK_TIMEOUT_MS)
                wrote = reply != null && reply.status == 0
            }
            if (!wrote) {
                CrashLog.recordQuiet(context, "esp-flash-block", IllegalStateException(wireTail()))
                throw IllegalStateException("The board rejected block $index of ${blocks.size}")
            }
            val done = (index + 1).toFloat() / blocks.size
            onStep("Writing block ${index + 1} of ${blocks.size}", 0.08f + 0.88f * done)
        }

        onStep("Restarting", 0.98f)
        // The ROM does not answer FLASH_END, so this is sent and the reboot is allowed to happen
        // rather than waited on.
        session.write(
            EspSlip.encode(
                EspBootloader.command(EspBootloader.OP_FLASH_END, EspBootloader.flashEndPayload(true)),
            ),
        )
        note("FLASH_END sent; the board reboots into the new image")
        Thread.sleep(900)
        onStep("Done", 1f)
        return "${image.label} v${firmware(image)?.version ?: "?"} written, ${bytes.size / 1024} KiB"
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
     * The chip's own console is read first, and that is the part that matters most here: a ROM
     * that printed "waiting for download" is already exactly where the flash wants it, so the
     * only reset worth trying is a bare EN pulse - and even that is a risk, because a chip that
     * owns its USB peripheral disappears when it is reset. Everything else is tried in the order
     * the transport makes likely, each ending with both lines released so the chip is running
     * when the SYNC goes out.
     *
     * A reset on a chip with its own USB peripheral takes the device off the bus, and that is
     * reported as [BoardLost] rather than as silence: the caller reopens the new device, and the
     * retry's untouched SYNC finds the chip already in the download mode its reset put it in.
     */
    private fun enterBootloader(session: UsbBoards.SerialSession, board: UsbBoards.Board): Boolean {
        drain(session, 350, capture = true)
        val said = consoleText()
        if (said.isNotBlank()) note("console: $said")
        if (sync(session, "as it is")) return true

        // The ROM's own words are the best evidence available: "waiting for download" means the
        // chip is in the mode the flash needs and is waiting for exactly the SYNC just sent.
        // Resetting it there restarts the same ROM at best, and drops the USB device at worst.
        val waiting = said.contains("waiting for download", ignoreCase = true) ||
            said.contains("DOWNLOAD", ignoreCase = true)
        val native = board.transport == UsbBoards.Transport.NATIVE_USB ||
            board.transport == UsbBoards.Transport.CDC_ACM
        val shapes = when {
            waiting -> listOf("EN pulse" to enPulseLines)
            native -> listOf(
                "usb-jtag reset" to usbJtagResetLines,
                "DTR/RTS reset" to classicResetLines,
                "EN pulse" to enPulseLines,
            )

            else -> listOf(
                "DTR/RTS reset" to classicResetLines,
                "EN pulse" to enPulseLines,
                "usb-jtag reset" to usbJtagResetLines,
            )
        }
        if (waiting) note("the ROM says it is waiting for download; only EN is pulsed, and only once")
        for ((name, shape) in shapes) {
            note("reset: $name")
            applyLines(session, shape)
            if (!usb.isAttached(session.deviceId)) {
                note("the board left the bus on the $name; it comes back as a new device")
                throw BoardLost()
            }
            drain(session, 250, capture = true)
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

    /** Reads and discards whatever the chip is printing, keeping the text when asked. */
    private fun drain(session: UsbBoards.SerialSession, forMs: Long, capture: Boolean = false) {
        val until = SystemClock.elapsedRealtime() + forMs
        while (SystemClock.elapsedRealtime() < until) {
            val bytes = session.read(256, 60) ?: continue
            if (capture) collectText(bytes)
        }
    }

    private fun collectText(bytes: ByteArray) {
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            when {
                c == 0x0A || c == 0x0D -> if (console.isNotEmpty() && console.last() != ' ') console.append(' ')
                c in 0x20..0x7E -> console.append(c.toChar())
            }
        }
        if (console.length > CONSOLE_CHARS) console.delete(0, console.length - CONSOLE_CHARS)
    }

    private fun consoleText(): String = console.toString().replace(Regex(" {2,}"), " ").trim()

    /**
     * Sends the sync payload the way esptool does: eight frames back to back, then one wait.
     *
     * A ROM that is still drawing its banner can drop the first frames it is handed, and the
     * cost of a burst is a few hundred bytes. [SYNC_ROUNDS] of those is two seconds of asking,
     * which is all a chip in the download mode ever needs - if it is going to answer at all.
     */
    private fun sync(session: UsbBoards.SerialSession, why: String = ""): Boolean {
        repeat(SYNC_ROUNDS) { round ->
            val burst = ByteArrayOutputStream(SYNC_FRAMES * 48)
            repeat(SYNC_FRAMES) {
                burst.write(EspSlip.encode(EspBootloader.command(EspBootloader.OP_SYNC, EspBootloader.syncPayload())))
            }
            if (!usb.isAttached(session.deviceId)) throw BoardLost()
            if (!session.write(burst.toByteArray())) {
                note("the sync burst could not be written")
                return false
            }
            if (round == 0) note("tx $SYNC_FRAMES sync frames", )
            val reply = await(session, EspBootloader.OP_SYNC, SYNC_LISTEN_MS)
            if (reply != null) {
                note("synced on round ${round + 1} ${if (why.isEmpty()) "" else "($why)"}".trim())
                return true
            }
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
        // A session whose device is gone can be written to and will never be read from, which is
        // the whole "nothing in 400 ms" report: check the bus before blaming the chip.
        if (!usb.isAttached(session.deviceId)) throw BoardLost()
        if (!session.write(EspSlip.encode(EspBootloader.command(op, payload)))) {
            note("op 0x%02x could not be written".format(op))
            return null
        }
        note("tx 0x%02x %d B %s".format(op, payload.size, hex(payload).take(64)))
        return await(session, op, timeoutMs)
    }

    /** Reads until the reply for [op] arrives, the caller loses patience, or the board vanishes. */
    private fun await(session: UsbBoards.SerialSession, op: Int, timeoutMs: Long): EspBootloader.Response? {
        val reader = EspSlip.Reader()
        val until = SystemClock.elapsedRealtime() + timeoutMs
        var empty = 0
        while (true) {
            val left = until - SystemClock.elapsedRealtime()
            if (left <= 0) {
                note("op 0x%02x: nothing in $timeoutMs ms".format(op))
                return null
            }
            // One transfer that spans whatever is left. A read that times out cancels the pending
            // USB transfer on Android, and a reply that arrives inside that cancellation is gone
            // for good - which is precisely the reply the erase sends after its silence.
            val chunk = session.read(512, left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            if (chunk == null) {
                empty++
                // Two empty transfers on a device that is no longer on the bus means the port is
                // dead, not that the chip is slow.
                if (empty >= 2 && !usb.isAttached(session.deviceId)) throw BoardLost()
                continue
            }
            empty = 0
            received += chunk.size
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
        const val WIRE_LINES = 200
        const val CONSOLE_CHARS = 400

        /** esptool's own habit: the sync payload goes out eight times per round. */
        const val SYNC_FRAMES = 8
        const val SYNC_ROUNDS = 4

        /** How long one sync burst is given to be answered. */
        const val SYNC_LISTEN_MS = 500L

        /** How long a chip that reset has to re-appear on the bus before it is called gone. */
        const val REATTACH_MS = 6_000L

        /** A board that drops off the bus more often than this is not going to be flashed. */
        const val MAX_RELINKS = 3
    }
}
