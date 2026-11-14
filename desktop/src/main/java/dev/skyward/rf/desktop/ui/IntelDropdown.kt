package dev.skyward.rf.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One row of the dropdown: what it is called, which layer it switches, and its glyph. */
private data class LayerRow(val label: String, val key: String, val glyph: Glyph, val color: Color)

/**
 * The layers, as a dropdown over the sensor rail.
 *
 * On the phone this is a floating panel on the map. On the desk the map is beside a rail that
 * already lists the boards, so the panel is a header on that rail that opens downward over the
 * sensors: the operator's eye stays on one column, and nothing covers the map they are reading.
 *
 * Each row carries two numbers, as the phone's rows do: what is being drawn now, and what has been
 * written into the session. A layer whose count is zero but whose session count is not is a layer
 * that was on earlier in the sweep.
 */
@Composable
fun IntelDropdown(
    modifier: Modifier = Modifier,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    layers: GlobeLayers,
    onLayer: (String, Boolean) -> Unit,
    counts: Map<String, Int>,
    logged: Map<String, Int>,
    notes: Map<String, String>,
    spin: Int,
    onSpin: () -> Unit,
) {
    val rows = listOf(
        LayerRow("SAT", "sat", Glyph.SAT, Desk.cyan),
        LayerRow("AIR", "air", Glyph.AIR, Desk.text),
        LayerRow("UAV", "uav", Glyph.UAV, Desk.warn),
        LayerRow("MESH", "mesh", Glyph.MESH, Color(0xFF7BD88F)),
        LayerRow("ESP", "boards", Glyph.ESP, Desk.accent),
        LayerRow("WIFI", "wifi", Glyph.WIFI, Desk.warn),
        LayerRow("BLE", "ble", Glyph.BLE, Desk.violet),
    )
    val liveTotal = rows.sumOf { counts[it.label] ?: 0 }
    // Off by default: a logged sweep can be hundreds of signals, and the panel sits over the
    // map. Turning it on is the operator saying they want to see the whole trail.
    var showAll by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        // The header is the control: closed it is one line of numbers, open it is the whole panel.
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (open) Desk.panelHi else Desk.panel, RoundedCornerShape(8.dp))
                .border(1.dp, if (open) Desk.line else Desk.line.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .clickable { onOpen(!open) }
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "INTEL",
                color = Desk.text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                fontFamily = Desk.mono,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "$liveTotal",
                color = Desk.accent,
                fontSize = 11.sp,
                fontFamily = Desk.mono,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (open) "HIDE" else "LAYERS",
                color = Desk.dim,
                fontSize = 9.sp,
                letterSpacing = 1.sp,
                fontFamily = Desk.mono,
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.rotate(if (open) 180f else 0f)) {
                LayerIcon(Glyph.CHEVRON, Desk.dim, 11.dp)
            }
        }

        if (!open) return@Column

        Column(
            Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .background(Desk.panel.copy(alpha = 0.98f), RoundedCornerShape(8.dp))
                .border(1.dp, Desk.line, RoundedCornerShape(8.dp))
                .padding(vertical = 6.dp, horizontal = 10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "LIVE",
                    color = Desk.dim,
                    fontSize = 8.sp,
                    letterSpacing = 1.sp,
                    fontFamily = Desk.mono,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "LOGGED",
                    color = Desk.dim,
                    fontSize = 8.sp,
                    letterSpacing = 1.sp,
                    fontFamily = Desk.mono,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (showAll) "ALL" else "SOME",
                    color = if (showAll) Desk.cyan else Desk.dim,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    fontFamily = Desk.mono,
                    modifier = Modifier
                        .background(if (showAll) Desk.panelHi else Desk.panel, RoundedCornerShape(4.dp))
                        .border(1.dp, if (showAll) Desk.cyan.copy(alpha = 0.5f) else Desk.line, RoundedCornerShape(4.dp))
                        .clickable { showAll = !showAll }
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
            rows.forEach { row ->
                val on = when (row.key) {
                    "sat" -> layers.sats
                    "air" -> layers.aircraft
                    "uav" -> layers.drones
                    "mesh" -> layers.mesh
                    "boards" -> layers.boards
                    "wifi" -> layers.wifi
                    "ble" -> layers.ble
                    else -> false
                }
                LayerRowView(
                    row = row,
                    on = on,
                    live = counts[row.label] ?: 0,
                    logged = logged[row.label] ?: 0,
                    showAll = showAll,
                    onClick = { onLayer(row.key, !on) },
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Desk.panelHi.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                    .clickable { onSpin() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LayerIcon(Glyph.SPIN, if (spin > 0) Desk.cyan else Desk.dim, 13.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "GLOBE SPIN",
                    color = if (spin > 0) Desk.text else Desk.dim,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    fontFamily = Desk.mono,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when (spin) {
                        1 -> "1X"
                        2 -> "3X"
                        else -> "OFF"
                    },
                    color = if (spin > 0) Desk.cyan else Desk.dim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = Desk.mono,
                )
            }

            Spacer(Modifier.height(8.dp))
            // Each feed says what it is doing, so an empty layer is never mistaken for a working
            // one: "feed off" and "no BLE radio" are different facts from "nothing in range".
            listOf("sat", "air", "mesh", "uav").forEach { key ->
                val note = notes[key].orEmpty()
                if (note.isNotBlank()) {
                    Text(
                        note,
                        color = Desk.dim,
                        fontSize = 9.sp,
                        fontFamily = Desk.mono,
                        maxLines = 2,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LayerRowView(
    row: LayerRow,
    on: Boolean,
    live: Int,
    logged: Int,
    showAll: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LayerIcon(row.glyph, if (on) row.color else row.color.copy(alpha = 0.3f), 14.dp)
        Spacer(Modifier.width(9.dp))
        Text(
            row.label,
            color = if (on) Desk.text else Desk.dim,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            fontFamily = Desk.mono,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            live.toString(),
            color = if (on) Desk.text else Desk.dim.copy(alpha = 0.6f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = Desk.mono,
            // Wide enough for a five-digit count: a session's satellite total did not fit and the
            // number was clipped at the fourth digit.
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(46.dp),
        )
        Spacer(Modifier.weight(1f))
        LoggedDots(
            count = logged,
            color = if (on) row.color else row.color.copy(alpha = 0.45f),
            showAll = showAll,
        )
    }
}

/**
 * One dot per logged signal.
 *
 * The number beside a layer said how many signals had been written down but not whether that was
 * three streets or three cities, so the trail is drawn instead: a dot each, wrapped into rows that
 * stay the same width as the panel. SOME keeps the panel short enough to read over the map; ALL
 * shows the whole trail, which on a long sweep is hundreds of dots.
 */
@Composable
private fun LoggedDots(count: Int, color: Color, showAll: Boolean) {
    val perRow = 9
    val cap = if (showAll) 450 else 27
    val shown = count.coerceAtMost(cap)
    val rows = ((shown + perRow - 1) / perRow).coerceAtLeast(1)
    val cell = 5.dp
    val dot = 2.5.dp

    Row(verticalAlignment = Alignment.Top) {
        Canvas(Modifier.width((cell * perRow)).height(cell * rows)) {
            val step = cell.toPx()
            val radius = dot.toPx() / 2f
            for (i in 0 until shown) {
                drawCircle(
                    color = color,
                    radius = radius,
                    center = Offset(
                        x = (i % perRow) * step + radius,
                        y = (i / perRow) * step + radius,
                    ),
                )
            }
        }
        if (count > shown) {
            Text(
                "+${count - shown}",
                color = Desk.dim,
                fontSize = 8.sp,
                fontFamily = Desk.mono,
                modifier = Modifier.padding(start = 3.dp, top = 1.dp),
            )
        }
    }
}
