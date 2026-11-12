package dev.skyward.rf.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyward.rf.core.ScanPlan
import dev.skyward.rf.core.SignalScale
import dev.skyward.rf.desktop.hardware.Bands
import dev.skyward.rf.desktop.hardware.BoardState
import dev.skyward.rf.desktop.hardware.ConsoleLine
import dev.skyward.rf.desktop.hardware.PortInfo
import dev.skyward.rf.desktop.hardware.SensorHost
import dev.skyward.rf.desktop.session.SessionRecorder
import dev.skyward.rf.desktop.session.SessionSummary
import kotlinx.coroutines.delay
import java.io.File

/**
 * The command centre.
 *
 * One screen, because an operator watching a desk does not hunt for tabs: the boards and their
 * band assignments on the left, the console in the middle where the work happens, the numbers
 * the boards are reporting on the right, and the session that records all of it along the
 * bottom. Every colour that is not grey comes from the app's own signal scale, so a strong
 * network is the same green here as it is on the phone's map.
 */
@Composable
fun CommandCenter(host: SensorHost, recorder: SessionRecorder) {
    val boards by host.boards.collectAsState()
    val ports by host.ports.collectAsState()
    val console by host.console.collectAsState()

    var selected by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var sessionName by remember { mutableStateOf("desk") }
    var startedAt by remember { mutableStateOf<Long?>(null) }
    var summary by remember { mutableStateOf<SessionSummary?>(null) }
    var rows by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        host.refresh()
        while (true) {
            delay(1_000)
            host.refresh()
            now = System.currentTimeMillis()
            rows = recorder.count
        }
    }

    val ordered = boards.values.sortedBy { it.path }
    val active = boards[selected ?: ""]
        ?: ordered.firstOrNull { it.connected }
        ?: ordered.firstOrNull()

    Column(Modifier.fillMaxSize().background(Desk.bg)) {
        TopBar(ordered.size, ordered.count { it.connected }, active, startedAt, now, rows)
        Hairline()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            SensorsRail(
                modifier = Modifier.width(310.dp).fillMaxHeight(),
                ports = ports,
                boards = ordered,
                activePath = active?.path,
                onSelect = { selected = it },
                host = host,
            )
            VerticalHairline()
            ConsolePane(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                board = active,
                lines = console[active?.path].orEmpty(),
                host = host,
            )
            VerticalHairline()
            Telemetry(
                modifier = Modifier.width(480.dp).fillMaxHeight(),
                board = active,
            )
        }
        Hairline()
        SessionBar(
            name = sessionName,
            onName = { sessionName = it },
            liveSince = startedAt,
            now = now,
            rows = rows,
            summary = summary,
            recorder = recorder,
            onStart = {
                recorder.start(sessionName.ifBlank { "desk" })
                startedAt = System.currentTimeMillis()
                summary = null
            },
            onStop = {
                summary = recorder.stop()
                startedAt = null
                rows = recorder.count
            },
        )
    }
}

// ---------------------------------------------------------------- chrome

@Composable
private fun TopBar(ports: Int, connected: Int, board: BoardState?, since: Long?, now: Long, rows: Int) {
    Row(
        Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("SIGNAL INTELGO", color = Desk.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.width(10.dp))
        Text("COMMAND CENTER", color = Desk.accent, fontSize = 11.sp, letterSpacing = 2.sp, fontFamily = Desk.mono)
        Spacer(Modifier.weight(1f))
        Badge("$connected/$ports BOARDS", if (connected > 0) Desk.accent else Desk.dim)
        Spacer(Modifier.width(8.dp))
        Badge(board?.chip?.ifBlank { "no board selected" } ?: "no board selected", Desk.violet)
        if (since != null) {
            Spacer(Modifier.width(8.dp))
            Badge("SESSION ${clock(now - since)} · $rows", Desk.warn, filled = true)
        }
    }
}

@Composable
private fun SensorsRail(
    modifier: Modifier,
    ports: List<PortInfo>,
    boards: List<BoardState>,
    activePath: String?,
    onSelect: (String) -> Unit,
    host: SensorHost,
) {
    Column(modifier.background(Desk.panel).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("SENSORS")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { host.refresh() }) {
                Text("REFRESH", color = Desk.accent, fontSize = 11.sp, fontFamily = Desk.mono)
            }
        }
        Spacer(Modifier.height(6.dp))
        if (ports.isEmpty()) {
            Text("No serial ports. Plug a board in.", color = Desk.dim, fontSize = 12.sp)
        }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(boards, key = { it.path }) { board ->
                PortRow(board, board.path == activePath, onClick = { onSelect(board.path) }, host = host)
            }
        }
        Spacer(Modifier.height(10.dp))
        Hairline()
        Spacer(Modifier.height(10.dp))
        ActiveBoardCard(boards.firstOrNull { it.path == activePath }, host)
    }
}

@Composable
private fun PortRow(board: BoardState, selected: Boolean, onClick: () -> Unit, host: SensorHost) {
    val dot = when {
        board.connected -> Desk.accent
        board.note == "port did not open" -> Desk.bad
        else -> Desk.dim
    }
    Box(
        Modifier.fillMaxWidth()
            .padding(bottom = 6.dp)
            .background(if (selected) Desk.panelHi else Color.Transparent, RoundedCornerShape(8.dp))
            .border(1.dp, if (selected) Desk.line else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(dot, RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(board.path, color = Desk.text, fontSize = 13.sp, fontFamily = Desk.mono, fontWeight = FontWeight.Bold)
                Text(
                    board.identity?.let { "${it.name} · ${it.firmware ?: "fw ?"}" } ?: board.detail.ifBlank { "serial" },
                    color = Desk.dim,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { if (board.connected) host.disconnect(board.path) else host.connect(board.path) }) {
                Text(if (board.connected) "STOP" else "LINK", color = if (board.connected) Desk.warn else Desk.accent, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ActiveBoardCard(board: BoardState?, host: SensorHost) {
    if (board == null) {
        Text("Select a port to see what it can sweep.", color = Desk.dim, fontSize = 12.sp)
        return
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(board.path)
            Spacer(Modifier.weight(1f))
            Badge(if (board.connected) "LINKED" else "OFFLINE", if (board.connected) Desk.accent else Desk.dim)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            board.identity?.banner ?: "Console has not named this board yet.",
            color = if (board.identity == null) Desk.dim else Desk.text,
            fontSize = 11.sp,
            fontFamily = Desk.mono,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        SectionLabel("SCAN")
        Spacer(Modifier.height(6.dp))
        // The menu is the plan model's own: whatever a board's silicon can be given, and nothing
        // it cannot. A C3 is never offered 2.4+5 because its radio is not there to be asked.
        Column {
            board.options.forEach { option ->
                val on = board.plan == option
                Box(
                    Modifier.fillMaxWidth()
                        .padding(bottom = 4.dp)
                        .background(if (on) Desk.accent.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(6.dp))
                        .border(1.dp, if (on) Desk.accent.copy(alpha = 0.7f) else Desk.line, RoundedCornerShape(6.dp))
                        .clickable { host.setPlan(board.path, option) }
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                ) {
                    Text(ScanPlan.label(option), color = if (on) Desk.accent else Desk.dim, fontSize = 11.sp, fontFamily = Desk.mono)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("GPS POLL", color = Desk.dim, fontSize = 11.sp, fontFamily = Desk.mono)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = board.gps,
                onCheckedChange = { host.setGps(board.path, it) },
                modifier = Modifier.size(width = 42.dp, height = 24.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                // One sweep now, without waiting for the cycle: the verb is the plan's own.
                host.send(board.path, if (ScanPlan.hasWifi(board.plan)) "scanap" else "blescan")
            },
            enabled = board.connected,
            colors = ButtonDefaults.buttonColors(containerColor = Desk.accent, contentColor = Desk.bg),
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("SWEEP NOW", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        if (board.lastLine.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(board.lastLine, color = Desk.dim, fontSize = 10.sp, fontFamily = Desk.mono, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- console

@Composable
private fun ConsolePane(modifier: Modifier, board: BoardState?, lines: List<ConsoleLine>, host: SensorHost) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Column(modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("CONSOLE")
            Spacer(Modifier.width(8.dp))
            Text(board?.path ?: " — ", color = Desk.text, fontSize = 12.sp, fontFamily = Desk.mono)
            Spacer(Modifier.weight(1f))
            Text("${board?.polls ?: 0} polls", color = Desk.dim, fontSize = 11.sp, fontFamily = Desk.mono)
            Spacer(Modifier.width(8.dp))
            Badge(board?.planLabel ?: "NO PLAN", Desk.violet)
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .background(Desk.panel, RoundedCornerShape(10.dp))
                .border(1.dp, Desk.line, RoundedCornerShape(10.dp)),
        ) {
            if (lines.isEmpty()) {
                Text(
                    "The transcript of the selected board. Connect a port and it is asked who it is.",
                    color = Desk.dim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(14.dp),
                )
            } else {
                LazyColumn(state = listState, contentPadding = PaddingValues(12.dp)) {
                    items(lines) { line -> ConsoleRow(line) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row {
            listOf("help", "status", "scanap", "blescan", "gps", "reboot").forEach { verb ->
                TextButton(
                    onClick = { board?.let { host.send(it.path, verb) } },
                    enabled = board?.connected == true,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(verb, color = Desk.accent, fontSize = 11.sp, fontFamily = Desk.mono)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                textStyle = TextStyle(fontFamily = Desk.mono, fontSize = 12.sp, color = Desk.text),
                placeholder = { Text("verb…", color = Desk.dim, fontSize = 12.sp, fontFamily = Desk.mono) },
                modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                        board?.let { host.send(it.path, input) }
                        input = ""
                        true
                    } else {
                        false
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    board?.let { host.send(it.path, input) }
                    input = ""
                },
                enabled = board?.connected == true && input.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Desk.accent, contentColor = Desk.bg),
                shape = RoundedCornerShape(6.dp),
            ) {
                Text("SEND", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ConsoleRow(line: ConsoleLine) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(line.stamp, color = Desk.dim.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = Desk.mono)
        Spacer(Modifier.width(8.dp))
        Text(
            (if (line.outbound) "› " else "  ") + line.text,
            color = if (line.outbound) Desk.accent else Desk.text.copy(alpha = 0.9f),
            fontSize = 11.sp,
            fontFamily = Desk.mono,
        )
    }
}

// ---------------------------------------------------------------- telemetry

@Composable
private fun Telemetry(modifier: Modifier, board: BoardState?) {
    var tab by remember { mutableStateOf(0) }
    val reading = board?.reading
    val sweep = board?.sweep

    Column(modifier.background(Desk.panel).padding(14.dp)) {
        SectionLabel("TELEMETRY")
        Spacer(Modifier.height(8.dp))
        Row {
            StatCell("FIX", when (reading?.fix) { true -> "3D"; false -> "NO"; null -> "—" }, if (reading?.fix == true) Desk.accent else Desk.dim)
            StatCell("SATS", reading?.sats?.toString() ?: "—", Desk.text)
            StatCell("HDOP", reading?.hdop?.let { String.format("%.1f", it) } ?: "—", Desk.text)
            StatCell("ALT", reading?.altM?.let { "${it.toInt()} m" } ?: "—", Desk.text)
        }
        Spacer(Modifier.height(4.dp))
        Row {
            StatCell("LAT", reading?.lat?.let { String.format("%.5f", it) } ?: "—", Desk.text)
            StatCell("LON", reading?.lon?.let { String.format("%.5f", it) } ?: "—", Desk.text)
            StatCell("POLLS", board?.polls?.toString() ?: "0", Desk.dim)
            StatCell("LAST", board?.note?.uppercase() ?: "—", if (board?.note == "ok") Desk.accent else Desk.dim)
        }
        Spacer(Modifier.height(12.dp))
        Hairline()
        Spacer(Modifier.height(12.dp))
        Row {
            BigNumber("AP", board?.aps ?: 0, Desk.text)
            BigNumber("BLE", board?.bles ?: 0, Desk.text)
            BigNumber("5 GHZ", board?.ghz5 ?: 0, if ((board?.ghz5 ?: 0) > 0) Desk.accent else Desk.dim)
        }
        Spacer(Modifier.height(14.dp))
        Row {
            TabLabel("ACCESS POINTS", tab == 0) { tab = 0 }
            Spacer(Modifier.width(14.dp))
            TabLabel("BLE DEVICES", tab == 1) { tab = 1 }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == 0) {
                val aps = sweep?.aps.orEmpty().sortedByDescending { it.rssi }
                if (aps.isEmpty()) EmptyRow("No access points reported yet.")
                else LazyColumn {
                    items(aps, key = { it.bssid }) { ap ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            RssiBar(ap.rssi)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(ap.ssid.ifBlank { "(hidden)" }, color = Desk.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(ap.bssid, color = Desk.dim, fontSize = 10.sp, fontFamily = Desk.mono)
                            }
                            Text("${ap.rssi}", color = rssiColor(ap.rssi), fontSize = 11.sp, fontFamily = Desk.mono)
                            Spacer(Modifier.width(10.dp))
                            Text("${ap.channel}", color = Desk.dim, fontSize = 11.sp, fontFamily = Desk.mono, modifier = Modifier.width(30.dp))
                            Badge(Bands.of(ap.channel), if (Bands.of(ap.channel) == Bands.W5) Desk.accent else Desk.dim)
                        }
                    }
                }
            } else {
                val devices = sweep?.devices.orEmpty().sortedByDescending { it.rssi }
                if (devices.isEmpty()) EmptyRow("No BLE advertisers reported yet.")
                else LazyColumn {
                    items(devices, key = { it.address }) { device ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            RssiBar(device.rssi)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(device.name.ifBlank { "(unnamed)" }, color = Desk.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(device.address, color = Desk.dim, fontSize = 10.sp, fontFamily = Desk.mono)
                            }
                            Text("${device.rssi}", color = rssiColor(device.rssi), fontSize = 11.sp, fontFamily = Desk.mono)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, color: Color) {
    Column(Modifier.padding(end = 14.dp)) {
        Text(label, color = Desk.dim, fontSize = 9.sp, letterSpacing = 1.sp, fontFamily = Desk.mono)
        Text(value, color = color, fontSize = 13.sp, fontFamily = Desk.mono, maxLines = 1)
    }
}

@Composable
private fun RowScope.BigNumber(label: String, value: Int, color: Color) {
    Column(Modifier.weight(1f).padding(end = 8.dp)) {
        Text(value.toString(), color = color, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = Desk.mono)
        Text(label, color = Desk.dim, fontSize = 10.sp, letterSpacing = 1.sp, fontFamily = Desk.mono)
    }
}

@Composable
private fun TabLabel(text: String, on: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable { onClick() }) {
        Text(text, color = if (on) Desk.accent else Desk.dim, fontSize = 10.sp, letterSpacing = 1.sp, fontFamily = Desk.mono)
        Spacer(Modifier.height(3.dp))
        Box(Modifier.height(2.dp).width(if (on) 90.dp else 0.dp).background(if (on) Desk.accent else Color.Transparent))
    }
}

@Composable
private fun RssiBar(rssi: Int) {
    val fraction = ((rssi + 95).coerceIn(0, 55)) / 55f
    Box(Modifier.width(34.dp).height(4.dp).background(Desk.line, RoundedCornerShape(2.dp))) {
        Box(Modifier.fillMaxHeight().width(34.dp * fraction).background(rssiColor(rssi), RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun EmptyRow(text: String) {
    Text(text, color = Desk.dim, fontSize = 12.sp)
}

// ---------------------------------------------------------------- session

@Composable
private fun SessionBar(
    name: String,
    onName: (String) -> Unit,
    liveSince: Long?,
    now: Long,
    rows: Int,
    summary: SessionSummary?,
    recorder: SessionRecorder,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel("SESSION")
        Spacer(Modifier.width(10.dp))
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            singleLine = true,
            enabled = liveSince == null,
            textStyle = TextStyle(fontFamily = Desk.mono, fontSize = 12.sp, color = Desk.text),
            modifier = Modifier.width(180.dp),
        )
        Spacer(Modifier.width(10.dp))
        Button(
            onClick = { if (liveSince == null) onStart() else onStop() },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (liveSince == null) Desk.accent else Desk.bad,
                contentColor = if (liveSince == null) Desk.bg else Color.White,
            ),
            shape = RoundedCornerShape(6.dp),
        ) {
            Text(if (liveSince == null) "START" else "STOP", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Text(
            if (liveSince == null) "not recording" else "recording ${clock(now - liveSince)} · $rows rows",
            color = if (liveSince == null) Desk.dim else Desk.warn,
            fontSize = 11.sp,
            fontFamily = Desk.mono,
        )
        Spacer(Modifier.width(14.dp))
        summary?.let {
            Text(
                "${it.name}: ${it.aps} AP · ${it.ble} BLE · ${it.ghz5} on 5 GHz · ${clock(it.durationMs)}",
                color = Desk.dim,
                fontSize = 11.sp,
                fontFamily = Desk.mono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { save("signal-intelgo-session.csv", recorder.csv()) }) {
            Text("EXPORT CSV", color = Desk.accent, fontSize = 11.sp, fontFamily = Desk.mono)
        }
        TextButton(onClick = { save("signal-intelgo-session.json", recorder.json()) }) {
            Text("EXPORT JSON", color = Desk.accent, fontSize = 11.sp, fontFamily = Desk.mono)
        }
    }
}

// ---------------------------------------------------------------- bits

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Desk.dim, fontSize = 10.sp, letterSpacing = 1.5.sp, fontFamily = Desk.mono, fontWeight = FontWeight.Bold)
}

@Composable
private fun Badge(text: String, color: Color, filled: Boolean = false) {
    Box(
        Modifier
            .background(if (filled) color.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(5.dp))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(text, color = color, fontSize = 10.sp, fontFamily = Desk.mono, maxLines = 1)
    }
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Desk.line))
}

@Composable
private fun VerticalHairline() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(Desk.line))
}

private fun rssiColor(rssi: Int): Color = Color(SignalScale.colorOfDbm(rssi))

private fun clock(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}

/** A native save dialog: the desk writes the file where the operator says, not to a fixed place. */
private fun save(suggested: String, content: String) {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Export session", java.awt.FileDialog.SAVE)
    dialog.file = suggested
    dialog.isVisible = true
    val directory = dialog.directory ?: return
    val chosen = dialog.file ?: return
    runCatching { File(directory, chosen).writeText(content) }
}
