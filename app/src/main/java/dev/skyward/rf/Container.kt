package dev.skyward.rf

import android.content.Context
import dev.skyward.rf.core.FileStore
import dev.skyward.rf.core.LatLon
import dev.skyward.rf.core.RfKind
import dev.skyward.rf.core.RfObservation
import dev.skyward.rf.core.SettingsStore
import dev.skyward.rf.location.LocationTracker
import dev.skyward.rf.net.WeatherFeed
import dev.skyward.rf.net.WigleApi
import dev.skyward.rf.peer.BlePeer
import dev.skyward.rf.peer.PeerProtocol
import dev.skyward.rf.scan.CellScanner
import dev.skyward.rf.scan.RfScanner
import dev.skyward.rf.scan.UsbBoards
import dev.skyward.rf.sky.AircraftSource
import dev.skyward.rf.sky.AirspaceEngine
import dev.skyward.rf.sky.Esp32Source
import dev.skyward.rf.sky.MeshSource
import dev.skyward.rf.sky.SatTracker
import dev.skyward.rf.wardrive.LoggedRegistry
import dev.skyward.rf.wardrive.SignalLog
import dev.skyward.rf.wardrive.WardriveEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Single wiring point for the whole app. Everything is long-lived, so it hangs off the
 * Application rather than a ViewModel.
 */
class Container(context: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val appContext = context.applicationContext

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Where the intel lives: a plain folder on shared storage, not app-private space.
     *
     * A PC that mounts the phone sees it as Android/data/dev.skyward.rf/files/data, so moving to
     * another phone is a copy: the folder off, the folder back on. No account, and nothing that
     * has to be running for it to work. The private folder remains only as the fallback for a
     * phone with no shared storage mounted; on the first run after this move the old one is
     * copied across so nothing already logged is left behind.
     */
    private val rootData: File = run {
        val visible = appContext.getExternalFilesDir(null)?.let { File(it, "data") }
        val legacy = File(appContext.filesDir, "data")
        val target = visible ?: legacy
        target.mkdirs()
        if (target != legacy) adopt(legacy, target)
        target
    }

    /** The folder to copy, as the phone reports its own path. */
    val dataFolderPath: String get() = rootData.absolutePath

    val settingsStore = SettingsStore(FileStore(rootData))
    val settings get() = settingsStore.current

    val logged = LoggedRegistry(FileStore(File(rootData, "logged").apply { mkdirs() }))

    /** Every signal heard with a fix, placed where it was heard and coloured by strength. */
    val signalLog = SignalLog(FileStore(File(rootData, "signals").apply { mkdirs() }))
    val wardrive = WardriveEngine(
        store = FileStore(File(rootData, "wardrives").apply { mkdirs() }),
        logged = logged,
    )

    val rf = RfScanner(appContext)
    val cell = CellScanner(appContext)
    val location = LocationTracker(appContext)

    val sat = SatTracker(
        store = FileStore(File(rootData, "tle").apply { mkdirs() }),
        http = http,
    )
    val aircraft = AircraftSource(http)
    val mesh = MeshSource { settingsStore.current }
    val esp32Usb = UsbBoards(appContext)
    val esp32 = Esp32Source({ settingsStore.current }, esp32Usb)

    /**
     * The second phone on the same network. It stays off until asked for: it opens a TCP port
     * and a UDP discovery port, which is not something an app should do uninvited.
     */
    val peer = BlePeer(appContext)

    /** The drives the other phone says it has, as last offered over the link. */
    val peerLogs = MutableStateFlow<List<PeerProtocol.LogInfo>>(emptyList())

    /** Uploads, lists and searches the user's own WiGLE account. */
    val wigle = WigleApi(http)

    /** Current conditions at the fix, keyless via Open-Meteo; only asked while the clock is on. */
    val weather = WeatherFeed(http)

    /** KML pulled back from WiGLE, kept beside the rest of the data so a PC can copy it too. */
    val wigleDir = File(rootData, "wigle").apply { mkdirs() }


    init {
        // A wired board is a second radio: what it sweeps joins the handset's Wi-Fi/BLE tables,
        // so its networks reach the layer counts, the map and the drive record.
        esp32.onSweep = { sweep ->
            rf.ingestSweep(sweep)
            // A wired board is a second radio on the same dashboard: its networks are
            // logged where the board is, which is the observer when the handset has no fix.
            val here = _observer.value
            if (here != null) {
                val now = System.currentTimeMillis()
                signalLog.record(
                    observations = sweep.aps.map {
                        RfObservation(RfKind.WIFI, it.bssid, it.ssid, it.rssi, it.channel, "", 0, now)
                    } + sweep.devices.map {
                        RfObservation(RfKind.BLE, it.address, it.name, it.rssi, 0, "", 2400, now)
                    },
                    lat = here.lat,
                    lon = here.lon,
                    via = "board",
                    now = now,
                )
            }
        }
    }

    private val _observer = MutableStateFlow<LatLon?>(null)
    val observer: StateFlow<LatLon?> = _observer

    val airspace = AirspaceEngine(
        scope = scope,
        settings = settingsStore,
        sat = sat,
        aircraftSource = aircraft,
        mesh = mesh,
        esp32 = esp32,
        rf = rf,
        cell = cell,
        logged = logged,
        observer = observer,
        loggingActive = { wardrive.state.value.active },
    )

    @Volatile
    private var running = false

    /** The last real handset fix, so board GPS is only ever a stand-in for it. */
    @Volatile
    private var phoneFix: LatLon? = null

    init {
        // Two devices on one network share what each of them can see: the other phone's sweeps
        // join this one's RF tables, and its fix stands in while this handset has none of its
        // own. A board on the dash travels with its phone, so when the board is the one holding
        // a lock its fix is what gets sent.
        peer.snapshotProvider = {
            val snap = rf.snapshot(limit = 80)
            val totals = rf.totals()
            BlePeer.Snapshot(
                fix = phoneFix ?: _observer.value,
                sats = 0,
                wifi = snap.wifi.map { PeerProtocol.Ap(it.id, it.name, it.rssi, it.channel) },
                ble = snap.ble.map { PeerProtocol.Device(it.id, it.name, it.rssi) },
                // Sent alongside the sample so both phones can show the same totals.
                wifiTotal = totals.wifi,
                bleTotal = totals.ble,
                // What the other phone is actually using, so its card can say so plainly.
                gpsUsed = (phoneFix ?: _observer.value) != null,
                // Cellular counts as in use once towers are actually being heard.
                cellUsed = cell.count() > 0,
            )
        }
        // Recorded drives travel on request, not with every sweep: a long drive would hold the
        // pipe for a minute and stall the live picture both phones are watching.
        peer.logProvider = {
            wardrive.sessions.value.map {
                PeerProtocol.LogInfo(
                    id = it.id,
                    name = it.name,
                    at = it.startedAt,
                    points = it.points,
                    km = it.km,
                    wifi = it.wifiAps,
                    ble = it.bleDevices,
                    best = it.bestScore,
                )
            }
        }
        peer.logSource = { id -> wardrive.sessionJson(id) }
        peer.onPeerLogs = { list -> peerLogs.value = list }
        peer.onPeerLog = { json, from -> wardrive.importSession(json, from) }

        // The hand-picked role, this phone's assigned sweep, and - on a host - the settings
        // every linked device should be using.
        peer.modeProvider = { settingsStore.current.peerRoleMode }
        peer.scanProvider = { settingsStore.current.scanAssign[dev.skyward.rf.core.ScanPlan.PEER_KEY].orEmpty() }
        peer.settingsProvider = {
            val cfg = settingsStore.current
            if (cfg.peerRoleMode != PeerProtocol.MODE_HOST) {
                null
            } else {
                PeerProtocol.HostSettings(
                    tileSource = cfg.tileSource,
                    dropWeakDots = cfg.dropWeakDots,
                    aircraftEnabled = cfg.aircraftEnabled,
                    satGroups = cfg.satGroups,
                    globeSpin = cfg.globeSpin.coerceIn(0, 2),
                    scanAssign = cfg.scanAssign,
                )
            }
        }
        // A globe view follows its host, and only a globe view does: a host is never rewritten
        // by the phone it is controlling, which is what keeps the two from pushing at each
        // other for the rest of the drive.
        peer.onHostSettings = { hs ->
            val cfg = settingsStore.current
            if (cfg.peerRoleMode == PeerProtocol.MODE_GLOBE && cfg.followHost) {
                val next = cfg.copy(
                    tileSource = hs.tileSource,
                    dropWeakDots = hs.dropWeakDots,
                    aircraftEnabled = hs.aircraftEnabled,
                    satGroups = if (hs.satGroups.isEmpty()) cfg.satGroups else hs.satGroups,
                    globeSpin = hs.globeSpin.coerceIn(0, 2),
                    scanAssign = cfg.scanAssign + hs.scanAssign,
                )
                // The host repeats its blob with every snapshot; writing the file on each one
                // would be a disk write every five seconds for a picture that has not moved.
                if (next != cfg) settingsStore.update { next }
            }
        }

        peer.onRemote = { wifi, ble, fix ->
            rf.ingestRemote(
                wifi = wifi.map { RfScanner.RemoteObs(it.id, it.name, it.rssi, it.ch) },
                ble = ble.map { RfScanner.RemoteObs(it.id, it.name, it.rssi) },
            )
            if (fix != null && phoneFix == null) _observer.value = fix
            if (fix != null) {
                // Logged at the peer's fix, not this phone's: the colour belongs where the
                // other radio actually heard the network.
                val now = System.currentTimeMillis()
                signalLog.record(
                    observations = wifi.map {
                        RfObservation(RfKind.WIFI, it.id, it.name, it.rssi, it.ch, "", 0, now)
                    } + ble.map {
                        RfObservation(RfKind.BLE, it.id, it.name, it.rssi, 0, "", 2400, now)
                    },
                    lat = fix.lat,
                    lon = fix.lon,
                    via = "peer",
                    now = now,
                )
            }
        }
    }

    fun start() {
        if (running) return
        running = true

        location.start()
        rf.startAll()
        mesh.start()
        // The persisted rate has to be on the port before the first probe opens it.
        esp32Usb.setBaud(settingsStore.current.esp32Baud)
        esp32.start()
        if (settingsStore.current.peerEnabled) peer.start()
        airspace.start()

        // Feed fixes to the observer and, when recording, to the wardrive engine.
        scope.launch {
            location.fix.collect { fix ->
                if (fix == null) return@collect
                phoneFix = LatLon(fix.lat, fix.lon)
                _observer.value = LatLon(fix.lat, fix.lon)
                signalLog.record(
                    // Only this handset's own findings: a peer's or a board's networks are
                    // logged at *their* fix, and re-logging them here at this one would move
                    // a signal to a street it was never heard on.
                    observations = rf.snapshot().let { it.wifi + it.ble }.filter { it.source == "local" },
                    lat = fix.lat,
                    lon = fix.lon,
                    via = "local",
                    now = fix.at,
                )
                // The clock's drop-down reads this. The feed throttles itself to one read per
                // ten minutes and one per two kilometres, so a fix per second is harmless.
                if (settingsStore.current.showLiveTime) {
                    launch { runCatching { weather.refresh(fix.lat, fix.lon) } }
                }
                if (wardrive.state.value.active) {
                    wardrive.onFix(
                        lat = fix.lat,
                        lon = fix.lon,
                        accuracyM = fix.accuracyM,
                        now = fix.at,
                        snapshot = rf.snapshot(),
                        counts = airspace.countsSnapshot(),
                    )
                }
            }
        }

        // A board on the dashboard often locks before the handset does, so while the phone has
        // no fix of its own a GPS-equipped ESP32 stands in for it. The moment a real fix
        // arrives it wins, and turning the setting off hands control straight back.
        scope.launch {
            esp32.state.collect { boards ->
                if (phoneFix != null) return@collect
                if (!settingsStore.current.esp32GpsFallback) return@collect
                val best = boards.firstOrNull { it.lat != null && it.lon != null && it.sats >= 4 } ?: return@collect
                _observer.value = LatLon(best.lat!!, best.lon!!)
            }
        }

        // Persist the logged registry periodically while the app runs.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                logged.save()
                signalLog.save()
            }
        }
    }

    /** Writes a downloaded WiGLE file into the data folder and reports where it landed. */
    fun saveWigleFile(fileName: String, text: String): String {
        val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "wigle.kml" }
        val file = File(wigleDir, safe)
        runCatching { file.writeText(text) }
        return file.absolutePath
    }

    fun stopScanning() {
        rf.stopAll()
    }

    /**
     * One-time move of the old private folder into the visible one. It never overwrites: a
     * folder that already holds files is the one in use.
     */
    private fun adopt(from: File, to: File) {
        if (!from.isDirectory) return
        if (to.listFiles()?.isNotEmpty() == true) return
        runCatching { from.copyRecursively(to, overwrite = false) }
    }

    fun shutdown() {
        logged.save()
        signalLog.save()
        airspace.stop()
        mesh.stop()
        esp32.stop()
        peer.stop()
        rf.stopAll()
        location.stop()
        running = false
    }
}
