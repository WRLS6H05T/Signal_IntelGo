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
 * Live aircraft, two feeds.
 *
 * Local mode uses api.adsb.lol's point query around the observer. That endpoint is hard-capped
 * at 250 nm server-side, and its `/v2/all` sibling answers HTTP 400 - so a radius, or that
 * endpoint, can never produce a worldwide picture.
 *
 * Global mode therefore uses the OpenSky Network's `/api/states/all`, which really does return
 * every aircraft the network is tracking worldwide (about 6,500 contacts, well under a
 * megabyte, one second). If OpenSky refuses - it is anonymously rate-limited - the app falls
 * back to the local radius query rather than showing an empty map.
 *
 * Distance drives a 1090 MHz free-space path-loss estimate, which is what colours and logs
 * contacts. For an aircraft on the far side of the planet that estimate is meaningless, but the
 * contact itself is real and is still plotted.
 */
class AircraftSource(private val http: OkHttpClient) {

    data class Result(val aircraft: List<AircraftView>, val status: FeedStatus)

    companion object {
        /** Worldwide snapshot: no radius, no key, anonymously rate-limited. */
        const val OPEN_SKY_URL = "https://opensky-network.org/api/states/all"
        /** Ceiling on what one global reply may hand the map. */
        const val MAX_GLOBAL = 12000
        private const val UA = "Signal_IntelGo/1.0 (wardriving; contact local operator)"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.num(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

    suspend fun fetch(
        observer: LatLon,
        radiusNm: Int,
        endpoint: String = "https://api.adsb.lol/v2/point",
        global: Boolean = false,
    ): Result = withContext(Dispatchers.IO) {
        if (global) {
            val worldwide = fetchOpenSky(observer)
            if (worldwide != null && worldwide.aircraft.isNotEmpty()) return@withContext worldwide
        }
        // Locale.US on purpose: a comma decimal separator would break the URL path.
        val lat = String.format(Locale.US, "%.4f", observer.lat)
        val lon = String.format(Locale.US, "%.4f", observer.lon)
        fetchAdsbLol("$endpoint/$lat/$lon/${radiusNm.coerceIn(5, 250)}", observer)
            ?: Result(
                emptyList(),
                FeedStatus(false, "network error", System.currentTimeMillis(), 0),
            )
    }

    /**
     * OpenSky state vectors, positional arrays:
     * 0 icao24, 1 callsign, 5 lon, 6 lat, 7 baro alt (m), 9 ground speed (m/s),
     * 10 track, 11 vertical rate (m/s), 13 geo alt (m), 14 squawk.
     */
    private fun fetchOpenSky(observer: LatLon): Result? {
        val req = Request.Builder().url(OPEN_SKY_URL).header("User-Agent", UA).build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@use null
                val rows = root["states"]?.jsonArray ?: return@use null
                val out = ArrayList<AircraftView>(minOf(rows.size, MAX_GLOBAL))
                for (el in rows) {
                    if (out.size >= MAX_GLOBAL) break
                    val row = runCatching { el.jsonArray }.getOrNull() ?: continue
                    if (row.size < 15) continue
                    val hex = row[0].jsonPrimitive.contentOrNull ?: continue
                    val lon = row[5].jsonPrimitive.doubleOrNull ?: continue
                    val lat = row[6].jsonPrimitive.doubleOrNull ?: continue
                    val baroM = row[7].jsonPrimitive.doubleOrNull
                    val geoM = row[13].jsonPrimitive.doubleOrNull
                    val altM = baroM ?: geoM
                    val speedMs = row[9].jsonPrimitive.doubleOrNull ?: 0.0
                    val track = row[10].jsonPrimitive.doubleOrNull ?: 0.0
                    val verticalMs = row[11].jsonPrimitive.doubleOrNull ?: 0.0
                    val distanceKm = Geo.distanceM(observer.lat, observer.lon, lat, lon) / 1000.0
                    out.add(
                        AircraftView(
                            hex = hex,
                            callSign = (row[1].jsonPrimitive.contentOrNull ?: "").trim(),
                            reg = "",
                            type = "",
                            lat = lat,
                            lon = lon,
                            altFt = altM?.let { (it * 3.28084).toInt() },
                            groundSpeedKt = speedMs * 1.94384,
                            trackDeg = track,
                            verticalRateFpm = (verticalMs * 196.85).toInt(),
                            squawk = row[14].jsonPrimitive.contentOrNull ?: "",
                            category = "",
                            distanceKm = distanceKm,
                            score = Geo.aircraftScore(distanceKm),
                        ),
                    )
                }
                out.sortBy { it.distanceKm }
                Result(
                    out,
                    FeedStatus(true, "${out.size} aircraft (worldwide)", System.currentTimeMillis(), out.size),
                )
            }
        }.getOrNull()
    }

    /** One adsb.lol point query plus parse. Null means the request itself failed. */
    private fun fetchAdsbLol(url: String, observer: LatLon): Result? {
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
                    FeedStatus(true, "${out.size} aircraft", System.currentTimeMillis(), out.size),
                )
            }
        }.getOrNull()
    }
}
