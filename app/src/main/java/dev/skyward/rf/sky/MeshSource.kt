package dev.skyward.rf.sky

import dev.skyward.rf.core.FeedStatus
import dev.skyward.rf.core.Geo
import dev.skyward.rf.core.MeshNode
import dev.skyward.rf.meshcore.MeshCore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.ConcurrentHashMap

/**
 * Mesh nodes (Meshtastic).
 *
 * Meshtastic gateways republish the mesh as plaintext JSON on the public broker
 * (`msh/<region>/2/json/#`), which is how the community map is built. Node position and
 * SNR arrive in that JSON, so the app can plot nodes it can never hear directly.
 *
 * Nodes also show up locally over BLE ("Meshtastic_xxxx" advertisers) - those have no
 * position but do have an RSSI, and are merged in through [upsertBleNode].
 */
class MeshSource(private val settings: () -> dev.skyward.rf.core.Settings) {

    data class MqttConfig(
        val broker: String,
        val topic: String,
        val user: String,
        val pass: String,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val nodes = ConcurrentHashMap<String, MeshNode>()
    private val names = ConcurrentHashMap<String, String>()

    private val _nodes = MutableStateFlow<Map<String, MeshNode>>(emptyMap())
    val nodesFlow: StateFlow<Map<String, MeshNode>> = _nodes

    private val _status = MutableStateFlow(FeedStatus(null, "idle", 0L, 0))
    val status: StateFlow<FeedStatus> = _status

    @Volatile
    private var client: MqttClient? = null

    /** The MeshCore feed's own connection: a different broker, a different packet format. */
    @Volatile
    private var coreClient: MqttClient? = null

    fun isRunning(): Boolean = client?.isConnected == true || coreClient?.isConnected == true

    /**
     * Starts both mesh feeds. Meshtastic and MeshCore are separate brokers and separate clients:
     * one being switched off is no reason to leave the other silent.
     */
    fun start() {
        startMeshtastic()
        startMeshCore()
    }

    private fun startMeshtastic() {
        if (client?.isConnected == true) return
        val cfg = settings()
        if (!cfg.meshMqttEnabled) {
            _status.value = FeedStatus(null, "mesh feed off", System.currentTimeMillis(), nodes.size)
            return
        }
        val broker = cfg.meshBroker.trim()
        if (broker.isEmpty()) {
            _status.value = FeedStatus(false, "no broker configured", System.currentTimeMillis(), nodes.size)
            return
        }
        // Only this client is dropped here: the MeshCore feed is a separate connection and a
        // Meshtastic reconnect must not take it down with it.
        runCatching {
            client?.let { old ->
                if (old.isConnected) old.disconnect()
                old.close()
            }
        }
        client = null
        runCatching {
            val id = "skyward-" + (1000..9999).random()
            val c = MqttClient(broker, id, MemoryPersistence())
            val opts = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 12
                keepAliveInterval = 30
                if (cfg.meshUser.isNotBlank()) userName = cfg.meshUser
                if (cfg.meshPass.isNotBlank()) password = cfg.meshPass.toCharArray()
            }
            c.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    _status.value = FeedStatus(false, cause?.message ?: "mqtt disconnected", System.currentTimeMillis(), nodes.size)
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val payload = message?.payload ?: return
                    runCatching { ingestJson(String(payload)) }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            })
            c.connect(opts)
            c.subscribe(cfg.meshTopic.trim(), 0)
            client = c
            // The address is part of the status: "live" on its own does not say which broker
            // this is on, and the topic is only half of what had to be configured to get here.
            _status.value = FeedStatus(
                true,
                "mesh live · ${hostOf(broker)} · ${cfg.meshTopic}",
                System.currentTimeMillis(),
                nodes.size,
            )
        }.onFailure {
            client = null
            _status.value = FeedStatus(false, it.message ?: "mqtt failed", System.currentTimeMillis(), nodes.size)
        }
    }

    fun stop() {
        runCatching {
            client?.let { c ->
                if (c.isConnected) c.disconnect()
                c.close()
            }
        }
        client = null
        runCatching {
            coreClient?.let { c ->
                if (c.isConnected) c.disconnect()
                c.close()
            }
        }
        coreClient = null
    }

    /**
     * The MeshCore feed.
     *
     * An observer repeater on a roof uplinks what it hears to `meshcore/{IATA}/{device}/packets`
     * as JSON whose `raw` field is the packet in hex. Only node adverts are decoded, so repeaters
     * and room servers join the mesh layer without this app needing a MeshCore radio of its own.
     */
    private fun startMeshCore() {
        val cfg = settings()
        if (!cfg.meshCoreEnabled) return
        if (coreClient?.isConnected == true) return
        val broker = cfg.meshCoreBroker.trim()
        if (broker.isEmpty()) return
        stopMeshCore()
        runCatching {
            val c = MqttClient(broker, "skyward-core-" + (1000..9999).random(), MemoryPersistence())
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
                        cause?.message ?: "meshcore disconnected",
                        System.currentTimeMillis(),
                        nodes.size,
                    )
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val payload = message?.payload ?: return
                    runCatching { ingestCoreJson(String(payload)) }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            })
            c.connect(opts)
            c.subscribe(cfg.meshCoreTopic.trim(), 0)
            coreClient = c
            _status.value = FeedStatus(
                true,
                "meshcore live · ${hostOf(broker)} · ${cfg.meshCoreTopic}",
                System.currentTimeMillis(),
                nodes.size,
            )
        }.onFailure {
            coreClient = null
            _status.value = FeedStatus(false, it.message ?: "meshcore failed", System.currentTimeMillis(), nodes.size)
        }
    }

    /** The broker without its scheme or port: what "live" is actually connected to. */
    private fun hostOf(broker: String): String =
        broker.trim()
            .removePrefix("ssl://")
            .removePrefix("tcp://")
            .removePrefix("ws://")
            .removePrefix("wss://")
            .substringBefore('/')

    private fun stopMeshCore() {
        runCatching {
            coreClient?.let { c ->
                if (c.isConnected) c.disconnect()
                c.close()
            }
        }
        coreClient = null
    }

    /** One MeshCore publication: either the `packets` message or the minimal `raw` one. */
    fun ingestCoreJson(text: String) {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val hex = root.str("raw") ?: root.str("data") ?: return
        val advert = MeshCore.decodeHex(hex) ?: return
        ingestCoreAdvert(
            advert = advert,
            snr = root.num("SNR") ?: root.num("snr"),
            rssi = root.num("RSSI") ?: root.num("rssi"),
        )
    }

    /**
     * Folds one advert into the mesh table.
     *
     * A node is keyed by its public key, so a repeater heard through three different observers is
     * one repeater. A repeater that does not share a location keeps any position it had and is
     * otherwise listed without one: placing it at this phone's fix would put infrastructure on a
     * street it has never been on.
     */
    fun ingestCoreAdvert(
        advert: MeshCore.Advert,
        snr: Double? = null,
        rssi: Double? = null,
        now: Long = System.currentTimeMillis(),
    ) {
        if (advert.pubkey.isBlank()) return
        val key = "core:" + advert.pubkey.take(16)
        val existing = nodes[key]
        val role = if (advert.role == MeshCore.Role.UNKNOWN) {
            existing?.role ?: MeshCore.Role.UNKNOWN
        } else {
            advert.role
        }
        val name = advert.name.ifBlank {
            existing?.name ?: (advert.role.label + " " + advert.pubkey.take(6))
        }
        nodes[key] = MeshNode(
            id = advert.pubkey.take(16),
            name = name,
            lat = advert.lat ?: existing?.lat,
            lon = advert.lon ?: existing?.lon,
            altitudeM = existing?.altitudeM ?: 0,
            snr = snr ?: existing?.snr ?: 0.0,
            hops = advert.hops,
            batteryPct = existing?.batteryPct ?: -1,
            // Heard now, whatever the advert's own clock said: a repeater advertises every
            // few hours, and its timestamp must not sort it under nodes seen this second.
            lastSeen = now,
            source = "meshcore",
            rssi = rssi?.toInt() ?: existing?.rssi,
            role = role,
            pubkey = advert.pubkey,
            score = when {
                snr != null -> snrToScore(snr)
                rssi != null -> Geo.rssiToScore(rssi.toInt())
                else -> existing?.score ?: 50
            },
        )
        publish()
    }

    /** Parses one Meshtastic JSON packet. Safe to call with anything. */
    fun ingestJson(text: String) {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val sender = root.str("sender") ?: root.str("from") ?: return
        val type = root.str("type") ?: ""
        val payload = root["payload"] as? JsonObject ?: return
        val now = System.currentTimeMillis()

        when (type) {
            "nodeinfo" -> {
                val name = payload.str("longname") ?: payload.str("shortname") ?: sender
                names[sender] = name
                val existing = nodes[sender]
                val battery = batteryOf(payload)
                nodes[sender] = (existing ?: MeshNode(id = sender, name = name, lat = null, lon = null, lastSeen = now))
                    .copy(
                        name = name,
                        snr = root.num("snr") ?: existing?.snr ?: 0.0,
                        batteryPct = battery ?: existing?.batteryPct ?: -1,
                        lastSeen = now,
                    )
                publish()
            }

            "position" -> {
                // `latitude_i` is the fixed-point field (degrees x 1e7) and `latitude` is the
                // float one, so the field the value came from decides the scale. Guessing the
                // scale from the magnitude read a node sitting on the equator or the prime
                // meridian - a fixed-point value below 180 - as roughly 200 degrees, which is
                // not a place on Earth, and drew it off the edge of the map.
                val latFixed = payload.num("latitude_i")
                val lonFixed = payload.num("longitude_i")
                val lat = if (latFixed != null) latFixed / 1e7 else payload.num("latitude") ?: return
                val lon = if (lonFixed != null) lonFixed / 1e7 else payload.num("longitude") ?: return
                // A zero pair means "no position shared", which some gateways send instead of
                // leaving the field out. It is not a node in the Gulf of Guinea.
                if (lat == 0.0 && lon == 0.0) return
                if (!lat.isFinite() || !lon.isFinite()) return
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return
                val snr = root.num("snr") ?: 0.0
                val existing = nodes[sender]
                nodes[sender] = MeshNode(
                    id = sender,
                    name = names[sender] ?: existing?.name ?: sender,
                    lat = lat,
                    lon = lon,
                    altitudeM = (payload.num("altitude") ?: existing?.altitudeM?.toDouble() ?: 0.0).toInt(),
                    snr = snr,
                    hops = (root.num("hops_away") ?: existing?.hops?.toDouble() ?: 0.0).toInt(),
                    batteryPct = existing?.batteryPct ?: -1,
                    lastSeen = now,
                    source = "mqtt",
                    // Carried over rather than rebuilt: a position packet is not a new node, so
                    // it must not erase what another packet already learned about this one.
                    rssi = existing?.rssi,
                    role = existing?.role ?: MeshCore.Role.UNKNOWN,
                    pubkey = existing?.pubkey.orEmpty(),
                    distanceKm = existing?.distanceKm,
                    score = snrToScore(snr),
                )
                publish()
            }

            "telemetry" -> {
                val battery = batteryOf(payload) ?: return
                nodes[sender]?.let { nodes[sender] = it.copy(batteryPct = battery, lastSeen = now) }
                publish()
            }
        }
    }

    /** A Meshtastic device heard directly over BLE: identity + RSSI, no position. */
    fun upsertBleNode(id: String, name: String, rssi: Int, now: Long) {
        val key = "ble:$id"
        val existing = nodes[key]
        nodes[key] = MeshNode(
            id = id,
            name = name,
            lat = existing?.lat,
            lon = existing?.lon,
            altitudeM = existing?.altitudeM ?: 0,
            snr = existing?.snr ?: 0.0,
            hops = 0,
            batteryPct = existing?.batteryPct ?: -1,
            lastSeen = now,
            source = "ble",
            rssi = rssi,
            score = Geo.rssiToScore(rssi),
        )
        publish()
    }

    fun updateDistances(observerLat: Double?, observerLon: Double?) {
        if (observerLat == null || observerLon == null) return
        var changed = false
        for ((k, n) in nodes) {
            // Hoisted because MeshNode is compiled in :shared; a smart cast would not cross
            // that boundary.
            val nLat = n.lat
            val nLon = n.lon
            if (nLat != null && nLon != null) {
                val d = OrbitMath.groundDistanceKm(observerLat, observerLon, nLat, nLon)
                val previous = n.distanceKm
                if (previous == null || kotlin.math.abs(previous - d) > 0.25) {
                    nodes[k] = n.copy(distanceKm = d)
                    changed = true
                }
            }
        }
        if (changed) publish()
    }

    private fun publish() {
        _nodes.value = nodes.toMap()
        _status.value = _status.value.copy(count = nodes.size)
    }

    private fun batteryOf(payload: JsonObject): Int? {
        (payload["device_metrics"] as? JsonObject)?.let { m ->
            m.num("battery_level")?.let { return it.toInt() }
            m.num("batteryLevel")?.let { return it.toInt() }
        }
        return payload.num("battery_level")?.toInt()
    }

    private fun snrToScore(snr: Double): Int {
        // LoRa SNR ranges roughly -20 dB (barely decodable) .. +12 dB (excellent).
        if (snr == 0.0) return 50
        val normalized = ((snr + 20.0) / 32.0 * 100.0).toInt()
        return normalized.coerceIn(0, 100)
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.num(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull
            ?: (this[key] as? JsonPrimitive)?.intOrNull?.toDouble()
}
