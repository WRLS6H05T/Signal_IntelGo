package dev.skyward.rf.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.skyward.rf.core.FileStore
import dev.skyward.rf.core.SettingsStore
import dev.skyward.rf.desktop.ble.BleBridge
import dev.skyward.rf.desktop.esp.EspFlash
import dev.skyward.rf.desktop.host.DeviceHost
import dev.skyward.rf.desktop.host.DriveFeed
import dev.skyward.rf.desktop.host.HostSettings
import dev.skyward.rf.wardrive.LoggedRegistry
import dev.skyward.rf.wardrive.SignalLog
import dev.skyward.rf.wardrive.WardriveEngine
import java.io.File
import dev.skyward.rf.desktop.sky.DesktopSky
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import dev.skyward.rf.desktop.ui.DesktopApp
import dev.skyward.rf.desktop.ui.TileStore
import dev.skyward.rf.desktop.ui.Ink
import dev.skyward.rf.desktop.ui.Panel
import dev.skyward.rf.desktop.ui.Sky
import dev.skyward.rf.desktop.ui.TextMain
import dev.skyward.rf.desktop.ui.WorldLand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * The app's wiring point on the desktop, in the shape the phone uses: one long-lived container
 * holding the host link and the sky. There is no account and no sign-in: the globe is the
 * screen, and hosting is a tab.
 */
class DesktopContainer {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** What this desktop pushes to every linked device; [HostSettings.rev] moves on each change. */
    val settings = MutableStateFlow(HostSettings())

    val bridge = BleBridge()

    val host = DeviceHost(bridge, scope, settings)

    /** One HTTP client for the tiles and the element sets: same pool, same timeouts. */
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val sky = DesktopSky(scope, http)

    val tiles = TileStore(http, scope)

    /** Everything the desk keeps on disk, in the phone's own file layout. */
    val store = FileStore(File(System.getProperty("user.home"), "signintelgo"))

    /** The phone's settings - units, globe spin and the rest - honoured by these screens. */
    val settingsStore = SettingsStore(store)

    val logged = LoggedRegistry(store)

    val signals = SignalLog(store)

    val wardrive = WardriveEngine(store, logged)

    /** Serial ports: the console and the flasher for a board on the desk's USB. */
    val esp = EspFlash()

    private val driveFeed = DriveFeed(host, wardrive, signals, scope)

    /** The bundled coastlines, read once by the build's copy of the phone's asset. */
    val land: Array<FloatArray> by lazy { WorldLand.load() }

    fun start() {
        host.start()
        driveFeed.start()
        // A device on the link with a fix is the observer, exactly as a board stands in for a
        // handset with no lock of its own.
        sky.start { host.state.value.devices.firstNotNullOfOrNull { it.fix } }
    }

    fun close() {
        runCatching { host.stop() }
        scope.cancel()
    }

    /** Changes one of the shared settings and bumps the revision the phones apply once. */
    fun update(change: (HostSettings) -> HostSettings) {
        val current = settings.value
        settings.value = change(current).copy(rev = current.rev + 1)
    }
}

/**
 * Proves the parts a windowless machine can prove: the BLE bridge reached an adapter, the
 * coastline asset parsed, and the catalogue produced positions.
 */
object SelfTest {

    fun run(): Int = runBlocking {
        val container = DesktopContainer()
        container.start()
        // Element sets are a network fetch; give it a few seconds before judging it.
        repeat(24) {
            if (container.sky.state.value.sats.isNotEmpty()) return@repeat
            delay(1_000)
        }
        val host = container.host.state.value
        val sky = container.sky.state.value
        println("bridge=${BleBridge.defaultScript().absolutePath}")
        println("adapter_ok=${host.adapterOk}")
        println("detail=${host.adapterDetail}")
        println("devices=${host.devices.size}")
        println("land_rings=${container.land.size}")
        println("sats=${sky.sats.size}")
        println("tle=${sky.detail}")
        container.close()
        if (host.adapterOk && sky.sats.isNotEmpty() && container.land.isNotEmpty()) 0 else 1
    }
}

fun main(args: Array<String>) {
    if (args.contains("--selftest")) exitProcess(SelfTest.run())

    val container = DesktopContainer()
    container.start()

    application {
        val windowState = rememberWindowState(width = 1280.dp, height = 820.dp)
        Window(
            onCloseRequest = {
                container.close()
                exitApplication()
            },
            title = "Signal_IntelGo",
            state = windowState,
        ) {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Sky,
                    onPrimary = Ink,
                    background = Ink,
                    surface = Panel,
                    onSurface = TextMain,
                ),
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
                    DesktopApp(container)
                }
            }
        }
    }
}
