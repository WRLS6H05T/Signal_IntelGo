package dev.skyward.rf.wardrive

import dev.skyward.rf.core.RfObservation
import kotlinx.serialization.Serializable

/**
 * One logged fix: where the vehicle was, what it could hear, and how strong the best
 * signal was. [score] is the strongest single Wi-Fi/BLE signal heard at the fix (0..100)
 * and is what colours the path; [avgScore] is kept for the histogram and exports.
 */
@Serializable
data class WardrivePoint(
    val lat: Double,
    val lon: Double,
    val t: Long,
    val score: Int,
    val avgScore: Int,
    val wifiCount: Int,
    val bleCount: Int,
    val bestWifi: String = "",
    val bestWifiRssi: Int = -127,
    val bestBle: String = "",
    val bestBleRssi: Int = -127,
    val accuracyM: Float = 0f,
    val networks: List<RfObservation> = emptyList(),
    val loggedSats: Int = 0,
    val loggedAircraft: Int = 0,
    val loggedDrones: Int = 0,
    val loggedMesh: Int = 0,
)

@Serializable
data class WardriveSession(
    val id: String,
    val name: String,
    val startedAt: Long,
    val endedAt: Long = 0L,
    val points: List<WardrivePoint> = emptyList(),
    val distanceM: Double = 0.0,
    val wifiAps: Int = 0,
    val bleDevices: Int = 0,
    val bestScore: Int = 0,
    /** Counts per colour bin, weak -> strong. */
    val binCounts: List<Int> = List(5) { 0 },
    val droneCount: Int = 0,
    val loggedSats: Int = 0,
    val loggedAircraft: Int = 0,
    val loggedMesh: Int = 0,
    /** The phone this drive came from, when it was sent over the peer link. */
    val from: String = "",
) {
    val durationMs: Long get() = if (endedAt > 0) (endedAt - startedAt).coerceAtLeast(0) else 0L
    val km: Double get() = distanceM / 1000.0
}

/** How many sky entities were captured during a fix. */
data class LoggedCounts(
    val sats: Int = 0,
    val aircraft: Int = 0,
    val drones: Int = 0,
    val mesh: Int = 0,
)

/** Lightweight row for the session list. */
data class SessionSummary(
    val id: String,
    val name: String,
    val startedAt: Long,
    val endedAt: Long,
    val points: Int,
    val km: Double,
    val wifiAps: Int,
    val bleDevices: Int,
    val bestScore: Int,
    val from: String = "",
)
