package dev.skyward.rf.scan

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.skyward.rf.core.DeviceWatch
import dev.skyward.rf.core.Geo
import dev.skyward.rf.core.RfKind
import dev.skyward.rf.core.WatchHit
import dev.skyward.rf.core.RfObservation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Wi-Fi + BLE scanning, entirely on-device.
 *
 * Wi-Fi: WifiManager.scanResults is polled. Android throttles startScan() (4 per 2 min in
 * the foreground), so we only nudge it and always read the cached result table - that is
 * exactly what a wardriver wants anyway: the freshest table the OS will hand over.
 *
 * BLE: a low-latency scan; every advertisement gives us an address, an RSSI and, for
 * drones broadcasting Remote ID, an ASTM F3411 payload.
 *
 * Both scanners keep a rolling table keyed by address so a single drive fix can snapshot
 * "everything heard in the last few seconds".
 */
class RfScanner(private val context: Context) {

    companion object {
        /** Observations older than this are dropped. */
        const val STALE_MS = 15_000L
    }

    /** One ESP32 advertisement, kept with its address so the roster can key on it. */
    data class Esp32Advert(
        val address: String,
        val rssi: Int,
        val advert: Esp32Codec.Advert,
        val seenAt: Long,
    )

    data class Snapshot(
        val wifi: List<RfObservation>,
        val ble: List<RfObservation>,
        val drones: Map<String, OdidParser.DroneFrame>,
        val esp32: List<Esp32Advert> = emptyList(),
    ) {
        val all: List<RfObservation> get() = wifi + ble
    }

    private val wifiTable = ConcurrentHashMap<String, RfObservation>()
    private val bleTable = ConcurrentHashMap<String, RfObservation>()
    private val droneFrames = ConcurrentHashMap<String, OdidParser.DroneFrame>()
    private val esp32Table = ConcurrentHashMap<String, Esp32Advert>()

    /** Bluetooth company id per advertiser, read from the AD payload; Flock's hardware uses one. */
    private val companyIds = ConcurrentHashMap<String, Int>()

    // Sweeps from a wired ESP32 board. Kept apart from the handset's tables because the Wi-Fi
    // poller prunes every BSSID it did not hear itself, which would wipe a board's findings a
    // few seconds after they arrived.
    private val boardWifi = ConcurrentHashMap<String, RfObservation>()
    private val boardBle = ConcurrentHashMap<String, RfObservation>()

    /**
     * Folds a board's own sweep into the picture the handset's radios fill, so the networks and
     * advertisers it named reach the layer counts, the map and the drive record exactly like
     * the phone's own findings. Entries age out on the normal [STALE_MS] window, so pulling the
     * cable clears them without a teardown step.
     */
    /** One observation that came from something other than this handset's own radios. */
    data class RemoteObs(
        val id: String,
        val name: String,
        val rssi: Int,
        val channel: Int = 0,
    )

    fun ingestSweep(sweep: Esp32Console.Sweep, now: Long = System.currentTimeMillis()) {
        ingestRemote(
            wifi = sweep.aps.map { RemoteObs(it.bssid, it.ssid, it.rssi, it.channel) },
            ble = sweep.devices.map { RemoteObs(it.address, it.name, it.rssi) },
            via = "board",
            now = now,
        )
    }

    /**
     * Observations from a second phone on the same network.
     *
     * They go into the same side tables a board's sweeps use, not the handset's own: the Wi-Fi
     * poller prunes everything it did not hear itself, so a peer's findings would vanish on its
     * next pass. They age out on the normal window, so a phone that walks out of range stops
     * contributing without needing a teardown.
     */
    fun ingestRemote(
        wifi: List<RemoteObs>,
        ble: List<RemoteObs>,
        via: String = "peer",
        now: Long = System.currentTimeMillis(),
    ) {
        for (ap in wifi) {
            if (ap.id.isBlank()) continue
            // One row per network. This handset's own hearing is never overwritten by the other
            // phone's, and a board on this cable is not overwritten by a peer's board either -
            // which is what makes the two tables agree instead of arguing about an RSSI.
            if (wifiTable.containsKey(ap.id)) continue
            if (via != "board" && boardWifi[ap.id]?.source == "board") continue
            boardWifi[ap.id] = RfObservation(
                kind = RfKind.WIFI,
                id = ap.id,
                name = ap.name.ifBlank { "<hidden>" },
                rssi = ap.rssi,
                channel = ap.channel,
                security = "",
                freqMhz = 0,
                seenAt = now,
                source = via,
            )
        }
        for (d in ble) {
            if (d.id.isBlank()) continue
            if (bleTable.containsKey(d.id)) continue
            if (via != "board" && boardBle[d.id]?.source == "board") continue
            boardBle[d.id] = RfObservation(
                kind = RfKind.BLE,
                id = d.id,
                name = d.name,
                rssi = d.rssi,
                channel = 0,
                security = "",
                freqMhz = 2400,
                seenAt = now,
                source = via,
            )
        }
    }

    /** True while a board is contributing observations of its own. */
    fun hasBoardSweep(): Boolean = boardWifi.isNotEmpty() || boardBle.isNotEmpty()

    /** Rows in the shared table that reached this phone from the other one. */
    fun peerCount(): Int =
        boardWifi.count { it.value.source == "peer" } + boardBle.count { it.value.source == "peer" }

    /**
     * The rows that reached this phone from the other one, strongest first.
     *
     * The host's INTEL tab lists these by name: what the peer posted sits in the same table as
     * the host's own findings, so it is obvious which radio on which phone heard it.
     */
    fun peerRows(limit: Int = 3, now: Long = System.currentTimeMillis()): List<RfObservation> =
        (boardWifi.values + boardBle.values)
            .filter { it.source == "peer" && now - it.seenAt <= STALE_MS }
            .sortedByDescending { it.rssi }
            .take(limit)

    /**
     * The rows a board on the cable swept, strongest first.
     *
     * Named rather than only counted: a board is a second radio with its own antenna, and the
     * host's INTEL table is where what it heard has to appear - a total alone cannot be told
     * apart from a network the phone itself heard.
     */
    fun boardRows(limit: Int = 3, now: Long = System.currentTimeMillis()): List<RfObservation> =
        (boardWifi.values + boardBle.values)
            .filter { it.source == "board" && now - it.seenAt <= STALE_MS }
            .sortedByDescending { it.rssi }
            .take(limit)

    /** Rows in the shared table that a wired board swept. */
    fun boardCount(): Int =
        boardWifi.count { it.value.source == "board" } + boardBle.count { it.value.source == "board" }

    /** The board's own intel, apart from the handset's sweeps: what the peer link carries. */
    data class BoardIntel(
        val wifi: List<RfObservation>,
        val ble: List<RfObservation>,
        val wifiTotal: Int,
        val bleTotal: Int,
    )

    /**
     * What a board on the cable has swept, for the other phone.
     *
     * Separate from [snapshot] on purpose: the handset's tables are the bulk of a sweep and are
     * capped at the strongest few, which would drop a board's findings on a busy street. The
     * totals count everything the board heard, not just what one message can hold.
     */
    fun boardIntel(limit: Int = 16, now: Long = System.currentTimeMillis()): BoardIntel {
        fun clean(map: Map<String, RfObservation>) = map.values
            .filter { it.source == "board" && now - it.seenAt <= STALE_MS }
            .sortedByDescending { it.rssi }
            .take(limit)
        val t = totals()
        return BoardIntel(
            wifi = clean(boardWifi),
            ble = clean(boardBle),
            wifiTotal = (t.wifi - wifiTable.size).coerceAtLeast(0),
            bleTotal = (t.ble - bleTable.size).coerceAtLeast(0),
        )
    }

    /** How full the tables are, beyond the per-snapshot limit the peer link can carry. */
    data class Totals(val wifi: Int, val ble: Int)

    /**
     * What this device contributes to the shared table: the handset's own radios plus any board
     * on the cable. The other phone's findings are excluded on purpose - they sit in the same
     * side tables, so reporting them back would make each exchange echo the peer's own sweeps
     * and the shared count would grow on every message instead of settling.
     */
    /**
     * The size of the one shared table.
     *
     * Distinct ids across every radio that contributed: this handset's own sweeps, a board on
     * the cable and the other phone's. Counting per source and adding the two devices' totals
     * counted every network the pair both heard twice - the number that never matched.
     */
    fun totals(): Totals = Totals(
        wifi = wifiTable.keys.union(boardWifi.keys).size,
        ble = bleTable.keys.union(boardBle.keys).size,
    )

    private val main = Handler(Looper.getMainLooper())
    private var wifiPolling = false
    private var bleScanning = false

    // Per-radio enable flags, exposed so the UI can toggle Wi-Fi / BLE off entirely.
    @Volatile
    private var wifiEnabledFlag = true

    @Volatile
    private var bleEnabledFlag = true

    private val _wifiEnabled = MutableStateFlow(true)
    val wifiEnabled: StateFlow<Boolean> = _wifiEnabled

    private val _bleEnabled = MutableStateFlow(true)
    val bleEnabled: StateFlow<Boolean> = _bleEnabled

    /** Turning Wi-Fi off drops the scan table, so its count falls to zero immediately. */
    fun setWifiEnabled(enabled: Boolean) {
        wifiEnabledFlag = enabled
        _wifiEnabled.value = enabled
        if (enabled) {
            lastWifiError = null
            startWifi()
        } else {
            stopWifi()
            wifiTable.clear()
            lastWifiError = null
        }
    }

    /** Turning BLE off also clears Remote ID frames, since those arrive in advertisements. */
    fun setBleEnabled(enabled: Boolean) {
        bleEnabledFlag = enabled
        _bleEnabled.value = enabled
        if (enabled) {
            lastBleError = null
            startBle()
        } else {
            stopBle()
            bleTable.clear()
            droneFrames.clear()
            lastBleError = null
        }
    }

    @Volatile
    var lastWifiError: String? = null
        private set

    @Volatile
    var lastBleError: String? = null
        private set

    // ---------------- permissions ----------------

    fun hasWifiPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 33) {
            val nearby = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ) == PackageManager.PERMISSION_GRANTED
            return fine && nearby
        }
        return fine
    }

    fun hasBlePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= 31) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    // ---------------- wi-fi ----------------

    private val wifiPoll = object : Runnable {
        override fun run() {
            if (!wifiPolling) return
            pollWifi()
            main.postDelayed(this, 5_000L)
        }
    }

    @SuppressLint("MissingPermission")
    private fun pollWifi() {
        if (!wifiEnabledFlag) return
        if (!hasWifiPermission()) {
            lastWifiError = "Wi-Fi scan permission missing"
            return
        }
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm == null) {
            lastWifiError = "No Wi-Fi service"
            return
        }
        if (!wm.isWifiEnabled) {
            lastWifiError = "Wi-Fi radio is off"
            return
        }
        runCatching { wm.startScan() } // throttled by the OS; ignore the boolean
        val now = System.currentTimeMillis()
        val results = runCatching { wm.scanResults }.getOrElse {
            lastWifiError = it.message ?: "scanResults failed"
            return
        }
        lastWifiError = null
        val ids = HashSet<String>()
        for (r in results) {
            val bssid = r.BSSID ?: continue
            ids.add(bssid)
            val ssid = if (r.SSID.isNullOrBlank()) "<hidden>" else r.SSID
            wifiTable[bssid] = RfObservation(
                kind = RfKind.WIFI,
                id = bssid,
                name = ssid,
                rssi = r.level,
                channel = freqToChannel(r.frequency),
                security = securityOf(r.capabilities),
                freqMhz = r.frequency,
                seenAt = now,
            )
        }
        // Age out everything that vanished from the table.
        val it = wifiTable.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.seenAt > STALE_MS || !ids.contains(e.key)) it.remove()
        }
    }

    private fun freqToChannel(freqMhz: Int): Int = when {
        freqMhz == 2484 -> 14
        freqMhz in 2412..2472 -> (freqMhz - 2407) / 5
        freqMhz in 5150..5895 -> (freqMhz - 5000) / 5
        freqMhz in 5955..7115 -> (freqMhz - 5950) / 5
        else -> 0
    }

    private fun securityOf(caps: String?): String {
        val c = caps ?: return "OPEN"
        return when {
            c.contains("WPA3", true) -> "WPA3"
            c.contains("WPA2", true) -> "WPA2"
            c.contains("WPA", true) -> "WPA"
            c.contains("WEP", true) -> "WEP"
            c.contains("OWE", true) -> "OWE"
            else -> "OPEN"
        }
    }

    // ---------------- bluetooth LE ----------------

    private fun adapter(): BluetoothAdapter? {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return mgr?.adapter
    }

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScan(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleScan(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            lastBleError = "BLE scan failed (code $errorCode)"
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleScan(result: ScanResult) {
        if (!hasBlePermission()) return
        val device = result.device ?: return
        val address = runCatching { device.address }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        val record = result.scanRecord
        companyId(record?.bytes)?.let { companyIds[address] = it }
        val name = record?.deviceName?.takeIf { it.isNotBlank() }
            ?: runCatching { device.name }.getOrNull()
            ?: ""

        bleTable[address] = RfObservation(
            kind = RfKind.BLE,
            id = address,
            name = name,
            rssi = result.rssi,
            channel = 0,
            security = if (name.startsWith("Meshtastic", true)) "MESH" else "",
            freqMhz = 2400,
            seenAt = now,
        )

        // ESP32 boards ride in manufacturer-specific data, so they are decoded from every
        // advertisement rather than needing a connection.
        val esp = Esp32Codec.extract(record?.bytes)
        if (esp != null) {
            Esp32Codec.decode(esp)?.let { advert ->
                esp32Table[address] = Esp32Advert(address, result.rssi, advert, now)
            }
        }

        val odid = OdidParser.extractServiceData(record?.bytes)
        if (odid != null) {
            val frame = OdidParser.parse(odid) ?: return
            val prev = droneFrames[address]
            droneFrames[address] = if (prev == null) frame else merge(prev, frame)
        }
    }

    private fun merge(old: OdidParser.DroneFrame, new: OdidParser.DroneFrame) = OdidParser.DroneFrame(
        uasId = new.uasId ?: old.uasId,
        idType = if (new.idType >= 0) new.idType else old.idType,
        lat = new.lat ?: old.lat,
        lon = new.lon ?: old.lon,
        geodeticAltM = new.geodeticAltM ?: old.geodeticAltM,
        pressureAltM = new.pressureAltM ?: old.pressureAltM,
        heightM = new.heightM ?: old.heightM,
        speedMs = new.speedMs ?: old.speedMs,
        headingDeg = new.headingDeg ?: old.headingDeg,
        verticalSpeedMs = new.verticalSpeedMs ?: old.verticalSpeedMs,
        status = if (new.status >= 0) new.status else old.status,
        operatorLat = new.operatorLat ?: old.operatorLat,
        operatorLon = new.operatorLon ?: old.operatorLon,
    )

    @SuppressLint("MissingPermission")
    fun startBle() {
        if (bleScanning || !bleEnabledFlag) return
        if (!hasBlePermission()) {
            lastBleError = "Bluetooth scan permission missing"
            return
        }
        val ad = adapter()
        if (ad == null || !ad.isEnabled) {
            lastBleError = "Bluetooth is off"
            return
        }
        val scanner = ad.bluetoothLeScanner
        if (scanner == null) {
            lastBleError = "BLE scanner unavailable"
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        runCatching { scanner.startScan(null, settings, bleCallback) }
            .onSuccess {
                bleScanning = true
                lastBleError = null
            }
            .onFailure { lastBleError = it.message ?: "BLE start failed" }
    }

    @SuppressLint("MissingPermission")
    fun stopBle() {
        if (!bleScanning) return
        val ad = adapter() ?: return
        runCatching { ad.bluetoothLeScanner?.stopScan(bleCallback) }
        bleScanning = false
    }

    // ---------------- lifecycle ----------------

    fun startWifi() {
        if (wifiPolling || !wifiEnabledFlag) return
        wifiPolling = true
        main.post(wifiPoll)
    }

    fun stopWifi() {
        wifiPolling = false
        main.removeCallbacks(wifiPoll)
    }

    fun startAll() {
        startWifi()
        startBle()
    }

    fun stopAll() {
        stopWifi()
        stopBle()
    }

    /** Fresh snapshot, newest / strongest first, aged out. */
    fun snapshot(limit: Int = 60): Snapshot {
        val now = System.currentTimeMillis()
        // Wi-Fi was pruned by its poller but the BLE table never was, so a drive banked every
        // randomised MAC it had ever heard and each snapshot sorted the whole history. Prune
        // both here, in the same window the filter below uses, and drop Remote ID frames whose
        // advertiser has gone quiet - otherwise the tables only ever grow while the app runs.
        prune(wifiTable, now) { it.seenAt }
        prune(bleTable, now) { it.seenAt }
        prune(esp32Table, now) { it.seenAt }
        droneFrames.keys.retainAll(bleTable.keys)
        fun clean(map: Map<String, RfObservation>) = map.values
            .filter { now - it.seenAt <= STALE_MS }
            .sortedByDescending { it.rssi }
            .take(limit)

        prune(boardWifi, now) { it.seenAt }
        prune(boardBle, now) { it.seenAt }
        // One table, not three. A shared id keeps the handset's copy - it carries a real
        // channel and security string - then a board's, then the other phone's. `map + map`
        // would have let the side tables overwrite the phone's own hearing, so a network seen
        // by both phones would flip to "peer" and the two counts would never line up.
        val mergedWifi = HashMap(wifiTable)
        for ((k, v) in boardWifi) mergedWifi.putIfAbsent(k, v)
        val mergedBle = HashMap(bleTable)
        for ((k, v) in boardBle) mergedBle.putIfAbsent(k, v)
        val wifi = clean(mergedWifi)
        val ble = clean(mergedBle)
        return Snapshot(
            wifi = wifi,
            ble = ble,
            drones = HashMap(droneFrames),
            esp32 = esp32Table.values.toList(),
        )
    }

    private fun <T> prune(table: ConcurrentHashMap<String, T>, now: Long, seenAt: (T) -> Long) {
        val it = table.entries.iterator()
        while (it.hasNext()) {
            if (now - seenAt(it.next().value) > STALE_MS) it.remove()
        }
    }

    fun clear() {
        wifiTable.clear()
        bleTable.clear()
        droneFrames.clear()
        esp32Table.clear()
        companyIds.clear()
        boardWifi.clear()
        boardBle.clear()
    }

    /**
     * Surveillance-hardware matches among everything currently in the tables.
     *
     * Everything the radios already hold is examined - the handset's own sweeps, a wired
     * board's, and the other phone's - because a camera does not care which radio heard it.
     * The match itself is name and manufacturer-id based; see [DeviceWatch] for what that can
     * and cannot recognise.
     */
    fun watchHits(now: Long = System.currentTimeMillis()): List<WatchHit> {
        val out = ArrayList<WatchHit>(8)
        fun look(table: Map<String, RfObservation>) {
            for (o in table.values) {
                if (now - o.seenAt > STALE_MS) continue
                val verdict = DeviceWatch.assess(o.kind, o.name, o.id, companyIds[o.id] ?: 0) ?: continue
                out.add(
                    WatchHit(
                        kind = verdict.kind,
                        id = o.id,
                        name = o.name,
                        rssi = o.rssi,
                        radio = o.kind,
                        source = o.source,
                        at = o.seenAt,
                        confidence = verdict.confidence,
                    ),
                )
            }
        }
        look(wifiTable)
        look(bleTable)
        look(boardWifi)
        look(boardBle)
        return out.distinctBy { "${it.kind.name}:${it.id}" }.sortedByDescending { it.rssi }
    }

    /**
     * AD structures are length, type, data. Type 0xFF carries a 16-bit company id,
     * little-endian, and that is the one field a BLE advertiser cannot easily hide.
     */
    private fun companyId(bytes: ByteArray?): Int? {
        val b = bytes ?: return null
        var i = 0
        while (i < b.size) {
            val len = b[i].toInt() and 0xFF
            if (len == 0) break
            if (i + 3 < b.size && (b[i + 1].toInt() and 0xFF) == 0xFF && len >= 3) {
                return (b[i + 2].toInt() and 0xFF) or ((b[i + 3].toInt() and 0xFF) shl 8)
            }
            i += len + 1
        }
        return null
    }

    /** Exposed for the UI: score of a single observation. */
    fun scoreOf(o: RfObservation): Int = Geo.rssiToScore(o.rssi)
}
