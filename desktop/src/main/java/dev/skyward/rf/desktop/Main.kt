package dev.skyward.rf.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.skyward.rf.desktop.hardware.SensorHost
import dev.skyward.rf.desktop.session.SessionRecorder
import dev.skyward.rf.desktop.ui.CommandCenter
import dev.skyward.rf.desktop.ui.DeskTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The desk host.
 *
 * It is the main console for the sensors: the boards are plugged in here, the session is
 * recorded here, and the phone is what you carry when you leave the desk. Both ends read the
 * same firmware with the same parser (see `:shared`), so an export from either is the same file
 * with the same columns.
 */
fun main() = application {
    val recorder = remember { SessionRecorder() }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val host = remember {
        SensorHost(scope) { board ->
            // A board's own fix goes on its rows; a board without one records blank coordinates
            // rather than borrowing the desk's position, which no radio measured.
            recorder.recordSweep(board.path, board.chip, board.sweep, board.reading)
        }
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Signal IntelGo · Command Center",
        state = rememberWindowState(width = 1440.dp, height = 920.dp),
    ) {
        DeskTheme {
            CommandCenter(host, recorder)
        }
    }
}
