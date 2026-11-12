package dev.skyward.rf.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * OSM raster tiles for the flat map.
 *
 * OpenStreetMap's volunteer tile servers are a shared resource: they ask for an identifying
 * User-Agent and no bulk downloading, so every tile is asked for once per run, kept in a bounded
 * memory cache, and drawn from there for as long as the desk is open. Nothing is prefetched - a
 * tile exists because the viewport needed it.
 *
 * [generation] is bumped when a tile lands, and the canvas reads it, so a tile that arrives
 * after the frame it was requested for still gets drawn on the next one.
 */
class TileStore(private val scope: CoroutineScope) {

    private val http = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val cache = object : LinkedHashMap<String, ImageBitmap>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > 320
    }

    private val pending = HashSet<String>()

    /** Bumped when a tile lands; the canvas reads this so a new tile is not lost to caching. */
    var generation by mutableIntStateOf(0)
        private set

    /** The tile if it is already here; a miss starts the download and returns null. */
    fun image(z: Int, x: Int, y: Int): ImageBitmap? {
        val key = "$z/$x/$y"
        synchronized(cache) { cache[key] }?.let { return it }
        val first = synchronized(pending) { pending.add(key) }
        if (!first) return null
        scope.launch(Dispatchers.IO) { fetch(key, z, x, y) }
        return null
    }

    private fun fetch(key: String, z: Int, x: Int, y: Int) {
        try {
            val request = Request.Builder()
                .url("https://tile.openstreetmap.org/$z/$x/$y.png")
                .header("User-Agent", USER_AGENT)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val bytes = response.body?.bytes() ?: return
                val image = org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                synchronized(cache) { cache[key] = image }
                generation++
            }
        } catch (_: Exception) {
            // A tile that will not load stays missing: the map draws the grid behind it and the
            // next pass asks again. There is nothing to report to the operator for one tile.
        } finally {
            synchronized(pending) { pending.remove(key) }
        }
    }

    companion object {
        const val USER_AGENT: String = "Signal_IntelGo/1.0 (desktop command centre; wardriving)"
    }
}
