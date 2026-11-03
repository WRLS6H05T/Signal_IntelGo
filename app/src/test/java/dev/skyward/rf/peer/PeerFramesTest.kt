package dev.skyward.rf.peer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerFramesTest {

    private fun payload(size: Int) = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun `a message survives twenty byte chunks`() {
        val original = payload(1000)
        val frames = PeerFrames.split(original, 20, id = 7)
        assertEquals(50, frames.size)
        val assembler = PeerFrames.Assembler()
        var out: ByteArray? = null
        for (frame in frames) out = assembler.add(frame) ?: out
        assertArrayEquals(original, out)
    }

    @Test
    fun `a message survives a negotiated mtu`() {
        val original = payload(3000)
        val frames = PeerFrames.split(original, 244, id = 1)
        assertEquals(13, frames.size)
        val assembler = PeerFrames.Assembler()
        var out: ByteArray? = null
        for (frame in frames.reversed()) out = assembler.add(frame) ?: out
        assertArrayEquals(original, out)
    }

    @Test
    fun `a message that would need too many chunks is dropped whole`() {
        assertTrue(PeerFrames.split(payload(6000), 20, id = 2).isEmpty())
    }

    @Test
    fun `incomplete and malformed frames never produce a message`() {
        val assembler = PeerFrames.Assembler()
        val frames = PeerFrames.split(payload(100), 20, id = 3)
        assertNull(assembler.add(frames.first()))
        assertNull(assembler.add(ByteArray(0)))
        assertNull(assembler.add(byteArrayOf(1, 2, 3)))
        // A frame claiming to be out of range is ignored rather than indexed.
        assertNull(assembler.add(byteArrayOf(1, 9, 3, 0, 42)))
    }

    @Test
    fun `two messages interleaved do not mix`() {
        val a = payload(120)
        val b = payload(300)
        val assembler = PeerFrames.Assembler()
        val fa = PeerFrames.split(a, 20, id = 10)
        val fb = PeerFrames.split(b, 20, id = 11)
        val results = HashMap<Int, ByteArray>()
        for (i in 0 until maxOf(fa.size, fb.size)) {
            fa.getOrNull(i)?.let { f -> assembler.add(f)?.let { results[10] = it } }
            fb.getOrNull(i)?.let { f -> assembler.add(f)?.let { results[11] = it } }
        }
        assertArrayEquals(a, results[10])
        assertArrayEquals(b, results[11])
    }

    @Test
    fun `the header keeps the message id`() {
        val frame = PeerFrames.split(payload(10), 20, id = 200).first()
        assertEquals(200, PeerFrames.parse(frame)!!.id)
        assertEquals(1, PeerFrames.parse(frame)!!.total)
    }
}
