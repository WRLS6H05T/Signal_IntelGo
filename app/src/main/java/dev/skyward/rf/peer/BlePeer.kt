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
        synchronized(lock) { outbox.clear() }
        assembler.reset()
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
