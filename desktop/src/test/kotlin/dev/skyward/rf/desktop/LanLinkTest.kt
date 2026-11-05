package dev.skyward.rf.desktop

import dev.skyward.rf.desktop.link.LanBridge
import dev.skyward.rf.desktop.link.LinkBridge
import dev.skyward.rf.desktop.link.LinkEvent
import dev.skyward.rf.peer.PeerFrames
import dev.skyward.rf.peer.PeerProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The network carrier, driven by a real socket.
 *
 * This is the test the Python bridge never had: it opens a connection to the production
 * [LanBridge], sends the exact bytes a phone sends - `PeerFrames` chunks, one base64 line each -
 * and proves the host side sees a device, a link and a whole message. No hardware, no radio, no
 * helper process.
 */
class LanLinkTest {

    @Test
    fun `a phone that dials in is linked and its message reassembles`() {
        val bridge = LanBridge(port = 47910, discoveryPort = 47911)
        val received = CopyOnWriteArrayList<LinkEvent>()
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { bridge.events.collect { received.add(it) } }

        assertTrue("the desk should listen", bridge.start())

        val message = PeerProtocol.Message(
            kind = PeerProtocol.KIND_SKY,
            id = "phone",
            name = "test phone",
            lat = 51.5074,
            lon = -0.1278,
            sats = 9,
            at = System.currentTimeMillis(),
            wifiTotal = 12,
            bleTotal = 3,
            wifi = listOf(PeerProtocol.Ap("aa:bb:cc:dd:ee:ff", "home", -54, 6)),
            ble = listOf(PeerProtocol.Device("11:22:33:44:55:66", "band", -71)),
        )
        val payload = PeerProtocol.encode(message).toByteArray(Charsets.UTF_8)
        val frames = PeerFrames.split(payload, 240, 7)

        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", 47910), 2_000)
            val out = socket.getOutputStream()
            for (frame in frames) {
                out.write((Base64.getEncoder().encodeToString(frame) + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
            val deadline = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < deadline &&
                received.count { it is LinkEvent.Notified } < frames.size
            ) {
                Thread.sleep(20)
            }
        }

        val advert = received.filterIsInstance<LinkEvent.Advert>().firstOrNull()
        assertNotNull("a dial-in should be offered as a device", advert)
        assertTrue(
            "the peer marker the host keys on must be present",
            advert!!.serviceData.containsKey(LinkBridge.PEER_SERVICE.toString()),
        )
        assertTrue(
            "the link must be reported as connected with a usable MTU",
            received.filterIsInstance<LinkEvent.Connected>().any { it.mtu > 23 },
        )

        val assembler = PeerFrames.Assembler()
        val body = received.filterIsInstance<LinkEvent.Notified>()
            .firstNotNullOfOrNull { assembler.add(it.data) }
        assertNotNull("the frames must reassemble into one message", body)

        val decoded = PeerProtocol.decode(String(body!!, Charsets.UTF_8))
        assertNotNull("the message must decode", decoded)
        assertEquals("test phone", decoded!!.name)
        assertEquals(51.5074, decoded.lat!!, 0.0001)
        assertEquals("home", decoded.wifi.first().name)

        bridge.stop()
        scope.cancel()
    }
}
