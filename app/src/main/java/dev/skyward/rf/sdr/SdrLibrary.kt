package dev.skyward.rf.sdr

import dev.skyward.rf.core.FileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * A recording that was stopped, saved and pinned.
 *
 * The spectrum travels with it. An I/Q recording is hundreds of megabytes and the app has no
 * player for it, so what is kept is what was actually on screen while it ran: the averaged trace
 * and a strip of waterfall rows. That is enough for "what was on 433.92 there", which is what a
 * pin is for, and it stays small enough to keep a few dozen of them.
 */
@Serializable
data class SdrCapture(
    val id: String,
    val name: String,
    val at: Long,
    val endedAt: Long,
    /** Tuned centre of the sweep, in MHz. */
    val centerMhz: Double,
    /** Width the bars cover: the dongle's sample rate, in MHz. */
    val spanMhz: Double,
    val sampleRate: Int,
    /** Frames drawn while recording; a recording with no frames is kept, and says so. */
    val frames: Int,
    /** Strongest bar of the whole recording, in dBFS. */
    val peakDb: Float,
    /** Where the phone was when the recording started: the pin's position. */
    val lat: Double,
    val lon: Double,
    val spectrum: List<Float> = emptyList(),
    val waterfall: List<List<Float>> = emptyList(),
)

/**
 * Recordings on disk, newest first.
 *
 * Written through [FileStore] like every other table in the app, so a capture lands in the same
 * folder that is copied off the phone and survives a restart without a database behind it.
 */
class SdrLibrary(private val store: FileStore) {

    companion object {
        private const val FILE = "sdr_v1.json"
        /** Older captures fall off the end: this is a map pin memory, not an archive. */
        private const val MAX = 60
    }

    private val _state = MutableStateFlow(store.read<List<SdrCapture>>(FILE).orEmpty())
    val state: StateFlow<List<SdrCapture>> = _state

    fun snapshot(): List<SdrCapture> = _state.value

    fun add(capture: SdrCapture) {
        val next = (listOf(capture) + _state.value).take(MAX)
        _state.value = next
        store.write(FILE, next)
    }

    fun remove(id: String) {
        val next = _state.value.filterNot { it.id == id }
        _state.value = next
        store.write(FILE, next)
    }

    fun clear() {
        _state.value = emptyList()
        store.write(FILE, emptyList<SdrCapture>())
    }
}
