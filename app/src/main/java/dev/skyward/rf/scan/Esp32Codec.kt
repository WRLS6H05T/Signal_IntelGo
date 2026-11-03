package dev.skyward.rf.scan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * ESP32 sensor-board payloads.
 *
 * A board may or may not have a GPS module fitted, and it may reach the phone either way, so
 * both transports decode into one [Advert]:
 *
 * **BLE** - manufacturer-specific data, company id `0xFFFF` (the reserved internal/test value,
 * so no vendor's id is borrowed), magic `53 47` ("SG"), version `01`, then little-endian:
 *
 * | off   | field                                                   |
 * |-------|---------------------------------------------------------|
 * | 0-1   | company id 0xFFFF                                        |
 * | 2-3   | magic 'S' 'G'                                            |
 * | 4     | version (1)                                              |
 * | 5-8   | latitude  x 1e7 (int32)                                  |
 * | 9-12  | longitude x 1e7 (int32)                                  |
 * | 13-14 | altitude metres (int16, negative below sea level)        |
 * | 15    | satellites in fix (uint8)                                |
 * | 16    | HDOP x 10 (uint8, 0 or 255 = unknown)                    |
 * | 17    | battery percent (uint8, 255 = unknown)                   |
 * | 18    | flags: 1 fix, 2 wifi count, 4 ble count, 8 moving        |
 * | 19-20 | Wi-Fi networks in the last sweep (uint16)                |
 * | 21-22 | BLE devices in the last sweep (uint16)                   |
 *
 * 25 bytes on the air once the AD length and type are counted, so it still fits inside a
 * legacy 31-byte advertisement with room for a short name.
 *
 * **MQTT** - boards with Wi-Fi publish JSON instead:
 * `{"id":"roof","lat":51.5,"lon":-0.12,"alt":32,"sats":9,"hdop":0.9,"batt":82,`
 * `"wifi":41,"ble":17,"fix":true,"fw":"1.0.3"}`
 *
 * Nothing here throws: malformed data returns null and the caller ignores the packet.
 */
object Esp32Codec {

    const val COMPANY_ID: Int = 0xFFFF
    private const val MAGIC_0: Int = 0x53 // 'S'
    private const val MAGIC_1: Int = 0x47 // 'G'
    const val VERSION: Int = 1
    const val BATTERY_UNKNOWN: Int = 0xFF

    const val FLAG_FIX: Int = 0x01
    const val FLAG_WIFI_COUNT: Int = 0x02
    const val FLAG_BLE_COUNT: Int = 0x04
    const val FLAG_MOVING: Int = 0x08

    /** Up to and including the flags byte; the two count fields are optional. */
    const val MIN_LEN: Int = 19
    const val FULL_LEN: Int = 23

    data class Advert(
        val fix: Boolean,
        val lat: Double?,
        val lon: Double?,
        val altM: Int?,
        val sats: Int,
        val hdop: Double?,
        val batteryPct: Int,
        val wifiCount: Int,
        val bleCount: Int,
        val moving: Boolean,
        val version: Int = VERSION,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun u8(b: ByteArray, i: Int): Int = if (i in b.indices) b[i].toInt() and 0xFF else 0

    private fun u16(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)

    private fun i16(b: ByteArray, i: Int): Int {
        val raw = u16(b, i)
        return if (raw and 0x8000 != 0) raw - 0x10000 else raw
    }

    private fun i32(b: ByteArray, i: Int): Int =
        u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16) or (u8(b, i + 3) shl 24)

    /** Decodes a manufacturer-data payload, or null if it is not one of ours. */
    fun decode(data: ByteArray?): Advert? {
        if (data == null || data.size < MIN_LEN) return null
        if (u16(data, 0) != COMPANY_ID) return null
        if (u8(data, 2) != MAGIC_0 || u8(data, 3) != MAGIC_1) return null
        if (u8(data, 4) != VERSION) return null

        val latRaw = i32(data, 5)
        val lonRaw = i32(data, 9)
        val alt = i16(data, 13)
        val sats = u8(data, 15)
        val hdopRaw = u8(data, 16)
        val battery = u8(data, 17)
        val flags = u8(data, 18)
        val lat = latRaw / 1e7
        val lon = lonRaw / 1e7
        // A fix is only believed when the flag says so and the numbers are on the planet; a
        // board that has not locked yet sends zeros, which would otherwise pin it at Null Island.
        val fix = flags and FLAG_FIX != 0 &&
            latRaw != 0 && lonRaw != 0 &&
            lat > -90.0 && lat < 90.0 && lon > -180.0 && lon < 180.0

        return Advert(
            fix = fix,
            lat = if (fix) lat else null,
            lon = if (fix) lon else null,
            altM = if (fix) alt else null,
            sats = sats,
            hdop = if (hdopRaw == 0 || hdopRaw == BATTERY_UNKNOWN) null else hdopRaw / 10.0,
            batteryPct = if (battery == BATTERY_UNKNOWN) -1 else battery,
            wifiCount = if (flags and FLAG_WIFI_COUNT != 0 && data.size >= 21) u16(data, 19) else 0,
            bleCount = if (flags and FLAG_BLE_COUNT != 0 && data.size >= 23) u16(data, 21) else 0,
            moving = flags and FLAG_MOVING != 0,
            version = VERSION,
        )
    }

    /** Encodes an [Advert] in the BLE layout; used by the firmware reference and the tests. */
    fun encode(advert: Advert): ByteArray {
        val out = ByteArray(FULL_LEN)
        out[0] = (COMPANY_ID and 0xFF).toByte()
        out[1] = ((COMPANY_ID shr 8) and 0xFF).toByte()
        out[2] = MAGIC_0.toByte()
        out[3] = MAGIC_1.toByte()
        out[4] = VERSION.toByte()
        val latRaw = ((advert.lat ?: 0.0) * 1e7).toLong().toInt()
        val lonRaw = ((advert.lon ?: 0.0) * 1e7).toLong().toInt()
        for (i in 0 until 4) {
            out[5 + i] = ((latRaw shr (8 * i)) and 0xFF).toByte()
            out[9 + i] = ((lonRaw shr (8 * i)) and 0xFF).toByte()
        }
        val alt = advert.altM ?: 0
        out[13] = (alt and 0xFF).toByte()
        out[14] = ((alt shr 8) and 0xFF).toByte()
        out[15] = advert.sats.coerceIn(0, 255).toByte()
        out[16] = advert.hdop?.let { (it * 10).toInt().coerceIn(0, 254).toByte() } ?: BATTERY_UNKNOWN.toByte()
        out[17] = if (advert.batteryPct < 0) BATTERY_UNKNOWN.toByte() else advert.batteryPct.coerceIn(0, 100).toByte()
        var flags = 0
        if (advert.fix) flags = flags or FLAG_FIX
        if (advert.wifiCount > 0) flags = flags or FLAG_WIFI_COUNT
        if (advert.bleCount > 0) flags = flags or FLAG_BLE_COUNT
        if (advert.moving) flags = flags or FLAG_MOVING
        out[18] = flags.toByte()
        for (i in 0 until 2) {
            out[19 + i] = ((advert.wifiCount shr (8 * i)) and 0xFF).toByte()
            out[21 + i] = ((advert.bleCount shr (8 * i)) and 0xFF).toByte()
        }
        return out
    }

    /**
     * Pulls our manufacturer data out of a raw scan record. The returned array starts at the
     * company id, which is what [decode] expects.
     */
    fun extract(record: ByteArray?): ByteArray? {
        if (record == null || record.isEmpty()) return null
        var i = 0
        while (i < record.size) {
            val len = record[i].toInt() and 0xFF
            if (len == 0) break
            if (i + len >= record.size) break
            val type = record[i + 1].toInt() and 0xFF
            if (type == 0xFF && len >= 4) { // Manufacturer Specific Data
                val company = (record[i + 2].toInt() and 0xFF) or ((record[i + 3].toInt() and 0xFF) shl 8)
                if (company == COMPANY_ID) return record.copyOfRange(i + 2, i + len + 1)
            }
            i += len + 1
        }
        return null
    }

    /** Decodes the JSON a Wi-Fi board publishes over MQTT. */
    fun decodeJson(text: String): Advert? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val lat = root.num("lat") ?: root.num("latitude")
        val lon = root.num("lon") ?: root.num("lng") ?: root.num("longitude")
        val flagged = root.bool("fix") ?: root.bool("valid")
        val fix = (flagged ?: (lat != null && lon != null)) &&
            lat != null && lon != null &&
            lat > -90.0 && lat < 90.0 && lon > -180.0 && lon < 180.0 &&
            !(lat == 0.0 && lon == 0.0)
        val battery = root.num("batt")?.toInt() ?: root.num("battery")?.toInt() ?: -1
        return Advert(
            fix = fix,
            lat = if (fix) lat else null,
            lon = if (fix) lon else null,
            altM = if (fix) root.num("alt")?.toInt() ?: root.num("altitude")?.toInt() else null,
            sats = root.num("sats")?.toInt() ?: root.num("sat")?.toInt() ?: 0,
            hdop = root.num("hdop") ?: root.num("hdop_x10")?.let { it / 10.0 },
            batteryPct = if (battery in 0..100) battery else -1,
            wifiCount = root.num("wifi")?.toInt() ?: root.num("wifi_count")?.toInt() ?: 0,
            bleCount = root.num("ble")?.toInt() ?: root.num("ble_count")?.toInt() ?: 0,
            moving = root.bool("moving") ?: false,
            version = root.num("v")?.toInt() ?: VERSION,
        )
    }

    private fun JsonObject.num(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull ?: (this[key] as? JsonPrimitive)?.intOrNull?.toDouble()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
