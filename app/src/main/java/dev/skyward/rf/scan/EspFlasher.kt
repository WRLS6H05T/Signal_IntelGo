package dev.skyward.rf.scan

import android.content.Context
import android.os.SystemClock
import dev.skyward.rf.core.CrashLog
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
 * frames, checksums, block arithmetic and image manifest all have JVM tests.
 */
class EspFlasher(private val context: Context, private val usb: UsbBoards) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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

        val session = usb.openSession(board.deviceId, 115200)
            ?: return Result.failure(IllegalStateException("The port is busy; unplug the board and try again"))

        try {
            onStep("Entering the bootloader", 0.02f)
            if (!enterBootloader(session, board)) {
                return Result.failure(IllegalStateException("No answer from the bootloader. Hold BOOT and tap RESET, then try again"))
            }

            // The erase is the first word of FLASH_BEGIN, and the ROM answers only once it has
            // finished erasing that many bytes. Ask for the image's own span, rounded up to
            // whole 4 KiB sectors: it covers the bootloader, the partition table and the app in
            // one merged image, it is what esptool asks for by default, and it finishes in
            // seconds. Asking for the whole 4 MiB instead was where this flasher stalled - a
            // long erase with no answer in the middle of it reads as a dead cable, and the
            // C3/C5 ROMs do not implement the separate ERASE_FLASH (0xD0) that would be the
            // obvious alternative: esptool only sends that after uploading its stub.
            val blocks = EspBootloader.blocks(bytes)
            val eraseBytes = EspBootloader.eraseSize(bytes.size)
            // Both shapes, so a board that ignores one still has a chance at the other: the
            // extended five-word form is what the C3 and C5 ROMs expect, the four-word form is
            // what the classic ESP32 answers.
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
            // esptool's own budget for a ROM erase: 40 s per megabyte, with a floor. A slow
            // module can take most of a minute for a 1.3 MiB span, and abandoning the wait is
            // worse than a slow one: the ROM answers nothing while it erases, so a retry sent
            // into that silence lands on a busy chip and the flash ends with the board partly
            // erased and unresponsive.
            val eraseTimeoutMs = (bytes.size / 1_000_000.0 * 40_000.0).toLong()
                .coerceAtLeast(ERASE_MIN_TIMEOUT_MS)
            val label = "Erasing ${eraseBytes / 1024} KiB"
            onStep(label, 0.04f)
            val begin = ticking(label, onStep) {
                command(session, EspBootloader.OP_FLASH_BEGIN, beginExtended, eraseTimeoutMs)
                    ?: run {
                        // Re-sync rather than resend: a fresh SYNC is what puts the ROM back in
                        // step if the first frame never reached it. Repeating the command on top
                        // of a running erase only adds noise to a silent chip.
                        sync(session)
                        command(session, EspBootloader.OP_FLASH_BEGIN, beginExtended, eraseTimeoutMs)
                    }
                    ?: run {
                        sync(session)
                        command(session, EspBootloader.OP_FLASH_BEGIN, beginLegacy, eraseTimeoutMs)
                    }
            } ?: run {
                // A flash that fails on a board nobody can look at has to leave something
                // readable behind: the exact bytes sent are the first thing to check against
                // esptool when a ROM stays silent.
                CrashLog.recordQuiet(
                    context,
                    "esp-flash-erase",
                    IllegalStateException(
                        "no reply to FLASH_BEGIN; image ${bytes.size} B, erase $eraseBytes B, " +
                            "extended ${hex(beginExtended)}, legacy ${hex(beginLegacy)}",
                    ),
                )
                return Result.failure(
                    IllegalStateException(
                        "The board stayed silent for ${eraseTimeoutMs / 1000}s on each erase attempt. " +
                            "Unplug it, hold BOOT, tap RESET, plug it back in, then flash again.",
                    ),
                )
            }
            if (begin.status != 0) {
                return Result.failure(IllegalStateException("The board refused the erase (status ${begin.status})"))
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
                    return Result.failure(IllegalStateException("The board rejected block $index of ${blocks.size}"))
                }
                val done = (index + 1).toFloat() / blocks.size
                onStep("Writing block ${index + 1} of ${blocks.size}", 0.08f + 0.88f * done)
            }

            onStep("Restarting", 0.98f)
            // The ROM does not answer FLASH_END, so this is sent and the reboot is allowed to
            // happen rather than waited on.
            session.write(EspSlip.encode(EspBootloader.command(EspBootloader.OP_FLASH_END, EspBootloader.flashEndPayload(true))))
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

    /** The payload as hex, for a failure report that can be read without the board attached. */
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /**
     * Gets a board into its bootloader and talking.
     *
     * Boards differ: a mini board with native USB needs the USB-Serial-JTAG pulse train, a
     * board behind a CH340 or CP210x needs the classic IO0/EN sequence, and the line polarity
     * is not the same everywhere. Trying both shapes beats asking the user which cable they
     * have, and costs about a second when the first is right.
     */
    private fun enterBootloader(session: UsbBoards.SerialSession, board: UsbBoards.Board): Boolean {
        val native = board.transport == UsbBoards.Transport.NATIVE_USB ||
            board.transport == UsbBoards.Transport.CDC_ACM
        val order = if (native) listOf(0, 1, 0, 1) else listOf(1, 0, 1, 0)
        for (attempt in order) {
            if (attempt == 0) usbJtagReset(session) else classicReset(session)
            drain(session, 250)
            if (sync(session)) return true
        }
        return false
    }

    private fun usbJtagReset(session: UsbBoards.SerialSession) {
        // The internal USB bridge reacts to this pulse train; the trailing RTS rewrites are
        // what make Windows flush the DTR change.
        session.setLines(dtr = true, rts = true)
        Thread.sleep(100)
        session.setLines(dtr = false, rts = true)
        Thread.sleep(100)
        session.setLines(dtr = true, rts = false)
        Thread.sleep(20)
        session.setLines(dtr = true, rts = true)
        Thread.sleep(150)
    }

    private fun classicReset(session: UsbBoards.SerialSession) {
        // DTR drives IO0 and RTS drives EN: hold the chip in reset with IO0 low, release both.
        session.setLines(dtr = true, rts = false)
        Thread.sleep(100)
        session.setLines(dtr = false, rts = true)
        Thread.sleep(80)
        session.setLines(dtr = true, rts = true)
        Thread.sleep(150)
    }

    private fun drain(session: UsbBoards.SerialSession, forMs: Long) {
        val until = SystemClock.elapsedRealtime() + forMs
        while (SystemClock.elapsedRealtime() < until) {
            session.read(256, 60) ?: continue
        }
    }

    private fun sync(session: UsbBoards.SerialSession): Boolean {
        repeat(7) {
            if (command(session, EspBootloader.OP_SYNC, EspBootloader.syncPayload(), SYNC_TIMEOUT_MS) != null) {
                return true
            }
            Thread.sleep(60)
        }
        return false
    }

    /** Sends a command and waits for the reply that echoes its opcode. */
    private fun command(
        session: UsbBoards.SerialSession,
        op: Int,
        payload: ByteArray,
        timeoutMs: Long,
    ): EspBootloader.Response? {
        if (!session.write(EspSlip.encode(EspBootloader.command(op, payload)))) return null
        val reader = EspSlip.Reader()
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val left = until - SystemClock.elapsedRealtime()
            if (left <= 0) return null
            // One long read rather than hundreds of 120 ms ones. A cancelled IN transfer can
            // lose the reply the board was in the middle of sending, and FLASH_BEGIN is the one
            // command whose answer arrives after a long silence - the whole erase. Blocking on a
            // read that spans that silence is the difference between a reply that is collected
            // and a board that looks as if it never answered.
            val bytes = session.read(512, left.coerceAtMost(5_000L).toInt()) ?: continue
            for (frame in reader.feed(bytes)) {
                val response = EspBootloader.response(frame) ?: continue
                if (response.op == op) return response
            }
        }
    }

    private companion object {
        const val MANIFEST = "firmware/firmware.json"
        const val SYNC_TIMEOUT_MS = 400L
        /**
         * Never wait less than this for an erase, however small the image is. A 4 MiB module
         * erasing a 1.3 MiB span has been seen to take well past half a minute, and the ROM is
         * silent for all of it.
         */
        const val ERASE_MIN_TIMEOUT_MS = 45_000L
        const val BLOCK_TIMEOUT_MS = 8_000L
    }
}
