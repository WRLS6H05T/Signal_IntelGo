package dev.skyward.rf.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.skyward.rf.Container
import dev.skyward.rf.core.Settings
import dev.skyward.rf.scan.EspImage
import dev.skyward.rf.scan.UsbBoards
import dev.skyward.rf.ui.theme.Sky
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The board's console, in a panel, plus the flash that writes this build's own firmware.
 *
 * This is the only way into a board from the phone: the same USB session the app identifies
 * boards on, handed to a person instead of the poller. The command line is not a shell invented
 * here - whatever the firmware understands is what works, and the GPS row is just two of those
 * commands with the values remembered in Settings.
 */
@Composable
fun EspSerialDialog(c: Container, settings: Settings, onDismiss: () -> Unit) {
    val boards by c.esp32Usb.state.collectAsState()
    val terminal by c.espTerminal.state.collectAsState()
    val wire by c.espFlasher.wire.collectAsState()
    val scope = rememberCoroutineScope()

    var selectedId by remember { mutableStateOf<Int?>(null) }
    var input by remember { mutableStateOf("") }
    var pinText by remember { mutableStateOf(settings.espGpsPin.toString()) }
    var baudText by remember { mutableStateOf(settings.espGpsBaud.toString()) }

    var showFlash by remember { mutableStateOf(false) }
    var image by remember { mutableStateOf(EspImage.C3) }
    var flashing by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0f) }
    var outcome by remember { mutableStateOf<String?>(null) }

    val board = boards.firstOrNull { it.deviceId == selectedId } ?: boards.firstOrNull()
    val scroll = rememberScrollState()

    // The log follows the board: a firmware that answers ten lines to `help` should end with
    // the prompt visible rather than three scrolls up from it.
    LaunchedEffect(terminal.lines.size) {
        runCatching { scroll.animateScrollTo(scroll.maxValue) }
    }

    Dialog(onDismissRequest = { if (!flashing) { c.espTerminal.close(); onDismiss() } }) {
        Surface(
            color = Sky.surface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Sky.outline),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "ESP32 SERIAL",
                    color = Sky.esp32,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (boards.isEmpty()) "No board on the cable" else "One board at a time; the poller waits",
                    color = Sky.textDim,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (b in boards) {
                        Chip(
                            text = b.label.take(18),
                            selected = b.deviceId == board?.deviceId,
                            accent = Sky.esp32,
                        ) { selectedId = b.deviceId }
                    }
                }
                if (boards.isEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Plug the board in, then rescan.", color = Sky.textDim, fontSize = 9.sp)
                        Spacer(Modifier.width(6.dp))
                        Chip(text = "RESCAN", selected = false, accent = Sky.esp32) { c.esp32Usb.rescan() }
                    }
                }
                if (boards.size > 1) Spacer(Modifier.height(4.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip(
                        text = if (terminal.open) "DISCONNECT" else "CONNECT",
                        selected = terminal.open,
                        accent = Sky.esp32,
                    ) {
                        if (terminal.open) {
                            c.espTerminal.close()
                        } else {
                            board?.let { c.espTerminal.open(it, settings.esp32Baud) }
                        }
                    }
                    Chip(text = "RESET", selected = false, accent = Sky.esp32) {
                        if (terminal.open) c.espTerminal.reset()
                    }
                    Chip(text = "FLASH FIRMWARE", selected = false, accent = Sky.warn) {
                        image = if (board?.chip?.contains("C5", true) == true) EspImage.C5 else EspImage.C3
                        outcome = null
                        showFlash = true
                    }
                    Text(
                        if (terminal.open) "open \u00b7 ${terminal.bytes} B" else "closed",
                        color = if (terminal.open) Sky.esp32 else Sky.textDim,
                        fontSize = 9.sp,
                    )
                    if (terminal.ports > 1) {
                        Chip(
                            text = "PORT ${terminal.portIndex + 1}/${terminal.ports}",
                            selected = false,
                            accent = Sky.esp32,
                        ) { c.espTerminal.nextPort() }
                    }
                }

                terminal.error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = Sky.warn, fontSize = 10.sp)
                }

                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 200.dp)
                        .background(Sky.surfaceHigh, RoundedCornerShape(8.dp))
                        .border(1.dp, Sky.outline, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                        .verticalScroll(scroll),
                ) {
                    if (terminal.lines.isEmpty()) {
                        Text("Connect to read the console.", color = Sky.textDim, fontSize = 9.sp)
                    }
                    for (line in terminal.lines) {
                        Text(
                            line,
                            color = if (line.startsWith(">")) Sky.esp32 else Sky.text,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Sky.text,
                        ),
                        label = { Text("command", fontSize = 9.sp) },
                    )
                    Spacer(Modifier.width(6.dp))
                    Chip(text = "SEND", selected = false, accent = Sky.esp32) {
                        if (input.isNotBlank()) {
                            c.espTerminal.send(input)
                            input = ""
                        }
                    }
                }
                Text(
                    "help \u00b7 status \u00b7 scan \u00b7 gpspin <n> \u00b7 gpsbaud <n>",
                    color = Sky.textDim,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                )

                Spacer(Modifier.height(8.dp))
                Text("GPS PIN AND BAUD", color = Sky.textDim, fontSize = 9.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = pinText,
                        onValueChange = { pinText = it.filter { ch -> ch.isDigit() }.take(2) },
                        modifier = Modifier.width(74.dp),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Sky.text,
                        ),
                        label = { Text("pin", fontSize = 9.sp) },
                    )
                    Spacer(Modifier.width(6.dp))
                    OutlinedTextField(
                        value = baudText,
                        onValueChange = { baudText = it.filter { ch -> ch.isDigit() }.take(6) },
                        modifier = Modifier.width(96.dp),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Sky.text,
                        ),
                        label = { Text("gps baud", fontSize = 9.sp) },
                    )
                    Spacer(Modifier.width(6.dp))
                    Chip(text = "SET", selected = false, accent = Sky.esp32) {
                        val pin = pinText.toIntOrNull()
                        val baud = baudText.toIntOrNull()
                        c.settingsStore.update { s ->
                            s.copy(
                                espGpsPin = pin ?: s.espGpsPin,
                                espGpsBaud = baud ?: s.espGpsBaud,
                            )
                        }
                        if (pin != null) c.espTerminal.send("gpspin $pin")
                        if (baud != null) c.espTerminal.send("gpsbaud $baud")
                    }
                }
                Text(
                    "The board saves both to NVS. Baud is the module's, not the console's.",
                    color = Sky.textDim,
                    fontSize = 9.sp,
                )

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(text = "CLOSE", selected = false, accent = Sky.textDim) {
                        if (!flashing) {
                            c.espTerminal.close()
                            onDismiss()
                        }
                    }
                }
            }
        }
    }

    if (showFlash) {
        val firmware = remember(image) { c.espFlasher.firmware(image) }
        AlertDialog(
            onDismissRequest = { if (!flashing) showFlash = false },
            title = { Text("Flash ${image.label}", color = Sky.text, fontSize = 15.sp) },
            text = {
                Column {
                    Detail("Port", board?.label ?: "none")
                    Detail("USB", board?.let { String.format("%04X:%04X", it.vendorId, it.productId) } ?: "\u2014")
                    Detail("Transport", board?.transport?.label ?: "\u2014")
                    Detail("Chip", image.chip)
                    Detail("Image", firmware?.let { "${it.bytes / 1024} KiB \u00b7 v${it.version}" } ?: "not bundled")
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("C3 MINI", image == EspImage.C3, Sky.esp32) { image = EspImage.C3 }
                        Chip("C5 MINI", image == EspImage.C5, Sky.esp32) { image = EspImage.C5 }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This erases the board's flash and replaces whatever firmware is on it, " +
                            "including GhostESP or Marauder.",
                        color = Sky.warn,
                        fontSize = 10.sp,
                    )
                    if (flashing) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = Sky.esp32,
                        )
                        Text(step, color = Sky.textDim, fontSize = 9.sp)
                    }
                    if (wire.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 140.dp)
                                .background(Sky.surfaceHigh, RoundedCornerShape(8.dp))
                                .border(1.dp, Sky.outline, RoundedCornerShape(8.dp))
                                .padding(6.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text("WIRE LOG", color = Sky.textDim, fontSize = 8.sp)
                            for (line in wire.takeLast(40)) {
                                Text(
                                    line,
                                    color = Sky.textDim,
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                    outcome?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = Sky.esp32, fontSize = 10.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = board != null && !flashing && firmware != null,
                    onClick = {
                        val target = board ?: return@TextButton
                        scope.launch {
                            // The terminal holds the interface and the USB stack will not let a
                            // second claim have it, so the port is handed over before the flash
                            // starts. Without this the flasher only ever saw a busy port.
                            c.espTerminal.close()
                            flashing = true
                            outcome = null
                            val result = withContext(Dispatchers.IO) {
                                c.espFlasher.flash(target, image) { s, p ->
                                    step = s
                                    progress = p
                                }
                            }
                            flashing = false
                            outcome = result.fold(
                                { "Flashed: $it" },
                                { it.message ?: "The flash failed" },
                            )
                        }
                    },
                ) { Text("CONFIRM FLASH", color = Sky.warn, fontSize = 11.sp) }
            },
            dismissButton = {
                TextButton(enabled = !flashing, onClick = { showFlash = false }) {
                    Text("CANCEL", color = Sky.textDim, fontSize = 11.sp)
                }
            },
        )
    }
}

@Composable
private fun Detail(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(key, color = Sky.textDim, fontSize = 10.sp, modifier = Modifier.width(78.dp))
        Text(value, color = Sky.text, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

/** The app's chip, with the accent passed in rather than read from a theme slot. */
@Composable
internal fun Chip(text: String, selected: Boolean, accent: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
        shape = RoundedCornerShape(9.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Sky.surfaceHigh,
            labelColor = Sky.textDim,
            selectedContainerColor = accent.copy(alpha = 0.22f),
            selectedLabelColor = accent,
        ),
    )
}
