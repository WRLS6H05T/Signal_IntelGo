package dev.skyward.rf.wardrive

import dev.skyward.rf.core.FileStore
import dev.skyward.rf.core.Layer
import dev.skyward.rf.core.LoggedEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the app has captured while logging, keyed by layer+id.
 *
 * The map paints these entities in the "logged" colour (see SignalScale.LOGGED_COLOR) and
 * the HUD shows how many of each layer have been caught on this device.
 */
class LoggedRegistry(private val store: FileStore) {

    private val file = "logged_entities_v1.json"
    private val map = ConcurrentHashMap<String, LoggedEntity>()
    private val _state = MutableStateFlow<Map<String, LoggedEntity>>(emptyMap())
    val state: StateFlow<Map<String, LoggedEntity>> = _state

    @Volatile
    private var dirty = false

    init {
        val loaded = store.read<List<LoggedEntity>>(file).orEmpty()
        loaded.forEach { map[it.key] = it }
        _state.value = map.toMap()
    }

    fun key(layer: Layer, id: String): String = "${layer.name}:$id"

    /** One entity to record in a batch; see [logBatch]. */
    data class Sighting(
        val layer: Layer,
        val id: String,
        val name: String,
        val lat: Double,
        val lon: Double,
    )

    fun log(layer: Layer, id: String, name: String, lat: Double, lon: Double, now: Long = System.currentTimeMillis()) {
        put(layer, id, name, lat, lon, now)
        publish()
    }

    /**
     * Records a whole sweep with a single publish.
     *
     * [log] re-allocates and republishes the entire entity map for every sighting, so one
     * capture pass over a wide sky meant thousands of full copies per tick - and the table
     * only grows as a drive goes on. That is what made the app get heavier the longer it ran.
     * Batching mutates the table in place and allocates the published snapshot once.
     */
    fun logBatch(now: Long, sightings: List<Sighting>) {
        if (sightings.isEmpty()) return
        for (s in sightings) put(s.layer, s.id, s.name, s.lat, s.lon, now)
        publish()
    }

    private fun put(layer: Layer, id: String, name: String, lat: Double, lon: Double, now: Long) {
        // The table is only ever persisted as JSON, and JSON cannot hold NaN or an infinity. One
        // such pair anywhere in it made the whole file fail to encode, so the save silently
        // stopped and every logged entity was gone at the next start - hidden by the live layers
        // re-logging themselves within a minute. Mesh nodes are heard again in hours, not
        // seconds, which is where the loss showed.
        if (!lat.isFinite() || !lon.isFinite()) return
        val k = key(layer, id)
        val existing = map[k]
        map[k] = if (existing == null) {
            LoggedEntity(
                key = k,
                layer = layer,
                name = name,
                lat = lat,
                lon = lon,
                firstSeen = now,
                lastSeen = now,
                sightings = 1,
            )
        } else {
            existing.copy(
                name = name.ifBlank { existing.name },
                lat = lat,
                lon = lon,
                lastSeen = now,
                sightings = existing.sightings + 1,
            )
        }
        dirty = true
    }

    private fun publish() {
        _state.value = map.toMap()
    }

    fun contains(layer: Layer, id: String): Boolean = map.containsKey(key(layer, id))

    /** One entity if the registry holds it, so a caller can borrow what it already knows. */
    fun find(layer: Layer, id: String): LoggedEntity? = map[key(layer, id)]

    fun count(layer: Layer): Int = map.values.count { it.layer == layer }

    fun entitiesFor(layer: Layer): List<LoggedEntity> = map.values.filter { it.layer == layer }

    fun all(): List<LoggedEntity> = map.values.toList()

    /** Flush to disk; call on pause/stop rather than on every sighting. */
    fun save() {
        if (!dirty) return
        // Only a write that reached the disk clears the flag. Clearing it regardless made a
        // failed save permanent: the table in memory kept growing while the file on disk stayed
        // at whatever it last held.
        if (store.write(file, map.values.toList())) dirty = false
    }

    fun clear() {
        map.clear()
        _state.value = emptyMap()
        store.write(file, emptyList<LoggedEntity>())
        dirty = false
    }
}
