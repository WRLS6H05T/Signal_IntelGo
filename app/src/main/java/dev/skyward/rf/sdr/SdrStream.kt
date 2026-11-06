package dev.skyward.rf.sdr

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.skyward.rf.core.CrashLog
import dev.skyward.rf.core.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The RTL-SDR dongle on the phone's USB port.
 *
 * An RTL2832U (0bda:2832, or 0bda:2838 with an R820T/R828D tuner in front of it) is a raw I/Q
 * receiver: it has no console, no identity and no commands, so none of [dev.skyward.rf.scan.UsbBoards]
 * applies to it. It is recognised from its descriptors alone, permission is asked for once, and
 * the bulk endpoint is read for as long as the cable is in.
 *
 * **What is not here.** Programming the tuner - the demod init and the R820T/R828D PLL - is what
 * gives I/Q meaning, and that driver is not in this file. Until it is, the panel says it is
 * waiting for I/Q rather than drawing a trace it cannot justify: a flat noise floor is honest,
 * a synthesised spectrum is not. Everything below the tuner - the pipe, the transform, the
 * recording, the saved trace and its pin - is real and is what the panel, the settings switch
 * and the map use.
 */
class SdrStream(private val context: Context, private val library: SdrLibrary) {

    companion object {
        private const val ACTION_PERMISSION = "dev.skyward.rf.SDR_PERMISSION"

        /** Realtek. 0x2832 is the bare receiver; 0x2838 is the R820T/R828D bundle. */
        private const val VID_REALTEK = 0x0BDA
        private val PIDS = setOf(0x2832, 0x2838)

        /** True when these descriptors are an RTL-SDR rather than a board with a serial console. */
        fun isDongle(vendorId: Int, productId: Int): Boolean =
            vendorId == VID_REALTEK && productId in PIDS

        private const val READ_TIMEOUT_MS = 200
        /** Four frames a second: fast enough to watch a carrier, slow enough to read it. */
        private const val FRAME_MS = 250L
        /** Samples kept for one transform; 2048 frames of 8-bit I/Q is 4 kB. */
        private const val WINDOW = 64 * 1024
        private const val PACKET = 16 * 1024
        private const val MAX_WATERFALL = 40
        /** Two seconds of silence is a dongle that is not delivering, not a quiet band. */
        private const val SILENCE_MS = 2_000L
    }

    /** What the panel draws: attached, streaming, and what the last frame peaked at. */
    data class Status(
        val attached: Boolean = false,
        val name: String = "",
        val detail: String = "not attached",
        val streaming: Boolean = false,
        val recording: Boolean = false,
        val frames: Long = 0L,
        val centerMhz: Double = 433.92,
        val sampleRate: Int = 2_400_000,
        val peakDb: Float = Spectrum.FLOOR,
        val since: Long = 0L,
    )

    private val _state = MutableStateFlow(Status())
    val state: StateFlow<Status> = _state

    private val _spectrum = MutableStateFlow(FloatArray(Spectrum.BINS) { Spectrum.FLOOR })
    val spectrum: StateFlow<FloatArray> = _spectrum

    /** Where the pin goes: the phone's own fix, read at the moment recording starts. */
    @Volatile
    var fixProvider: (() -> LatLon?)? = null

    private val manager: UsbManager? by lazy {
        context.getSystemService(Context.USB_SERVICE) as? UsbManager
    }

    private val registered = AtomicBoolean(false)
    private val active = AtomicBoolean(false)

    @Volatile
    private var worker: Thread? = null

    // Recording accumulators. Only touched from the stream thread and the UI calls below, both
    // under [recLock], because a frame can arrive while the stop button is being pressed.
    private val recLock = Any()
    @Volatile
    private var recording = false
    private var recFrames = 0
    private var recPeak = Spectrum.FLOOR
    private var recSum = FloatArray(Spectrum.BINS)
    private var recRows = ArrayList<List<Float>>()
    private var recStarted = 0L
    private var recLat = 0.0
    private var recLon = 0.0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val d = intent.sdrDevice() ?: return
                    if (isDongle(d.vendorId, d.productId)) attach(d)
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val d = intent.sdrDevice() ?: return
                    if (isDongle(d.vendorId, d.productId)) detach()
                }

                ACTION_PERMISSION -> {
                    val d = intent.sdrDevice() ?: return
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        begin(d)
                    } else {
                        _state.value = _state.value.copy(attached = false, detail = "permission denied")
                    }
                }
            }
        }
    }

    fun isRunning(): Boolean = registered.get()

    fun status(): Status = _state.value

    fun start() {
        if (!registered.compareAndSet(false, true)) return
        val mgr = manager ?: run {
            registered.set(false)
            _state.value = _state.value.copy(detail = "no USB host on this phone")
            return
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure {
            CrashLog.recordQuiet(context, "sdr-receiver", it)
            registered.set(false)
            return
        }
        // A dongle that was already plugged in when the switch was thrown.
        runCatching {
            mgr.deviceList.values
                .filter { isDongle(it.vendorId, it.productId) }
                .forEach { attach(it) }
        }
    }

    fun stop() {
        if (!registered.getAndSet(false)) return
        runCatching { context.unregisterReceiver(receiver) }
        detach()
        _state.value = _state.value.copy(detail = "off")
    }

    /** Tuned centre, in MHz. Applied the next time the dongle is opened. */
    fun setCentre(mhz: Double) {
        _state.value = _state.value.copy(centerMhz = mhz.coerceIn(24.0, 1_766.0))
    }

    /**
     * Starts a recording. Refused without a fix: a capture that cannot be pinned is a file with
     * no place attached to it, which is the one thing the map cannot show.
     */
    fun beginRecording(): Boolean {
        val fix = fixProvider?.invoke()
        if (fix == null) {
            _state.value = _state.value.copy(detail = "no GPS fix for the pin")
            return false
        }
        synchronized(recLock) {
            recFrames = 0
            recPeak = Spectrum.FLOOR
            recSum = FloatArray(Spectrum.BINS)
            recRows = ArrayList()
            recStarted = System.currentTimeMillis()
            recLat = fix.lat
            recLon = fix.lon
            recording = true
        }
        _state.value = _state.value.copy(recording = true, since = recStarted, detail = "recording")
        return true
    }

    /** Stops the recording, saves what was on screen and returns it so it can be pinned. */
    fun endRecording(): SdrCapture? {
        val wasRecording: Boolean
        val frames: Int
        val peak: Float
        val sum: FloatArray
        val rows: List<List<Float>>
        val started: Long
        val lat: Double
        val lon: Double
        synchronized(recLock) {
            wasRecording = recording
            if (!wasRecording) return null
            recording = false
            frames = recFrames
            peak = recPeak
            sum = recSum
            rows = recRows.toList()
            started = recStarted
            lat = recLat
            lon = recLon
        }
        val now = System.currentTimeMillis()
        val state = _state.value
        val avg = FloatArray(Spectrum.BINS) { if (frames > 0) sum[it] / frames else Spectrum.FLOOR }
        val capture = SdrCapture(
            id = "sdr-$now",
            name = String.format(Locale.US, "SDR %.3f MHz", state.centerMhz),
            at = started,
            endedAt = now,
            centerMhz = state.centerMhz,
            spanMhz = state.sampleRate / 1_000_000.0,
            sampleRate = state.sampleRate,
            frames = frames,
            peakDb = peak,
            lat = lat,
            lon = lon,
            spectrum = avg.toList(),
            waterfall = rows,
        )
        library.add(capture)
        _state.value = state.copy(recording = false, detail = "saved")
        return capture
    }

    // ---------------- attach / detach ----------------

    private fun attach(device: UsbDevice) {
        val mgr = manager ?: return
        val label = runCatching { device.productName }.getOrNull().orEmpty().ifBlank { "RTL-SDR" }
        _state.value = _state.value.copy(attached = true, name = label, detail = "attached")
        if (!mgr.hasPermission(device)) {
            runCatching {
                val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                mgr.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
            }.onFailure { CrashLog.recordQuiet(context, "sdr-permission", it) }
            return
        }
        begin(device)
    }

    private fun begin(device: UsbDevice) {
        if (!active.compareAndSet(false, true)) return
        worker = Thread({ open(device) }, "sdr-stream").apply { isDaemon = true }
        worker?.start()
    }

    private fun detach() {
        active.set(false)
        worker = null
        if (recording) {
            // The cable came out mid-recording: what was on screen is still worth keeping.
            runCatching { endRecording() }
        }
        _state.value = _state.value.copy(
            attached = false,
            streaming = false,
            detail = "detached",
            peakDb = Spectrum.FLOOR,
        )
        _spectrum.value = FloatArray(Spectrum.BINS) { Spectrum.FLOOR }
    }

    // ---------------- streaming ----------------

    private fun open(device: UsbDevice) {
        val mgr = manager ?: return
        val conn = runCatching { mgr.openDevice(device) }.getOrNull()
        if (conn == null) {
            _state.value = _state.value.copy(streaming = false, detail = "could not open the dongle")
            active.set(false)
            return
        }
        var iface: UsbInterface? = null
        try {
            val port = findPort(device) ?: run {
                _state.value = _state.value.copy(streaming = false, detail = "no bulk endpoint")
                return
            }
            iface = port.first
            if (!conn.claimInterface(iface, true)) {
                _state.value = _state.value.copy(streaming = false, detail = "interface busy")
                return
            }
            _state.value = _state.value.copy(
                streaming = true,
                detail = "waiting for I/Q",
                centerMhz = _state.value.centerMhz,
            )
            val buf = ByteArray(PACKET)
            val window = ByteArray(WINDOW)
            var filled = 0
            var frames = 0L
            var lastFrame = 0L
            var lastData = SystemClock.elapsedRealtime()
            while (active.get()) {
                val n = runCatching {
                    conn.bulkTransfer(port.second, buf, buf.size, READ_TIMEOUT_MS)
                }.getOrDefault(-1)
                val now = SystemClock.elapsedRealtime()
                if (n > 0) {
                    lastData = now
                    if (filled + n > window.size) {
                        val keep = window.size - n
                        if (keep > 0) System.arraycopy(window, filled - keep, window, 0, keep)
                        filled = keep.coerceAtLeast(0)
                    }
                    if (filled + n <= window.size) {
                        System.arraycopy(buf, 0, window, filled, n)
                        filled += n
                    }
                    if (now - lastFrame >= FRAME_MS && filled > 512) {
                        lastFrame = now
                        publishFrame(Spectrum.analyse(window.copyOf(filled)), ++frames)
                    }
                } else if (now - lastData > SILENCE_MS && _state.value.streaming) {
                    // Opened, claimed and reading, but nothing is coming out: the tuner has not
                    // been programmed, which is a different fault from a quiet band.
                    _state.value = _state.value.copy(streaming = false, detail = "no I/Q from the tuner")
                }
            }
        } catch (t: Throwable) {
            CrashLog.recordQuiet(context, "sdr-stream", t)
        } finally {
            iface?.let { i -> runCatching { conn.releaseInterface(i) } }
            runCatching { conn.close() }
            _state.value = _state.value.copy(streaming = false, detail = "closed")
            active.set(false)
        }
    }

    private fun publishFrame(frame: FloatArray, frames: Long) {
        _spectrum.value = frame
        val peak = frame.maxOrNull() ?: Spectrum.FLOOR
        synchronized(recLock) {
            if (recording) {
                recFrames++
                if (peak > recPeak) recPeak = peak
                for (i in frame.indices) recSum[i] += frame[i]
                // Every fourth frame is a waterfall row: forty rows is a minute at four a second.
                if (recFrames % 4 == 1 && recRows.size < MAX_WATERFALL) recRows.add(frame.toList())
            }
        }
        _state.value = _state.value.copy(frames = frames, peakDb = peak, streaming = true, detail = "streaming")
    }

    /** The widest bulk IN endpoint: an RTL2832U streams on interface 0, alternate setting 1. */
    private fun findPort(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        var best: Pair<UsbInterface, UsbEndpoint>? = null
        for (i in 0 until device.interfaceCount) {
            val iface = runCatching { device.getInterface(i) }.getOrNull() ?: continue
            for (e in 0 until iface.endpointCount) {
                val ep = runCatching { iface.getEndpoint(e) }.getOrNull() ?: continue
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction != UsbConstants.USB_DIR_IN) continue
                if (best == null || ep.maxPacketSize > best.second.maxPacketSize) {
                    best = iface to ep
                }
            }
        }
        return best
    }
}

private fun Intent.sdrDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
} else {
    @Suppress("DEPRECATION")
    getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
