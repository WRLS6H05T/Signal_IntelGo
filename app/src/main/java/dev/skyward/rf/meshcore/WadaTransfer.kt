package dev.skyward.rf.meshcore

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64

/**
 * The WADAMESH "Transfer" session, driven from the phone instead of a browser.
 *
 * WADAMESH shows an address and a six-digit session code, and serves the file page on
 * `http://<device>/files`. The page is only a shell around a WebSocket on the same path: the
 * first frame is `AUTH <code>`, then plain-text commands (`LIST`, `GET <path>`, `READ <offset>`)
 * and chunked binary frames carry the files. Nothing is encrypted - it is a session on the
 * user's own LAN - but the code is what keeps another browser on that network out, so it is
 * never written to settings and dies with the session.
 *
 * The device allows one file client at a time and answers 503 when a browser already has it.
 */
class WadaTransfer {

    /** One file the device offers: a screenshot, a crash report, a backup, an upload. */
    data class Entry(val size: Long, val path: String)

    data class Downloaded(val name: String, val bytes: ByteArray)

    enum class Phase { IDLE, CONNECTING, CONNECTED, ERROR }

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase

    private val _status = MutableStateFlow("Open Transfer on the device and type its address and code.")
    val status: StateFlow<String> = _status

    private val _files = MutableStateFlow<List<Entry>>(emptyList())
    val files: StateFlow<List<Entry>> = _files

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var input: InputStream? = null

    @Volatile
    private var output: OutputStream? = null

    suspend fun connect(address: String, pin: String) = withContext(Dispatchers.IO) {
        val target = WadaWire.parseAddress(address)
        if (target == null) {
            fail("Type the address the device shows, e.g. 192.168.1.42")
            return@withContext
        }
        if (!pin.matches(PIN)) {
            fail("The session code is the six digits on the device")
            return@withContext
        }
        close()
        _phase.value = Phase.CONNECTING
        // The port is named because it is the part that is easy to get wrong: the device serves
        // its file session on its own web port, not on 80, and a wrong port only ever looks like
        // a device that has gone quiet.
        _status.value = "Connecting to ${target.first}:${target.second}…"
        runCatching {
            val s = Socket()
            s.connect(InetSocketAddress(target.first, target.second), 6_000)
            s.soTimeout = 20_000
            s.tcpNoDelay = true
            val streams = WadaWire.openFilesSession(s)
            socket = s
            input = streams.first
            output = streams.second
            sendText("AUTH $pin")
            // The verdict is not necessarily the first frame. A device that greets the client
            // before it judges the code is still accepting, and taking the greeting for the
            // answer is what reported "the session was refused" on a session that worked.
            var verdict: String? = null
            for (attempt in 0 until AUTH_FRAMES) {
                when (val reply = WadaWire.readFrame(requireInput(), requireOutput())) {
                    is WadaWire.Frame.Text -> {
                        val text = reply.text.trim()
                        verdict = text
                        if (WadaWire.isAuthOk(text) || WadaWire.isRefused(text)) break
                    }
                    is WadaWire.Frame.Binary -> Unit
                    WadaWire.Frame.Closed -> {
                        verdict = null
                        break
                    }
                }
            }
            if (verdict == null || !WadaWire.isAuthOk(verdict)) {
                // The device's own words: "refused" alone does not say whether the code was
                // wrong, the page is already open somewhere else, or the firmware answered
                // something this app does not know.
                throw IOException(
                    when {
                        verdict == null -> "the device closed the session before accepting the code"
                        WadaWire.isRefused(verdict) ->
                            "the code was refused ($verdict) - it changes every time Transfer is reopened"
                        else -> "the device answered \"$verdict\" instead of accepting the code"
                    },
                )
            }
            _phase.value = Phase.CONNECTED
            _status.value = "Connected to the device."
            listLocked()
        }.onFailure { e ->
            close()
            fail(e.message ?: "could not reach the device")
        }
    }

    /** Re-reads the device's file list: the session is live, the files on it are not static. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (_phase.value != Phase.CONNECTED) return@withContext
        runCatching { listLocked() }.onFailure { e ->
            close()
            fail(e.message ?: "the device stopped answering")
        }
    }

    /** Pulls one file whole. The device sends it in 2 KiB chunks, each acked by the next READ. */
    suspend fun download(path: String): Downloaded? = withContext(Dispatchers.IO) {
        if (_phase.value != Phase.CONNECTED) {
            fail("Connect to the device first")
            return@withContext null
        }
        runCatching {
            val out = requireOutput()
            val input = requireInput()
            sendText("GET $path")
            var size = -1L
            var name = path
            while (size < 0) {
                val frame = WadaWire.readFrame(input, out)
                if (frame is WadaWire.Frame.Text && frame.text.startsWith("ERR ")) {
                    throw IOException(frame.text.removePrefix("ERR "))
                }
                if (frame !is WadaWire.Frame.Text) continue
                val meta = WadaWire.parseFile(frame.text) ?: continue
                size = meta.first
                name = meta.second
            }
            if (size > MAX_DOWNLOAD) throw IOException("larger than the 64 MB transfer limit")
            val bytes = ByteArrayOutputStream(size.toInt().coerceAtLeast(64))
            var offset = 0L
            _status.value = "Downloading ${name.substringAfterLast('/')}…"
            sendText("READ 0")
            while (offset < size) {
                when (val frame = WadaWire.readFrame(input, out)) {
                    is WadaWire.Frame.Text ->
                        if (frame.text.startsWith("ERR ")) throw IOException(frame.text.removePrefix("ERR "))
                    is WadaWire.Frame.Binary -> {
                        val chunk = WadaWire.parseChunk(frame.bytes)
                            ?: throw IOException("the device sent a download frame this app cannot read")
                        // The offset is the device's own accounting: a mismatch means bytes were
                        // lost, and a file assembled past the gap would be quietly wrong.
                        if (chunk.first != offset) throw IOException("the download lost its place")
                        bytes.write(chunk.second)
                        offset += chunk.second.size
                        if (offset < size) sendText("READ $offset")
                    }
                    WadaWire.Frame.Closed -> throw IOException("the device closed the session")
                }
            }
            val leaf = name.substringAfterLast('/')
            _status.value = "Got $leaf (${size} bytes)"
            Downloaded(leaf, bytes.toByteArray())
        }.onFailure { e ->
            _status.value = e.message ?: "download failed"
            null
        }.getOrNull()
    }

    /** Ends the session; the device keeps serving the files it already has. */
    fun close() {
        runCatching {
            val out = output
            if (out != null) {
                out.write(WadaWire.closeFrame())
                out.flush()
            }
        }
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
        _files.value = emptyList()
        _phase.value = Phase.IDLE
    }

    private fun listLocked() {
        val out = requireOutput()
        val input = requireInput()
        sendText("LIST")
        val found = ArrayList<Entry>()
        while (true) {
            when (val frame = WadaWire.readFrame(input, out)) {
                is WadaWire.Frame.Text -> {
                    val text = frame.text
                    if (text == "LIST DONE") {
                        _files.value = found
                        _status.value =
                            if (found.isEmpty()) "No files on the device yet."
                            else "${found.size} files on the device."
                        return
                    }
                    if (text.startsWith("ERR ")) throw IOException(text.removePrefix("ERR "))
                    val entry = WadaWire.parseEntry(text) ?: continue
                    found += entry
                    // The device hands out one entry per request: its own page answers every
                    // ENTRY with LIST NEXT. Without that the list stopped at the first file and
                    // the session sat there until the socket gave up.
                    sendText("LIST NEXT")
                }
                is WadaWire.Frame.Binary -> Unit
                WadaWire.Frame.Closed -> throw IOException("the device closed the session")
            }
        }
    }

    private fun sendText(text: String) {
        val out = requireOutput()
        out.write(WadaWire.textFrame(text))
        out.flush()
    }

    private fun requireInput(): InputStream = input ?: throw IOException("not connected")

    private fun requireOutput(): OutputStream = output ?: throw IOException("not connected")

    private fun fail(message: String) {
        _phase.value = Phase.ERROR
        _status.value = message
    }

    private companion object {
        val PIN = Regex("\\d{6}")

        /** How many frames to read before deciding the device is never going to answer. */
        const val AUTH_FRAMES = 4

        /** The browser page caps a download at 64 MB; a phone assembling more would be a memory bug. */
        const val MAX_DOWNLOAD = 64L * 1024 * 1024
    }
}

/**
 * The wire side of the file session: RFC 6455 frames and the device's plain-text replies.
 *
 * Kept apart from the session so the frame and reply handling can be tested without a device.
 */
internal object WadaWire {

    sealed interface Frame {
        data class Text(val text: String) : Frame
        data class Binary(val bytes: ByteArray) : Frame
        object Closed : Frame
    }

    /** No file on the device is bigger than this in one frame. */
    private const val MAX_FRAME = 3 * 1024 * 1024

    /** Where the device's web server listens, and so where /files is served. */
    const val DEFAULT_PORT = 8765

    /**
     * "192.168.1.42", "192.168.1.42:8080" and the whole page address the device prints
     * ("http://192.168.1.42:8765/files") all mean the same thing.
     *
     * The device shows its transfer page as a URL, so the field is routinely filled with the
     * scheme, the path and a trailing slash. Only the authority reaches the socket: rejecting
     * the rest made a correct address look like a missing one.
     *
     * A bare address means [DEFAULT_PORT], which is the device's web port - the one its file
     * session is actually on. Defaulting to 80 dialled a port nothing listens on and made a
     * reachable device look dead.
     */
    fun parseAddress(raw: String): Pair<String, Int>? {
        var text = raw.trim()
        val scheme = text.indexOf("://")
        if (scheme >= 0) text = text.substring(scheme + 3)
        text = text.substringBefore('/').substringBefore('?').substringBefore('#').trimEnd('/')
        if (text.isEmpty()) return null
        val host: String
        val portText = if (text.startsWith("[")) {
            // A bracketed IPv6 literal keeps its own colons; only what follows `]` is the port.
            val close = text.indexOf(']')
            if (close < 0) return null
            host = text.substring(1, close)
            text.substring(close + 1).takeIf { it.isNotEmpty() }?.removePrefix(":")
        } else {
            host = text.substringBefore(':')
            if (text.contains(':')) text.substringAfter(':') else null
        }
        val port = if (portText == null) DEFAULT_PORT else portText.toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return host to port
    }

    /**
     * The device's verdict on the session code.
     *
     * `AUTH OK` is the documented reply, but the comparison used to be exact: firmware that
     * answers the verdict followed by its own name, or with different case or padding, was
     * reported as a refusal on a session that was in fact accepted.
     */
    fun isAuthOk(text: String): Boolean {
        val t = text.trim().uppercase()
        return t == "AUTH OK" || t.startsWith("AUTH OK ")
    }

    /**
     * An explicit rejection, so the reader stops on it instead of waiting out its frame budget.
     * A greeting is neither: the verdict may still be in the next frame.
     */
    fun isRefused(text: String): Boolean {
        val t = text.trim().uppercase()
        return t == "ERR" || t.startsWith("ERR ") || t.startsWith("AUTH FAIL") ||
            t.startsWith("AUTH BAD") || t.startsWith("AUTH ERR") || t.startsWith("DENIED")
    }

    fun parseEntry(line: String): WadaTransfer.Entry? {
        val parts = line.split(' ', limit = 3)
        if (parts.size < 3 || parts[0] != "ENTRY") return null
        val size = parts[1].toLongOrNull() ?: return null
        return WadaTransfer.Entry(size, parts[2])
    }

    fun parseFile(line: String): Pair<Long, String>? {
        val parts = line.split(' ', limit = 3)
        if (parts.size < 3 || parts[0] != "FILE") return null
        val size = parts[1].toLongOrNull() ?: return null
        if (size <= 0) return null
        return size to parts[2]
    }

    /** A download frame is `0x01`, the 32-bit little-endian offset, then the bytes. */
    fun parseChunk(bytes: ByteArray): Pair<Long, ByteArray>? {
        if (bytes.size < 6 || bytes[0].toInt() != 0x01) return null
        val offset = (bytes[1].toLong() and 0xFF) or
            ((bytes[2].toLong() and 0xFF) shl 8) or
            ((bytes[3].toLong() and 0xFF) shl 16) or
            ((bytes[4].toLong() and 0xFF) shl 24)
        return offset to bytes.copyOfRange(5, bytes.size)
    }

    fun textFrame(text: String): ByteArray = clientFrame(0x1, text.toByteArray(Charsets.UTF_8))

    fun closeFrame(): ByteArray = clientFrame(0x8, ByteArray(0))

    fun clientFrame(opcode: Int, payload: ByteArray, mask: ByteArray = newMask()): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 14)
        out.write(0x80 or opcode)
        when {
            payload.size < 126 -> out.write(0x80 or payload.size)
            payload.size < 65536 -> {
                out.write(0x80 or 126)
                out.write(payload.size ushr 8)
                out.write(payload.size and 0xFF)
            }
            else -> {
                out.write(0x80 or 127)
                repeat(8) { i -> out.write(((payload.size.toLong() shr (8 * (7 - i))) and 0xFF).toInt()) }
            }
        }
        out.write(mask)
        for (i in payload.indices) out.write(payload[i].toInt() xor mask[i % 4].toInt())
        return out.toByteArray()
    }

    /**
     * Reads one whole frame. The device never fragments what it sends, so a partial frame is a
     * broken stream rather than something to reassemble; ping is answered and pong is ignored.
     */
    fun readFrame(input: InputStream, output: OutputStream): Frame {
        while (true) {
            val first = input.read()
            if (first < 0) return Frame.Closed
            val second = input.read()
            if (second < 0) return Frame.Closed
            val opcode = first and 0x0F
            val masked = (second and 0x80) != 0
            var length = (second and 0x7F).toLong()
            if (length == 126L) {
                length = (readByte(input).toLong() shl 8) or readByte(input).toLong()
            } else if (length == 127L) {
                length = 0
                repeat(8) { length = (length shl 8) or readByte(input).toLong() }
            }
            if (length < 0 || length > MAX_FRAME) throw IOException("a frame of $length bytes")
            val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null
            val payload = ByteArray(length.toInt())
            readFully(input, payload)
            if (mask != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                }
            }
            when (opcode) {
                0x1 -> return Frame.Text(String(payload, Charsets.UTF_8))
                0x2 -> return Frame.Binary(payload)
                0x8 -> return Frame.Closed
                0x9 -> {
                    output.write(clientFrame(0xA, payload))
                    output.flush()
                }
                else -> Unit
            }
        }
    }

    /**
     * Opens `GET /files` and upgrades it. A 503 is the device's way of saying a browser already
     * holds the single file session, so it gets a message that names the cause.
     */
    fun openFilesSession(socket: Socket): Pair<InputStream, OutputStream> {
        val out = BufferedOutputStream(socket.getOutputStream())
        val key = newMask() + newMask() + newMask() + newMask()
        val request = buildString {
            append("GET /files HTTP/1.1\r\n")
            append("Host: ").append(socket.inetAddress?.hostAddress ?: "device")
            if (socket.port != 80) append(':').append(socket.port)
            append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(Base64.getEncoder().encodeToString(key)).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n\r\n")
        }
        out.write(request.toByteArray(Charsets.US_ASCII))
        out.flush()
        val input = BufferedInputStream(socket.getInputStream())
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) throw IOException("the device closed the connection")
            header.append(b.toChar())
            if (header.length > 8192) throw IOException("the device sent no handshake")
        }
        val line = header.lineSequence().firstOrNull() ?: ""
        if (!line.contains("101")) {
            throw IOException(
                if (line.contains("503")) {
                    // The firmware answers 503 both when Transfer is closed on the device and
                    // when another page already holds its one session; saying which is likely
                    // is more use than repeating the status line.
                    "Transfer is not open on the device, or a browser already has the session"
                } else {
                    "the device refused the session ($line)"
                },
            )
        }
        return input to out
    }

    private fun newMask(): ByteArray = ByteArray(4).also { SecureRandom().nextBytes(it) }

    private fun readByte(input: InputStream): Int {
        val b = input.read()
        if (b < 0) throw IOException("the device closed the session")
        return b
    }

    private fun readFully(input: InputStream, dst: ByteArray) {
        var read = 0
        while (read < dst.size) {
            val n = input.read(dst, read, dst.size - read)
            if (n < 0) throw IOException("the device closed the session")
            read += n
        }
    }
}
