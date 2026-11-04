package dev.skyward.rf.net

import dev.skyward.rf.core.appJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos

/**
 * Current conditions at a fix, from Open-Meteo.
 *
 * Open-Meteo is keyless, so there is no account, no token in the APK and nothing to configure
 * before the clock's drop-down works. The feed is asked only when it has something new to say:
 * at most every ten minutes, and again after the phone has moved two kilometres, because the
 * weather at the pin does not change between two taps on the same street corner.
 *
 * "Current" is the model's analysis for the hour, not a station a hundred miles away, and
 * [Now.elevationM] is the terrain height at the pin - useful on its own when the barometer and
 * the map disagree.
 */
class WeatherFeed(private val http: OkHttpClient) {

    @Serializable
    private data class Answer(
        val elevation: Double? = null,
        val current: Current? = null,
    )

    @Serializable
    private data class Current(
        val time: String? = null,
        @SerialName("temperature_2m") val tempC: Double? = null,
        @SerialName("apparent_temperature") val feelsC: Double? = null,
        @SerialName("relative_humidity_2m") val humidity: Int? = null,
        val precipitation: Double? = null,
        @SerialName("weather_code") val code: Int? = null,
        @SerialName("cloud_cover") val cloud: Int? = null,
        @SerialName("pressure_msl") val pressureHpa: Double? = null,
        @SerialName("wind_speed_10m") val windKmh: Double? = null,
        @SerialName("wind_gusts_10m") val gustsKmh: Double? = null,
        @SerialName("wind_direction_10m") val windDir: Int? = null,
    )

    data class Now(
        val lat: Double,
        val lon: Double,
        val tempC: Double? = null,
        val feelsC: Double? = null,
        val humidity: Int? = null,
        val cloud: Int? = null,
        val precipMm: Double? = null,
        val windKmh: Double? = null,
        val gustsKmh: Double? = null,
        val windDir: Int? = null,
        val pressureHpa: Double? = null,
        val elevationM: Double? = null,
        val condition: String = "—",
        val at: Long = 0L,
        val error: String? = null,
    )

    companion object {
        const val ENDPOINT = "https://api.open-meteo.com/v1/forecast"

        /** The weather does not change between two taps; a refetch per tap would be rude. */
        const val MIN_GAP_MS = 10 * 60_000L

        /** A failed read is retried on this gap, not on every GPS fix. */
        const val RETRY_MS = 60_000L

        /** Walking across town is a different forecast; moving across a room is not. */
        const val MOVE_KM = 2.0

        private const val FIELDS = "temperature_2m,relative_humidity_2m,apparent_temperature," +
            "precipitation,weather_code,cloud_cover,pressure_msl,wind_speed_10m," +
            "wind_direction_10m,wind_gusts_10m"
    }

    private val _state = MutableStateFlow<Now?>(null)
    val state: StateFlow<Now?> = _state

    @Volatile
    private var nextAttempt = 0L

    @Volatile
    private var inFlight = false

    /** Fetches when the fix is new enough to be worth it; safe to call on every fix. */
    suspend fun refresh(lat: Double, lon: Double, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val prev = _state.value
        if (!force && now < nextAttempt) return
        if (!force && prev != null && prev.error == null) {
            val moved = groundKm(prev.lat, prev.lon, lat, lon) > MOVE_KM
            if (!moved && now - prev.at < MIN_GAP_MS) return
        }
        if (inFlight) return
        inFlight = true
        try {
            val url = ENDPOINT.toHttpUrl().newBuilder()
                .addQueryParameter("latitude", String.format(Locale.US, "%.4f", lat))
                .addQueryParameter("longitude", String.format(Locale.US, "%.4f", lon))
                .addQueryParameter("current", FIELDS)
                .addQueryParameter("wind_speed_unit", "kmh")
                .addQueryParameter("timezone", "auto")
                .build()
            val body = withContext(Dispatchers.IO) {
                http.newCall(
                    Request.Builder()
                        .url(url)
                        .header("Accept", "application/json")
                        .get()
                        .build(),
                ).execute().use { resp -> resp.body?.string().orEmpty() }
            }
            val answer = runCatching { appJson.decodeFromString<Answer>(body) }.getOrNull()
            val c = answer?.current
            _state.value = if (c == null) {
                nextAttempt = now + RETRY_MS
                (prev ?: Now(lat, lon)).copy(
                    lat = lat,
                    lon = lon,
                    at = now,
                    error = "no weather in the answer",
                )
            } else {
                nextAttempt = now + MIN_GAP_MS
                Now(
                    lat = lat,
                    lon = lon,
                    tempC = c.tempC,
                    feelsC = c.feelsC,
                    humidity = c.humidity,
                    cloud = c.cloud,
                    precipMm = c.precipitation,
                    windKmh = c.windKmh,
                    gustsKmh = c.gustsKmh,
                    windDir = c.windDir,
                    pressureHpa = c.pressureHpa,
                    elevationM = answer.elevation,
                    condition = describe(c.code),
                    at = now,
                )
            }
        } catch (e: IOException) {
            nextAttempt = now + RETRY_MS
            _state.value = (prev ?: Now(lat, lon)).copy(at = now, error = e.message ?: "network error")
        } finally {
            inFlight = false
        }
    }

    private fun groundKm(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = (bLat - aLat) * 111.0
        val dLon = (bLon - aLon) * 111.0 * cos(((aLat + bLat) / 2.0) * Math.PI / 180.0)
        return abs(dLat) + abs(dLon)
    }
}

/** WMO weather codes, as Open-Meteo reports them. */
fun describe(code: Int?): String = when (code) {
    0 -> "Clear"
    1 -> "Mainly clear"
    2 -> "Partly cloudy"
    3 -> "Overcast"
    45, 48 -> "Fog"
    51, 53, 55 -> "Drizzle"
    56, 57 -> "Freezing drizzle"
    61 -> "Light rain"
    63 -> "Rain"
    65 -> "Heavy rain"
    66, 67 -> "Freezing rain"
    71, 73, 75 -> "Snow"
    77 -> "Snow grains"
    80, 81, 82 -> "Rain showers"
    85, 86 -> "Snow showers"
    95 -> "Thunderstorm"
    96, 99 -> "Thunderstorm with hail"
    null -> "—"
    else -> "WMO $code"
}

/** Sixteen-point compass, for the wind arrow-less readout. */
fun compassPoint(deg: Int?): String {
    val d = deg ?: return ""
    val points = listOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )
    return points[((d % 360 + 360) % 360 + 11) / 22 % 16]
}
