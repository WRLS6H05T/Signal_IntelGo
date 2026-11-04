package dev.skyward.rf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyward.rf.Container
import dev.skyward.rf.net.WigleApi
import dev.skyward.rf.ui.theme.Sky
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * WiGLE, in full: the key, the upload, the history of uploads, the KML of any of them, and a
 * search over everything this account has ever contributed.
 *
 * The panel is deliberately a workbench rather than a single button. WiGLE gives each account a
 * daily query allowance, so every button here is one deliberate request, and the search pages
 * forward only when asked instead of draining the allowance to fill a list nobody scrolled.
 */
@Composable
fun WigleCard(c: Container) {
    val settings by c.settingsStore.state.collectAsState()
    var apiName by remember(settings.wigleName) { mutableStateOf(settings.wigleName) }
    var apiToken by remember(settings.wigleToken) { mutableStateOf(settings.wigleToken) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var uploads by remember { mutableStateOf<List<WigleApi.Upload>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("wifi") }
    var results by remember { mutableStateOf<List<WigleApi.Wireless>>(emptyList()) }
    var total by remember { mutableStateOf(0L) }
    var next by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Every action starts the same way: the key is saved once, then the call runs off the UI
    // thread with the busy flag held for the duration.
    fun keyed(action: suspend (String, String) -> Unit) {
        val user = apiName.trim()
        val key = apiToken.trim()
        if (user.isBlank() || key.isBlank()) {
            status = "Add the API name and token first"
            return
        }
        c.settingsStore.update { it.copy(wigleName = user, wigleToken = key) }
        scope.launch {
            busy = true
            action(user, key)
            busy = false
        }
    }

    SectionCard(
        title = "WiGLE",
        trailing = when {
            busy -> "working"
            settings.wigleToken.isNotBlank() -> "key set"
            else -> "no key"
        },
    ) {
        Text(
            "The whole WiGLE API for this account: upload the drive, list everything already " +
                "uploaded, pull the KML of any upload, and search your own contributions. The " +
                "key is from wigle.net/account and never leaves the phone except to WiGLE.",
            color = Sky.textDim,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiName,
            onValueChange = { apiName = it },
            label = { Text("API name", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = apiToken,
            onValueChange = { apiToken = it },
            label = { Text("API token", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction(if (busy) "WORKING" else "UPLOAD CSV", Sky.cyan) {
                keyed { user, key ->
                    val out = c.wigle.upload(
                        csv = c.signalLog.exportWigleCsv(),
                        fileName = "signal_intelgo_${System.currentTimeMillis()}.csv",
                        name = user,
                        token = key,
                    )
                    status = out.message
                }
            }
            SmallAction("VERIFY KEY", Sky.mesh) {
                keyed { user, key -> status = c.wigle.verify(user, key).message }
            }
            SmallAction("SHARE CSV") {
                shareExport(
                    context,
                    "signal_intelgo_wigle_${System.currentTimeMillis()}.csv",
                    "text/csv",
                    c.signalLog.exportWigleCsv(),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("MY UPLOADS", style = SkywardThemeLabel(), color = Sky.textDim)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction("LIST UPLOADS", Sky.cyan) {
                keyed { user, key ->
                    val page = c.wigle.uploads(user, key)
                    uploads = page.results
                    status = if (page.ok) "${page.results.size} uploads on this account" else page.message
                }
            }
            SmallAction("SAVE ALL KML", Sky.warn) {
                keyed { user, key ->
                    var start = 0
                    var saved = 0
                    var seen = 0
                    var stopped = false
                    while (!stopped) {
                        val page = c.wigle.uploads(user, key, start)
                        if (!page.ok) {
                            status = page.message
                            stopped = true
                            break
                        }
                        if (page.results.isEmpty()) break
                        for (u in page.results) {
                            val (kml, message) = c.wigle.kml(u.transid, user, key)
                            if (kml != null) {
                                c.saveWigleFile("${u.transid}.kml", kml)
                                saved++
                            } else {
                                status = message
                            }
                            status = "KML $saved of ${seen + page.results.size}..."
                            // A polite gap: WiGLE throttles, and a burst of hundreds of files
                            // would hit the allowance for nothing.
                            delay(150)
                        }
                        seen += page.results.size
                        start += page.results.size
                        if (page.results.size < WigleApi.PAGE) stopped = true
                    }
                    c.wardrive.refreshSessions()
                    status = "Saved $saved of $seen uploads to ${c.wigleDir.absolutePath}"
                }
            }
        }
        uploads.take(8).forEach { u ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        u.transid.ifBlank { "upload" },
                        color = Sky.text,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "${u.fileName.ifBlank { "file" }} · ${u.discovered}/${u.total} new · " +
                            u.firstTime.take(10) + if (u.status.isNotBlank()) " · ${u.status}" else "",
                        color = Sky.textDim,
                        fontSize = 9.sp,
                    )
                }
                SmallAction("KML", Sky.cyan) {
                    keyed { user, key ->
                        val (kml, message) = c.wigle.kml(u.transid, user, key)
                        status = if (kml != null) {
                            "Saved ${c.saveWigleFile("${u.transid}.kml", kml)}"
                        } else {
                            message
                        }
                    }
                }
            }
        }
        if (uploads.size > 8) {
            Spacer(Modifier.height(4.dp))
            Text("+${uploads.size - 8} more on this page", color = Sky.textDim, fontSize = 9.sp)
        }

        Spacer(Modifier.height(12.dp))
        Text("SEARCH MY DATA", style = SkywardThemeLabel(), color = Sky.textDim)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("SSID, name or BSSID", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in listOf("wifi" to "WI-FI", "bt" to "BLUETOOTH", "cell" to "CELL")) {
                SmallAction(
                    option.second,
                    if (kind == option.first) Sky.cyan else Sky.textDim,
                ) {
                    kind = option.first
                    results = emptyList()
                    total = 0
                    next = ""
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction("SEARCH MINE", Sky.cyan) {
                keyed { user, key ->
                    val page = c.wigle.search(user, key, query, kind)
                    if (page.ok) {
                        results = page.results
                        total = page.total
                        next = page.next
                        status = if (page.total == 0L) {
                            "Nothing of yours matches that"
                        } else {
                            "${page.results.size} of $total matches, newest first"
                        }
                    } else {
                        status = page.message
                    }
                }
            }
            if (next.isNotBlank()) {
                SmallAction("NEXT PAGE", Sky.mesh) {
                    keyed { user, key ->
                        val page = c.wigle.search(user, key, query, kind, next)
                        if (page.ok) {
                            results = results + page.results
                            next = page.next
                            status = "${results.size} of $total matches"
                        } else {
                            status = page.message
                        }
                    }
                }
            }
            if (results.isNotEmpty()) {
                SmallAction("SAVE CSV") {
                    shareExport(
                        context,
                        "wigle_${kind}_${System.currentTimeMillis()}.csv",
                        "text/csv",
                        resultsCsv(results),
                    )
                }
            }
        }
        results.take(20).forEach { r ->
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        r.name.ifBlank { "<hidden>" },
                        color = Sky.text,
                        fontSize = 11.sp,
                    )
                    Text(
                        r.netid + " · " + (r.lat?.let { lat ->
                            String.format(Locale.US, "%.5f, %.5f", lat, r.lon ?: 0.0)
                        } ?: "no position") + " · ch ${r.channel} · qos ${r.qos}" +
                            if (r.encryption.isNotBlank()) " · ${r.encryption}" else "",
                        color = Sky.textDim,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        if (results.size > 20) {
            Spacer(Modifier.height(4.dp))
            Text("+${results.size - 20} more loaded", color = Sky.textDim, fontSize = 9.sp)
        }

        if (status.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                status,
                color = when {
                    status.startsWith("Saved") || status.startsWith("uploaded") -> Sky.mesh
                    status.startsWith("WiGLE rejected") || status.startsWith("WiGLE's daily") -> Sky.warn
                    else -> Sky.textDim
                },
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** One row per network, in the same shape the app's own exports use. */
private fun resultsCsv(rows: List<WigleApi.Wireless>): String {
    val sb = StringBuilder("netid,name,type,lat,lon,channel,encryption,qos,first_seen,last_seen\n")
    for (r in rows) {
        sb.append(r.netid).append(',')
            .append('"').append(r.name.replace("\"", "\"\"")).append("\",")
            .append(r.type).append(',')
            .append(r.lat?.let { String.format(Locale.US, "%.6f", it) } ?: "").append(',')
            .append(r.lon?.let { String.format(Locale.US, "%.6f", it) } ?: "").append(',')
            .append(r.channel).append(',')
            .append(r.encryption).append(',')
            .append(r.qos).append(',')
            .append(r.firstSeen).append(',')
            .append(r.lastSeen).append('\n')
    }
    return sb.toString()
}
