package dev.skyward.rf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyward.rf.Container
import dev.skyward.rf.sdr.SdrCapture
import dev.skyward.rf.sdr.Spectrum
import dev.skyward.rf.ui.theme.Sky
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where the dongle is tuned, in MHz, in the order the panel offers them.
 *
 * These are the bands a handheld SDR is pointed at: 100 MHz broadcast, 145 and 433.92 the two
 * licence-free bands most devices use, 868 and 915 the same job in Europe and the Americas.
 */
private val SdrPresets = listOf(100.0, 145.0, 315.0, 433.92, 868.0, 915.0)

/**
 * The RTL-SDR drop-down, top centre of the map.
 *
 * Nothing is drawn unless the SDR switch is on and a dongle is actually attached: a panel for
 * hardware that is not there would be a permanent invitation to tap something that cannot work.
 * When one is plugged in it drops open by itself, and folds back to one pill when the operator
 * wants the map back.
 */
@Composable
fun SdrPanel(c: Container, modifier: Modifier = Modifier, onSaved: (SdrCapture) -> Unit = {}) {
    val settings by c.settingsStore.state.collectAsState()
    val status by c.sdr.state.collectAsState()
    val bins by c.sdr.spectrum.collectAsState()
    if (!settings.sdrEnabled || !status.attached) return

    var open by remember { mutableStateOf(true) }
    // Plugging the dongle in is the request to look at it.
    LaunchedEffect(status.attached) {
        if (status.attached) open = true
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Sky.surface.copy(alpha = 0.96f))
                .border(1.dp, Sky.sdr.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .clickable { open = !open }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(if (status.streaming) Sky.sdr else Sky.textDim, 6)
            Spacer(Modifier.width(6.dp))
            Text("SDR", style = SkywardThemeLabel(), color = Sky.text, fontSize = 9.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                if (status.recording) {
                    "REC \u00b7 ${status.frames} \u25be"
                } else {
                    String.format(Locale.US, "%.3f MHz \u25be", status.centerMhz)
                },
                color = if (status.recording) Sky.danger else Sky.textDim,
                fontSize = 9.sp,
            )
        }
        if (!open) return@Column
        Spacer(Modifier.height(6.dp))
        SdrBody(c, status, bins, onSaved)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SdrBody(
    c: Container,
    status: dev.skyward.rf.sdr.SdrStream.Status,
    bins: FloatArray,
    onSaved: (SdrCapture) -> Unit = {},
) {
    Column(
        Modifier
            .width(322.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Sky.surface.copy(alpha = 0.97f))
            .border(1.dp, Sky.outline, RoundedCornerShape(14.dp))
            .padding(10.dp),
    ) {
        val span = status.sampleRate / 1_000_000.0
        SpectrumView(bins)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                String.format(Locale.US, "%.3f", status.centerMhz - span / 2),
                color = Sky.textDim,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.weight(1f))
            Text(
                String.format(Locale.US, "SPAN %.1f MHz", span),
                color = Sky.textDim,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.weight(1f))
            Text(
                String.format(Locale.US, "%.3f", status.centerMhz + span / 2),
                color = Sky.textDim,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(4.dp))
        val peakMhz = Spectrum.binToMhz(bins, Spectrum.peakBin(bins), status.centerMhz, span)
        KeyValueRow(
            "Peak",
            String.format(Locale.US, "%.0f dBFS at %.3f MHz", status.peakDb, peakMhz),
            Sky.sdr,
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SdrPresets.forEach { mhz ->
                val chosen = kotlin.math.abs(status.centerMhz - mhz) < 0.001
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (chosen) Sky.sdr.copy(alpha = 0.28f) else Sky.outline.copy(alpha = 0.3f))
                        .clickable {
                            c.settingsStore.update { it.copy(sdrCenterMhz = mhz) }
                            c.sdr.setCentre(mhz)
                        }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    Text(
                        String.format(Locale.US, "%.1f", mhz),
                        color = if (chosen) Sky.sdr else Sky.textDim,
                        fontSize = 9.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val recording = status.recording
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (recording) Sky.outline.copy(alpha = 0.25f) else Sky.danger.copy(alpha = 0.22f),
                    )
                    // No I/Q, nothing to record: a capture of silence would be a pin that says
                    // nothing about the band it was pinned to.
                    .clickable(enabled = !recording && status.streaming) { c.sdr.beginRecording() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    "\u25cf REC",
                    style = SkywardThemeLabel(),
                    color = if (recording) Sky.textDim else Sky.danger,
                )
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (recording) Sky.sdr.copy(alpha = 0.24f) else Sky.outline.copy(alpha = 0.25f),
                    )
                    .clickable(enabled = recording) {
                        // Stopping is what saves: the capture is pinned at the fix it started on.
                        c.sdr.endRecording()?.let { capture -> onSaved(capture) }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    "STOP \u00b7 SAVE",
                    style = SkywardThemeLabel(),
                    color = if (recording) Sky.sdr else Sky.textDim,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (recording) "${status.frames} frames" else "idle",
                color = Sky.textDim,
                fontSize = 9.sp,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            status.detail,
            color = if (status.streaming) Sky.textDim else Sky.warn,
            fontSize = 9.sp,
        )
    }
}

/** The trace itself: bars normalised to the dB floor, with the strongest one marked. */
@Composable
private fun SpectrumView(
    bins: FloatArray,
    modifier: Modifier = Modifier,
    height: Dp = 96.dp,
) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height),
    ) {
        drawRect(Sky.bg.copy(alpha = 0.85f))
        for (i in 1..3) {
            val y = size.height * i / 4f
            drawLine(Sky.outline.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), 1f)
        }
        if (bins.isEmpty()) return@Canvas
        val step = size.width / (bins.size - 1).coerceAtLeast(1)
        val path = Path()
        bins.forEachIndexed { i, db ->
            val t = ((db - Spectrum.FLOOR) / -Spectrum.FLOOR).coerceIn(0f, 1f)
            val x = i * step
            val y = size.height * (1f - t)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Sky.sdr, style = Stroke(width = 1.6f * density))
        val peak = Spectrum.peakBin(bins)
        drawLine(
            Sky.cyan.copy(alpha = 0.45f),
            Offset(peak * step, 0f),
            Offset(peak * step, size.height),
            1f,
        )
        // Centre tick: the tuned frequency, so the trace can be read against a known point.
        drawLine(
            Sky.textDim.copy(alpha = 0.5f),
            Offset(size.width / 2f, 0f),
            Offset(size.width / 2f, size.height),
            1f,
        )
    }
}

/**
 * A saved capture, opened from its pin.
 *
 * The trace is the one that was on screen: a recording keeps the averaged spectrum, not the raw
 * I/Q, so it can be read back on the map without the dongle and without a player.
 */
@Composable
fun SdrCard(capture: SdrCapture, modifier: Modifier = Modifier, onClose: () -> Unit) {
    val df = remember { SimpleDateFormat("d MMM HH:mm:ss", Locale.US) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Sky.surface)
            .border(1.dp, Sky.sdr.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(Sky.sdr, 9)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(capture.name, color = Sky.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Saved SDR capture", color = Sky.textDim, fontSize = 11.sp)
            }
            Text("\u2715", color = Sky.textDim, modifier = Modifier.clickable { onClose() })
        }
        Spacer(Modifier.height(6.dp))
        SpectrumView(capture.spectrum.toFloatArray(), height = 70.dp)
        Spacer(Modifier.height(6.dp))
        KeyValueRow("Centre", String.format(Locale.US, "%.3f MHz", capture.centerMhz), Sky.sdr)
        KeyValueRow("Span", String.format(Locale.US, "%.1f MHz", capture.spanMhz))
        KeyValueRow("Peak", String.format(Locale.US, "%.0f dBFS", capture.peakDb))
        KeyValueRow("Recorded", df.format(Date(capture.at)))
        KeyValueRow(
            "Length",
            "${(capture.endedAt - capture.at) / 1000}s \u00b7 ${capture.frames} frames",
        )
        KeyValueRow("Position", String.format(Locale.US, "%.5f, %.5f", capture.lat, capture.lon))
    }
}
