package dev.skyward.rf.ui

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyward.rf.Container
import dev.skyward.rf.ui.theme.Sky

/**
 * The Watcher's status box, drawn directly under the clock in the map's top-right corner.
 *
 * It is deliberately the same width as the clock above it and the same surface: the corner then
 * answers both "what time is it" and "is it listening" without a control floating over the map.
 * It shows the state (listening, awake, speaking) and the last phrase heard, and a tap wakes it
 * without saying the name.
 */
@Composable
fun WatcherBox(c: Container) {
    val state by c.voice.state.collectAsState()

    val dot = when {
        state.speaking -> Sky.logged
        state.awake -> Sky.cyan
        state.listening -> Sky.mesh
        else -> Sky.textDim
    }
    val line = when {
        state.note.isNotBlank() -> state.note
        state.lastHeard.isNotBlank() -> state.lastHeard.take(26)
        state.awake -> "awake"
        state.listening -> "listening"
        else -> "standby"
    }

    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Sky.surface.copy(alpha = 0.95f))
            .border(1.dp, Sky.outline, RoundedCornerShape(10.dp))
            .clickable { c.voice.wake() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(dot),
            )
            Spacer(Modifier.width(5.dp))
            Text("WATCHER", color = Sky.cyan, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
        Text(line, color = Sky.textDim, fontSize = 8.sp, maxLines = 1)
    }
}
