package dev.skyward.rf.core

/**
 * Security, as a Wi-Fi advertisement states it.
 *
 * Firmware writes this in half a dozen ways - `WPA2_PSK`, `WPA2-PSK`, `[WPA2-PSK-CCMP][ESS]`,
 * `NONE`, `OPEN` - so the raw string is collapsed to one of the labels a person reads, and the
 * open check is a property of the label rather than of the string: OWE is encrypted but has no
 * password, which is open for the purpose of knowing a network lets anyone in.
 */
object WifiSecurity {

    const val UNKNOWN = "not reported"
    const val OPEN = "OPEN"
    const val OWE = "OWE"
    const val WEP = "WEP"
    const val WPA = "WPA"
    const val WPA2 = "WPA2"
    const val WPA3 = "WPA3"

    fun label(raw: String): String {
        val s = raw.lowercase()
        return when {
            s.isBlank() -> UNKNOWN
            s.contains("owe") || s.contains("enhanced open") -> OWE
            // SAE is WPA3's handshake, and firmware that prints it rarely prints "WPA3" too.
            s.contains("wpa3") || s.contains("sae") -> WPA3
            s.contains("wpa2") -> WPA2
            s.contains("wep") -> WEP
            s.contains("wpa") -> WPA
            s.contains("open") || s.contains("none") -> OPEN
            else -> raw.trim().uppercase().take(12)
        }
    }

    /** True for a network with no key: open, or OWE's passwordless encryption. */
    fun isOpen(raw: String): Boolean = label(raw) == OPEN || label(raw) == OWE
}
