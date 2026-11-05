package dev.skyward.rf.core

import java.util.Locale

/**
 * Distance and temperature in the units the reader thinks in.
 *
 * Everything is stored and computed metric - kilometres, metres, Celsius - because the GPS
 * engine, the satellite maths, the peer frames and the weather feed all speak one language.
 * Conversion therefore happens only where a value is written on screen, so switching the unit
 * never rewrites a saved drive, never changes what a peer receives, and can never make two
 * parts of the screen disagree about what a number means.
 */
object Units {
    const val KM = "km"
    const val MILES = "mi"
    const val CELSIUS = "c"
    const val FAHRENHEIT = "f"

    private const val KM_PER_MILE = 1.609344
    private const val FT_PER_METRE = 3.280839895
    private const val MM_PER_INCH = 25.4

    fun miles(unit: String): Boolean = unit == MILES

    /** "12.4 km" or "7.7 mi". The decimals stay the caller's: a satellite and a street differ. */
    fun distance(km: Double, unit: String, decimals: Int = 1): String =
        if (miles(unit)) {
            String.format(Locale.US, "%.${decimals}f mi", km / KM_PER_MILE)
        } else {
            String.format(Locale.US, "%.${decimals}f km", km)
        }

    fun temperature(celsius: Double?, unit: String): String {
        val c = celsius ?: return "\u2014"
        return if (unit == FAHRENHEIT) {
            String.format(Locale.US, "%.1f \u00b0F", c * 9.0 / 5.0 + 32.0)
        } else {
            String.format(Locale.US, "%.1f \u00b0C", c)
        }
    }

    /** Wind follows the distance unit: mph with miles, km/h with kilometres. */
    fun wind(kmh: Double?, unit: String): String {
        val v = kmh ?: return "\u2014"
        return if (miles(unit)) {
            String.format(Locale.US, "%.0f mph", v / KM_PER_MILE)
        } else {
            String.format(Locale.US, "%.0f km/h", v)
        }
    }

    /** Height: metres, or feet for someone thinking in miles. */
    fun elevation(metres: Double?, unit: String): String {
        val m = metres ?: return "\u2014"
        return if (miles(unit)) {
            String.format(Locale.US, "%.0f ft", m * FT_PER_METRE)
        } else {
            String.format(Locale.US, "%.0f m", m)
        }
    }

    /** Rain depth, same reasoning as the rest: millimetres, or inches. */
    fun rain(mm: Double?, unit: String): String {
        val v = mm ?: return "\u2014"
        return if (miles(unit)) {
            String.format(Locale.US, "%.2f in", v / MM_PER_INCH)
        } else {
            String.format(Locale.US, "%.1f mm", v)
        }
    }

    fun distanceLabel(unit: String): String = if (miles(unit)) "mi" else "km"

    fun temperatureLabel(unit: String): String = if (unit == FAHRENHEIT) "\u00b0F" else "\u00b0C"
}
