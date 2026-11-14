package dev.skyward.rf.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.skyward.rf.desktop.DeskSettings
import dev.skyward.rf.desktop.hardware.SensorHost
import dev.skyward.rf.desktop.peer.DeskPeer
import dev.skyward.rf.desktop.wardrive.DeskDrives
import dev.skyward.rf.desktop.session.SessionRecorder
import dev.skyward.rf.desktop.sky.DeskSky
import dev.skyward.rf.desktop.ui.CommandCenter
import dev.skyward.rf.desktop.ui.DeskTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.awt.Dimension
import java.io.File

/**
 * The desk host.
 *
 * It is the main console for the sensors and for the sky: the boards are plugged in here, the
 * globe runs on the same element sets and the same aircraft feed as the phone, and the session
 * is recorded here. The handset is what you carry when you leave the desk - both ends read one
 * protocol and one sky, because the parser, the plan model, the orbit maths and the feeds all
 * live in `:shared`.
 */
fun main() = application {
    val recorder = remember { SessionRecorder() }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val home = remember { File(System.getProperty("user.home"), ".signal-intelgo") }
    val sky = remember { DeskSky(scope, home) }
    val settings = remember { DeskSettings(home) }
    // Drives sent over the link are kept beside the desk's own data, one file per drive.
    val drives = remember { DeskDrives(home) }
    // The phone's side of the link, pulled here: off until Setup says otherwise, as on the phone.
    val peer = remember { DeskPeer(scope, drives) }
    val host = remember {
        SensorHost(scope) { board ->
            // A board's own fix goes on its rows; a board without one records blank coordinates
            // rather than borrowing the desk's position, which no radio measured.
            recorder.recordSweep(board.path, board.chip, board.sweep, board.reading)
        }
    }

    LaunchedEffect(Unit) { sky.start() }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Signal IntelGo · Command Center",
        // A command centre opens maximised: the globe wants every pixel, and a fixed size put
        // the session bar below the screen edge on a 864-pixel-tall display.
        state = rememberWindowState(placement = WindowPlacement.Maximized),
    ) {
        // The panes are laid out in fixed-width columns; below this the globe loses its room and
        // the telemetry panel rides over the map, so the window is not allowed to get smaller.
        LaunchedEffect(Unit) { window.minimumSize = Dimension(1100, 700) }
        DeskTheme {
            val cfg by settings.state.collectAsState()
            LaunchedEffect(cfg.peerEnabled) {
                if (cfg.peerEnabled) peer.start() else peer.stop()
            }
            CommandCenter(host, recorder, sky, settings, peer)
        }
    }
}
