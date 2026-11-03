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
import dev.skyward.rf.core.Geo
import dev.skyward.rf.core.RfKind
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
    fun ingestSweep(sweep: Esp32Console.Sweep, now: Long = System.currentTimeMillis()) {
        for (ap in sweep.aps) {
            boardWifi[ap.bssid] = RfObservation(
                kind = RfKind.WIFI,
                id = ap.bssid,
                name = ap.ssid.ifBlank { "<hidden>" },
                rssi = ap.rssi,
                channel = ap.channel,
                security = "",
                freqMhz = 0,
                seenAt = now,
            )
        }
        for (d in sweep.devices) {
            boardBle[d.address] = RfObservation(
                kind = RfKind.BLE,
                id = d.address,
                name = d.name,
                rssi = d.rssi,
                channel = 0,
                security = "",
                freqMhz = 2400,
                seenAt = now,
            )
        }
    }

    /** True while a board is contributing observations of its own. */
    fun hasBoardSweep(): Boolean = boardWifi.isNotEmpty() || boardBle.isNotEmpty()

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
        // A duplicate keeps the handset's copy: it carries a real channel and security string.
        val wifi = clean(wifiTable + boardWifi)
        val ble = clean(bleTable + boardBle)
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
        boardWifi.clear()
        boardBle.clear()
    }

    /** Exposed for the UI: score of a single observation. */
    fun scoreOf(o: RfObservation): Int = Geo.rssiToScore(o.rssi)
}
