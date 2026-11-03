package dev.skyward.rf.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Esp32CodecTest {

    private fun advert(
        fix: Boolean = true,
        lat: Double? = 51.5074123,
        lon: Double? = -0.1278123,
        altM: Int? = 32,
        sats: Int = 9,
        hdop: Double? = 0.9,
        batteryPct: Int = 82,
        wifiCount: Int = 41,
        bleCount: Int = 17,
        moving: Boolean = true,
    ) = Esp32Codec.Advert(
        fix = fix,
        lat = lat,
        lon = lon,
        altM = altM,
        sats = sats,
        hdop = hdop,
        batteryPct = batteryPct,
        wifiCount = wifiCount,
        bleCount = bleCount,
        moving = moving,
    )

    @Test
    fun roundTripsAFullAdvertisement() {
        val out = Esp32Codec.decode(Esp32Codec.encode(advert()))!!
        assertTrue(out.fix)
        assertEquals(51.5074123, out.lat!!, 1e-7)
        assertEquals(-0.1278123, out.lon!!, 1e-7)
        assertEquals(32, out.altM!!)
        assertEquals(9, out.sats)
        assertEquals(0.9, out.hdop!!, 1e-6)
        assertEquals(82, out.batteryPct)
        assertEquals(41, out.wifiCount)
        assertEquals(17, out.bleCount)
        assertTrue(out.moving)
    }

    @Test
    fun keepsAltitudeBelowSeaLevel() {
        val out = Esp32Codec.decode(Esp32Codec.encode(advert(altM = -12)))!!
        assertEquals(-12, out.altM!!)
    }

    @Test
    fun noFixMeansNoPosition() {
        // A board that has not locked sends zeros; that must not become a dot at Null Island.
        val out = Esp32Codec.decode(Esp32Codec.encode(advert(fix = false, lat = 0.0, lon = 0.0, sats = 0)))!!
        assertFalse(out.fix)
        assertNull(out.lat)
        assertNull(out.lon)
        assertNull(out.altM)
    }

    @Test
    fun unknownBatteryAndHdopDecodeAsUnknown() {
        val encoded = Esp32Codec.encode(advert(batteryPct = -1, hdop = null))
        val out = Esp32Codec.decode(encoded)!!
        assertEquals(-1, out.batteryPct)
        assertNull(out.hdop)
    }

    @Test
    fun ignoresForeignAndTruncatedPayloads() {
        assertNull(Esp32Codec.decode(null))
        assertNull(Esp32Codec.decode(ByteArray(4)))
        val other = Esp32Codec.encode(advert()).copyOf()
        other[0] = 0x4C.toByte() // some other company id
        assertNull(Esp32Codec.decode(other))
    }

    @Test
    fun extractsManufacturerDataFromAScanRecord() {
        val payload = Esp32Codec.encode(advert())
        val name = byteArrayOf(5, 0x09, 'r'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 'f'.code.toByte())
        val ad = ByteArray(payload.size + 2)
        ad[0] = (payload.size + 1).toByte() // length covers type byte + payload
        ad[1] = 0xFF.toByte()
        System.arraycopy(payload, 0, ad, 2, payload.size)
        val record = name + ad

        val extracted = Esp32Codec.extract(record)!!
        assertEquals(payload.size, extracted.size)
        val decoded = Esp32Codec.decode(extracted)!!
        assertEquals(9, decoded.sats)
        assertEquals(41, decoded.wifiCount)
    }

    @Test
    fun decodesTheJsonAPublishingBoardSends() {
        val out = Esp32Codec.decodeJson(
            """{"id":"roof","lat":51.5,"lon":-0.12,"alt":32,"sats":9,"hdop":0.9,
               "batt":82,"wifi":41,"ble":17,"fix":true,"fw":"1.0.3"}""",
        )!!
        assertTrue(out.fix)
        assertEquals(51.5, out.lat!!, 1e-9)
        assertEquals(-0.12, out.lon!!, 1e-9)
        assertEquals(9, out.sats)
        assertEquals(41, out.wifiCount)
        assertEquals(82, out.batteryPct)
    }

    @Test
    fun jsonWithoutFixStillReportsTheSweep() {
        val out = Esp32Codec.decodeJson("""{"id":"boot","sats":0,"wifi":3,"ble":1}""")!!
        assertFalse(out.fix)
        assertNull(out.lat)
        assertEquals(3, out.wifiCount)
    }

    @Test
    fun junkJsonIsIgnored() {
        assertNull(Esp32Codec.decodeJson("not json"))
        assertNull(Esp32Codec.decodeJson(""))
    }
}
