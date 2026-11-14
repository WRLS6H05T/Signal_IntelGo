package dev.skyward.rf.desktop.wardrive

import dev.skyward.rf.core.LatLon
import dev.skyward.rf.wardrive.WardrivePoint
import dev.skyward.rf.wardrive.WardriveSession
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Drives that arrived from the handset, kept where the desk keeps everything else.
 *
 * A drive crosses the peer link as the same JSON the phone records, gzipped and cut into parts by
 * `PeerLogs`; this is where the rebuilt text lands. One file per drive, named after its id, so a
 * second fetch of the same drive overwrites rather than duplicating - the phone re-sends an updated
 * session when a drive is still recording.
 *
 * The stored text is what the phone wrote, with [WardriveSession.from] stamped with the sender's
 * name when the phone did not write one: a drive on this desk has to say which device recorded it.
 */
class DeskDrives(home: File) {

    /** One saved drive, as the Setup pane lists it. */
    data class Row(
        val id: String,
        val name: String,
        val startedAt: Long,
        val points: Int,
        val km: Double,
        val wifi: Int,
        val ble: Int,
        val from: String,
    )

    private val dir = File(home, "wardrive").apply { mkdirs() }
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Writes [payload] if it parses as a session; null when the link delivered something else. */
    fun save(payload: String, from: String): Row? {
        val session = runCatching {
            json.decodeFromString(WardriveSession.serializer(), payload)
        }.getOrNull() ?: return null
        val stamped = if (session.from.isBlank() && from.isNotBlank()) session.copy(from = from) else session
        val file = File(dir, "peer_${stamped.id}.json")
        runCatching { file.writeText(json.encodeToString(WardriveSession.serializer(), stamped)) }
            .onFailure { return null }
        return row(stamped)
    }

    /**
     * The fixes of one saved drive.
     *
     * The map needs more than the line: the phone paints a drive as one dot per fix on its
     * red-to-green scale, so the scores and the radios heard at each fix have to come through.
     */
    fun points(id: String): List<WardrivePoint> = runCatching {
        json.decodeFromString(WardriveSession.serializer(), File(dir, "peer_$id.json").readText())
    }.getOrNull()?.points.orEmpty()

    /** Every saved drive, newest first. Unreadable files are skipped rather than listed empty. */
    fun list(): List<Row> {
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { file ->
            runCatching { json.decodeFromString(WardriveSession.serializer(), file.readText()) }.getOrNull()
        }.map { row(it) }.sortedByDescending { it.startedAt }
    }

    /** The drive's fixes in order: what the map draws as its track. */
    fun track(id: String): List<LatLon> {
        val file = File(dir, "peer_$id.json")
        if (!file.isFile) return emptyList()
        val session = runCatching {
            json.decodeFromString(WardriveSession.serializer(), file.readText())
        }.getOrNull() ?: return emptyList()
        return session.points.map { LatLon(it.lat, it.lon) }
    }

    private fun row(session: WardriveSession) = Row(
        id = session.id,
        name = session.name,
        startedAt = session.startedAt,
        points = session.points.size,
        km = session.km,
        wifi = session.wifiAps,
        ble = session.bleDevices,
        from = session.from,
    )
}
