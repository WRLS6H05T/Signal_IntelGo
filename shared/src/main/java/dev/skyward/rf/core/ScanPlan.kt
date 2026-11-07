package dev.skyward.rf.core

/**
 * What a linked radio has been told to sweep.
 *
 * A phone and a board are not the same radio: an ESP32-C5 can hear 5 GHz where a classic
 * ESP32 cannot, a board without a GPS module has nothing to contribute on that front, and a
 * phone is dual-band by default. The code is one small string - band, plus a `+gps` suffix -
 * so it survives the BLE link, the settings file and a display label without a dozen enums.
 *
 * These belong to a board on the cable, and to nothing else: a peer link does not assign work
 * to another phone - both ends sweep with their own radios and share what they find.
 */
object ScanPlan {

    const val W24 = "w24"
    const val DUAL = "dual"
    const val W5 = "w5"
    const val BLE = "ble"
    private const val GPS = "+gps"

    /** The band part of a code, without the GPS suffix. */
    fun band(code: String): String = code.substringBefore('+')

    fun hasGps(code: String): Boolean = code.contains(GPS)

    /** Same band, GPS on or off. */
    fun withGps(code: String, on: Boolean): String {
        val base = band(code).ifBlank { W24 }
        return if (on) base + GPS else base
    }

    /**
     * Bands this radio can actually take.
     *
     * 5 GHz is only offered on a dual-band device: an ESP32 without a C5 cannot hear it, and
     * offering it would be an assignment that silently returns nothing.
     */
    fun bandOptions(dualBand: Boolean, ble: Boolean): List<String> = buildList {
        if (dualBand) {
            add(DUAL)
            add(W24)
            add(W5)
        } else {
            add(W24)
        }
        if (ble) add(BLE)
    }

    fun label(code: String): String = when (band(code)) {
        DUAL -> "WI-FI 2.4+5"
        W24 -> "WI-FI 2.4"
        W5 -> "WI-FI 5"
        BLE -> "BLE"
        else -> "NOT SET"
    } + if (hasGps(code)) " + GPS" else ""

    fun defaultBand(dualBand: Boolean, ble: Boolean): String = when {
        dualBand -> DUAL
        ble -> BLE
        else -> W24
    }
}
