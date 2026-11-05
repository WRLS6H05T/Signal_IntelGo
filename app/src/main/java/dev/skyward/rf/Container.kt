package dev.skyward.rf

import android.content.Context
import dev.skyward.rf.core.FileStore
import dev.skyward.rf.core.LatLon
import dev.skyward.rf.core.RfKind
import dev.skyward.rf.core.RfObservation
import dev.skyward.rf.core.Watcher
import dev.skyward.rf.core.SettingsStore
import dev.skyward.rf.core.Units
import dev.skyward.rf.core.VoiceStyles
import dev.skyward.rf.voice.WatcherVoice
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
import dev.skyward.rf.scan.EspTerminal
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

    /**
     * Watch hits in the order they appeared, with the moment they did.
     *
     * A timed update has to say what is new since the last one, and a WatchView carries no
     * timestamp of its own, so the sighting is stamped as it joins the list.
     */
    private val recentAlerts =
        java.util.concurrent.CopyOnWriteArrayList<Pair<Long, dev.skyward.rf.core.WatchView>>()

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

    /** The board's console, with a keyboard: same port, human on the other end. */
    val espTerminal = EspTerminal(esp32Usb)

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

    /** Hands-free Watcher: one wake word, the phone's own recogniser and speaker. */
    val voice = WatcherVoice(appContext)

    /** A watch alert is worth hearing once, then not again for a while. */
    private val alertGapMs = 8_000L
    private val alertRepeatMs = 5 * 60_000L

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

    /**
     * The host's settings revision, and the follower's memory of what it has taken.
     *
     * A revision goes up only when one of the shared settings actually changes, so the blob the
     * host repeats with every snapshot can be told apart from a fresh decision.
     */
    private val hostRev = java.util.concurrent.atomic.AtomicInteger(1)
    private var lastHostSig: String? = null
    private var lastHostRev = 0

    /** The drive state the other phone last reported, so a repeat is not a new instruction. */
    @Volatile
    private var lastPeerRecording = false

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
            val boards = rf.boardIntel()
            // The host holds the one table and sends back everything it has, including what
            // the peer posted, so the follower sees the same road. A follower sends only what
            // its own radios heard: echoing the host's rows back would bounce the same devices
            // around the link for ever.
            val host = isHostPhone()
            val wifiRows = if (host) snap.wifi else snap.wifi.filter { it.source == "local" }
            val bleRows = if (host) snap.ble else snap.ble.filter { it.source == "local" }
            BlePeer.Snapshot(
                fix = phoneFix ?: _observer.value,
                sats = 0,
                wifi = wifiRows.map { PeerProtocol.Ap(it.id, it.name, it.rssi, it.channel) },
                ble = bleRows.map { PeerProtocol.Device(it.id, it.name, it.rssi) },
                // Sent alongside the sample so both phones can show the same totals.
                wifiTotal = totals.wifi,
                bleTotal = totals.ble,
                // What the other phone is actually using, so its card can say so plainly.
                gpsUsed = (phoneFix ?: _observer.value) != null,
                // Cellular counts as in use once towers are actually being heard.
                cellUsed = cell.count() > 0,
                // A follower's cable intel, which the host has no way of hearing for itself.
                // The host's own board rows already travel in the merged table above, so they
                // are sent once and only their totals are repeated here.
                boardWifi = if (host) emptyList()
                else boards.wifi.map { PeerProtocol.Ap(it.id, it.name, it.rssi, it.channel) },
                boardBle = if (host) emptyList()
                else boards.ble.map { PeerProtocol.Device(it.id, it.name, it.rssi) },
                boardWifiTotal = boards.wifiTotal,
                boardBleTotal = boards.bleTotal,
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

        // A drive is a shared act: whichever phone starts one, both record the same road. The
        // comparison is against what the other phone last said, not against this phone, so a
        // stop here is not undone by the other phone repeating an older snapshot.
        peer.recordingProvider = {
            val st = wardrive.state.value
            st.active to st.name
        }
        peer.onPeerRecording = { active, name ->
            if (active != lastPeerRecording) {
                lastPeerRecording = active
                val drive = wardrive.state.value
                when {
                    active && !drive.active -> wardrive.start(name.ifBlank { null })
                    !active && drive.active -> wardrive.stop()
                }
            }
        }

        // The other phone's board intel lands on this phone as the other phone's: it reaches
        // the counts, the map and the watch list, but it is not offered back as this phone's
        // own sweep, and the record of it stays with the host that heard it.
        peer.onPeerBoard = { wifi, ble ->
            rf.ingestRemote(
                wifi = wifi.map { RfScanner.RemoteObs(it.id, it.name, it.rssi, it.ch) },
                ble = ble.map { RfScanner.RemoteObs(it.id, it.name, it.rssi) },
                via = "peer",
            )
        }

        // The Watcher is one assistant across the pair: said to either phone, obeyed by both.
        peer.onPeerVoice = { name ->
            runCatching { Watcher.Command.valueOf(name) }.getOrNull()?.let { cmd ->
                runCatching { handleVoice(cmd, fromPeer = true) }
            }
        }

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
                    rev = hostRev.get(),
                    tileSource = cfg.tileSource,
                    dropWeakDots = cfg.dropWeakDots,
                    aircraftEnabled = cfg.aircraftEnabled,
                    satGroups = cfg.satGroups,
                    globeSpin = cfg.globeSpin.coerceIn(0, 2),
                    scanAssign = cfg.scanAssign,
                    showLiveTime = cfg.showLiveTime,
                    clockSeconds = cfg.clockSeconds,
                    distanceUnit = cfg.distanceUnit,
                    tempUnit = cfg.tempUnit,
                )
            }
        }
        // Every phone that is not the host follows it, because the settings that matter while
        // driving - the map, the clock, the units, what each radio sweeps - are one decision,
        // not two. A host is never rewritten by the phones it drives, and taking a blob only
        // once per revision is what lets a change made here stick: the host repeats itself every
        // five seconds, and only a fresh revision from the host wins the value back.
        peer.onHostSettings = { hs ->
            val cfg = settingsStore.current
            val follows = cfg.peerRoleMode != PeerProtocol.MODE_HOST && cfg.followHost
            if (follows && PeerProtocol.shouldApplyHost(hs.rev, lastHostRev)) {
                lastHostRev = hs.rev
                val next = cfg.copy(
                    tileSource = hs.tileSource,
                    dropWeakDots = hs.dropWeakDots,
                    aircraftEnabled = hs.aircraftEnabled,
                    satGroups = if (hs.satGroups.isEmpty()) cfg.satGroups else hs.satGroups,
                    globeSpin = hs.globeSpin.coerceIn(0, 2),
                    scanAssign = cfg.scanAssign + hs.scanAssign,
                    showLiveTime = hs.showLiveTime,
                    clockSeconds = hs.clockSeconds,
                    distanceUnit = hs.distanceUnit,
                    tempUnit = hs.tempUnit,
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

    init {
        voice.onCommand = { cmd -> runCatching { handleVoice(cmd) } }

        // The two switches are the whole lifecycle: voice on opens the microphone, voice off
        // closes it, and the audio switch only silences the replies.
        scope.launch {
            settingsStore.state.collect { cfg ->
                voice.audible = cfg.voiceAudio
                voice.style = VoiceStyles.of(cfg.voiceGender)
                if (cfg.voiceEnabled) voice.start() else voice.stop()
            }
        }

        // GPS is a switch, not a mute: off stops the receiver instead of ignoring its fixes, so
        // a session at a desk does not spend the battery on a chip nothing is reading.
        scope.launch {
            settingsStore.state.collect { cfg ->
                if (!running) return@collect
                if (cfg.gpsEnabled) location.start() else location.stop()
            }
        }

        // The host's revision, bumped only when a shared setting actually moves. A follower
        // reads it to tell "the host just decided this" from "the host is repeating itself".
        scope.launch {
            settingsStore.state.collect { cfg ->
                if (cfg.peerRoleMode != PeerProtocol.MODE_HOST) {
                    lastHostSig = null
                    return@collect
                }
                val sig = listOf(
                    cfg.tileSource,
                    cfg.dropWeakDots,
                    cfg.aircraftEnabled,
                    cfg.satGroups.joinToString(","),
                    cfg.globeSpin,
                    cfg.scanAssign.toSortedMap().toString(),
                    cfg.showLiveTime,
                    cfg.clockSeconds,
                    cfg.distanceUnit,
                    cfg.tempUnit,
                ).joinToString("|")
                if (sig != lastHostSig) {
                    lastHostSig = sig
                    hostRev.incrementAndGet()
                }
            }
        }

        // Watch alerts in the glitched voice: Flock, Axon, TASER and trackers alike, one per
        // device per five minutes and never two inside eight seconds, so a busy street does not
        // turn the drive into a monologue.
        scope.launch {
            val announced = HashMap<String, Long>()
            var lastAlert = 0L
            airspace.state.collect { sky ->
                val cfg = settingsStore.current
                if (!cfg.voiceEnabled || !cfg.voiceAudio) return@collect
                // The host owns the Watcher. Its table already carries what the peer posted,
                // so its alerts cover both radios and the pair speaks once, not twice.
                if (!isHostPhone()) return@collect
                val now = System.currentTimeMillis()
                if (now - lastAlert < alertGapMs) return@collect
                val hit = sky.watch.firstOrNull { w ->
                    now - (announced["${w.kind.name}:${w.id}"] ?: 0L) > alertRepeatMs
                } ?: return@collect
                announced["${hit.kind.name}:${hit.id}"] = now
                lastAlert = now
                voice.speak(Watcher.alert(hit), glitch = true)
            }
        }
    }

    /**
     * Whether this phone is the host of the pair.
     *
     * The host holds the intel and drives the Watcher. It is the phone set to host, or - in the
     * automatic split - the larger screen, which is the device the roles already give the globe.
     * A phone set to globe view is the follower; an unlinked phone hosts itself.
     */
    fun isHostPhone(): Boolean = when (settingsStore.current.peerRoleMode) {
        PeerProtocol.MODE_HOST -> true
        PeerProtocol.MODE_GLOBE -> false
        else -> !peer.isRunning() || peer.role() == PeerProtocol.DISPLAY ||
            (peer.role() == "" && peer.firstOnLink())
    }

    /**
     * Commands that describe what both phones should be doing, not what one should say.
     *
     * Reports and help are answered by the phone that was asked, sleep ends one session, and a
     * drive already travels as shared recording state, so none of those are forwarded.
     */
    private val SHARED_COMMANDS = setOf(
        Watcher.Command.MAP,
        Watcher.Command.DRIVE,
        Watcher.Command.LOG,
        Watcher.Command.SETUP,
        Watcher.Command.OPEN_MENU,
        Watcher.Command.CLOSE_MENU,
        Watcher.Command.MILES,
        Watcher.Command.KILOMETRES,
        Watcher.Command.CELSIUS,
        Watcher.Command.FAHRENHEIT,
        Watcher.Command.MUTE,
        Watcher.Command.UNMUTE,
    )

    /**
     * What a spoken command does. Screens are not changed here - the request goes into
     * [WatcherVoice.nav] and the scaffold acts on it - but everything stateful is: logging,
     * units and the audio switch.
     */
    private fun handleVoice(cmd: Watcher.Command, fromPeer: Boolean = false) {
        // A command that describes what both phones should be doing is passed on, so saying
        // "miles" or "open menu" to one phone does it on the other. Answers and drives are
        // not forwarded: a report is spoken by the phone that was asked, and a drive already
        // travels as shared recording state.
        if (!fromPeer && isHostPhone() && cmd in SHARED_COMMANDS && peer.isRunning()) {
            peer.sendVoice(cmd.name)
        }
        when (cmd) {
            Watcher.Command.REPORT -> voice.speak(voiceReport())
            Watcher.Command.HELP -> voice.speak(Watcher.help())
            Watcher.Command.MAP -> voice.nav.value = Watcher.Nav.Go(Watcher.Tab.MAP)
            Watcher.Command.DRIVE -> voice.nav.value = Watcher.Nav.Go(Watcher.Tab.DRIVE)
            Watcher.Command.LOG -> voice.nav.value = Watcher.Nav.Go(Watcher.Tab.LOG)
            Watcher.Command.SETUP -> voice.nav.value = Watcher.Nav.Go(Watcher.Tab.SETUP)
            Watcher.Command.OPEN_MENU -> voice.nav.value = Watcher.Nav.OpenMenu
            Watcher.Command.CLOSE_MENU -> voice.nav.value = Watcher.Nav.CloseMenu
            Watcher.Command.START ->
                voice.speak(if (wardrive.start()) "Recording." else "Already recording.")
            Watcher.Command.STOP -> {
                val ended = wardrive.stop()
                voice.speak(if (ended == null) "Not recording." else "Stopped.")
            }
            Watcher.Command.MILES -> {
                settingsStore.update { it.copy(distanceUnit = Units.MILES) }
                voice.speak("Miles.")
            }
            Watcher.Command.KILOMETRES -> {
                settingsStore.update { it.copy(distanceUnit = Units.KM) }
                voice.speak("Kilometres.")
            }
            Watcher.Command.CELSIUS -> {
                settingsStore.update { it.copy(tempUnit = Units.CELSIUS) }
                voice.speak("Celsius.")
            }
            Watcher.Command.FAHRENHEIT -> {
                settingsStore.update { it.copy(tempUnit = Units.FAHRENHEIT) }
                voice.speak("Fahrenheit.")
            }
            Watcher.Command.MUTE -> {
                // Said before the switch is thrown, or the confirmation is the first thing
                // muted. The other phone's copy of a forwarded mute stays silent: the phone
                // that heard the words is the one that answers.
                if (!fromPeer) voice.speak("Muting.")
                settingsStore.update { it.copy(voiceAudio = false) }
            }
            Watcher.Command.UNMUTE -> {
                settingsStore.update { it.copy(voiceAudio = true) }
                voice.audible = true
                if (!fromPeer) voice.speak("Audio on.")
            }
            // The recogniser already treats "sleep" as the end of the session; this only
            // matters if the command is ever routed from elsewhere.
            Watcher.Command.SLEEP -> voice.speak("Standing down.")
        }
    }

    /** The intel report, read from the live picture at the moment it is asked for. */
    fun voiceReport(): String {
        val sky = airspace.state.value
        val totals = rf.totals()
        val drive = wardrive.state.value
        return Watcher.intelReport(
            Watcher.ReportInput(
                watches = sky.watch,
                wifi = totals.wifi,
                ble = totals.ble,
                cells = sky.cells.size,
                mesh = sky.mesh.size,
                sats = sky.sats.size,
                aircraft = sky.aircraft.size,
                drones = sky.drones.size,
                gps = observer.value != null,
                logging = drive.active,
                km = drive.km,
                unit = settingsStore.current.distanceUnit,
            ),
        )
    }

    /**
     * The timed intel update, read from the same picture the spoken report uses.
     *
     * The sightings are the watch list as it appeared during the last [minutes], stamped by the
     * collector in [start], so the update can say what is new rather than re-reading the table.
     */
    fun intelUpdate(minutes: Int): String {
        val sky = airspace.state.value
        val totals = rf.totals()
        val drive = wardrive.state.value
        val since = System.currentTimeMillis() - minutes * 60_000L
        return Watcher.intelUpdate(
            Watcher.ReportInput(
                watches = sky.watch,
                wifi = totals.wifi,
                ble = totals.ble,
                cells = sky.cells.size,
                mesh = sky.mesh.size,
                sats = sky.sats.size,
                aircraft = sky.aircraft.size,
                drones = sky.drones.size,
                gps = observer.value != null,
                logging = drive.active,
                km = drive.km,
                unit = settingsStore.current.distanceUnit,
            ),
            minutes,
            sightings = recentAlerts.filter { it.first >= since }.map { it.second },
        )
    }

    fun start() {
        if (running) return
        running = true

        if (settingsStore.current.gpsEnabled) location.start()
        rf.startAll()
        mesh.start()
        // The persisted rate has to be on the port before the first probe opens it.
        esp32Usb.setBaud(settingsStore.current.esp32Baud)
        esp32.start()
        if (settingsStore.current.peerEnabled) peer.start()
        airspace.start()

        // Stamps every watch hit as it appears, so the timed update can report what is new. The
        // list is trimmed to an hour: nothing older than the longest interval is ever asked for.
        scope.launch {
            airspace.state.collect { sky ->
                val now = System.currentTimeMillis()
                val known = recentAlerts.mapTo(HashSet()) { it.second.kind to it.second.id }
                for (w in sky.watch) {
                    if ((w.kind to w.id) !in known) recentAlerts.add(now to w)
                }
                recentAlerts.removeAll { now - it.first > 60 * 60_000L }
            }
        }

        // The timed intel update. The gap is read fresh each round, so a change in Setup takes
        // effect at the end of the current wait rather than needing a restart.
        scope.launch {
            while (true) {
                val minutes = settingsStore.current.intelUpdateMinutes
                if (minutes <= 0 || !settingsStore.current.voiceEnabled) {
                    kotlinx.coroutines.delay(20_000L)
                    continue
                }
                kotlinx.coroutines.delay(minutes * 60_000L)
                // Moved while waiting: that is a new schedule, not this one.
                if (settingsStore.current.intelUpdateMinutes != minutes) continue
                if (!settingsStore.current.voiceEnabled || !isHostPhone()) continue
                voice.speak(intelUpdate(minutes), intel = true)
            }
        }

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
                    val snap = rf.snapshot()
                    wardrive.onFix(
                        lat = fix.lat,
                        lon = fix.lon,
                        accuracyM = fix.accuracyM,
                        now = fix.at,
                        wifi = snap.wifi,
                        ble = snap.ble,
                        droneKeys = snap.drones.keys,
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
        voice.stop()
        rf.stopAll()
        location.stop()
        running = false
    }
}
