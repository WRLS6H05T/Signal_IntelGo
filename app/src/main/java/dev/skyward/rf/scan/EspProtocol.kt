package dev.skyward.rf.scan

import java.io.ByteArrayOutputStream

/**
 * The ESP32 ROM bootloader's wire format, as much of it as the flasher needs.
 *
 * Every exchange is a SLIP frame around a command packet. The command packet is eight bytes of
 * header - a direction byte, the opcode, a 16-bit length and a 32-bit checksum - followed by the
 * payload. The checksum starts at `0xEF` and is XORed with every payload byte, which is a weak
 * check on purpose: it costs the ROM nothing and catches a corrupted frame before it is acted
 * on. Replies come back the same way with direction `0x01` and the opcode echoed.
 *
 * The numbers here were taken from esptool 5.6 rather than from memory: 1024-byte writes, the
 * `07 07 12 20` sync preamble, the five-word FLASH_BEGIN that non-ESP32 chips expect, and
 * FLASH_END's inverted reboot word. Getting any of them wrong looks, from the phone, exactly
 * like a board that is not there.
 *
 * This object is deliberately free of Android and USB types so the exact bytes can be tested on
 * the JVM; [EspFlasher] does the talking to the port and hands frames here.
 */
object EspSlip {

    const val END = 0xC0
    const val ESC = 0xDB
    const val ESC_END = 0xDC
    const val ESC_ESC = 0xDD

    /** Wraps [data] in a frame, escaping the three bytes the framing reserves. */
    fun encode(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 8)
        out.write(END)
        for (b in data) {
            when (val v = b.toInt() and 0xFF) {
                END -> {
                    out.write(ESC)
                    out.write(ESC_END)
                }

                ESC -> {
                    out.write(ESC)
                    out.write(ESC_ESC)
                }

                else -> out.write(v)
            }
        }
        out.write(END)
        return out.toByteArray()
    }

    /** Unescapes one frame, or null when an escape sequence is cut short. */
    fun decode(frame: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream(frame.size)
        var i = 0
        while (i < frame.size) {
            val v = frame[i].toInt() and 0xFF
            i++
            if (v != ESC) {
                out.write(v)
                continue
            }
            if (i >= frame.size) return null
            when (val next = frame[i].toInt() and 0xFF) {
                ESC_END -> out.write(END)
                ESC_ESC -> out.write(ESC)
                // A SLIP implementation that escapes something else is not speaking the same
                // protocol; treating it as an error beats writing a wrong byte to flash.
                else -> return null
            }
            i++
        }
        return out.toByteArray()
    }

    /**
     * Turns the port's byte stream back into whole responses.
     *
     * A read from USB ends wherever the board's packet ended, so a frame can arrive in pieces
     * or several can arrive at once; the partial one is kept until its closing [END] shows up.
     */
    class Reader {
        private val pending = ByteArrayOutputStream()
        private var started = false

        fun feed(bytes: ByteArray, length: Int = bytes.size): List<ByteArray> {
            val frames = ArrayList<ByteArray>(2)
            for (i in 0 until length.coerceAtMost(bytes.size)) {
                val v = bytes[i].toInt() and 0xFF
                if (v == END) {
                    if (started && pending.size() > 0) {
                        decode(pending.toByteArray())?.let { if (it.isNotEmpty()) frames.add(it) }
                    }
                    pending.reset()
                    started = false
                } else {
                    started = true
                    pending.write(v)
                }
            }
            return frames
        }

        fun reset() {
            pending.reset()
            started = false
        }
    }
}

/**
 * Command packets and the handful of ROM commands the flasher issues.
 *
 * The sequence is the stock one: reset into the bootloader, SYNC until the board answers,
 * READ_REG at the chip-detect address to learn which silicon is attached, then FLASH_BEGIN, one
 * FLASH_DATA per 1 KiB block and FLASH_END. No stub loader is uploaded, so this works on an
 * untouched board with nothing but its ROM: slower than esptool's stub at high baud, but it has
 * no chip-specific second stage that can be mismatched.
 */
object EspBootloader {

    const val OP_FLASH_BEGIN = 0x02
    const val OP_FLASH_DATA = 0x03
    const val OP_FLASH_END = 0x04
    const val OP_SYNC = 0x08
    const val OP_READ_REG = 0x0A

    private const val CHECKSUM_SEED = 0xEF

    /** Where every ESP32 variant publishes its chip-detect magic. */
    const val CHIP_DETECT_REG: Int = 0x40001000

    /** The ROM's write size: the block size esptool sends and the one FLASH_BEGIN declares. */
    const val BLOCK_SIZE: Int = 1024

    /** The ROM erases in 4 KiB sectors, so a partial last sector has to be counted. */
    const val ERASE_ALIGN: Int = 4096

    /**
     * Chip id magic values, for the parts that publish one.
     *
     * Only C3 is trusted here: esptool 5.6 marks both C3 and C5 as not using the magic value
     * register, and guessing the C5's number would be worse than not knowing it, because a
     * wrong answer would pick the wrong image. The dialog asks for the chip instead, and this
     * map is only a hint when a board does answer.
     */
    val CHIPS: Map<Long, String> = mapOf(
        0x1B31506FL to "ESP32-C3",
        0x2CE0806FL to "ESP32-C6",
        0x00000009L to "ESP32-S3",
        0x00F01D83L to "ESP32",
        0x000007C6L to "ESP32-S2",
        0x6921506FL to "ESP32-C2",
        0xCA26CC22L to "ESP32-H2",
    )

    fun chipName(magic: Long): String? = CHIPS[magic and 0xFFFFFFFFL]

    /** The command packet, before SLIP framing. */
    fun command(op: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(8 + payload.size)
        out[0] = 0x00
        out[1] = op.toByte()
        out[2] = (payload.size and 0xFF).toByte()
        out[3] = ((payload.size ushr 8) and 0xFF).toByte()
        var checksum = CHECKSUM_SEED
        for (b in payload) checksum = checksum xor (b.toInt() and 0xFF)
        out[4] = (checksum and 0xFF).toByte()
        out[5] = ((checksum ushr 8) and 0xFF).toByte()
        out[6] = ((checksum ushr 16) and 0xFF).toByte()
        out[7] = ((checksum ushr 24) and 0xFF).toByte()
        payload.copyInto(out, 8)
        return out
    }

    /**
     * SYNC's payload is not just filler: four bytes of `07 07 12 20` then 32 of `0x55`, which
     * is what the ROM's autobaud detector keys on.
     */
    fun syncPayload(): ByteArray {
        val out = ByteArray(36) { 0x55 }
        out[0] = 0x07
        out[1] = 0x07
        out[2] = 0x12
        out[3] = 0x20
        return out
    }

    /** A reply: the opcode it answers, its value field and any data. */
    data class Response(val op: Int, val value: Int, val data: ByteArray) {
        val status: Int
            /** FLASH_* report success as the first two data bytes; the rest carry the value. */
            get() = if (data.size >= 2) (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8) else value
    }

    fun response(frame: ByteArray): Response? {
        if (frame.size < 8) return null
        if (frame[0].toInt() and 0xFF != 0x01) return null
        val op = frame[1].toInt() and 0xFF
        val size = (frame[2].toInt() and 0xFF) or ((frame[3].toInt() and 0xFF) shl 8)
        val value = (frame[4].toInt() and 0xFF) or
            ((frame[5].toInt() and 0xFF) shl 8) or
            ((frame[6].toInt() and 0xFF) shl 16) or
            ((frame[7].toInt() and 0xFF) shl 24)
        val data = if (size > 0 && frame.size >= 8 + size) frame.copyOfRange(8, 8 + size) else ByteArray(0)
        return Response(op, value, data)
    }

    /**
     * FLASH_BEGIN: erase size, block count, block size, offset and the encrypted flag. The fifth
     * word is what C3/C5 ROMs expect; the classic ESP32 takes four and is not flashed here.
     */
    fun flashBeginPayload(imageSize: Int, blocks: Int, offset: Int = 0): ByteArray =
        ints(eraseSize(imageSize), blocks, BLOCK_SIZE, offset, 0)

    fun flashDataPayload(dataSize: Int, seq: Int, data: ByteArray): ByteArray {
        val head = ints(dataSize, seq, 0, 0)
        val out = ByteArray(head.size + data.size)
        head.copyInto(out, 0)
        data.copyInto(out, head.size)
        return out
    }

    /**
     * FLASH_END's word is inverted on the wire: 0 reboots into the new image, 1 stays in the
     * bootloader. Sending 1 here is the classic "flashed but nothing happens" on a bench board.
     */
    fun flashEndPayload(reboot: Boolean = true): ByteArray = ints(if (reboot) 0 else 1)

    /**
     * Splits the image into ROM-sized blocks, padding the last with `0xFF` - the erased state of
     * flash, so the padding changes nothing if the block is ever rewritten.
     */
    fun blocks(image: ByteArray, blockSize: Int = BLOCK_SIZE): List<ByteArray> {
        if (image.isEmpty()) return emptyList()
        val count = (image.size + blockSize - 1) / blockSize
        return (0 until count).map { i ->
            val from = i * blockSize
            val to = minOf(from + blockSize, image.size)
            if (to - from == blockSize) {
                image.copyOfRange(from, to)
            } else {
                ByteArray(blockSize) { 0xFF.toByte() }.also { image.copyInto(it, 0, from, to) }
            }
        }
    }

    /** How much the ROM will erase: the image rounded up to whole 4 KiB sectors. */
    fun eraseSize(imageSize: Int): Int {
        val sectors = (imageSize + ERASE_ALIGN - 1) / ERASE_ALIGN
        return sectors * ERASE_ALIGN
    }

    private fun ints(vararg values: Int): ByteArray {
        val out = ByteArray(values.size * 4)
        values.forEachIndexed { index, v ->
            out[index * 4] = (v and 0xFF).toByte()
            out[index * 4 + 1] = ((v ushr 8) and 0xFF).toByte()
            out[index * 4 + 2] = ((v ushr 16) and 0xFF).toByte()
            out[index * 4 + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        return out
    }
}
