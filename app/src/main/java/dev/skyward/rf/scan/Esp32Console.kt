package dev.skyward.rf.scan

/**
 * What a wired board says on its own console.
 *
 * A board on a cable has exactly one channel back to the phone, and it is the same serial
 * console that was used to identify it. GhostESP and Marauder answer `gps` and a scan command
 * there, and a board with a GPS module behind a bridge may simply stream NMEA; either way the
 * text arrives here and is turned into numbers the roster can use.
 *
 * Every field is nullable on purpose. A board that never printed a Wi-Fi sweep has an unknown
 * AP count, not a count of zero, and the panel says so.
 */
object Esp32Console {

    /** One reading from a transcript. Null means "the board did not say". */
    data class Reading(
        val wifi: Int? = null,
        val ble: Int? = null,
        val sats: Int? = null,
        val fix: Boolean? = null,
        val hdop: Double? = null,
        val lat: Double? = null,
        val lon: Double? = null,
        val altM: Double? = null,
    ) {
        /** Nothing worth showing: the poll produced no numbers at all. */
        val isEmpty: Boolean
            get() = wifi == null && ble == null && sats == null && lat == null && lon == null

        /** Newer values win; a field this poll did not mention keeps its earlier value. */
        fun merge(other: Reading): Reading = Reading(
            wifi = other.wifi ?: wifi,
            ble = other.ble ?: ble,
            sats = other.sats ?: sats,
            fix = other.fix ?: fix,
            hdop = other.hdop ?: hdop,
            lat = other.lat ?: lat,
            lon = other.lon ?: lon,
            altM = other.altM ?: altM,
        )
    }

    /**
     * Commands that make a board sweep and report.
     *
     * Both CLIs answer `gps` with the current fix and `scanap` with an AP sweep; GhostESP and
     * Marauder differ only in the BLE verb. A bare ESP32 or a Biscuit has no CLI at all, so it
     * gets no commands and the poll simply listens, which is enough for NMEA.
     */
    fun commands(family: BoardFamily): List<String> = when (family) {
        BoardFamily.GHOSTESP -> listOf("gps\r\n", "scanap\r\n", "bledetect\r\n")
        BoardFamily.MARAUDER -> listOf("gps\r\n", "scanap\r\n", "blescan\r\n")
        BoardFamily.BISCUIT, BoardFamily.ESP32 -> emptyList()
    }

    // ---------------- text fields ----------------

    private val AP_PATTERNS = listOf(
        Regex("""(?i)\b(\d+)[ \t]+access[ \t]+points?\b"""),
        Regex("""(?i)\baccess\s+points?\s*(?:found)?\s*[:=]\s*(\d+)"""),
        Regex("""(?i)\baps?\s+found\s*[:=]?\s*(\d+)"""),
        Regex("""(?i)\baps?\s*[:=]\s*(\d+)"""),
        Regex("""(?i)\b(\d+)[ \t]+aps?\b"""),
        Regex("""(?i)\bnetworks?\s*[:=]\s*(\d+)"""),
    )

    private val BLE_PATTERNS = listOf(
        Regex("""(?i)\b(\d+)[ \t]+(?:ble|bluetooth)[ \t]+(?:devices?|nodes?)\b"""),
        Regex("""(?i)\bble\s+(?:devices?|count|found)\s*[:=]?\s*(\d+)"""),
        Regex("""(?i)\bbluetooth\s+devices?\s*[:=]\s*(\d+)"""),
        Regex("""(?i)\bble\s*[:=]\s*(\d+)"""),
    )

    private val SAT_PATTERNS = listOf(
        Regex("""(?i)\bsats?\s*[:=]\s*(\d+)"""),
        Regex("""(?i)\bsatellites?\s*[:=]\s*(\d+)"""),
        Regex("""(?i)\b(\d+)[ \t]+sats?\b"""),
    )

    private val HDOP = Regex("""(?i)\bhdop\s*[:=]\s*([\d.]+)""")
    private val LAT = Regex("""(?i)\blat(?:itude)?\s*[:=]\s*(-?\d+(?:\.\d+)?)""")
    private val LON = Regex("""(?i)\blon(?:gitude)?\s*[:=]\s*(-?\d+(?:\.\d+)?)""")
    private val ALT = Regex("""(?i)\balt(?:itude)?\s*[:=]\s*(-?\d+(?:\.\d+)?)""")
    private val FIX_YES = Regex("""(?i)(?:\bfix\b\s*[:=]\s*(?:yes|true|ok|valid|acquired|on|1)\b|\b(?:gps|gnss)\s*[:=]\s*(?:fix|3d|valid|acquired)\b)""")
    private val FIX_NO = Regex("""(?i)(?:\bfix\b\s*[:=]\s*(?:no|false|none|invalid|lost|off|0)\b|\b(?:gps|gnss)\s*[:=]\s*(?:no\s*fix|none|lost|waiting|searching)\b)""")

    /** Turns a poll's transcript into a [Reading]; never throws. */
    fun parse(text: String): Reading {
        var out = Reading()
        // NMEA first: it is the only format with a standard position encoding, and a board
        // that streams it may also print nothing else.
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("$")) nmea(t)?.let { out = out.merge(it) }
        }
        val labelled = Reading(
            wifi = firstInt(text, AP_PATTERNS),
            ble = firstInt(text, BLE_PATTERNS),
            sats = firstInt(text, SAT_PATTERNS),
            hdop = firstDouble(text, HDOP)?.takeIf { it > 0.0 },
            lat = firstDouble(text, LAT),
            lon = firstDouble(text, LON),
            altM = firstDouble(text, ALT),
            fix = when {
                FIX_YES.containsMatchIn(text) -> true
                FIX_NO.containsMatchIn(text) -> false
                else -> null
            },
        )
        return out.merge(labelled)
    }

    // ---------------- NMEA ----------------

    /**
     * GGA carries everything at once - fix quality, satellites, HDOP and altitude - so it is
     * read in preference to RMC, which only states the position and whether the fix is valid.
     */
    private fun nmea(sentence: String): Reading? {
        val fields = sentence.substringBefore('*').split(',')
        if (fields.size < 7) return null
        return when (fields[0].takeLast(3)) {
            "GGA" -> {
                if (fields.size < 10) return null
                val quality = fields[6].toIntOrNull() ?: 0
                Reading(
                    fix = quality > 0,
                    sats = fields[7].toIntOrNull(),
                    hdop = fields[8].toDoubleOrNull()?.takeIf { it > 0.0 },
                    lat = degrees(fields[2], fields[3]),
                    lon = degrees(fields[4], fields[5]),
                    altM = fields[9].toDoubleOrNull(),
                )
            }

            "RMC" -> Reading(
                fix = fields[2].equals("A", ignoreCase = true),
                lat = degrees(fields[3], fields[4]),
                lon = degrees(fields[5], fields[6]),
            )

            else -> null
        }
    }

    /** NMEA writes degrees and minutes as `ddmm.mmmm`, with the hemisphere as a letter. */
    private fun degrees(value: String, hemisphere: String): Double? {
        if (value.isBlank()) return null
        val raw = value.toDoubleOrNull() ?: return null
        val whole = (raw / 100.0).toInt()
        val out = whole + (raw - whole * 100.0) / 60.0
        val negative = hemisphere.equals("S", true) || hemisphere.equals("W", true)
        return if (negative) -out else out
    }

    // ---------------- helpers ----------------

    private fun firstInt(text: String, patterns: List<Regex>): Int? {
        for (p in patterns) {
            val v = p.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (v != null) return v
        }
        return null
    }

    private fun firstDouble(text: String, pattern: Regex): Double? =
        pattern.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
}
