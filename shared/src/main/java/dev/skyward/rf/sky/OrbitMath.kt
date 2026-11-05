package dev.skyward.rf.sky

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Orbit geometry.
 *
 * Orekit's SGP4/SDP4 propagator hands back a position in TEME (True Equator, Mean
 * Equinox, metres). To put a satellite on a map we need a ground point and a
 * look angle, so we rotate TEME -> ECEF with Greenwich Mean Sidereal Time and then
 * solve the WGS-84 geodetic (lat, lon, height) and local ENU elevation/azimuth.
 *
 * The GMST polynomial below is the same IAU-82 expression satellite.js uses, which lets
 * us validate this chain numerically against that reference implementation.
 */
object OrbitMath {

    private const val WGS84_A = 6378.137 // km
    private const val WGS84_F = 1.0 / 298.257223563
    private const val WGS84_E2 = WGS84_F * (2.0 - WGS84_F)
    private const val DEG = 180.0 / PI
    private const val RAD = PI / 180.0

    /** Julian date from Unix epoch milliseconds. */
    fun julianDate(epochMillis: Long): Double = epochMillis / 86_400_000.0 + 2_440_587.5

    /** Greenwich Mean Sidereal Time in radians (IAU-82, matches satellite.js). */
    fun gmst(epochMillis: Long): Double {
        val jd = julianDate(epochMillis)
        val t = (jd - 2_451_545.0) / 36_525.0
        val seconds = 67_310.548_41 +
            (876_600.0 * 3600.0 + 8_640_184.812_866) * t +
            0.093_104 * t * t -
            6.2e-6 * t * t * t
        var theta = (seconds % 86_400.0 + 86_400.0) % 86_400.0
        theta = theta / 240.0 * RAD
        return theta
    }

    /** TEME position (metres) -> ECEF (kilometres). */
    fun temeToEcef(xm: Double, ym: Double, zm: Double, epochMillis: Long): DoubleArray {
        val theta = gmst(epochMillis)
        val x = xm / 1000.0
        val y = ym / 1000.0
        val z = zm / 1000.0
        val c = cos(theta)
        val s = sin(theta)
        return doubleArrayOf(x * c + y * s, -x * s + y * c, z)
    }

    /** WGS-84 geodetic from ECEF kilometres: (latDeg, lonDeg, altKm). */
    fun ecefToGeodetic(x: Double, y: Double, z: Double): DoubleArray {
        val lon = atan2(y, x)
        val r = sqrt(x * x + y * y)
        if (r < 1e-9) return doubleArrayOf(if (z >= 0) 90.0 else -90.0, 0.0, abs(z) - WGS84_A * (1 - WGS84_F))

        var lat = atan2(z, r * (1.0 - WGS84_E2))
        var alt = 0.0
        var n = WGS84_A
        for (i in 0 until 8) {
            n = WGS84_A / sqrt(1.0 - WGS84_E2 * sin(lat) * sin(lat))
            alt = r / cos(lat) - n
            val next = atan2(z, r * (1.0 - WGS84_E2 * n / (n + alt)))
            if (abs(next - lat) < 1e-12) {
                lat = next
                break
            }
            lat = next
        }
        return doubleArrayOf(lat * DEG, lon * DEG, alt)
    }

    /** Geodetic -> ECEF kilometres. */
    fun geodeticToEcef(latDeg: Double, lonDeg: Double, altKm: Double = 0.0): DoubleArray {
        val lat = latDeg * RAD
        val lon = lonDeg * RAD
        val n = WGS84_A / sqrt(1.0 - WGS84_E2 * sin(lat) * sin(lat))
        val x = (n + altKm) * cos(lat) * cos(lon)
        val y = (n + altKm) * cos(lat) * sin(lon)
        val z = (n * (1.0 - WGS84_E2) + altKm) * sin(lat)
        return doubleArrayOf(x, y, z)
    }

    /** Look angles from an observer to a target ECEF point: (elevationDeg, azimuthDeg, rangeKm). */
    fun lookAngles(
        obsLat: Double,
        obsLon: Double,
        targetEcef: DoubleArray,
        obsAltKm: Double = 0.0,
    ): DoubleArray {
        val o = geodeticToEcef(obsLat, obsLon, obsAltKm)
        val dx = targetEcef[0] - o[0]
        val dy = targetEcef[1] - o[1]
        val dz = targetEcef[2] - o[2]
        val lat = obsLat * RAD
        val lon = obsLon * RAD
        val east = -sin(lon) * dx + cos(lon) * dy
        val north = -sin(lat) * cos(lon) * dx - sin(lat) * sin(lon) * dy + cos(lat) * dz
        val up = cos(lat) * cos(lon) * dx + cos(lat) * sin(lon) * dy + sin(lat) * dz
        val range = sqrt(east * east + north * north + up * up)
        if (range < 1e-9) return doubleArrayOf(90.0, 0.0, 0.0)
        val el = asin((up / range).coerceIn(-1.0, 1.0)) * DEG
        var az = atan2(east, north) * DEG
        if (az < 0) az += 360.0
        return doubleArrayOf(el, az, range)
    }

    /** Great-circle distance in km. */
    fun groundDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * RAD
        val p2 = lat2 * RAD
        val dp = (lat2 - lat1) * RAD
        val dl = (lon2 - lon1) * RAD
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2.0 * 6371.0088 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
