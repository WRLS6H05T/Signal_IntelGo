package dev.skyward.rf.core

/**
 * What a BLE advertiser probably is, from the name it broadcasts.
 *
 * A BLE advertiser has no standard field for its kind: the advertisements carry a name and a
 * manufacturer blob, and the names are whatever the vendor chose. So this is a keyword read of the
 * name, ordered from the most specific product families to the vaguest, and it says `UNKNOWN`
 * rather than guessing when nothing matches - a wrong category is worse than none when the point of
 * the column is to find the headset among forty beacons.
 *
 * Kept in `:shared` so the phone and the desk label the same advertiser the same way.
 */
object BleKind {

    const val AUDIO = "AUDIO"
    const val WEARABLE = "WEARABLE"
    const val PHONE = "PHONE"
    const val COMPUTER = "COMPUTER"
    const val INPUT = "INPUT"
    const val TRACKER = "TRACKER"
    const val BEACON = "BEACON"
    const val VEHICLE = "VEHICLE"
    const val MEDIA = "MEDIA"
    const val PRINTER = "PRINTER"
    const val HEALTH = "HEALTH"
    const val NETWORK = "NETWORK"
    const val PAYMENT = "PAYMENT"
    const val UNKNOWN = "UNKNOWN"

    private val rules: List<Pair<String, List<String>>> = listOf(
        AUDIO to listOf(
            "airpods", "buds", "earbud", "headphone", "headset", "speaker", "soundbar", "boombox",
            "jabra", "bose", "beats", "sonos", "jbl", "sennheiser", "soundcore", "wave",
            // Sony's models are sold by model number, so the prefix is the only clue in the name.
            "wh-", "wf-", "sony", "tws", "earbud",
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
        PAYMENT to listOf("square", "sumup", "zettle", "card reader", "pos ", "terminal"),
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

    /** The category for an advertiser, from its name. Address is accepted for future OUI rules. */
    fun of(name: String, address: String = ""): String {
        val needle = name.lowercase().trim()
        if (needle.isNotEmpty()) {
            rules.forEach { (kind, words) ->
                if (words.any { needle.contains(it) }) return kind
            }
        }
        return UNKNOWN
    }

    /** Lower case, for a table cell. */
    fun label(name: String, address: String = ""): String = of(name, address).lowercase()
}
