package dev.skyward.rf.sky

import dev.skyward.rf.core.CameraKind
import dev.skyward.rf.core.LatLon
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera layer is read from OpenStreetMap, not heard from a radio.
 *
 * There is no vendor feed of camera positions, so the layer is only as good as what the map
 * says: these tests pin the parts that decide whether a marker is a Flock plate reader, a CCTV
 * camera, or nothing at all, and whether the card gets a feed it can actually open.
 */
class CameraSourceTest {

    private val source = CameraSource(OkHttpClient())
    private val here = LatLon(51.50000, -0.10000)

    private fun body(vararg elements: String) =
        """{"version":0.6,"elements":[${elements.joinToString(",")}]}"""

    private fun node(id: Long, lat: Double, lon: Double, tags: String) =
        """{"type":"node","id":$id,"lat":$lat,"lon":$lon,"tags":$tags}"""

    @Test
    fun `a plate reader mapped as ALPR is a Flock camera`() {
        val cameras = source.parse(
            body(
                node(
                    1,
                    here.lat,
                    here.lon,
                    """{"man_made":"surveillance","surveillance:type":"ALPR","manufacturer":"Flock Safety"}""",
                ),
            ),
            here,
        ).cameras

        assertEquals(1, cameras.size)
        assertEquals(CameraKind.FLOCK, cameras[0].kind)
        assertEquals("Flock Safety", cameras[0].manufacturer)
        assertEquals("node/1", cameras[0].id)
    }

    @Test
    fun `an ALPR mapped without the surveillance tag is still a plate reader`() {
        val cameras = source.parse(
            body(node(2, here.lat, here.lon, """{"surveillance:type":"alpr"}""")),
            here,
        ).cameras

        assertEquals(1, cameras.size)
        assertEquals(CameraKind.FLOCK, cameras[0].kind)
    }

    @Test
    fun `a plain surveillance camera is CCTV, with its telemetry`() {
        val cameras = source.parse(
            body(
                node(
                    3,
                    here.lat,
                    here.lon,
                    """{"man_made":"surveillance","camera:type":"dome","operator":"Town Council",""" +
                        """"camera:direction":"135","camera:mount":"pole","surveillance:zone":"traffic"}""",
                ),
            ),
            here,
        ).cameras

        assertEquals(1, cameras.size)
        val c = cameras[0]
        assertEquals(CameraKind.CCTV, c.kind)
        assertEquals("dome", c.cameraType)
        assertEquals("Town Council", c.operator)
        assertEquals(135.0, c.directionDeg ?: 0.0, 0.001)
        assertEquals("pole", c.mount)
        assertEquals("traffic", c.zone)
    }

    @Test
    fun `a guard post is not a camera`() {
        val cameras = source.parse(
            body(node(4, here.lat, here.lon, """{"man_made":"surveillance","surveillance:type":"guard"}""")),
            here,
        ).cameras

        assertTrue(cameras.isEmpty())
    }

    @Test
    fun `a camera mapped as a way is placed at its centre`() {
        val cameras = source.parse(
            body(
                """{"type":"way","id":8,"center":{"lat":51.501,"lon":-0.101},""" +
                    """"tags":{"man_made":"surveillance","camera:type":"fixed"}}""",
            ),
            here,
        ).cameras

        assertEquals(1, cameras.size)
        assertEquals("way/8", cameras[0].id)
        assertEquals(51.501, cameras[0].lat, 0.000001)
        assertEquals(-0.101, cameras[0].lon, 0.000001)
    }

    @Test
    fun `a camera with no coordinate is not placed at the phone`() {
        val cameras = source.parse(
            body("""{"type":"node","id":9,"tags":{"man_made":"surveillance"}}"""),
            here,
        ).cameras

        // Placing it here would put fixed infrastructure on whatever street the phone is on.
        assertTrue(cameras.isEmpty())
    }

    @Test
    fun `the camera's own feed is carried, and the specific tag wins`() {
        val cameras = source.parse(
            body(
                node(
                    5,
                    here.lat,
                    here.lon,
                    """{"man_made":"surveillance","contact:webcam":"https://cam.example/street.m3u8",""" +
                        """"website":"https://example.org/council"}""",
                ),
            ),
            here,
        ).cameras

        assertEquals("https://cam.example/street.m3u8", cameras[0].liveFeed)
        assertEquals("HLS stream", cameras[0].feedKind)
    }

    @Test
    fun `a feed that is not a web address is not offered`() {
        val cameras = source.parse(
            body(
                node(
                    6,
                    here.lat,
                    here.lon,
                    """{"man_made":"surveillance","webcam":"rtsp://cam.example/live"}""",
                ),
            ),
            here,
        ).cameras

        assertEquals("", cameras[0].liveFeed)
        assertEquals("", cameras[0].feedKind)
    }

    @Test
    fun `the nearest cameras come first and the list is bounded`() {
        val cameras = source.parse(
            body(
                node(10, 51.530, -0.100, """{"man_made":"surveillance"}"""),
                node(11, 51.501, -0.100, """{"man_made":"surveillance"}"""),
            ),
            here,
        ).cameras

        assertEquals("node/11", cameras[0].id)
        assertEquals("node/10", cameras[1].id)
        assertTrue(cameras[0].distanceKm < cameras[1].distanceKm)
        assertTrue(cameras.size <= CameraSource.MAX_CAMERAS)
    }

    @Test
    fun `an answer that is not a camera table is an error, not an empty street`() {
        val result = source.parse("<html>rate limited</html>", here)

        assertTrue(result.cameras.isEmpty())
        assertEquals(false, result.status.ok)
    }

    @Test
    fun `the query asks for surveillance and ALPR around the fix`() {
        val q = CameraSource.query(here)

        assertTrue(q.contains("""node(around:3000,51.50000,-0.10000)["man_made"="surveillance"]"""))
        assertTrue(q.contains("""way(around:3000,51.50000,-0.10000)["surveillance:type"~"ALPR",i]"""))
        assertTrue(q.contains("out center;"))
    }

    @Test
    fun `a camera with no name gets the name of its kind`() {
        val cameras = source.parse(
            body(
                node(12, here.lat, here.lon, """{"man_made":"surveillance","surveillance:type":"ALPR"}"""),
                node(13, here.lat, here.lon, """{"man_made":"surveillance"}"""),
            ),
            here,
        ).cameras

        assertEquals("Flock camera", cameras[0].name)
        assertEquals("CCTV camera", cameras[1].name)
    }

    @Test
    fun `a direction that is written as a compass word is not read as degrees`() {
        val cameras = source.parse(
            body(
                node(
                    14,
                    here.lat,
                    here.lon,
                    """{"man_made":"surveillance","camera:direction":"north"}""",
                ),
            ),
            here,
        ).cameras

        assertNull(cameras[0].directionDeg)
    }
}
