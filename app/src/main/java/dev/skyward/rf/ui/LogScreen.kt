package dev.skyward.rf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyward.rf.Container
import dev.skyward.rf.core.Layer
import dev.skyward.rf.core.SignalScale
import dev.skyward.rf.ui.theme.Sky
import dev.skyward.rf.wardrive.SessionSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogScreen(c: Container, modifier: Modifier = Modifier, onShowOnMap: () -> Unit) {
    val sessions by c.wardrive.sessions.collectAsState()
    val logged by c.logged.state.collectAsState()
    val context = LocalContext.current
    val df = remember { SimpleDateFormat("MMM d · HH:mm", Locale.US) }

    LazyColumn(
        modifier
            .fillMaxSize()
            .background(Sky.bg)
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(6.dp)) }

        item {
            SectionCard(title = "Logged entities", trailing = "${logged.size} total") {
                KeyValueRow("Satellites", logged.values.count { it.layer == Layer.SATELLITE }.toString(), Sky.logged)
                KeyValueRow("Aircraft", logged.values.count { it.layer == Layer.AIRCRAFT }.toString(), Sky.logged)
                KeyValueRow("Drones", logged.values.count { it.layer == Layer.DRONE }.toString(), Sky.logged)
                KeyValueRow("Mesh nodes", logged.values.count { it.layer == Layer.MESH }.toString(), Sky.logged)
                KeyValueRow("Cell towers", logged.values.count { it.layer == Layer.CELL }.toString(), Sky.logged)
                KeyValueRow("ESP32 nodes", logged.values.count { it.layer == Layer.ESP32 }.toString(), Sky.logged)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallAction("EXPORT CSV") {
                        shareExport(
                            context,
                            "signal_intelgo_logged_${System.currentTimeMillis()}.csv",
                            "text/csv",
                            c.wardrive.exportLoggedCsv(),
                        )
                    }
                    SmallAction("CLEAR", color = Sky.danger) {
                        c.logged.clear()
                        c.wardrive.refreshSessions()
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Anything captured while recording is drawn in violet on the map.",
                    color = Sky.textDim,
                    fontSize = 10.sp,
                )
            }
        }

        item {
            Text(
                "DRIVES",
                style = SkywardThemeLabel(),
                color = Sky.textDim,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }

        if (sessions.isEmpty()) {
            item {
                Text(
                    "No drives yet. Start one from the drive tab — the phone logs Wi-Fi and BLE while it moves.",
                    color = Sky.textDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }

        items(sessions, key = { it.id }) { s: SessionSummary ->
            SessionRow(
                s = s,
                df = df,
                onShow = {
                    c.wardrive.showSession(s.id)
                    onShowOnMap()
                },
                onExportGeo = {
                    val full = c.wardrive.loadSession(s.id)
                    if (full != null) {
                        shareExport(
                            context,
                            "signal_intelgo_${s.id}.geojson",
                            "application/geo+json",
                            c.wardrive.exportGeoJson(full),
                        )
                    }
                },
                onExportCsv = {
                    val full = c.wardrive.loadSession(s.id)
                    if (full != null) {
                        shareExport(context, "signal_intelgo_${s.id}.csv", "text/csv", c.wardrive.exportCsv(full))
                    }
                },
                onDelete = { c.wardrive.deleteSession(s.id) },
            )
        }

        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
private fun SessionRow(
    s: SessionSummary,
    df: SimpleDateFormat,
    onShow: () -> Unit,
    onExportGeo: () -> Unit,
    onExportCsv: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Sky.surface)
            .border(1.dp, Sky.outline, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.name, color = Sky.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${df.format(Date(s.startedAt))} · ${(s.endedAt - s.startedAt) / 60000} min",
                    color = Sky.textDim,
                    fontSize = 10.sp,
                )
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(Sky.surfaceHigh)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    "PEAK ${s.bestScore}",
                    style = SkywardThemeLabel(),
                    color = Color(SignalScale.colorOfScore(s.bestScore)),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatCell("Distance", String.format("%.2f km", s.km), Modifier.weight(1f))
            StatCell("Fixes", s.points.toString(), Modifier.weight(1f))
            StatCell("Wi-Fi", s.wifiAps.toString(), Modifier.weight(1f))
            StatCell("BLE", s.bleDevices.toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction("MAP", color = Sky.cyan, onClick = onShow)
            SmallAction("GEOJSON", onClick = onExportGeo)
            SmallAction("CSV", onClick = onExportCsv)
            Spacer(Modifier.weight(1f))
            SmallAction("DEL", color = Sky.danger, onClick = onDelete)
        }
    }
}

@Composable
fun SmallAction(label: String, color: Color = Sky.textDim, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Sky.surfaceHigh)
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(label, style = SkywardThemeLabel(), color = color, fontSize = 10.sp)
    }
}

@Composable
fun LogHeaderHint() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(4.dp))
        Text("Sessions are stored on device", color = Sky.textDim, fontSize = 10.sp)
    }
}
