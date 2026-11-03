package dev.skyward.rf.sky

import dev.skyward.rf.core.FileStore
import dev.skyward.rf.core.Geo
import dev.skyward.rf.core.LatLon
import dev.skyward.rf.core.SatView
import kotlinx.coroutines.Dispatchers
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
        private val KNOWN_GROUPS = listOf("stations", "visual", "weather", "gps-ops", "science", "amateur")

        /**
         * ZARYA, the station core: NORAD 25544, catalogue 1998-067A. This is the International
         * Space Station itself, not a module docked to it and not a cubesat that happens to have
         * "ISS" inside its name.
         */
        const val ISS_NORAD = "25544"
        const val ISS_NAME = "ISS (ZARYA)"
        const val ISS_DESIGNATOR = "1998-067A"
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
        for (group in wanted) {
            val url = "$BASE?GROUP=$group&FORMAT=tle"
            val req = Request.Builder().url(url).header("User-Agent", "Signal_IntelGo/1.0 (wardriving)").build()
            runCatching {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        errors.add("$group: HTTP ${resp.code}")
                        return@use
                    }
                    val body = resp.body?.string().orEmpty()
                    val parsed = parseTle(body)
                    if (parsed.isEmpty()) errors.add("$group: empty") else collected.addAll(parsed)
                }
            }.onFailure { errors.add("$group: ${it.message ?: "network error"}") }
        }
        if (collected.isNotEmpty()) {
            val deduped = collapseIss(collected.associateBy { it.noradId }.values.toList())
            cache = CacheFile(
                fetchedAt = System.currentTimeMillis(),
                groups = wanted,
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
    suspend fun positions(observer: LatLon?, nowMs: Long = System.currentTimeMillis()): List<SatView> =
        withContext(Dispatchers.Default) {
            if (cache.sats.isEmpty()) return@withContext emptyList()
            val date = AbsoluteDate(Date(nowMs), TimeScalesFactory.getTAI())
            val out = ArrayList<SatView>(cache.sats.size)
            for (tle in cache.sats) {
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
            out.sortByDescending { it.elevation }
            out
        }
}
