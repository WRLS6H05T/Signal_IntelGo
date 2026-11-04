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

    /** Messages about recorded drives, as opposed to live sweeps. */
    const val KIND_LOGS = "logs"

    /** Globe device: the bigger screen, which shows the map and the sphere. */
    const val DISPLAY = "display"

    /** Wardriving device: the smaller screen, which shows the drive readout. */
    const val SENSOR = "sensor"

    /** Roles chosen by hand, instead of by screen size. */
    const val MODE_AUTO = "auto"
    const val MODE_HOST = "host"
    const val MODE_GLOBE = "globe"

    /** Discovery payloads. They are UDP datagrams, so they stay tiny and plain. */
    const val PROBE = "SKYWARD?"
    const val HERE = "SKYWARD!"

    @Serializable
    data class Ap(val id: String, val name: String, val rssi: Int, val ch: Int = 0)

    @Serializable
    data class Device(val id: String, val name: String, val rssi: Int)

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

    /**
     * The settings a host is responsible for, pushed to every linked device.
     *
     * Deliberately a bounded, display-and-scan set: the follower is not told to re-flash a
     * board or change a broker, because a bad value there would take the other phone off the
     * air with no way back except picking up the handset.
     *
     * [rev] goes up whenever the host changes one of these. A follower applies a blob once per
     * revision, which is what lets both rules hold at the same time: a host toggle lands on the
     * other phone, and a value changed on the other phone for itself stays put while the host
     * merely repeats what it already said.
     */
    @Serializable
    data class HostSettings(
        val rev: Int = 0,
        val tileSource: String = "streets",
        val dropWeakDots: Boolean = false,
        val aircraftEnabled: Boolean = true,
        val satGroups: List<String> = emptyList(),
        val globeSpin: Int = 1,
        /** Band assigned to each radio, keyed ScanPlan.PEER_KEY or a board id. */
        val scanAssign: Map<String, String> = emptyMap(),
        /** The clock and the units: the shared dashboard, not the radio. */
        val showLiveTime: Boolean = true,
        val clockSeconds: Boolean = true,
        val distanceUnit: String = "km",
        val tempUnit: String = "c",
    )

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
        /** The role this phone was given by hand: auto, host or globe. */
        val mode: String = "",
        /** What this phone has been assigned to sweep, as a ScanPlan code. */
        val scan: String = "",
        /** Whether this phone is recording a drive right now, and what it called that drive. */
        val recording: Boolean = false,
        val recordName: String = "",
        /**
         * Intel from this phone's own ESP32 boards, carried alongside its sweep so the other
         * phone holds it too. The record of it stays with the board that heard it.
         */
        val boardWifi: List<Ap> = emptyList(),
        val boardBle: List<Device> = emptyList(),
        val boardWifiTotal: Int = 0,
        val boardBleTotal: Int = 0,
        /** Present only from a host, and only on a host's snapshot. */
        val hostSettings: HostSettings? = null,
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

    /** An explicit role wins over the screen-size comparison; "auto" falls back to it. */
    fun roleForMode(mode: String, mineInches: Float, peerInches: Float): String = when (mode) {
        MODE_HOST, MODE_GLOBE -> DISPLAY
        else -> roleFor(mineInches, peerInches)
    }

    fun roleLabel(role: String): String = when (role) {
        DISPLAY -> "globe"
        SENSOR -> "wardrive"
        else -> "shared"
    }

    fun modeLabel(mode: String): String = when (mode) {
        MODE_HOST -> "host"
        MODE_GLOBE -> "globe view"
        else -> "auto"
    }

    fun isGlobeMode(mode: String): Boolean = mode == MODE_HOST || mode == MODE_GLOBE

    /**
     * Whether a follower should take a host blob.
     *
     * Once per revision is the whole rule. The host resends its settings with every snapshot,
     * so applying repeats unconditionally would undo every change made here seconds later;
     * applying nothing would ignore the next thing the host does.
     */
    fun shouldApplyHost(rev: Int, applied: Int): Boolean = rev > applied
}
