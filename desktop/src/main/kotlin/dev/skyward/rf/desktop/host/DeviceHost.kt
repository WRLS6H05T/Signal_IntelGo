package dev.skyward.rf.desktop.host

import dev.skyward.rf.core.LatLon
import dev.skyward.rf.peer.PeerFrames
import dev.skyward.rf.peer.PeerProtocol
import dev.skyward.rf.scan.Esp32Codec
import dev.skyward.rf.desktop.link.LinkBridge
import dev.skyward.rf.desktop.link.LinkEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The desktop as the host of every device in radio range.
 *
 * It is the same link the two-phone feature already uses, with the desktop in the seat the phone
 * called HOST: it scans for phones advertising [PEER_SERVICE], connects to them as the GATT
 * central, subscribes to `UP`, writes `DOWN`, and merges what they hear into one table. Sensor
 * boards need no connection at all - their telemetry is in the advertisement - so a board is
 * picked up by the same scanner that finds the phones.
 *
 * The protocol, the chunking and the board codec are compiled from `:shared`, which is the same
 * source the phone builds. There is one wire format in this project, not two.
 */
class DeviceHost(
    private val links: List<LinkBridge>,
    private val scope: CoroutineScope,
    private val settings: StateFlow<HostSettings>,
) {

    companion object {
        val PEER_SERVICE: UUID = UUID.fromString("7a1f5e10-3c6b-4a2e-9f1d-5b8c2d4e6f70")
        val UP: String = "7a1f5e11-3c6b-4a2e-9f1d-5b8c2d4e6f70"
        val DOWN: String = "7a1f5e12-3c6b-4a2e-9f1d-5b8c2d4e6f70"

        /** Observations older than this stop counting, the same window the phone uses. */
        const val STALE_MS = 15_000L

        /** One snapshot every five seconds, exactly as a host phone sends. */
        private const val SEND_MS = 5_000L

        /** Windows BLE allows one outstanding write; this is the gap between frames. */
        private const val FRAME_GAP_MS = 45L

        /** Do not hammer a phone that keeps refusing a connection. */
        private const val RECONNECT_MS = 12_000L

        const val ID = "desktop"
        const val NAME = "intelgo-desktop"
    }

    enum class Kind { PEER, BOARD }

    data class Hosted(
        val address: String,
        val kind: Kind,
        val name: String,
        val rssi: Int,
        val lastSeen: Long,
        val connected: Boolean = false,
        val mtu: Int = 0,
        val fix: LatLon? = null,
        val sats: Int = 0,
        val wifi: Int = 0,
        val ble: Int = 0,
        val battery: Int = -1,
        val hdop: Double? = null,
        val moving: Boolean = false,
        val recording: Boolean = false,
        val recordName: String = "",
        val scan: String = "",
        val firmware: String = "",
        val frames: Int = 0,
    )

    data class Row(val id: String, val name: String, val rssi: Int, val via: String, val at: Long)

    data class State(
        val adapterOk: Boolean = false,
        val adapterDetail: String = "starting the BLE bridge",
        val devices: List<Hosted> = emptyList(),
        val wifi: List<Row> = emptyList(),
        val ble: List<Row> = emptyList(),
        val framesIn: Int = 0,
        val framesOut: Int = 0,
        val log: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val devices = ConcurrentHashMap<String, Hosted>()

    /** Merged observations, keyed by BSSID/address; one row per network. */
    private val wifiTable = ConcurrentHashMap<String, Row>()
    private val bleTable = ConcurrentHashMap<String, Row>()

    private val assemblers = ConcurrentHashMap<String, PeerFrames.Assembler>()

    private val connecting = ConcurrentHashMap<String, Long>()

    /** Which carrier owns each address, so a reply goes back out the way it came in. */
    private val owners = ConcurrentHashMap<String, LinkBridge>()

    private val outCount = AtomicInteger(0)

    private val notes = ArrayDeque<String>()

    fun start() {
        for (link in links) {
            scope.launch { link.events.collect { event -> handle(event, link) } }
            link.start()
            link.scan(true)
        }
        scope.launch {
            while (true) {
                delay(SEND_MS)
                pushSnapshots()
                prune()
            }
        }
    }

    fun stop() {
        for (link in links) {
            link.scan(false)
            link.stop()
        }
    }

    private fun handle(event: LinkEvent, link: LinkBridge) {
        // Remember which carrier an address belongs to before acting on it: a phone on Wi-Fi and
        // a phone on a cable both addressed here, and their replies must not cross.
        val address = when (event) {
            is LinkEvent.Advert -> event.address
            is LinkEvent.Connected -> event.address
            is LinkEvent.Notified -> event.address
            else -> null
        }
        if (address != null) owners[address] = link

        when (event) {
            is LinkEvent.Ready -> {
                _state.value = _state.value.copy(adapterOk = true, adapterDetail = event.detail)
                note("link ready: ${event.detail}")
            }

            is LinkEvent.Failed -> {
                _state.value = _state.value.copy(adapterOk = false, adapterDetail = event.message)
                note(event.message)
            }

            is LinkEvent.Note -> note(event.message)

            is LinkEvent.Advert -> onAdvert(event, link)

            is LinkEvent.Connected -> {
                assemblers[event.address] = PeerFrames.Assembler()
                devices[event.address] = (devices[event.address] ?: return).copy(
                    connected = true,
                    mtu = event.mtu,
                    lastSeen = System.currentTimeMillis(),
                )
                note("linked ${event.address} (MTU ${event.mtu})")
                publish()
            }

            is LinkEvent.Disconnected -> {
                assemblers.remove(event.address)
                connecting.remove(event.address)
                devices[event.address]?.let { devices[event.address] = it.copy(connected = false, mtu = 0) }
                note("unlinked ${event.address}")
                publish()
            }

            is LinkEvent.Notified -> onFrames(event)
        }
    }

    private fun onAdvert(advert: LinkEvent.Advert, link: LinkBridge) {
        owners[advert.address] = link
        val board = HostCodec.boardAdvert(advert)
        if (board != null) {
            val existing = devices[advert.address]
            devices[advert.address] = Hosted(
                address = advert.address,
                kind = Kind.BOARD,
                name = advert.name.ifBlank { existing?.name ?: "ESP32 sensor" },
                rssi = advert.rssi,
                lastSeen = advert.seenAt,
                fix = if (board.fix) LatLon(board.lat ?: 0.0, board.lon ?: 0.0) else null,
                sats = board.sats,
                wifi = board.wifiCount,
                ble = board.bleCount,
                battery = board.batteryPct,
                hdop = board.hdop,
                moving = board.moving,
                firmware = "v${board.version}",
                frames = existing?.frames ?: 0,
            )
            publish()
            return
        }
        val peerId = HostCodec.peerId(advert) ?: return
        val existing = devices[advert.address]
        devices[advert.address] = Hosted(
            address = advert.address,
            kind = Kind.PEER,
            name = advert.name.ifBlank { existing?.name ?: "phone $peerId" },
            rssi = advert.rssi,
            lastSeen = advert.seenAt,
            connected = existing?.connected ?: false,
            mtu = existing?.mtu ?: 0,
            fix = existing?.fix,
            sats = existing?.sats ?: 0,
            wifi = existing?.wifi ?: 0,
            ble = existing?.ble ?: 0,
            recording = existing?.recording ?: false,
            recordName = existing?.recordName.orEmpty(),
            scan = existing?.scan.orEmpty(),
            frames = existing?.frames ?: 0,
        )
        // The desktop is always the central: a phone advertises and accepts, and does not dial.
        val now = System.currentTimeMillis()
        val lastTry = connecting[advert.address] ?: 0L
        if (existing?.connected != true && now - lastTry > RECONNECT_MS) {
            connecting[advert.address] = now
            owners[advert.address]?.connect(advert.address)
            note("dialling $peerId at ${advert.address}")
        }
        publish()
    }

    private fun onFrames(event: LinkEvent.Notified) {
        val assembler = assemblers.getOrPut(event.address) { PeerFrames.Assembler() }
        val message = assembler.add(event.data) ?: return
        val decoded = PeerProtocol.decode(String(message, Charsets.UTF_8)) ?: return
        val host = devices[event.address] ?: return
        // Held in locals because the message type now lives in another module, where the
        // compiler will not carry a smart cast across the boundary.
        val fixLat = decoded.lat
        val fixLon = decoded.lon
        val fix = if (fixLat != null && fixLon != null) LatLon(fixLat, fixLon) else host.fix
        devices[event.address] = host.copy(
            name = decoded.name.ifBlank { host.name },
            lastSeen = System.currentTimeMillis(),
            fix = fix,
            sats = decoded.sats,
            wifi = if (decoded.wifiTotal > 0) decoded.wifiTotal else decoded.wifi.size,
            ble = if (decoded.bleTotal > 0) decoded.bleTotal else decoded.ble.size,
            recording = decoded.recording,
            recordName = decoded.recordName,
            scan = decoded.scan,
            frames = host.frames + 1,
        )
        merge(decoded.wifi.map { Row(it.id, it.name, it.rssi, host.name, System.currentTimeMillis()) }, wifiTable)
        merge(decoded.ble.map { Row(it.id, it.name, it.rssi, host.name, System.currentTimeMillis()) }, bleTable)
        // A linked phone's settings are its own; the desktop does not take them, it is the host.
        publish()
    }

    private fun merge(rows: List<Row>, table: ConcurrentHashMap<String, Row>) {
        for (row in rows) {
            if (row.id.isBlank()) continue
            val existing = table[row.id]
            // The strongest recent hearing wins, so a phone carried past a network updates it.
            if (existing == null || row.rssi > existing.rssi) table[row.id] = row
        }
    }

    /**
     * Sends this desktop's host blob to every linked phone.
     *
     * A host is responsible for the shared view - basemap, weak-dot filter, aircraft, satellite
     * groups, spin, units - and repeats that blob with every snapshot so a phone that reconnects
     * catches up without a handshake of its own.
     */
    private fun pushSnapshots() {
        val blob = settings.value
        for (device in devices.values.filter { it.kind == Kind.PEER && it.connected }) {
            val message = PeerProtocol.Message(
                kind = PeerProtocol.KIND_SKY,
                id = ID,
                name = NAME,
                // The desktop is the big screen by definition: it should take the globe on the
                // far end's automatic split.
                diagIn = 27f,
                at = System.currentTimeMillis(),
                mode = PeerProtocol.MODE_HOST,
                hostSettings = blob.toProtocol(),
            )
            val bytes = PeerProtocol.encode(message).toByteArray(Charsets.UTF_8)
            val mtu = device.mtu.coerceAtLeast(23)
            val frames = PeerFrames.split(bytes, (mtu - 3 - PeerFrames.HEADER).coerceIn(16, 240), frameId.incrementAndGet() and 0xFF)
            if (frames.isEmpty()) continue
            scope.launch {
                for (chunk in frames) {
                    if (owners[device.address]?.write(device.address, DOWN, chunk) != true) break
                    outCount.incrementAndGet()
                    delay(FRAME_GAP_MS)
                }
                publish()
            }
        }
    }

    private val frameId = AtomicInteger(0)

    private fun prune() {
        val cutoff = System.currentTimeMillis() - STALE_MS
        // A linked phone is not stale just because it stopped advertising; the link is the proof.
        devices.entries.removeAll { (_, device) -> !device.connected && device.lastSeen < cutoff }
        wifiTable.entries.removeAll { it.value.at < cutoff }
        bleTable.entries.removeAll { it.value.at < cutoff }
        publish()
    }

    private fun note(message: String) {
        if (message.isBlank()) return
        synchronized(notes) {
            notes.addFirst(message.take(200))
            while (notes.size > 60) notes.removeLast()
        }
        publish()
    }

    private fun publish() {
        val rows = devices.values.sortedWith(
            compareByDescending<Hosted> { it.kind == Kind.PEER }.thenBy { it.name },
        )
        val wifi = wifiTable.values.sortedByDescending { it.rssi }
        val ble = bleTable.values.sortedByDescending { it.rssi }
        _state.value = _state.value.copy(
            devices = rows,
            wifi = wifi.take(60),
            ble = ble.take(60),
            framesIn = devices.values.sumOf { it.frames },
            framesOut = outCount.get(),
            log = synchronized(notes) { notes.toList() },
        )
    }
}

/** What the desktop is responsible for on the link, and the knobs the window exposes. */
data class HostSettings(
    val rev: Int = 1,
    val tileSource: String = "streets",
    val dropWeakDots: Boolean = false,
    val aircraftEnabled: Boolean = true,
    val globeSpin: Int = 1,
    val distanceUnit: String = "km",
    val tempUnit: String = "c",
    val satGroups: List<String> = listOf("stations", "visual", "weather"),
) {
    fun toProtocol(): PeerProtocol.HostSettings = PeerProtocol.HostSettings(
        rev = rev,
        tileSource = tileSource,
        dropWeakDots = dropWeakDots,
        aircraftEnabled = aircraftEnabled,
        satGroups = satGroups,
        globeSpin = globeSpin,
        showLiveTime = true,
        clockSeconds = true,
        distanceUnit = distanceUnit,
        tempUnit = tempUnit,
    )
}

/** Decoding that has nothing to do with the radio, kept here so a JVM test can cover it. */
object HostCodec {

    /** The board's own telemetry, if this advertisement is one of ours. */
    fun boardAdvert(advert: LinkEvent.Advert): Esp32Codec.Advert? {
        val payload = advert.manufacturer[Esp32Codec.COMPANY_ID] ?: return null
        // Esp32Codec.decode expects the company id in front of the payload, as it sits on air.
        val wire = ByteArray(payload.size + 2)
        wire[0] = (Esp32Codec.COMPANY_ID and 0xFF).toByte()
        wire[1] = ((Esp32Codec.COMPANY_ID shr 8) and 0xFF).toByte()
        payload.copyInto(wire, 2)
        return Esp32Codec.decode(wire)
    }

    /** The peer id a phone is advertising, if this advertisement is one. */
    fun peerId(advert: LinkEvent.Advert): String? {
        val service = DeviceHost.PEER_SERVICE.toString()
        val data = advert.serviceData[service] ?: return null
        val id = String(data, Charsets.UTF_8).trim()
        return if (id.isBlank()) null else id
    }
}
