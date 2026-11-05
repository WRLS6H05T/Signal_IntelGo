package dev.skyward.rf.desktop.link

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The link over the network: a socket, not a radio.
 *
 * The desktop listens, phones dial in. That direction is deliberate - the desk is the device that
 * stays put on a desk and can be given a fixed address, while the phone is the one that moves,
 * changes networks, and comes back.
 *
 * A phone also has to find the desk without being told an IP. It broadcasts the probe the phone's
 * own link already defines (`SKYWARD?`) on UDP, and every desk answers `SKYWARD!<port>`; the
 * phone then dials whoever answered. Nothing has to be typed on either end, and a desk on a
 * different subnet simply never answers.
 *
 * Wi-Fi and USB tethering are the same carrier from here: a tethered phone is just a client whose
 * source address happens to be the cable's network.
 */
class LanBridge(
    private val port: Int = LinkBridge.PORT,
    private val discoveryPort: Int = LinkBridge.DISCOVERY_PORT,
) : LinkBridge {

    private val _events = MutableSharedFlow<LinkEvent>(replay = 8, extraBufferCapacity = 512)
    override val events: SharedFlow<LinkEvent> = _events

    private val sessions = ConcurrentHashMap<String, Session>()

    @Volatile
    private var running = false

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var discovery: DatagramSocket? = null

    @Volatile
    override var detail: String = "not listening"
        private set

    override fun start(): Boolean {
        if (running) return true
        return runCatching {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            server = socket
            running = true
            detail = "listening on tcp $port"
            emit(LinkEvent.Ready(detail))
            startDiscovery()
            Thread({ acceptLoop(socket) }, "lan-accept").apply { isDaemon = true }.start()
            true
        }.getOrElse { error ->
            detail = "cannot listen on tcp $port: ${error.message}"
            emit(LinkEvent.Failed(detail))
            false
        }
    }

    override fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        runCatching { discovery?.close() }
        discovery = null
        sessions.values.forEach { it.close() }
        sessions.clear()
        detail = "not listening"
    }

    override fun scan(on: Boolean) {
        // Nothing to scan: the desk is the one being connected to.
    }

    override fun connect(address: String): Boolean {
        // A socket that reached us is already connected; say so again so the host stops dialling.
        val session = sessions[address] ?: return false
        emit(LinkEvent.Connected(address, session.mtu))
        return true
    }

    override fun write(address: String, characteristic: String, data: ByteArray): Boolean =
        sessions[address]?.write(data) ?: false

    // ---------------- plumbing ----------------

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            val host = client.inetAddress?.hostAddress ?: "phone"
            val address = "lan:$host:${client.port}"
            val session = Session(address, client)
            sessions[address] = session
            emit(LinkEvent.Advert(
                address = address,
                name = host,
                rssi = 0,
                serviceUuids = listOf(LinkBridge.PEER_SERVICE.toString()),
                manufacturer = emptyMap(),
                serviceData = mapOf(LinkBridge.PEER_SERVICE.toString() to "lan".toByteArray()),
                seenAt = System.currentTimeMillis(),
            ))
            emit(LinkEvent.Connected(address, session.mtu))
            emit(LinkEvent.Note("linked $host over the network"))
            Thread({ readLoop(session) }, "lan-read").apply { isDaemon = true }.start()
        }
    }

    private fun readLoop(session: Session) {
        val reader = runCatching {
            BufferedReader(InputStreamReader(session.input, Charsets.UTF_8))
        }.getOrNull() ?: return
        try {
            while (running) {
                val line = reader.readLine() ?: break
                val bytes = runCatching { Base64.getDecoder().decode(line.trim()) }.getOrNull() ?: continue
                if (bytes.isEmpty()) continue
                emit(LinkEvent.Notified(session.address, LinkBridge.PEER_SERVICE.toString(), bytes))
            }
        } catch (_: Throwable) {
            // A phone that walks out of Wi-Fi does this; the host just unlinks it.
        } finally {
            sessions.remove(session.address)
            session.close()
            emit(LinkEvent.Disconnected(session.address))
            emit(LinkEvent.Note("unlinked ${session.address}"))
        }
    }

    private fun startDiscovery() {
        runCatching {
            val socket = DatagramSocket(null)
            socket.reuseAddress = true
            socket.broadcast = true
            socket.bind(InetSocketAddress(discoveryPort))
            discovery = socket
            Thread({
                val buffer = ByteArray(64)
                while (running) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    val read = runCatching { socket.receive(packet) }.getOrNull() ?: continue
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                    if (text == LinkBridge.PROBE) {
                        val reply = "${LinkBridge.HERE}$port".toByteArray()
                        runCatching {
                            socket.send(DatagramPacket(reply, reply.size, InetAddress.getByName(packet.address.hostAddress), packet.port))
                        }
                    }
                }
            }, "lan-discovery").apply { isDaemon = true }.start()
        }
    }

    private fun emit(event: LinkEvent) {
        _events.tryEmit(event)
    }

    private class Session(val address: String, private val socket: Socket) {

        /** The same budget the host assumes for a Bluetooth link, so chunking is unchanged. */
        val mtu: Int = 185

        val input: InputStream get() = socket.getInputStream()

        private val output: OutputStream = socket.getOutputStream()

        private val lock = Any()

        fun write(bytes: ByteArray): Boolean = synchronized(lock) {
            runCatching {
                output.write((Base64.getEncoder().encodeToString(bytes) + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
                true
            }.getOrDefault(false)
        }

        fun close() {
            runCatching { socket.close() }
        }
    }
}
