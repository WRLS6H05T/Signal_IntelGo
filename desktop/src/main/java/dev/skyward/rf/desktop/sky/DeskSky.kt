package dev.skyward.rf.desktop.sky

import dev.skyward.rf.core.AircraftView
import dev.skyward.rf.core.FileStore
import dev.skyward.rf.core.LatLon
import dev.skyward.rf.core.SatView
import dev.skyward.rf.sky.AircraftSource
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
 * What is above the desk.
 *
 * The phone sees this through its own radios and its own network; the desk sees it through the
 * same two community feeds the app uses - CelesTrak element sets propagated locally with
 * SGP4, and api.adsb.lol's keyless point query - so the globe here is the same sky as the one on
 * the handset, from the same observer, not a decoration beside the console.
 *
 * Propagation ticks every few seconds because it is local arithmetic; the aircraft feed is asked
 * once a minute because it is somebody else's server and the ring is only 30 miles wide.
 */
class DeskSky(
    private val scope: CoroutineScope,
    home: File,
) {

    data class State(
        val observer: LatLon? = null,
        val sats: List<SatView> = emptyList(),
        val aircraft: List<AircraftView> = emptyList(),
        val satNote: String = "loading element sets…",
        val airNote: String = "no observer yet",
        val groups: Set<String> = emptySet(),
        val satsLive: Boolean = true,
        val airLive: Boolean = true,
        val lastTick: Long = 0,
    )

    private val store = FileStore(home)
    private val http = OkHttpClient.Builder()
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
    private val tracker = SatTracker(store, http)
    private val aircraft = AircraftSource(http)

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
                else -> it
            }
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
