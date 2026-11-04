package dev.skyward.rf.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** User-tunable behaviour, persisted as JSON. */
@Serializable
data class Settings(
    val satGroups: List<String> = listOf("stations", "visual", "weather"),
    val aircraftEnabled: Boolean = true,
    val aircraftEndpoint: String = "https://api.adsb.lol/v2/point",
    val meshMqttEnabled: Boolean = false,
    val meshBroker: String = "ssl://mqtt.meshtastic.org:8883",
    val meshTopic: String = "msh/US/2/json/#",
    val meshUser: String = "meshdev",
    val meshPass: String = "large4cats",
    /**
     * "streets" (OpenStreetMap) or "dark" (OSM tiles darkened at draw time). OSM is the default: it is keyless and
     * serves full-detail tiles, and the canvas desaturates them anyway.
     */
    val tileSource: String = "streets",
    /**
     * Off: the greyscale basemap is drawn in-app. On: the canvas plots only the live layers
     * and street detail is handed to the phone's own map app, with no tile requests at all.
     */
    val useDeviceMap: Boolean = false,
    val refreshSatMs: Long = 5_000,
    val refreshAircraftMs: Long = 12_000,
    /** Satellites/aircraft closer than this are captured while a drive is recording. */
    val logRadiusKm: Double = 250.0,
    val dropWeakDots: Boolean = false,
    /** Globe auto-spin as a speed step: 0 off, 1 slow, 2 fast. */
    val globeSpin: Int = 1,
    /** ESP32 sensor boards: BLE advertisements always, MQTT when a Wi-Fi board publishes. */
    val esp32Enabled: Boolean = true,
    /** GhostESP, ESP32 Marauder, Biscuit and anything else ESP32 plugged into the USB port. */
    val esp32UsbEnabled: Boolean = true,
    val esp32MqttEnabled: Boolean = false,
    val esp32Broker: String = "tcp://broker.hivemq.com:1883",
    val esp32Topic: String = "skyward/esp32/#",
    /** Let a board's own GPS stand in while the phone has no fix of its own. */
    val esp32GpsFallback: Boolean = true,
    /**
     * Serial speed for the board on the cable. A GhostESP or Marauder flashed at another rate
     * answers a 115200 probe with noise, which reads exactly like a board that will not scan.
     */
    val esp32Baud: Int = 115200,
    /** Share scans and GPS with a second phone on the same Wi-Fi or hotspot. */
    val peerEnabled: Boolean = false,
    /** Peer address to dial when broadcast discovery is blocked; blank means find it. */
    val peerHost: String = "",
    /** WiGLE account API name and token, used only by the CSV upload button. */
    val wigleName: String = "",
    val wigleToken: String = "",
)

class SettingsStore(private val store: FileStore) {
    private val file = "settings_v1.json"
    private val _state = MutableStateFlow(store.read<Settings>(file) ?: Settings())

    val state: StateFlow<Settings> = _state
    val current: Settings get() = _state.value

    fun update(block: (Settings) -> Settings) {
        val next = block(current)
        _state.value = next
        store.write(file, next)
    }
}

val appJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}
