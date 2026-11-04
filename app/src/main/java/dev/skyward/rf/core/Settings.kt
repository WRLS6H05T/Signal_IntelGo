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
    val dropWeakDots: Boolean = false,
    /** Globe auto-spin as a speed step: 0 off, 1 slow, 2 fast. */
    val globeSpin: Int = 1,
    /**
     * A running clock in the map's top-right corner; tapping it opens the weather at the GPS
     * pin. Off, nothing is drawn and the weather feed is never asked.
     */
    val showLiveTime: Boolean = true,
    /**
     * Seconds under the clock. Off, the hour and minute are drawn larger in the same corner -
     * which is the size that reads at arm's length on a dash mount.
     */
    val clockSeconds: Boolean = true,
    /** "km" or "mi": what every distance on screen is written in. Stored data stays metric. */
    val distanceUnit: String = "km",
    /** "c" or "f": the weather drop-down's temperature. */
    val tempUnit: String = "c",
    /** Hands-free Watcher: listen for the wake word and obey the spoken commands. */
    val voiceEnabled: Boolean = false,
    /** "female" or "male": which machine voice answers. */
    val voiceGender: String = "female",
    /** Speak the replies and the watch alerts out loud. */
    val voiceAudio: Boolean = true,
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
    /**
     * Link role: "auto" lets screen size decide, "host" takes the globe and pushes its
     * settings to every linked device, "globe" is a view-only globe that follows the host.
     */
    val peerRoleMode: String = "auto",
    /** While this phone is a globe view, apply the host's settings as they arrive. */
    val followHost: Boolean = true,
    /** Scan job per radio: keyed by ScanPlan.PEER_KEY or a board id. See ScanPlan. */
    val scanAssign: Map<String, String> = emptyMap(),
    /** WiGLE account API name and token, used only by the CSV upload button. */
    val wigleName: String = "",
    val wigleToken: String = "",
)

/**
 * Fixed capture radius for satellites, in kilometres.
 *
 * It was a slider, and a slider invited a number large enough to catch nearly the whole
 * catalogue on every tick - which is not "overhead", it is the map. 150 km is a pass you can
 * actually work, and it is the same number the engine logs out to.
 */
const val LOG_RADIUS_KM: Double = 150.0

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
