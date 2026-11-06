package dev.skyward.rf.core

import dev.skyward.rf.meshcore.MeshCore

import kotlinx.serialization.Serializable

/**
 * Shared value types for the app.
 *
 * Persisted types are @Serializable; live "view" types are plain data classes that are
 * recomputed on every refresh tick.
 */

@Serializable
data class LatLon(val lat: Double, val lon: Double)

/**
 * Which radio the observation came from.
 *
 * SUBGHZ is a CC1101 on a wired GhostESP or Marauder rather than a phone radio: it hears
 * keyfobs, sensors and gate remotes, which have no address to sweep for, so its identity is the
 * frequency the carrier appeared on.
 */
@Serializable
enum class RfKind { WIFI, BLE, SUBGHZ }

/** One Wi-Fi BSSID or BLE advertiser heard at a moment in time. */
@Serializable
data class RfObservation(
    val kind: RfKind,
    val id: String,
    val name: String,
    /** dBm, typically -30 (right next to it) .. -100 (edge of range). */
    val rssi: Int,
    val channel: Int = 0,
    val security: String = "",
    val freqMhz: Int = 0,
    val seenAt: Long = 0L,
    /** "local", "peer" or "board": which radio heard it, so each can be placed at its own fix. */
    val source: String = "local",
)

/** Airspace layers that can be "logged" during a drive. */
@Serializable
enum class Layer { SATELLITE, AIRCRAFT, DRONE, MESH, CELL, ESP32, WATCH }

data class SatView(
    val noradId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val altKm: Double,
    val elevation: Double,
    val azimuth: Double,
    val rangeKm: Double,
    /** 0..100 estimated signal quality (elevation driven), used for the logged readout. */
    val score: Int,
    val logged: Boolean = false,
)

data class AircraftView(
    val hex: String,
    val callSign: String,
    val reg: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    val altFt: Int?,
    val groundSpeedKt: Double,
    val trackDeg: Double,
    val verticalRateFpm: Int,
    val squawk: String,
    val category: String,
    val distanceKm: Double,
    /** 0..100 rough link budget from distance (1090 MHz free-space path loss). */
    val score: Int,
    val logged: Boolean = false,
)

data class DroneView(
    val mac: String,
    val uasId: String,
    val operatorId: String,
    val lat: Double?,
    val lon: Double?,
    val altM: Double?,
    val heightM: Double?,
    val speedMs: Double?,
    val headingDeg: Double?,
    val rssi: Int,
    val lastSeen: Long,
    val distanceKm: Double?,
    /** 0..100 from BLE RSSI. */
    val score: Int,
    val logged: Boolean = false,
)

data class MeshNode(
    val id: String,
    val name: String,
    val lat: Double?,
    val lon: Double?,
    val altitudeM: Int = 0,
    /** LoRa SNR in dB, -20..+12. */
    val snr: Double = 0.0,
    val hops: Int = 0,
    val batteryPct: Int = -1,
    val lastSeen: Long = 0L,
    val source: String = "mqtt",
    val rssi: Int? = null,
    /** MeshCore role when the node advertised one; Meshtastic nodes stay UNKNOWN. */
    val role: MeshCore.Role = MeshCore.Role.UNKNOWN,
    /** Hex public key for a MeshCore node: its identity, and the first byte is its hash. */
    val pubkey: String = "",
    val distanceKm: Double? = null,
    /** 0..100 from LoRa SNR (or BLE RSSI when the node is only heard locally). */
    val score: Int = 0,
    val logged: Boolean = false,
)

/**
 * An ESP32 sensor board, heard over BLE or publishing over MQTT.
 *
 * A board without a GPS module has no position of its own and is placed at the observer,
 * so [sats] is what tells you whether [lat]/[lon] are the board's own fix or a stand-in.
 */
data class Esp32Node(
    val id: String,
    val name: String,
    val lat: Double?,
    val lon: Double?,
    val altM: Int? = null,
    val sats: Int = 0,
    val hdop: Double? = null,
    val batteryPct: Int = -1,
    val wifiCount: Int = 0,
    val bleCount: Int = 0,
    /** True when the board reports a CC1101: the sub-GHz radio is fitted, not just supported. */
    val subGhz: Boolean = false,
    /** Sub-GHz carriers the board's last sweep caught, not how many it has ever seen. */
    val subGhzHits: Int = 0,
    val moving: Boolean = false,
    val firmware: String = "",
    /** Firmware family when the board identified itself, e.g. GhostESP or ESP32 Marauder. */
    val board: String = "",
    /** USB product string or bridge chip, when the board is plugged in. */
    val chip: String = "",
    val uptimeS: Long = 0L,
    val rssi: Int? = null,
    /** "ble" when heard directly, "mqtt" when it published over Wi-Fi, "usb" when wired. */
    val source: String = "ble",
    val lastSeen: Long = 0L,
    val distanceKm: Double? = null,
    /** 0..100 from GNSS quality, or from RSSI when the board has no fix. */
    val score: Int = 0,
    val logged: Boolean = false,
) {
    /**
     * True when the board identifies a dual-band radio. Classic ESP32 and S3 are 2.4 GHz only;
     * the C5 is the one that hears 5 GHz, and a board that says so is offered the choice.
     */
    val dualBand: Boolean
        get() = listOf(chip, board, firmware).any {
            it.contains("C5", ignoreCase = true) ||
                it.contains("dual", ignoreCase = true) ||
                it.contains("5GHz", ignoreCase = true) ||
                it.contains("5 GHz", ignoreCase = true)
        }

    /** A GPS module that has actually reported something. */
    val hasGps: Boolean get() = sats > 0 || hdop != null

    /** BLE sweeping is only offered when the firmware has shown it sweeps BLE at all. */
    val hasBle: Boolean get() = bleCount > 0 || board.isNotBlank() || firmware.isNotBlank()
}

data class CellView(
    val key: String,
    val radio: String,
    val operator: String,
    val mcc: String,
    val mnc: String,
    val area: Int,
    val cellId: Long,
    val dbm: Int,
    /** Where the tower was first heard: Android exposes no mast coordinates. */
    val lat: Double,
    val lon: Double,
    val lastSeen: Long,
    val registered: Boolean,
    val score: Int,
    val logged: Boolean = false,
)

/**
 * A matched piece of surveillance hardware.
 *
 * A handset radio reports no bearing, so [lat]/[lon] are the observer's fix - the place the
 * device was heard from - nudged a few metres so several hits do not draw as one dot. That is
 * the honest answer: "here, and somewhere within radio range", not a triangulated position.
 */
data class WatchView(
    val kind: WatchKind,
    val id: String,
    val name: String,
    val radio: RfKind,
    val rssi: Int,
    val source: String,
    val lat: Double,
    val lon: Double,
    val score: Int,
    val logged: Boolean = false,
    val lastSeen: Long = 0L,
    /** How the match was made, e.g. "name + manufacturer id". */
    val confidence: String = "",
)

/** Health of a network feed, surfaced in the HUD so silent failures are visible. */
data class FeedStatus(
    val ok: Boolean? = null,
    val detail: String = "",
    val at: Long = 0L,
    val count: Int = 0,
)

/** An entity captured while a wardrive session was running. */
@Serializable
data class LoggedEntity(
    val key: String,
    val layer: Layer,
    val name: String,
    val lat: Double,
    val lon: Double,
    val firstSeen: Long,
    val lastSeen: Long,
    val sightings: Int = 1,
)

fun LatLon.pretty(): String = String.format("%.5f, %.5f", lat, lon)
