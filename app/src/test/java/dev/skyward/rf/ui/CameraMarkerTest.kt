package dev.skyward.rf.ui

import dev.skyward.rf.core.CameraKind
import dev.skyward.rf.core.CameraView
import dev.skyward.rf.core.Layer
import dev.skyward.rf.sky.AirspaceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapped camera layer on the map.
 *
 * Cameras are the one layer with an outside source: OpenStreetMap instead of a radio. What the
 * source found must reach the map as its own kind of marker, keep the camera's own feed so the
 * card can open it, and disappear when the layer is switched off - and a camera read from a map
 * must never be filtered out as a weak dot, because it has no signal to be weak.
 */
class CameraMarkerTest {

    private fun cam(
        id: String,
        kind: CameraKind,
        feed: String = "",
        feedKind: String = "",
    ) = CameraView(
        id = id,
        kind = kind,
        lat = 51.5,
        lon = -0.1,
        name = if (kind == CameraKind.FLOCK) "Flock camera" else "CCTV camera",
        manufacturer = if (kind == CameraKind.FLOCK) "Flock Safety" else "",
        cameraType = if (kind == CameraKind.CCTV) "dome" else "fixed",
        liveFeed = feed,
        feedKind = feedKind,
        distanceKm = 0.25,
    )

    private fun markers(
        cameras: List<CameraView>,
        showCameras: Boolean = true,
        minScore: Int = 0,
    ): List<MapMarker> = buildMarkers(
        sky = AirspaceEngine.SkyState(cameras = cameras),
        showSats = false,
        showAir = false,
        showDrones = false,
        showMesh = false,
        showCells = false,
        showEsp32 = false,
        showWatch = false,
        showCameras = showCameras,
        distanceUnit = "km",
        minScore = minScore,
    )

    @Test
    fun `a plate reader and a CCTV camera are both drawn, each as its own kind`() {
        val out = markers(
            listOf(
                cam("node/1", CameraKind.FLOCK),
                cam("node/2", CameraKind.CCTV),
            ),
        )

        assertEquals(2, out.size)
        assertTrue(out.all { it.layer == Layer.CAMERA })
        assertEquals(CameraKind.FLOCK, out.first { it.key.contains("node/1") }.camera)
        assertEquals(CameraKind.CCTV, out.first { it.key.contains("node/2") }.camera)
    }

    @Test
    fun `switching the camera layer off removes every camera`() {
        val out = markers(listOf(cam("node/1", CameraKind.FLOCK)), showCameras = false)

        assertTrue(out.isEmpty())
    }

    @Test
    fun `a mapped camera with a feed carries the address into its card`() {
        val out = markers(
            listOf(
                cam("node/1", CameraKind.CCTV, feed = "https://cam.example/street.m3u8", feedKind = "HLS stream"),
                cam("node/2", CameraKind.CCTV),
            ),
        )

        assertEquals("https://cam.example/street.m3u8", out.first { it.key.contains("node/1") }.feedUrl)
        assertNull(out.first { it.key.contains("node/2") }.feedUrl)
        assertTrue(
            out.first { it.key.contains("node/1") }.lines.contains("Live feed" to "HLS stream"),
        )
        assertTrue(
            out.first { it.key.contains("node/2") }.lines.contains("Live feed" to "none mapped"),
        )
    }

    @Test
    fun `a mapped camera is never dropped as a weak dot`() {
        // The weak-dot filter is about radio score. A camera has none, and a map that hid it
        // because the filter was on would be describing the source's absence as the camera's.
        val out = markers(
            listOf(
                cam("node/1", CameraKind.FLOCK),
                cam("node/2", CameraKind.CCTV),
            ),
            minScore = 20,
        )

        assertEquals(2, out.size)
    }

    @Test
    fun `a camera's telemetry is in its card`() {
        val out = markers(listOf(cam("node/2", CameraKind.CCTV)))

        val lines = out[0].lines.toMap()
        assertEquals("OpenStreetMap · ODbL", lines["Source"])
        assertEquals("dome", lines["Camera"])
        assertEquals("CCTV · mapped", out[0].subtitle)
        assertEquals("node/2", lines["OSM"])
    }
}
