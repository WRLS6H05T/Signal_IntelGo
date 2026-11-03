package dev.skyward.rf.sky

import dev.skyward.rf.core.AircraftView
import dev.skyward.rf.core.FeedStatus
import dev.skyward.rf.core.Geo
import dev.skyward.rf.core.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/**
 * Live aircraft inside a fixed ring.
 *
 * The limit is 30 statute miles because that is the traffic that is actually overhead: a contact
 * a hundred miles away is a position on a map, not something this antenna is hearing, and its
 * 1090 MHz link estimate is noise. So the ring is applied twice - once in the query, and again
 * on every row that comes back, because the feed answers a grid square rather than a circle.
 *
 * The feed is api.adsb.lol's point query, which needs no key. Its replies are capped at 250 nm
 * server-side, well outside this ring, so nothing here depends on a paid tier.
 */
class AircraftSource(private val http: OkHttpClient) {

    data class Result(val aircraft: List<AircraftView>, val status: FeedStatus)

    companion object {
        /** Contacts further out than this are dropped: not overhead traffic, not logged. */
        const val RANGE_MI = 30.0

        /** The same ring in kilometres, which is what the feed's answers are measured in. */
        const val RANGE_KM = RANGE_MI * 1.609344

        /**
         * What is asked of the feed, in nautical miles. Slightly wider than the ring so an
         * aircraft crossing the boundary is still seen before it leaves, never narrower - the
         * point query answers from a grid, and a tight request can miss the corners.
         */
        private const val REQUEST_NM = 30

        private const val DEFAULT_ENDPOINT = "https://api.adsb.lol/v2/point"
        private const val UA = "Signal_IntelGo/1.0 (wardriving; contact local operator)"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.num(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

    suspend fun fetch(
        observer: LatLon,
        endpoint: String = DEFAULT_ENDPOINT,
    ): Result = withContext(Dispatchers.IO) {
        // Locale.US on purpose: a comma decimal separator would break the URL path.
        val lat = String.format(Locale.US, "%.4f", observer.lat)
        val lon = String.format(Locale.US, "%.4f", observer.lon)
        fetchPoint("$endpoint/$lat/$lon/$REQUEST_NM", observer)
            ?: Result(
                emptyList(),
                FeedStatus(false, "network error", System.currentTimeMillis(), 0),
            )
    }

    /** One point query plus parse. Null means the request itself failed. */
    private fun fetchPoint(url: String, observer: LatLon): Result? {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@use Result(
                        emptyList(),
                        FeedStatus(false, "HTTP ${resp.code}", System.currentTimeMillis(), 0),
                    )
                }
                val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                val arr = root?.get("ac")?.jsonArray ?: return@use Result(
                    emptyList(),
                    FeedStatus(true, "no aircraft in range", System.currentTimeMillis(), 0),
                )
                val out = ArrayList<AircraftView>(arr.size)
                for (el in arr) {
                    val o = runCatching { el.jsonObject }.getOrNull() ?: continue
                    val latD = o.num("lat") ?: continue
                    val lonD = o.num("lon") ?: continue
                    val hex = o.str("hex") ?: continue
                    val distanceKm = Geo.distanceM(observer.lat, observer.lon, latD, lonD) / 1000.0
                    // The query answers a square around the observer, so the circle is applied
                    // here: a contact in the corner of the square is still outside the ring.
                    if (distanceKm > RANGE_KM) continue
                    val altFt = o.num("alt_baro")?.toInt() ?: o.num("alt_geom")?.toInt()
                    out.add(
                        AircraftView(
                            hex = hex,
                            callSign = (o.str("flight") ?: "").trim(),
                            reg = (o.str("r") ?: "").trim(),
                            type = (o.str("t") ?: "").trim(),
                            lat = latD,
                            lon = lonD,
                            altFt = altFt,
                            groundSpeedKt = o.num("gs") ?: 0.0,
                            trackDeg = o.num("track") ?: 0.0,
                            verticalRateFpm = (o.num("baro_rate") ?: o.num("geom_rate") ?: 0.0).toInt(),
                            squawk = o.str("squawk") ?: "",
                            category = o.str("category") ?: "",
                            distanceKm = distanceKm,
                            score = Geo.aircraftScore(distanceKm),
                        ),
                    )
                }
                out.sortBy { it.distanceKm }
                Result(
                    out,
                    FeedStatus(
                        true,
                        "${out.size} aircraft within ${RANGE_MI.toInt()} mi",
                        System.currentTimeMillis(),
                        out.size,
                    ),
                )
            }
        }.getOrNull()
    }
}
