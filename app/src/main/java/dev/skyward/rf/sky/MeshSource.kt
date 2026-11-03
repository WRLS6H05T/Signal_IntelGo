package dev.skyward.rf.sky

import dev.skyward.rf.core.FeedStatus
import dev.skyward.rf.core.Geo
import dev.skyward.rf.core.MeshNode
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

    fun isRunning(): Boolean = client?.isConnected == true

    fun start() {
        if (isRunning()) return
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
        stop()
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
            _status.value = FeedStatus(true, "mesh live · ${cfg.meshTopic}", System.currentTimeMillis(), nodes.size)
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
                val latI = payload.num("latitude_i") ?: payload.num("latitude")
                val lonI = payload.num("longitude_i") ?: payload.num("longitude")
                if (latI == null || lonI == null) return
                val lat = if (kotlin.math.abs(latI) > 180.0) latI / 1e7 else latI
                val lon = if (kotlin.math.abs(lonI) > 180.0) lonI / 1e7 else lonI
                if (lat == 0.0 && lon == 0.0) return
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
            if (n.lat != null && n.lon != null) {
                val d = OrbitMath.groundDistanceKm(observerLat, observerLon, n.lat, n.lon)
                if (n.distanceKm == null || kotlin.math.abs(n.distanceKm - d) > 0.25) {
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
