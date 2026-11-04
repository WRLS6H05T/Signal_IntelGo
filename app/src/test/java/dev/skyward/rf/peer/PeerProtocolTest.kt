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
        val json = PeerProtocol.encode(sample).replace("\"v\":1", "\"v\":2")
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
    fun `host settings carry their revision and the shared dashboard`() {
        val hs = PeerProtocol.HostSettings(
            rev = 3,
            clockSeconds = false,
            distanceUnit = "mi",
            tempUnit = "f",
            showLiveTime = false,
        )
        val back = PeerProtocol.decode(
            PeerProtocol.encode(sample.copy(mode = PeerProtocol.MODE_HOST, hostSettings = hs)),
        )
        assertNotNull(back)
        assertEquals(3, back!!.hostSettings!!.rev)
        assertFalse(back.hostSettings!!.clockSeconds)
        assertFalse(back.hostSettings!!.showLiveTime)
        assertEquals("mi", back.hostSettings!!.distanceUnit)
        assertEquals("f", back.hostSettings!!.tempUnit)
    }

    @Test
    fun `a host blob is applied once per revision`() {
        assertTrue(PeerProtocol.shouldApplyHost(rev = 4, applied = 3))
        assertFalse("a repeated snapshot must not undo a local change", PeerProtocol.shouldApplyHost(4, 4))
        assertFalse(PeerProtocol.shouldApplyHost(3, 4))
    }

    @Test
    fun `labels name what each screen shows`() {
        assertEquals("globe", PeerProtocol.roleLabel(PeerProtocol.DISPLAY))
        assertEquals("wardrive", PeerProtocol.roleLabel(PeerProtocol.SENSOR))
        assertEquals("shared", PeerProtocol.roleLabel(""))
    }
}
