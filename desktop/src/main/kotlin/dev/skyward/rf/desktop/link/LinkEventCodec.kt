package dev.skyward.rf.desktop.link

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * The line vocabulary a carrier may speak, apart from the carrier.
 *
 * The network and cable carriers both send base64 `PeerFrames` chunks, but keeping a decoder for
 * the original advertisement-shaped lines is what lets the wire format be tested on the JVM -
 * and what a future carrier with a different shape would implement instead of teaching the host
 * a second vocabulary.
 */
object LinkEventCodec {

    fun decode(line: String, json: Json = Json { ignoreUnknownKeys = true; isLenient = true }): LinkEvent? {
        val text = line.trim()
        if (text.isEmpty() || !text.startsWith("{")) return null
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val kind = root["ev"]?.jsonPrimitive?.contentOrNull ?: return null
        return when (kind) {
            "ready" -> LinkEvent.Ready(root.str("detail").ifBlank { root.str("adapter").ifBlank { "ready" } })
            "error" -> LinkEvent.Failed(root.str("message").ifBlank { "link error" })
            "log" -> LinkEvent.Note(root.str("message"))
            "advert" -> LinkEvent.Advert(
                address = root.str("address"),
                name = root.str("name"),
                rssi = root["rssi"]?.jsonPrimitive?.intOrNull ?: 0,
                serviceUuids = root["service_uuids"]?.let { element ->
                    runCatching {
                        element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull?.lowercase() }
                    }.getOrNull()
                }.orEmpty(),
                manufacturer = root["manufacturer"]?.let { element ->
                    runCatching {
                        element.jsonObject.mapNotNull { (key, value) ->
                            val id = key.toIntOrNull() ?: return@mapNotNull null
                            val bytes = runCatching { Base64.getDecoder().decode(value.jsonPrimitive.content) }
                                .getOrNull() ?: return@mapNotNull null
                            id to bytes
                        }.toMap()
                    }.getOrNull()
                }.orEmpty(),
                serviceData = root["service_data"]?.let { element ->
                    runCatching {
                        element.jsonObject.mapNotNull { (key, value) ->
                            val bytes = runCatching { Base64.getDecoder().decode(value.jsonPrimitive.content) }
                                .getOrNull() ?: return@mapNotNull null
                            key.lowercase() to bytes
                        }.toMap()
                    }.getOrNull()
                }.orEmpty(),
                seenAt = System.currentTimeMillis(),
            )

            "connected" -> LinkEvent.Connected(
                address = root.str("address"),
                mtu = root["mtu"]?.jsonPrimitive?.intOrNull ?: 185,
            )

            "disconnected" -> LinkEvent.Disconnected(root.str("address"))
            "notify" -> LinkEvent.Notified(
                address = root.str("address"),
                uuid = root.str("uuid").lowercase(),
                data = runCatching { Base64.getDecoder().decode(root.str("data")) }.getOrNull() ?: return null,
            )

            else -> null
        }
    }

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: ""
}
