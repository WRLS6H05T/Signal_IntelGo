package dev.skyward.rf.peer

import dev.skyward.rf.core.SettingsStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The two-device link over Wi-Fi, instead of Bluetooth.
 *
 * The phone dials the desk and keeps the socket open. The desk's address is found the way the
 * link already finds a peer: broadcast `SKYWARD?` on UDP, and every desk answers `SKYWARD!<port>`.
 * Nothing has to be typed, and a desk on another network simply never answers.
 *
 * The frames are the same [PeerFrames] chunks the Bluetooth link sends, base64'd one per line.
 * That is what keeps this a second carrier rather than a second protocol: the message builders,
 * the assembler and every callback are the ones [BlePeer] already owns, so a snapshot is built
 * in exactly one place and both carriers send the same bytes.
 */
class PeerLan(
    private val peer: BlePeer,
    private val settings: SettingsStore,
) {

    data class State(
        val running: Boolean = false,
        val connected: Boolean = false,
        val host: String = "",
        val sent: Int = 0,
        val received: Int = 0,
        val detail: String = "off",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val id: String = java.util.UUID.randomUUID().toString().replace("-", "").take(6)

    private val name: String = runCatching { android.os.Build.MODEL }.getOrDefault("Android")

    private val frameId = AtomicInteger(0)

    private val assembler = PeerFrames.Assembler()

    private var ticker: java.util.concurrent.ScheduledExecutorService? = null

    @Volatile
    private var running = false

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var out: java.io.OutputStream? = null

    @Volatile
    private var lastSendAt = 0L

    @Volatile
    private var host = ""

    /** How often a sweep is offered. The Bluetooth link sends every five seconds. */
    private val sendMs = 5_000L

    fun start() {
        if (running) return
        if (settings.current.peerHost.isNotBlank()) host = settings.current.peerHost
        running = true
        _state.value = State(running = true, detail = "looking for a desk")
        ticker = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "lan-peer").apply { isDaemon = true }
        }
        ticker?.scheduleWithFixedDelay({ tick() }, 0L, 1_000L, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        running = false
        runCatching { ticker?.shutdownNow() }
        ticker = null
        closeSocket()
        assembler.reset()
        _state.value = State(detail = "off")
    }

    private fun tick() {
        if (!running) return
        val link = socket
        if (link == null || link.isClosed) {
            connect()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastSendAt < sendMs) return
        lastSendAt = now
        val message = buildMessage()
        val bytes = PeerProtocol.encode(message).toByteArray(Charsets.UTF_8)
        // The socket has no 20-byte MTU; the same chunk size keeps one reader on the desk.
        val frames = PeerFrames.split(bytes, 240, frameId.incrementAndGet() and 0xFF)
        if (frames.isEmpty()) return
        var sent = 0
        for (frame in frames) {
            if (!send(frame)) break
            sent++
        }
        if (sent > 0) {
            _state.value = _state.value.copy(sent = _state.value.sent + sent)
        }
    }

    /** Finds a desk by broadcast, then dials it. */
    private fun connect() {
        val discovered = if (host.isBlank()) discover() else host
        if (discovered.isBlank()) {
            _state.value = _state.value.copy(connected = false, detail = "no desk answered")
            return
        }
        val target = if (discovered.contains(':')) discovered else "$discovered:${DISCOVERY_PORT + 1}"
        val (address, portText) = target.substringBeforeLast(':') to target.substringAfterLast(':')
        val port = portText.toIntOrNull() ?: DEFAULT_PORT
        runCatching {
            val s = Socket()
            s.connect(InetSocketAddress(InetAddress.getByName(address), port), 3_000)
            s.tcpNoDelay = true
            socket = s
            out = s.getOutputStream()
            host = target
            _state.value = _state.value.copy(connected = true, host = address, detail = "linked to $address:$port")
            Thread({ readLoop(s) }, "lan-peer-read").apply { isDaemon = true }.start()
        }.onFailure { error ->
            closeSocket()
            _state.value = _state.value.copy(connected = false, detail = "cannot reach $address: ${error.message}")
        }
    }

    private fun discover(): String {
        val reply = runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 1_200
                val probe = PeerProtocol.PROBE.toByteArray()
                socket.send(DatagramPacket(probe, probe.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
                val buffer = ByteArray(64)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                if (!text.startsWith(PeerProtocol.HERE)) return@use ""
                val port = text.removePrefix(PeerProtocol.HERE).trim()
                "${packet.address.hostAddress}:${port.ifBlank { DEFAULT_PORT.toString() }}"
            }
        }.getOrElse { "" }
        return reply
    }

    private fun readLoop(link: Socket) {
        runCatching {
            BufferedReader(InputStreamReader(link.getInputStream(), Charsets.UTF_8)).useLines { lines ->
                for (line in lines) {
                    if (!running) break
                    val bytes = runCatching { Base64.getDecoder().decode(line.trim()) }.getOrNull() ?: continue
                    val message = assembler.add(bytes) ?: continue
                    val decoded = PeerProtocol.decode(String(message, Charsets.UTF_8)) ?: continue
                    received(decoded)
                }
            }
        }
        closeSocket()
        _state.value = _state.value.copy(connected = false, detail = "desk disconnected")
    }

    /** Applies one message through the callbacks the Bluetooth link already uses. */
    private fun received(message: PeerProtocol.Message) {
        val lat = message.lat
        val lon = message.lon
        val fix = if (lat != null && lon != null) dev.skyward.rf.core.LatLon(lat, lon) else null
        peer.onRemote?.invoke(message.wifi, message.ble, fix)
        if (message.boardWifi.isNotEmpty() || message.boardBle.isNotEmpty()) {
            peer.onPeerBoard?.invoke(message.boardWifi, message.boardBle)
        }
        val hostSettings = message.hostSettings
        if (hostSettings != null) peer.onHostSettings?.invoke(hostSettings)
        if (message.recording || message.recordName.isNotBlank()) {
            peer.onPeerRecording?.invoke(message.recording, message.recordName)
        }
        if (message.voice.isNotBlank()) peer.onPeerVoice?.invoke(message.voice)
        if (message.logs.isNotEmpty()) peer.onPeerLogs?.invoke(message.logs)
        _state.value = _state.value.copy(received = _state.value.received + 1)
    }

    private fun buildMessage(): PeerProtocol.Message {
        val snapshot = peer.snapshotProvider?.invoke()
        val recording = peer.recordingProvider?.invoke() ?: (false to "")
        return PeerProtocol.Message(
            kind = PeerProtocol.KIND_SKY,
            id = id,
            name = name,
            diagIn = peer.diagonalInches(),
            lat = snapshot?.fix?.lat,
            lon = snapshot?.fix?.lon,
            sats = snapshot?.sats ?: 0,
            at = System.currentTimeMillis(),
            wifiTotal = snapshot?.wifiTotal ?: 0,
            bleTotal = snapshot?.bleTotal ?: 0,
            gpsUsed = snapshot?.gpsUsed ?: false,
            cellUsed = snapshot?.cellUsed ?: false,
            wifi = snapshot?.wifi.orEmpty(),
            ble = snapshot?.ble.orEmpty(),
            boardWifi = snapshot?.boardWifi.orEmpty(),
            boardBle = snapshot?.boardBle.orEmpty(),
            boardWifiTotal = snapshot?.boardWifiTotal ?: 0,
            boardBleTotal = snapshot?.boardBleTotal ?: 0,
            mode = peer.modeProvider?.invoke().orEmpty(),
            scan = peer.scanProvider?.invoke().orEmpty(),
            recording = recording.first,
            recordName = recording.second,
            logs = peer.logProvider?.invoke().orEmpty(),
        )
    }

    private fun send(frame: ByteArray): Boolean {
        val stream = out ?: return false
        return runCatching {
            stream.write((Base64.getEncoder().encodeToString(frame) + "\n").toByteArray(Charsets.UTF_8))
            stream.flush()
            true
        }.getOrDefault(false)
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
        out = null
        host = ""
    }

    private companion object {
        /** The desk's TCP port: the discovery port plus one, kept in step with LinkBridge. */
        const val DEFAULT_PORT = 47800
        const val DISCOVERY_PORT = 47801
    }
}
