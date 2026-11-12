package dev.skyward.rf.scan

// Minimal ASTM F3411 (Open Drone ID) parser for BLE Remote ID advertisements.
//
// Broadcasters send the ODID payload as service data under the 16-bit UUID 0xFFFA.
// A payload is either a single message or a "message pack" (type 0xF) holding short
// records of the form: one byte type + version, one byte payload size, then the payload.
//
// Byte layouts used here (F3411-22a, Location/Vector + Basic ID):
//   Basic ID (0):        1 = type/ver, 2 = id type, 3..22 = 20-byte ASCII id
//   Location/Vector (1): 1 = status flags, 2 = heading, 3 = speed, 4 = vert speed,
//                        5..8 = lat (int32 /1e7), 9..12 = lon (int32 /1e7),
//                        13..14 = pressure alt, 15..16 = geodetic alt,
//                        17..18 = height over ground, 19 = h/v accuracy,
//                        20 = baro/speed accuracy, 21..22 = timestamp
//   System (4):          1 = flags, 2..5 = operator lat, 6..9 = operator lon
//
// Altitude fields decode as ((raw - 1000) / 2) metres.
object OdidParser {

    /** Full 16-bit Bluetooth SIG UUID for UAS Remote ID service data. */
    const val ODID_SERVICE_UUID: Int = 0xFFFA

    data class OdidMessage(val type: Int, val body: ByteArray)

    data class DroneFrame(
        val uasId: String? = null,
        val idType: Int = -1,
        val lat: Double? = null,
        val lon: Double? = null,
        val geodeticAltM: Double? = null,
        val pressureAltM: Double? = null,
        val heightM: Double? = null,
        val speedMs: Double? = null,
        val headingDeg: Double? = null,
        val verticalSpeedMs: Double? = null,
        val status: Int = -1,
        val operatorLat: Double? = null,
        val operatorLon: Double? = null,
    )

    private fun u8(b: ByteArray, i: Int): Int = if (i < b.size) b[i].toInt() and 0xFF else 0

    private fun u16(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)

    private fun i32(b: ByteArray, i: Int): Int =
        u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16) or (u8(b, i + 3) shl 24)

    /** Altitude encoding: value 0 means "unknown"; otherwise (raw - 1000) / 2 metres. */
    private fun altitude(raw: Int): Double? {
        if (raw == 0 || raw == 0xFFFF) return null
        return (raw - 1000) / 2.0
    }

    /** Expands a payload into the messages it carries. */
    fun messages(payload: ByteArray): List<OdidMessage> {
        if (payload.isEmpty()) return emptyList()
        val header = u8(payload, 0)
        val type = (header shr 4) and 0x0F
        if (type == 0x0F) {
            // Message pack: records of [type|ver][size][body...]
            val out = ArrayList<OdidMessage>()
            var i = 1
            while (i + 1 < payload.size) {
                val recType = (u8(payload, i) shr 4) and 0x0F
                val size = u8(payload, i + 1)
                if (size <= 0 || i + 2 + size > payload.size) break
                out.add(OdidMessage(recType, payload.copyOfRange(i + 2, i + 2 + size)))
                i += 2 + size
            }
            return out
        }
        return listOf(OdidMessage(type, payload.copyOfRange(1, payload.size)))
    }

    /** Parses a complete set of ODID messages into one (partial) drone frame. */
    fun parse(payload: ByteArray): DroneFrame? {
        val msgs = messages(payload)
        if (msgs.isEmpty()) return null
        var frame = DroneFrame()
        var sawAnything = false
        for (m in msgs) {
            when (m.type) {
                0 -> { // Basic ID
                    val idType = u8(m.body, 0) and 0x0F
                    val raw = m.body.copyOfRange(1, minOf(m.body.size, 21))
                    val text = buildString {
                        for (c in raw) {
                            val v = c.toInt() and 0xFF
                            if (v in 32..126) append(v.toChar())
                        }
                    }.trim().trim('\u0000')
                    if (text.isNotEmpty()) {
                        frame = frame.copy(uasId = text, idType = idType)
                        sawAnything = true
                    }
                }

                1 -> { // Location / Vector
                    val flags = u8(m.body, 0)
                    val status = flags and 0x0F
                    val headingRaw = u8(m.body, 1)
                    val speedRaw = u8(m.body, 2)
                    val vertRaw = u8(m.body, 3)
                    val latRaw = i32(m.body, 4)
                    val lonRaw = i32(m.body, 8)
                    val pressure = u16(m.body, 12)
                    val geodetic = u16(m.body, 14)
                    val height = u16(m.body, 16)
                    val lat = if (latRaw == 0 || latRaw == Int.MIN_VALUE) null else latRaw / 1e7
                    val lon = if (lonRaw == 0 || lonRaw == Int.MIN_VALUE) null else lonRaw / 1e7
                    val vert = if (vertRaw == 0) 0.0 else (vertRaw - 63) * 0.5
                    frame = frame.copy(
                        lat = lat,
                        lon = lon,
                        headingDeg = if (headingRaw == 0) null else headingRaw * 360.0 / 256.0,
                        speedMs = if (speedRaw == 0) null else if (speedRaw == 255) 254.0 else speedRaw.toDouble(),
                        verticalSpeedMs = if (vertRaw == 0) null else vert,
                        pressureAltM = altitude(pressure),
                        geodeticAltM = altitude(geodetic),
                        heightM = altitude(height),
                        status = status,
                    )
                    sawAnything = true
                }

                4 -> { // System: operator location
                    val opLatRaw = i32(m.body, 1)
                    val opLonRaw = i32(m.body, 5)
                    val opLat = if (opLatRaw == 0) null else opLatRaw / 1e7
                    val opLon = if (opLonRaw == 0) null else opLonRaw / 1e7
                    if (opLat != null && opLon != null) {
                        frame = frame.copy(operatorLat = opLat, operatorLon = opLon)
                        sawAnything = true
                    }
                }
            }
        }
        return if (sawAnything) frame else null
    }

    /** Extracts the ODID service-data payload from a raw scan record, if present. */
    fun extractServiceData(record: ByteArray?): ByteArray? {
        if (record == null || record.isEmpty()) return null
        var i = 0
        while (i < record.size) {
            val len = record[i].toInt() and 0xFF
            if (len == 0) break
            if (i + len >= record.size) break
            val type = record[i + 1].toInt() and 0xFF
            if (type == 0x16 && len >= 4) { // Service Data - 16-bit UUID
                val uuid = (record[i + 2].toInt() and 0xFF) or ((record[i + 3].toInt() and 0xFF) shl 8)
                if (uuid == ODID_SERVICE_UUID) {
                    return record.copyOfRange(i + 4, i + len + 1)
                }
            }
            i += len + 1
        }
        return null
    }
}
