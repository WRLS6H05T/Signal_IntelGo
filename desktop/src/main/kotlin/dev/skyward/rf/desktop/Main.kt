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
import dev.skyward.rf.desktop.auth.GoogleAuth
import dev.skyward.rf.desktop.ble.BleBridge
import dev.skyward.rf.desktop.host.DeviceHost
import dev.skyward.rf.desktop.host.HostSettings
import dev.skyward.rf.desktop.ui.DesktopApp
import dev.skyward.rf.desktop.ui.Ink
import dev.skyward.rf.desktop.ui.Panel
import dev.skyward.rf.desktop.ui.Sky
import dev.skyward.rf.desktop.ui.TextMain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Everything the window needs, in the same shape the phone uses: one long-lived container with
 * the host link, the adapter and the account on it.
 */
class DesktopContainer {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** What this desktop pushes to every linked device; [HostSettings.rev] moves on each change. */
    val settings = MutableStateFlow(HostSettings())

    val bridge = BleBridge()

    val host = DeviceHost(bridge, scope, settings)

    val auth = GoogleAuth(scope)

    fun start() {
        host.start()
    }

    fun close() {
        runCatching { host.stop() }
        scope.cancel()
    }

    /** Changes one of the shared settings and bumps the revision the phones apply once. */
    fun update(change: (HostSettings) -> HostSettings) {
        val current = settings.value
        val next = change(current).copy(rev = current.rev + 1)
        settings.value = next
    }
}

/**
 * Checks that the app can reach its own bridge and that the adapter answers, without opening a
 * window. This is what a machine with no radio in it can still prove.
 */
object SelfTest {

    fun run(): Int = runBlocking {
        val container = DesktopContainer()
        container.start()
        delay(7_000)
        val state = container.host.state.value
        val script = BleBridge.defaultScript()
        val clientId = container.auth.clientId()
        println("bridge=${script.absolutePath}")
        println("bridge_exists=${script.exists()}")
        println("adapter_ok=${state.adapterOk}")
        println("detail=${state.adapterDetail}")
        println("devices=${state.devices.size}")
        println("peers=${state.devices.count { it.kind == DeviceHost.Kind.PEER }}")
        println("boards=${state.devices.count { it.kind == DeviceHost.Kind.BOARD }}")
        println("google_client_id=${if (clientId.isBlank()) "missing" else "configured"}")
        println("auth=${container.auth.state.value::class.simpleName}")
        container.close()
        if (state.adapterOk) 0 else 1
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
            title = "Signal_IntelGo - desktop host",
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
