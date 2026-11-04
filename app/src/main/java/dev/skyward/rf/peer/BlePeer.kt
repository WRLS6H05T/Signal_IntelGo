package dev.skyward.rf.peer

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import dev.skyward.rf.core.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

/**
 * A second phone, linked over Bluetooth Low Energy.
 *
 * Both phones advertise the same service and both scan for it, so either one finds the other
 * first. To keep one link instead of two crossed ones, only the phone with the smaller id dials;
 * the other stays connectable and accepts. One GATT connection then carries both directions:
 * the central writes to [DOWN] and the peripheral notifies on [UP].
 *
 * While linked, each side keeps scanning Wi-Fi and BLE with the app's normal scanners and sends
 * what it holds every few seconds. So the link is a second radio, not a replacement: a phone
 * paired with another still wardrives on its own, and each side ends up with both sets of
 * observations.
 *
 * BLE is a narrow pipe - twenty bytes per write until the MTU is negotiated - so messages are
 * chunked ([PeerFrames]) and capped in size. A sweep that does not fit is dropped whole rather
 * than sent truncated; the next one is a few seconds away.
 */
class BlePeer(private val context: Context) {

    companion object {
        val SERVICE: UUID = UUID.fromString("7a1f5e10-3c6b-4a2e-9f1d-5b8c2d4e6f70")

        /** Peripheral to central: notifications carrying this phone's sweeps. */
        val UP: UUID = UUID.fromString("7a1f5e11-3c6b-4a2e-9f1d-5b8c2d4e6f70")

        /** Central to peripheral: writes carrying the other phone's sweeps. */
        val DOWN: UUID = UUID.fromString("7a1f5e12-3c6b-4a2e-9f1d-5b8c2d4e6f70")

        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val SEND_MS = 5_000L
        private const val TICK_MS = 40L
        private const val MAX_ITEMS = 32
        private const val NAME_CHARS = 20
        private const val MAX_MESSAGE = 4_800

        /** How long one drive part may wait before the next is queued, so control messages fit. */
        private const val PART_GAP_MS = 150L

        /** How many drives are offered at once; more would not fit one message. */
        private const val MAX_OFFERS = 12
    }

    /** What this device offers the other one. */
    data class Snapshot(
        val fix: LatLon?,
        val sats: Int,
        val wifi: List<PeerProtocol.Ap>,
        val ble: List<PeerProtocol.Device>,
        /** Full table sizes; [wifi] and [ble] are only what one message can carry. */
        val wifiTotal: Int = 0,
        val bleTotal: Int = 0,
        /** Whether this phone is using a GPS fix, and whether it is hearing cell towers. */
        val gpsUsed: Boolean = false,
        val cellUsed: Boolean = false,
    )

    data class State(
        val running: Boolean = false,
        val advertises: Boolean = false,
        val scanning: Boolean = false,
        val connected: Boolean = false,
        val peerName: String = "",
        val role: String = "",
        /** What the other device is showing, from its own side of the size comparison. */
        val peerRole: String = "",
        val peerWifiTotal: Int = 0,
        val peerBleTotal: Int = 0,
        /** The other phone's own answer to whether it is using GPS and cellular. */
        val peerGps: Boolean = false,
        val peerCell: Boolean = false,
        val detail: String = "off",
        val sent: Int = 0,
        val received: Int = 0,
        val peerWifi: Int = 0,
        val peerBle: Int = 0,
    )

    private val id: String = UUID.randomUUID().toString().replace("-", "").take(6)

    private val deviceName: String = runCatching { android.os.Build.MODEL }.getOrDefault("Android")

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val _peerFix = MutableStateFlow<LatLon?>(null)
    val peerFix: StateFlow<LatLon?> = _peerFix

    @Volatile
    var onRemote: ((List<PeerProtocol.Ap>, List<PeerProtocol.Device>, LatLon?) -> Unit)? = null

    @Volatile
    var snapshotProvider: (() -> Snapshot)? = null

    /** The drives this phone can offer, and the JSON behind one id. */
    @Volatile
    var logProvider: (() -> List<PeerProtocol.LogInfo>)? = null

    @Volatile
    var logSource: ((String) -> String?)? = null

    /** The other phone's list, and a drive it has finished sending. */
    @Volatile
    var onPeerLogs: ((List<PeerProtocol.LogInfo>) -> Unit)? = null

    @Volatile
    var onPeerLog: ((String, String) -> Unit)? = null

    @Volatile
    private var running = false

    @Volatile
    private var mtuSize = 23

    @Volatile
    private var peerDiagIn = 0f

    @Volatile
    private var sent = 0

    @Volatile
    private var received = 0

    @Volatile
    private var transmitting = false

    private val frameId = java.util.concurrent.atomic.AtomicInteger(0)

    /** Drive parts waiting for the pipe, and the slices of a drive arriving. */
    private val outParts = ArrayDeque<PeerProtocol.LogPart>()

    private val inParts = HashMap<String, HashMap<Int, String>>()

    private val inTotals = HashMap<String, Int>()

    @Volatile
    private var lastPartAt = 0L
    private val assembler = PeerFrames.Assembler()
    private val outbox = ArrayDeque<ByteArray>()
    private val lock = Any()
    private var ticker: ScheduledExecutorService? = null

    private var server: BluetoothGattServer? = null

    @Volatile
    private var connection: BluetoothDevice? = null

    @Volatile
    private var subscription: BluetoothDevice? = null

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var peerAddress: String? = null

    private var advertiserStarted = false
    private var scanningStarted = false

    @Volatile
    private var lastQueuedAt = 0L

    /** Screen diagonal in inches: the only thing that decides which device gets which screen. */
    fun diagonalInches(): Float {
        val dm = context.resources.displayMetrics
        if (dm.densityDpi <= 0 || dm.widthPixels <= 0 || dm.heightPixels <= 0) return 0f
        return hypot(dm.widthPixels.toFloat(), dm.heightPixels.toFloat()) / dm.densityDpi
    }

    fun role(): String = PeerProtocol.roleFor(diagonalInches(), peerDiagIn)

    /** The other phone's screen, as it sees itself: the roles are complementary. */
    fun peerRole(): String = PeerProtocol.roleFor(peerDiagIn, diagonalInches())

    fun isRunning(): Boolean = running

    // ---------------- lifecycle ----------------

    private fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun canScan(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED

    private fun canConnect(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun canAdvertise(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) ==
            PackageManager.PERMISSION_GRANTED

    fun start() {
        if (running) return
        val ad = adapter()
        if (ad == null) {
            _state.value = State(detail = "no Bluetooth adapter")
            return
        }
        if (!ad.isEnabled) {
            _state.value = State(detail = "Bluetooth is off")
            return
        }
        if (!canScan() || !canConnect() || !canAdvertise()) {
            _state.value = State(detail = "Bluetooth permission missing")
            return
        }
        running = true
        _state.value = State(running = true, detail = "starting")
        startServer()
        startAdvertising(ad)
        startScanning(ad)
        ticker = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ble-peer").apply { isDaemon = true }
        }
        ticker?.scheduleWithFixedDelay({ tick() }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        running = false
        runCatching { ticker?.shutdownNow() }
        ticker = null
        stopAdvertising()
        stopScanning()
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        runCatching { server?.clearServices() }
        runCatching { server?.close() }
        server = null
        connection = null
        subscription = null
        peerAddress = null
        peerDiagIn = 0f
        synchronized(lock) {
            outbox.clear()
            outParts.clear()
            inParts.clear()
            inTotals.clear()
        }
        lastPartAt = 0L
        assembler.reset()
        onPeerLogs?.invoke(emptyList())
        _peerFix.value = null
        _state.value = State(detail = "off")
    }

    fun restart() {
        stop()
        start()
    }

    // ---------------- GATT server (peripheral side) ----------------

    private fun startServer() {
        // A GATT server is opened from the BluetoothManager; the adapter only owns the link.
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val s = runCatching { manager?.openGattServer(context, serverCallback) }.getOrNull() ?: return
        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val up = BluetoothGattCharacteristic(
            UP,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        up.addDescriptor(
            BluetoothGattDescriptor(
                CCCD,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        val down = BluetoothGattCharacteristic(
            DOWN,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        service.addCharacteristic(up)
        service.addCharacteristic(down)
        runCatching { s.addService(service) }
        server = s
    }

    private fun startAdvertising(ad: BluetoothAdapter) {
        if (advertiserStarted) return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            // Service data alone carries both the service UUID and this phone's id: a 128-bit
            // UUID plus a separate UUID field would overrun the 31-byte advertisement and the
            // stack rejects the whole thing instead of trimming it.
            .addServiceData(ParcelUuid(SERVICE), id.toByteArray())
            .setIncludeDeviceName(false)
            .build()
        runCatching {
            ad.bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
            advertiserStarted = true
            _state.value = _state.value.copy(advertises = true)
        }
    }

    private fun stopAdvertising() {
        if (!advertiserStarted) return
        advertiserStarted = false
        runCatching { adapter()?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        _state.value = _state.value.copy(advertises = false)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            advertiserStarted = false
            _state.value = _state.value.copy(advertises = false, detail = "cannot advertise ($errorCode)")
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connection = device
                stopAdvertising()
                _state.value = _state.value.copy(
                    connected = true,
                    peerName = runCatching { device?.name }.getOrNull() ?: "BLE peer",
                    role = role(),
                    detail = "linked",
                )
                // Both ends offer their drives straight away; the lists are small.
                sendLogList()
            } else {
                connection = null
                subscription = null
                _state.value = _state.value.copy(
                    connected = false,
                    peerName = "",
                    peerWifi = 0,
                    peerBle = 0,
                    peerWifiTotal = 0,
                    peerBleTotal = 0,
                    peerRole = "",
                    peerGps = false,
                    peerCell = false,
                    detail = if (running) "waiting for the other device" else "off",
                )
                onPeerLogs?.invoke(emptyList())
                synchronized(lock) { outParts.clear() }
                if (running) adapter()?.let { startAdvertising(it) }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (descriptor?.uuid == CCCD) {
                subscription = if (value?.any { it.toInt() != 0 } == true) device else null
            }
            if (responseNeeded) {
                runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value) }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (characteristic?.uuid == DOWN && value != null) ingest(value)
            if (responseNeeded) {
                runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value) }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            mtuSize = mtu.coerceIn(23, 517)
        }
    }

    // ---------------- scanning and connecting (central side) ----------------

    private fun startScanning(ad: BluetoothAdapter) {
        if (scanningStarted) return
        val scanner = runCatching { ad.bluetoothLeScanner }.getOrNull() ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching {
            // No filter: the UUID lives inside service data, and filtering on it is not reliable
            // across Bluetooth stacks. The callback rejects anything without our service data.
            scanner.startScan(null, settings, scanCallback)
            scanningStarted = true
            _state.value = _state.value.copy(scanning = true)
        }
    }

    private fun stopScanning() {
        if (!scanningStarted) return
        scanningStarted = false
        runCatching { adapter()?.bluetoothLeScanner?.stopScan(scanCallback) }
        _state.value = _state.value.copy(scanning = false)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (gatt != null || connection != null) return
            val peerId = runCatching {
                result.scanRecord?.getServiceData(ParcelUuid(SERVICE))?.toString(Charsets.UTF_8)
            }.getOrNull().orEmpty()
            if (peerId.isBlank() || peerId == id) return
            // Both phones advertise and scan, so only the smaller id dials and the other accepts.
            if (id > peerId) return
            val device = result.device ?: return
            peerAddress = runCatching { device.address }.getOrNull()
            runCatching { device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE) }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = g
                stopScanning()
                runCatching { g?.requestMtu(517) }
                runCatching { g?.discoverServices() }
                _state.value = _state.value.copy(connected = true, detail = "linked", role = role())
                sendLogList()
            } else {
                runCatching { g?.close() }
                gatt = null
                _state.value = _state.value.copy(
                    connected = false,
                    peerWifi = 0,
                    peerBle = 0,
                    peerWifiTotal = 0,
                    peerBleTotal = 0,
                    peerRole = "",
                    peerGps = false,
                    peerCell = false,
                    detail = if (running) "searching" else "off",
                )
                onPeerLogs?.invoke(emptyList())
                synchronized(lock) { outParts.clear() }
                adapter()?.let { startScanning(it) }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt?, status: Int) {
            val characteristic = g?.getService(SERVICE)?.getCharacteristic(UP) ?: return
            runCatching { g.setCharacteristicNotification(characteristic, true) }
            val descriptor = characteristic.getDescriptor(CCCD) ?: return
            if (Build.VERSION.SDK_INT >= 33) {
                runCatching {
                    g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }
            } else {
                @Suppress("DEPRECATION")
                runCatching {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(descriptor)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt?, mtu: Int, status: Int) {
            mtuSize = mtu.coerceIn(23, 517)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            ingest(value)
        }

        @Deprecated("Required below Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            characteristic?.value?.let { ingest(it) }
        }
    }

    // ---------------- data ----------------

    private fun ingest(bytes: ByteArray) {
        val message = assembler.add(bytes) ?: return
        val decoded = PeerProtocol.decode(String(message, Charsets.UTF_8)) ?: return
        received++
        if (decoded.diagIn > 0f) peerDiagIn = decoded.diagIn
        val fix = if (decoded.lat != null && decoded.lon != null) LatLon(decoded.lat, decoded.lon) else null
        if (fix != null) _peerFix.value = fix
        _state.value = _state.value.copy(
            peerName = decoded.name.ifBlank { decoded.id },
            role = role(),
            received = received,
            peerRole = peerRole(),
            peerWifi = decoded.wifi.size,
            peerBle = decoded.ble.size,
            // The totals are what the other phone actually holds, not just what got sent.
            peerWifiTotal = if (decoded.wifiTotal > 0) decoded.wifiTotal else decoded.wifi.size,
            peerBleTotal = if (decoded.bleTotal > 0) decoded.bleTotal else decoded.ble.size,
            peerGps = decoded.gpsUsed,
            peerCell = decoded.cellUsed,
        )
        if (decoded.logs.isNotEmpty()) onPeerLogs?.invoke(decoded.logs)
        if (decoded.wantList) sendLogList()
        if (decoded.want.isNotBlank()) startSending(decoded.want)
        decoded.part?.let { takePart(it) }
        if (decoded.wifi.isNotEmpty() || decoded.ble.isNotEmpty() || fix != null) {
            onRemote?.invoke(decoded.wifi, decoded.ble, fix)
        }
    }

    /**
     * One chunk per tick. BLE cannot take a whole message at once, and a characteristic write
     * that is still in flight will fail if another is issued behind it, so the ticker paces the
     * outbox instead of bursting it.
     */
    private fun tick() {
        if (!running) return
        if (outbox.isEmpty() && System.currentTimeMillis() - lastQueuedAt >= SEND_MS) {
            lastQueuedAt = System.currentTimeMillis()
            queueSnapshot()
        }
        pushPartIfDue()
        if (transmitting) return
        val chunk = synchronized(lock) { outbox.removeFirstOrNull() } ?: return
        transmitting = true
        val ok = try {
            if (gatt != null) writeCentral(chunk) else notifyPeripheral(chunk)
        } finally {
            transmitting = false
        }
        if (!ok) {
            // Not ready for this chunk: put it back and try again on the next tick.
            synchronized(lock) { outbox.addFirst(chunk) }
        } else if (synchronized(lock) { outbox.isEmpty() }) {
            // A message counts as sent when its last chunk leaves.
            markSent()
        }
    }

    private fun queueSnapshot() {
        // A drive owns the pipe until its last slice is out: replacing the queue mid-file would
        // leave the other phone holding a transfer that can never complete.
        if (synchronized(lock) { outParts.isNotEmpty() }) return
        val snapshot = snapshotProvider?.invoke() ?: return
        val message = PeerProtocol.Message(
            kind = PeerProtocol.KIND_SKY,
            id = id,
            name = deviceName,
            diagIn = diagonalInches(),
            lat = snapshot.fix?.lat,
            lon = snapshot.fix?.lon,
            sats = snapshot.sats,
            at = System.currentTimeMillis(),
            wifi = snapshot.wifi.take(MAX_ITEMS).map { it.copy(name = it.name.take(NAME_CHARS)) },
            ble = snapshot.ble.take(MAX_ITEMS).map { it.copy(name = it.name.take(NAME_CHARS)) },
            wifiTotal = if (snapshot.wifiTotal > 0) snapshot.wifiTotal else snapshot.wifi.size,
            bleTotal = if (snapshot.bleTotal > 0) snapshot.bleTotal else snapshot.ble.size,
            gpsUsed = snapshot.gpsUsed,
            cellUsed = snapshot.cellUsed,
        )
        val bytes = PeerProtocol.encode(message).toByteArray(Charsets.UTF_8)
        // Too big for the pipe means too big: dropping it whole is honest, truncating it would
        // look like a quiet radio on the other side.
        if (bytes.size > MAX_MESSAGE) return
        val frames = PeerFrames.split(bytes, payloadSize(), frameId.incrementAndGet() and 0xFF)
        if (frames.isEmpty()) return
        synchronized(lock) {
            outbox.clear()
            outbox.addAll(frames)
        }
    }

    /** Offers this phone's recorded drives. Small: twelve names and their counts. */
    fun sendLogList() {
        if (!_state.value.connected) return
        val logs = logProvider?.invoke()?.take(MAX_OFFERS)?.map { it.copy(name = it.name.take(40)) }
            ?: return
        queue(
            PeerProtocol.Message(
                kind = PeerProtocol.KIND_LOGS,
                id = id,
                name = deviceName,
                diagIn = diagonalInches(),
                at = System.currentTimeMillis(),
                logs = logs,
            ),
        )
    }

    /** Asks the other phone to send its list again. */
    fun requestLogList() {
        if (!_state.value.connected) return
        queue(
            PeerProtocol.Message(
                kind = PeerProtocol.KIND_LOGS,
                id = id,
                name = deviceName,
                diagIn = diagonalInches(),
                at = System.currentTimeMillis(),
                wantList = true,
            ),
        )
    }

    /** Asks for one drive by id; it arrives as a run of parts. */
    fun requestLog(logId: String) {
        if (!_state.value.connected || logId.isBlank()) return
        queue(
            PeerProtocol.Message(
                kind = PeerProtocol.KIND_LOGS,
                id = id,
                name = deviceName,
                diagIn = diagonalInches(),
                at = System.currentTimeMillis(),
                want = logId,
            ),
        )
        _state.value = _state.value.copy(detail = "fetching a drive from ${_state.value.peerName}")
    }

    /** Packs the requested drive and queues it a slice at a time. */
    private fun startSending(logId: String) {
        val json = logSource?.invoke(logId) ?: return
        val parts = PeerLogs.pack(json)
        if (parts.isEmpty()) return
        if (parts.size > PeerLogs.MAX_PARTS) {
            // Honest refusal: half a drive would look like a complete one on the other side.
            _state.value = _state.value.copy(detail = "that drive is too large to send")
            return
        }
        synchronized(lock) {
            outParts.clear()
            parts.forEachIndexed { idx, data ->
                outParts.add(PeerProtocol.LogPart(id = logId, idx = idx, total = parts.size, data = data))
            }
        }
        _state.value = _state.value.copy(detail = "sending a drive to ${_state.value.peerName}")
    }

    private fun pushPartIfDue() {
        val next = synchronized(lock) { outParts.firstOrNull() } ?: return
        val now = System.currentTimeMillis()
        if (now - lastPartAt < PART_GAP_MS) return
        val queued = queue(
            PeerProtocol.Message(
                kind = PeerProtocol.KIND_LOGS,
                id = id,
                name = deviceName,
                diagIn = diagonalInches(),
                at = now,
                part = next,
            ),
        )
        synchronized(lock) {
            if (queued) outParts.removeFirstOrNull() else outParts.clear()
        }
        lastPartAt = now
        if (!queued) _state.value = _state.value.copy(detail = "could not send the drive")
    }

    /** Keeps the slices of a drive until the last one lands, then hands the JSON over. */
    private fun takePart(part: PeerProtocol.LogPart) {
        if (part.id.isBlank() || part.data.isEmpty()) return
        if (part.total <= 0 || part.total > PeerLogs.MAX_PARTS || part.idx !in 0 until part.total) return
        val complete = synchronized(lock) {
            val total = inTotals[part.id]
            if (total != null && total != part.total) {
                // A newer transfer of the same drive: start over rather than splice two files.
                inParts.remove(part.id)
                inTotals.remove(part.id)
            }
            inTotals[part.id] = part.total
            val slices = inParts.getOrPut(part.id) { HashMap() }
            slices[part.idx] = part.data
            if (slices.size < part.total) return@synchronized null
            val ordered = (0 until part.total).map { slices[it] ?: return@synchronized null }
            inParts.remove(part.id)
            inTotals.remove(part.id)
            ordered
        } ?: return
        val json = PeerLogs.unpack(complete) ?: return
        val from = _state.value.peerName.ifBlank { "peer" }
        onPeerLog?.invoke(json, from)
        _state.value = _state.value.copy(detail = "received a drive from $from")
    }

    /** Encodes one message into frames and appends them; false when it cannot be sent. */
    private fun queue(message: PeerProtocol.Message): Boolean {
        val bytes = PeerProtocol.encode(message).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_MESSAGE) return false
        val frames = PeerFrames.split(bytes, payloadSize(), frameId.incrementAndGet() and 0xFF)
        if (frames.isEmpty()) return false
        synchronized(lock) { outbox.addAll(frames) }
        return true
    }

    private fun payloadSize(): Int = (mtuSize - PeerFrames.HEADER).coerceIn(16, 240)

    private fun writeCentral(chunk: ByteArray): Boolean {
        val g = gatt ?: return false
        val characteristic = g.getService(SERVICE)?.getCharacteristic(DOWN) ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(
                    characteristic,
                    chunk,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = chunk
                @Suppress("DEPRECATION")
                g.writeCharacteristic(characteristic)
            }
        }.getOrDefault(false)
    }

    private fun notifyPeripheral(chunk: ByteArray): Boolean {
        val device = subscription ?: return false
        val s = server ?: return false
        val characteristic = s.getService(SERVICE)?.getCharacteristic(UP) ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                s.notifyCharacteristicChanged(device, characteristic, false, chunk) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = chunk
                @Suppress("DEPRECATION")
                s.notifyCharacteristicChanged(device, characteristic, false)
            }
        }.getOrDefault(false)
    }

    /** Called once per ticker pass so the send counter reflects what actually went out. */
    private fun markSent() {
        sent++
        _state.value = _state.value.copy(sent = sent)
    }
}
