package dev.skyward.rf.desktop.session

import dev.skyward.rf.desktop.hardware.Bands
import dev.skyward.rf.scan.Esp32Console

/** One sighting, as it goes into the session file. */
data class Sighting(
    val atMillis: Long,
    val board: String,
    val chip: String,
    val kind: String,
    val address: String,
    val name: String,
    val rssi: Int,
    val channel: Int,
    val band: String,
    val lat: Double?,
    val lon: Double?,
)

data class SessionSummary(
    val name: String,
    val startedAt: Long,
    val endedAt: Long,
    val aps: Int,
    val ble: Int,
    val ghz5: Int,
) {
    val durationMs: Long get() = (endedAt - startedAt).coerceAtLeast(0)
}

/**
 * The desk's record of what the boards heard while the operator was watching.
 *
 * A phone records a drive; the desk records a sitting. Both keep the same fields, because a
 * session is only useful if the same network can be recognised in either file afterwards - so a
 * row carries the board it came from and the fix the board had at the time, and nothing carries
 * a position that was not measured.
 */
class SessionRecorder {

    private val rows = mutableListOf<Sighting>()
    private var startedAt: Long = 0
    private var name: String = ""

    val isActive: Boolean get() = startedAt > 0

    val count: Int get() = synchronized(rows) { rows.size }

    val started: Long get() = startedAt

    fun start(sessionName: String, atMillis: Long = System.currentTimeMillis()) {
        synchronized(rows) {
            rows.clear()
            name = sessionName.ifBlank { "session" }
            startedAt = atMillis
        }
    }

    fun stop(atMillis: Long = System.currentTimeMillis()): SessionSummary? {
        val summary = synchronized(rows) {
            if (startedAt == 0L) return null
            SessionSummary(
                name = name,
                startedAt = startedAt,
                endedAt = atMillis,
                aps = rows.count { it.kind == WIFI },
                ble = rows.count { it.kind == BLE },
                ghz5 = rows.count { it.kind == WIFI && it.band == Bands.W5 },
            )
        }
        synchronized(rows) { startedAt = 0 }
        return summary
    }

    /** Files a whole sweep; nothing is recorded while no session is running. */
    fun recordSweep(
        board: String,
        chip: String,
        sweep: Esp32Console.Sweep,
        reading: Esp32Console.Reading,
        atMillis: Long = System.currentTimeMillis(),
    ) {
        if (!isActive) return
        val fresh = sweep.aps.map {
            Sighting(
                atMillis = atMillis,
                board = board,
                chip = chip,
                kind = WIFI,
                address = it.bssid,
                name = it.ssid,
                rssi = it.rssi,
                channel = it.channel,
                band = Bands.of(it.channel),
                lat = reading.lat,
                lon = reading.lon,
            )
        } + sweep.devices.map {
            Sighting(
                atMillis = atMillis,
                board = board,
                chip = chip,
                kind = BLE,
                address = it.address,
                name = it.name,
                rssi = it.rssi,
                channel = 0,
                band = "",
                lat = reading.lat,
                lon = reading.lon,
            )
        }
        if (fresh.isEmpty()) return
        synchronized(rows) { rows.addAll(fresh) }
    }

    fun rows(): List<Sighting> = synchronized(rows) { rows.toList() }

    /** WiGLE-shaped enough to open in a spreadsheet, and one row per sighting rather than per AP. */
    fun csv(): String = buildString {
        appendLine("time_iso,time_ms,board,chip,kind,address,name,rssi,channel,band,lat,lon")
        for (r in rows()) {
            appendLine(
                listOf(
                    iso(r.atMillis),
                    r.atMillis.toString(),
                    esc(r.board),
                    esc(r.chip),
                    r.kind,
                    esc(r.address),
                    esc(r.name),
                    r.rssi.toString(),
                    if (r.channel > 0) r.channel.toString() else "",
                    r.band,
                    r.lat?.toString() ?: "",
                    r.lon?.toString() ?: "",
                ).joinToString(","),
            )
        }
    }

    fun json(): String = buildString {
        appendLine("{")
        appendLine("  \"name\": ${quote(name)},")
        appendLine("  \"startedAt\": $startedAt,")
        appendLine("  \"sightings\": [")
        val all = rows()
        all.forEachIndexed { i, r ->
            append("    {")
            append("\"time\": ${quote(iso(r.atMillis))}, ")
            append("\"board\": ${quote(r.board)}, \"chip\": ${quote(r.chip)}, ")
            append("\"kind\": ${quote(r.kind)}, \"address\": ${quote(r.address)}, ")
            append("\"name\": ${quote(r.name)}, \"rssi\": ${r.rssi}, ")
            append("\"channel\": ${r.channel}, \"band\": ${quote(r.band)}, ")
            append("\"lat\": ${r.lat ?: "null"}, \"lon\": ${r.lon ?: "null"}")
            append("}")
            appendLine(if (i == all.lastIndex) "" else ",")
        }
        appendLine("  ]")
        appendLine("}")
    }

    private fun iso(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

    private fun esc(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        const val WIFI: String = "WIFI"
        const val BLE: String = "BLE"
    }
}
