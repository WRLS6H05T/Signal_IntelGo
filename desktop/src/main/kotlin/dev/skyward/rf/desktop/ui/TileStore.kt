package dev.skyward.rf.desktop.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.Image
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Raster tiles for the desktop map.
 *
 * Same source as the phone - OpenStreetMap, keyless - and the same disk-first policy: a tile that
 * has been fetched once is served from disk on the next run, so a map of an area works again with
 * no connection. Decoding goes through Skia, which is what Compose Desktop draws with.
 */
class TileStore(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val dir: File = File(File(System.getProperty("user.home"), "signintelgo"), "tiles"),
) {

    private val memory = ConcurrentHashMap<Long, ImageBitmap>()
    private val pending = ConcurrentHashMap.newKeySet<Long>()

    private val _version = MutableStateFlow(0)

    /** Counts finished loads, so the canvas redraws when a tile arrives. */
    val version: StateFlow<Int> = _version

    private fun key(z: Int, x: Int, y: Int): Long = (z.toLong() shl 40) or (x.toLong() shl 20) or y.toLong()

    fun url(z: Int, x: Int, y: Int): String = "https://tile.openstreetmap.org/$z/$x/$y.png"

    fun cached(z: Int, x: Int, y: Int): ImageBitmap? {
        memory[key(z, x, y)]?.let { return it }
        val file = File(dir, "$z/$x/$y.png")
        if (!file.exists()) return null
        val bitmap = runCatching { Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }.getOrNull() ?: return null
        memory[key(z, x, y)] = bitmap
        return bitmap
    }

    /** Asks for a tile that is not here yet; a no-op while one request is already in flight. */
    fun request(z: Int, x: Int, y: Int) {
        if (z < 0 || z > 19) return
        val k = key(z, x, y)
        if (memory.containsKey(k)) return
        if (!pending.add(k)) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val file = File(dir, "$z/$x/$y.png")
                if (!file.exists()) {
                    file.parentFile?.mkdirs()
                    val request = Request.Builder()
                        .url(url(z, x, y))
                        .header("User-Agent", "Signal_IntelGo-desktop/1.0")
                        .build()
                    http.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            response.body?.bytes()?.let { bytes ->
                                file.writeBytes(bytes)
                                Image.makeFromEncoded(bytes).toComposeImageBitmap().let { memory[k] = it }
                            }
                        }
                    }
                } else {
                    Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap().let { memory[k] = it }
                }
            }
            pending.remove(k)
            _version.value = _version.value + 1
        }
    }
}
