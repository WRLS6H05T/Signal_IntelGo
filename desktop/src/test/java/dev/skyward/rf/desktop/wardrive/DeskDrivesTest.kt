package dev.skyward.rf.desktop.wardrive

import dev.skyward.rf.wardrive.WardrivePoint
import dev.skyward.rf.wardrive.WardriveSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.file.Files

/**
 * The only part of the drive transfer a build machine can prove: the phone's session JSON is what
 * gets written, and what is written is what the list and the map read back.
 */
class DeskDrivesTest {

    private fun session(id: String = "abc") = WardriveSession(
        id = id,
        name = "evening loop",
        startedAt = 1_700_000_000_000L,
        endedAt = 1_700_000_200_000L,
        points = listOf(
            WardrivePoint(lat = 51.5, lon = -0.12, t = 1_700_000_000_000L, score = 40, avgScore = 30, wifiCount = 2, bleCount = 1),
            WardrivePoint(lat = 51.6, lon = -0.10, t = 1_700_000_100_000L, score = 50, avgScore = 35, wifiCount = 3, bleCount = 2),
        ),
        distanceM = 12_300.0,
        wifiAps = 9,
        bleDevices = 4,
    )

    @Test
    fun `a drive from the phone is kept, listed and readable as a track`() {
        val home = Files.createTempDirectory("desk-drives").toFile()
        val store = DeskDrives(home)
        val json = Json.encodeToString(WardriveSession.serializer(), session())

        val row = store.save(json, "pixel")
        assertNotNull(row)
        assertEquals(2, row!!.points)
        assertEquals(9, row.wifi)
        assertEquals("pixel", row.from)

        val listed = store.list()
        assertEquals(1, listed.size)
        assertEquals("evening loop", listed[0].name)

        val track = store.track("abc")
        assertEquals(2, track.size)
        assertEquals(51.5, track.first().lat, 1e-9)
        assertEquals(-0.10, track.last().lon, 1e-9)
    }

    @Test
    fun `a second fetch of the same drive replaces it instead of duplicating`() {
        val home = Files.createTempDirectory("desk-drives").toFile()
        val store = DeskDrives(home)
        val json = Json.encodeToString(WardriveSession.serializer(), session())

        store.save(json, "pixel")
        store.save(json, "pixel")

        assertEquals(1, store.list().size)
    }

    @Test
    fun `something that is not a session is refused`() {
        val home = Files.createTempDirectory("desk-drives").toFile()
        val store = DeskDrives(home)

        assertEquals(null, store.save("{\"not\":\"a drive\"}", "pixel"))
        assertEquals(0, store.list().size)
        assertEquals(0, store.track("missing").size)
    }
}
