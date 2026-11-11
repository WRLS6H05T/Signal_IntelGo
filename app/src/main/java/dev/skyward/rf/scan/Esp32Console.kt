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
        /** True when the board has a GNSS module, false when it says it has none. */
        val gps: Boolean? = null,
        /** True when the board reports a CC1101; null when it never said either way. */
        val subGhz: Boolean? = null,
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
            gps = other.gps ?: gps,
            subGhz = other.subGhz ?: subGhz,
        )
    }

    /** One access point a board swept: what the phone's own radio would have reported. */
    data class Ap(val bssid: String, val ssid: String, val rssi: Int, val channel: Int)

    /** One BLE advertiser a board heard. */
    data class Device(val address: String, val name: String, val rssi: Int)

    /**
     * The named half of a sweep. Counts alone only prove a radio worked; these are what put the
     * board's networks and advertisers on the map and into the drive record.
     */
    /**
     * One sub-GHz carrier: a frequency and a level, nothing else.
     *
     * A keyfob, a gate remote or a weather sensor has no address to sweep for, so the carrier
     * itself is the identity. Two hits on the same frequency are one transmitter as far as this
     * table can tell, which is why the strongest hearing is the one kept.
     */
    data class SubGhz(val freqMhz: Double, val rssi: Int, val label: String)

    data class Sweep(
        val aps: List<Ap> = emptyList(),
        val devices: List<Device> = emptyList(),
        /** Sub-GHz carriers heard while the board's CC1101 was receiving. */
        val subGhz: List<SubGhz> = emptyList(),
    ) {
        val isEmpty: Boolean get() = aps.isEmpty() && devices.isEmpty() && subGhz.isEmpty()

        val total: Int get() = aps.size + devices.size + subGhz.size
    }

    /**
     * Commands that make a board sweep and report.
     *
     * Both CLIs answer `gps` with the current fix and `scanap` with an AP sweep; GhostESP and
     * Marauder differ only in the BLE verb. A board that never identified itself is sent the
     * same sweeps: silence during a probe is a busy console, not an absent one.
     */
    fun commands(family: BoardFamily): List<String> = when (family) {
        // A sweep left running from the last poll would have to be stopped before a new one is
        // accepted, which is why every cycle opens with stopscan.
        BoardFamily.GHOSTESP -> listOf("stopscan\r\n", "gps\r\n", "scanap\r\n", "bledetect\r\n")
        BoardFamily.MARAUDER -> listOf("stopscan\r\n", "gps\r\n", "scanap\r\n", "blescan\r\n")
        // The project's own sensor sweeps once per verb and has finished printing before the
        // next command is read, so there is never a sweep left running to stop.
        BoardFamily.SIGNALSENSOR -> listOf("gps\r\n", "scanap\r\n", "blescan\r\n")
        // An unidentified board is not a board without a console: it is usually one that was
        // mid-sweep for the whole probe window. It is asked who it is - `status` is answered with
        // the banner that names the firmware - and given a fix request, which is all a headless
        // NMEA board needs. Asking it to sweep before it has said what it is orders a verb it
        // may not know; the answer to `status` upgrades it onto its real sweeps next cycle.
        BoardFamily.ESP32 -> listOf("status\r\n", "gps\r\n")
        // A Biscuit really has no CLI, only ESP-IDF logs, which no verb belongs in.
        BoardFamily.BISCUIT -> emptyList()
    }

    /**
     * The poll's command list, with the sub-GHz receive appended when that radio is in use.
     *
     * Appended last so the AP and BLE sweeps have already reported before the CC1101 is handed
     * to a receive loop, and only when a command is configured: the verb differs between GhostESP
     * builds and Marauder revisions, and a board that does not know it would print an error into
     * the middle of a sweep transcript.
     */
    fun commandsFor(family: BoardFamily, subGhzCommand: String?, plan: String? = null): List<String> {
        val base = commands(family)
        if (base.isEmpty()) return base
        // The assignment owns the sweeps: a BLE-only plan must not start an AP scan, because the
        // board spends the poll window listing networks nobody asked for and the BLE sweep is
        // what gets cut off. A stop and a fix request are always worth sending - neither fills
        // the transcript.
        val asked = if (plan.isNullOrBlank()) {
            base
        } else {
            base.filter { line ->
                val verb = line.trim()
                when {
                    verb.startsWith("stop") -> true
                    verb.startsWith("ble") -> dev.skyward.rf.core.ScanPlan.hasBle(plan)
                    verb.startsWith("scan") -> dev.skyward.rf.core.ScanPlan.hasWifi(plan)
                    else -> true
                }
            }
        }
        if (subGhzCommand.isNullOrBlank()) return asked
        return asked + (subGhzCommand.trim() + "\r\n")
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
    private val GPS_YES = Regex(
        """(?i)\b(?:gps|gnss)\s*[:=]\s*(?:ok|on|yes|present|detected|ready|fix|3d|2d|a|\d)""",
    )
    private val GPS_NO = Regex(
        """(?i)(?:\b(?:no|without)\s+(?:gps|gnss)\b|\b(?:gps|gnss)\b[^\n]{0,4}?\b(?:not\s+(?:detected|found|present|fitted)|absent|missing|disabled))""",
    )
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
            gps = when {
                GPS_NO.containsMatchIn(text) -> false
                out.lat != null || out.sats != null -> true
                GPS_YES.containsMatchIn(text) -> true
                // A board that answers `gps` with only a fix state still has a module.
                out.fix != null -> true
                else -> null
            },
        )
        // A board saying "cc1101" or printing a carrier frequency has the sub-GHz radio fitted.
        // Silence leaves it null: not fitted and never mentioned are different answers.
        val radio = SUBGHZ_WORDS.containsMatchIn(text) ||
            text.lineSequence().any { subGhzOf(it) != null }
        return out.merge(labelled.copy(subGhz = if (radio) true else null))
    }

    // ---------------- sweep listings ----------------

    private val MAC = Regex("""(?i)\b([0-9a-f]{2}(?::[0-9a-f]{2}){5})\b""")
    private val RSSI_LABELLED = Regex("""(?i)\b(?:rssi|signal|dbm|level)\s*[:=]?\s*(-?\d{1,3})\b""")
    private val RSSI_PLAIN = Regex("""(?<![\w.-])(-\d{1,3})(?![\w.])""")
    private val CHANNEL = Regex("""(?i)\b(?:ch|chan|channel)\s*[:=]?\s*(\d{1,3})\b""")
    private val SSID_LABELLED = Regex(
        """(?i)\b(?:ssid|essid|network(?:\s*name)?)\s*[:=]\s*([^,;|]{1,32})""",
    )
    private val NAME_LABELLED = Regex("""(?i)\bname\s*[:=]\s*([^,;|]{1,32})""")
    private val BLE_LINE = Regex("""(?i)\b(?:ble|bt|bluetooth)\b""")

    /**
     * Reads a sweep listing into the individual networks and advertisers it named.
     *
     * The two firmwares print sweeps in different shapes and neither is documented as stable, so
     * this works line by line off the only two things that must be there for a line to mean
     * anything: an address and a signal level. A line without both is boot noise and is skipped,
     * so a silent or partly-printed sweep adds nothing rather than inventing entries.
     */
    /** A board that names a CC1101, or its own sub-GHz band, has the radio fitted. */
    private val SUBGHZ_WORDS = Regex("""(?i)\b(?:cc1101|sub-?ghz|sub ghz)\b""")

    /** A carrier frequency with its unit. 2400 MHz Wi-Fi and 2.4 GHz BLE are outside the band. */
    private val FREQ_MHZ = Regex("""(?i)\b([3-9]\d{2}(?:[.,]\d{1,3})?)\s*mhz\b""")

    /**
     * A sub-GHz carrier on this line, if it has both a frequency and a level.
     *
     * Both are required, for the same reason a network needs an address and a level: a bare
     * frequency in a help text or a firmware banner is not a transmission.
     */
    private fun subGhzOf(line: String): SubGhz? {
        if (BLE_LINE.containsMatchIn(line)) return null
        val mhz = FREQ_MHZ.find(line)?.groupValues?.getOrNull(1)
            ?.replace(',', '.')?.toDoubleOrNull() ?: return null
        if (mhz !in 280.0..960.0) return null
        val rssi = rssiOf(line) ?: return null
        return SubGhz(mhz, rssi, clean(line).take(24))
    }

    fun parseScan(text: String): Sweep {
        val aps = LinkedHashMap<String, Ap>()
        val devices = LinkedHashMap<String, Device>()
        val sub = LinkedHashMap<String, SubGhz>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.length > 240) continue
            // A carrier line carries a frequency and a level but no address, so it is taken
            // before the MAC test that would otherwise discard it.
            subGhzOf(line)?.let { hit ->
                val key = String.format(java.util.Locale.US, "%.2f", hit.freqMhz)
                val prev = sub[key]
                if (prev == null || hit.rssi > prev.rssi) sub[key] = hit
            }
            val mac = MAC.find(line)?.groupValues?.getOrNull(1)?.uppercase() ?: continue
            val rssi = rssiOf(line) ?: continue
            val channel = CHANNEL.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val isBle = BLE_LINE.containsMatchIn(line) && channel == null
            if (!isBle && (channel != null || SSID_LABELLED.containsMatchIn(line))) {
                val ssid = ssidOf(line, mac)
                aps[mac] = Ap(mac, ssid, rssi, channel ?: 0)
            } else {
                devices[mac] = Device(mac, nameOf(line, mac), rssi)
            }
        }
        return Sweep(aps.values.toList(), devices.values.toList(), sub.values.toList())
    }

    /** Signal level: a labelled RSSI wins, otherwise the first negative number in range. */
    private fun rssiOf(line: String): Int? {
        val labelled = RSSI_LABELLED.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (labelled != null && labelled in -127..0) return labelled
        for (m in RSSI_PLAIN.findAll(line)) {
            val v = m.groupValues[1].toIntOrNull() ?: continue
            if (v in -127..-5) return v
        }
        return null
    }

    /**
     * SSID: a labelled one is taken as printed. An unlabelled sweep line only names itself
     * after the address, so whatever follows the MAC is used when it looks like a name.
     */
    private fun ssidOf(line: String, mac: String): String {
        SSID_LABELLED.find(line)?.groupValues?.getOrNull(1)?.let { return clean(it) }
        val after = line.substringAfter(mac, "")
        return clean(after)
    }

    /** BLE has no SSID; the advertiser's name sits either side of the address. */
    private fun nameOf(line: String, mac: String): String {
        SSID_LABELLED.find(line)?.groupValues?.getOrNull(1)?.let { return clean(it) }
        NAME_LABELLED.find(line)?.groupValues?.getOrNull(1)?.let { return clean(it) }
        val after = clean(line.substringAfter(mac, ""))
        if (after.isNotBlank()) return after
        return clean(line.substringBefore(mac))
    }

    /** Strips the punctuation and field labels a console line wraps around a value. */
    private fun clean(value: String): String = value
        .replace(Regex("""(?i)\b(?:rssi|signal|dbm|ch|chan|channel|bssid|mac|enc|auth|security)\b\s*[:=]?"""), " ")
        .replace(Regex("""[-+]?\d+(?:\.\d+)?"""), " ")
        .replace(Regex("""[\[\]{}()<>,;:|/\\*\"']"""), " ")
        .trim()
        .replace(Regex("""\s{2,}"""), " ")
        .take(32)

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
