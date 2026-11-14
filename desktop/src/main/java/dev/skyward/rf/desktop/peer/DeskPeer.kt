package dev.skyward.rf.desktop.peer

import dev.skyward.rf.core.LatLon
import dev.skyward.rf.wardrive.WardrivePoint
import dev.skyward.rf.desktop.wardrive.DeskDrives
import dev.skyward.rf.peer.PeerFrames
import dev.skyward.rf.peer.PeerLogs
import dev.skyward.rf.peer.PeerProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.simplejavable.Adapter
import org.simplejavable.BluetoothUUID
import org.simplejavable.Peripheral

/**
 * The handset's peer link, pulled from the desk.
 *
 * The phone's link is a BLE GATT service: each side advertises it, and one GATT connection carries
 * both directions, the peripheral notifying on `UP` and the central writing on `DOWN`. The desk is
 * only ever the central here - it listens to the phone's snapshots and holds what they carry, which
 * is exactly what [PeerProtocol] calls data.
 *
 * Windows does not hand a JVM a Bluetooth API at all, so the GATT client is SimpleBLE's WinRT
 * backend through SimpleJavaBLE. Two consequences shape the scan loop below:
 *
 *  - the binding exposes a peripheral's address, RSSI and manufacturer data, but not the service
 *    UUIDs it advertises, so the phone cannot be picked out of a scan. Each candidate is therefore
 *    connected and its GATT table read; a device whose table does not hold the peer service is
 *    dropped and not probed again for two minutes.
 *  - connecting is slow, so one round probes a handful of the strongest signals rather than every
 *    peripheral in range. A desk in a room full of BLE would otherwise spend all its time dialling
 *    other people's headphones.
 *
 * Nothing travels the other way yet: the desk has no board intel the phone needs more than it
 * needs the link to stay up, and a link that only listens cannot misconfigure the far end.
 */
class DeskPeer(private val scope: CoroutineScope, private val drives: DeskDrives) {

    /** What the desk knows about the other device, for the Setup pane. */
    data class State(
        val enabled: Boolean = false,
        val linked: Boolean = false,
        val name: String = "",
        val note: String = "off",
        val sats: Int = 0,
        val fix: LatLon? = null,
        val gpsUsed: Boolean = false,
        val wifi: Int = 0,
        val ble: Int = 0,
        val entities: Int = 0,
        val entityTotal: Int = 0,
        val at: Long = 0L,
        /** The drives the phone says it has, as last offered. */
        val offered: List<PeerProtocol.LogInfo> = emptyList(),
        /** The drive being pulled right now, and how many of its parts have arrived. */
        val fetching: String = "",
        val progress: Int = 0,
        val progressTotal: Int = 0,
        /** Drives already on this desk, newest first. */
        val saved: List<DeskDrives.Row> = emptyList(),
        /** The last drive that finished arriving, for a line of confirmation. */
        val lastSave: String = "",
        /** Frames heard on the notify characteristic since the link came up. */
        val frames: Int = 0,
    )

    private companion object {
        /** Same service and notify characteristic the phone's `BlePeer` advertises. */
        val SERVICE = BluetoothUUID("7a1f5e10-3c6b-4a2e-9f1d-5b8c2d4e6f70")
        val UP = BluetoothUUID("7a1f5e11-3c6b-4a2e-9f1d-5b8c2d4e6f70")

        /** Central to peripheral: the desk's requests, written rather than notified. */
        val DOWN = BluetoothUUID("7a1f5e12-3c6b-4a2e-9f1d-5b8c2d4e6f70")

        const val SCAN_MS = 6_000

        /** Below this a candidate is another room's device, not a phone on the desk. */
        const val RSSI_FLOOR = -88

        /** Peripherals probed per round: each probe is a full connect and service discovery. */
        const val PROBES = 6
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var job: Job? = null
    private var link: Peripheral? = null
    private val assembler = PeerFrames.Assembler()

    /** When a device that turned out not to be the phone may be probed again. */
    private val skipUntil = HashMap<String, Long>()

    /** Frames of a drive arriving, keyed by drive id and then by slice index. */
    private val inParts = HashMap<String, HashMap<Int, String>>()
    private val inTotals = HashMap<String, Int>()
    private var sendId = 0
    private var frames = 0

    fun start() {
        if (job != null) return
        _state.value = State(enabled = true, note = "starting", saved = drives.list())
        job = scope.launch(Dispatchers.IO) {
            runCatching { loop() }.onFailure { failure ->
                // A missing adapter, a radio switched off in Windows, or a native library that
                // will not load all end here, and each of them is a fact the operator needs to
                // see rather than a link that quietly never comes up.
                _state.value = _state.value.copy(note = "no link: ${failure.message ?: failure::class.simpleName}")
            }
        }
    }

    /** Asks the phone for the drives it is willing to hand over. */
    fun requestList() {
        send(PeerProtocol.Message(kind = PeerProtocol.KIND_LOGS, wantList = true))
    }

    /** Asks for one drive by id; it arrives as a run of parts. */
    fun fetch(id: String) {
        if (id.isBlank()) return
        inParts.remove(id)
        inTotals.remove(id)
        _state.value = _state.value.copy(fetching = id, progress = 0, progressTotal = 0, lastSave = "")
        send(PeerProtocol.Message(kind = PeerProtocol.KIND_LOGS, want = id))
    }

    /** A drive's fixes, for the map. */
    fun track(id: String): List<LatLon> = drives.track(id)

    /** The same drive with its scores, for the dot-per-fix drawing. */
    fun points(id: String): List<WardrivePoint> = drives.points(id)

    /** Re-reads the folder, so drives that arrived by cable list beside the ones from the link. */
    fun refreshSaved() {
        _state.value = _state.value.copy(saved = drives.list())
    }

    fun stop() {
        job?.cancel()
        job = null
        link?.let { runCatching { it.disconnect() } }
        link = null
        synchronized(assembler) { assembler.reset() }
        _state.value = State()
    }

    /**
     * Writes one message on [DOWN].
     *
     * The phone reads this characteristic through the same frame assembler it feeds its own
     * notifications into, so a request longer than the MTU has to be cut the same way the sweeps
     * are. The negotiated MTU is used when the stack reports one, with the 23-byte default as the
     * floor.
     */
    private fun send(message: PeerProtocol.Message) {
        val peripheral = link ?: return
        val payload = PeerProtocol.encode(message).toByteArray(Charsets.UTF_8)
        val chunk = (peripheral.mtu - 3).coerceIn(20, 512)
        val id = sendId++ and 0xFF
        runCatching {
            PeerFrames.split(payload, chunk, id).forEach { frame ->
                peripheral.writeRequest(SERVICE, DOWN, frame)
            }
        }.onFailure {
            _state.value = _state.value.copy(note = "write refused · ${it.message ?: "link busy"}")
        }
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val adapter = runCatching { Adapter.getAdapters().firstOrNull() }.getOrNull()
            if (adapter == null) {
                _state.value = _state.value.copy(linked = false, note = "no Bluetooth adapter")
                delay(15_000)
                continue
            }
            // A radio that is off is the commonest reason a link never comes up, and from the
            // outside it looks identical to a phone in the next room: say which one it is.
            if (!runCatching { Adapter.isBluetoothEnabled() }.getOrDefault(false)) {
                _state.value = _state.value.copy(linked = false, note = "Bluetooth is off in Windows")
                delay(10_000)
                continue
            }
            _state.value = _state.value.copy(note = "scanning")
            val found = runCatching {
                adapter.scanFor(SCAN_MS)
                adapter.scanGetResults()
            }.getOrElse { emptyList() }

            val candidates = found
                .filter { it.isConnectable && it.rssi > RSSI_FLOOR }
                .sortedByDescending { it.rssi }
                .take(PROBES)

            var linked = false
            for (candidate in candidates) {
                val until = skipUntil[candidate.identifier] ?: 0L
                if (System.currentTimeMillis() < until) continue
                if (tryLink(candidate)) {
                    linked = true
                    break
                }
            }
            if (!linked) {
                _state.value = _state.value.copy(
                    linked = false,
                    note = if (found.isEmpty()) "searching · nothing in range" else "searching · ${found.size} in range",
                )
                delay(1_500)
            }
        }
    }

    /** Connects, keeps the subscription for as long as the link holds, and reports whether it did. */
    private suspend fun tryLink(peripheral: Peripheral): Boolean {
        _state.value = _state.value.copy(note = "connecting to ${peripheral.identifier}")
        val connected = runCatching {
            peripheral.connect()
            peripheral.isConnected
        }.getOrDefault(false)
        if (!connected) {
            skipUntil[peripheral.identifier] = System.currentTimeMillis() + 20_000
            return false
        }

        val ours = runCatching {
            peripheral.services().any { it.uuid().equals(SERVICE.toString(), ignoreCase = true) }
        }.getOrDefault(false)
        if (!ours) {
            runCatching { peripheral.disconnect() }
            skipUntil[peripheral.identifier] = System.currentTimeMillis() + 120_000
            return false
        }

        synchronized(assembler) { assembler.reset() }
        frames = 0
        inParts.clear()
        inTotals.clear()
        link = peripheral
        val subscribed = runCatching {
            peripheral.notify(SERVICE, UP) { bytes -> onFrames(bytes) }
        }.isSuccess
        _state.value = _state.value.copy(
            linked = true,
            name = peripheral.identifier,
            note = if (subscribed) "linked · waiting for a snapshot" else "linked · subscription refused",
            saved = drives.list(),
        )

        // Ask as soon as the link is up rather than waiting to be spoken to. The phone offers a
        // snapshot every five seconds while it has a subscriber, but a phone whose app has been
        // throttled in the background can sit there saying nothing, and a request is what turns a
        // silent link into either an answer or a visible write failure.
        if (subscribed) {
            delay(1_500)
            requestList()
        }

        // The binding has no disconnect callback of its own that carries the reason, so the link
        // is watched rather than waited on.
        while (peripheral.isConnected) delay(1_000)
        link = null
        synchronized(assembler) { assembler.reset() }
        _state.value = _state.value.copy(linked = false, note = "link dropped · scanning")
        return true
    }

    /** Runs on SimpleBLE's callback thread: reassemble, decode, publish. */
    private fun onFrames(bytes: ByteArray) {
        // Counted before decoding: "the phone says nothing" and "the phone is sending something
        // this build cannot read" need different answers, and from the outside they look the same.
        frames++
        val payload = synchronized(assembler) { assembler.add(bytes) }
        if (payload == null) {
            if (frames == 1 || frames % 40 == 0) {
                _state.value = _state.value.copy(frames = frames, note = "linked · $frames frames · nothing complete yet")
            }
            return
        }
        val message = PeerProtocol.decode(String(payload, Charsets.UTF_8)) ?: return
        if (message.kind == PeerProtocol.KIND_LOGS) {
            takeLogs(message)
            return
        }
        if (message.kind != PeerProtocol.KIND_SKY) return
        val lat = message.lat
        val lon = message.lon
        _state.value = _state.value.copy(
            linked = true,
            name = message.name.ifBlank { _state.value.name },
            sats = message.sats,
            fix = if (lat != null && lon != null) LatLon(lat, lon) else null,
            gpsUsed = message.gpsUsed,
            wifi = message.wifiTotal,
            ble = message.bleTotal,
            entities = message.entities.size,
            entityTotal = message.entityTotal,
            at = message.at,
            note = "receiving snapshots",
            frames = frames,
        )
    }

    /** The phone's answer to a list request, and the slices of a drive being pulled. */
    private fun takeLogs(message: PeerProtocol.Message) {
        if (message.logs.isNotEmpty()) {
            _state.value = _state.value.copy(offered = message.logs)
        }
        val part = message.part ?: return
        if (part.id.isBlank() || part.data.isEmpty()) return
        if (part.total <= 0 || part.total > PeerLogs.MAX_PARTS || part.idx !in 0 until part.total) return

        val known = inTotals[part.id]
        if (known != null && known != part.total) {
            // A different slicing of the same id is a fresh transfer, not a continuation.
            inParts.remove(part.id)
        }
        inTotals[part.id] = part.total
        val slices = inParts.getOrPut(part.id) { HashMap() }
        slices[part.idx] = part.data
        _state.value = _state.value.copy(fetching = part.id, progress = slices.size, progressTotal = part.total)
        if (slices.size < part.total) return

        inParts.remove(part.id)
        inTotals.remove(part.id)
        val json = PeerLogs.unpack((0 until part.total).mapNotNull { slices[it] }) ?: run {
            _state.value = _state.value.copy(fetching = "", lastSave = "that drive did not unpack")
            return
        }
        val row = drives.save(json, _state.value.name)
        _state.value = _state.value.copy(
            fetching = "",
            progress = part.total,
            progressTotal = part.total,
            saved = drives.list(),
            lastSave = if (row == null) {
                "that drive did not parse"
            } else {
                "saved ${row.name.ifBlank { row.id }} · ${row.points} fixes · ${String.format("%.1f", row.km)} km"
            },
        )
    }
}
