package dev.skyward.rf.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyward.rf.core.Layer
import dev.skyward.rf.core.Units
import dev.skyward.rf.desktop.DesktopContainer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The log: every drive this machine has recorded, what was captured in the air, and the exports.
 *
 * The phone's Log screen has the same four blocks, because they answer the same question - what
 * did this machine actually catch, and can I get it out of here. Export writes into the app's
 * own folder instead of opening an Android share sheet, since a desktop has a filesystem.
 */
@Composable
fun LogScreen(container: DesktopContainer, modifier: Modifier = Modifier, onShowOnMap: () -> Unit) {
    val sessions by container.wardrive.sessions.collectAsState()
    val logged by container.logged.state.collectAsState()
    val placed by container.signals.count.collectAsState()
    val settings by container.settingsStore.state.collectAsState()
    val unit = settings.distanceUnit
    val df = remember { SimpleDateFormat("MMM d · HH:mm", Locale.US) }

    var note by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 80.dp),
    ) {
        Text("LOG", color = Sky, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        Text("drives, captured entities and placed signals", color = TextDim, fontSize = 11.sp)
        Spacer(Modifier.height(14.dp))

        Section("Drives", trailing = "${sessions.size} saved") {
            if (sessions.isEmpty()) {
                Text(
                    "No drives yet. Start one on the Drive tab; each saved drive is a track coloured by the strongest signal at that fix.",
                    color = TextDim,
                    fontSize = 11.sp,
                )
            } else {
                sessions.forEach { session ->
                    val active = selected == session.id
                    Column(
                        Modifier.fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) Sky.copy(alpha = 0.08f) else Panel)
                            .border(1.dp, if (active) Sky.copy(alpha = 0.4f) else Line, RoundedCornerShape(8.dp))
                            .clickable {
                                selected = if (active) null else session.id
                                container.wardrive.showSession(if (active) null else session.id)
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(session.name, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(Units.distance(session.km, unit), color = Sky, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${df.format(Date(session.startedAt))}  ·  ${session.points} fixes  ·  ${session.wifiAps} AP  ·  ${session.bleDevices} BLE  ·  best ${session.bestScore}",
                            color = TextDim,
                            fontSize = 10.sp,
                        )
                        if (session.from.isNotBlank()) {
                            Text("sent from ${session.from}", color = Violet, fontSize = 10.sp)
                        }
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Action("SHOW") {
                                selected = session.id
                                container.wardrive.showSession(session.id)
                                onShowOnMap()
                            }
                            Action("CSV") {
                                val full = container.wardrive.loadSession(session.id)
                                if (full == null) {
                                    note = "That drive is no longer on disk"
                                } else {
                                    val name = "drive_${session.id}.csv"
                                    container.store.writeText("exports/$name", container.wardrive.exportCsv(full))
                                    note = "Wrote exports/$name"
                                }
                            }
                            Action("GEOJSON") {
                                val full = container.wardrive.loadSession(session.id)
                                if (full == null) {
                                    note = "That drive is no longer on disk"
                                } else {
                                    val name = "drive_${session.id}.geojson"
                                    container.store.writeText("exports/$name", container.wardrive.exportGeoJson(full))
                                    note = "Wrote exports/$name"
                                }
                            }
                            Action("DELETE", color = Amber) {
                                container.wardrive.deleteSession(session.id)
                                if (selected == session.id) {
                                    selected = null
                                    container.wardrive.showSession(null)
                                }
                                note = "Deleted ${session.name}"
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            if (note != null) {
                Spacer(Modifier.height(6.dp))
                Text(note!!, color = Mint, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }

        Spacer(Modifier.height(10.dp))
        Section("Captured entities", trailing = "${logged.size} total") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Satellites", logged.values.count { it.layer == Layer.SATELLITE }.toString(), Modifier.weight(1f))
                Stat("Aircraft", logged.values.count { it.layer == Layer.AIRCRAFT }.toString(), Modifier.weight(1f))
                Stat("Drones", logged.values.count { it.layer == Layer.DRONE }.toString(), Modifier.weight(1f))
                Stat("Boards", logged.values.count { it.layer == Layer.ESP32 }.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Action("EXPORT CSV") {
                val name = "logged_${System.currentTimeMillis()}.csv"
                container.store.writeText("exports/$name", container.wardrive.exportLoggedCsv())
                note = "Wrote exports/$name"
            }
        }

        Spacer(Modifier.height(10.dp))
        Section("Placed signals", trailing = "$placed placed") {
            Text(
                "Every Wi-Fi network and BLE advertiser heard with a fix, at the point where it was actually heard. A drive hosted from a phone keeps the phone's position and the radio's own name for the row.",
                color = TextDim,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("GEOJSON") {
                    val name = "signals_${System.currentTimeMillis()}.geojson"
                    container.store.writeText("exports/$name", container.signals.exportGeoJson())
                    note = "Wrote exports/$name"
                }
                Action("CSV") {
                    val name = "signals_${System.currentTimeMillis()}.csv"
                    container.store.writeText("exports/$name", container.signals.exportCsv())
                    note = "Wrote exports/$name"
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Exports are written to ${container.store.file("exports").absolutePath}",
            color = TextDim,
            fontSize = 10.sp,
        )
    }
}
