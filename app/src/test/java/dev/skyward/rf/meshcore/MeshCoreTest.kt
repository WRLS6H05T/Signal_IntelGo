package dev.skyward.rf.meshcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MeshCore adverts as an observer bridge uplinks them: one hex string per packet.
 *
 * The packets here are built byte by byte from the documented layout rather than pasted from a
 * capture, so each test can say exactly which field it is about: role nibble, location flag,
 * transport codes, hash-width path.
 */
class MeshCoreTest {

    /** Minimal little-endian packet writer, so a test can state the bytes it means. */
    private class Packet {
        private val bytes = java.io.ByteArrayOutputStream()
        fun u8(value: Int) = apply { bytes.write(value and 0xFF) }
        fun i32(value: Int) = apply { repeat(4) { k -> bytes.write((value ushr (8 * k)) and 0xFF) } }
        fun fill(count: Int, value: Int = 0) = apply { repeat(count) { bytes.write(value) } }
        fun text(value: String) = apply { bytes.write(value.toByteArray()) }
        fun done(): ByteArray = bytes.toByteArray()
    }

    private fun advert(
        flags: Int,
        lat: Double? = null,
        lon: Double? = null,
        name: String = "",
        hops: Int = 0,
        hashSize: Int = 1,
        transport: Boolean = false,
        payloadType: Int = MeshCore.PAYLOAD_ADVERT,
        withAppData: Boolean = true,
    ): ByteArray {
        // Version 0 in bits 6-7, payload type in bits 2-5, route type in bits 0-1: 0 is a
        // transport flood, 1 is a plain flood.
        val p = Packet()
            .u8((payloadType shl 2) or (if (transport) 0x00 else 0x01))
        // Transport-coded routes carry two 16-bit region codes before the path.
        if (transport) p.fill(4)
        p.u8(((hashSize - 1) shl 6) or hops)
        p.fill(hops * hashSize, 0x5A)
        // Public key: 32 deterministic bytes, so the hex form can be asserted exactly.
        repeat(32) { p.u8(it + 1) }
        p.i32(1_700_000_000)
        p.fill(64, 0x11) // signature, skipped
        if (withAppData) {
            p.u8(flags)
            if (lat != null && lon != null) {
                p.i32((lat * 1_000_000).toInt())
                p.i32((lon * 1_000_000).toInt())
            }
            if (flags and MeshCore.FLAG_FEATURE_1 != 0) p.fill(2)
            if (flags and MeshCore.FLAG_FEATURE_2 != 0) p.fill(2)
            if (flags and MeshCore.FLAG_HAS_NAME != 0) p.text(name)
        }
        return p.done()
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { String.format("%02x", it.toInt() and 0xFF) }

    @Test
    fun `a repeater advert is a repeater with its advertised position`() {
        val packet = advert(
            flags = 0x02 or MeshCore.FLAG_HAS_LOCATION or MeshCore.FLAG_HAS_NAME,
            lat = 51.5074,
            lon = -0.1278,
            name = "RPT-North",
        )
        val node = MeshCore.decodeHex(hex(packet))
        assertNotNull(node)
        node!!
        assertEquals(MeshCore.Role.REPEATER, node.role)
        assertEquals("RPT-North", node.name)
        assertEquals(51.5074, node.lat!!, 0.000001)
        assertEquals(-0.1278, node.lon!!, 0.000001)
        assertEquals("0102030405060708090A0B0C0D0E0F10" + "1112131415161718191A1B1C1D1E1F20", node.pubkey)
        assertEquals(1_700_000_000_000L, node.at)
    }

    @Test
    fun `the low nibble decides the role`() {
        fun roleOf(flags: Int) = MeshCore.decode(advert(flags))!!.role
        assertEquals(MeshCore.Role.CHAT, roleOf(0x01))
        assertEquals(MeshCore.Role.REPEATER, roleOf(0x02))
        assertEquals(MeshCore.Role.ROOM, roleOf(0x03))
        assertEquals(MeshCore.Role.SENSOR, roleOf(0x04))
        // A node kind this build does not know is unknown, not a repeater.
        assertEquals(MeshCore.Role.UNKNOWN, roleOf(0x09))
    }

    @Test
    fun `a node that shares no location has none`() {
        val node = MeshCore.decode(advert(0x02 or MeshCore.FLAG_HAS_NAME, name = "pocket"))!!
        assertNull(node.lat)
        assertNull(node.lon)
        assertEquals("pocket", node.name)
    }

    @Test
    fun `a zero position is no position, not the Gulf of Guinea`() {
        val node = MeshCore.decode(
            advert(0x02 or MeshCore.FLAG_HAS_LOCATION, lat = 0.0, lon = 0.0),
        )!!
        assertNull(node.lat)
        assertNull(node.lon)
    }

    @Test
    fun `only adverts are decoded`() {
        // 0x02 is a plain text message: the mesh's private traffic is not this app's business.
        assertNull(MeshCore.decode(advert(0x01, payloadType = 0x02, withAppData = false)))
        assertNull(MeshCore.decode(advert(0x01, payloadType = 0x00, withAppData = false)))
    }

    @Test
    fun `transport codes and wide path hashes are stepped over`() {
        val packet = advert(
            flags = 0x02 or MeshCore.FLAG_HAS_LOCATION or MeshCore.FLAG_HAS_NAME,
            lat = 48.8566,
            lon = 2.3522,
            name = "REL-2",
            hops = 2,
            hashSize = 2,
            transport = true,
        )
        val node = MeshCore.decode(packet)!!
        assertEquals(MeshCore.Role.REPEATER, node.role)
        assertEquals(2, node.hops)
        assertEquals("REL-2", node.name)
        assertEquals(48.8566, node.lat!!, 0.000001)
    }

    @Test
    fun `reserved feature fields do not eat the name`() {
        val packet = advert(
            flags = 0x04 or MeshCore.FLAG_HAS_LOCATION or MeshCore.FLAG_HAS_NAME or
                MeshCore.FLAG_FEATURE_1 or MeshCore.FLAG_FEATURE_2,
            lat = -33.8688,
            lon = 151.2093,
            name = "SNSR-3",
        )
        val node = MeshCore.decode(packet)!!
        assertEquals(MeshCore.Role.SENSOR, node.role)
        assertEquals("SNSR-3", node.name)
    }

    @Test
    fun `an advert with no appdata still names its key`() {
        val node = MeshCore.decode(advert(0x00, withAppData = false))!!
        assertEquals(MeshCore.Role.UNKNOWN, node.role)
        assertEquals("", node.name)
        assertEquals(64, node.pubkey.length)
    }

    @Test
    fun `a truncated packet is rejected rather than half-read`() {
        val full = advert(0x02 or MeshCore.FLAG_HAS_LOCATION, lat = 1.0, lon = 2.0)
        // Cut inside the signature: there is no advert to report yet.
        assertNull(MeshCore.decode(full.copyOf(100)))
        assertNull(MeshCore.decode(ByteArray(0)))
        assertNull(MeshCore.decode(byteArrayOf(0x11)))
    }

    @Test
    fun `hex is read the way a broker publishes it`() {
        val packet = advert(0x02 or MeshCore.FLAG_HAS_NAME, name = "RPT")
        val spaced = hex(packet).uppercase().chunked(2).joinToString(" ")
        val node = MeshCore.decodeHex(spaced)!!
        assertEquals(MeshCore.Role.REPEATER, node.role)
        assertEquals("RPT", node.name)

        assertNull(MeshCore.decodeHex(""))
        assertNull(MeshCore.decodeHex("abc"))
        assertNull(MeshCore.decodeHex("zz"))
    }

    @Test
    fun `a repeater heard through two observers is one node`() {
        // Same key, same advert seen twice: the decoder must not invent an identity per packet.
        val packet = advert(0x02, name = "RPT")
        val a = MeshCore.decode(packet)!!
        val b = MeshCore.decode(packet)!!
        assertEquals(a.pubkey, b.pubkey)
        assertTrue(a.pubkey.isNotBlank())
    }
}
