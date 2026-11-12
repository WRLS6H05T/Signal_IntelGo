package dev.skyward.rf.sky

import dev.skyward.rf.core.FileStore
import dev.skyward.rf.core.Geo
import dev.skyward.rf.core.LatLon
import dev.skyward.rf.core.SatView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.orekit.propagation.analytical.tle.TLE
import org.orekit.propagation.analytical.tle.TLEPropagator
import org.orekit.time.AbsoluteDate
import org.orekit.time.TimeScalesFactory
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * Live satellite positions.
 *
 * Element sets come from CelesTrak (no key required); propagation happens locally with
 * Orekit's SGP4/SDP4 so the map keeps moving between network refreshes. TLEs are cached on
 * disk for six hours, which is well inside their accuracy window and keeps the app usable
 * with no connectivity.
 */
class SatTracker(
    private val store: FileStore,
    private val http: OkHttpClient,
) {

    companion object {
        private const val CACHE_FILE = "tle_cache_v1.json"
        private const val MAX_AGE_MS = 6 * 60 * 60 * 1000L
        private const val BASE = "https://celestrak.org/NORAD/elements/gp.php"

        /** The constellation files: same objects, served separately from the GP endpoint. */
        private const val SUPPLEMENTAL = "https://celestrak.org/NORAD/elements/supplemental/sup-gp.php"
        // `active` is CelesTrak's whole tracked catalogue (15,880 element sets when this was
        // written); starlink and cubesat are the two named constellations that take the total
        // past eleven thousand on their own. A host that wants the full sky asks for `active`.
        private val KNOWN_GROUPS = listOf(
            "stations", "visual", "weather", "gps-ops", "science", "amateur",
            "starlink", "cubesat", "active",
        )

        /**
         * ZARYA, the station core: NORAD 25544, catalogue 1998-067A. This is the International
         * Space Station itself, not a module docked to it and not a cubesat that happens to have
         * "ISS" inside its name.
         */
        const val ISS_NORAD = "25544"
        const val ISS_NAME = "ISS (ZARYA)"
        const val ISS_DESIGNATOR = "1998-067A"

        data class Birds(val norad: String, val name: String, val country: String)

        /**
         * BIRDS-3: three 1U CubeSats deployed from the ISS on 17 June 2019 - NepaliSat-1,
         * Raavana-1 and Uguisu.
         *
         * All three decayed in early October 2021, so CelesTrak publishes no element sets for
         * them and this group is normally empty. They are still asked for on every refresh: if
         * any is ever catalogued again it appears like any other satellite, and until then the
         * app does not invent a position for a satellite that is no longer there.
         */
        val BIRDS3 = listOf(
            Birds("44331", "NEPALISAT-1", "Nepal"),
            Birds("44329", "RAAVANA-1", "Sri Lanka"),
            Birds("44330", "UGUISU", "Japan"),
        )
        val BIRDS3_IDS: Set<String> = BIRDS3.map { it.norad }.toSet()

        /**
         * The GPS constellation, recognised by catalogue name.
         *
         * CelesTrak's gps-ops group is named `GPS BIIR-2  (PRN 13)`, `GPS 71 (USA 260)`,
         * `NAVSTAR 71` and so on, and the name is the one group marker that survives the cache:
         * the app stores element sets, not the group each one came from. Offline mode plots
         * these and nothing else.
         */
        fun isGpsName(name: String): Boolean {
            val n = name.trim()
            return n.equals("GPS", ignoreCase = true) ||
                n.startsWith("GPS ", ignoreCase = true) ||
                n.startsWith("NAVSTAR", ignoreCase = true)
        }
    }

    @Serializable
    data class CachedTle(val name: String, val line1: String, val line2: String) {
        val noradId: String
            get() = line1.substring(2, 7).trim()
    }

    @Serializable
    data class CacheFile(
        val fetchedAt: Long = 0L,
        val groups: List<String> = emptyList(),
        val sats: List<CachedTle> = emptyList(),
    )

    data class RefreshResult(val ok: Boolean, val count: Int, val detail: String, val fetchedAt: Long)

    private val propagators = ConcurrentHashMap<String, TLEPropagator>()
    private var cache: CacheFile = CacheFile()
    private var lastError: String? = null

    init {
        // Collapse on load too: a cache written by an older build still holds every module.
        store.read<CacheFile>(CACHE_FILE)?.let { cache = it.copy(sats = collapseIss(it.sats)) }
    }

    /**
     * One station, one dot, and it is the station.
     *
     * The `stations` group carries the ISS modules - NAUKA, POISK, RASSVET - as separate
     * catalog objects. Propagated on their own they land a few kilometres apart, so the map
     * drew the station in two or three places at once. Everything named for the station except
     * the ZARYA core (NORAD 25544) is therefore dropped. The surviving entry is renamed to the
     * canonical catalogue name, so a build that cached an odd variant cannot relabel it.
     */
    private fun collapseIss(sats: List<CachedTle>): List<CachedTle> {
        val station = sats.firstOrNull { it.noradId == ISS_NORAD }
        val rest = sats.filter { it.noradId != ISS_NORAD && !isStationName(it.name) }
        if (station != null) return rest + station.copy(name = ISS_NAME)
        // 25544 missing from this batch: keep at most one station-named entry so the map still
        // has the station, rather than leaving it to be flagged twice.
        val lone = sats.firstOrNull { it.noradId != ISS_NORAD && isStationName(it.name) }
        return if (lone == null) rest else rest + lone.copy(name = ISS_NAME)
    }

    /** `ISS (ZARYA)`, `ISS` and the docked modules - never `SWISSCUBE`, which merely contains "ISS". */
    private fun isStationName(name: String): Boolean {
        val n = name.trim()
        return n.equals("ISS", ignoreCase = true) || n.startsWith("ISS (", ignoreCase = true)
    }

    fun knownGroups(): List<String> = KNOWN_GROUPS
    fun cacheAgeMs(now: Long = System.currentTimeMillis()): Long =
        if (cache.fetchedAt == 0L) Long.MAX_VALUE else (now - cache.fetchedAt).coerceAtLeast(0L)

    fun cachedCount(): Int = cache.sats.size
    fun error(): String? = lastError

    /** Downloads and stores element sets for the requested CelesTrak groups. */
    suspend fun refresh(groups: List<String>, force: Boolean = false): RefreshResult = withContext(Dispatchers.IO) {
        val wanted = groups.ifEmpty { listOf("stations") }
        val fresh = !force && cacheAgeMs() < MAX_AGE_MS && cache.groups.containsAll(wanted) && cache.sats.isNotEmpty()
        if (fresh) {
            return@withContext RefreshResult(true, cache.sats.size, "cached", cache.fetchedAt)
        }
        val collected = ArrayList<CachedTle>()
        val errors = ArrayList<String>()
        // CelesTrak's `active` group already contains every object in the named groups, and it asks
        // that its service not be hammered: one catalogue request replaces nine.
        val fetched = if ("active" in wanted) listOf("active") else wanted
        val arrived = ArrayList<String>(fetched.size)
        for ((index, group) in fetched.withIndex()) {
            // CelesTrak publishes a one-request-per-second limit and answers 403 to a burst.
            // Asking for eight groups at once is exactly that burst, so they go out spaced.
            if (index > 0) delay(1_200)
            val result = fetchGroup(group)
            if (result.sats.isEmpty()) {
                errors.add("$group: ${result.note}")
            } else {
                collected.addAll(result.sats)
                arrived.add(group)
            }
        }
        // BIRDS-3 by catalogue number: it is not a CelesTrak group, and a 404 for a decayed
        // object is the expected answer rather than an error worth showing.
        collected.addAll(fetchBirds3())
        if (collected.isNotEmpty()) {
            val deduped = collapseIss(collected.associateBy { it.noradId }.values.toList())
            // Only the groups that actually arrived are recorded. Recording the request instead
            // meant a throttled group was cached as if it were there, and the six hour freshness
            // check then kept the missing satellites missing.
            cache = CacheFile(
                fetchedAt = System.currentTimeMillis(),
                groups = arrived,
                sats = deduped,
            )
            store.write(CACHE_FILE, cache)
            propagators.keys.retainAll(deduped.map { it.noradId }.toSet())
            lastError = errors.firstOrNull()
            RefreshResult(true, deduped.size, if (errors.isEmpty()) "live" else errors.joinToString("; "), cache.fetchedAt)
        } else {
            lastError = errors.joinToString("; ").ifBlank { "no data" }
            RefreshResult(
                ok = cache.sats.isNotEmpty(),
                count = cache.sats.size,
                detail = "offline: $lastError",
                fetchedAt = cache.fetchedAt,
            )
        }
    }

    /**
     * One group's element sets, with the constellation files as a fallback.
     *
     * CelesTrak rate-limits exactly the group the desk most wants: `starlink` answers 403 on the
     * GP endpoint while every other group answers 200, and the same data is published as a
     * supplemental file. A refused group is asked for there before it is called a failure.
     */
    private fun fetchGroup(group: String): GroupResult {
        val direct = httpGet("$BASE?GROUP=$group&FORMAT=tle")
        if (direct.sats.isNotEmpty()) return GroupResult(direct.sats, "${direct.sats.size}")
        val supplemental = httpGet("$SUPPLEMENTAL?FILE=$group&FORMAT=tle")
        if (supplemental.sats.isNotEmpty()) return GroupResult(supplemental.sats, "${supplemental.sats.size} supplemental")
        // Both answers are reported: "403 then 403" and "200 with nothing in it" are different
        // problems, and the note is the only place an operator can see which one happened.
        return GroupResult(emptyList(), "${direct.note} / supplemental ${supplemental.note}")
    }

    private data class GroupResult(val sats: List<CachedTle>, val note: String)

    private data class HttpResult(val sats: List<CachedTle>, val note: String)

    private fun httpGet(url: String): HttpResult = runCatching {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Signal_IntelGo/1.0 (wardriving)")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                HttpResult(emptyList(), "HTTP ${resp.code}")
            } else {
                val parsed = parseTle(resp.body?.string().orEmpty())
                HttpResult(parsed, if (parsed.isEmpty()) "HTTP 200, no TLEs" else "HTTP 200")
            }
        }
    }.getOrElse { HttpResult(emptyList(), it.message ?: "network error") }

    /** Asks for the three BIRDS-3 objects by catalogue number; usually returns nothing. */
    private fun fetchBirds3(): List<CachedTle> {
        val out = ArrayList<CachedTle>(BIRDS3.size)
        for (bird in BIRDS3) {
            val req = Request.Builder()
                .url("$BASE?CATNR=${bird.norad}&FORMAT=tle")
                .header("User-Agent", "Signal_IntelGo/1.0 (wardriving)")
                .build()
            runCatching {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val hit = parseTle(resp.body?.string().orEmpty())
                        .firstOrNull { it.noradId == bird.norad } ?: return@use
                    out.add(hit.copy(name = "BIRDS-3 ${bird.name}"))
                }
            }
        }
        return out
    }

    /** How many BIRDS-3 objects currently have live elements; normally zero. */
    fun birds3Count(): Int = cache.sats.count { it.noradId in BIRDS3_IDS }

    private fun parseTle(text: String): List<CachedTle> {
        val lines = text.split('\n').map { it.trimEnd('\r') }.filter { it.isNotBlank() }
        val out = ArrayList<CachedTle>()
        var i = 0
        while (i < lines.size) {
            val a = lines[i]
            val b = lines.getOrNull(i + 1)
            val c = lines.getOrNull(i + 2)
            if (b != null && c != null && b.startsWith("1 ") && c.startsWith("2 ")) {
                out.add(CachedTle(a.trim(), b.trim(), c.trim()))
                i += 3
            } else {
                i += 1
            }
        }
        return out
    }

    /**
     * Orekit's UTC scale needs the IERS leap-second table, which we deliberately do not ship.
     * Instead both the TLE epoch and the query time are read as UTC wall-clock values in the
     * TAI scale - the leap seconds cancel in `durationFrom`, so SGP4 timing stays correct
     * while the app works offline with no data files.
     */
    private fun propagatorFor(tle: CachedTle): TLEPropagator? {
        propagators[tle.noradId]?.let { return it }
        return runCatching {
            val prop = TLEPropagator.selectExtrapolator(
                TLE(tle.line1, tle.line2, TimeScalesFactory.getTAI()),
            )
            propagators[tle.noradId] = prop
            prop
        }.getOrNull()
    }

    /**
     * Propagates every cached satellite to [nowMs].
     *
     * Nothing is dropped for being below the observer's horizon: the globe is a picture of the
     * whole orbit, not of your sky, and the old horizon filter was why the station - and most
     * of the catalogue - was missing from it. Elevation and the link score still tell you
     * whether a given satellite is actually workable from where you are.
     */
    suspend fun positions(
        observer: LatLon?,
        nowMs: Long = System.currentTimeMillis(),
        /** Offline mode: the GPS constellation, and nothing else in the sky. */
        gpsOnly: Boolean = false,
    ): List<SatView> =
        withContext(Dispatchers.Default) {
            if (cache.sats.isEmpty()) return@withContext emptyList()
            val date = AbsoluteDate(Date(nowMs), TimeScalesFactory.getTAI())
            val out = ArrayList<SatView>(cache.sats.size)
            for (tle in cache.sats) {
                if (gpsOnly && !isGpsName(tle.name)) continue
                val prop = propagatorFor(tle) ?: continue
                val pv = runCatching { prop.propagate(date) }.getOrNull() ?: continue
                val pos = runCatching { pv.position }.getOrNull() ?: continue
                val ecef = OrbitMath.temeToEcef(pos.x, pos.y, pos.z, nowMs)
                val geo = OrbitMath.ecefToGeodetic(ecef[0], ecef[1], ecef[2])
                val el: Double
                val az: Double
                val range: Double
                if (observer != null) {
                    val look = OrbitMath.lookAngles(observer.lat, observer.lon, ecef)
                    el = look[0]
                    az = look[1]
                    range = look[2]
                } else {
                    el = 90.0
                    az = 0.0
                    range = geo[2]
                }
                out.add(
                    SatView(
                        noradId = tle.noradId,
                        name = tle.name,
                        lat = geo[0],
                        lon = geo[1],
                        altKm = geo[2],
                        elevation = el,
                        azimuth = az,
                        rangeKm = range,
                        score = Geo.satelliteScore(el),
                    ),
                )
            }
            // Catalogue order, not elevation order. The map samples this list when the shell is
            // too large to draw whole, and an elevation sort reshuffles itself on every tick:
            // the sample then picks different satellites each time, so icons pop in and out at
            // the edge of the globe. A stable order keeps the same subset plotted from one tick
            // to the next, and every consumer that wants "highest first" sorts for itself.
            out.sortBy { it.noradId }
            out
        }
}
