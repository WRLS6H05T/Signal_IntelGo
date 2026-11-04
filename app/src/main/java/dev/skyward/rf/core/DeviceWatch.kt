package dev.skyward.rf.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Passive signature matching for surveillance hardware.
 *
 * A phone radio gives a name, an address and - for BLE - the manufacturer id the advertiser
 * puts in its payload. That is enough to recognise the families that broadcast a stable name:
 * Flock Safety ALPR cameras and their battery packs, Axon body-worn cameras and Axon's TASER
 * conducted-energy devices, and the Bluetooth item trackers that follow a person or a car
 * around - AirTag, Tile, SmartTag, Chipolo and their cousins. It is deliberately not claimed to
 * be more than that. A camera that keeps its name hidden, a tracker running Find My's encrypted
 * rotation, or any device whose address rotates every fifteen minutes, is indistinguishable from
 * an anonymous MAC, and nothing here pretends otherwise.
 *
 * The patterns come from published field work - the flock-detection firmware's signature table
 * and the XUNTONG manufacturer id Flock's camera and battery hardware advertises - not from a
 * vendor API. So a hit is a lead worth looking at, never proof of what the device is, and
 * [Verdict.confidence] says how much corroboration stood behind it. For trackers the direction
 * is the other way round: a name is the only evidence, because the strong trackers randomise
 * their addresses and hide their payloads, and the manufacturer id alone would match every phone
 * in the street.
 */
enum class WatchKind(val label: String, val detail: String, val spoken: String) {
    FLOCK("FLOCK", "Flock Safety ALPR camera", "Flock camera"),
    TASER("TASER", "Axon TASER", "Taser"),
    AXON("AXON", "Axon body camera", "Axon camera"),
    TRACKER("TRACKER", "Bluetooth item tracker", "Bluetooth tracker"),
}

/** One matched advertiser, as the radios heard it. */
data class WatchHit(
    val kind: WatchKind,
    val id: String,
    val name: String,
    val rssi: Int,
    val radio: RfKind,
    val source: String,
    val at: Long,
    /** How the match was made, shown on the marker so the user can weigh it. */
    val confidence: String,
)

object DeviceWatch {

    /** XUNTONG's Bluetooth company id: the strongest single signal for Flock hardware. */
    const val XUNTONG_COMPANY_ID = 0x09C8

    /** Apple's and Samsung's company ids: what an AirTag or SmartTag advertises under. */
    const val APPLE_COMPANY_ID = 0x004C
    const val SAMSUNG_COMPANY_ID = 0x0075

    /** Names Flock's cameras, batteries and gunshot units have been seen to broadcast. */
    private val FLOCK_CONTAINS = listOf("flock", "flockos", "flocksafety", "fs ext battery", "pigvision")

    /** Prefixes rather than contains: "FS_" and "Penguin-" are only Flock at the start of a name. */
    private val FLOCK_PREFIXES = listOf("fs_", "fs-", "penguin-")

    /** The catalogue-style name: Flock- then four hex digits from the address. */
    private val FLOCK_SERIAL = Regex("^flock-[0-9a-f]{4}", RegexOption.IGNORE_CASE)

    /**
     * Tracker product names. Not "tracker" on its own: that word is on fitness bands and pet
     * collars, and a false alarm every hundred metres is worse than a missed tag.
     */
    private val TRACKER_WORDS = listOf(
        "airtag", "air tag", "smarttag", "smart tag", "galaxy smarttag",
        "chipolo", "cubitag", "cubi tag", "pebblebee", "trackr", "nut find", "nutfind",
        "itag", "tile tracker", "tile mate", "tile pro", "tile slim", "tile sticker",
        "tile wallet", "tile mate",
    )

    /** Old Tile tags broadcast the bare product name, which is an exact match, not a substring. */
    private val TRACKER_EXACT = Regex("^tile([-_ ].*)?$", RegexOption.IGNORE_CASE)

    data class Verdict(val kind: WatchKind, val confidence: String, val strong: Boolean)

    /**
     * Names the family behind one advertisement, or null when it looks like ordinary traffic.
     *
     * TASER is tested before AXON because Axon names a TASER "AXON TASER 7", and the more
     * specific device is the more useful label.
     */
    fun assess(radio: RfKind, name: String, id: String, companyId: Int): Verdict? {
        val n = name.trim().lowercase()
        val xuntong = companyId == XUNTONG_COMPANY_ID
        val serial = FLOCK_SERIAL.containsMatchIn(name.trim())
        val flockName = n.isNotEmpty() &&
            (FLOCK_CONTAINS.any { n.contains(it) } || FLOCK_PREFIXES.any { n.startsWith(it) })

        if (xuntong || serial || flockName) {
            val confidence = when {
                xuntong && (serial || flockName) -> "name + manufacturer id"
                xuntong -> "manufacturer id only"
                serial -> "SSID serial format"
                else -> "name only"
            }
            return Verdict(WatchKind.FLOCK, confidence, strong = xuntong || (serial && flockName))
        }

        // Axon's connected devices: body cameras and the Fleet / Signal family carry the Axon
        // name, and the CEDs carry TASER or the model number.
        if (n.contains("taser") || n.startsWith("x26") || n.startsWith("t7-")) {
            return Verdict(WatchKind.TASER, "name only", strong = false)
        }
        if (n.contains("axon")) {
            return Verdict(WatchKind.AXON, "name only", strong = false)
        }

        // Trackers: a name is the evidence. The manufacturer id only raises confidence, because
        // Apple's id is on every iPhone and Samsung's on every Galaxy in the same street.
        val trackerName = n.isNotEmpty() &&
            (TRACKER_WORDS.any { n.contains(it) } || TRACKER_EXACT.matches(name.trim()))
        if (trackerName) {
            val maker = when (companyId) {
                APPLE_COMPANY_ID -> true
                SAMSUNG_COMPANY_ID -> true
                else -> false
            }
            return Verdict(
                WatchKind.TRACKER,
                if (maker) "name + manufacturer id" else "name only",
                strong = maker,
            )
        }
        return null
    }

    /**
     * Where to draw a hit around the observer's own fix.
     *
     * A handset radio reports no bearing, so the honest position is "here, and somewhere within
     * radio range". The hit is therefore pinned at the fix and nudged by a few tens of metres
     * derived from its address - deterministic, so it does not wander between frames, and enough
     * that several cameras heard at once do not draw as one dot.
     */
    fun place(id: String, lat: Double, lon: Double): Pair<Double, Double> {
        val h = id.hashCode()
        val angle = ((h and 0xFF) / 255.0) * 2.0 * PI
        val metres = 0.00015 + ((h shr 8 and 0xFF) / 255.0) * 0.00015
        val dLat = metres * cos(angle)
        val dLon = metres * sin(angle) / cos(lat * PI / 180.0).coerceAtLeast(0.2)
        return (lat + dLat) to (lon + dLon)
    }
}
