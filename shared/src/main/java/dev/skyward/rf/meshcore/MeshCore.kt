package dev.skyward.rf.meshcore

/**
 * MeshCore packets: the wire format WADAMESH, its companions and the MQTT observer bridges speak.
 *
 * A MeshCore observer on a roof uplinks every packet it hears as hex JSON
 * (`meshcore/{IATA}/{device}/packets`, field `raw`, or `.../raw`, field `data`). Most of those are
 * traffic this app has no business decoding — requests, acks, encrypted group text — but one
 * payload type is public and self-describing: the node advert. It carries the Ed25519 public key,
 * a timestamp, and an appdata block whose flag byte says what kind of node it is and whether it
 * shares a position.
 *
 * That is what makes repeaters mappable. A repeater is infrastructure: it is not carried in
 * someone's pocket, it advertises `is repeater`, and when its owner shares a location it is a
 * fixed point on the map rather than a radio heard from somewhere.
 *
 * Nothing here throws and nothing is trusted: a malformed or truncated packet returns null, and a
 * signature is skipped rather than verified, because verifying one needs the key distribution
 * this decoder deliberately does not have.
 *
 * Format: [packet_format.md](https://github.com/meshcore-dev/MeshCore/blob/main/docs/packet_format.md)
 * and [payloads.md](https://github.com/meshcore-dev/MeshCore/blob/main/docs/payloads.md).
 */
object MeshCore {

    /** Header payload type for a node advertisement (`header` bits 2-5). */
    const val PAYLOAD_ADVERT: Int = 0x04

    /** Appdata flags: the node kind is in the low nibble, the rest describe what is present. */
    const val FLAG_HAS_LOCATION: Int = 0x10
    const val FLAG_FEATURE_1: Int = 0x20
    const val FLAG_FEATURE_2: Int = 0x40
    const val FLAG_HAS_NAME: Int = 0x80

    /** What a node says it is. Repeaters are the infrastructure, everything else is a user. */
    enum class Role(val label: String) {
        CHAT("chat"),
        REPEATER("repeater"),
        ROOM("room server"),
        SENSOR("sensor"),
        UNKNOWN("node"),
    }

    data class Advert(
        /** Hex public key: the node's identity, and the first byte is the node hash. */
        val pubkey: String,
        val name: String,
        val role: Role,
        /** Advertised position, or null when the owner does not share one. */
        val lat: Double? = null,
        val lon: Double? = null,
        /** The advert's own timestamp in milliseconds; 0 when the node had no clock. */
        val at: Long = 0L,
        /** How many hops the packet had already travelled when the observer heard it. */
        val hops: Int = 0,
    )

    /** Decodes a hex publication; null when it is not a node advert. */
    fun decodeHex(hex: String): Advert? {
        val bytes = unhex(hex) ?: return null
        return decode(bytes)
    }

    fun decode(packet: ByteArray): Advert? {
        if (packet.size < 2) return null
        val header = packet[0].toInt() and 0xFF
        val route = header and 0x03
        val type = (header ushr 2) and 0x0F
        if (type != PAYLOAD_ADVERT) return null

        var i = 1
        // Transport-coded routes carry two 16-bit region codes before the path.
        if (route == 0x00 || route == 0x03) i += 4
        if (i >= packet.size) return null

        // path_length packs the hop count in bits 0-5 and the hash size minus one in bits 6-7.
        val pathLen = packet[i].toInt() and 0xFF
        val hops = pathLen and 0x3F
        val hashSize = ((pathLen ushr 6) and 0x03) + 1
        i += 1 + hops * hashSize

        // public key (32) + timestamp (4) + signature (64)
        if (i + 32 + 4 + 64 > packet.size) return null
        val pubkey = hexOf(packet, i, 32)
        i += 32
        val seconds = u32(packet, i).toLong()
        i += 4
        i += 64
        val at = if (seconds in 1_000_000_000L..4_000_000_000L) seconds * 1000L else 0L

        // An advert with no appdata is legal: it only announces that the node exists.
        if (i >= packet.size) return Advert(pubkey, "", Role.UNKNOWN, at = at, hops = hops)

        val flags = packet[i].toInt() and 0xFF
        i += 1
        var lat: Double? = null
        var lon: Double? = null
        if (flags and FLAG_HAS_LOCATION != 0) {
            if (i + 8 > packet.size) return null
            val rawLat = i32(packet, i) / 1_000_000.0
            val rawLon = i32(packet, i + 4) / 1_000_000.0
            i += 8
            // A zero pair means "not set", not a position in the Gulf of Guinea.
            if (rawLat in -90.0..90.0 && rawLon in -180.0..180.0 &&
                !(rawLat == 0.0 && rawLon == 0.0)
            ) {
                lat = rawLat
                lon = rawLon
            }
        }
        // Reserved features sit between the position and the name, and must be stepped over.
        if (flags and FLAG_FEATURE_1 != 0) i += 2
        if (flags and FLAG_FEATURE_2 != 0) i += 2

        val name = if (flags and FLAG_HAS_NAME != 0 && i < packet.size) {
            String(packet, i, packet.size - i, Charsets.UTF_8)
                .filter { it.code in 32..126 }
                .trim()
                .take(48)
        } else {
            ""
        }

        val role = when (flags and 0x0F) {
            0x01 -> Role.CHAT
            0x02 -> Role.REPEATER
            0x03 -> Role.ROOM
            0x04 -> Role.SENSOR
            else -> Role.UNKNOWN
        }
        return Advert(pubkey, name, role, lat, lon, at, hops)
    }

    /** Hex to bytes, ignoring whitespace and case; null on odd length or a stray character. */
    fun unhex(text: String): ByteArray? {
        val clean = text.filterNot { it.isWhitespace() || it == ':' }
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        val out = ByteArray(clean.length / 2)
        for (k in out.indices) {
            val hi = hexDigit(clean[k * 2]) ?: return null
            val lo = hexDigit(clean[k * 2 + 1]) ?: return null
            out[k] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexDigit(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }

    private fun hexOf(b: ByteArray, from: Int, len: Int): String {
        val sb = StringBuilder(len * 2)
        for (k in from until from + len) sb.append(String.format("%02X", b[k].toInt() and 0xFF))
        return sb.toString()
    }

    private fun u32(b: ByteArray, i: Int): Long =
        (b[i].toLong() and 0xFF) or
            ((b[i + 1].toLong() and 0xFF) shl 8) or
            ((b[i + 2].toLong() and 0xFF) shl 16) or
            ((b[i + 3].toLong() and 0xFF) shl 24)

    private fun i32(b: ByteArray, i: Int): Int {
        val raw = u32(b, i)
        return raw.toInt()
    }
}
