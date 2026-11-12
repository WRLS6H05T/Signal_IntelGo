package dev.skyward.rf.desktop.hardware

import dev.skyward.rf.scan.BoardFamily

/**
 * What a board's own console says it is.
 *
 * The desk reads the same banner the phone reads, and reads it the same way: only the text is
 * trusted, because every USB vendor id involved (Espressif, SiLabs, WCH, FTDI) is shared by
 * millions of ordinary dev boards. Silence is not a failure - a board mid-sweep prints nothing
 * for a while - so [from] returns null rather than inventing an identity.
 */
data class SensorIdentity(
    val family: BoardFamily,
    val chip: String?,
    val firmware: String?,
    /** The console line the identity was read from, kept so the card can show its source. */
    val banner: String,
) {

    /** The C5 is the only mini board with a 5 GHz radio, so the chip decides the band menu. */
    val dualBand: Boolean get() = chip?.contains("C5", ignoreCase = true) == true

    /** Every family with a console can be told to sweep BLE; Biscuit has no CLI at all. */
    val hasBle: Boolean get() = family != BoardFamily.BISCUIT

    val name: String
        get() = when (family) {
            BoardFamily.SIGNALSENSOR -> "SignalSensor"
            BoardFamily.GHOSTESP -> "GhostESP"
            BoardFamily.MARAUDER -> "Marauder"
            BoardFamily.BISCUIT -> "Biscuit"
            BoardFamily.ESP32 -> "ESP32"
        }

    companion object {

        private val CHIP = Regex("""(?i)\besp32[\s_-]?(c2|c3|c5|c6|s2|s3|wroom|pico)\b""")

        /** A plain `ESP32` with no silicon suffix: recognised, but no band claim is made. */
        private val GENERIC = Regex("""(?i)\besp32\b""")

        private val VERSION = Regex("""(?i)\bv(\d+\.\d+(?:\.\d+)?)\b""")
        private val VERSION_LABELLED = Regex(
            """(?i)\b(?:firmware|fw|version)\s*(?:=|:)?\s*v?(\d+\.\d+(?:\.\d+)?)\b""",
        )

        /** Reads a transcript's best claim about the board, or null when it made none. */
        fun from(text: String): SensorIdentity? {
            val line = text.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && (CHIP.containsMatchIn(it) || GENERIC.containsMatchIn(it) || FAMILY_WORDS.containsMatchIn(it)) }
                ?: return null
            val family = when {
                FAMILY_SENSOR.containsMatchIn(text) -> BoardFamily.SIGNALSENSOR
                FAMILY_GHOST.containsMatchIn(text) -> BoardFamily.GHOSTESP
                FAMILY_MARAUDER.containsMatchIn(text) -> BoardFamily.MARAUDER
                else -> BoardFamily.ESP32
            }
            val chip = CHIP.find(text)?.let { m ->
                "ESP32-" + m.groupValues[1].uppercase()
            } ?: if (GENERIC.containsMatchIn(text)) "ESP32" else null
            val firmware = VERSION_LABELLED.find(text)?.groupValues?.getOrNull(1)
                ?: VERSION.find(text)?.groupValues?.getOrNull(1)
            return SensorIdentity(family, chip, firmware, line.take(160))
        }

        private val FAMILY_WORDS = Regex("""(?i)\b(?:ghostesp|marauder|biscuit)\b""")

        /** The project's own firmware prints this on every boot and every `status`. */
        private val FAMILY_SENSOR = Regex("""(?i)\bsignal\s*intelgo\s*sensor\b""")

        private val FAMILY_GHOST = Regex("""(?i)\bghostesp\b""")

        private val FAMILY_MARAUDER = Regex("""(?i)\bmarauder\b""")
    }
}
