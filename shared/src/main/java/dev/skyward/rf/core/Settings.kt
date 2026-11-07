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
     * MeshCore (WADAMESH and the MQTT observer bridges): a second feed on its own broker,
     * because the observers publish hex packets rather than Meshtastic JSON.
     */
    val meshCoreEnabled: Boolean = false,
    val meshCoreBroker: String = "tcp://broker.hivemq.com:1883",
    /** Observer layout: `meshcore/{IATA}/{device}/packets`, where `raw` holds the hex packet. */
    val meshCoreTopic: String = "meshcore/+/+/packets",
    /**
     * The device address typed for WADAMESH direct transfer. The session code is deliberately
     * absent: it is minted per session, shown on the device, and worthless afterwards.
     */
    val wadaHost: String = "",
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
    /**
     * The phone's own GNSS receiver. Off means stopped, not muted: nothing keeps waking the
     * chip for fixes nothing reads. The map keeps the last position it had.
     */
    val gpsEnabled: Boolean = true,
    /**
     * Offline mode: the GPS constellation and nothing else, from the element sets and map tiles
     * already on the phone. No satellite, aircraft, mesh, peer or weather request is made, so a
     * drive out of signal shows a sky and a map instead of an empty screen.
     */
    val offlineMode: Boolean = false,
    val refreshSatMs: Long = 5_000,
    val refreshAircraftMs: Long = 12_000,
    val dropWeakDots: Boolean = false,
    /** Globe auto-spin as a speed step: 0 off, 1 = 1x, 2 = 3x. */
    val globeSpin: Int = 1,
    /**
     * The colour a logged satellite takes when it becomes an entity: "purple" by default, or
     * one of the presets in [dev.skyward.rf.ui.EntityColors]. Other layers keep purple.
     */
    val satEntityColor: String = "purple",
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
    /**
     * Minutes between the Watcher's own intel updates: 0 off, otherwise 3, 5, 7, 10 or 30. The
     * update is the INTEL tab read down, plus whatever has been sighted since the last one.
     */
    val intelUpdateMinutes: Int = 0,
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
    /**
     * Sub-GHz sweeps on a wired board whose firmware has a CC1101. Off, no sub-GHz command is
     * sent and no carrier is logged; the board is otherwise polled exactly as before.
     */
    val subGhzEnabled: Boolean = true,
    /**
     * The sub-GHz sweep command, because the verb differs between GhostESP builds and Marauder
     * revisions and there is no way to ask a firmware which one it wants.
     */
    val subGhzCommand: String = "subghz rx",
    /**
     * RTL-SDR dongle: the spectrum drop-down on the map, its recording and its pins. Off, the
     * dongle is left alone - not opened, not recorded from, and no panel is drawn.
     */
    val sdrEnabled: Boolean = false,
    /** Tuned centre for the dongle, in MHz. Ignored below 24 MHz or above 1766 MHz. */
    val sdrCenterMhz: Double = 433.92,
    /** Which GPIO the board's GPS module transmits on, set from the in-app serial terminal. */
    val espGpsPin: Int = 4,
    /** The GPS module's own rate, which is not the console's: 9600 is the usual NMEA default. */
    val espGpsBaud: Int = 9600,
    /** Share scans and GPS with a second phone on the same Wi-Fi or hotspot. */
    val peerEnabled: Boolean = false,
    /** Peer address to dial when broadcast discovery is blocked; blank means find it. */
    val peerHost: String = "",
    /** Scan job per board, keyed by the board's id. See ScanPlan: the peer link assigns none. */
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
