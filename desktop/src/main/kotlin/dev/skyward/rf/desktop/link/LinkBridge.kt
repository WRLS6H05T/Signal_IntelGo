package dev.skyward.rf.desktop.link

import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID

/**
 * One thing a link carried, independent of how it was carried.
 *
 * Deliberately transport-shaped rather than app-shaped: this is what arrived, not what the host
 * decided it means. [dev.skyward.rf.desktop.host.DeviceHost] does the decoding, so a new carrier
 * - a socket, a serial port - needs no change to the host at all.
 */
sealed interface LinkEvent {

    data class Ready(val detail: String) : LinkEvent

    data class Failed(val message: String) : LinkEvent

    data class Advert(
        val address: String,
        val name: String,
        val rssi: Int,
        val serviceUuids: List<String>,
        /** Company id -> raw payload, as the advertisement carried it. */
        val manufacturer: Map<Int, ByteArray>,
        /** 128-bit service UUID (lowercase) -> raw payload. */
        val serviceData: Map<String, ByteArray>,
        val seenAt: Long,
    ) : LinkEvent

    data class Connected(val address: String, val mtu: Int) : LinkEvent

    data class Disconnected(val address: String) : LinkEvent

    data class Notified(val address: String, val uuid: String, val data: ByteArray) : LinkEvent

    data class Note(val message: String) : LinkEvent
}

/**
 * A carrier for the two-device link.
 *
 * There were two reasons to make this an interface. The first is the obvious one: the desktop
 * should be able to reach a phone over a cable, over Wi-Fi, or over anything added later without
 * the host learning a new vocabulary. The second is less obvious and more important - the link
 * used to be a Python process driving Windows Bluetooth, and a computer with no Python, or no
 * Bluetooth adapter, or a phone on the same desk on a cable, had no way to connect at all.
 *
 * Every carrier moves the same bytes: [dev.skyward.rf.peer.PeerFrames] chunks, one base64 line
 * each. Over Bluetooth that framing is a workaround for a 20-byte GATT write; over a socket it is
 * simply a convenient record separator, and keeping it identical is what lets one assembler on
 * each end serve every carrier.
 */
interface LinkBridge {

    val events: SharedFlow<LinkEvent>

    fun start(): Boolean

    fun stop()

    /** Begin (or stop) looking for devices. Carriers that accept connections ignore it. */
    fun scan(on: Boolean)

    fun connect(address: String): Boolean

    fun write(address: String, characteristic: String, data: ByteArray): Boolean

    /** What this carrier is doing, for the window. */
    val detail: String

    companion object {
        /** The phone's peer service, used as the marker for "this is one of ours". */
        val PEER_SERVICE: UUID = UUID.fromString("7a1f5e10-3c6b-4a2e-9f1d-5b8c2d4e6f70")

        /** The link's TCP port, and the UDP port that answers "where is the desk?". */
        const val PORT = 47800
        const val DISCOVERY_PORT = 47801

        /** The discovery probe the phone already defines; answered with [HERE]. */
        const val PROBE = "SKYWARD?"
        const val HERE = "SKYWARD!"
    }
}
