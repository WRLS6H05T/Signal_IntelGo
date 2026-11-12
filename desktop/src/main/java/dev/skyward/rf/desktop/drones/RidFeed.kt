package dev.skyward.rf.desktop.drones

import dev.skyward.rf.core.DroneView
import dev.skyward.rf.core.Geo
import dev.skyward.rf.core.LatLon
import dev.skyward.rf.scan.OdidParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Remote ID over BLE, as the desk can hear it.
 *
 * The phone gets these advertisements from Android's BLE scanner. A JVM cannot: Windows does not
 * expose advertisement scanning to Java without a native bridge. So the radio work is done by
 * [rid_scan.py] - Python's bleak, which this machine already has - and the decoding stays here,
 * using the very parser the phone uses on the same frames. The desk listens; it does not guess.
 *
 * If Python or the Bluetooth radio is missing, the layer reports that rather than showing an empty
 * sky that looks like a working scanner with nothing in range.
 */
class RidFeed(
    private val scope: CoroutineScope,
    private val home: File,
) {

    data class State(
        val drones: List<DroneView> = emptyList(),
        val note: String = "scanner off",
        val live: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Latest message from each aircraft, merged: a pack splits identity, position and system. */
    /**
     * Why the scanner is not listening, once it is not.
     *
     * The publisher used to rewrite the status line on every tick, so "no BLE radio" was replaced
     * by "scanner off" a second later and the operator could never tell a missing radio from a
     * switched-off layer.
     */
    @Volatile
    private var failure: String? = null

    private val frames = ConcurrentHashMap<String, OdidParser.DroneFrame>()
    private val rssi = ConcurrentHashMap<String, Int>()
    private val seenAt = ConcurrentHashMap<String, Long>()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var observer: (() -> LatLon?)? = null

    private var loop: Job? = null

    fun observe(where: () -> LatLon?) {
        observer = where
    }

    fun isRunning(): Boolean = process?.isAlive == true

    fun start() {
        if (isRunning()) return
        val script = scriptFile() ?: run {
            _state.value = State(note = "scanner script missing", live = false)
            return
        }
        val python = pythonCommand() ?: run {
            _state.value = State(note = "python not found", live = false)
            return
        }
        val started = runCatching {
            ProcessBuilder(python + script.absolutePath)
                .redirectErrorStream(true)
                .start()
        }.getOrNull()
        if (started == null) {
            _state.value = State(note = "scanner would not start", live = false)
            return
        }
        process = started
        failure = null
        _state.value = State(note = "scanning for Remote ID…", live = true)
        loop = scope.launch(Dispatchers.IO) {
            started.inputStream.bufferedReader().forEachLine { line -> handle(line) }
            // The reader ends when the child dies; that is a fact worth showing, and one the
            // publisher must not overwrite with a cheerful "off".
            if (failure == null) failure = "scanner exited"
            _state.update { it.copy(note = failure ?: "scanner exited", live = false) }
        }
        // Expiry and republishing: an advertisement herd goes stale much faster than the map.
        scope.launch(Dispatchers.Default) {
            while (isActive && isRunning()) {
                publish()
                kotlinx.coroutines.delay(2_000)
            }
        }
    }

    fun stop() {
        process?.let { proc -> runCatching { proc.destroy() } }
        process = null
        failure = null
        loop?.cancel()
        loop = null
        frames.clear()
        rssi.clear()
        seenAt.clear()
        _state.value = State(note = "scanner off", live = false)
    }

    /** One line from the scanner: `mac|rssi|hex`, or `unavailable|reason`. */
    private fun handle(line: String) {
        if (line.isBlank()) return
        val parts = line.split('|')
        if (parts[0] == "unavailable") {
            // bleak reports a tuple for an unavailable adapter; the quoted sentence inside it is
            // the part an operator can act on, the enum repr is not.
            val raw = parts.getOrElse(1) { "" }
            val reason = Regex("'([^']{6,})'").find(raw)?.groupValues?.get(1) ?: raw
            failure = "no BLE radio · ${reason.trim()}"
            _state.value = State(note = failure!!, live = false)
            return
        }
        if (parts.size < 3) return
        val bytes = runCatching { parts[2].chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
            .getOrNull() ?: return
        val frame = OdidParser.parse(bytes) ?: return
        val mac = parts[0]
        val previous = frames[mac]
        frames[mac] = if (previous == null) frame else merge(previous, frame)
        parts[1].toIntOrNull()?.let { rssi[mac] = it }
        seenAt[mac] = System.currentTimeMillis()
    }

    /**
     * Remote ID is broadcast as a pack of short messages, each carrying a different part of the
     * aircraft - identity in one, position in another, the operator in a third. Keeping only the
     * newest message would drop the position whenever the identity was re-sent, so parts are
     * merged and a later value only replaces an earlier one where it actually carries data.
     */
    private fun merge(old: OdidParser.DroneFrame, new: OdidParser.DroneFrame) = OdidParser.DroneFrame(
        uasId = new.uasId ?: old.uasId,
        idType = if (new.idType >= 0) new.idType else old.idType,
        lat = new.lat ?: old.lat,
        lon = new.lon ?: old.lon,
        geodeticAltM = new.geodeticAltM ?: old.geodeticAltM,
        pressureAltM = new.pressureAltM ?: old.pressureAltM,
        heightM = new.heightM ?: old.heightM,
        speedMs = new.speedMs ?: old.speedMs,
        headingDeg = new.headingDeg ?: old.headingDeg,
        verticalSpeedMs = new.verticalSpeedMs ?: old.verticalSpeedMs,
        status = if (new.status >= 0) new.status else old.status,
        operatorLat = new.operatorLat ?: old.operatorLat,
        operatorLon = new.operatorLon ?: old.operatorLon,
    )

    private fun publish() {
        val now = System.currentTimeMillis()
        val where = observer?.invoke()
        val alive = frames.keys.filter { now - (seenAt[it] ?: 0L) < 20_000L }
        val drones = alive.mapNotNull { mac ->
            val frame = frames[mac] ?: return@mapNotNull null
            // Locals: these come from another module, so they cannot be smart-cast in place.
            val lat = frame.lat
            val lon = frame.lon
            val signal = rssi[mac] ?: -127
            val seen = seenAt[mac] ?: now
            val distance = if (lat != null && lon != null && where != null) {
                Geo.distanceM(where.lat, where.lon, lat, lon) / 1000.0
            } else {
                null
            }
            DroneView(
                mac = mac,
                uasId = frame.uasId ?: "unknown",
                operatorId = if (frame.operatorLat != null) "broadcast" else "",
                lat = lat,
                lon = lon,
                altM = frame.geodeticAltM ?: frame.pressureAltM,
                heightM = frame.heightM,
                speedMs = frame.speedMs,
                headingDeg = frame.headingDeg,
                rssi = signal,
                lastSeen = seen,
                distanceKm = distance,
                score = scoreOf(signal),
            )
        }.sortedByDescending { it.lastSeen }
        _state.update {
            it.copy(
                drones = drones,
                note = when {
                    !isRunning() -> failure ?: "scanner off"
                    drones.isEmpty() -> "listening · nothing broadcasting"
                    else -> "${drones.size} broadcasting"
                },
            )
        }
    }

    /** Same shape as the phone's BLE score: -100 dBm is nothing, -40 dBm is on top of you. */
    private fun scoreOf(rssi: Int): Int = ((rssi + 100) * 100 / 60).coerceIn(0, 100)

    private fun scriptFile(): File? {
        val target = File(home, "rid_scan.py")
        if (target.isFile) return target
        val stream = RidFeed::class.java.getResourceAsStream("/rid_scan.py") ?: return null
        return runCatching {
            target.parentFile?.mkdirs()
            stream.use { input -> target.outputStream().use { input.copyTo(it) } }
            target
        }.getOrNull()?.takeIf { abs(it.length()) > 0 }
    }

    /** The interpreter this machine actually has, preferring the launcher Windows ships. */
    private fun pythonCommand(): List<String>? {
        val candidates = listOf(listOf("python"), listOf("py", "-3"), listOf("python3"))
        for (candidate in candidates) {
            val ok = runCatching {
                val probe = ProcessBuilder(candidate + listOf("-c", "import bleak"))
                    .redirectErrorStream(true)
                    .start()
                probe.inputStream.bufferedReader().use { it.readText() }
                probe.waitFor() == 0
            }.getOrDefault(false)
            if (ok) return candidate
        }
        return null
    }
}
