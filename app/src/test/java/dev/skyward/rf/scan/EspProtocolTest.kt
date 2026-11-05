package dev.skyward.rf.scan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flasher's byte layout is checked here because it is the one part that has to be exact:
 * a wrong checksum or an unescaped 0xC0 and the ROM silently ignores the command, which on a
 * board reaches the user as "nothing happens".
 */
class EspProtocolTest {

    @Test
    fun `flash begin erases the whole flash before the image when asked`() {
        val p = EspBootloader.flashBeginPayload(imageSize = 1_303_456, blocks = 1273, eraseBytes = 4 * 1024 * 1024)
        assertEquals(20, p.size)
        // 0x00400000, little endian: the whole 4 MiB chip, not just the image's sectors.
        assertEquals(0x00, p[0].toInt())
        assertEquals(0x00, p[1].toInt())
        assertEquals(0x40, p[2].toInt())
        assertEquals(0x00, p[3].toInt())
        // 1273 blocks follow unchanged, so only the erase size moved.
        assertEquals(0xF9, p[4].toInt() and 0xFF)
        assertEquals(0x04, p[5].toInt() and 0xFF)
    }

    @Test
    fun `slip escapes the reserved bytes and decodes back`() {
        val payload = byteArrayOf(0x01, 0xC0.toByte(), 0xDB.toByte(), 0x55, 0xC0.toByte())
        val framed = EspSlip.encode(payload)
        assertEquals(0xC0.toByte(), framed.first())
        assertEquals(0xC0.toByte(), framed.last())
        assertTrue(framed.contains(0xDC.toByte()))
        assertTrue(framed.contains(0xDD.toByte()))
        assertArrayEquals(payload, EspSlip.decode(framed.copyOfRange(1, framed.size - 1)))
    }

    @Test
    fun `slip decoding an unterminated escape is an error rather than a wrong byte`() {
        assertNull(EspSlip.decode(byteArrayOf(0x01, 0xDB.toByte())))
    }

    @Test
    fun `reader keeps a frame that arrives in pieces and splits two that arrive at once`() {
        val first = EspSlip.encode(byteArrayOf(1, 2, 3))
        val second = EspSlip.encode(byteArrayOf(4, 5))
        val stream = first + second
        val reader = EspSlip.Reader()
        val early = reader.feed(stream, 4)
        assertEquals(0, early.size)
        val rest = reader.feed(stream.copyOfRange(4, stream.size), stream.size - 4)
        assertEquals(2, rest.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), rest[0])
        assertArrayEquals(byteArrayOf(4, 5), rest[1])
    }

    @Test
    fun `command header carries the length and the xor checksum`() {
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val packet = EspBootloader.command(EspBootloader.OP_SYNC, data)
        assertEquals(0, packet[0].toInt())
        assertEquals(EspBootloader.OP_SYNC, packet[1].toInt())
        assertEquals(3, packet[2].toInt())
        assertEquals(0, packet[3].toInt())
        val checksum = 0xEF xor 0x01 xor 0x02 xor 0x03
        assertEquals(checksum, packet[4].toInt() and 0xFF)
        assertEquals(0, packet[5].toInt())
        assertEquals(0, packet[6].toInt())
        assertEquals(0, packet[7].toInt())
        assertArrayEquals(data, packet.copyOfRange(8, 11))
    }

    @Test
    fun `sync opens with the rom autobaud preamble and is thirty six bytes`() {
        val sync = EspBootloader.syncPayload()
        assertEquals(36, sync.size)
        assertArrayEquals(byteArrayOf(0x07, 0x07, 0x12, 0x20), sync.copyOfRange(0, 4))
        assertArrayEquals(ByteArray(32) { 0x55 }, sync.copyOfRange(4, 36))
    }

    @Test
    fun `response reads the value and the status`() {
        val frame = byteArrayOf(0x01, 0x02, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val r = EspBootloader.response(frame)!!
        assertEquals(EspBootloader.OP_FLASH_BEGIN, r.op)
        assertEquals(0, r.status)
        val failure = byteArrayOf(0x01, 0x02, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x05, 0x00)
        assertEquals(5, EspBootloader.response(failure)!!.status)
        assertNull(EspBootloader.response(frame.copyOfRange(0, 6)))
    }

    @Test
    fun `blocks pad the tail with erased flash and cover the image`() {
        val image = ByteArray(EspBootloader.BLOCK_SIZE + 10) { 0x42 }
        val blocks = EspBootloader.blocks(image)
        assertEquals(2, blocks.size)
        assertEquals(EspBootloader.BLOCK_SIZE, blocks[0].size)
        assertEquals(0x42.toByte(), blocks[1][9])
        assertEquals(0xFF.toByte(), blocks[1][10])
        assertEquals(0xFF.toByte(), blocks[1][EspBootloader.BLOCK_SIZE - 1])
        // 1034 bytes needs two 1 KiB blocks but the ROM erases whole 4 KiB sectors.
        assertEquals(4096, EspBootloader.eraseSize(image.size))
    }

    @Test
    fun `flash begin payload is five little endian words with the erase size first`() {
        val p = EspBootloader.flashBeginPayload(imageSize = 4096, blocks = 4)
        // Five words: the C3 and C5 ROMs take the extended form, and the fifth word is the
        // encrypted-write flag, zero here. A four-word payload is ignored by those ROMs.
        assertEquals(20, p.size)
        assertEquals(0x00, p[0].toInt())
        assertEquals(0x10, p[1].toInt())
        assertEquals(4, p[4].toInt())
        assertEquals(0x00, p[8].toInt())
        assertEquals(0x04, p[9].toInt())
    }

    @Test
    fun `flash begin erases the image's own span by default`() {
        val p = EspBootloader.flashBeginPayload(imageSize = 1_303_456, blocks = 1273)
        // 1 303 456 rounds up to 1 306 624: 319 whole 4 KiB sectors, not the 4 MiB chip.
        assertEquals(0x00, p[0].toInt() and 0xFF)
        assertEquals(0xF0, p[1].toInt() and 0xFF)
        assertEquals(0x13, p[2].toInt() and 0xFF)
        assertEquals(0x00, p[3].toInt() and 0xFF)
        assertEquals(1_306_624, EspBootloader.eraseSize(1_303_456))
    }

    @Test
    fun `flash end reboots with a zero word and stays with one`() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), EspBootloader.flashEndPayload(reboot = true))
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), EspBootloader.flashEndPayload(reboot = false))
    }

    @Test
    fun `flash data payload puts the block behind four words`() {
        val block = ByteArray(8) { 0x11 }
        val p = EspBootloader.flashDataPayload(8, 3, block)
        assertEquals(24, p.size)
        assertEquals(8, p[0].toInt())
        assertEquals(3, p[4].toInt())
        assertEquals(0x11.toByte(), p[16])
    }

    @Test
    fun `known chip magics are named and an unknown one is not`() {
        assertEquals("ESP32-C3", EspBootloader.chipName(0x1B31506FL))
        assertEquals("ESP32-S3", EspBootloader.chipName(0x00000009L))
        // C5 has no magic value of its own to check, so it must not be guessed at.
        assertNull(EspBootloader.chipName(0x2202A3AFL))
        assertNull(EspBootloader.chipName(0x12345678L))
    }
}
