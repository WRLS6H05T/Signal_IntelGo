package dev.skyward.rf.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.skyward.rf.Container
import dev.skyward.rf.ui.theme.Sky

/**
 * The Watcher's status box, drawn directly under the clock in the map's top-right corner.
 *
 * It is the microphone's switch: the Watcher only listens between a tap here and the end of the
 * command that follows, so this box is the whole interface for the voice. It shows the state and
 * the last phrase heard, and asking for the microphone permission happens on the tap that needs
 * it rather than at launch.
 */
@Composable
fun WatcherBox(c: Container) {
    val state by c.voice.state.collectAsState()
    val context = LocalContext.current
    val mic = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) c.voice.wake()
    }

    val dot = when {
        state.speaking -> Sky.logged
        state.listening -> Sky.mesh
        else -> Sky.textDim
    }
    val line = when {
        state.speaking -> "speaking"
        state.listening -> "listening"
        state.note.isNotBlank() -> state.note
        state.lastHeard.isNotBlank() -> state.lastHeard.take(26)
        else -> "tap to talk"
    }

    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Sky.surface.copy(alpha = 0.95f))
            .border(1.dp, Sky.outline, RoundedCornerShape(10.dp))
            .clickable {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                if (granted) c.voice.wake() else mic.launch(Manifest.permission.RECORD_AUDIO)
            }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dot),
            )
            Spacer(Modifier.width(6.dp))
            Text("WATCHER", color = Sky.cyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Text(line, color = Sky.textDim, fontSize = 10.sp, maxLines = 1)
    }
}
