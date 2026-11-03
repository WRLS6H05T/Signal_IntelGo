package dev.skyward.rf.sky

import dev.skyward.rf.core.Esp32Node
import dev.skyward.rf.core.FeedStatus
import dev.skyward.rf.core.Geo
import dev.skyward.rf.core.Settings
import dev.skyward.rf.scan.Esp32Codec
import dev.skyward.rf.scan.UsbBoards
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * ESP32 sensor boards.
 *
 * Three ways in, merged into one roster:
 *
 *  - **BLE**: a board advertises its payload (see [Esp32Codec]) and the scanner hands it over.
 *    Nothing is paired, so any number of boards work at once and a board can walk past
 *    without setup. Range is tens of metres, which is what makes it the "sensor next to me"
 *    path.
 *  - **USB**: a board on a cable is found by [UsbBoards], which identifies GhostESP, ESP32
 *    Marauder, Biscuit and bare ESP32 boards and keeps them in the roster for as long as they
 *    stay plugged in.
 *  - **MQTT**: a board with Wi-Fi publishes JSON; that path reaches boards anywhere the broker
 *    is reachable, and each packet carries the board's own GPS fix, which is how a remote
 *    sensor shows up on the map at its real position.
 *
 * A board without a GPS module is still useful: it reports battery and what it swept, and it
 * sits at the phone's position until it has a fix of its own.
 */
class Esp32Source(
    private val settings: () -> Settings,
    private val usb: UsbBoards? = null,
) {

    companion object {
        /** A board that stops advertising has probably gone out of range. */
        const val BLE_TTL_MS = 90_000L
        /** Remote boards publish slowly and may be behind a flaky link, so they linger. */
        const val MQTT_TTL_MS = 10 * 60_000L
        /** A wired board has no RSSI to score; a cable is as good as a link gets. */
        const val USB_SCORE = 72
    }

    private data class Entry(val node: Esp32Node, val seenAt: Long, val via: String)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val nodes = ConcurrentHashMap<String, Entry>()

    private val _state = MutableStateFlow<List<Esp32Node>>(emptyList())
    val state: StateFlow<List<Esp32Node>> = _state

    private val _status = MutableStateFlow(FeedStatus(null, "idle", 0L, 0))
    val status: StateFlow<FeedStatus> = _status

    @Volatile
    private var client: MqttClient? = null

    @Volatile
    private var observerLat: Double? = null

    @Volatile
    private var observerLon: Double? = null

    init {
        // Wired boards are pushed in by the USB detector rather than polled.
        usb?.onChange = { boards -> syncUsb(boards) }
    }

    fun isRunning(): Boolean = client?.isConnected == true

    fun isUsbRunning(): Boolean = usb?.isRunning() == true

    fun count(): Int = nodes.size

    /** Boards currently answering as ESP32 hardware; empty until something is plugged in. */
    fun boards(): List<UsbBoards.Board> = usb?.current().orEmpty()

    fun updateObserver(lat: Double?, lon: Double?) {
        observerLat = lat
        observerLon = lon
    }

    // ---------------- transport: MQTT ----------------

    fun start() {
        val cfg = settings()
        if (!cfg.esp32Enabled) return
        if (cfg.esp32UsbEnabled) usb?.start() else usb?.stop()
        if (!cfg.esp32MqttEnabled) {
            _status.value = FeedStatus(
                null,
                if (isUsbRunning()) "usb only" else "mqtt off",
                System.currentTimeMillis(),
                nodes.size,
            )
            return
        }
        val broker = cfg.esp32Broker.trim()
        if (broker.isEmpty()) {
            _status.value = FeedStatus(false, "no broker configured", System.currentTimeMillis(), nodes.size)
            return
        }
        if (isRunning()) return
        stopMqtt()
        runCatching {
            val c = MqttClient(broker, "esp32-" + (1000..9999).random(), MemoryPersistence())
            val opts = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 12
                keepAliveInterval = 30
            }
            c.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    _status.value = FeedStatus(
                        false,
                        cause?.message ?: "mqtt disconnected",
                        System.currentTimeMillis(),
                        nodes.size,
                    )
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val payload = message?.payload ?: return
                    runCatching { ingestJson(String(payload)) }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            })
            c.connect(opts)
            c.subscribe(cfg.esp32Topic.trim(), 0)
            client = c
            _status.value = FeedStatus(true, "esp32 live · ${cfg.esp32Topic}", System.currentTimeMillis(), nodes.size)
        }.onFailure {
            client = null
            _status.value = FeedStatus(false, it.message ?: "mqtt failed", System.currentTimeMillis(), nodes.size)
        }
    }

    fun stop() {
        stopMqtt()
        usb?.stop()
        for (key in nodes.keys.filter { it.startsWith("usb:") }) nodes.remove(key)
        publish()
        _status.value = _status.value.copy(ok = false, detail = "stopped")
    }

    private fun stopMqtt() {
        runCatching {
            client?.let { c ->
                if (c.isConnected) c.disconnect()
                c.close()
            }
        }
        client = null
    }

    // ---------------- transport: USB ----------------

    /**
     * Mirrors the USB detector's roster into the node table. A board keeps the score and any
     * position it already had; wired boards have no GNSS of their own here, so they sit at the
     * observer until something reports a fix.
     */
    private fun syncUsb(boards: List<UsbBoards.Board>) {
        val now = System.currentTimeMillis()
        val keep = boards.map { it.key }.toSet()
        for (key in nodes.keys.filter { it.startsWith("usb:") }) {
            if (key !in keep) nodes.remove(key)
        }
        for (b in boards) {
            val existing = nodes[b.key]?.node
            val r = b.reading
            val sats = r.sats ?: existing?.sats ?: 0
            val node = (existing ?: Esp32Node(id = b.key, name = b.label, lat = null, lon = null)).copy(
                name = b.label,
                board = b.familyLabel,
                chip = b.chip,
                firmware = b.version.ifBlank { existing?.firmware.orEmpty() },
                // The console poll is the only position source a wired board has. Whatever it
                // last reported is kept until it reports a newer fix.
                lat = r.lat ?: existing?.lat,
                lon = r.lon ?: existing?.lon,
                altM = r.altM?.roundToInt() ?: existing?.altM,
                sats = sats,
                hdop = r.hdop ?: existing?.hdop,
                wifiCount = r.wifi ?: existing?.wifiCount ?: 0,
                bleCount = r.ble ?: existing?.bleCount ?: 0,
                source = "usb",
                lastSeen = now,
                // A wired board is still a good link even with no fix, so it never scores 0.
                score = if (sats >= 4) {
                    scoreOf(true, sats, r.hdop ?: existing?.hdop, null)
                } else {
                    existing?.score?.takeIf { it >= USB_SCORE } ?: USB_SCORE
                },
            )
            nodes[b.key] = Entry(node, now, via = "usb")
        }
        publish()
    }

    // ---------------- transport: BLE ----------------

    /** One decoded advertisement heard directly from a board. */
    fun ingestAdvert(mac: String, advert: Esp32Codec.Advert, rssi: Int, now: Long) {
        if (!settings().esp32Enabled) return
        val key = "ble:${mac.uppercase()}"
        val existing = nodes[key]?.node
        val name = existing?.name?.takeIf { it.isNotBlank() } ?: "ESP32 ${mac.takeLast(5)}"
        val node = (existing ?: Esp32Node(id = key, name = name, lat = null, lon = null)).copy(
            name = name,
            // Keep the last known position when the receiver briefly loses its lock; the score
            // drops with the satellite count, so a stale fix never looks confident.
            lat = advert.lat ?: existing?.lat,
            lon = advert.lon ?: existing?.lon,
            altM = advert.altM ?: existing?.altM,
            sats = advert.sats,
            hdop = advert.hdop,
            batteryPct = if (advert.batteryPct >= 0) advert.batteryPct else existing?.batteryPct ?: -1,
            wifiCount = advert.wifiCount,
            bleCount = advert.bleCount,
            moving = advert.moving,
            rssi = rssi,
            source = "ble",
            lastSeen = now,
            score = scoreOf(advert.fix, advert.sats, advert.hdop, rssi),
        )
        nodes[key] = Entry(node, now, via = "ble")
        publish()
    }

    /** One JSON packet from a board that publishes over Wi-Fi. */
    fun ingestJson(text: String) {
        if (!settings().esp32Enabled) return
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val id = root.str("id") ?: root.str("node") ?: root.str("mac") ?: root.str("device") ?: return
        val advert = Esp32Codec.decodeJson(text) ?: return
        val now = System.currentTimeMillis()
        val key = "mqtt:${id.uppercase()}"
        val existing = nodes[key]?.node
        val name = root.str("name")?.takeIf { it.isNotBlank() } ?: existing?.name ?: id
        val node = (existing ?: Esp32Node(id = key, name = name, lat = null, lon = null)).copy(
            name = name,
            lat = advert.lat ?: existing?.lat,
            lon = advert.lon ?: existing?.lon,
            altM = advert.altM ?: existing?.altM,
            sats = advert.sats,
            hdop = advert.hdop,
            batteryPct = if (advert.batteryPct >= 0) advert.batteryPct else existing?.batteryPct ?: -1,
            wifiCount = advert.wifiCount,
            bleCount = advert.bleCount,
            moving = advert.moving,
            firmware = root.str("fw") ?: root.str("firmware") ?: existing?.firmware.orEmpty(),
            uptimeS = root.num("up")?.toLong() ?: root.num("uptime")?.toLong() ?: existing?.uptimeS ?: 0L,
            source = "mqtt",
            lastSeen = now,
            score = scoreOf(advert.fix, advert.sats, advert.hdop, null),
        )
        nodes[key] = Entry(node, now, via = "mqtt")
        publish()
    }

    // ---------------- roster ----------------

    /** Live roster: stale boards dropped, distances refreshed, best first. */
    fun snapshot(now: Long = System.currentTimeMillis()): List<Esp32Node> {
        if (!settings().esp32Enabled) {
            nodes.clear()
            _state.value = emptyList()
            return emptyList()
        }
        val it = nodes.entries.iterator()
        while (it.hasNext()) {
            val e = it.next().value
            // Wired boards do not age out: they are removed when the cable comes out.
            val ttl = when (e.via) {
                "ble" -> BLE_TTL_MS
                "mqtt" -> MQTT_TTL_MS
                else -> Long.MAX_VALUE
            }
            if (now - e.seenAt > ttl) it.remove()
        }
        val lat = observerLat
        val lon = observerLon
        val out = nodes.values.map { e ->
            val n = e.node
            val d = if (lat != null && lon != null && n.lat != null && n.lon != null) {
                OrbitMath.groundDistanceKm(lat, lon, n.lat, n.lon)
            } else {
                null
            }
            n.copy(distanceKm = d, lastSeen = e.seenAt)
        }.sortedWith(compareByDescending<Esp32Node> { it.score }.thenBy { it.name })
        _state.value = out
        _status.value = _status.value.copy(count = out.size, at = now)
        return out
    }

    /**
     * The board whose own GNSS is worth borrowing: it needs a real fix, not a two-satellite
     * guess, and the strongest score wins if several are in range.
     */
    fun bestFix(): Esp32Node? = nodes.values.map { it.node }
        .filter { it.lat != null && it.lon != null && it.sats >= 4 }
        .maxByOrNull { it.score }

    fun clear() {
        nodes.clear()
        _state.value = emptyList()
    }

    /**
     * A fix is scored by how good the lock is - satellites, then dilution of precision - and a
     * board with no fix falls back to its link quality, because that is all it tells us.
     */
    private fun scoreOf(fix: Boolean, sats: Int, hdop: Double?, rssi: Int?): Int {
        if (!fix) return rssi?.let { Geo.rssiToScore(it) } ?: 0
        var score = 60 + (sats * 2).coerceAtMost(30)
        if (hdop != null && hdop > 1.0) score -= ((hdop - 1.0) * 6.0).toInt()
        return score.coerceIn(0, 100)
    }

    private fun publish() {
        _state.value = nodes.values.map { it.node }
    }

    private fun JsonPrimitive?.text(): String? = this?.contentOrNull

    private fun kotlinx.serialization.json.JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive).text()

    private fun kotlinx.serialization.json.JsonObject.num(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull ?: (this[key] as? JsonPrimitive)?.intOrNull?.toDouble()
}
