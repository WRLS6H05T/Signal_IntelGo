package dev.skyward.rf.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.pow

data class TileRef(val z: Int, val x: Int, val y: Int)

/** A cached parent tile plus the sub-rectangle of it that covers the requested child. */
data class TileFallback(val bitmap: Bitmap, val srcX: Int, val srcY: Int, val srcSize: Int)

object TileSources {
    const val DARK = "dark"
    const val STREETS = "streets"

    /**
     * CARTO's basemaps now answer every tile request with an "API KEY REQUIRED" placeholder
     * image, so nothing here needs a key: both styles come from OpenStreetMap and DARK is
     * darkened when it is drawn.
     */
    fun url(source: String, z: Int, x: Int, y: Int): String =
        "https://tile.openstreetmap.org/$z/$x/$y.png"

    fun attribution(source: String): String = "© OpenStreetMap contributors"
}

/** Disk + memory cached raster tiles, loaded off the main thread. */
class TileStore(
    context: Context,
    private val http: OkHttpClient,
) {
    private val dir = File(context.cacheDir, "tiles_v2").apply {
        mkdirs()
        // Earlier builds cached CARTO's "API KEY REQUIRED" placeholder under "tiles"; a new
        // directory means those files can never be served again.
        runCatching { File(context.cacheDir, "tiles").deleteRecursively() }
    }
    private val memory = object : LruCache<Long, Bitmap>(96) {
        override fun sizeOf(key: Long, value: Bitmap): Int = 1
    }
    private val inFlight = ConcurrentHashMap.newKeySet<Long>()

    private fun key(z: Int, x: Int, y: Int): Long =
        (z.toLong() shl 40) or (x.toLong() shl 20) or y.toLong()

    private fun fileFor(z: Int, x: Int, y: Int): File = File(dir, "$z/$x/$y.png")

    fun cached(z: Int, x: Int, y: Int): Bitmap? {
        memory.get(key(z, x, y))?.let { return it }
        val f = fileFor(z, x, y)
        if (!f.exists()) return null
        val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() ?: return null
        memory.put(key(z, x, y), bmp)
        return bmp
    }

    /**
     * Nearest cached ancestor covering the same ground. A zoom step then never shows an empty
     * map while the exact level downloads: the parent is stretched over the child's area.
     */
    fun ancestor(z: Int, x: Int, y: Int): TileFallback? {
        var cz = z
        var cx = x
        var cy = y
        while (cz > 2) {
            cz -= 1
            cx = cx shr 1
            cy = cy shr 1
            val bmp = cached(cz, cx, cy) ?: continue
            val n = 1 shl (z - cz)
            val span = bmp.width / n
            if (span < 1) continue
            val subX = x - (cx shl (z - cz))
            val subY = y - (cy shl (z - cz))
            return TileFallback(bmp, subX * span, subY * span, span)
        }
        return null
    }

    /** Loads one tile; returns true when new pixels became available. */
    suspend fun load(source: String, z: Int, x: Int, y: Int): Boolean {
        if (cached(z, x, y) != null) return false
        val k = key(z, x, y)
        if (!inFlight.add(k)) return false
        try {
            return withContext(Dispatchers.IO) {
                val req = Request.Builder()
                    .url(TileSources.url(source, z, x, y))
                    .header("User-Agent", "Signal_IntelGo/1.0 (Android; wardriving app)")
                    .build()
                runCatching {
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use false
                        val bytes = resp.body?.bytes() ?: return@use false
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@use false
                        memory.put(k, bmp)
                        runCatching {
                            val f = fileFor(z, x, y)
                            f.parentFile?.mkdirs()
                            f.writeBytes(bytes)
                        }
                        true
                    }
                }.getOrDefault(false)
            }
        } finally {
            inFlight.remove(k)
        }
    }

    /** Tiles covering the viewport for the current camera. */
    fun visible(source: String, lat: Double, lon: Double, zoom: Double, widthPx: Float, heightPx: Float): List<TileRef> {
        val z = zoom.toInt().coerceIn(2, 18)
        val n = 1 shl z
        val world = 256.0 * 2.0.pow(zoom)
        val tileWorld = world / n
        val cx = dev.skyward.rf.core.Geo.lonToNorm(lon) * world
        val cy = dev.skyward.rf.core.Geo.latToNorm(lat) * world
        val left = cx - widthPx / 2.0
        val top = cy - heightPx / 2.0
        val x0 = floor(left / tileWorld).toInt()
        val x1 = floor((left + widthPx) / tileWorld).toInt()
        val y0 = floor(top / tileWorld).toInt()
        val y1 = floor((top + heightPx) / tileWorld).toInt()
        val out = ArrayList<TileRef>(24)
        for (ty in y0..y1) {
            if (ty < 0 || ty >= n) continue
            for (tx in x0..x1) {
                val wrapped = ((tx % n) + n) % n
                out.add(TileRef(z, wrapped, ty))
            }
        }
        return out
    }

    fun clear() {
        memory.evictAll()
        runCatching { dir.deleteRecursively() }
        dir.mkdirs()
    }
}
