package dev.skyward.rf.sky

import dev.skyward.rf.core.LatLon
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The public KiwiSDR directory as this layer reads it.
 *
 * The question every test here asks is the one the map has to answer: is this entry a receiver a
 * person could actually connect to and hear something on, and does the card only offer bands that
 * receiver can reach?
 */
class KiwiSourceTest {

    private val source = KiwiSource(OkHttpClient())

    /** A fix in London, so the receivers below have a real distance to be ordered by. */
    private val here = LatLon(51.5, -0.12)

    private fun receiver(
        id: String,
        name: String = "Receiver $id",
        url: String = "http://host$id:8073",
        lat: Double = 51.5,
        lon: Double = -0.12,
        bands: String = "[0,30000000]",
        users: Int = 1,
        usersMax: Int = 4,
        offline: Boolean = false,
        snr: Int = 30,
        antenna: String = "Dipole",
    ): String = """
        {"id":"$id","name":"$name","url":"$url","gps":[$lat,$lon],"bands":$bands,
         "users":$users,"users_max":$usersMax,"offline":$offline,"snr_hf":$snr,
         "antenna":"$antenna","sdr_hw":"KiwiSDR 2 v1.902","grid":"IO90"}
    """.trimIndent()

    private fun list(vararg rows: String): String =
        """{"schema":1,"receiver_count":${rows.size},"receivers":[${rows.joinToString(",")}]}"""

    @Test
    fun `a listed receiver is kept with its address, coverage and antenna`() {
        val result = source.parse(list(receiver("a", name = "80m Dipole")), here)
        assertEquals(1, result.kiwis.size)
        val k = result.kiwis.first()
        assertEquals("80m Dipole", k.name)
        assertEquals("http://hosta:8073", k.url)
        assertEquals(0L, k.loHz)
        assertEquals(30_000_000L, k.hiHz)
        assertEquals("Dipole", k.antenna)
        assertEquals("KiwiSDR 2 v1.902", k.hardware)
        assertEquals(1, k.users)
        assertEquals(4, k.usersMax)
        assertEquals(30, k.snr)
        assertEquals(true, result.status.ok)
    }

    @Test
    fun `an offline receiver is not plotted`() {
        val result = source.parse(list(receiver("a", offline = true), receiver("b")), here)
        assertEquals(1, result.kiwis.size)
        assertEquals("Receiver b", result.kiwis.first().name)
    }

    @Test
    fun `a receiver with no position is not pinned anywhere`() {
        val noFix = """{"id":"x","name":"Nowhere","url":"http://n:8073","offline":false}"""
        val result = source.parse(list(noFix, receiver("b")), here)
        assertEquals(1, result.kiwis.size)
        assertEquals("Receiver b", result.kiwis.first().name)
    }

    @Test
    fun `an entry with no openable address is dropped`() {
        val result = source.parse(list(receiver("a", url = "ftp://host:8073")), here)
        assertTrue(result.kiwis.isEmpty())
    }

    @Test
    fun `the nearest receivers come first and the list is bounded`() {
        val rows = (0 until KiwiSource.MAX_KIWI + 30).map { i ->
            // A tenth of a degree further south each time, so distance from the fix grows with
            // the index and the ordering (and the cut at the cap) is unambiguous. A tenth, not
            // a half: a cap large enough to walk past the south pole would drop the rows that
            // have no latitude, and the test would be measuring that instead.
            receiver("r$i", name = "R$i", url = "http://r$i:8073", lat = 51.5 - i * 0.1)
        }.toTypedArray()
        val result = source.parse(list(*rows), here)
        assertEquals(KiwiSource.MAX_KIWI, result.kiwis.size)
        val distances = result.kiwis.map { it.distanceKm }
        assertEquals(distances.sorted(), distances)
        assertEquals("R0", result.kiwis.first().name)
    }

    @Test
    fun `without a fix nothing claims to be nearest`() {
        val result = source.parse(
            list(receiver("a", name = "Zeta"), receiver("b", name = "Alpha")),
            observer = null,
        )
        assertEquals(listOf("Alpha", "Zeta"), result.kiwis.map { it.name })
        assertTrue(result.kiwis.all { it.distanceKm == 0.0 })
    }

    @Test
    fun `the tuned link is the operator's page with the frequency and mode`() {
        assertEquals(
            "http://host:8073/?f=7100lsb",
            KiwiSource.tuneUrl("http://host:8073", 7_100, "LSB"),
        )
        // A trailing slash in the directory must not become a double slash.
        assertEquals(
            "http://host:8073/?f=14200usb",
            KiwiSource.tuneUrl("http://host:8073/", 14_200, "usb"),
        )
    }

    @Test
    fun `a band outside the receiver's coverage is never offered`() {
        val short = KiwiSource.presetsFor(0L, 10_000_000L)
        assertTrue(short.any { it.label == "40m" })
        assertFalse(short.any { it.label == "20m" })
        assertFalse(short.any { it.label == "10m" })

        val full = KiwiSource.presetsFor(0L, 30_000_000L)
        assertTrue(full.any { it.label == "20m" })
        assertTrue(full.any { it.label == "10m" })
    }

    @Test
    fun `a receiver with every channel taken reads as full`() {
        val result = source.parse(list(receiver("a", users = 4, usersMax = 4)), here)
        assertTrue(result.kiwis.first().full)
        val free = source.parse(list(receiver("b", users = 1, usersMax = 4)), here)
        assertFalse(free.kiwis.first().full)
    }

    @Test
    fun `an answer that is not a receiver list is an error, not an empty sky`() {
        val result = source.parse("<html>rate limited</html>", here)
        assertTrue(result.kiwis.isEmpty())
        assertEquals(false, result.status.ok)
        assertTrue(result.status.detail.isNotBlank())
    }

    @Test
    fun `coverage reads as a band label`() {
        val result = source.parse(list(receiver("a", bands = "[500000,1700000]")), here)
        assertEquals("0.5\u20131.7 MHz", result.kiwis.first().coverage)
    }
}
