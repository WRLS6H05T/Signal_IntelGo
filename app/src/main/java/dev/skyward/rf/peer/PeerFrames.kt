package dev.skyward.rf.peer

/**
 * Chunking for the BLE peer link.
 *
 * A GATT write carries at most MTU-3 bytes - twenty by default - while a sweep message is
 * kilobytes, so every message is cut into frames with a four-byte header and reassembled on the
 * other side. The header is a message id, a sequence number and the total chunk count: enough to
 * spot a lost chunk and to drop a message that never completes, without needing a retransmit
 * protocol on top of BLE.
 *
 * Deliberately free of Android types so it can be tested: this is where an off-by-one in the
 * chunk size would quietly corrupt every message and look like a protocol bug.
 */
object PeerFrames {

    const val HEADER = 4

    /** Largest number of chunks a single message may span, which caps a message at 255 x MTU. */
    const val MAX_CHUNKS = 255

    data class Frame(val id: Int, val seq: Int, val total: Int, val body: ByteArray)

    /**
     * Cuts [payload] into frames of at most [chunkBytes] body bytes. Returns an empty list rather
     * than anything partial when the message would need more than [MAX_CHUNKS] frames - sending
     * most of a sweep is worse than sending none, because the receiver cannot tell the
     * difference between a truncated list and a quiet radio.
     */
    fun split(payload: ByteArray, chunkBytes: Int, id: Int): List<ByteArray> {
        val body = chunkBytes.coerceAtLeast(1)
        val total = (payload.size + body - 1) / body
        if (total > MAX_CHUNKS) return emptyList()
        val out = ArrayList<ByteArray>(total)
        for (seq in 0 until total) {
            val from = seq * body
            val to = minOf(from + body, payload.size)
            val frame = ByteArray(HEADER + (to - from))
            frame[0] = (id and 0xFF).toByte()
            frame[1] = seq.toByte()
            frame[2] = total.toByte()
            frame[3] = 0
            System.arraycopy(payload, from, frame, HEADER, to - from)
            out.add(frame)
        }
        return out
    }

    fun parse(bytes: ByteArray): Frame? {
        if (bytes.size <= HEADER) return null
        val total = bytes[2].toInt() and 0xFF
        val seq = bytes[1].toInt() and 0xFF
        if (total <= 0 || seq >= total) return null
        return Frame(bytes[0].toInt() and 0xFF, seq, total, bytes.copyOfRange(HEADER, bytes.size))
    }

    /** Reassembles frames; anything malformed, stale or abandoned is discarded. */
    class Assembler {

        private class Partial(val total: Int, val parts: Array<ByteArray?>) {
            var filled = 0
            var touchedAt = System.currentTimeMillis()
        }

        private val pending = HashMap<Int, Partial>()

        /** Returns the complete message when [bytes] finished one, otherwise null. */
        fun add(bytes: ByteArray): ByteArray? {
            val frame = parse(bytes) ?: return null
            val entry = pending[frame.id]
            val partial = when {
                entry == null -> Partial(frame.total, arrayOfNulls(frame.total)).also { pending[frame.id] = it }
                entry.total != frame.total -> Partial(frame.total, arrayOfNulls(frame.total)).also { pending[frame.id] = it }
                else -> entry
            }
            partial.touchedAt = System.currentTimeMillis()
            if (partial.parts[frame.seq] == null) {
                partial.parts[frame.seq] = frame.body
                partial.filled++
            }
            // Abandoned messages must not accumulate: a phone that walked away mid-send would
            // otherwise leave half a sweep resident for the life of the process.
            val cutoff = System.currentTimeMillis() - 30_000
            pending.entries.removeAll { it.value.touchedAt < cutoff }
            if (partial.filled < partial.total) return null

            pending.remove(frame.id)
            val size = partial.parts.sumOf { it?.size ?: 0 }
            val out = ByteArray(size)
            var at = 0
            for (part in partial.parts) at += (part?.let { System.arraycopy(it, 0, out, at, it.size); it.size } ?: 0)
            return out
        }

        fun reset() = pending.clear()
    }
}
