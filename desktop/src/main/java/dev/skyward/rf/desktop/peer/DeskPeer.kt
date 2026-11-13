package dev.skyward.rf.desktop.peer

import dev.skyward.rf.core.LatLon
import dev.skyward.rf.peer.PeerFrames
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
class DeskPeer(private val scope: CoroutineScope) {

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
    )

    private companion object {
        /** Same service and notify characteristic the phone's `BlePeer` advertises. */
        val SERVICE = BluetoothUUID("7a1f5e10-3c6b-4a2e-9f1d-5b8c2d4e6f70")
        val UP = BluetoothUUID("7a1f5e11-3c6b-4a2e-9f1d-5b8c2d4e6f70")

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

    fun start() {
        if (job != null) return
        _state.value = State(enabled = true, note = "starting")
        job = scope.launch(Dispatchers.IO) {
            runCatching { loop() }.onFailure { failure ->
                // A missing adapter, a radio switched off in Windows, or a native library that
                // will not load all end here, and each of them is a fact the operator needs to
                // see rather than a link that quietly never comes up.
                _state.value = _state.value.copy(note = "no link: ${failure.message ?: failure::class.simpleName}")
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        link?.let { runCatching { it.disconnect() } }
        link = null
        synchronized(assembler) { assembler.reset() }
        _state.value = State()
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
        link = peripheral
        val subscribed = runCatching {
            peripheral.notify(SERVICE, UP) { bytes -> onFrames(bytes) }
        }.isSuccess
        _state.value = _state.value.copy(
            linked = true,
            name = peripheral.identifier,
            note = if (subscribed) "linked · waiting for a snapshot" else "linked · subscription refused",
        )

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
        val payload = synchronized(assembler) { assembler.add(bytes) } ?: return
        val message = PeerProtocol.decode(String(payload, Charsets.UTF_8)) ?: return
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
        )
    }
}
