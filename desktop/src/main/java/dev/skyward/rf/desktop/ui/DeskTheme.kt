package dev.skyward.rf.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * The app's palette, on a desktop-shaped canvas.
 *
 * The phone is dark because it lives on a windscreen; the desk is dark for the same reason a
 * command console is - the operator stares at it for hours and the RF colours have to be the
 * only colours that mean anything. The accent is the same mint the ESP32 layer uses in the app,
 * so a board reads as the same board in both places.
 */
object Desk {
    val bg = Color(0xFF0B0F14)
    val panel = Color(0xFF121821)
    val panelHi = Color(0xFF18202B)
    val line = Color(0xFF22303D)
    val text = Color(0xFFE6EDF3)
    val dim = Color(0xFF8B98A5)
    val accent = Color(0xFF5BE49B)
    val violet = Color(0xFFC084FC)
    val warn = Color(0xFFFDAE61)
    val bad = Color(0xFFD73027)

    val mono: FontFamily = FontFamily.Monospace
}

@Composable
fun DeskTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Desk.accent,
            onPrimary = Desk.bg,
            background = Desk.bg,
            onBackground = Desk.text,
            surface = Desk.panel,
            onSurface = Desk.text,
            surfaceVariant = Desk.panelHi,
            onSurfaceVariant = Desk.dim,
            outline = Desk.line,
            error = Desk.bad,
        ),
        typography = Typography(),
        content = content,
    )
}
