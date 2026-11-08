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
import dev.skyward.rf.core.DeviceWatch
import dev.skyward.rf.core.SettingsStore
import dev.skyward.rf.core.WatchView
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
/**
 * Satellites are logged out to a fixed 150 km of ground distance, not a user-set radius: a
 * pass is over in minutes, the point of the sky view is what is actually overhead, and a
 * larger number catches most of the catalogue rather than anything you could work. Once
 * logged, a satellite stays an entity for good.
 */
private const val SAT_RANGE_KM = 150.0

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
) {

    data class SkyState(
        val sats: List<SatView> = emptyList(),
        val aircraft: List<AircraftView> = emptyList(),
        val drones: List<DroneView> = emptyList(),
        val mesh: List<MeshNode> = emptyList(),
        val cells: List<CellView> = emptyList(),
        val esp32: List<Esp32Node> = emptyList(),
        val watch: List<WatchView> = emptyList(),
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
        val loggedWatch: Int get() = watch.count { it.logged }
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
            // Offline mode never asks: it draws whatever sets are already on the phone.
            if (!cfg.offlineMode && now - lastTleRefresh > 15 * 60_000L) {
                lastTleRefresh = now
                // Always keep the station group: the ISS is the one object everyone looks for.
                sat.refresh((cfg.satGroups + "stations").distinct())
            }

            // A Starlink shell is thousands of element sets. Propagating all of them at the
            // interval meant for a few hundred objects is what would make the globe crawl,
            // so the sky tick stretches while the catalogue is that large.
            val satMs = if (sat.cachedCount() > 2_000) maxOf(cfg.refreshSatMs, 20_000L) else cfg.refreshSatMs
            if (now - lastSat >= satMs) {
                lastSat = now
                val sats = sat.positions(obs, now, gpsOnly = cfg.offlineMode).map { s ->
                    s.copy(logged = logged.contains(Layer.SATELLITE, s.noradId))
                }
                // Inside 150 miles is an entity from the moment it is up, whether or not a drive
                // is recording, and nothing ever removes it: the registry keeps the sighting, so
                // a satellite once caught stays marked after it has moved away.
                if (obs != null) {
                    val overhead = sats.filter {
                        OrbitMath.groundDistanceKm(obs.lat, obs.lon, it.lat, it.lon) <= SAT_RANGE_KM
                    }
                    if (overhead.isNotEmpty()) {
                        logged.logBatch(
                            System.currentTimeMillis(),
                            overhead.map { s ->
                                LoggedRegistry.Sighting(Layer.SATELLITE, s.noradId, s.name, s.lat, s.lon)
                            },
                        )
                    }
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
            if (!cfg.offlineMode && cfg.aircraftEnabled && obs != null) {
                // One fixed ring: 30 miles, the traffic that is actually overhead.
                val result = aircraftSource.fetch(obs, cfg.aircraftEndpoint)
                val merged = mergeAircraft(result.aircraft, 400)
                // Every contact heard inside the ring becomes an entity straight away, not
                // only while a drive is recording. That is what keeps it on the map, haloed
                // as logged, after it has flown back out of range.
                val heard = merged.filter { it.distanceKm <= AircraftSource.RANGE_KM }
                if (heard.isNotEmpty()) {
                    logged.logBatch(
                        System.currentTimeMillis(),
                        heard.map { a ->
                            LoggedRegistry.Sighting(
                                Layer.AIRCRAFT,
                                a.hex,
                                a.callSign.ifBlank { a.reg.ifBlank { a.hex } },
                                a.lat,
                                a.lon,
                            )
                        },
                    )
                }
                _state.value = _state.value.copy(aircraft = merged, acStatus = result.status)
            } else {
                // Off means gone, not frozen. Leaving the last snapshot in state is what kept
                // aeroplanes on the map with the layer switched off: nothing ever removed them.
                aircraftMemory.clear()
                _state.value = _state.value.copy(
                    aircraft = emptyList(),
                    acStatus = FeedStatus(
                        false,
                        if (cfg.offlineMode) "offline mode" else "aircraft off",
                        System.currentTimeMillis(),
                        0,
                    ),
                )
            }
            val interval = cfg.refreshAircraftMs.coerceAtLeast(5_000)
            delay(interval)
        }
    }

    /**
     * Fresh contacts replace their old entry in place; nothing is removed for leaving the ring.
     * Only what was heard inside 30 miles enters the table, and once an aircraft is in it, it
     * stays on the map at its last known position - a contact that crosses the boundary, or
     * that the feed misses between polls, must not blink out of the picture.
     *
     * The cap is the only thing that ever removes anything, so a long session cannot grow
     * without bound.
     */
    private fun mergeAircraft(fresh: List<AircraftView>, cap: Int): List<AircraftView> {
        fresh.filter { it.distanceKm <= AircraftSource.RANGE_KM }
            .forEach { aircraftMemory[it.hex] = it }
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
        // Offline is a picture of the sky, not of the street: only the GPS constellation reaches
        // the globe, so nothing else is published for the map to draw or count.
        if (settings.current.offlineMode) {
            _state.value = _state.value.copy(
                drones = emptyList(),
                mesh = emptyList(),
                cells = emptyList(),
                esp32 = emptyList(),
                watch = emptyList(),
                observer = observer.value,
                lastTick = now,
            )
            return
        }
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
            // A node is its id, not the position it last happened to send. After a restart the
            // first packet from a node is usually a nodeinfo or an advert with no location, and
            // the place this phone already found it belongs to the same node - so a node the
            // registry holds is placed where the registry has it instead of dropping off the map
            // until it shares a position again.
            val known = if (n.lat == null || n.lon == null) logged.find(Layer.MESH, n.id) else null
            val lat = n.lat ?: known?.lat
            val lon = n.lon ?: known?.lon
            n.copy(
                lat = lat,
                lon = lon,
                distanceKm = n.distanceKm ?: if (lat != null && lon != null && obs != null) {
                    OrbitMath.groundDistanceKm(obs.lat, obs.lon, lat, lon)
                } else {
                    null
                },
                logged = logged.contains(Layer.MESH, n.id),
            )
        }.sortedByDescending { it.lastSeen }

        // A node that shares a position becomes an entity the moment it is heard, not only
        // while a drive is recording: the registry is on disk, so the node is still on the map
        // after the feed stops or the app restarts, the way a satellite or a plane already is.
        // Only ids the registry does not hold are written - putting the whole table back every
        // two seconds is the republish that made a long session get heavier.
        val unheard = meshNodes.mapNotNull { n ->
            val lat = n.lat ?: return@mapNotNull null
            val lon = n.lon ?: return@mapNotNull null
            if (logged.contains(Layer.MESH, n.id)) return@mapNotNull null
            LoggedRegistry.Sighting(Layer.MESH, n.id, n.name.ifBlank { n.id }, lat, lon)
        }
        if (unheard.isNotEmpty()) logged.logBatch(now, unheard)

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

        // Surveillance hardware: Flock ALPR cameras, Axon body cameras and TASERs, matched from
        // the names and manufacturer ids the radios already carry. A radio carries no bearing,
        // so a hit is pinned at this phone's fix - where it was heard from - with a small stable
        // offset so several do not stack into one dot.
        val placed = if (obs == null) {
            emptyList()
        } else {
            rf.watchHits(now).map { h ->
                val (wLat, wLon) = DeviceWatch.place(h.id, obs.lat, obs.lon)
                WatchView(
                    kind = h.kind,
                    id = h.id,
                    name = h.name.ifBlank { h.id },
                    radio = h.radio,
                    rssi = h.rssi,
                    source = h.source,
                    lat = wLat,
                    lon = wLon,
                    score = Geo.rssiToScore(h.rssi),
                    lastSeen = h.at,
                    confidence = h.confidence,
                )
            }
        }
        // A camera does not move, so it is written the first time it is heard rather than only
        // while a drive is recording. The registry write is skipped for anything already known,
        // or this would republish the whole table every two seconds.
        if (obs != null) {
            val fresh = placed.filterNot { logged.contains(Layer.WATCH, watchKey(it)) }
            if (fresh.isNotEmpty()) {
                logged.logBatch(
                    now,
                    fresh.map {
                        LoggedRegistry.Sighting(Layer.WATCH, watchKey(it), it.name, it.lat, it.lon)
                    },
                )
            }
        }
        val watch = placed.map { it.copy(logged = logged.contains(Layer.WATCH, watchKey(it))) }

        _rf.value = snap
        _state.value = _state.value.copy(
            drones = drones,
            mesh = meshNodes,
            cells = cells,
            esp32 = boards,
            watch = watch,
            observer = obs,
            lastTick = now,
        )
    }

    private fun watchKey(w: WatchView): String = "${w.kind.name}:${w.id}"

    /** Marks everything currently in range as logged for this drive. */
    private fun capture(now: Long) {
        val st = _state.value
        // Collected then written in one batch: logging entity by entity republished - and
        // re-allocated - the whole registry once per sighting, every tick, on a table that
        // keeps growing. That cost is what made a long session slow down.
        val batch = ArrayList<LoggedRegistry.Sighting>(st.sats.size + 64)
        var sats = 0
        for (s in st.sats) {
            // The same fixed ring the engine marks overhead, so the number on screen and the
            // number in the registry cannot disagree.
            if (s.elevation < 0 || s.rangeKm > SAT_RANGE_KM) continue
            batch.add(LoggedRegistry.Sighting(Layer.SATELLITE, s.noradId, s.name, s.lat, s.lon))
            sats++
        }
        var aircraft = 0
        for (a in st.aircraft) {
            // The same ring the map draws: only traffic inside it is captured.
            if (a.distanceKm <= AircraftSource.RANGE_KM) {
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
