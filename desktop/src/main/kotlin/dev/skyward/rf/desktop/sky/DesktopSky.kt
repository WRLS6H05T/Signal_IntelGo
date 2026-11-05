package dev.skyward.rf.desktop.sky

import dev.skyward.rf.core.FileStore
import dev.skyward.rf.core.LatLon
import dev.skyward.rf.core.SatView
import dev.skyward.rf.sky.SatTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** What the globe is drawing right now. */
data class SkyState(
    val sats: List<SatView> = emptyList(),
    val detail: String = "loading element sets",
    val fetchedAt: Long = 0L,
)

/**
 * The satellite catalogue, for the desktop.
 *
 * This is the phone's own [SatTracker] from `:shared` - same CelesTrak groups, same disk cache,
 * same SGP4 propagation, same ISS collapsing - so the globe on the desk and the globe in the
 * hand show the same station in the same place. Only the scheduling is desktop-side.
 */
class DesktopSky(
    private val scope: CoroutineScope,
    home: File = File(System.getProperty("user.home"), "signintelgo"),
) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val tracker = SatTracker(FileStore(File(home, "tle")), http)

    private val _state = MutableStateFlow(SkyState())
    val state: StateFlow<SkyState> = _state

    fun start(observer: () -> LatLon?) {
        scope.launch {
            val groups = listOf("stations", "visual", "weather")
            val result = runCatching { tracker.refresh(groups) }.getOrNull()
            val detail = when {
                result?.ok == true && result.count > 0 -> "${result.count} element sets"
                result?.ok == true -> "cached catalogue"
                else -> "no element sets: ${result?.detail ?: "offline"}"
            }
            _state.value = _state.value.copy(detail = detail, fetchedAt = result?.fetchedAt ?: 0L)
            while (true) {
                val positions = runCatching { tracker.positions(observer()) }.getOrDefault(emptyList())
                _state.value = SkyState(positions, _state.value.detail, System.currentTimeMillis())
                delay(4_000)
            }
        }
    }
}
