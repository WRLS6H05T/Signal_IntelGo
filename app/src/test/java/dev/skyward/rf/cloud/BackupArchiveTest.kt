package dev.skyward.rf.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {

    private fun tempDir(): File = Files.createTempDirectory("intelgo-test").toFile()

    private fun write(root: File, path: String, text: String) {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    @Test
    fun `pack keeps the data and skips the tile cache`() {
        val dir = tempDir()
        write(dir, "settings_v1.json", """{"logRadiusKm":250.0}""")
        write(dir, "wardrives/session-1.jsonl", """{"lat":51.5,"lon":-0.12}""" + "\n")
        write(dir, "tle/cache.json", """{"fetched":1}""")

        val bundle = BackupArchive.pack(dir, now = 1_700_000_000_000L)

        // Two files: the settings and the session. The TLE cache is fetched again on first use.
        assertEquals(2, bundle.manifest.files)
        assertTrue(bundle.chunks.isNotEmpty())
        assertEquals(1_700_000_000_000L, bundle.manifest.createdAt)
    }

    @Test
    fun `a backup survives the round trip`() {
        val source = tempDir()
        write(source, "settings_v1.json", """{"peerEnabled":true}""")
        write(source, "signals/log.json", """[{"bssid":"aa:bb"}]""")
        write(source, "wardrives/a.jsonl", "line one\nline two\n")

        val bundle = BackupArchive.pack(source)
        val restored = tempDir()
        val manifest = BackupArchive.unpack(bundle.chunks.joinToString(""), restored)

        assertEquals(3, manifest?.files)
        assertEquals("""{"peerEnabled":true}""", File(restored, "settings_v1.json").readText())
        assertEquals("line one\nline two\n", File(restored, "wardrives/a.jsonl").readText())
        assertEquals("""[{"bssid":"aa:bb"}]""", File(restored, "signals/log.json").readText())
    }

    @Test
    fun `a large archive is cut into pieces that fit one insert`() {
        val source = tempDir()
        // Random bytes do not compress, so this really does exceed a single chunk.
        val blob = ByteArray(400_000).also { java.util.Random(7).nextBytes(it) }
        File(source, "wardrives/big.bin").apply { parentFile?.mkdirs() }.writeBytes(blob)

        val bundle = BackupArchive.pack(source)

        assertTrue("expected more than one piece", bundle.chunks.size > 1)
        assertTrue(bundle.chunks.all { it.length <= BackupArchive.CHUNK_CHARS })
        val restored = tempDir()
        BackupArchive.unpack(bundle.chunks.joinToString(""), restored)
        assertTrue(File(restored, "wardrives/big.bin").readBytes().contentEquals(blob))
    }

    @Test
    fun `an entry that points outside the data directory is dropped`() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { out ->
            out.putNextEntry(ZipEntry("../escaped.txt"))
            out.write("no".toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("kept.txt"))
            out.write("yes".toByteArray())
            out.closeEntry()
        }
        val encoded = Base64.getEncoder().encodeToString(zip.toByteArray())
        val root = File(tempDir(), "data").apply { mkdirs() }

        BackupArchive.unpack(encoded, root)

        assertFalse(File(root.parentFile, "escaped.txt").exists())
        assertEquals("yes", File(root, "kept.txt").readText())
    }

    @Test
    fun `an empty account row restores nothing`() {
        assertNull(BackupArchive.unpack("", tempDir()))
    }
}
