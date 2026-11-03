package dev.skyward.rf

import android.content.Context
import dev.skyward.rf.core.FileStore
import dev.skyward.rf.core.LatLon
import dev.skyward.rf.core.SettingsStore
import dev.skyward.rf.location.LocationTracker
import dev.skyward.rf.scan.CellScanner
import dev.skyward.rf.scan.RfScanner
import dev.skyward.rf.scan.UsbBoards
import dev.skyward.rf.sky.AircraftSource
import dev.skyward.rf.sky.AirspaceEngine
import dev.skyward.rf.sky.Esp32Source
import dev.skyward.rf.sky.MeshSource
import dev.skyward.rf.sky.SatTracker
import dev.skyward.rf.wardrive.LoggedRegistry
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

    private val rootData = File(appContext.filesDir, "data").apply { mkdirs() }

    val settingsStore = SettingsStore(FileStore(rootData))
    val settings get() = settingsStore.current

    val logged = LoggedRegistry(FileStore(File(rootData, "logged").apply { mkdirs() }))
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
        logRadiusKm = { settingsStore.current.logRadiusKm },
    )

    @Volatile
    private var running = false

    /** The last real handset fix, so board GPS is only ever a stand-in for it. */
    @Volatile
    private var phoneFix: LatLon? = null

    fun start() {
        if (running) return
        running = true

        location.start()
        rf.startAll()
        mesh.start()
        esp32.start()
        airspace.start()

        // Feed fixes to the observer and, when recording, to the wardrive engine.
        scope.launch {
            location.fix.collect { fix ->
                if (fix == null) return@collect
                phoneFix = LatLon(fix.lat, fix.lon)
                _observer.value = LatLon(fix.lat, fix.lon)
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
            }
        }
    }

    fun stopScanning() {
        rf.stopAll()
    }

    fun shutdown() {
        logged.save()
        airspace.stop()
        mesh.stop()
        esp32.stop()
        rf.stopAll()
        location.stop()
        running = false
    }
}
