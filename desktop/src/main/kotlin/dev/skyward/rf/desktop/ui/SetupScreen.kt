package dev.skyward.rf.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyward.rf.core.Units
import dev.skyward.rf.desktop.DesktopContainer
import dev.skyward.rf.desktop.link.LinkBridge
import dev.skyward.rf.desktop.esp.EspFlash
import dev.skyward.rf.desktop.esp.EspImage
import dev.skyward.rf.desktop.esp.SerialBoard
import dev.skyward.rf.desktop.esp.SerialConsole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Setup: the settings the desk actually acts on, the link that feeds it, and the sensor boards.
 *
 * The phone's Setup screen is mostly about its own radios - Wi-Fi and BLE permissions, the
 * scanning plan, voice, mesh. None of that exists here, so those blocks are not copied: what is
 * here is every setting the desktop honours, the BLE bridge that lets it hear at all, and the
 * USB side of a board - console, baud and the same bundled C3/C5 firmware the phone writes.
 */
@Composable
fun SetupScreen(container: DesktopContainer, modifier: Modifier = Modifier) {
    val settings by container.settingsStore.state.collectAsState()
    val host by container.host.state.collectAsState()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 90.dp),
    ) {
        Text("SETUP", color = Sky, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        Text("what the desk does with what it hears", color = TextDim, fontSize = 11.sp)
        Spacer(Modifier.height(14.dp))

        Section("Display and units") {
            Toggle("Miles instead of kilometres", settings.distanceUnit == Units.MILES) { on ->
                container.settingsStore.update { it.copy(distanceUnit = if (on) Units.MILES else Units.KM) }
            }
            Toggle("Fahrenheit instead of Celsius", settings.tempUnit == Units.FAHRENHEIT) { on ->
                container.settingsStore.update { it.copy(tempUnit = if (on) Units.FAHRENHEIT else Units.CELSIUS) }
            }
            Toggle("Spinning globe", settings.globeSpin > 0) { on ->
                container.settingsStore.update { it.copy(globeSpin = if (on) 1 else 0) }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Stored data stays metric: the unit is applied where a number is written on screen, so switching it never rewrites a saved drive.",
                color = TextDim,
                fontSize = 10.sp,
            )
        }

        Spacer(Modifier.height(10.dp))
        Section("Host link", trailing = if (host.adapterOk) "adapter ready" else "not ready") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(8.dp).height(8.dp).clip(CircleShape).background(if (host.adapterOk) Mint else Amber))
                Spacer(Modifier.width(8.dp))
                Text(host.adapterDetail, color = TextDim, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("Link", color = TextDim, fontSize = 10.sp, letterSpacing = 1.sp)
            Text(container.lan.detail, color = TextMain, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text(container.usb.detail, color = TextMain, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(4.dp))
            Text(
                "${host.devices.size} devices  ·  ${host.framesIn} frames in  ·  ${host.framesOut} out",
                color = TextDim,
                fontSize = 10.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "No radio and no helper process. The desk listens on TCP ${LinkBridge.PORT} and answers the phone's UDP probe on ${LinkBridge.DISCOVERY_PORT}, so a phone on the same Wi-Fi - or on USB tethering - finds it and dials in. A device on a USB cable is read on its own COM port.",
                color = TextDim,
                fontSize = 10.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text("USB cable", color = TextDim, fontSize = 10.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(4.dp))
            val cables = container.usb.ports()
            if (cables.isEmpty()) {
                Text("No COM port found. Plug the device in and reopen this tab.", color = TextDim, fontSize = 10.sp)
            } else {
                cables.forEach { board ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            board.pretty,
                            color = TextDim,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        Action("OPEN") { container.usb.toggle(board.path) }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Boards(container)
    }
}

/**
 * USB sensor boards: the console and the flasher.
 *
 * A board on the desk's COM port is the one thing here that is exactly what the phone does: the
 * same ROM bootloader frames, the same two bundled images, the same whole-chip erase. Only the
 * transport differs, so a board flashed here boots the same firmware a phone would have written.
 */
@Composable
private fun Boards(container: DesktopContainer) {
    val scope = rememberCoroutineScope()
    val console = remember { SerialConsole() }
    val output by console.text.collectAsState()

    var ports by remember { mutableStateOf(container.esp.ports()) }
    var choice by remember { mutableStateOf<SerialBoard?>(null) }
    var image by remember { mutableStateOf(EspImage.C3) }
    var command by remember { mutableStateOf("scan") }
    var step by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0f) }
    var result by remember { mutableStateOf<String?>(null) }
    var flashing by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { console.close() }
    }

    Section("Sensor boards", trailing = "${ports.size} ports") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                ports.forEach { board ->
                    val active = choice?.path == board.path
                    Text(
                        (if (active) "▸ " else "  ") + board.pretty,
                        color = if (active) Sky else TextDim,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .background(if (active) Sky.copy(alpha = 0.08f) else Color.Transparent)
                            .padding(vertical = 3.dp, horizontal = 4.dp)
                            .let { it }
                            .then(Modifier),
                    )
                }
                if (ports.isEmpty()) {
                    Text("No serial port found. Plug the board in and refresh.", color = TextDim, fontSize = 10.sp)
                }
            }
            Action("REFRESH") {
                ports = container.esp.ports()
                choice = ports.firstOrNull()
            }
        }

        if (ports.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ports.take(6).forEach { board ->
                    Action(board.path, color = if (choice?.path == board.path) Sky else TextDim) { choice = board }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("CONSOLE", color = TextDim, fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 180.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Ink)
                .border(1.dp, Line, RoundedCornerShape(8.dp))
                .padding(8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                if (output.isBlank()) "The board's own output appears here once the port is open." else output,
                color = if (output.isBlank()) TextDim else Mint,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            label = { Text("Command", fontSize = 10.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Action(if (console.isOpen) "PORT OPEN" else "OPEN PORT", color = if (console.isOpen) Mint else Sky) {
                val board = choice ?: return@Action
                val opened = console.open(board.path, 115200)
                result = if (opened) "Opened ${board.path} at 115200" else "Could not open ${board.path}"
            }
            Action("SEND") {
                result = if (console.send(command)) "Sent: $command" else "Open the port first"
            }
            Action("CLEAR") { console.clear() }
            Action("CLOSE", color = TextDim) { console.close() }
        }

        Spacer(Modifier.height(12.dp))
        Text("FLASH FIRMWARE", color = TextDim, fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        val firmware = container.esp.firmware(image)
        Text(
            "v${firmware?.version ?: "?"}  ·  ${firmware?.bytes?.div(1024) ?: 0} KiB  ·  ${image.chip}",
            color = TextDim,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EspImage.entries.forEach { candidate ->
                Action(candidate.label, color = if (image == candidate) Amber else TextDim) { image = candidate }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action(if (flashing) "FLASHING…" else "FLASH ${image.label.uppercase()}", color = Amber, enabled = !flashing && choice != null) {
                val board = choice ?: return@Action
                console.close()
                flashing = true
                step = "Starting"
                progress = 0f
                result = null
                scope.launch(Dispatchers.IO) {
                    val outcome = container.esp.flash(board, image) { text, value ->
                        step = text
                        progress = value
                    }
                    result = outcome.fold({ "Flashed: $it" }, { "Failed: ${it.message}" })
                    flashing = false
                }
            }
            Text(step, color = TextDim, fontSize = 10.sp, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Line)) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(Amber))
        }
        if (result != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                result!!,
                color = if (result!!.startsWith("Failed") || result!!.startsWith("Could")) Amber else Mint,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Flashing erases the whole 4 MiB chip before it writes: the old partition table and NVS go with it. Do not unplug the board while blocks are being written.",
            color = TextDim,
            fontSize = 10.sp,
        )
    }
}
