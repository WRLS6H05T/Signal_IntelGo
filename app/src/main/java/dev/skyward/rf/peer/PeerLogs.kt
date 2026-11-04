package dev.skyward.rf.peer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Packaging for a whole drive session sent to the other phone.
 *
 * A session is JSON text that repeats itself heavily - the same access points named at every fix -
 * so it is gzipped first, which shrinks a drive by roughly an order of magnitude. Base64 makes the
 * bytes safe to carry in the same JSON-line protocol the live sweeps use, and the result is cut
 * into parts small enough that each one still fits a single BLE message at the smallest MTU.
 *
 * Free of Android types on purpose: this is the part of the transfer that a JVM test can prove,
 * and an off-by-one here would corrupt every drive sent between two phones.
 */
object PeerLogs {

    /**
     * Base64 characters per part. 1.2 kB is about 64 frames at the default 23-byte MTU, well
     * inside the 255-frame cap, and still tiny at a negotiated 517.
     */
    const val PART_CHARS = 1_200

    /** A cap on what one transfer may cost, so a runaway file cannot hold the link forever. */
    const val MAX_PARTS = 400

    /** Gzips [json] and cuts the base64 into parts; empty when there is nothing to send. */
    fun pack(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        val encoded = Base64.getEncoder().encodeToString(out.toByteArray())
        return encoded.chunked(PART_CHARS)
    }

    /** Rebuilds the JSON from parts, or null when they are incomplete or unreadable. */
    fun unpack(parts: List<String>): String? {
        if (parts.isEmpty() || parts.any { it.isBlank() }) return null
        val gz = runCatching { Base64.getDecoder().decode(parts.joinToString("")) }.getOrNull() ?: return null
        return runCatching {
            GZIPInputStream(ByteArrayInputStream(gz)).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
    }
}
