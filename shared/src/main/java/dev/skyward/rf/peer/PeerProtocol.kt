package dev.skyward.rf.peer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

/**
 * Wire format for the two-device link.
 *
 * Both ends are the same kind of peer. There is no host and no follower: neither phone assigns
 * the other a scan, rewrites its settings or speaks for it. What travels is what each side has
 * to offer - the networks and advertisers its own radios heard, its fix, its boards, the
 * entities it has logged and the drives it has recorded - so your own two devices, or two
 * friends' phones, end up holding the same intel without either of them being in charge.
 *
 * Kept free of Android and of the app's own models on purpose: this is the one part of the
 * feature that can be tested on the JVM, and a protocol that drifts silently between two phones
 * is exactly the kind of fault no build catches.
 *
 * Messages are one JSON object per line. Anything unreadable is dropped rather than closing the
 * link - a half-written line while the other phone is sending is normal, not fatal.
 */
object PeerProtocol {

    /**
     * Bumped when the link stops being what the old one was: this version carries entities and
     * no longer carries a mode or a scan assignment, so an older build is refused rather than
     * read as a peer that has simply gone quiet.
     */
    const val VERSION = 2

    const val KIND_SKY = "sky"

    /** Messages about recorded drives, as opposed to live sweeps. */
    const val KIND_LOGS = "logs"

    /** A Watcher command, said to one phone and obeyed by both. */
    const val KIND_WATCHER = "watcher"

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

    /**
     * One entity this phone has logged, offered to the other one.
     *
     * [layer] is the Layer name, so a satellite arrives as a satellite and an aircraft as an
     * aircraft: the receiving phone plots it in the same layer it would have plotted its own
     * catch, tagged as the peer's. The position is where it was caught, not where it is now.
     */
    @Serializable
    data class Entity(
        val layer: String,
        val id: String,
        val name: String,
        val lat: Double,
        val lon: Double,
    ) {
        val key: String get() = "$layer:$id"
    }

    /** One recorded drive this phone is offering, as the other one sees it in its list. */
    @Serializable
    data class LogInfo(
        val id: String,
        val name: String,
        val at: Long,
        val points: Int,
        val km: Double,
        val wifi: Int,
        val ble: Int,
        val best: Int,
    )

    /** One slice of a drive being sent; [total] must agree on every slice. */
    @Serializable
    data class LogPart(val id: String, val idx: Int, val total: Int, val data: String)

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
        /** Whether the other phone is using a GPS fix of its own, and its cellular radio. */
        val gpsUsed: Boolean = false,
        val cellUsed: Boolean = false,
        val wifi: List<Ap> = emptyList(),
        val ble: List<Device> = emptyList(),
        /** The drives this phone has recorded and is willing to hand over. */
        val logs: List<LogInfo> = emptyList(),
        /** Asks the other phone to push its list of drives. */
        val wantList: Boolean = false,
        /** The id of a drive to fetch; it answers with a run of [part] messages. */
        val want: String = "",
        /** One slice of a drive on its way over. */
        val part: LogPart? = null,
        /** Whether this phone is recording a drive right now, and what it called that drive. */
        val recording: Boolean = false,
        val recordName: String = "",
        /** A Watcher command forwarded from the other phone, by name. */
        val voice: String = "",
        /**
         * Intel from this phone's own ESP32 boards, carried alongside its sweep so the other
         * phone holds it too. The record of it stays with the board that heard it.
         */
        val boardWifi: List<Ap> = emptyList(),
        val boardBle: List<Device> = emptyList(),
        val boardWifiTotal: Int = 0,
        val boardBleTotal: Int = 0,
        /** Entities this phone has logged; [entityTotal] is the table it was sampled from. */
        val entities: List<Entity> = emptyList(),
        val entityTotal: Int = 0,
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
