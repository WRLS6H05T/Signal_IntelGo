package dev.skyward.rf.cloud

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The account copy of the app's data: one zip of the data directory, base64, cut into pieces
 * small enough to sit in a single REST insert.
 *
 * Deflate does the real work here - the data is JSON text and repeats itself heavily (a drive's
 * log names the same access points over and over), so a few megabytes of text fits in a few
 * hundred kilobytes. The chunking exists because one insert has a request-size ceiling, and a
 * backup that fails silently once a drive gets long is worse than no backup at all.
 *
 * Nothing in here touches Android, so the round trip is covered by a plain JVM test.
 */
object BackupArchive {

    /** Entry name of the manifest written first in the zip. */
    const val MANIFEST = "backup.json"

    /**
     * Base64 characters per stored chunk: 240k characters is 180 kB of text, well inside what a
     * REST insert accepts, while keeping a multi-megabyte archive to a handful of pieces.
     */
    const val CHUNK_CHARS = 240_000

    /** Cached element sets are downloaded again on first use, so they do not belong in a backup. */
    private val SKIP_DIRS = setOf("tle")

    @Serializable
    data class Manifest(
        val createdAt: Long,
        val files: Int,
        val plainBytes: Long,
        val app: String = "Signal_IntelGo",
    )

    data class Bundle(val manifest: Manifest, val chunks: List<String>) {
        /** Characters to send, which is what the request size is measured in. */
        val encodedChars: Int get() = chunks.sumOf { it.length }
    }

    /** Zips [root], skipping regenerable directories, and cuts the base64 into pieces. */
    fun pack(root: File, now: Long = System.currentTimeMillis()): Bundle {
        val files = root.walkTopDown()
            .filter { it.isFile }
            .filter { f ->
                val path = f.relativeTo(root).invariantSeparatorsPath
                SKIP_DIRS.none { d -> path.startsWith("$d/") } && !path.endsWith(".tmp")
            }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .toList()

        val manifest = Manifest(
            createdAt = now,
            files = files.size,
            plainBytes = files.sumOf { it.length() },
        )
        val plain = ByteArrayOutputStream()
        ZipOutputStream(plain).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(Json.encodeToString(manifest).toByteArray())
            zip.closeEntry()
            for (f in files) {
                zip.putNextEntry(ZipEntry(f.relativeTo(root).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }

        val encoded = Base64.getEncoder().encodeToString(plain.toByteArray())
        val chunks = if (encoded.isEmpty()) listOf("") else encoded.chunked(CHUNK_CHARS)
        return Bundle(manifest = manifest, chunks = chunks)
    }

    /**
     * Writes the archive back over [root] and returns its manifest.
     *
     * Entries that would land outside the data directory are dropped rather than sanitised: a
     * backup is data, and a zip is a file anyone can rewrite.
     */
    fun unpack(encoded: String, root: File): Manifest? {
        if (encoded.isBlank()) return null
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
        var manifest: Manifest? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                if (!entry.isDirectory) {
                    val target = target(root, name)
                    if (name == MANIFEST) {
                        manifest = runCatching {
                            Json.decodeFromString<Manifest>(zip.readBytes().toString(Charsets.UTF_8))
                        }.getOrNull()
                    } else if (target != null) {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return manifest
    }

    /** The file an entry may be written to, or null when the name tries to escape [root]. */
    private fun target(root: File, name: String): File? {
        if (name.isBlank() || name.startsWith("/") || name.contains("..")) return null
        val file = File(root, name)
        val rootPath = root.canonicalPath + File.separator
        return if (file.canonicalPath.startsWith(rootPath)) file else null
    }
}
