package dev.skyward.rf.desktop

import dev.skyward.rf.desktop.ble.BleEvent
import dev.skyward.rf.desktop.ble.BleEventCodec
import dev.skyward.rf.desktop.host.DeviceHost
import dev.skyward.rf.desktop.host.HostCodec
import dev.skyward.rf.scan.Esp32Codec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The desktop host's own decoding, and the two things that must match somebody else's
 * implementation: the bridge's wire format and Google's PKCE transform.
 */
class HostCodecTest {

    /**
     * The payload exactly as `bleak` reports it: after the company id, which the scanner puts in
     * the manufacturer map's key instead. That is the shape the host has to reconstruct in front
     * of [Esp32Codec.decode].
     */
    private fun board(fix: Boolean, lat: Double, lon: Double): ByteArray = Esp32Codec.encode(
        Esp32Codec.Advert(
            fix = fix,
            lat = if (fix) lat else null,
            lon = if (fix) lon else null,
            altM = 32,
            sats = 9,
            hdop = 0.8,
            batteryPct = 82,
            wifiCount = 41,
            bleCount = 17,
            moving = true,
        ),
    ).let { it.copyOfRange(2, it.size) }

    private fun advert(
        manufacturer: Map<Int, ByteArray> = emptyMap(),
        serviceData: Map<String, ByteArray> = emptyMap(),
    ) = BleEvent.Advert(
        address = "AA:BB:CC:DD:EE:FF",
        name = "test",
        rssi = -61,
        serviceUuids = emptyList(),
        manufacturer = manufacturer,
        serviceData = serviceData,
        seenAt = 1_000L,
    )

    @Test
    fun `a board advert decodes with the shared codec`() {
        val decoded = HostCodec.boardAdvert(
            advert(manufacturer = mapOf(Esp32Codec.COMPANY_ID to board(true, 51.5, -0.12))),
        )
        assertNotNull(decoded)
        assertEquals(true, decoded!!.fix)
        assertEquals(51.5, decoded.lat!!, 1e-7)
        assertEquals(-0.12, decoded.lon!!, 1e-7)
        assertEquals(9, decoded.sats)
        assertEquals(41, decoded.wifiCount)
        assertEquals(17, decoded.bleCount)
        assertEquals(82, decoded.batteryPct)
    }

    @Test
    fun `a board with no fix is not placed at null island`() {
        val decoded = HostCodec.boardAdvert(
            advert(manufacturer = mapOf(Esp32Codec.COMPANY_ID to board(false, 0.0, 0.0))),
        )
        assertNotNull(decoded)
        assertEquals(false, decoded!!.fix)
        assertNull(decoded.lat)
        assertNull(decoded.lon)
    }

    @Test
    fun `somebody else's manufacturer data is not a board`() {
        val decoded = HostCodec.boardAdvert(advert(manufacturer = mapOf(0x004C to byteArrayOf(1, 2, 3))))
        assertNull(decoded)
    }

    @Test
    fun `a phone is recognised by the peer service data`() {
        val id = HostCodec.peerId(
            advert(serviceData = mapOf(DeviceHost.PEER_SERVICE.toString() to "a1b2c3".toByteArray())),
        )
        assertEquals("a1b2c3", id)
    }

    @Test
    fun `blank peer service data is not a phone`() {
        val id = HostCodec.peerId(
            advert(serviceData = mapOf(DeviceHost.PEER_SERVICE.toString() to "   ".toByteArray())),
        )
        assertNull(id)
    }

    @Test
    fun `the bridge line format round-trips an advertisement`() {
        val payload = board(true, 40.7128, -74.006)
        val line = """
            {"ev":"advert","address":"AA:BB","name":"roof","rssi":-70,
             "service_uuids":["7A1F5E10-3C6B-4A2E-9F1D-5B8C2D4E6F70"],
             "manufacturer":{"65535":"${Base64.getEncoder().encodeToString(payload)}"},
             "service_data":{}}
        """.trimIndent()
        val event = BleEventCodec.decode(line)
        assertTrue(event is BleEvent.Advert)
        val advert = event as BleEvent.Advert
        assertEquals("AA:BB", advert.address)
        assertEquals(-70, advert.rssi)
        assertEquals(listOf("7a1f5e10-3c6b-4a2e-9f1d-5b8c2d4e6f70"), advert.serviceUuids)
        val decoded = HostCodec.boardAdvert(advert)
        assertNotNull(decoded)
        assertEquals(40.7128, decoded!!.lat!!, 1e-7)
    }

    @Test
    fun `a bridge error is reported rather than swallowed`() {
        val event = BleEventCodec.decode("""{"ev":"error","message":"no adapter"}""")
        assertTrue(event is BleEvent.Failed)
        assertEquals("no adapter", (event as BleEvent.Failed).message)
    }

    @Test
    fun `a broken line is not an event`() {
        assertNull(BleEventCodec.decode("BLE is on"))
        assertNull(BleEventCodec.decode("{"))
    }
}
