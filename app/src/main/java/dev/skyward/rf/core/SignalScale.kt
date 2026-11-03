package dev.skyward.rf.core

/**
 * The wardrive colour language.
 *
 * Path segments and RF dots are painted on a 5-bin red -> green scale ordered by the
 * strongest signal logged at that fix (weak = red, strong = green), using the classic
 * RdYlGn 5-class palette so the two ends stay distinguishable in sunlight.
 *
 * Entities that were captured during a logging session (satellites, aircraft, drones,
 * mesh nodes) are painted in [loggedColor] instead of their layer colour, so "logged"
 * reads instantly on the map.
 */
object SignalScale {

    /** Weak -> strong. */
    val binArgb: IntArray = intArrayOf(
        0xFFD73027.toInt(), // very weak   (red)
        0xFFF46D43.toInt(), // weak        (orange)
        0xFFFDAE61.toInt(), // fair        (amber)
        0xFFA6D96A.toInt(), // strong      (yellow-green)
        0xFF1A9850.toInt(), // very strong (green)
    )

    val binLabels = arrayOf("Very weak", "Weak", "Fair", "Strong", "Very strong")
    val binDbm = arrayOf("< -85 dBm", "-85..-75", "-75..-65", "-65..-55", "> -55 dBm")
    val binFractions = arrayOf("0-20", "20-40", "40-60", "60-80", "80-100")

    /** Colour used for any entity that has been logged during a drive. */
    const val LOGGED_COLOR: Int = 0xFFC084FC.toInt()

    const val BIN_COUNT = 5

    /** Score (0..100) -> bin index 0..4. */
    fun binOfScore(score: Int): Int = when {
        score < 20 -> 0
        score < 40 -> 1
        score < 60 -> 2
        score < 80 -> 3
        else -> 4
    }

    fun colorOfScore(score: Int): Int = binArgb[binOfScore(score)]

    fun colorOfDbm(dbm: Int): Int = colorOfScore(Geo.rssiToScore(dbm))

    /** Score for a set of observations: the strongest radio wins the point. */
    fun peakScore(observations: Collection<RfObservation>): Int =
        observations.maxOfOrNull { Geo.rssiToScore(it.rssi) } ?: 0

    fun meanScore(observations: Collection<RfObservation>): Int =
        if (observations.isEmpty()) 0 else observations.sumOf { Geo.rssiToScore(it.rssi) } / observations.size
}
