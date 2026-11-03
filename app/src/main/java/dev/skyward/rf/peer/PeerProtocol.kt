package dev.skyward.rf.peer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

/**
 * Wire format for the two-device link.
 *
 * Kept free of Android and of the app's own models on purpose: this is the one part of the
 * feature that can be tested on the JVM, and a protocol that drifts silently between two phones
 * is exactly the kind of fault no build catches.
 *
 * Messages are one JSON object per line. Anything unreadable is dropped rather than closing the
 * link - a half-written line while the other phone is sending is normal, not fatal.
 */
object PeerProtocol {

    const val VERSION = 1
    const val KIND_SKY = "sky"

    /** Globe device: the bigger screen, which shows the map and the sphere. */
    const val DISPLAY = "display"

    /** Wardriving device: the smaller screen, which shows the drive readout. */
    const val SENSOR = "sensor"

    /** Discovery payloads. They are UDP datagrams, so they stay tiny and plain. */
    const val PROBE = "SKYWARD?"
    const val HERE = "SKYWARD!"

    @Serializable
    data class Ap(val id: String, val name: String, val rssi: Int, val ch: Int = 0)

    @Serializable
    data class Device(val id: String, val name: String, val rssi: Int)

    @Serializable
    data class Message(
        val v: Int = VERSION,
        val kind: String = KIND_SKY,
        val id: String = "",
        val name: String = "",
        /** Screen diagonal in inches; decides which device takes which screen. */
        val diagIn: Float = 0f,
        val lat: Double? = null,
        val lon: Double? = null,
        val sats: Int = 0,
        val at: Long = 0L,
        /** Full table sizes: [wifi] and [ble] are only the strongest slice the pipe carries. */
        val wifiTotal: Int = 0,
        val bleTotal: Int = 0,
        val wifi: List<Ap> = emptyList(),
        val ble: List<Device> = emptyList(),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    fun encode(message: Message): String = json.encodeToString(Message.serializer(), message)

    fun decode(line: String): Message? {
        val text = line.trim()
        if (text.isEmpty() || text.length > 64_000) return null
        val message = runCatching {
            json.decodeFromString(Message.serializer(), text)
        }.getOrNull() ?: return null
        // A different version is not something to guess about: both phones must agree, or one
        // of them reads the other's fields as nonsense.
        if (message.v != VERSION) return null
        if (message.kind.isBlank()) return null
        return message
    }

    /**
     * Which screen this device should show.
     *
     * The larger diagonal takes the globe, the smaller takes the wardriving readout. A tie, or
     * an unknown size, leaves both alone rather than making two similar phones fight over the
     * same role every message.
     */
    fun roleFor(mineInches: Float, peerInches: Float): String = when {
        mineInches <= 0f || peerInches <= 0f -> ""
        abs(mineInches - peerInches) < 0.3f -> ""
        mineInches > peerInches -> DISPLAY
        else -> SENSOR
    }

    fun roleLabel(role: String): String = when (role) {
        DISPLAY -> "globe"
        SENSOR -> "wardrive"
        else -> "shared"
    }
}
