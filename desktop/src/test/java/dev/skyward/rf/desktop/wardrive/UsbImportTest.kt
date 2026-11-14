package dev.skyward.rf.desktop.wardrive

import dev.skyward.rf.wardrive.WardrivePoint
import dev.skyward.rf.wardrive.WardriveSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * What a cable pull can be proved to do on a machine with no phone on it: read the layout the phone
 * writes, and hand it to the same store the Bluetooth path uses.
 */
class UsbImportTest {

    private fun sessionJson(id: String = "abc"): String = Json.encodeToString(
        WardriveSession.serializer(),
        WardriveSession(
            id = id,
            name = "cable loop",
            startedAt = 1_700_000_000_000L,
            endedAt = 1_700_000_200_000L,
            points = listOf(
                WardrivePoint(lat = 51.5, lon = -0.12, t = 1_700_000_000_000L, score = 40, avgScore = 30, wifiCount = 2, bleCount = 1),
                WardrivePoint(lat = 51.6, lon = -0.10, t = 1_700_000_100_000L, score = 50, avgScore = 35, wifiCount = 3, bleCount = 2),
            ),
            distanceM = 12_300.0,
            wifiAps = 9,
            bleDevices = 4,
        ),
    )

    @Test
    fun `an export folder pulled off a phone is imported, twice without duplicating`() {
        val home = Files.createTempDirectory("usb-import").toFile()
        val drives = DeskDrives(home)
        val importer = UsbImport(drives, home)
        val export = File(home, "SignalIntelGo/wardrives").apply { mkdirs() }
        File(home, "SignalIntelGo/readme.txt").writeText("not a session")
        File(export, "session_abc.json").writeText(sessionJson())

        assertEquals(1, importer.importFolder(File(home, "SignalIntelGo")))
        // A phone left plugged in is pulled again on the next pass, so importing must be idempotent.
        assertEquals(1, importer.importFolder(File(home, "SignalIntelGo")))

        val listed = drives.list()
        assertEquals(1, listed.size)
        assertEquals("cable loop", listed[0].name)
        assertEquals(2, drives.track("abc").size)
    }

    @Test
    fun `a folder with no sessions imports nothing`() {
        val home = Files.createTempDirectory("usb-import-empty").toFile()
        val importer = UsbImport(DeskDrives(home), home)
        val empty = File(home, "empty").apply { mkdirs() }
        File(empty, "notes.txt").writeText("nothing here")
        assertEquals(0, importer.importFolder(empty))
    }
}
