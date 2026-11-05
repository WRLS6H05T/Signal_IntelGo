package dev.skyward.rf.wardrive

import dev.skyward.rf.core.FileStore
import dev.skyward.rf.core.appJson
import dev.skyward.rf.core.Geo
import dev.skyward.rf.core.RfObservation
import dev.skyward.rf.core.SignalScale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.decodeFromString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records a drive: every GPS fix is paired with whatever the radios heard at it and stored with
 * a 0..100 signal score. The score is what the map turns into the 5-colour red->green path.
 *
 * The radios are handed in rather than read from a scanner, so the recorder has no opinion about
 * how the world was heard: local chips, a peer's table or a board's sweep arrive the same way.
 */
class WardriveEngine(
    private val store: FileStore,
    private val logged: LoggedRegistry,
    private val maxNetworksPerPoint: Int = 10,
) {

    data class State(
        val active: Boolean = false,
        val sessionId: String? = null,
        val name: String = "",
        val startedAt: Long = 0L,
        val points: List<WardrivePoint> = emptyList(),
        val distanceM: Double = 0.0,
        val wifiCount: Int = 0,
        val bleCount: Int = 0,
        val bestScore: Int = 0,
        val lastPoint: WardrivePoint? = null,
        val note: String? = null,
    ) {
        val durationMs: Long
            get() = if (startedAt > 0) (System.currentTimeMillis() - startedAt).coerceAtLeast(0) else 0L
        val km: Double get() = distanceM / 1000.0
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions

    /** Session currently drawn on the map (live recording wins over a loaded one). */
    private val _overlay = MutableStateFlow<WardriveSession?>(null)
    val overlay: StateFlow<WardriveSession?> = _overlay

    private val seenWifi = HashSet<String>()
    private val seenBle = HashSet<String>()
    private val seenDrones = HashSet<String>()
    private var bestScore = 0
    private var lastFixAt = 0L
    private var lastLat = 0.0
    private var lastLon = 0.0

    init {
        refreshSessions()
    }

    fun start(name: String? = null): Boolean {
        if (_state.value.active) return false
        seenWifi.clear()
        seenBle.clear()
        seenDrones.clear()
        bestScore = 0
        lastFixAt = 0L
        lastLat = 0.0
        lastLon = 0.0
        val now = System.currentTimeMillis()
        val sessionName = name ?: defaultName(now)
        val st = State(active = true, sessionId = now.toString(), startedAt = now, name = sessionName)
        _state.value = st
        _overlay.value = null
        return true
    }

    fun stop(): WardriveSession? {
        val st = _state.value
        if (!st.active) return null
        val session = buildSession(st, System.currentTimeMillis())
        save(session)
        logged.save()
        _state.value = State()
        refreshSessions()
        _overlay.value = session
        return session
    }

    fun setNote(note: String?) {
        _state.value = _state.value.copy(note = note)
    }

    /** Called for every accepted GPS fix while recording. */
    fun onFix(
        lat: Double,
        lon: Double,
        accuracyM: Float,
        now: Long,
        wifi: List<RfObservation>,
        ble: List<RfObservation>,
        droneKeys: Set<String> = emptySet(),
        counts: LoggedCounts = LoggedCounts(),
    ): WardrivePoint? {
        val st = _state.value
        if (!st.active) return null

        // Throttle: at least 2 s apart and either moved 2 m or 8 s elapsed.
        val movedM = if (lastFixAt == 0L) Double.MAX_VALUE else Geo.distanceM(lastLat, lastLon, lat, lon)
        val elapsed = now - lastFixAt
        if (lastFixAt != 0L && elapsed < 2_000) return null
        if (lastFixAt != 0L && movedM < 2.0 && elapsed < 8_000) return null

        val obs = wifi + ble
        val peak = SignalScale.peakScore(obs)
        val avg = SignalScale.meanScore(obs)
        val bestWifiObs = wifi.maxByOrNull { it.rssi }
        val bestBleObs = ble.maxByOrNull { it.rssi }
        val top = obs.sortedByDescending { it.rssi }.take(maxNetworksPerPoint)

        val point = WardrivePoint(
            lat = lat,
            lon = lon,
            t = now,
            score = peak,
            avgScore = avg,
            wifiCount = wifi.size,
            bleCount = ble.size,
            bestWifi = bestWifiObs?.let { "${it.name} (${it.security})" } ?: "",
            bestWifiRssi = bestWifiObs?.rssi ?: -127,
            bestBle = bestBleObs?.name?.ifBlank { bestBleObs.id } ?: "",
            bestBleRssi = bestBleObs?.rssi ?: -127,
            accuracyM = accuracyM,
            networks = top,
            loggedSats = counts.sats,
            loggedAircraft = counts.aircraft,
            loggedDrones = counts.drones,
            loggedMesh = counts.mesh,
        )

        wifi.forEach { seenWifi.add(it.id) }
        ble.forEach { seenBle.add(it.id) }
        droneKeys.forEach { seenDrones.add(it) }
        if (peak > bestScore) bestScore = peak

        val addedDistance = if (lastFixAt == 0L) 0.0 else Geo.distanceM(lastLat, lastLon, lat, lon)
        lastFixAt = now
        lastLat = lat
        lastLon = lon

        val next = st.copy(
            points = st.points + point,
            distanceM = st.distanceM + addedDistance,
            wifiCount = seenWifi.size,
            bleCount = seenBle.size,
            bestScore = bestScore,
            lastPoint = point,
            note = null,
        )
        _state.value = next
        return point
    }

    /** Recomputes bin counts etc. from the running state. */
    private fun buildSession(st: State, endedAt: Long): WardriveSession {
        val bins = IntArray(5)
        st.points.forEach { bins[SignalScale.binOfScore(it.score)]++ }
        return WardriveSession(
            id = st.sessionId ?: endedAt.toString(),
            name = st.name.ifBlank { defaultName(st.startedAt) },
            startedAt = st.startedAt,
            endedAt = endedAt,
            points = st.points,
            distanceM = st.distanceM,
            wifiAps = seenWifi.size,
            bleDevices = seenBle.size,
            bestScore = bestScore,
            binCounts = bins.toList(),
            droneCount = seenDrones.size,
        )
    }

    private fun save(session: WardriveSession) {
        store.write("session_${session.id}.json", session)
    }

    fun refreshSessions() {
        val names = store.list("session_", ".json")
        val out = ArrayList<SessionSummary>()
        for (n in names) {
            val s = store.read<WardriveSession>(n) ?: continue
            out.add(
                SessionSummary(
                    id = s.id,
                    name = s.name,
                    startedAt = s.startedAt,
                    endedAt = s.endedAt,
                    points = s.points.size,
                    km = s.km,
                    wifiAps = s.wifiAps,
                    bleDevices = s.bleDevices,
                    bestScore = s.bestScore,
                    from = s.from,
                ),
            )
        }
        _sessions.value = out.sortedByDescending { it.startedAt }
    }

    fun loadSession(id: String): WardriveSession? = store.read("session_$id.json")

    fun showSession(id: String?) {
        _overlay.value = if (id == null) null else loadSession(id)
    }

    /** The stored JSON for one drive, as the peer link sends it. */
    fun sessionJson(id: String): String? = store.readText("session_$id.json")

    /**
     * Stores a drive another phone sent. The id is kept, so fetching the same drive twice
     * replaces the copy instead of stacking duplicates, and the sender's name is recorded so
     * the list can say where it came from.
     */
    fun importSession(json: String, from: String): Boolean {
        val session = runCatching { appJson.decodeFromString<WardriveSession>(json) }.getOrNull()
            ?: return false
        if (session.id.isBlank()) return false
        val stamped = if (session.from.isBlank()) session.copy(from = from.take(32)) else session
        store.write("session_${stamped.id}.json", stamped)
        refreshSessions()
        return true
    }

    fun deleteSession(id: String) {
        store.delete("session_$id.json")
        if (_overlay.value?.id == id) _overlay.value = null
        refreshSessions()
    }

    // ---------------- exports ----------------

    fun exportGeoJson(session: WardriveSession): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val sb = StringBuilder()
        sb.append("{\"type\":\"FeatureCollection\",\"properties\":{")
        sb.append("\"name\":").append(q(session.name))
        sb.append(",\"started\":\"").append(iso.format(Date(session.startedAt))).append("\"")
        sb.append(",\"km\":").append(String.format(Locale.US, "%.3f", session.km))
        sb.append(",\"wifi\":").append(session.wifiAps)
        sb.append(",\"ble\":").append(session.bleDevices)
        sb.append(",\"scale\":\"5-bin RdYlGn weak(red)->strong(green)\"")
        sb.append("},\"features\":[")
        if (session.points.size > 1) {
            sb.append("{\"type\":\"Feature\",\"properties\":{\"kind\":\"track\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
            session.points.forEachIndexed { i, p ->
                if (i > 0) sb.append(',')
                sb.append('[').append(fmt(p.lon)).append(',').append(fmt(p.lat)).append(']')
            }
            sb.append("]}},")
        }
        session.points.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append("{\"type\":\"Feature\",\"properties\":{")
            sb.append("\"score\":").append(p.score)
            sb.append(",\"bin\":").append(SignalScale.binOfScore(p.score))
            sb.append(",\"binLabel\":").append(q(SignalScale.binLabels[SignalScale.binOfScore(p.score)]))
            sb.append(",\"wifi\":").append(p.wifiCount)
            sb.append(",\"ble\":").append(p.bleCount)
            sb.append(",\"bestWifi\":").append(q(p.bestWifi))
            sb.append(",\"bestWifiRssi\":").append(p.bestWifiRssi)
            sb.append(",\"bestBle\":").append(q(p.bestBle))
            sb.append(",\"bestBleRssi\":").append(p.bestBleRssi)
            sb.append(",\"sats\":").append(p.loggedSats)
            sb.append(",\"aircraft\":").append(p.loggedAircraft)
            sb.append(",\"time\":\"").append(iso.format(Date(p.t))).append("\"")
            sb.append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
            sb.append(fmt(p.lon)).append(',').append(fmt(p.lat)).append("]}}")
        }
        sb.append("]}")
        return sb.toString()
    }

    fun exportCsv(session: WardriveSession): String {
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.append("lat,lon,time,score,bin,bin_label,wifi_count,ble_count,best_wifi,best_wifi_rssi,best_ble,best_ble_rssi,sats,aircraft,accuracy_m\n")
        for (p in session.points) {
            val bin = SignalScale.binOfScore(p.score)
            sb.append(fmt(p.lat)).append(',')
                .append(fmt(p.lon)).append(',')
                .append(iso.format(Date(p.t))).append(',')
                .append(p.score).append(',')
                .append(bin).append(',')
                .append(q(SignalScale.binLabels[bin])).append(',')
                .append(p.wifiCount).append(',')
                .append(p.bleCount).append(',')
                .append(q(p.bestWifi)).append(',')
                .append(p.bestWifiRssi).append(',')
                .append(q(p.bestBle)).append(',')
                .append(p.bestBleRssi).append(',')
                .append(p.loggedSats).append(',')
                .append(p.loggedAircraft).append(',')
                .append(String.format(Locale.US, "%.1f", p.accuracyM)).append('\n')
        }
        return sb.toString()
    }

    fun exportLoggedCsv(): String {
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.append("layer,id,name,lat,lon,first_seen,last_seen,sightings\n")
        for (e in logged.all()) {
            sb.append(e.layer.name).append(',')
                .append(q(e.key.substringAfter(':'))).append(',')
                .append(q(e.name)).append(',')
                .append(fmt(e.lat)).append(',')
                .append(fmt(e.lon)).append(',')
                .append(iso.format(Date(e.firstSeen))).append(',')
                .append(iso.format(Date(e.lastSeen))).append(',')
                .append(e.sightings).append('\n')
        }
        return sb.toString()
    }

    private fun defaultName(ts: Long): String =
        "Drive " + SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(ts))

    private fun q(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.6f", v)
}
