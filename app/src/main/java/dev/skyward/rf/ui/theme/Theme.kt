package dev.skyward.rf.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Sky {
    val bg = Color(0xFF05070C)
    val surface = Color(0xFF0B111B)
    val surfaceHigh = Color(0xFF121A27)
    val outline = Color(0xFF243044)
    val text = Color(0xFFE8EEF7)
    val textDim = Color(0xFF8FA3BF)
    val cyan = Color(0xFF22D3EE)
    val satellite = Color(0xFF38BDF8)
    val aircraft = Color(0xFFF8FAFC)
    val drone = Color(0xFFF472B6)
    val mesh = Color(0xFF34D399)
    val esp32 = Color(0xFF5BE49B)
    val logged = Color(0xFFC084FC)
    val warn = Color(0xFFFB923C)
    val danger = Color(0xFFF87171)

    /** Surveillance watch: one colour per family, so a hit names itself before it is tapped. */
    val watchFlock = Color(0xFFFB7185)
    val watchAxon = Color(0xFF60A5FA)
    val watchTaser = Color(0xFFFBBF24)
    val watchTracker = Color(0xFFA78BFA)
}

private val scheme = darkColorScheme(
    primary = Sky.cyan,
    onPrimary = Color(0xFF04141A),
    primaryContainer = Color(0xFF0E3A45),
    onPrimaryContainer = Sky.cyan,
    secondary = Sky.mesh,
    onSecondary = Color(0xFF04140D),
    tertiary = Sky.logged,
    background = Sky.bg,
    onBackground = Sky.text,
    surface = Sky.surface,
    onSurface = Sky.text,
    surfaceVariant = Sky.surfaceHigh,
    onSurfaceVariant = Sky.textDim,
    outline = Sky.outline,
    error = Sky.danger,
)

val Mono = FontFamily.Monospace

private val typography = Typography(
    displaySmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 30.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 21.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 1.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontSize = 10.sp, letterSpacing = 0.8.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.6.sp),
)

@Composable
fun SkywardTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
