package dev.skyward.rf.wardrive

import dev.skyward.rf.core.FileStore
import dev.skyward.rf.core.Geo
import dev.skyward.rf.core.RfKind
import dev.skyward.rf.core.RfObservation
import dev.skyward.rf.core.SignalScale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every Wi-Fi network and BLE device that was heard together with a position.
 *
 * The drive path records where the vehicle was and how strong the best signal was; this records
 * the individual signals, each at the point where it was actually heard, with the bin colour it
 * earned. That distinction matters when a second phone or a board supplies observations: their
 * networks belong at *their* fix, not at this handset's, or a signal would be credited to the
 * wrong street.
 *
 * One entry per network, keeping the strongest hearing: a weaker repeat would repaint a
 * known-strong network red and say nothing new.
 */
@Serializable
data class LoggedSignal(
    val id: String,
    val kind: RfKind,
    val name: String,
    val rssi: Int,
    val score: Int,
    val bin: Int,
    val color: String,
    val lat: Double,
    val lon: Double,
    val at: Long,
    /** "local", "peer" or "board": which radio did the listening. */
    val via: String,
    /** Wi-Fi channel. BLE has no channel number, so it stays 0. */
    val channel: Int = 0,
)

class SignalLog(private val store: FileStore) {

    companion object {
        private const val FILE = "signals_v1.json"
        private const val MAX = 5_000
    }

    private val lock = Any()
    private val table = LinkedHashMap<String, LoggedSignal>()

    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count

    /** The whole table, for anything that has to draw or send it. Replaced on every change. */
    private val _entries = MutableStateFlow<List<LoggedSignal>>(emptyList())
    val entries: StateFlow<List<LoggedSignal>> = _entries

    init {
        store.read<List<LoggedSignal>>(FILE)?.forEach { table[keyOf(it.kind, it.id)] = it }
        _count.value = table.size
        _entries.value = table.values.toList()
    }

    private fun keyOf(kind: RfKind, id: String) = kind.name + ":" + id

    private fun hex(argb: Int) = String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

    /**
     * Records [observations] at one fix. A null or non-finite fix drops them: a signal with no
     * position cannot be given a colour that means anything on a map.
     */
    fun record(
        observations: Collection<RfObservation>,
        lat: Double?,
        lon: Double?,
        via: String,
        now: Long = System.currentTimeMillis(),
    ) {
        if (lat == null || lon == null) return
        if (!lat.isFinite() || !lon.isFinite()) return
        synchronized(lock) {
            for (o in observations) {
                if (o.id.isBlank()) continue
                val score = Geo.rssiToScore(o.rssi)
                val key = keyOf(o.kind, o.id)
                val previous = table[key]
                if (previous != null && previous.score >= score) continue
                table[key] = LoggedSignal(
                    id = o.id,
                    kind = o.kind,
                    name = o.name,
                    rssi = o.rssi,
                    score = score,
                    bin = SignalScale.binOfScore(score),
                    color = hex(SignalScale.colorOfScore(score)),
                    lat = lat,
                    lon = lon,
                    at = now,
                    via = via,
                    channel = o.channel,
                )
            }
            while (table.size > MAX) {
                val oldest = table.keys.firstOrNull() ?: break
                table.remove(oldest)
            }
            publish()
        }
    }

    /** Caller holds [lock]. */
    private fun publish() {
        _count.value = table.size
        _entries.value = table.values.toList()
    }

    fun snapshot(): List<LoggedSignal> = synchronized(lock) { table.values.toList() }

    fun save() {
        val list = snapshot()
        store.write(FILE, list)
    }

    fun clear() {
        synchronized(lock) { table.clear() }
        publish()
        store.write(FILE, emptyList<LoggedSignal>())
    }

    fun exportCsv(): String {
        val sb = StringBuilder("kind,id,name,rssi,score,bin,color,lat,lon,at,via\n")
        for (s in snapshot()) {
            sb.append(s.kind.name).append(',')
                .append(s.id).append(',')
                .append('"').append(s.name.replace("\"", "\"\"")).append('"').append(',')
                .append(s.rssi).append(',')
                .append(s.score).append(',')
                .append(s.bin).append(',')
                .append(s.color).append(',')
                .append(String.format(Locale.US, "%.6f", s.lat)).append(',')
                .append(String.format(Locale.US, "%.6f", s.lon)).append(',')
                .append(s.at).append(',')
                .append(s.via).append('\n')
        }
        return sb.toString()
    }

    /**
     * WiGLE's own CSV dialect, not a bespoke one: the upload endpoint validates the header and
     * the column order, and a file it cannot parse is rejected wholesale.
     *
     * Commas are the field separator and the format has no quoting, so a name carrying one is
     * flattened rather than allowed to shift every following column.
     */
    fun exportWigleCsv(): String {
        val sb = StringBuilder()
        sb.append("WigleWifi-1.6,appRelease=1.0,model=Android,release=Android,")
            .append("device=Android,display=,board=,brand=Android,starred=no,comment=Signal_IntelGo\n")
        sb.append("MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,")
            .append("CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type\n")
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        for (s in snapshot()) {
            // WiGLE only knows WIFI and BLE rows; a sub-GHz carrier has no MAC, no SSID and
            // no channel, and writing one would either fail the upload or invent a network.
            if (s.kind == RfKind.SUBGHZ) continue
            val ssid = s.name.replace(',', ' ').replace('"', ' ').trim()
            sb.append(s.id).append(',')
                .append(ssid).append(',')
                .append(if (s.kind == RfKind.WIFI) "[ESS]" else "").append(',')
                .append(df.format(Date(s.at))).append(',')
                .append(s.channel).append(',')
                .append(s.rssi).append(',')
                .append(String.format(Locale.US, "%.6f", s.lat)).append(',')
                .append(String.format(Locale.US, "%.6f", s.lon)).append(',')
                .append("0,0,")
                .append(if (s.kind == RfKind.WIFI) "WIFI" else "BLE").append('\n')
        }
        return sb.toString()
    }

    fun exportGeoJson(): String {
        val sb = StringBuilder()
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        for (s in snapshot()) {
            if (!first) sb.append(',')
            first = false
            val name = s.name.replace("\\", "\\\\").replace("\"", "\\\"")
            sb.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
                .append(String.format(Locale.US, "%.6f", s.lon)).append(',')
                .append(String.format(Locale.US, "%.6f", s.lat)).append("]},")
                .append("\"properties\":{\"kind\":\"").append(s.kind.name.lowercase()).append("\",")
                .append("\"id\":\"").append(s.id).append("\",")
                .append("\"name\":\"").append(name).append("\",")
                .append("\"rssi\":").append(s.rssi).append(',')
                .append("\"score\":").append(s.score).append(',')
                .append("\"bin\":").append(s.bin).append(',')
                .append("\"color\":\"").append(s.color).append("\",")
                .append("\"via\":\"").append(s.via).append("\",")
                .append("\"t\":").append(s.at).append("}}")
        }
        sb.append("]}")
        return sb.toString()
    }
}
