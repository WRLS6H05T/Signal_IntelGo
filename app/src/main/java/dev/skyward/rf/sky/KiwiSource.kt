package dev.skyward.rf.sky

import dev.skyward.rf.core.FeedStatus
import dev.skyward.rf.core.Geo
import dev.skyward.rf.core.KiwiSdrView
import dev.skyward.rf.core.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale

/**
 * Public KiwiSDR receivers: HF stations other people have put on the internet.
 *
 * There is nothing to authenticate and nothing to subscribe to. The Kiwi project's directory is
 * the list, published as a file the community maps read - a mirror of kiwisdr.com rather than the
 * origin itself, which serves its own page and asks not to be harvested. That is the honest
 * source for this layer: receivers someone actually listed, with the position, antenna, coverage
 * and user count their operator chose to publish.
 *
 * What this class does with it is deliberately small. It reads the directory when the layer is
 * switched on and never on a timer: receivers come and go over hours, one phone asking every
 * minute would be load nobody agreed to, and the mirror's own operator asks for one read per
 * human action. The read is throttled to one per half hour so flipping the layer twice does not
 * ask twice.
 *
 * The audio is not here either, and that is not an omission. A Kiwi streams its spectrum and
 * sound over its own browser connection, so the card tunes by opening the operator's page at a
 * frequency: the phone's browser is the receiver's client, and this app stays the map.
 */
class KiwiSource(private val http: OkHttpClient) {

    /** One preset the card offers: a band, the frequency it is worked on, and its mode. */
    data class Preset(val label: String, val kHz: Int, val mode: String)

    data class Result(val kiwis: List<KiwiSdrView>, val status: FeedStatus)

    companion object {
        /**
         * The receiver list as JSON: `receivers[]` of `name`, `url`, `gps`, `bands`, `users`,
         * `users_max`, `offline`, `sdr_hw`, `antenna`, `grid` and `snr_hf`.
         */
        const val ENDPOINT = "https://cdn.aethersdr.com/kiwi.json"

        /**
         * Nine hundred receivers worldwide is nine hundred pins on one planet. The nearest are
         * the ones whose propagation actually resembles this street, so those are what is kept.
         */
        const val MAX_KIWI = 80

        /** One read per half hour, and only when asked: this list is not a ticker. */
        const val MIN_GAP_MS = 30 * 60_000L
        const val RETRY_MS = 2 * 60_000L

        private const val UA = "Signal_IntelGo/1.0 (public KiwiSDR map layer)"

        /**
         * Frequencies worth a tap on any HF receiver, in kHz with the mode they are worked in.
         *
         * They are filtered against each receiver's advertised coverage before the card offers
         * them: a Kiwi that stops at 10 MHz must not be handed a 15 m button that lands outside
         * its front end.
         */
        internal val PRESETS = listOf(
            Preset("MW 1.0", 1_000, "am"),
            Preset("80m", 3_700, "lsb"),
            Preset("40m", 7_100, "lsb"),
            Preset("30m", 10_120, "cw"),
            Preset("20m", 14_200, "usb"),
            Preset("17m", 18_150, "usb"),
            Preset("15m", 21_200, "usb"),
            Preset("10m", 28_500, "usb"),
        )

        /**
         * The operator's own page, pre-tuned: Kiwi's documented `f=` parameter takes the
         * frequency in kHz with the demod mode appended, e.g. `?f=7100lsb`. Nothing else is needed
         * to land on a frequency, and leaving the zoom out keeps the operator's saved session.
         */
        internal fun tuneUrl(url: String, kHz: Int, mode: String): String {
            val base = url.trim().trimEnd('/')
            return base + "/?f=" + kHz + mode.trim().lowercase(Locale.US)
        }

        /** The presets a receiver can actually hear, so the card never offers a dead frequency. */
        internal fun presetsFor(loHz: Long, hiHz: Long): List<Preset> =
            PRESETS.filter { it.kHz * 1_000L in loHz..hiHz }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _kiwis = MutableStateFlow<List<KiwiSdrView>>(emptyList())
    val kiwis: StateFlow<List<KiwiSdrView>> = _kiwis

    private val _status = MutableStateFlow(FeedStatus(null, "idle", 0L, 0))
    val status: StateFlow<FeedStatus> = _status

    @Volatile
    private var nextAttempt = 0L

    @Volatile
    private var inFlight = false

    /** Reads the public list; safe to call from a switch or a tap, it throttles itself. */
    suspend fun refresh(observer: LatLon?, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (now < nextAttempt || inFlight)) return
        inFlight = true
        try {
            val req = Request.Builder().url(ENDPOINT).header("User-Agent", UA).get().build()
            withContext(Dispatchers.IO) {
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        // The receivers already on the map stay: a refused read is not a
                        // statement that those receivers went off the air.
                        nextAttempt = System.currentTimeMillis() + RETRY_MS
                        _status.value = FeedStatus(
                            false,
                            "HTTP ${resp.code}",
                            System.currentTimeMillis(),
                            _kiwis.value.size,
                        )
                        return@use
                    }
                    val result = parse(body, observer)
                    if (result.status.ok == false) {
                        nextAttempt = System.currentTimeMillis() + RETRY_MS
                        _status.value = result.status
                        return@use
                    }
                    nextAttempt = System.currentTimeMillis() + MIN_GAP_MS
                    _kiwis.value = result.kiwis
                    _status.value = result.status
                }
            }
        } catch (e: IOException) {
            nextAttempt = System.currentTimeMillis() + RETRY_MS
            _status.value = FeedStatus(
                false,
                e.message ?: "network error",
                System.currentTimeMillis(),
                _kiwis.value.size,
            )
        } finally {
            inFlight = false
        }
    }

    /**
     * Parses one copy of the directory.
     *
     * A receiver that is offline, that publishes no position, or that has no usable address is
     * dropped rather than drawn: a pin on the map is a promise that something can be listened to
     * there, and this layer has no other way to keep it.
     */
    internal fun parse(body: String, observer: LatLon?): Result {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val rows = root?.get("receivers") as? JsonArray
            ?: return Result(emptyList(), FeedStatus(false, "no receiver list", 0L, 0))
        val out = ArrayList<KiwiSdrView>(rows.size)
        val seen = HashSet<String>()
        for (row in rows) {
            val o = runCatching { row.jsonObject }.getOrNull() ?: continue
            if ((o["offline"] as? JsonPrimitive)?.booleanOrNull == true) continue
            val url = o.str("url")?.trim().orEmpty()
            if (!url.startsWith("http://", ignoreCase = true) &&
                !url.startsWith("https://", ignoreCase = true)
            ) {
                continue
            }
            // One receiver can be listed more than once through different proxies; the address
            // is what a listener would open, so it is what identifies the entry.
            if (!seen.add(url.trimEnd('/').lowercase(Locale.US))) continue
            val gps = o["gps"] as? JsonArray ?: continue
            val lat = (gps.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: continue
            val lon = (gps.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: continue
            if (!lat.isFinite() || !lon.isFinite()) continue
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            val bands = o["bands"] as? JsonArray
            val lo = (bands?.getOrNull(0) as? JsonPrimitive)?.doubleOrNull?.toLong() ?: 0L
            val hi = (bands?.getOrNull(1) as? JsonPrimitive)?.doubleOrNull?.toLong() ?: 30_000_000L
            out.add(
                KiwiSdrView(
                    id = o.str("id") ?: url,
                    name = o.str("name") ?: url,
                    url = url,
                    lat = lat,
                    lon = lon,
                    // A coverage that is backwards is not coverage; the Kiwi default is 0-30 MHz.
                    loHz = lo,
                    hiHz = if (hi > lo) hi else lo,
                    users = o.int("users") ?: 0,
                    usersMax = o.int("users_max") ?: 0,
                    snr = o.int("snr_hf") ?: o.int("snr_all") ?: o.int("snr") ?: 0,
                    antenna = o.str("antenna").orEmpty(),
                    hardware = o.str("sdr_hw").orEmpty(),
                    grid = o.str("grid").orEmpty(),
                    distanceKm = if (observer == null) {
                        0.0
                    } else {
                        Geo.distanceM(observer.lat, observer.lon, lat, lon) / 1000.0
                    },
                ),
            )
        }
        val ordered = if (observer == null) {
            // With no fix there is no "nearest", and claiming one would be a fiction: the list
            // is alphabetical and the card shows every distance as zero.
            out.sortedBy { it.name.lowercase(Locale.US) }
        } else {
            out.sortedBy { it.distanceKm }
        }
        val kept = if (ordered.size > MAX_KIWI) ordered.subList(0, MAX_KIWI).toList() else ordered
        return Result(
            kept,
            FeedStatus(
                true,
                if (kept.isEmpty()) "no public receivers listed" else "${kept.size} public receivers",
                System.currentTimeMillis(),
                kept.size,
            ),
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
