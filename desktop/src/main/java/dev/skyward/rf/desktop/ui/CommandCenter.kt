package dev.skyward.rf.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.skyward.rf.core.LatLon
import dev.skyward.rf.core.ScanPlan
import dev.skyward.rf.core.Settings
import dev.skyward.rf.core.SignalScale
import dev.skyward.rf.desktop.DeskSettings
import dev.skyward.rf.desktop.hardware.Bands
import dev.skyward.rf.desktop.hardware.BoardState
import dev.skyward.rf.desktop.hardware.ConsoleLine
import dev.skyward.rf.desktop.hardware.PortInfo
import dev.skyward.rf.desktop.hardware.SensorHost
import dev.skyward.rf.desktop.session.SessionRecorder
import dev.skyward.rf.desktop.session.SessionSummary
import dev.skyward.rf.desktop.session.Sighting
import dev.skyward.rf.desktop.sky.DeskSky
import kotlinx.coroutines.delay
import java.io.File

/** The app's own four surfaces, on the desk. */
enum class DeskTab(val label: String) {
    MAP("MAP"),
    BOARD("BOARD"),
    LOG("LOG"),
    SETUP("SETUP"),
}

/**
 * The command centre.
 *
 * It is the app with the phone's limits removed: the globe is the same orthographic ball with
 * the same land outline and the same layers, the boards are plugged into this machine, and the
 * session is recorded here rather than on a handset that has to stay awake. The tabs are the
 * app's own - Map, Board (its Drive/scan console), Log and Setup - so nothing has to be learned
 * twice to use either end.
 */
@Composable
fun CommandCenter(host: SensorHost, recorder: SessionRecorder, sky: DeskSky, settings: DeskSettings) {
    val boards by host.boards.collectAsState()
    val ports by host.ports.collectAsState()
    val console by host.console.collectAsState()
    val skyState by sky.state.collectAsState()
    val cfg by settings.state.collectAsState()

    var tab by remember { mutableStateOf(DeskTab.MAP) }
    var selected by remember { mutableStateOf<String?>(null) }
    var layers by remember { mutableStateOf(GlobeLayers()) }
    // Spin starts at whatever Setup was left on - the phone's 1X by default: a desk that is
    // watching a globe wants it alive, and a stationary ball is indistinguishable from a frozen
    // app. The choice is written back to the shared settings, so both ends keep the same pace.
    var spin by remember { mutableStateOf(cfg.globeSpin.coerceIn(0, 2)) }
    var report by remember { mutableStateOf<WatchReport?>(null) }

    // The Watcher's own clock. It reads the same two tables a spoken report would, at the
    // interval set in Setup; 0 minutes keeps it quiet until somebody asks.
    LaunchedEffect(cfg.intelUpdateMinutes, cfg.watcherReportStyle) {
        val minutes = cfg.intelUpdateMinutes
        if (minutes <= 0) return@LaunchedEffect
        while (true) {
            delay(minutes * 60_000L)
            report = WatchReport(
                System.currentTimeMillis(),
                buildReport(sky.state.value, recorder.count, cfg.watcherReportStyle, clockOf(System.currentTimeMillis())),
            )
        }
    }
    // The second number in each INTEL row: what has been written into this session so far.
    val loggedCounts = remember(recorder.count) {
        val rows = recorder.rows()
        mapOf(
            "WIFI" to rows.count { it.kind == SessionRecorder.WIFI },
            "BLE" to rows.count { it.kind == SessionRecorder.BLE },
        )
    }
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

    // A board's own fix is the observer until somebody types one in Setup: the desk has no GPS
    // of its own, and borrowing the phone's position would be inventing one.
    LaunchedEffect(active?.reading?.lat, active?.reading?.lon) {
        val lat = active?.reading?.lat
        val lon = active?.reading?.lon
        if (lat != null && lon != null && sky.state.value.observer == null) sky.setObserver(LatLon(lat, lon))
    }

    Column(Modifier.fillMaxSize().background(Desk.bg)) {
        TopBar(
            ports = ports.size,
            connected = ordered.count { it.connected },
            board = active,
            tab = tab,
            onTab = { tab = it },
            since = startedAt,
            now = now,
            rows = rows,
        )
        Hairline()
        when (tab) {
            DeskTab.MAP -> MapPane(
                modifier = Modifier.weight(1f),
                host = host,
                ports = ports,
                boards = ordered,
                activePath = active?.path,
                onSelect = { selected = it },
                skyState = skyState,
                layers = layers,
                spin = spin,
                onSpin = {
                    val next = (spin + 1) % 3
                    spin = next
                    settings.update { it.copy(globeSpin = next) }
                },
                logged = loggedCounts,
                onLayer = { key, on ->
                    layers = when (key) {
                        "sat" -> layers.copy(sats = on)
                        "air" -> layers.copy(aircraft = on)
                        "boards" -> layers.copy(boards = on)
                        "wifi" -> layers.copy(wifi = on)
                        "ble" -> layers.copy(ble = on)
                        "mesh" -> layers.copy(mesh = on)
                        "uav" -> layers.copy(drones = on)
                        else -> layers
                    }
                    // The feeds stop being fetched as well as drawn: a layer that is off should
                    // not keep spending the community feed's allowance, and the mesh brokers and
                    // the BLE scanner should not be left running behind a hidden layer either.
                    if (key == "sat") sky.setLayer("sat", on)
                    if (key == "air") sky.setLayer("air", on)
                    if (key == "mesh") sky.setLayer("mesh", on)
                    if (key == "uav") sky.setLayer("uav", on)
                },
                active = active,
            )

            DeskTab.BOARD -> BoardPane(
                modifier = Modifier.weight(1f),
                host = host,
                ports = ports,
                boards = ordered,
                activePath = active?.path,
                onSelect = { selected = it },
                active = active,
                console = console[active?.path].orEmpty(),
            )

            DeskTab.LOG -> LogPane(
                modifier = Modifier.weight(1f),
                recorder = recorder,
                boards = ordered,
            )

            DeskTab.SETUP -> SetupPane(
                modifier = Modifier.weight(1f),
                sky = sky,
                skyState = skyState,
                cfg = cfg,
                settings = settings,
                host = host,
                report = report,
                onRead = {
                    report = WatchReport(
                        System.currentTimeMillis(),
                        buildReport(sky.state.value, recorder.count, cfg.watcherReportStyle, clockOf(System.currentTimeMillis())),
                    )
                },
                boards = ordered,
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
private fun TopBar(
    ports: Int,
    connected: Int,
    board: BoardState?,
    tab: DeskTab,
    onTab: (DeskTab) -> Unit,
    since: Long?,
    now: Long,
    rows: Int,
) {
    Row(
        Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("SIGNAL INTELGO", color = Desk.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.width(10.dp))
        Text("COMMAND CENTER", color = Desk.accent, fontSize = 11.sp, letterSpacing = 2.sp, fontFamily = Desk.mono)
        Spacer(Modifier.width(24.dp))
        DeskTab.entries.forEach { entry ->
            TabButton(entry, tab == entry) { onTab(entry) }
            Spacer(Modifier.width(4.dp))
        }
        Spacer(Modifier.weight(1f))
        Badge("$connected/$ports BOARDS", if (connected > 0) Desk.accent else Desk.dim)
        Spacer(Modifier.width(8.dp))
        Badge(board?.chip?.ifBlank { "no board" } ?: "no board", Desk.violet)
        if (since != null) {
            Spacer(Modifier.width(8.dp))
            Badge("SESSION ${clock(now - since)} · $rows", Desk.warn, filled = true)
        }
    }
}

@Composable
private fun TabButton(tab: DeskTab, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (on) Desk.panelHi else Color.Transparent, RoundedCornerShape(6.dp))
            .border(1.dp, if (on) Desk.line else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LayerIcon(
                when (tab.label) {
                    "MAP" -> Glyph.MAP
                    "BOARD" -> Glyph.BOARD
                    "LOG" -> Glyph.LOG
                    else -> Glyph.SETUP
                },
                if (on) Desk.accent else Desk.dim,
                12.dp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                tab.label,
                color = if (on) Desk.accent else Desk.dim,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                fontFamily = Desk.mono,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

// ---------------------------------------------------------------- map

@Composable
private fun MapPane(
    modifier: Modifier,
    host: SensorHost,
    ports: List<PortInfo>,
    boards: List<BoardState>,
    activePath: String?,
    onSelect: (String) -> Unit,
    skyState: DeskSky.State,
    layers: GlobeLayers,
    onLayer: (String, Boolean) -> Unit,
    active: BoardState?,
    spin: Int,
    onSpin: () -> Unit,
    logged: Map<String, Int>,
) {
    // The rail and the telemetry column are fixed widths and the map takes what is left. They are
    // sized from the pane rather than by weight: as weighted siblings the globe took the whole
    // remainder and pushed the telemetry column past the right edge of the window.
    BoxWithConstraints(modifier.fillMaxSize()) {
    val railWidth = 280.dp
    val panelWidth = if (maxWidth >= 900.dp) 320.dp else 250.dp
    val mapWidth = maxWidth - railWidth - panelWidth - 2.dp
    Row(Modifier.fillMaxSize()) {
        // The rail and the layer panel share one column: the panel is a header that opens over
        // the sensors, so switching a layer never covers the map being read.
        var intelOpen by remember { mutableStateOf(false) }
        Box(Modifier.width(railWidth).fillMaxHeight()) {
            SensorsRail(
                modifier = Modifier.fillMaxSize(),
                ports = ports,
                boards = boards,
                activePath = activePath,
                onSelect = onSelect,
                host = host,
            )
            IntelDropdown(
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                open = intelOpen,
                onOpen = { intelOpen = it },
                layers = layers,
                onLayer = onLayer,
                counts = mapOf(
                    "SAT" to skyState.sats.size,
                    "AIR" to skyState.aircraft.size,
                    "UAV" to skyState.drones.size,
                    "MESH" to skyState.mesh.size,
                    "ESP" to boards.count { it.connected },
                    "WIFI" to boards.sumOf { it.aps },
                    "BLE" to boards.sumOf { it.bles },
                ),
                logged = logged,
                notes = mapOf(
                    "sat" to skyState.satNote,
                    "air" to skyState.airNote,
                    "mesh" to skyState.meshNote,
                    "uav" to skyState.droneNote,
                ),
                spin = spin,
                onSpin = onSpin,
            )
        }
        VerticalHairline()
        MapView(
            // Whatever the two fixed columns did not take. A weight here let the globe claim the
            // whole row and carry the telemetry column off the right edge: inside the row the
            // compass box stops being the implicit receiver, so the width is computed above.
            modifier = Modifier.width(mapWidth).fillMaxHeight(),
            sats = skyState.sats,
            aircraft = skyState.aircraft,
            mesh = skyState.mesh,
            drones = skyState.drones,
            boards = boards.filter { it.connected },
            observer = skyState.observer,
            layers = layers,
            onLayer = onLayer,
            spin = spin,
            onSpin = onSpin,
        )
        VerticalHairline()
        Telemetry(modifier = Modifier.width(panelWidth).fillMaxHeight(), board = active)
    }
    }
}

// ---------------------------------------------------------------- board

@Composable
private fun BoardPane(
    modifier: Modifier,
    host: SensorHost,
    ports: List<PortInfo>,
    boards: List<BoardState>,
    activePath: String?,
    onSelect: (String) -> Unit,
    active: BoardState?,
    console: List<ConsoleLine>,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
    val railWidth = 310.dp
    val panelWidth = if (maxWidth >= 940.dp) 340.dp else 270.dp
    val mapWidth = maxWidth - railWidth - panelWidth - 2.dp
    Row(Modifier.fillMaxSize()) {
        SensorsRail(
            modifier = Modifier.width(railWidth).fillMaxHeight(),
            ports = ports,
            boards = boards,
            activePath = activePath,
            onSelect = onSelect,
            host = host,
        )
        VerticalHairline()
        ConsolePane(
            modifier = Modifier.width(mapWidth).fillMaxHeight(),
            board = active,
            lines = console,
            host = host,
        )
        VerticalHairline()
        Telemetry(modifier = Modifier.width(panelWidth).fillMaxHeight(), board = active)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LayerIcon(Glyph.SPIN, Desk.accent, 10.dp)
                    Spacer(Modifier.width(5.dp))
                    Text("REFRESH", color = Desk.accent, fontSize = 11.sp, fontFamily = Desk.mono)
                }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LayerIcon(Glyph.LINK, if (board.connected) Desk.warn else Desk.accent, 11.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(if (board.connected) "STOP" else "LINK", color = if (board.connected) Desk.warn else Desk.accent, fontSize = 11.sp)
                }
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
                host.send(board.path, if (ScanPlan.hasWifi(board.plan)) "scanap" else "blescan")
            },
            enabled = board.connected,
            colors = ButtonDefaults.buttonColors(containerColor = Desk.accent, contentColor = Desk.bg),
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LayerIcon(Glyph.SWEEP, Desk.bg, 12.dp)
                Spacer(Modifier.width(6.dp))
                Text("SWEEP NOW", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
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
private fun RowScope.StatCell(label: String, value: String, color: Color) {
    // Each cell takes its share of the panel, so a long value shortens rather than pushing the
    // row past the edge of the window.
    Column(Modifier.weight(1f).padding(end = 10.dp)) {
        Text(label, color = Desk.dim, fontSize = 9.sp, letterSpacing = 1.sp, fontFamily = Desk.mono, maxLines = 1)
        Text(
            value,
            color = color,
            fontSize = 13.sp,
            fontFamily = Desk.mono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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

// ---------------------------------------------------------------- log

@Composable
private fun LogPane(modifier: Modifier, recorder: SessionRecorder, boards: List<BoardState>) {
    val rows = remember(recorder.count) { recorder.rows() }
    val wifi = rows.count { it.kind == SessionRecorder.WIFI }
    val ble = rows.count { it.kind == SessionRecorder.BLE }
    val ghz5 = rows.count { it.kind == SessionRecorder.WIFI && it.band == Bands.W5 }

    Column(modifier.padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("LOG")
            Spacer(Modifier.weight(1f))
            Text("${rows.size} rows", color = Desk.dim, fontSize = 11.sp, fontFamily = Desk.mono)
            Spacer(Modifier.width(10.dp))
            Badge("$wifi AP", Desk.warn)
            Spacer(Modifier.width(6.dp))
            Badge("$ble BLE", Desk.violet)
            Spacer(Modifier.width(6.dp))
            Badge("$ghz5 ON 5 GHZ", Desk.accent)
        }
        Spacer(Modifier.height(12.dp))
        if (rows.isEmpty()) {
            Text(
                "Nothing recorded yet. START a session, link a board, and every network and advertiser it sweeps is filed here with the board's own fix.",
                color = Desk.dim,
                fontSize = 12.sp,
            )
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            LogCell("TIME", 80.dp, Desk.dim)
            LogCell("BOARD", 90.dp, Desk.dim)
            LogCell("KIND", 50.dp, Desk.dim)
            LogCell("ADDRESS", 150.dp, Desk.dim)
            LogCell("NAME", 180.dp, Desk.dim)
            LogCell("RSSI", 60.dp, Desk.dim)
            LogCell("CH", 40.dp, Desk.dim)
            LogCell("BAND", 60.dp, Desk.dim)
        }
        Hairline()
        LazyColumn(Modifier.weight(1f)) {
            items(rows.asReversed().take(500), key = { it.atMillis.toString() + it.address }) { row ->
                LogRow(row)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row {
            Button(
                onClick = { save("signal-intelgo-session.csv", recorder.csv()) },
                colors = ButtonDefaults.buttonColors(containerColor = Desk.accent, contentColor = Desk.bg),
                shape = RoundedCornerShape(6.dp),
            ) { Text("EXPORT CSV", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { save("signal-intelgo-session.json", recorder.json()) },
                colors = ButtonDefaults.buttonColors(containerColor = Desk.panelHi, contentColor = Desk.text),
                shape = RoundedCornerShape(6.dp),
            ) { Text("EXPORT JSON", fontSize = 11.sp) }
            Spacer(Modifier.width(14.dp))
            Text(
                "Boards linked: ${boards.count { it.connected }} · the desk records what it hears, not what it was told",
                color = Desk.dim,
                fontSize = 11.sp,
                fontFamily = Desk.mono,
            )
        }
    }
}

@Composable
private fun LogRow(row: Sighting) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        LogCell(clock(row.atMillis % 86_400_000).let { it }, 80.dp, Desk.dim)
        LogCell(row.board, 90.dp, Desk.dim)
        LogCell(row.kind, 50.dp, if (row.kind == SessionRecorder.WIFI) Desk.warn else Desk.violet)
        LogCell(row.address, 150.dp, Desk.text)
        LogCell(row.name.ifBlank { "—" }, 180.dp, Desk.text)
        LogCell(row.rssi.toString(), 60.dp, rssiColor(row.rssi))
        LogCell(if (row.channel > 0) row.channel.toString() else "—", 40.dp, Desk.dim)
        LogCell(row.band.ifBlank { "—" }, 60.dp, if (row.band == Bands.W5) Desk.accent else Desk.dim)
    }
}

@Composable
private fun LogCell(text: String, width: androidx.compose.ui.unit.Dp, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        fontFamily = Desk.mono,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width),
    )
}

// ---------------------------------------------------------------- setup

@Composable
private fun SetupPane(
    modifier: Modifier,
    sky: DeskSky,
    skyState: DeskSky.State,
    cfg: Settings,
    settings: DeskSettings,
    host: SensorHost,
    report: WatchReport?,
    onRead: () -> Unit,
    boards: List<BoardState>,
) {
    var latText by remember { mutableStateOf(skyState.observer?.let { String.format("%.5f", it.lat) } ?: "") }
    var lonText by remember { mutableStateOf(skyState.observer?.let { String.format("%.5f", it.lon) } ?: "") }

    Column(modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
        SectionLabel("OBSERVER")
        Spacer(Modifier.height(8.dp))
        Text(
            "The globe and the aircraft ring are centred on an observer. A board with a GPS module is the observer when it has a fix; otherwise set one here - the desk has no receiver of its own.",
            color = Desk.dim,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = latText,
                onValueChange = { latText = it },
                singleLine = true,
                label = { Text("LAT", fontSize = 10.sp) },
                textStyle = TextStyle(fontFamily = Desk.mono, fontSize = 12.sp, color = Desk.text),
                modifier = Modifier.width(150.dp),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = lonText,
                onValueChange = { lonText = it },
                singleLine = true,
                label = { Text("LON", fontSize = 10.sp) },
                textStyle = TextStyle(fontFamily = Desk.mono, fontSize = 12.sp, color = Desk.text),
                modifier = Modifier.width(150.dp),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val lat = latText.trim().toDoubleOrNull()
                    val lon = lonText.trim().toDoubleOrNull()
                    if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                        sky.setObserver(LatLon(lat, lon))
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Desk.accent, contentColor = Desk.bg),
                shape = RoundedCornerShape(6.dp),
            ) { Text("SET", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(14.dp))
            Text(
                skyState.observer?.let { "at ${String.format("%.5f, %.5f", it.lat, it.lon)}" } ?: "no observer",
                color = Desk.dim,
                fontSize = 11.sp,
                fontFamily = Desk.mono,
            )
        }
        Spacer(Modifier.height(20.dp))
        Hairline()
        Spacer(Modifier.height(16.dp))
        SectionLabel("SATELLITE GROUPS")
        Spacer(Modifier.height(8.dp))
        Row {
            // The app's whole list, including the two constellations that carry it past eleven
            // thousand objects, plus CelesTrak's full catalogue as one switch.
            listOf("stations", "visual", "weather", "gps-ops", "science", "amateur", "starlink", "cubesat", "active").forEach { group ->
                val on = group in skyState.groups
                Box(
                    Modifier
                        .padding(end = 6.dp)
                        .background(if (on) Desk.cyan.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(6.dp))
                        .border(1.dp, if (on) Desk.cyan.copy(alpha = 0.6f) else Desk.line, RoundedCornerShape(6.dp))
                        .clickable { sky.toggleGroup(group) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        if (group == "active") "ALL" else group,
                        color = if (on) Desk.cyan else Desk.dim,
                        fontSize = 11.sp,
                        fontFamily = Desk.mono,
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Hairline()
        Spacer(Modifier.height(16.dp))
        SectionLabel("FEEDS")
        Spacer(Modifier.height(8.dp))
        Text("CelesTrak · ${skyState.satNote}", color = Desk.text, fontSize = 11.sp, fontFamily = Desk.mono)
        Text("api.adsb.lol · ${skyState.airNote} (30 mi ring)", color = Desk.text, fontSize = 11.sp, fontFamily = Desk.mono)
        Text("Meshtastic + MeshCore · ${skyState.meshNote} · ${skyState.mesh.size} nodes", color = Desk.text, fontSize = 11.sp, fontFamily = Desk.mono)
        Text("BLE Remote ID · ${skyState.droneNote} · ${skyState.drones.size} broadcasting", color = Desk.text, fontSize = 11.sp, fontFamily = Desk.mono)
        Text("${skyState.sats.size} satellites propagated · ${skyState.aircraft.size} aircraft in the ring", color = Desk.dim, fontSize = 11.sp, fontFamily = Desk.mono)
        Text(
            "Mesh and Remote ID are switched on from the INTEL panel on the map, so a feed only " +
                "runs while its layer is being looked at.",
            color = Desk.dim,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row {
            Button(
                onClick = { sky.reload() },
                colors = ButtonDefaults.buttonColors(containerColor = Desk.panelHi, contentColor = Desk.text),
                shape = RoundedCornerShape(6.dp),
            ) { Text("RELOAD ELEMENT SETS", fontSize = 11.sp) }
            Spacer(Modifier.width(10.dp))
            Text(
                "A set younger than six hours is reused without a fetch, exactly as on the phone.",
                color = Desk.dim,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(20.dp))
        Hairline()
        Spacer(Modifier.height(16.dp))
        SectionLabel("BOARDS")
        Spacer(Modifier.height(8.dp))
        if (boards.isEmpty()) {
            Text("No serial ports seen. A board on a cable is a radio the desk has and the phone does not always.", color = Desk.dim, fontSize = 11.sp)
        } else {
            // Each board is switched on and off from here as well as from the rail, and its plan is
            // cycled rather than picked from a list: a board offers only the bands its silicon has,
            // so the next plan is the honest choice on a firmware that does not answer queries.
            boards.forEach { board ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${board.path} · ${board.identity?.name ?: "unknown"}",
                            color = Desk.text,
                            fontSize = 11.sp,
                            fontFamily = Desk.mono,
                        )
                        Text(
                            "${board.chip.ifBlank { "chip not named" }} · ${board.planLabel}",
                            color = Desk.dim,
                            fontSize = 10.sp,
                            fontFamily = Desk.mono,
                        )
                    }
                    TextButton(onClick = { if (board.connected) host.disconnect(board.path) else host.connect(board.path) }) {
                        Text(
                            if (board.connected) "DISCONNECT" else "CONNECT",
                            color = if (board.connected) Desk.warn else Desk.accent,
                            fontSize = 10.sp,
                            fontFamily = Desk.mono,
                        )
                    }
                    TextButton(onClick = {
                        val options = board.options
                        val at = options.indexOf(board.plan)
                        host.setPlan(board.path, options[(at + 1).mod(options.size)])
                    }) { Text("PLAN", color = Desk.cyan, fontSize = 10.sp, fontFamily = Desk.mono) }
                }
            }
        }
        Text(
            "A board is polled only while it is connected: disconnecting leaves the port free for " +
                "another tool instead of holding it open behind a hidden window.",
            color = Desk.dim,
            fontSize = 11.sp,
        )

        Spacer(Modifier.height(20.dp))
        Hairline()
        Spacer(Modifier.height(16.dp))
        SectionLabel("MAP")
        Spacer(Modifier.height(8.dp))
        Text("AUTO-SPIN", color = Desk.dim, fontSize = 10.sp, letterSpacing = 1.2.sp, fontFamily = Desk.mono)
        Spacer(Modifier.height(6.dp))
        ChoiceChips(listOf("OFF", "1X", "3X"), cfg.globeSpin.coerceIn(0, 2)) { pick ->
            settings.update { it.copy(globeSpin = pick) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "The globe's own pace, shared with the phone: the same number is read by both maps.",
            color = Desk.dim,
            fontSize = 11.sp,
        )

        Spacer(Modifier.height(20.dp))
        Hairline()
        Spacer(Modifier.height(16.dp))
        SectionLabel("WATCHER")
        Spacer(Modifier.height(8.dp))
        Text(
            "The desk has no microphone, so the Watcher here is its written half: it reads the live " +
                "tables and the session log, and a report is exactly what a spoken one would have said.",
            color = Desk.dim,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
        SettingSwitch(
            label = "Timed intel update",
            note = if (cfg.intelUpdateMinutes == 0) "off - reads only when asked" else "every ${cfg.intelUpdateMinutes} minutes",
            on = cfg.intelUpdateMinutes > 0,
        ) { on ->
            settings.update { it.copy(intelUpdateMinutes = if (on) 5 else 0) }
        }
        if (cfg.intelUpdateMinutes > 0) {
            val minuteChoices = listOf(3, 5, 7, 10, 30)
            ChoiceChips(minuteChoices.map { "$it MIN" }, minuteChoices.indexOf(cfg.intelUpdateMinutes).coerceAtLeast(0)) { pick ->
                settings.update { it.copy(intelUpdateMinutes = minuteChoices[pick]) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("WATCHER READS", color = Desk.dim, fontSize = 10.sp, letterSpacing = 1.2.sp, fontFamily = Desk.mono)
        Spacer(Modifier.height(6.dp))
        val styles = listOf("intel", "entities", "both")
        ChoiceChips(listOf("INTEL", "ENTITIES", "BOTH"), styles.indexOf(cfg.watcherReportStyle).coerceAtLeast(0)) { pick ->
            settings.update { it.copy(watcherReportStyle = styles[pick]) }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onRead,
                colors = ButtonDefaults.buttonColors(containerColor = Desk.accent, contentColor = Desk.bg),
                shape = RoundedCornerShape(6.dp),
            ) { Text("READ NOW", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(12.dp))
            Text(
                report?.let { "last read ${clockOf(it.at)}" } ?: "nothing read yet",
                color = Desk.dim,
                fontSize = 11.sp,
                fontFamily = Desk.mono,
            )
        }
        report?.let {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth()
                    .background(Desk.panelHi, RoundedCornerShape(8.dp))
                    .border(1.dp, Desk.line, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                Text(it.text, color = Desk.text, fontSize = 11.sp, fontFamily = Desk.mono)
            }
        }

        Spacer(Modifier.height(20.dp))
        Hairline()
        Spacer(Modifier.height(16.dp))
        SectionLabel("SHARED WITH THE PHONE")
        Spacer(Modifier.height(8.dp))
        Text(
            "The handset's own settings, kept in the same file the desk now writes: a choice made " +
                "here is the choice the phone starts with. The desk stores them; the phone acts on them.",
            color = Desk.dim,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
        SettingSwitch("Hands-free voice", "the phone listens for the wake word", cfg.voiceEnabled) { on ->
            settings.update { it.copy(voiceEnabled = on) }
        }
        SettingSwitch("Spoken replies", "watch alerts use the glitched voice", cfg.voiceAudio) { on ->
            settings.update { it.copy(voiceAudio = on) }
        }
        SettingSwitch("Offline mode", "element sets and tiles already downloaded", cfg.offlineMode) { on ->
            settings.update { it.copy(offlineMode = on) }
        }
        SettingSwitch("Hide weak dots", "only plot entities above the red bin", cfg.dropWeakDots) { on ->
            settings.update { it.copy(dropWeakDots = on) }
        }
        Spacer(Modifier.height(10.dp))
        Text("DISTANCE", color = Desk.dim, fontSize = 10.sp, letterSpacing = 1.2.sp, fontFamily = Desk.mono)
        Spacer(Modifier.height(6.dp))
        val units = listOf("km", "mi")
        ChoiceChips(listOf("KM", "MI"), units.indexOf(cfg.distanceUnit).coerceAtLeast(0)) { pick ->
            settings.update { it.copy(distanceUnit = units[pick]) }
        }
        Spacer(Modifier.height(10.dp))
        Text("TEMPERATURE", color = Desk.dim, fontSize = 10.sp, letterSpacing = 1.2.sp, fontFamily = Desk.mono)
        Spacer(Modifier.height(6.dp))
        val temps = listOf("c", "f")
        ChoiceChips(listOf("C", "F"), temps.indexOf(cfg.tempUnit).coerceAtLeast(0)) { pick ->
            settings.update { it.copy(tempUnit = temps[pick]) }
        }
    }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                LayerIcon(Glyph.RECORD, if (liveSince == null) Desk.bg else Color.White, 10.dp)
                Spacer(Modifier.width(6.dp))
                Text(if (liveSince == null) "START" else "STOP", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
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

/** A row of equal choices with the picked one lit: the phone's segmented control, on the desk. */
@Composable
private fun ChoiceChips(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row {
        options.forEachIndexed { index, label ->
            val on = index == selected
            Box(
                Modifier
                    .padding(end = 6.dp)
                    .background(if (on) Desk.cyan.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(6.dp))
                    .border(1.dp, if (on) Desk.cyan.copy(alpha = 0.6f) else Desk.line, RoundedCornerShape(6.dp))
                    .clickable { onPick(index) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(label, color = if (on) Desk.cyan else Desk.dim, fontSize = 11.sp, fontFamily = Desk.mono)
            }
        }
    }
}

/** A labelled switch with the line that says what it does, as the app's settings rows read. */
@Composable
private fun SettingSwitch(label: String, note: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Desk.text, fontSize = 12.sp)
            if (note.isNotBlank()) Text(note, color = Desk.dim, fontSize = 10.sp)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

/** Wall-clock time for a report stamp: the desk reads local time, not the session's stopwatch. */
private fun clockOf(millis: Long): String =
    java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
        .format(java.time.LocalTime.ofSecondOfDay((millis / 1000) % 86_400))
