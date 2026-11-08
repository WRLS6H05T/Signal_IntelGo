package dev.skyward.rf.meshcore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The transfer session's wire format: the browser page and the device agree on these exact
 * bytes, so the app only has to agree with them too.
 */
class WadaTransferWireTest {

    /** A frame as the device sends it: unmasked, unless a test says otherwise. */
    private fun serverFrame(opcode: Int, payload: ByteArray, mask: ByteArray? = null): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x80 or opcode)
        val masked = mask != null
        when {
            payload.size < 126 -> out.write((if (masked) 0x80 else 0) or payload.size)
            else -> {
                out.write((if (masked) 0x80 else 0) or 126)
                out.write(payload.size ushr 8)
                out.write(payload.size and 0xFF)
            }
        }
        if (mask != null) out.write(mask)
        for (i in payload.indices) {
            out.write(if (mask != null) payload[i].toInt() xor mask[i % 4].toInt() else payload[i].toInt())
        }
        return out.toByteArray()
    }

    @Test
    fun `an address is read the way the device shows it`() {
        assertEquals("192.168.1.42" to 80, WadaWire.parseAddress("192.168.1.42"))
        assertEquals("192.168.1.42" to 80, WadaWire.parseAddress(" http://192.168.1.42/ "))
        assertEquals("mesh.local" to 8080, WadaWire.parseAddress("mesh.local:8080"))
        // The page address the device prints, pasted whole: scheme, path and query go.
        assertEquals("192.168.1.42" to 80, WadaWire.parseAddress("http://192.168.1.42/files"))
        assertEquals("192.168.1.42" to 80, WadaWire.parseAddress(" 192.168.1.42/files/ "))
        assertEquals("192.168.1.42" to 8080, WadaWire.parseAddress("ws://192.168.1.42:8080/files?x=1"))
        assertNull(WadaWire.parseAddress(""))
        assertNull(WadaWire.parseAddress("http://"))
        assertNull(WadaWire.parseAddress("host:no-port"))
    }

    @Test
    fun `a session command goes out masked, as RFC 6455 requires of a client`() {
        val frame = WadaWire.textFrame("AUTH 123456")
        assertEquals(0x81, frame[0].toInt() and 0xFF)
        assertEquals(0x80 or 11, frame[1].toInt() and 0xFF)
        val mask = frame.copyOfRange(2, 6)
        val payload = frame.copyOfRange(6, 6 + 11)
        val plain = ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        assertEquals("AUTH 123456", String(plain))
    }

    @Test
    fun `a body longer than 125 bytes carries the 16-bit length`() {
        val text = "x".repeat(300)
        val frame = WadaWire.textFrame(text)
        assertEquals(126, frame[1].toInt() and 0x7F)
        assertEquals(300, ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF))
    }

    @Test
    fun `the file list the device sends parses into size and path`() {
        val entry = WadaWire.parseEntry("ENTRY 12034 /screenshots/capture.png")
        assertNotNull(entry)
        assertEquals(12034L, entry!!.size)
        assertEquals("/screenshots/capture.png", entry.path)
        assertNull(WadaWire.parseEntry("LIST DONE"))
        assertNull(WadaWire.parseEntry("ENTRY notanumber /x.png"))
    }

    @Test
    fun `download metadata names the file and its size`() {
        val meta = WadaWire.parseFile("FILE 4096 /meshcore-backup.json")
        assertEquals(4096L, meta!!.first)
        assertEquals("/meshcore-backup.json", meta.second)
        assertNull(WadaWire.parseFile("FILE 0 /empty.bin"))
    }

    @Test
    fun `a download chunk carries the offset the device wrote it at`() {
        val frame = byteArrayOf(0x01, 0x00, 0x08, 0x00, 0x00, 'a'.code.toByte(), 'b'.code.toByte())
        val chunk = WadaWire.parseChunk(frame)
        assertNotNull(chunk)
        assertEquals(2048L, chunk!!.first)
        assertArrayEquals(byteArrayOf('a'.code.toByte(), 'b'.code.toByte()), chunk.second)
        // A text reply is not a chunk, and neither is a frame with no payload at all.
        assertNull(WadaWire.parseChunk("READY".toByteArray()))
        assertNull(WadaWire.parseChunk(byteArrayOf(0x01, 0x00)))
    }

    @Test
    fun `a text frame is read as text`() {
        val input = ByteArrayInputStream(serverFrame(0x1, "AUTH OK".toByteArray()))
        val frame = WadaWire.readFrame(input, ByteArrayOutputStream())
        assertEquals("AUTH OK", (frame as WadaWire.Frame.Text).text)
    }

    @Test
    fun `a long frame is read across its extended length`() {
        val payload = ByteArray(400) { (it and 0xFF).toByte() }
        val input = ByteArrayInputStream(serverFrame(0x2, payload))
        val frame = WadaWire.readFrame(input, ByteArrayOutputStream())
        assertArrayEquals(payload, (frame as WadaWire.Frame.Binary).bytes)
    }

    @Test
    fun `a masked frame is unmasked`() {
        val payload = "ENTRY 1 /transfer/a.bin".toByteArray()
        val input = ByteArrayInputStream(serverFrame(0x1, payload, byteArrayOf(1, 2, 3, 4)))
        val frame = WadaWire.readFrame(input, ByteArrayOutputStream())
        assertEquals("ENTRY 1 /transfer/a.bin", (frame as WadaWire.Frame.Text).text)
    }

    @Test
    fun `a ping is answered so a quiet session is not dropped`() {
        val out = ByteArrayOutputStream()
        val input = ByteArrayInputStream(
            serverFrame(0x9, "hi".toByteArray()) + serverFrame(0x1, "LIST DONE".toByteArray()),
        )
        val frame = WadaWire.readFrame(input, out)
        assertEquals("LIST DONE", (frame as WadaWire.Frame.Text).text)
        val pong = out.toByteArray()
        assertEquals(0x8A, pong[0].toInt() and 0xFF)
    }

    @Test
    fun `a closed socket is a closed session, not an endless wait`() {
        val frame = WadaWire.readFrame(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
        assertEquals(WadaWire.Frame.Closed, frame)
        val close = WadaWire.readFrame(
            ByteArrayInputStream(serverFrame(0x8, ByteArray(0))),
            ByteArrayOutputStream(),
        )
        assertEquals(WadaWire.Frame.Closed, close)
    }

    @Test
    fun `the verdict on the code is read past its padding and its device name`() {
        assertTrue(WadaWire.isAuthOk("AUTH OK"))
        assertTrue(WadaWire.isAuthOk("auth ok"))
        assertTrue(WadaWire.isAuthOk("  AUTH OK  "))
        // Firmware that names itself after the verdict is still accepting the code.
        assertTrue(WadaWire.isAuthOk("AUTH OK WADAMESH"))
        assertFalse(WadaWire.isAuthOk("AUTH OKAY?"))
        assertFalse(WadaWire.isAuthOk("WADAMESH transfer ready"))
        assertFalse(WadaWire.isAuthOk(""))
    }

    @Test
    fun `a greeting is not a refusal, so the reader waits for the verdict`() {
        assertTrue(WadaWire.isRefused("AUTH FAIL"))
        assertTrue(WadaWire.isRefused("ERR bad code"))
        assertTrue(WadaWire.isRefused("denied"))
        assertFalse(WadaWire.isRefused("AUTH OK"))
        assertFalse(WadaWire.isRefused("WADAMESH transfer"))
        assertFalse(WadaWire.isRefused(""))
    }
}
