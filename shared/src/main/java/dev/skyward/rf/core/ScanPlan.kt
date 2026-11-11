package dev.skyward.rf.core

/**
 * What a linked radio has been told to sweep.
 *
 * A phone and a board are not the same radio: an ESP32-C5 can hear 5 GHz where a classic
 * ESP32 cannot, a board without a GPS module has nothing to contribute on that front, and a
 * phone is dual-band by default. The code is one small string - band, optionally `+ble`, plus
 * a `+gps` suffix - so it survives the BLE link, the settings file and a display label without
 * a dozen enums.
 *
 * A board carries two radios, so the modes that mean anything are the four a board can actually
 * be given: BLE on its own, 2.4 GHz Wi-Fi, both Wi-Fi bands, and BLE alongside both bands. 5 GHz
 * alone is not one of them: a board that hears 5 GHz hears 2.4 GHz as well, so a 5-only
 * assignment would be a way to miss networks it was already hearing.
 *
 * These belong to a board on the cable, and to nothing else: a peer link does not assign work
 * to another phone - both ends sweep with their own radios and share what they find.
 */
object ScanPlan {

    const val W24 = "w24"
    const val DUAL = "dual"
    const val W5 = "w5"
    const val BLE = "ble"

    /** Marks a Wi-Fi mode as also sweeping BLE: `dual+ble`. */
    const val BLE_SUFFIX = "+ble"

    private const val GPS = "+gps"

    /** The band part of a code, without the BLE and GPS suffixes. */
    fun band(code: String): String = code.substringBefore('+')

    fun hasGps(code: String): Boolean = code.contains(GPS)

    /** True when this plan sweeps BLE, either on its own or beside a band. */
    fun hasBle(code: String): Boolean = band(code) == BLE || code.contains(BLE_SUFFIX)

    /** True when this plan sweeps Wi-Fi at all: a BLE-only plan must not start an AP scan. */
    fun hasWifi(code: String): Boolean = band(code) != BLE

    /** Same band and BLE, GPS on or off. */
    fun withGps(code: String, on: Boolean): String {
        val base = code.substringBefore(GPS).ifBlank { W24 }
        return if (on) base + GPS else base
    }

    /**
     * The sweeps this board can be given.
     *
     * [dualBand] is the silicon talking, not the USB product string, and [ble] is only offered
     * once the firmware has shown it sweeps BLE at all - assigning a radio the board does not
     * have is a switch that silently returns nothing.
     */
    fun bandOptions(dualBand: Boolean, ble: Boolean): List<String> = buildList {
        if (ble) add(BLE)
        add(W24)
        if (dualBand) {
            add(DUAL)
            if (ble) add(DUAL + BLE_SUFFIX)
        }
    }

    fun label(code: String): String {
        val base = when (band(code)) {
            DUAL -> "WI-FI 2.4+5"
            W24 -> "WI-FI 2.4"
            // Kept for a card saved before BLE had its own mode, so an old assignment still
            // reads as what it is instead of "NOT SET".
            W5 -> "WI-FI 5"
            BLE -> "BLE"
            else -> "NOT SET"
        }
        val ble = if (hasBle(code) && band(code) != BLE) " + BLE" else ""
        val gps = if (hasGps(code)) " + GPS" else ""
        return base + ble + gps
    }

    /**
     * What a board nobody has assigned sweeps: the Wi-Fi bands it can hear.
     *
     * BLE is never the default. A card that has not been touched has to be doing the sweep that
     * puts networks on the map; defaulting a board to BLE left it hearing nothing but advertisers
     * while its card said it was scanning, which reads as the scans never having run.
     */
    fun defaultBand(dualBand: Boolean): String = if (dualBand) DUAL else W24
}
