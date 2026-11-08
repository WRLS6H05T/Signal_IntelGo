package dev.skyward.rf.meshcore

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The session end to end against a stand-in device on loopback.
 *
 * The device's firmware has answered `AUTH` both with a verdict and with nothing at all, and a
 * session that waited for a verdict it was never sent sat on the socket until it timed out - which
 * is exactly what "it never connects" looked like from the phone. These tests drive the real
 * [WadaTransfer] over a real socket so that case cannot come back.
 */
class WadaTransferSessionTest {

    private fun serverFrame(opcode: Int, text: String): ByteArray {
        val payload = text.toByteArray()
        require(payload.size < 126)
        return byteArrayOf((0x80 or opcode).toByte(), payload.size.toByte()) + payload
    }

    /** The client's frames are masked, so the device has to undo the mask to read a command. */
    private fun readClientText(input: InputStream): String {
        val first = input.read()
        if (first < 0) return ""
        val second = input.read()
        val masked = (second and 0x80) != 0
        var length = second and 0x7F
        if (length == 126) length = (input.read() shl 8) or input.read()
        val mask = ByteArray(4)
        if (masked) {
            var read = 0
            while (read < 4) {
                val n = input.read(mask, read, 4 - read)
                if (n < 0) return ""
                read += n
            }
        }
        val payload = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(payload, read, length - read)
            if (n < 0) return ""
            read += n
        }
        if (masked) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        return String(payload)
    }

    /**
     * A stand-in for the device: upgrades `/files`, reads the first command, then answers with the
     * given text frames. Returns the thread so the test can wait for the handshake it wants to
     * inspect.
     */
    private fun device(
        server: ServerSocket,
        request: AtomicReference<String>,
        command: AtomicReference<String>,
        replies: List<String>,
    ): Thread = thread(isDaemon = true) {
        server.accept().use { s ->
            s.soTimeout = 5_000
            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) return@use
                head.append(b.toChar())
            }
            request.set(head.toString())
            output.write(
                (
                    "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: stand-in\r\n\r\n"
                    ).toByteArray(),
            )
            output.flush()
            command.set(readClientText(input))
            replies.forEach { output.write(serverFrame(0x1, it)) }
            output.flush()
            // Hold the socket open long enough for the client to finish reading its list.
            Thread.sleep(400)
        }
    }

    @Test
    fun `a device that accepts the code without saying so still lists its files`() = runBlocking {
        val server = ServerSocket(0)
        val request = AtomicReference("")
        val command = AtomicReference("")
        // No "AUTH OK" at all; the list arrives as the only answer.
        val dev = device(server, request, command, listOf("ENTRY 12 /a.txt", "ENTRY 34 /b.bin", "LIST DONE"))
        try {
            val transfer = WadaTransfer()
            transfer.connect("127.0.0.1:${server.localPort}", "123456")

            assertEquals(WadaTransfer.Phase.CONNECTED, transfer.phase.value)
            assertEquals(listOf("/a.txt", "/b.bin"), transfer.files.value.map { it.path })
            assertEquals("AUTH 123456", command.get())
            assertTrue(request.get().startsWith("GET /files "))
            assertTrue(request.get().contains("Sec-WebSocket-Key:"))
            transfer.close()
        } finally {
            server.close()
            dev.join(5_000)
        }
    }

    @Test
    fun `a refused code is reported as a refusal, not as a timeout`() = runBlocking {
        val server = ServerSocket(0)
        val dev = device(server, AtomicReference(""), AtomicReference(""), listOf("AUTH FAIL"))
        try {
            val transfer = WadaTransfer()
            transfer.connect("127.0.0.1:${server.localPort}", "111111")

            assertEquals(WadaTransfer.Phase.ERROR, transfer.phase.value)
            assertTrue(transfer.status.value.contains("refused"))
        } finally {
            server.close()
            dev.join(5_000)
        }
    }
}
