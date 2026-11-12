package dev.skyward.rf.desktop.sky

import dev.skyward.rf.core.AircraftView
import dev.skyward.rf.core.DroneView
import dev.skyward.rf.core.FileStore
import dev.skyward.rf.core.LatLon
import dev.skyward.rf.core.MeshNode
import dev.skyward.rf.core.SatView
import dev.skyward.rf.core.Settings
import dev.skyward.rf.desktop.drones.RidFeed
import dev.skyward.rf.sky.AircraftSource
import dev.skyward.rf.sky.MeshSource
import dev.skyward.rf.sky.SatTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * What is around and above the desk.
 *
 * The phone sees this through its own radios and its own network; the desk sees it through the
 * same community feeds the app uses - CelesTrak element sets propagated locally with SGP4,
 * api.adsb.lol's keyless point query, the Meshtastic and MeshCore MQTT brokers, and BLE Remote ID
 * - so the map here is the same sky and the same mesh as the one on the handset, from the same
 * observer, not a decoration beside the console.
 *
 * Propagation ticks every few seconds because it is local arithmetic; the aircraft feed is asked
 * once a minute because it is somebody else's server and the ring is only 30 miles wide. The mesh
 * brokers stream on their own and are only copied out for drawing; Remote ID arrives from the
 * helper process and expires in seconds.
 */
class DeskSky(
    private val scope: CoroutineScope,
    home: File,
) {

    data class State(
        val observer: LatLon? = null,
        val sats: List<SatView> = emptyList(),
        val aircraft: List<AircraftView> = emptyList(),
        val mesh: List<MeshNode> = emptyList(),
        val drones: List<DroneView> = emptyList(),
        val satNote: String = "loading element sets…",
        val airNote: String = "no observer yet",
        val meshNote: String = "feed off",
        val droneNote: String = "scanner off",
        val groups: Set<String> = emptySet(),
        val satsLive: Boolean = true,
        val airLive: Boolean = true,
        val meshLive: Boolean = false,
        val droneLive: Boolean = false,
        val lastTick: Long = 0,
    )

    private val store = FileStore(home)
    private val http = OkHttpClient.Builder()
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
    private val tracker = SatTracker(store, http)
    private val aircraft = AircraftSource(http)

    /**
     * The mesh feed is the app's own source, moved into `:shared`, reading the same public brokers
     * the phone reads. Both meshes are one switch on the desk: an operator watching the map wants
     * "is there a mesh", and the two feeds are merged into one node table anyway.
     */
    private val meshFeed = MeshSource {
        Settings(meshMqttEnabled = meshWanted, meshCoreEnabled = meshWanted)
    }

    private val rid = RidFeed(scope, home)

    @Volatile
    private var meshWanted = false

    private val _state = MutableStateFlow(State(groups = tracker.knownGroups().toSet()))
    val state: StateFlow<State> = _state

    private var observer: LatLon? = null

    fun setObserver(where: LatLon?) {
        observer = where
        _state.update { it.copy(observer = where, airNote = if (where == null) "no observer yet" else "asking…") }
    }

    fun setLayer(layer: String, on: Boolean) {
        _state.update {
            when (layer) {
                "sat" -> it.copy(satsLive = on)
                "air" -> it.copy(airLive = on)
                "mesh" -> it.copy(meshLive = on)
                "uav" -> it.copy(droneLive = on)
                else -> it
            }
        }
        when (layer) {
            "mesh" -> {
                meshWanted = on
                if (on) meshFeed.start() else meshFeed.stop()
                _state.update { it.copy(meshNote = if (on) "connecting…" else "feed off", mesh = if (on) it.mesh else emptyList()) }
            }

            "uav" -> if (on) rid.start() else rid.stop()
            else -> Unit
        }
    }

    fun toggleGroup(group: String) {
        val next = _state.value.groups.toMutableSet()
        if (!next.add(group)) next.remove(group)
        _state.update { it.copy(groups = next) }
        reload()
    }

    fun start() {
        reload()
        // Local propagation: no network, so this can run as often as the globe wants to move.
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                val positions = runCatching { tracker.positions(observer) }.getOrDefault(emptyList())
                _state.update { it.copy(sats = positions, lastTick = System.currentTimeMillis()) }
                delay(4_000)
            }
        }
        // The aircraft feed is a shared community service: one request a minute is plenty for a
        // 30 mile ring, and it is the reason this loop is not on the same tick as the satellites.
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val where = observer
                if (where != null) {
                    val result = runCatching { aircraft.fetch(where) }.getOrNull()
                    _state.update {
                        if (result == null) {
                            it.copy(airNote = "network error")
                        } else {
                            it.copy(
                                aircraft = result.aircraft,
                                airNote = if (result.aircraft.isEmpty()) "nothing in the ring" else "${result.aircraft.size} in the ring",
                            )
                        }
                    }
                }
                delay(60_000)
            }
        }
        // The mesh brokers push as they like; this only copies the latest table out for drawing
        // and re-scores nodes against the observer, which the phone does on every fix too.
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                if (meshWanted) {
                    meshFeed.updateDistances(observer?.lat, observer?.lon)
                    val nodes = meshFeed.nodesFlow.value.values.toList()
                    val running = meshFeed.isRunning()
                    _state.update {
                        it.copy(
                            mesh = nodes.sortedByDescending { node -> node.lastSeen },
                            meshNote = when {
                                nodes.isEmpty() && running -> "connected · no nodes heard"
                                nodes.isEmpty() -> "connecting…"
                                else -> "${nodes.size} nodes"
                            },
                        )
                    }
                }
                delay(2_000)
            }
        }
        rid.observe { observer }
        // The drone layer republishes itself; nothing to poll here, but its state has to reach the
        // map, so it is mirrored into this one rather than read from two places by the UI.
        scope.launch(Dispatchers.Default) {
            rid.state.collect { drones ->
                _state.update { it.copy(drones = drones.drones, droneNote = drones.note, droneLive = drones.live) }
            }
        }
    }

    /** Re-reads the element sets; a cached set younger than six hours is reused without a fetch. */
    fun reload() {
        scope.launch(Dispatchers.IO) {
            val result = tracker.refresh(_state.value.groups.toList(), force = false)
            _state.update {
                it.copy(
                    satNote = if (result.ok) "${result.count} element sets · ${result.detail}" else "offline · ${result.detail}",
                )
            }
        }
    }
}
