package dev.skyward.rf.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow

data class TileRef(val z: Int, val x: Int, val y: Int)

/** A cached parent tile plus the sub-rectangle of it that covers the requested child. */
data class TileFallback(
    val bitmap: Bitmap,
    val srcX: Int,
    val srcY: Int,
    val srcSize: Int,
    /** Where the parent sits in the cache, so its draw-ready wrapper can be looked up too. */
    val z: Int = 0,
    val x: Int = 0,
    val y: Int = 0,
)

/** One cached tile, draw-ready: the image wrapper is kept so a frame never makes a new one. */
data class TileImage(val image: ImageBitmap, val srcX: Int, val srcY: Int, val srcSize: Int)

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
    // Files, not cacheDir: a tile the user deliberately saved for offline use must not be the
    // first thing the system evicts when storage runs low. CLEAR TILE CACHE removes it.
    private val dir = File(context.filesDir, "tiles_v2").apply {
        mkdirs()
        // Earlier builds cached CARTO's "API KEY REQUIRED" placeholder under "tiles"; a new
        // directory means those files can never be served again.
        runCatching { File(context.cacheDir, "tiles").deleteRecursively() }
        runCatching { File(context.cacheDir, "tiles_v2").deleteRecursively() }
    }
    private val memory = object : LruCache<Long, Bitmap>(96) {
        override fun sizeOf(key: Long, value: Bitmap): Int = 1
    }
    private val inFlight = ConcurrentHashMap.newKeySet<Long>()

    // Drawing wraps each bitmap in an ImageBitmap, and doing that per tile per frame was one
    // object a tile a frame - dozens of allocations a frame for pixels that had not changed.
    // The wrappers are held beside the bitmaps, under the same key.
    private val images = object : LruCache<Long, ImageBitmap>(96) {
        override fun sizeOf(key: Long, value: ImageBitmap): Int = 1
    }

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
            return TileFallback(bmp, subX * span, subY * span, span, cz, cx, cy)
        }
        return null
    }

    /** A tile ready for `drawImage`: [cached], with its image wrapper made once. */
    fun cachedImage(z: Int, x: Int, y: Int): ImageBitmap? {
        val k = key(z, x, y)
        images.get(k)?.let { return it }
        val bmp = cached(z, x, y) ?: return null
        val image = bmp.asImageBitmap()
        images.put(k, image)
        return image
    }

    /** [ancestor], with the parent's image wrapper reused rather than rebuilt. */
    fun ancestorImage(z: Int, x: Int, y: Int): TileImage? {
        val fb = ancestor(z, x, y) ?: return null
        val pk = key(fb.z, fb.x, fb.y)
        val image = images.get(pk) ?: fb.bitmap.asImageBitmap().also { images.put(pk, it) }
        return TileImage(image, fb.srcX, fb.srcY, fb.srcSize)
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
    fun visible(
        source: String,
        lat: Double,
        lon: Double,
        zoom: Double,
        widthPx: Float,
        heightPx: Float,
        /** Same centre offset the projection draws with, or the tiles and the pins drift apart. */
        shiftX: Float = 0f,
    ): List<TileRef> {
        val z = zoom.toInt().coerceIn(2, 18)
        val n = 1 shl z
        val world = 256.0 * 2.0.pow(zoom)
        val tileWorld = world / n
        val cx = dev.skyward.rf.core.Geo.lonToNorm(lon) * world
        val cy = dev.skyward.rf.core.Geo.latToNorm(lat) * world
        val left = cx - widthPx / 2.0 - (if (shiftX.isFinite()) shiftX else 0f)
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

    /**
     * Warms the disk cache around a point so the map still draws with no network.
     *
     * Bounded on purpose. These tiles come from a volunteer-run server, so this saves a
     * neighbourhood at street zooms - the ground you are about to walk or drive - rather than
     * a country. Coarse zooms are fetched first because they cover the whole area, so the map
     * is readable even if the download is stopped early.
     */
    suspend fun downloadArea(
        source: String,
        lat: Double,
        lon: Double,
        maxZoom: Int,
        radiusKm: Double = 3.0,
        cap: Int = 900,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        if (!lat.isFinite() || !lon.isFinite()) return 0
        val zMax = maxZoom.coerceIn(11, 17)
        val zMin = (zMax - 3).coerceAtLeast(9)
        val refs = LinkedHashSet<TileRef>()
        for (z in zMin..zMax) {
            val n = 1 shl z
            // Ground width of one tile at this latitude and zoom.
            val metresPerTile = 40_075_016.0 * cos(Math.toRadians(lat.coerceIn(-85.0, 85.0))) / n
            val span = ((radiusKm * 1000.0) / metresPerTile).toInt().coerceIn(0, 12)
            val cx = (dev.skyward.rf.core.Geo.lonToNorm(lon) * n).toInt()
            val cy = (dev.skyward.rf.core.Geo.latToNorm(lat) * n).toInt()
            for (dy in -span..span) {
                val y = cy + dy
                if (y < 0 || y >= n) continue
                for (dx in -span..span) refs.add(TileRef(z, ((cx + dx) % n + n) % n, y))
            }
            if (refs.size >= cap) break
        }
        val list = refs.take(cap)
        var saved = 0
        var done = 0
        for (t in list) {
            if (cached(t.z, t.x, t.y) == null && load(source, t.z, t.x, t.y)) saved++
            done++
            // Not every tile: a few hundred recompositions of the label is wasted work.
            if (done % 8 == 0 || done == list.size) onProgress(done, list.size)
        }
        return saved
    }

    fun clear() {
        memory.evictAll()
        images.evictAll()
        runCatching { dir.deleteRecursively() }
        dir.mkdirs()
    }
}
