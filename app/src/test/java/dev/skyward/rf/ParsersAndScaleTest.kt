package dev.skyward.rf

import dev.skyward.rf.core.FileStore
import dev.skyward.rf.core.Geo
import dev.skyward.rf.core.SignalScale
import dev.skyward.rf.scan.OdidParser
import dev.skyward.rf.wardrive.LoggedRegistry
import dev.skyward.rf.wardrive.WardriveEngine
import dev.skyward.rf.wardrive.WardrivePoint
import dev.skyward.rf.wardrive.WardriveSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ParsersAndScaleTest {

    // ---------------- helpers ----------------

    private fun le32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )

    private fun le16(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
    )

    /** ODID Location/Vector body (22 bytes) as broadcast in the wild. */
    private fun locationBody(lat: Double, lon: Double, speed: Int, heading: Int, altM: Double): ByteArray {
        val b = ByteArray(22)
        b[0] = 1 // operational status
        b[1] = heading.toByte()
        b[2] = speed.toByte()
        b[3] = 0
        le32((lat * 1e7).toInt()).copyInto(b, 4)
        le32((lon * 1e7).toInt()).copyInto(b, 8)
        le16((altM * 2 + 1000).toInt()).copyInto(b, 12)
        le16((altM * 2 + 1000).toInt()).copyInto(b, 14)
        le16((altM * 2 + 1000).toInt()).copyInto(b, 16)
        return b
    }

    private fun basicIdBody(id: String): ByteArray {
        val b = ByteArray(21)
        b[0] = 1 // serial number
        id.take(20).forEachIndexed { i, c -> b[1 + i] = c.code.toByte() }
        return b
    }

    // ---------------- Remote ID ----------------

    @Test
    fun parsesSingleLocationMessage() {
        val payload = byteArrayOf(0x10) + locationBody(37.7749, -122.4194, 15, 128, 120.0)
        val frame = OdidParser.parse(payload)
        assertNotNull(frame)
        requireNotNull(frame)
        assertEquals(37.7749, frame.lat!!, 0.0001)
        assertEquals(-122.4194, frame.lon!!, 0.0001)
        assertEquals(15.0, frame.speedMs!!, 0.001)
        assertEquals(180.0, frame.headingDeg!!, 0.5)
        assertEquals(120.0, frame.geodeticAltM!!, 0.01)
        assertEquals(120.0, frame.heightM!!, 0.01)
    }

    @Test
    fun parsesMessagePackWithBasicIdAndLocation() {
        val idBody = basicIdBody("TESTUAS123")
        val loc = locationBody(51.5074, -0.1278, 7, 64, 80.0)
        val pack = byteArrayOf(
            0xF0.toByte(),
            0x00, idBody.size.toByte(),
        ) + idBody + byteArrayOf(
            0x10, loc.size.toByte(),
        ) + loc

        val frame = OdidParser.parse(pack)
        assertNotNull(frame)
        requireNotNull(frame)
        assertEquals("TESTUAS123", frame.uasId)
        assertEquals(51.5074, frame.lat!!, 0.0001)
        assertEquals(-0.1278, frame.lon!!, 0.0001)
    }

    @Test
    fun extractsServiceDataFromScanRecord() {
        val payload = byteArrayOf(0x10) + locationBody(10.0, 20.0, 5, 32, 60.0)
        val adStructure = byteArrayOf(
            (3 + payload.size).toByte(),
            0x16,
            0xFA.toByte(),
            0xFF.toByte(),
        ) + payload
        val extracted = OdidParser.extractServiceData(adStructure)
        assertNotNull(extracted)
        requireNotNull(extracted)
        assertEquals(payload.size, extracted.size)
        assertEquals(payload[0], extracted[0])
    }

    @Test
    fun rejectsGarbageWithoutThrowing() {
        assertEquals(null, OdidParser.parse(ByteArray(0)))
        assertEquals(null, OdidParser.extractServiceData(byteArrayOf(3, 0x09, 1, 2)))
        // Truncated location message must not crash the scanner.
        OdidParser.parse(byteArrayOf(0x10, 1, 2))
    }

    // ---------------- colour scale ----------------

    @Test
    fun fiveBinsMapScoreToRedThroughGreen() {
        val expected = listOf(0 to 0, 19 to 0, 20 to 1, 39 to 1, 40 to 2, 59 to 2, 60 to 3, 79 to 3, 80 to 4, 100 to 4)
        for ((score, bin) in expected) {
            assertEquals("score $score", bin, SignalScale.binOfScore(score))
        }
        assertEquals(5, SignalScale.binArgb.size)
        assertEquals(5, SignalScale.binArgb.distinct().size)

        // Weakest bin must read red, strongest must read green.
        val weakest = SignalScale.binArgb[0]
        val strongest = SignalScale.binArgb[4]
        val weakRed = (weakest shr 16) and 0xFF
        val weakGreen = (weakest shr 8) and 0xFF
        val strongRed = (strongest shr 16) and 0xFF
        val strongGreen = (strongest shr 8) and 0xFF
        assertTrue("weak bin should be red-dominant", weakRed > weakGreen)
        assertTrue("strong bin should be green-dominant", strongGreen > strongRed)

        assertEquals(SignalScale.binArgb[4], SignalScale.colorOfScore(100))
        assertEquals(SignalScale.binArgb[0], SignalScale.colorOfScore(0))
    }

    @Test
    fun dbmMapsToBinsAndDistancesAreMonotonic() {
        assertEquals(4, SignalScale.binOfScore(Geo.rssiToScore(-40)))
        assertEquals(0, SignalScale.binOfScore(Geo.rssiToScore(-100)))
        assertTrue(Geo.aircraftScore(2.0) > Geo.aircraftScore(80.0))
        assertTrue(Geo.aircraftScore(80.0) > Geo.aircraftScore(300.0))
        assertEquals(100, Geo.satelliteScore(90.0))
        assertEquals(0, Geo.satelliteScore(0.0))
    }

    @Test
    fun peakSignalWinsTheFix() {
        val weak = dev.skyward.rf.core.RfObservation(
            kind = dev.skyward.rf.core.RfKind.WIFI,
            id = "aa", name = "weak", rssi = -88,
        )
        val strong = dev.skyward.rf.core.RfObservation(
            kind = dev.skyward.rf.core.RfKind.BLE,
            id = "bb", name = "strong", rssi = -45,
        )
        assertEquals(Geo.rssiToScore(-45), SignalScale.peakScore(listOf(weak, strong)))
        assertEquals(0, SignalScale.peakScore(emptyList()))
    }

    // ---------------- exports ----------------

    @Test
    fun exportsCarryScoreAndBinIntoGeoJsonAndCsv() {
        val root = File(System.getProperty("java.io.tmpdir"), "skyward-test-" + System.nanoTime())
        val engine = WardriveEngine(
            store = FileStore(File(root, "wardrives")),
            logged = LoggedRegistry(FileStore(File(root, "logged"))),
        )
        val points = listOf(
            WardrivePoint(lat = 1.0, lon = 2.0, t = 1_000L, score = 90, avgScore = 50, wifiCount = 3, bleCount = 2, bestWifi = "Home", bestWifiRssi = -40),
            WardrivePoint(lat = 1.001, lon = 2.001, t = 2_000L, score = 10, avgScore = 12, wifiCount = 1, bleCount = 0, bestWifi = "Far", bestWifiRssi = -91),
        )
        val session = WardriveSession(
            id = "t1",
            name = "Test drive",
            startedAt = 1_000L,
            endedAt = 2_000L,
            points = points,
            distanceM = 1_234.0,
            wifiAps = 4,
            bleDevices = 2,
            bestScore = 90,
            binCounts = listOf(1, 0, 0, 0, 1),
        )

        val geojson = engine.exportGeoJson(session)
        assertTrue(geojson.startsWith("{\"type\":\"FeatureCollection\""))
        assertTrue(geojson.contains("\"score\":90"))
        assertTrue(geojson.contains("\"bin\":4"))
        assertTrue(geojson.contains("\"bin\":0"))
        assertTrue(geojson.contains("\"type\":\"LineString\""))

        val csv = engine.exportCsv(session)
        val lines = csv.trim().lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("lat,lon,time,score,bin,bin_label"))
        assertTrue("row: ${lines[1]}", lines[1].contains("90,4,\"Very strong\""))
    }
}
