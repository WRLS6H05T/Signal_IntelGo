package dev.skyward.rf.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyward.rf.core.SignalScale
import dev.skyward.rf.core.Units
import dev.skyward.rf.desktop.DesktopContainer
import dev.skyward.rf.desktop.host.DeviceHost
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The drive recorder, on the desk.
 *
 * The phone records what its own radios hear at its own fixes. A desktop machine has neither a
 * Wi-Fi scanner nor a GPS, so the drive here is the one its hosted devices are driving: the
 * position comes from whichever linked device reports a fix, and the networks and advertisers
 * come from the merged table those devices fill. That is why the screen says where the fix came
 * from - a drive logged while no phone was linked has nothing to place.
 */
@Composable
fun DriveScreen(container: DesktopContainer, host: DeviceHost.State, modifier: Modifier = Modifier) {
    val drive by container.wardrive.state.collectAsState()
    val settings by container.settingsStore.state.collectAsState()
    val unit = settings.distanceUnit

    val source = host.devices.firstOrNull { it.fix != null }
    val linked = host.devices.count { it.connected }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 80.dp),
    ) {
        Text("DRIVE", color = Sky, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        Text(
            "a recording of where the link was, and what its radios heard there",
            color = TextDim,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                Modifier.clip(CircleShape).background(if (drive.active) Amber else TextDim).then(Modifier.height(9.dp).width(9.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (drive.active) "RECORDING" else "IDLE",
                    color = if (drive.active) Amber else TextDim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                )
                Text(
                    if (drive.active) drive.name else "start a drive to place every network at a fix",
                    color = TextDim,
                    fontSize = 11.sp,
                )
            }
            Action(
                if (drive.active) "STOP AND SAVE" else "START DRIVE",
                color = if (drive.active) Amber else Mint,
            ) {
                if (drive.active) {
                    container.wardrive.stop()
                } else {
                    container.wardrive.start()
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Distance", Units.distance(drive.km, unit), Modifier.weight(1f))
            Stat("Fixes", drive.points.size.toString(), Modifier.weight(1f))
            Stat("Networks", (drive.wifiCount + drive.bleCount).toString(), Modifier.weight(1f))
            Stat("Best", drive.bestScore.toString(), Modifier.weight(1f))
        }

        Spacer(Modifier.height(14.dp))
        Section("Fix source") {
            if (source == null) {
                Text(
                    "No position yet. A drive places its points at a fix, so a phone or a GPS-equipped board has to be linked and reporting one.",
                    color = TextDim,
                    fontSize = 11.sp,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(Modifier.height(7.dp).width(7.dp).clip(CircleShape).background(Mint))
                    Spacer(Modifier.width(8.dp))
                    Text(source.name, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "%.5f, %.5f".format(source.fix!!.lat, source.fix!!.lon) +
                        "  ·  ${source.sats} sats  ·  $linked linked",
                    color = TextDim,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        val point = drive.lastPoint
        if (point != null) {
            Spacer(Modifier.height(10.dp))
            Section("Last fix", trailing = "%.5f, %.5f".format(point.lat, point.lon)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Score", point.score.toString(), Modifier.weight(1f))
                    Stat("Wi-Fi", point.wifiCount.toString(), Modifier.weight(1f))
                    Stat("BLE", point.bleCount.toString(), Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                val bin = SignalScale.binOfScore(point.score)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.height(8.dp).width(8.dp).clip(CircleShape)
                            .background(androidx.compose.ui.graphics.Color(SignalScale.binArgb[bin])),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${SignalScale.binLabels[bin]}  ·  ${SignalScale.binDbm[bin]}",
                        color = TextDim,
                        fontSize = 10.sp,
                    )
                }
                if (point.bestWifi.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("best Wi-Fi  ${point.bestWifi}  ${point.bestWifiRssi} dBm", color = TextDim, fontSize = 10.sp)
                }
                if (point.bestBle.isNotBlank()) {
                    Text("best BLE  ${point.bestBle}  ${point.bestBleRssi} dBm", color = TextDim, fontSize = 10.sp)
                }
            }
        }

        val recent = container.wardrive.sessions.collectAsState().value
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Section("Recent drives", trailing = "${recent.size} saved") {
                val df = SimpleDateFormat("MMM d · HH:mm", Locale.US)
                recent.take(4).forEach { session ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(session.name, color = TextMain, fontSize = 12.sp)
                            Text(
                                "${df.format(Date(session.startedAt))}  ·  ${Units.distance(session.km, unit)}  ·  ${session.points} fixes",
                                color = TextDim,
                                fontSize = 10.sp,
                            )
                        }
                        Action("SHOW") {
                            container.wardrive.showSession(session.id)
                        }
                    }
                }
            }
        }
    }
}
