package dev.skyward.rf.desktop.ui

/**
 * The Natural Earth land rings the phone bundles, read from the desktop's own resources.
 *
 * Same asset, same format: a header line, then one ring per line as `lon lat` pairs in
 * hundredths of a degree. The file is not copied into the repository a second time - the build
 * puts the phone's copy on the desktop classpath - so the two screens cannot disagree about
 * where a coastline is.
 */
object WorldLand {

    private var rings: Array<FloatArray> = emptyArray()

    fun load(): Array<FloatArray> {
        if (rings.isNotEmpty()) return rings
        synchronized(this) {
            if (rings.isNotEmpty()) return rings
            rings = runCatching {
                val stream = WorldLand::class.java.getResourceAsStream("/world_land.txt") ?: return emptyArray()
                stream.bufferedReader().useLines { lines ->
                    val out = ArrayList<FloatArray>(160)
                    var lineNo = 0
                    for (raw in lines) {
                        lineNo++
                        if (lineNo == 1) continue
                        val text = raw.trim()
                        if (text.length < 11) continue
                        val parts = text.split(' ')
                        if (parts.size < 6 || parts.size % 2 != 0) continue
                        val arr = FloatArray(parts.size)
                        var ok = true
                        for (i in parts.indices) {
                            val v = parts[i].toFloatOrNull()
                            if (v == null) {
                                ok = false
                                break
                            }
                            arr[i] = v / 100f
                        }
                        if (ok) out.add(arr)
                    }
                    out.toTypedArray()
                }
            }.getOrDefault(emptyArray())
        }
        return rings
    }
}
