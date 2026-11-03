package dev.skyward.rf.core

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Spherical + Web-Mercator helpers shared by the map and the RF engines. */
object Geo {
    const val EARTH_R_M = 6371008.8

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * PI / 180.0
        val p2 = lat2 * PI / 180.0
        val dp = (lat2 - lat1) * PI / 180.0
        val dl = (lon2 - lon1) * PI / 180.0
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_R_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * PI / 180.0
        val p2 = lat2 * PI / 180.0
        val dl = (lon2 - lon1) * PI / 180.0
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return ((atan2(y, x) * 180.0 / PI) + 360.0) % 360.0
    }

    // ---- Web Mercator (normalized 0..1 world coordinates) ----

    fun lonToNorm(lon: Double): Double = (lon + 180.0) / 360.0

    fun latToNorm(lat: Double): Double {
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        val rad = clamped * PI / 180.0
        val y = ln(tan(rad) + 1.0 / cos(rad))
        return (1.0 - y / PI) / 2.0
    }

    fun normToLon(nx: Double): Double = nx * 360.0 - 180.0

    fun normToLat(ny: Double): Double {
        val y = (1.0 - 2.0 * ny) * PI
        return 180.0 / PI * atan(sinh(y))
    }

    private fun sinh(x: Double): Double = (kotlin.math.exp(x) - kotlin.math.exp(-x)) / 2.0

    /** Converts dBm to a 0..100 "link quality" number for the given radio band. */
    fun rssiToScore(dbm: Int): Int = (((dbm + 95).toDouble() / 55.0) * 100.0).toInt().coerceIn(0, 100)

    /**
     * Link budget for a Mode-S/ADS-B aircraft (1090 MHz).
     *
     * Transponders run high power (roughly 60 dBm EIRP) and receivers are sensitive to
     * about -90 dBm, which is why aircraft are decodable out to the radio horizon. FSPL at
     * 1090 MHz is 32.44 + 20log10(1090) + 20log10(km) = 93.19 + 20log10(km) dB, so received
     * power is EIRP - FSPL.
     */
    fun aircraftScore(distanceKm: Double): Int {
        if (distanceKm <= 0.5) return 100
        val fspl = 32.44 + 20.0 * kotlin.math.log10(1090.0) + 20.0 * kotlin.math.log10(distanceKm)
        val dbm = (60.0 - fspl).toInt()
        return rssiToScore(dbm)
    }

    /** Satellites are "strongest" overhead; low over the horizon is heavily attenuated. */
    fun satelliteScore(elevationDeg: Double): Int = (elevationDeg.coerceIn(0.0, 90.0) / 90.0 * 100.0).toInt()
}
