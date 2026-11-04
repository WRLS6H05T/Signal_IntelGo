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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyward.rf.Container
import dev.skyward.rf.core.Geo
import dev.skyward.rf.core.RfKind
import dev.skyward.rf.core.RfObservation
import dev.skyward.rf.core.SignalScale
import dev.skyward.rf.peer.PeerProtocol
import dev.skyward.rf.ui.theme.Sky
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScanScreen(c: Container, modifier: Modifier = Modifier, onFixPermissions: () -> Unit) {
    val wd by c.wardrive.state.collectAsState()
    val rf by c.airspace.rfSnapshot.collectAsState()
    val observer by c.observer.collectAsState()
    val counts by c.airspace.loggedCounts.collectAsState()
    val sky by c.airspace.state.collectAsState()

    val wifiOk = c.rf.hasWifiPermission()
    val bleOk = c.rf.hasBlePermission()
    val locOk = c.location.hasPermission()
    val wifiOn by c.rf.wifiEnabled.collectAsState()
    val bleOn by c.rf.bleEnabled.collectAsState()

    val bestNow = rf.all.maxByOrNull { it.rssi }

    LazyColumn(
        modifier
            .fillMaxSize()
            .background(Sky.bg)
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(6.dp)) }

        // ---- record control ----
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (wd.active) Color(0xFF2A1016) else Sky.surface)
                    .border(1.dp, if (wd.active) Sky.warn.copy(alpha = 0.6f) else Sky.outline, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(if (wd.active) Sky.danger else Sky.cyan)
                        .clickable {
                            if (wd.active) {
                                c.wardrive.stop()
                            } else if (!locOk) {
                                onFixPermissions()
                            } else {
                                c.wardrive.start()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (wd.active) "■" else "▶",
                        color = Sky.bg,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (wd.active) "RECORDING" else "WARDRIVE IDLE",
                        style = SkywardThemeLabel(),
                        color = if (wd.active) Sky.warn else Sky.textDim,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (wd.active) wd.name else "Tap to start logging Wi-Fi + BLE",
                        color = Sky.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (wd.active) {
                            val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
                            "since ${fmt.format(Date(wd.startedAt))} · ${wd.points.size} fixes"
                        } else {
                            "Path is coloured by the strongest signal at each fix"
                        },
                        color = Sky.textDim,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        // ---- permission warnings ----
        if (!locOk || !wifiOk || !bleOk) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF2A1F08))
                        .border(1.dp, Sky.warn.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                ) {
                    Text("PERMISSIONS NEEDED", style = SkywardThemeLabel(), color = Sky.warn)
                    Spacer(Modifier.height(6.dp))
                    if (!locOk) Text("• Location — required for GPS and for Wi-Fi/BLE scan results", color = Sky.text, fontSize = 12.sp)
                    if (!wifiOk) Text("• Nearby Wi-Fi devices / location", color = Sky.text, fontSize = 12.sp)
                    if (!bleOk) Text("• Bluetooth scan", color = Sky.text, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(Sky.warn)
                            .clickable { onFixPermissions() }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text("GRANT", color = Sky.bg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ---- live numbers ----
        item {
            SectionCard(title = "Live", trailing = if (wd.active) "REC" else "READY") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    StatCell("Points", wd.points.size.toString(), Modifier.weight(1f))
                    StatCell("Distance", String.format("%.2f km", wd.km), Modifier.weight(1f))
                    StatCell(
                        "Best",
                        wd.bestScore.toString(),
                        Modifier.weight(1f),
                        accent = Color(SignalScale.colorOfScore(wd.bestScore)),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    StatCell("Wi-Fi APs", wd.wifiCount.toString(), Modifier.weight(1f), accent = Sky.cyan)
                    StatCell("BLE devs", wd.bleCount.toString(), Modifier.weight(1f), accent = Sky.mesh)
                    StatCell("Now", "${bestNow?.rssi ?: 0} dBm", Modifier.weight(1f), accent = Color(SignalScale.colorOfDbm(bestNow?.rssi ?: -127)))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    StatCell("GPS", if (observer != null) "lock" else "none", Modifier.weight(1f), accent = if (observer != null) Sky.cyan else Sky.danger)
                    StatCell("Sats logged", counts.sats.toString(), Modifier.weight(1f), accent = Sky.logged)
                    StatCell("Aircraft logged", counts.aircraft.toString(), Modifier.weight(1f), accent = Sky.logged)
                }
            }
        }

        // ---- the other phone, when the BLE link is up ----
        item {
            val peer by c.peer.state.collectAsState()
            SectionCard(
                title = "Peer",
                trailing = when {
                    !peer.running -> "off"
                    peer.connected -> "linked"
                    else -> "searching"
                },
            ) {
                if (!peer.running) {
                    Text(
                        "No link. Both phones advertising is what starts it; the scans are "
                            + "shared while a drive records on either side.",
                        color = Sky.textDim,
                        fontSize = 11.sp,
                    )
                } else {
                    KeyValueRow("Device", peer.peerName.ifBlank { "waiting for the other phone" })
                    KeyValueRow("Wi-Fi", peer.peerWifiTotal.toString(), Sky.cyan)
                    KeyValueRow("BLE", peer.peerBleTotal.toString(), Sky.mesh)
                    KeyValueRow(
                        "GPS",
                        if (peer.peerGps) "using fix" else "not used",
                        if (peer.peerGps) Sky.cyan else Sky.textDim,
                    )
                    KeyValueRow(
                        "Cellular",
                        if (peer.peerCell) "in use" else "not used",
                        if (peer.peerCell) Sky.cyan else Sky.textDim,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Their signals arrive with their own GPS fix and are logged there, "
                            + "so the colours land on the streets they were heard on.",
                        color = Sky.textDim,
                        fontSize = 10.sp,
                    )
                }
            }
        }

        // ---- colour distribution ----
        item {
            val bins = IntArray(5)
            wd.points.forEach { bins[SignalScale.binOfScore(it.score)]++ }
            val max = bins.maxOrNull()?.coerceAtLeast(1) ?: 1
            SectionCard(title = "Signal distribution", trailing = "5 bins") {
                Row(
                    Modifier.fillMaxWidth().height(66.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (i in 0 until 5) {
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(bins[i].toString(), color = Sky.text, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.height(3.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height((6 + (bins[i].toFloat() / max) * 34).dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(SignalScale.binArgb[i])),
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(SignalScale.binLabels[i].take(4), color = Sky.textDim, fontSize = 9.sp)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Red = weak signal logged, green = strong. Bars count fixes per bin.",
                    color = Sky.textDim,
                    fontSize = 10.sp,
                )
            }
        }

        // ---- heard now ----
        item {
            SectionCard(title = "Heard now", trailing = "${rf.all.size} radios") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToggleChip("WIFI", rf.wifi.size, Sky.cyan, wifiOn) { c.rf.setWifiEnabled(!wifiOn) }
                    ToggleChip("BLE", rf.ble.size, Sky.mesh, bleOn) { c.rf.setBleEnabled(!bleOn) }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (wifiOn && bleOn) "both on" else if (wifiOn) "wifi only" else if (bleOn) "ble only" else "radios off",
                        style = SkywardThemeLabel(),
                        color = Sky.textDim,
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (rf.all.isEmpty()) {
                    Text(
                        if (wifiOk || bleOk) "No radios heard yet — scanning continues."
                        else "Grant permissions to scan.",
                        color = Sky.textDim,
                        fontSize = 12.sp,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        rf.all.take(10).forEach { o -> ObservationRow(o) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (c.rf.lastWifiError != null) Text("Wi-Fi: ${c.rf.lastWifiError}", color = Sky.warn, fontSize = 10.sp)
                if (c.rf.lastBleError != null) Text("BLE: ${c.rf.lastBleError}", color = Sky.warn, fontSize = 10.sp)
            }
        }

        // ---- mesh + remote id ----
        item {
            SectionCard(title = "Sky capture", trailing = "${sky.drones.size} UAV") {
                KeyValueRow("Satellites", "${sky.sats.size} in view")
                KeyValueRow("Aircraft", "${sky.aircraft.size} in range")
                KeyValueRow("Remote ID", "${sky.drones.size} drones")
                KeyValueRow("Mesh nodes", "${sky.mesh.size} nodes")
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(Sky.surfaceHigh)
                        .clickable { c.airspace.captureNow() }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text("LOG CURRENT SKY", color = Sky.logged, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
private fun ObservationRow(o: RfObservation) {
    val color = Color(SignalScale.colorOfDbm(o.rssi))
    val score = Geo.rssiToScore(o.rssi)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (o.kind == RfKind.WIFI) "WIFI" else "BLE ",
            style = SkywardThemeLabel(),
            color = if (o.kind == RfKind.WIFI) Sky.cyan else Sky.mesh,
            modifier = Modifier.width(38.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                o.name.ifBlank { o.id },
                color = Sky.text,
                fontSize = 12.sp,
                maxLines = 1,
            )
            Text(
                "${o.id}${if (o.channel > 0) " · ch ${o.channel}" else ""}${if (o.security.isNotBlank()) " · ${o.security}" else ""}",
                color = Sky.textDim,
                fontSize = 9.sp,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .width(46.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Sky.surfaceHigh),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(score / 100f)
                    .height(5.dp)
                    .background(color),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            "${o.rssi}",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = color,
            modifier = Modifier.width(32.dp),
        )
    }
}
