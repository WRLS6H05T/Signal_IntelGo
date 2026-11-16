package dev.skyward.rf.desktop.alerts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.skyward.rf.core.DeviceWatch
import dev.skyward.rf.core.RfKind
import dev.skyward.rf.core.WatchHit
import dev.skyward.rf.core.WatchKind
import dev.skyward.rf.desktop.hardware.BoardState

/** One thing the desk flagged: what it is, how loudly it was heard, and how sure the match is. */
data class DeskAlert(
    val at: Long,
    val kind: WatchKind,
    val id: String,
    val name: String,
    val rssi: Int,
    val radio: RfKind,
    val source: String,
    val confidence: String,
    val strong: Boolean,
)

/**
 * The phone's watch alerting, with a banner instead of a voice.
 *
 * The phone speaks each new hit in the glitched voice and keeps a sightings ledger behind the timed
 * intel update. The desk has no microphone and no speech, so the same two jobs are done on screen: a
 * strip across the top for the hit that just fired, and a ledger in the LOG tab for everything the
 * session has flagged.
 *
 * The rate limits are the phone's, unchanged, because they are what makes the feature usable: one
 * alert per device per five minutes, and never two inside eight seconds, or a street full of Flock
 * hardware turns the panel into a wall of noise. When several new families are in view at once the
 * strongest is the one announced and the rest keep until the gap reopens.
 */
class DeskAlerts(private val clock: () -> Long = System::currentTimeMillis) {

    /** Newest first. Trimmed to the hour, which is the longest window a report asks about. */
    var alerts by mutableStateOf<List<DeskAlert>>(emptyList())
        private set

    /** The hit to put on the strip, or null once it has been shown or dismissed. */
    var latest by mutableStateOf<DeskAlert?>(null)
        private set

    private val announced = HashMap<String, Long>()
    private var lastAt = 0L

    /** Reads the current watch table and raises at most one alert. */
    fun scan(hits: List<WatchHit>, now: Long = clock()) {
        for (hit in hits.sortedByDescending { it.rssi }) {
            // lastAt of 0 is the first alert of a run, not one "just now" at t=0.
            if (lastAt != 0L && now - lastAt < GAP_MS) break
            val key = "${hit.kind.name}:${hit.id}"
            val seen = announced[key]
            if (seen != null && now - seen < REPEAT_MS) continue
            announced[key] = now
            lastAt = now
            val alert = DeskAlert(
                at = now,
                kind = hit.kind,
                id = hit.id,
                name = hit.name,
                rssi = hit.rssi,
                radio = hit.radio,
                source = hit.source,
                confidence = hit.confidence,
                strong = verdictIsStrong(hit),
            )
            alerts = (listOf(alert) + alerts).take(MAX)
            latest = alert
        }
        // The ledger ages out even on a quiet minute, so the LOG tab cannot show an hour-old hit as
        // if it were current.
        if (alerts.any { now - it.at > KEEP_MS }) {
            alerts = alerts.filter { now - it.at <= KEEP_MS }
        }
    }

    /** The strip hides itself after [BANNER_MS]; this is the operator closing it sooner. */
    fun dismiss() {
        latest = null
    }

    fun clear() {
        alerts = emptyList()
        latest = null
        announced.clear()
        lastAt = 0L
    }

    /** Whether the strip should still be on screen. */
    fun bannerOpen(now: Long): Boolean = latest?.let { now - it.at <= BANNER_MS } == true

    companion object {
        const val GAP_MS = 8_000L
        const val REPEAT_MS = 5 * 60_000L
        const val KEEP_MS = 60 * 60_000L
        const val BANNER_MS = 12_000L
        private const val MAX = 200

        /**
         * A strong verdict is the manufacturer id or the serial format, not a name that merely looks
         * like one: the strip marks those differently because they are the ones not to argue with.
         */
        private fun verdictIsStrong(hit: WatchHit): Boolean =
            hit.confidence.contains("manufacturer id") || hit.confidence.contains("serial")
    }
}

/**
 * The desk's half of the Watcher: finding the watched hardware among what the boards have swept.
 *
 * The matcher is the phone's [DeviceWatch], so a board's sweep and the handset's own scan agree about
 * what a Flock camera looks like. Boards report no company id, so the manufacturer-id half of that
 * table cannot fire here; what does fire is the SSID serial format and the vendor names, which is why
 * the confidence string travels with the alert instead of being flattened into a tick.
 */
object DeskWatch {

    fun hits(boards: List<BoardState>): List<WatchHit> {
        val out = ArrayList<WatchHit>(4)
        for (board in boards) {
            if (!board.connected) continue
            for (ap in board.sweep.aps) {
                val verdict = DeviceWatch.assess(RfKind.WIFI, ap.ssid, ap.bssid, 0) ?: continue
                out.add(
                    WatchHit(
                        kind = verdict.kind,
                        id = ap.bssid,
                        name = ap.ssid,
                        rssi = ap.rssi,
                        radio = RfKind.WIFI,
                        source = board.path,
                        at = 0L,
                        confidence = verdict.confidence,
                    ),
                )
            }
            for (device in board.sweep.devices) {
                val verdict = DeviceWatch.assess(RfKind.BLE, device.name, device.address, 0) ?: continue
                out.add(
                    WatchHit(
                        kind = verdict.kind,
                        id = device.address,
                        name = device.name,
                        rssi = device.rssi,
                        radio = RfKind.BLE,
                        source = board.path,
                        at = 0L,
                        confidence = verdict.confidence,
                    ),
                )
            }
        }
        return out
    }
}
