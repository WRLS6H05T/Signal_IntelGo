package dev.skyward.rf.sky

import dev.skyward.rf.core.CameraKind
import dev.skyward.rf.core.CameraView
import dev.skyward.rf.core.FeedStatus
import dev.skyward.rf.core.Geo
import dev.skyward.rf.core.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos

/**
 * Surveillance cameras around the phone, from OpenStreetMap.
 *
 * There is no official map of Flock cameras: Flock publishes none, and no agency publishes a
 * complete list. What exists is volunteer mapping in OpenStreetMap, tagged
 * `man_made=surveillance` with `surveillance:type=ALPR` for plate readers and `camera:type` for
 * the rest, and it is queryable through the Overpass API without a key. That is the honest
 * source for a camera layer: positions someone actually recorded, complete with the vendor,
 * operator, facing direction and - for the ones that publish one - the camera's own feed.
 *
 * It is a lower bound, and the card says so: an unmapped camera is not an absent camera.
 *
 * The feed is asked at most every ten minutes, and again after a kilometre of travel. Cameras
 * are bolted to posts; re-asking because the phone moved across a street is a request for
 * nothing.
 */
class CameraSource(private val http: OkHttpClient) {

    data class Result(val cameras: List<CameraView>, val status: FeedStatus)

    companion object {
        const val ENDPOINT = "https://overpass-api.de/api/interpreter"

        /** The ring cameras are read for. A camera three miles away is not watching this street. */
        const val RADIUS_M = 3_000

        /** A city centre can hold thousands; the map needs the nearest slice, not all of them. */
        const val MAX_CAMERAS = 400

        const val MIN_GAP_MS = 10 * 60_000L
        const val RETRY_MS = 60_000L
        const val MOVE_KM = 1.0

        private const val UA = "Signal_IntelGo/1.0 (surveillance camera map; contact local operator)"

        /**
         * Where a mapped camera keeps a link to its own picture, most specific first.
         *
         * `image` and `website` come last on purpose: an operator's home page is not a feed, and
         * a card that offers "live feed" for one would be lying.
         */
        private val FEED_TAGS = listOf(
            "contact:webcam",
            "surveillance:webcam",
            "webcam",
            "stream",
            "video_stream",
            "camera:url",
            "image",
            "url",
            "contact:website",
            "website",
        )

        /**
         * Every mapped surveillance camera around the fix.
         *
         * The ALPR half is asked for on its own as well as through `man_made=surveillance`: a
         * plate reader mapped with only `surveillance:type=ALPR` is still a plate reader, and a
         * query that insists on the other tag loses it.
         */
        internal fun query(observer: LatLon, radiusM: Int = RADIUS_M): String {
            // Locale.US on purpose: a comma decimal separator would break the Overpass filter.
            val lat = String.format(Locale.US, "%.5f", observer.lat)
            val lon = String.format(Locale.US, "%.5f", observer.lon)
            return buildString {
                appendLine("[out:json][timeout:25];")
                appendLine("(")
                appendLine("  node(around:$radiusM,$lat,$lon)[\"man_made\"=\"surveillance\"];")
                appendLine("  way(around:$radiusM,$lat,$lon)[\"man_made\"=\"surveillance\"];")
                appendLine("  node(around:$radiusM,$lat,$lon)[\"surveillance:type\"~\"ALPR\",i];")
                appendLine("  way(around:$radiusM,$lat,$lon)[\"surveillance:type\"~\"ALPR\",i];")
                appendLine(");")
                append("out center;")
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _cameras = MutableStateFlow<List<CameraView>>(emptyList())
    val cameras: StateFlow<List<CameraView>> = _cameras

    private val _status = MutableStateFlow(FeedStatus(null, "idle", 0L, 0))
    val status: StateFlow<FeedStatus> = _status

    @Volatile
    private var nextAttempt = 0L

    @Volatile
    private var inFlight = false

    @Volatile
    private var lastFix: LatLon? = null

    /** Reads the mapped cameras around a fix; safe to call on every tick, it throttles itself. */
    suspend fun refresh(observer: LatLon, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (now < nextAttempt || inFlight)) return
        val last = lastFix
        if (!force && last != null && groundKm(last, observer) < MOVE_KM) return
        inFlight = true
        try {
            val url = ENDPOINT.toHttpUrl().newBuilder()
                .addQueryParameter("data", query(observer))
                .build()
            val req = Request.Builder().url(url).header("User-Agent", UA).get().build()
            withContext(Dispatchers.IO) {
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        // Keep the cameras already on the map: a rate-limited request is not a
                        // statement that the cameras were removed.
                        nextAttempt = now + RETRY_MS
                        _status.value = FeedStatus(false, "HTTP ${resp.code}", now, _cameras.value.size)
                        return@use
                    }
                    val result = parse(body, observer)
                    if (result.status.ok == false) {
                        nextAttempt = now + RETRY_MS
                        _status.value = result.status.copy(at = now)
                        return@use
                    }
                    lastFix = observer
                    nextAttempt = now + MIN_GAP_MS
                    _cameras.value = result.cameras
                    _status.value = result.status.copy(at = now)
                }
            }
        } catch (e: IOException) {
            nextAttempt = now + RETRY_MS
            _status.value = FeedStatus(false, e.message ?: "network error", now, _cameras.value.size)
        } finally {
            inFlight = false
        }
    }

    /**
     * Parses one Overpass answer.
     *
     * An element with no coordinate is dropped rather than placed at the phone: these cameras
     * are fixed infrastructure, and a marker on the wrong street is worse than no marker.
     */
    internal fun parse(body: String, observer: LatLon): Result {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val elements = root?.get("elements") as? JsonArray
            ?: return Result(
                emptyList(),
                FeedStatus(false, "no camera answer", 0L, 0),
            )
        val seen = HashSet<String>()
        val out = ArrayList<CameraView>(elements.size)
        for (el in elements) {
            val o = runCatching { el.jsonObject }.getOrNull() ?: continue
            val tags = (o["tags"] as? JsonObject)?.entries
                ?.associate { (k, v) -> k to ((v as? JsonPrimitive)?.contentOrNull.orEmpty()) }
                ?: emptyMap()
            val kind = kindOf(tags) ?: continue
            val center = o["center"] as? JsonObject
            val lat = o.num("lat") ?: center?.num("lat") ?: continue
            val lon = o.num("lon") ?: center?.num("lon") ?: continue
            if (!lat.isFinite() || !lon.isFinite()) continue
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            val type = o.str("type") ?: "node"
            val id = o.num("id")?.toLong()?.toString() ?: o.str("id") ?: continue
            if (!seen.add("$type/$id")) continue
            val feed = feedOf(tags)
            val distanceKm = Geo.distanceM(observer.lat, observer.lon, lat, lon) / 1000.0
            out.add(
                CameraView(
                    id = "$type/$id",
                    kind = kind,
                    lat = lat,
                    lon = lon,
                    name = tags["name"].orEmpty().ifBlank {
                        if (kind == CameraKind.FLOCK) "Flock camera" else "CCTV camera"
                    },
                    operator = tags["operator"].orEmpty(),
                    manufacturer = tags["manufacturer"].orEmpty(),
                    cameraType = tags["camera:type"].orEmpty(),
                    directionDeg = tags["camera:direction"]?.trim()?.toDoubleOrNull(),
                    mount = tags["camera:mount"].orEmpty(),
                    zone = tags["surveillance:zone"].orEmpty(),
                    install = tags["surveillance"].orEmpty(),
                    ref = tags["ref"].orEmpty(),
                    distanceKm = distanceKm,
                    liveFeed = feed?.first.orEmpty(),
                    feedKind = feed?.second.orEmpty(),
                ),
            )
        }
        out.sortBy { it.distanceKm }
        val kept = if (out.size > MAX_CAMERAS) out.subList(0, MAX_CAMERAS).toList() else out
        return Result(
            kept,
            FeedStatus(
                true,
                if (kept.isEmpty()) "no cameras mapped within ${RADIUS_M / 1000} km"
                else "${kept.size} mapped cameras",
                System.currentTimeMillis(),
                kept.size,
            ),
        )
    }

    /**
     * Which kind of camera this is, or null when it is not one.
     *
     * A guard post shares the `man_made=surveillance` tag and is not a camera. ALPR covers every
     * plate reader the map holds, which in practice is mostly Flock; the card names the vendor
     * rather than claiming Flock for hardware that is not.
     */
    internal fun kindOf(tags: Map<String, String>): CameraKind? {
        val type = tags["surveillance:type"].orEmpty().lowercase(Locale.US)
        if (type.startsWith("guard")) return null
        val manufacturer = tags["manufacturer"].orEmpty().lowercase(Locale.US)
        val alpr = type.contains("alpr") ||
            tags.keys.any { it.lowercase(Locale.US).startsWith("surveillance:alpr") } ||
            manufacturer.contains("flock")
        return if (alpr) CameraKind.FLOCK else CameraKind.CCTV
    }

    /** The camera's own feed, and what kind of player it needs, when the map carries one. */
    internal fun feedOf(tags: Map<String, String>): Pair<String, String>? {
        for (key in FEED_TAGS) {
            val value = tags[key]?.trim().orEmpty()
            if (!value.startsWith("http://", ignoreCase = true) &&
                !value.startsWith("https://", ignoreCase = true)
            ) {
                // An RTSP address cannot be handed to another app as a web link, and pretending
                // otherwise would offer a button that does nothing.
                continue
            }
            return value to feedKind(value)
        }
        return null
    }

    private fun feedKind(url: String): String {
        val u = url.lowercase(Locale.US)
        return when {
            u.contains(".m3u8") || u.contains("hls") -> "HLS stream"
            u.contains("mjpg") || u.contains("mjpeg") || u.contains("action=stream") -> "MJPEG stream"
            u.contains(".mp4") || u.contains(".webm") -> "video file"
            else -> "web page"
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.num(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun groundKm(a: LatLon, b: LatLon): Double {
        val dLat = (b.lat - a.lat) * 111.0
        val dLon = (b.lon - a.lon) * 111.0 * cos(((a.lat + b.lat) / 2.0) * Math.PI / 180.0)
        return abs(dLat) + abs(dLon)
    }
}
