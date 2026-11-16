package dev.skyward.rf.core

/**
 * What kind of thing a BLE advertiser is.
 *
 * A BLE advertisement has no standard field for this - it carries a name and a manufacturer blob,
 * and the name is whatever the vendor chose - so the kind comes from a keyword read of the name and
 * falls back to the vendor that owns the address, and only then to `UNKNOWN`. A wrong category is
 * worse than none when the point of the column is to find the headset among forty beacons.
 *
 * An enum rather than bare strings so the phone and the desk cannot drift on spelling, and so a
 * later screen can group by it with `entries`.
 */
enum class BleKind(val label: String) {
    AUDIO("audio"),
    WEARABLE("wearable"),
    PHONE("phone"),
    COMPUTER("computer"),
    INPUT("input"),
    TRACKER("tracker"),
    BEACON("beacon"),
    VEHICLE("vehicle"),
    MEDIA("media"),
    PRINTER("printer"),
    HEALTH("health"),
    NETWORK("network"),
    PAYMENT("payment"),
    UNKNOWN("unknown"),
    ;

    companion object {

        private val rules: List<Pair<BleKind, List<String>>> = listOf(
            AUDIO to listOf(
                "airpods", "buds", "earbud", "headphone", "headset", "speaker", "soundbar", "boombox",
                "jabra", "bose", "beats", "sonos", "jbl", "sennheiser", "soundcore", "wave", "tws",
                // Sony sells by model number, so the prefix is the only clue in the name.
                "wh-", "wf-", "sony",
            ),
            WEARABLE to listOf(
                "watch", "fitbit", "garmin", "whoop", "band", "ring", "wear", "amazfit", "oura",
            ),
            TRACKER to listOf("airtag", "smarttag", "tile", "chipolo", "tracker", "trackr", "itag"),
            BEACON to listOf("beacon", "ibeacon", "eddystone", "estimote", "altbeacon"),
            INPUT to listOf(
                "keyboard", "keychron", "mouse", "trackpad", "gamepad", "controller", "dualsense",
                "dualshock", "xbox", "joy-con", "logitech", "kbd",
            ),
            PAYMENT to listOf("square", "sumup", "zettl", "card reader", "pos ", "terminal"),
            PRINTER to listOf("printer", "brother", "epson", "canon", "xerox", "hp office"),
            HEALTH to listOf(
                "thermo", "glucose", "oximeter", "pulse", "blood", "scale", "medical", "hotcold",
                "dexcom", "omnipod",
            ),
            VEHICLE to listOf("tesla", "obd", "carplay", "android auto", "vehicle", "scooter", "bike"),
            NETWORK to listOf("router", "gateway", "access point", "mesh", "extender", "wifi"),
            MEDIA to listOf("tv", "roku", "chromecast", "fire tv", "firetv", "shield", "projector"),
            COMPUTER to listOf("macbook", "laptop", "thinkpad", "surface", "desktop", "imac", "pc-"),
            PHONE to listOf("iphone", "pixel", "galaxy", "sm-g", "redmi", "oneplus", "moto ", "phone"),
        )

        /** The category an advertiser's name claims, or [UNKNOWN] when it claims nothing. */
        fun of(name: String): BleKind {
            val needle = name.lowercase().trim()
            if (needle.isEmpty()) return UNKNOWN
            rules.forEach { (kind, words) ->
                if (words.any { needle.contains(it) }) return kind
            }
            return UNKNOWN
        }
    }
}

/**
 * The maker behind an advertiser's address, and the kind that maker's BLE devices usually are.
 *
 * A public Bluetooth address carries an IEEE-assigned 24-bit prefix, so the first three octets name
 * the company that bought the block. That is the only thing the address can honestly say: a *random*
 * address - which is what phones, watches and trackers hand out by default - is just a number the
 * device made up, and running the lookup on one produces a plausible and wrong vendor. The desk's
 * board firmware reports no address type, and the bits cannot stand in for it, because a real public
 * prefix like Apple's `F0:18:98` begins `11` and every random sub-type pattern is legal for a public
 * address too. So an OUI hit is offered as the vendor; no hit means *nothing*, not "not a vendor".
 *
 * A seed table on purpose: this holds only prefixes checked against the registry. The full registry
 * is 30,000+ blocks and does not belong in a phone binary - add entries as sightings demand, with the
 * registry entry to hand, and never guess one.
 */
object BleVendor {

    /** Prefix to vendor, with the kind when that vendor's BLE presence is dominated by one. */
    private val byOui: Map<String, Pair<String, BleKind>> = mapOf(
        // Apple, Inc. - MA-L blocks. Apple ships phones, watches, earbuds and computers, so the
        // vendor is all the prefix can say; the name has to supply the kind.
        "00:1C:B3" to ("apple" to BleKind.UNKNOWN),
        "3C:07:54" to ("apple" to BleKind.UNKNOWN),
        "00:03:93" to ("apple" to BleKind.UNKNOWN),
        "F0:18:98" to ("apple" to BleKind.UNKNOWN),
        // Bose Corporation - speakers, headphones, soundbars.
        "C8:7B:23" to ("bose" to BleKind.AUDIO),
    )

    /** The vendor name, or null when the prefix is not one this table has been taught. */
    fun of(mac: String): String? = entry(mac)?.first

    /** The kind the vendor implies, or null when nobody knows the prefix. */
    fun kindOf(mac: String): BleKind? = entry(mac)?.second?.takeIf { it != BleKind.UNKNOWN }

    private fun entry(mac: String): Pair<String, BleKind>? = BleAddress.oui(mac)?.let { byOui[it] }
}

/** The 48-bit address, read only as far as the prefix that IEEE may have assigned. */
object BleAddress {

    /** The twelve address digits, upper case, or null when the string is not an address. */
    fun digits(mac: String): String? {
        val hex = mac.uppercase().filter { it in "0123456789ABCDEF" }
        return if (hex.length >= 12) hex.take(12) else null
    }

    /** `AA:BB:CC` for any well-formed address; whether it is really IEEE-assigned is unknowable here. */
    fun oui(mac: String): String? =
        digits(mac)?.take(6)?.chunked(2)?.joinToString(":")
}

/**
 * The maker, from the SIG company identifier in the advertisement's manufacturer data.
 *
 * Every advertisement that carries manufacturer data names its company here, up front and in the
 * clear, which is why it is worth more than the address: a phone, a watch and a pair of earbuds all
 * hand out randomised addresses, and none of them can hide this field. The table is short for the
 * same reason as [BleVendor]'s - only identifiers checked against the SIG's assigned numbers.
 *
 * Apple, Google and Microsoft ship every kind of device off one identifier, so theirs name a vendor
 * and no kind; the service UUIDs decide for those.
 */
object BleCompany {

    private val byId: Map<Int, Pair<String, BleKind>> = mapOf(
        0x0006 to ("microsoft" to BleKind.COMPUTER),
        0x004C to ("apple" to BleKind.UNKNOWN),
        0x00E0 to ("google" to BleKind.UNKNOWN),
        // Garmin International, Inc.
        0x0087 to ("garmin" to BleKind.WEARABLE),
        // Plantronics, Inc. - headsets.
        0x0055 to ("plantronics" to BleKind.AUDIO),
        // Harman International - JBL and Harman Kardon speakers and headphones.
        0x0057 to ("harman" to BleKind.AUDIO),
        // GN Hearing A/S - hearing instruments and the Jabra line.
        0x0089 to ("gn" to BleKind.AUDIO),
        // Gimbal, Inc. - beacons.
        0x008C to ("gimbal" to BleKind.BEACON),
    )

    fun of(companyId: Int): String? = byId[companyId]?.first

    fun kindOf(companyId: Int): BleKind? = byId[companyId]?.second?.takeIf { it != BleKind.UNKNOWN }
}

/**
 * The service UUIDs an advertisement lists.
 *
 * A device only advertises the services it offers, and some of them settle its kind on their own:
 * LE Audio's announcement services are audio, Heart Rate is something you wear, HID is a keyboard.
 * Services on nearly every device - battery, device information, generic access - are left out on
 * purpose, because `has a battery` is not a category.
 */
object BleService {

    private val kindByShort: Map<String, BleKind> = mapOf(
        "1802" to BleKind.TRACKER,   // Immediate Alert: find-me
        "1808" to BleKind.HEALTH,    // Glucose
        "1809" to BleKind.HEALTH,    // Health Thermometer
        "180D" to BleKind.WEARABLE,  // Heart Rate
        "1810" to BleKind.HEALTH,    // Blood Pressure
        "1811" to BleKind.PHONE,     // Alert Notification: a phone publishes it
        "1812" to BleKind.INPUT,     // Human Interface Device
        "1814" to BleKind.WEARABLE,  // Running Speed and Cadence
        "1816" to BleKind.WEARABLE,  // Cycling Speed and Cadence
        "1826" to BleKind.WEARABLE,  // Fitness Machine
        "1827" to BleKind.NETWORK,   // Mesh Provisioning
        "184E" to BleKind.AUDIO,     // Audio Stream Control (LE Audio)
        "1851" to BleKind.AUDIO,     // Basic Audio Announcement (LE Audio)
        "1852" to BleKind.AUDIO,     // Broadcast Audio Announcement (LE Audio)
        "FD6F" to BleKind.PHONE,     // Exposure Notification
        "FEAA" to BleKind.BEACON,    // Eddystone
    )

    private val vendorByShort: Map<String, String> = mapOf(
        "FE2C" to "google",          // Fast Pair: earbuds, but trackers use it too
    )

    /** The first UUID that names a kind, or null. */
    fun kindOf(uuids: List<String>): BleKind? = uuids.firstNotNullOfOrNull { kindByShort[BleUuid.short(it) ?: ""] }

    fun vendorOf(uuids: List<String>): String? = uuids.firstNotNullOfOrNull { vendorByShort[BleUuid.short(it) ?: ""] }
}

/** UUIDs arrive short (`180D`), long (`0000180D-0000-1000-8000-00805F9B34FB`) or not at all. */
object BleUuid {

    fun short(raw: String): String? {
        val s = raw.trim().uppercase()
        if (s.isEmpty()) return null
        if (s.length == 4 && s.all { it in "0123456789ABCDEF" }) return s
        val parts = s.split('-')
        if (parts.size != 5) return null
        val bluetoothBase = parts[2] == "1000" && parts[3] == "8000" && parts[4] == "00805F9B34FB"
        if (!bluetoothBase) return null
        return parts[0].takeLast(4)
    }
}

/**
 * The one string the tables and the map card print for an advertiser.
 *
 * Name first - a device that says `Jabra Elite` is audio whatever its prefix is - then the kind its
 * service UUIDs imply, then the manufacturer's company id, then whatever vendor the address's prefix
 * names. Everything else is `unknown`, and `unknown` is a real answer: it is what a silent advertiser
 * behind a randomised address with no manufacturer data is, and no amount of wanting otherwise
 * changes it.
 */
object BleIdentity {

    fun label(
        name: String,
        address: String = "",
        companyId: Int = 0,
        serviceUuids: List<String> = emptyList(),
    ): String {
        val kind = BleKind.of(name)
        if (kind != BleKind.UNKNOWN) return kind.label
        BleService.kindOf(serviceUuids)?.let { return it.label }
        BleCompany.kindOf(companyId)?.let { return it.label }
        BleCompany.of(companyId)?.let { return it }
        BleService.vendorOf(serviceUuids)?.let { return it }
        BleVendor.kindOf(address)?.let { return it.label }
        BleVendor.of(address)?.let { return it }
        return BleKind.UNKNOWN.label
    }
}
