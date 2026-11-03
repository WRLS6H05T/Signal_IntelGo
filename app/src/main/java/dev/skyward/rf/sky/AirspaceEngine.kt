package dev.skyward.rf.sky

import dev.skyward.rf.core.AircraftView
import dev.skyward.rf.core.CellView
import dev.skyward.rf.core.DroneView
import dev.skyward.rf.core.Esp32Node
import dev.skyward.rf.core.FeedStatus
import dev.skyward.rf.core.Geo
import dev.skyward.rf.core.LatLon
import dev.skyward.rf.core.Layer
import dev.skyward.rf.core.MeshNode
import dev.skyward.rf.core.SatView
import dev.skyward.rf.core.SettingsStore
import dev.skyward.rf.location.LocationTracker
import dev.skyward.rf.scan.CellScanner
import dev.skyward.rf.scan.RfScanner
import dev.skyward.rf.wardrive.LoggedCounts
import dev.skyward.rf.wardrive.LoggedRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the live picture: satellite propagation ticks, ADS-B polling, Remote ID + mesh
 * aggregation, and the optional capture of everything seen while a drive is recording.
 */
class AirspaceEngine(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val sat: SatTracker,
    private val aircraftSource: AircraftSource,
    private val mesh: MeshSource,
    private val esp32: Esp32Source,
    private val rf: RfScanner,
    private val cell: CellScanner,
    private val logged: LoggedRegistry,
    private val observer: StateFlow<LatLon?>,
    private val loggingActive: () -> Boolean,
    private val logRadiusKm: () -> Double,
) {

    data class SkyState(
        val sats: List<SatView> = emptyList(),
        val aircraft: List<AircraftView> = emptyList(),
        val drones: List<DroneView> = emptyList(),
        val mesh: List<MeshNode> = emptyList(),
        val cells: List<CellView> = emptyList(),
        val esp32: List<Esp32Node> = emptyList(),
        val satStatus: FeedStatus = FeedStatus(),
        val acStatus: FeedStatus = FeedStatus(),
        val tleCount: Int = 0,
        val tleAgeMs: Long = 0L,
        val lastTick: Long = 0L,
        val observer: LatLon? = null,
    ) {
        val loggedSats: Int get() = sats.count { it.logged }
        val loggedAircraft: Int get() = aircraft.count { it.logged }
        val loggedDrones: Int get() = drones.count { it.logged }
        val loggedMesh: Int get() = mesh.count { it.logged }
        val loggedCells: Int get() = cells.count { it.logged }
        val loggedEsp32: Int get() = esp32.count { it.logged }
    }

    private val _state = MutableStateFlow(SkyState())
    val state: StateFlow<SkyState> = _state

    /** Latest Wi-Fi/BLE table, surfaced to the scan screen. */
    private val _rf = MutableStateFlow(RfScanner.Snapshot(emptyList(), emptyList(), emptyMap()))
    val rfSnapshot: StateFlow<RfScanner.Snapshot> = _rf

    private val _loggedCounts = MutableStateFlow(LoggedCounts())
    val loggedCounts: StateFlow<LoggedCounts> = _loggedCounts

    /**
     * Every aircraft heard this session, keyed by ICAO hex. The feed only reports what is
     * inside the current radius right now, so without this a plane blinks out the moment it
     * leaves coverage or a poll fails - the map felt like it was losing traffic.
     */
    private val aircraftMemory = LinkedHashMap<String, AircraftView>()

    private var tickJob: Job? = null
    private var aircraftJob: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        tickJob = scope.launch(Dispatchers.Default) { tickLoop() }
        aircraftJob = scope.launch(Dispatchers.IO) { aircraftLoop() }
    }

    fun stop() {
        started = false
        tickJob?.cancel()
        aircraftJob?.cancel()
    }

    private suspend fun tickLoop() {
        var lastSat = 0L
        var lastTleRefresh = 0L
        while (scope.isActive && started) {
            val now = System.currentTimeMillis()
            val cfg = settings.current
            val obs = observer.value

            // Element sets: refresh at most every 15 minutes; the tracker caches for 6 h.
            if (now - lastTleRefresh > 15 * 60_000L) {
                lastTleRefresh = now
                // Always keep the station group: the ISS is the one object everyone looks for.
                sat.refresh((cfg.satGroups + "stations").distinct())
            }

            if (now - lastSat >= cfg.refreshSatMs) {
                lastSat = now
                val sats = sat.positions(obs, now).map { s ->
                    s.copy(logged = logged.contains(Layer.SATELLITE, s.noradId))
                }
                val status = FeedStatus(
                    ok = sats.isNotEmpty() || sat.cachedCount() > 0,
                    detail = sat.error() ?: "${sat.cachedCount()} elements",
                    at = now,
                    count = sats.size,
                )
                _state.value = _state.value.copy(
                    sats = sats,
                    satStatus = status,
                    tleCount = sat.cachedCount(),
                    tleAgeMs = sat.cacheAgeMs(now),
                    lastTick = now,
                    observer = obs,
                )
            }

            updateDronesAndMesh(now)
            if (loggingActive()) capture(now)
            delay(2_000L.coerceAtMost(cfg.refreshSatMs))
        }
    }

    private suspend fun aircraftLoop() {
        while (scope.isActive && started) {
            val cfg = settings.current
            val obs = observer.value
            if (cfg.aircraftEnabled && obs != null) {
                // "All aircraft" is global, not a bigger radius: the point query is capped at
                // 250 nm by the API, so a radius can never cover the world.
                val radiusNm = if (cfg.aircraftAll) 250 else cfg.aircraftRadiusNm
                val result = aircraftSource.fetch(obs, radiusNm, cfg.aircraftEndpoint, cfg.aircraftAll)
                // Keep everything a worldwide snapshot contains; a radius poll stays bounded.
                val merged = mergeAircraft(result.aircraft, if (cfg.aircraftAll) 12000 else 400)
                _state.value = _state.value.copy(aircraft = merged, acStatus = result.status)
            } else {
                // Off means gone, not frozen. Leaving the last snapshot in state is what kept
                // aeroplanes on the map with the layer switched off: nothing ever removed them.
                aircraftMemory.clear()
                _state.value = _state.value.copy(
                    aircraft = emptyList(),
                    acStatus = FeedStatus(false, "aircraft off", System.currentTimeMillis(), 0),
                )
            }
            // A global snapshot is megabytes, so it is polled far less often than a radius.
            val interval =
                if (cfg.aircraftAll) 60_000L else cfg.refreshAircraftMs.coerceAtLeast(5_000)
            delay(interval)
        }
    }

    /** Fresh contacts replace their old entry in place; nothing is ever dropped. */
    private fun mergeAircraft(fresh: List<AircraftView>, cap: Int): List<AircraftView> {
        fresh.forEach { aircraftMemory[it.hex] = it }
        // Bound the growth on a long drive: insertion order means the oldest go first.
        if (aircraftMemory.size > cap) {
            aircraftMemory.keys.take(aircraftMemory.size - cap).forEach { aircraftMemory.remove(it) }
        }
        // Distance order matters now: the map plots only the nearest slice of an unbounded
        // worldwide feed, so the list has to be sorted here rather than in the draw loop.
        return aircraftMemory.values
            .sortedBy { it.distanceKm }
            .map { it.copy(logged = logged.contains(Layer.AIRCRAFT, it.hex)) }
    }

    private fun updateDronesAndMesh(now: Long) {
        val snap = rf.snapshot()
        val obs = observer.value

        // Remote ID drones: merge the per-MAC ODID frames with the latest RSSI.
        val droneRssi = snap.ble.associate { it.id to it.rssi }
        val drones = snap.drones.mapNotNull { (mac, frame) ->
            val lat = frame.lat
            val lon = frame.lon
            val rssi = droneRssi[mac] ?: -100
            val distanceKm = if (lat != null && lon != null && obs != null) {
                OrbitMath.groundDistanceKm(obs.lat, obs.lon, lat, lon)
            } else {
                null
            }
            DroneView(
                mac = mac,
                uasId = frame.uasId ?: mac,
                operatorId = if (frame.operatorLat != null && frame.operatorLon != null) {
                    String.format("%.5f, %.5f", frame.operatorLat, frame.operatorLon)
                } else {
                    ""
                },
                lat = lat,
                lon = lon,
                altM = frame.geodeticAltM ?: frame.pressureAltM,
                heightM = frame.heightM,
                speedMs = frame.speedMs,
                headingDeg = frame.headingDeg,
                rssi = rssi,
                lastSeen = now,
                distanceKm = distanceKm,
                score = Geo.rssiToScore(rssi),
                logged = logged.contains(Layer.DRONE, mac),
            )
        }.sortedByDescending { it.score }

        // Mesh: MQTT nodes plus any Meshtastic advertiser heard directly over BLE.
        for (o in snap.ble) {
            if (o.security == "MESH" || o.name.startsWith("Meshtastic", true)) {
                mesh.upsertBleNode(o.id, o.name.ifBlank { o.id }, o.rssi, o.seenAt)
            }
        }
        mesh.updateDistances(obs?.lat, obs?.lon)
        val meshNodes = mesh.nodesFlow.value.values.map { n ->
            n.copy(logged = logged.contains(Layer.MESH, n.id))
        }.sortedByDescending { it.lastSeen }

        // Cell towers: pinned where first heard, refreshed with live signal strength.
        val cells = cell.poll(obs, now).map { c ->
            c.copy(logged = logged.contains(Layer.CELL, c.key))
        }

        // ESP32 sensor boards: adverts heard here, JSON packets from the source's own broker
        // client. A board with a GPS module reports its own fix; one without sits at the phone.
        esp32.updateObserver(obs?.lat, obs?.lon)
        for (a in snap.esp32) esp32.ingestAdvert(a.address, a.advert, a.rssi, now)
        val boards = esp32.snapshot(now).map { b ->
            b.copy(logged = logged.contains(Layer.ESP32, b.id))
        }

        _rf.value = snap
        _state.value = _state.value.copy(
            drones = drones,
            mesh = meshNodes,
            cells = cells,
            esp32 = boards,
            observer = obs,
            lastTick = now,
        )
    }

    /** Marks everything currently in range as logged for this drive. */
    private fun capture(now: Long) {
        val radius = logRadiusKm()
        val st = _state.value
        // Collected then written in one batch: logging entity by entity republished - and
        // re-allocated - the whole registry once per sighting, every tick, on a table that
        // keeps growing. That cost is what made a long session slow down.
        val batch = ArrayList<LoggedRegistry.Sighting>(st.sats.size + 64)
        var sats = 0
        for (s in st.sats) {
            if (s.elevation < 0) continue
            if (s.rangeKm > radius * 4 && s.elevation < 5) continue
            batch.add(LoggedRegistry.Sighting(Layer.SATELLITE, s.noradId, s.name, s.lat, s.lon))
            sats++
        }
        var aircraft = 0
        for (a in st.aircraft) {
            if (a.distanceKm <= radius) {
                batch.add(
                    LoggedRegistry.Sighting(
                        Layer.AIRCRAFT,
                        a.hex,
                        a.callSign.ifBlank { a.reg.ifBlank { a.hex } },
                        a.lat,
                        a.lon,
                    ),
                )
                aircraft++
            }
        }
        val drones = st.drones.size
        st.drones.forEach { d ->
            val lat = d.lat
            val lon = d.lon
            if (lat != null && lon != null) {
                batch.add(LoggedRegistry.Sighting(Layer.DRONE, d.mac, d.uasId, lat, lon))
            }
        }
        val meshCount = st.mesh.size
        st.mesh.forEach { n ->
            val lat = n.lat
            val lon = n.lon
            if (lat != null && lon != null) {
                batch.add(LoggedRegistry.Sighting(Layer.MESH, n.id, n.name, lat, lon))
            }
        }
        st.cells.forEach { c ->
            batch.add(LoggedRegistry.Sighting(Layer.CELL, c.key, "${c.radio} ${c.mcc}-${c.mnc}", c.lat, c.lon))
        }
        st.esp32.forEach { b ->
            val lat = b.lat
            val lon = b.lon
            if (lat != null && lon != null) {
                batch.add(LoggedRegistry.Sighting(Layer.ESP32, b.id, b.name, lat, lon))
            }
        }
        logged.logBatch(now, batch)
        _loggedCounts.value = LoggedCounts(sats = sats, aircraft = aircraft, drones = drones, mesh = meshCount)
    }

    /** Manual "capture the sky" action, useful outside a drive. */
    fun captureNow() {
        capture(System.currentTimeMillis())
        logged.save()
    }

    fun countsSnapshot(): LoggedCounts = _loggedCounts.value
}
