package dev.skyward.rf.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerProtocolTest {

    private val sample = PeerProtocol.Message(
        kind = PeerProtocol.KIND_SKY,
        id = "ab12cd34",
        name = "Pixel",
        diagIn = 6.2f,
        lat = 34.36,
        lon = -86.29,
        sats = 9,
        at = 1_700_000_000_000L,
        wifi = listOf(PeerProtocol.Ap("AA:BB:CC:DD:EE:01", "HomeNet", -51, 6)),
        ble = listOf(PeerProtocol.Device("AA:BB:CC:DD:EE:02", "Buds", -70)),
    )

    @Test
    fun `a message survives the wire`() {
        val back = PeerProtocol.decode(PeerProtocol.encode(sample))
        assertNotNull(back)
        assertEquals("ab12cd34", back!!.id)
        assertEquals(34.36, back.lat!!, 0.0001)
        assertEquals(-86.29, back.lon!!, 0.0001)
        assertEquals(9, back.sats)
        assertEquals(1, back.wifi.size)
        assertEquals("HomeNet", back.wifi[0].name)
        assertEquals(-51, back.wifi[0].rssi)
        assertEquals(6, back.wifi[0].ch)
        assertEquals("Buds", back.ble[0].name)
    }

    @Test
    fun `a message without a fix is still a message`() {
        val back = PeerProtocol.decode(PeerProtocol.encode(sample.copy(lat = null, lon = null)))
        assertNotNull(back)
        assertNull(back!!.lat)
        assertNull(back.lon)
    }

    @Test
    fun `another protocol version is refused rather than misread`() {
        val json = PeerProtocol.encode(sample).replace("\"v\":2", "\"v\":1")
        assertNull(PeerProtocol.decode(json))
    }

    @Test
    fun `garbage and empty lines are dropped`() {
        assertNull(PeerProtocol.decode(""))
        assertNull(PeerProtocol.decode("   "))
        assertNull(PeerProtocol.decode("{not json"))
        assertNull(PeerProtocol.decode("{\"v\":1,\"kind\":\"\"}"))
    }

    @Test
    fun `the bigger screen takes the globe and the smaller one the drive view`() {
        assertEquals(PeerProtocol.DISPLAY, PeerProtocol.roleFor(6.7f, 5.4f))
        assertEquals(PeerProtocol.SENSOR, PeerProtocol.roleFor(5.4f, 6.7f))
    }

    @Test
    fun `similar screens and unknown sizes get no role`() {
        assertEquals("", PeerProtocol.roleFor(6.1f, 6.2f))
        assertEquals("", PeerProtocol.roleFor(0f, 6.2f))
        assertEquals("", PeerProtocol.roleFor(6.2f, 0f))
    }

    @Test
    fun `a drive started on one phone is announced to the other`() {
        val back = PeerProtocol.decode(
            PeerProtocol.encode(sample.copy(recording = true, recordName = "Drive A")),
        )
        assertNotNull(back)
        assertTrue(back!!.recording)
        assertEquals("Drive A", back.recordName)
    }

    @Test
    fun `board intel travels with the sweep`() {
        val back = PeerProtocol.decode(
            PeerProtocol.encode(
                sample.copy(
                    boardWifi = listOf(PeerProtocol.Ap("AA:BB:CC:DD:EE:03", "BoardNet", -44, 36)),
                    boardBle = listOf(PeerProtocol.Device("AA:BB:CC:DD:EE:04", "Tag", -61)),
                    boardWifiTotal = 4,
                    boardBleTotal = 2,
                ),
            ),
        )
        assertNotNull(back)
        assertEquals("BoardNet", back!!.boardWifi[0].name)
        assertEquals(36, back.boardWifi[0].ch)
        assertEquals(4, back.boardWifiTotal)
        assertEquals("Tag", back.boardBle[0].name)
        assertEquals(2, back.boardBleTotal)
    }

    @Test
    fun `a watcher command is forwarded to the other phone`() {
        val back = PeerProtocol.decode(
            PeerProtocol.encode(
                sample.copy(kind = PeerProtocol.KIND_WATCHER, voice = "OPEN_MENU"),
            ),
        )
        assertNotNull(back)
        assertEquals(PeerProtocol.KIND_WATCHER, back!!.kind)
        assertEquals("OPEN_MENU", back.voice)
    }

    @Test
    fun `logged entities travel so the other phone holds the same intel`() {
        val back = PeerProtocol.decode(
            PeerProtocol.encode(
                sample.copy(
                    entities = listOf(
                        PeerProtocol.Entity("SATELLITE", "25544", "ISS (ZARYA)", 51.6, -0.12),
                        PeerProtocol.Entity("AIRCRAFT", "3c6444", "DLH441", 48.1, 11.5),
                    ),
                    entityTotal = 240,
                ),
            ),
        )
        assertNotNull(back)
        assertEquals(2, back!!.entities.size)
        assertEquals("SATELLITE:25544", back.entities[0].key)
        assertEquals(51.6, back.entities[0].lat, 0.0001)
        assertEquals("DLH441", back.entities[1].name)
        assertEquals(240, back.entityTotal)
    }

    @Test
    fun `the link carries no host role and no scan assignment`() {
        val json = PeerProtocol.encode(
            sample.copy(entities = listOf(PeerProtocol.Entity("MESH", "!ab12", "Roof", 51.0, -0.1))),
        )
        assertFalse("no host blob" , json.contains("hostSettings"))
        assertFalse("no hand-picked role", json.contains("\"mode\""))
        assertFalse("no assigned sweep", json.contains("\"scan\""))
    }

    @Test
    fun `labels name what each screen shows`() {
        assertEquals("globe", PeerProtocol.roleLabel(PeerProtocol.DISPLAY))
        assertEquals("wardrive", PeerProtocol.roleLabel(PeerProtocol.SENSOR))
        assertEquals("shared", PeerProtocol.roleLabel(""))
    }
}
