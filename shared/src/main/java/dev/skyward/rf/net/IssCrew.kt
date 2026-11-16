package dev.skyward.rf.net

import dev.skyward.rf.core.FileStore
import dev.skyward.rf.core.appJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Who is aboard the station.
 *
 * This is the one thing about the ISS that the element sets cannot say: a TLE says where the
 * station is, never how many people are in it. The roster is a keyless, community-maintained
 * JSON kept current from launch and docking schedules, and the count taken from it is the
 * people flagged as being on the station - not the total in space, because a Tiangong crew and
 * a free-flying Dragon are in orbit without being aboard.
 *
 * Cached on disk for a day: a crew change is a launch, not a tick, so an offline phone still
 * answers with yesterday's count rather than with nothing. A failed read keeps the last good
 * count and carries the error beside it - dropping to null would turn a network hiccup into
 * "nobody is aboard".
 */
class IssCrew(private val http: OkHttpClient, private val store: FileStore) {

    @Serializable
    private data class Answer(
        @SerialName("iss_expedition") val expedition: Int? = null,
        val people: List<Person> = emptyList(),
    )

    @Serializable
    private data class Person(
        val name: String = "",
        val spacecraft: String = "",
        val iss: Boolean = false,
    )

    /** What the station card shows: the number aboard, the expedition, and when it was read. */
    data class Crew(
        val aboard: Int,
        val expedition: Int? = null,
        val names: List<String> = emptyList(),
        val at: Long = 0L,
        val error: String? = null,
    ) {
        /** "7 · Expedition 75", or the bare count when there is no expedition to name. */
        val label: String
            get() = when {
                aboard <= 0 -> "—"
                expedition != null -> "$aboard · Expedition $expedition"
                else -> aboard.toString()
            }
    }

    @Serializable
    private data class Cache(
        val aboard: Int = 0,
        val expedition: Int? = null,
        val names: List<String> = emptyList(),
        val at: Long = 0L,
    )

    companion object {
        const val ENDPOINT =
            "https://corquaid.github.io/international-space-station-APIs/JSON/people-in-space.json"

        /** A crew change is a launch or a landing, so a day-old answer is still the answer. */
        const val TTL_MS = 24 * 60 * 60_000L

        /** A failed read is retried on this gap, not on every tap. */
        const val RETRY_MS = 10 * 60_000L

        private const val FILE = "iss_crew.json"
        private const val UA = "Signal_IntelGo/1.0 (ISS crew)"

        /**
         * Counts the people flagged aboard. Null means the answer could not be read, including
         * a roster where nobody is flagged: that is a reshaped or broken answer, not a station
         * that has been evacuated.
         */
        fun parse(body: String): Crew? {
            val answer = runCatching { appJson.decodeFromString<Answer>(body) }.getOrNull() ?: return null
            val aboard = answer.people.filter { it.iss }
            if (aboard.isEmpty()) return null
            return Crew(
                aboard = aboard.size,
                expedition = answer.expedition,
                names = aboard.map { it.name }.filter { it.isNotBlank() },
            )
        }
    }

    private val _state = MutableStateFlow<Crew?>(null)
    val state: StateFlow<Crew?> = _state

    @Volatile
    private var inFlight = false

    @Volatile
    private var nextAttempt = 0L

    init {
        // Loaded before any network call, so the card has an answer the moment it opens.
        store.read<Cache>(FILE)?.let { c ->
            if (c.aboard > 0) _state.value = Crew(c.aboard, c.expedition, c.names, c.at)
        }
    }

    /** Fetches when the cached answer is older than [TTL_MS]; safe to call on every tap. */
    suspend fun refreshIfStale(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val prev = _state.value
        if (!force && prev != null && prev.error == null && now - prev.at < TTL_MS) return
        if (!force && now < nextAttempt) return
        if (inFlight) return
        inFlight = true
        try {
            val body = withContext(Dispatchers.IO) {
                http.newCall(
                    Request.Builder()
                        .url(ENDPOINT)
                        .header("Accept", "application/json")
                        .header("User-Agent", UA)
                        .get()
                        .build(),
                ).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    resp.body?.string().orEmpty()
                }
            }
            val crew = parse(body) ?: throw IOException("no crew in the answer")
            val at = System.currentTimeMillis()
            _state.value = crew.copy(at = at)
            store.write(FILE, Cache(crew.aboard, crew.expedition, crew.names, at))
            nextAttempt = 0L
        } catch (e: IOException) {
            nextAttempt = System.currentTimeMillis() + RETRY_MS
            _state.value = (prev ?: Crew(aboard = 0)).copy(error = e.message ?: "network error")
        } finally {
            inFlight = false
        }
    }
}
