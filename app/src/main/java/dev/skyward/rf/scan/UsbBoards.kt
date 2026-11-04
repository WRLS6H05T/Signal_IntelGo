package dev.skyward.rf.scan

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.skyward.rf.core.CrashLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock

/** Which firmware family a board turned out to be. */
enum class BoardFamily { MARAUDER, GHOSTESP, BISCUIT, ESP32 }

/**
 * Turns a serial console transcript into a board identity.
 *
 * Kept free of Android types so it can be tested on the JVM: the USB code below only gathers
 * text and hands it here.
 *
 * The three firmwares are told apart by what they print, because they all run on the same
 * silicon and every USB vendor id involved (Espressif, SiLabs, WCH, FTDI) is shared by millions
 * of ordinary dev boards:
 *
 *  - **ESP32 Marauder** prints `ESP32 Marauder` / `By: justcallmekoko`, and answers
 *    `protocolinfo --machine <id>` with `@MARAUDER:{"protocol":1,...}`.
 *  - **GhostESP** is silent at boot and answers `version` / `help`, printing `GhostESP` and a
 *    vocabulary (`scanap`, `bledetect`, `badble`, `karma`) that Marauder does not have.
 *  - **Biscuit** is headless — it has no serial CLI at all, only ESP-IDF logs — so it is
 *    identified by its hardware signature instead: a Biscuit Pro/Ultra presents two ESP32
 *    serial ports at once (native USB-C5 core plus a WROOM behind a CP210x/CH340 bridge).
 */
object UsbBoardMatch {

    data class Hit(val family: BoardFamily, val version: String)

    private val TAGGED_VERSION = Regex("""(?i)\bv(\d+\.\d+(?:\.\d+)?)""")
    private val LABELLED_VERSION = Regex("""(?i)version[^0-9]{0,12}(\d+\.\d+(?:\.\d+)?)""")
    private val ANY_VERSION = Regex("""\b(\d+\.\d+(?:\.\d+)?)\b""")
    private val GHOST_VERBS = listOf("scanap", "bledetect", "badble", "karma", "pineap", "blebridge")

    /**
     * [text] is everything the board printed while being probed; [dualPort] is true when two
     * ESP32-class ports are attached at the same time, which is the Biscuit hardware signature.
     */
    fun classify(text: String, dualPort: Boolean, descriptor: String = ""): Hit? {
        // USB strings are free: a board that sets its product name names itself without the
        // console being read at all.
        val d = descriptor.lowercase()
        if (d.contains("ghost")) return Hit(BoardFamily.GHOSTESP, versionOf(text))
        if (d.contains("marauder")) return Hit(BoardFamily.MARAUDER, versionOf(text))
        if (d.contains("biscuit")) return Hit(BoardFamily.BISCUIT, versionOf(text))
        val t = text.trim()
        if (t.isEmpty()) return if (dualPort) Hit(BoardFamily.BISCUIT, "") else null
        val lower = t.lowercase()

        val marauder = lower.contains("@marauder:{") ||
            lower.contains("esp32 marauder") ||
            lower.contains("justcallmekoko")
        if (marauder) return Hit(BoardFamily.MARAUDER, versionOf(t))

        // "ghost" alone covers GhostESP, its GhostLink console and its ghostesp.local banner;
        // no other ESP32 firmware prints that word.
        val ghost = lower.contains("ghost") ||
            GHOST_VERBS.count { lower.contains(it) } >= 2
        if (ghost) return Hit(BoardFamily.GHOSTESP, versionOf(t))

        // No CLI answered. A lone ESP-IDF log on a dual-port device is the Biscuit shape;
        // the same log on its own is just an ESP32 board.
        val idf = lower.contains("esp-idf") || lower.contains("esp32") || lower.contains("boot:")
        return when {
            dualPort && idf -> Hit(BoardFamily.BISCUIT, versionOf(t))
            idf -> Hit(BoardFamily.ESP32, versionOf(t))
            dualPort -> Hit(BoardFamily.BISCUIT, "")
            else -> null
        }
    }

    /** First version-looking number, preferring one the firmware tagged with a `v`. */
    fun versionOf(text: String): String {
        TAGGED_VERSION.find(text)?.let { return it.groupValues[1] }
        LABELLED_VERSION.find(text)?.let { return it.groupValues[1] }
        return ANY_VERSION.find(text)?.groupValues?.get(1).orEmpty()
    }
}

/**
 * ESP32 boards plugged into the phone.
 *
 * A board is recognised in two steps. The USB descriptors say which chip family and which
 * bridge it uses - Espressif native USB-Serial/JTAG (`303a:1001`), CP210x (`10c4:ea60`), CH340
 * (`1a86:7523`) and so on - which is worth showing on its own, because that much is certain
 * from the descriptors alone. Then the console is opened at 115200 8N1 and asked who it is, so
 * GhostESP and ESP32 Marauder name themselves. Both steps are best-effort: a board whose
 * console stays quiet is still reported, as an ESP32 on a named bridge.
 *
 * Nothing here needs a pairing step or a permission prompt the user has to hunt for: the USB
 * host permission dialog is raised for the one device that was just plugged in.
 */
class UsbBoards(private val context: Context) {

    companion object {
        private const val ACTION_PERMISSION = "dev.skyward.rf.USB_PERMISSION"
        /** Rates a bridge is normally flashed at, in the order the status panel steps them. */
        val BAUD_RATES = listOf(9_600, 19_200, 38_400, 57_600, 115_200, 230_400, 460_800, 921_600)

        /** How long to listen for a banner. Marauder answers immediately, GhostESP within ~1 s. */
        private const val LISTEN_MS = 4_000L
        private const val READ_TIMEOUT_MS = 200
        private const val PROBE_GAP_MS = 380L
        private const val MAX_TEXT = 6_000
        /** How long each telemetry poll listens after its commands go out. */
        private const val POLL_READ_MS = 6_000L
        /** Gap between the poll's commands, so an AP sweep can finish before BLE starts. */
        private const val COMMAND_GAP_MS = 1_200L
        /** Gap between telemetry polls: a Wi-Fi sweep needs time to finish printing. */
        private const val POLL_GAP_MS = 6_000L
        private const val POLL_TEXT = 8_000

        // GhostESP is silent at boot and only answers a command, and its CLI tolerates either
        // line ending depending on build, so both are sent before giving up on it.
        private val PROBES = listOf(
            "\r\n",
            "help\r\n",
            "help\n",
            "version\r\n",
            "version\n",
            "chipinfo\r\n",
            "help all\r\n",
            "protocolinfo --machine sig1\r\n",
        )

        /** Espressif's own USB-Serial/JTAG, present on C3/C5/C6/S3/P4 boards. */
        private const val VID_ESPRESSIF = 0x303A
        private const val VID_SILABS = 0x10C4
        private const val VID_WCH = 0x1A86
        private const val VID_FTDI = 0x0403
    }

    enum class Transport(val label: String) {
        NATIVE_USB("native USB"),
        CP210X("CP210x"),
        CH34X("CH34x"),
        FTDI("FTDI"),
        CDC_ACM("CDC-ACM"),
        OTHER("serial"),
    }

    data class Board(
        val deviceId: Int,
        val vendorId: Int,
        val productId: Int,
        val label: String,
        val family: BoardFamily,
        val version: String = "",
        val transport: Transport = Transport.OTHER,
        val chip: String = "",
        /** Sanitised console snippet: what an unidentified board actually said. */
        val raw: String = "",
        /** Numbers the board reported on its own console; empty until it answers a poll. */
        val reading: Esp32Console.Reading = Esp32Console.Reading(),
        /** The networks and advertisers the board's last sweep named. */
        val sweep: Esp32Console.Sweep = Esp32Console.Sweep(),
        /** True while this board holds the cable link; only one does at a time. */
        val active: Boolean = false,
        val attachedAt: Long = System.currentTimeMillis(),
    ) {
        val key: String get() = "usb:$deviceId"

        val familyLabel: String
            get() = when (family) {
                BoardFamily.MARAUDER -> "ESP32 Marauder"
                BoardFamily.GHOSTESP -> "GhostESP"
                BoardFamily.BISCUIT -> "Biscuit"
                BoardFamily.ESP32 -> "ESP32"
            }

        val detail: String
            get() = listOf(
                familyLabel,
                transport.label,
                chip.ifBlank { String.format("%04x:%04x", vendorId, productId) },
            ).filter { it.isNotBlank() }.joinToString(" \u00b7 ")
    }

    /**
     * Live serial speed. Not a constant: a board flashed at 57 600 or 921 600 reads as pure
     * noise at 115 200, and its console then looks dead rather than wrong.
     */
    @Volatile
    private var baud = 115200

    fun setBaud(rate: Int) {
        baud = rate.coerceIn(1_200, 2_000_000)
    }

    fun baudRate(): Int = baud

    private val _state = MutableStateFlow<List<Board>>(emptyList())
    val state: StateFlow<List<Board>> = _state

    /** Called whenever the roster changes; [dev.skyward.rf.sky.Esp32Source] mirrors it. */
    @Volatile
    var onChange: ((List<Board>) -> Unit)? = null

    /** Called with each sweep a board finishes, so its findings can join the RF tables. */
    @Volatile
    var onSweep: ((Esp32Console.Sweep) -> Unit)? = null

    private val manager: UsbManager? by lazy {
        context.getSystemService(Context.USB_SERVICE) as? UsbManager
    }

    private val devices = ConcurrentHashMap<Int, Board>()

    /** The live UsbDevice for each id, so a queued board can be polled once the cable is free. */
    private val attached = ConcurrentHashMap<Int, UsbDevice>()

    /**
     * One cable, one conversation.
     *
     * Probing and polling both open the same serial port and write commands to it, so if they
     * ran at the same time the board would see two interleaved command streams and answer
     * neither. This lock makes the port exclusive between them. It is deliberately the only
     * serialisation in the app: BLE boards never touch the port, so any number of them can
     * report while a wired board is being polled, and the peer link is a network socket that
     * has nothing to share with a USB endpoint.
     */
    private val portLock = ReentrantLock()

    private val probing = ConcurrentHashMap.newKeySet<Int>()

    /** Boards with a telemetry poll running; cleared when the cable comes out. */
    private val polling = ConcurrentHashMap.newKeySet<Int>()

    private val poller = Executors.newCachedThreadPool { r ->
        Thread(r, "usb-telemetry").apply { isDaemon = true }
    }

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "usb-boards").apply { isDaemon = true }
    }

    @Volatile
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> intent.device()?.let { onAttached(it) }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> intent.device()?.let { drop(it.deviceId) }
                ACTION_PERMISSION -> {
                    val d = intent.device() ?: return
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        probe(d)
                    } else {
                        // Still worth listing: the board is attached, we just cannot read it.
                        upsert(d, UsbBoardMatch.Hit(BoardFamily.ESP32, ""), "permission denied")
                    }
                }
            }
        }
    }

    fun isRunning(): Boolean = registered

    fun current(): List<Board> = _state.value

    fun start() {
        if (registered) return
        val mgr = manager ?: return
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
            registered = true
        }.onFailure { CrashLog.recordQuiet(context, "usb-receiver", it) }
        // Boards that were already plugged in when the app started.
        runCatching {
            for (device in mgr.deviceList.values) onAttached(device)
        }
    }

    fun stop() {
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
        polling.clear()
        probing.clear()
        devices.clear()
        attached.clear()
        publish()
    }

    fun rescan() {
        val mgr = manager ?: return
        runCatching { for (device in mgr.deviceList.values) onAttached(device) }
    }

    // ---------------- attach / detach ----------------

    private fun onAttached(device: UsbDevice) {
        val mgr = manager ?: return
        if (!mgr.hasPermission(device)) {
            requestPermission(device, mgr)
            return
        }
        probe(device)
    }

    private fun requestPermission(device: UsbDevice, mgr: UsbManager) {
        runCatching {
            val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            mgr.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
        }.onFailure { CrashLog.recordQuiet(context, "usb-permission", it) }
    }

    private fun drop(deviceId: Int) {
        // Removing the id first is what ends that board's poll loop.
        polling.remove(deviceId)
        devices.remove(deviceId)
        probing.remove(deviceId)
        attached.remove(deviceId)
        publish()
        // The unplugged board's poller calls pump() from its own finally block as well; doing it
        // here means the next board takes the cable immediately rather than a cycle later.
        pump()
    }

    // ---------------- probe ----------------

    private fun probe(device: UsbDevice) {
        val id = device.deviceId
        // A board that is already polled is left alone: sounding it again would send help and
        // version commands into the middle of its sweep for no reason.
        if (id in polling) return
        if (!probing.add(id)) return
        worker.execute { runProbe(device) }
    }

    private fun runProbe(device: UsbDevice) {
        val mgr = manager
        val port = findPort(device)
        val transport = transportOf(device)
        if (mgr == null || port == null || !mgr.hasPermission(device)) {
            probing.remove(device.deviceId)
            upsert(device, UsbBoardMatch.Hit(BoardFamily.ESP32, ""), "")
            return
        }
        val (iface, inEp, outEp) = port
        // Wait for a poll to finish rather than opening a second session on the same port.
        portLock.lock()
        val conn = runCatching { mgr.openDevice(device) }.getOrNull()
        if (conn == null) {
            portLock.unlock()
            probing.remove(device.deviceId)
            upsert(device, UsbBoardMatch.Hit(BoardFamily.ESP32, ""), "")
            return
        }
        var text = ""
        try {
            if (!conn.claimInterface(iface, true)) {
                upsert(device, UsbBoardMatch.Hit(BoardFamily.ESP32, ""), "interface busy")
                return
            }
            configure(conn, transport, iface.id)
            text = listen(conn, inEp, outEp)
            // A wrong vendor baud word silently turns the console into noise on a CH34x
            // bridge, so one retry with the other convention costs a second and recovers it.
            if (transport == Transport.CH34X &&
                UsbBoardMatch.classify(text, false, describe(device)) == null
            ) {
                configure(conn, transport, iface.id, altBaud = true)
                text += "\n" + listen(conn, inEp, outEp)
            }
        } catch (t: Throwable) {
            CrashLog.recordQuiet(context, "usb-probe", t)
        } finally {
            runCatching { conn.releaseInterface(iface) }
            runCatching { conn.close() }
            probing.remove(device.deviceId)
            portLock.unlock()
        }
        val dual = dualPortPresent(device)
        val hit = UsbBoardMatch.classify(text, dual, describe(device))
            ?: UsbBoardMatch.Hit(BoardFamily.ESP32, "")
        upsert(device, hit, "", text)
        // Identification is not the end of the conversation: the same console is the board's
        // only channel for sweeps and its own GPS, so it is polled for as long as it is wired -
        // one board at a time, because they all share that one console.
        pump()
    }

    // ---------------- telemetry ----------------

    /**
     * One poller per board. Each cycle re-opens the port, asks for a sweep and a fix, and folds
     * whatever came back into the board's [Board.reading]. Re-opening rather than holding the
     * port keeps a stuck read from wedging the board for the rest of the session.
     */
    /** Starts the first board that is waiting, and only when no other board holds the link. */
    private fun pump() {
        if (polling.isNotEmpty()) return
        val next = devices.values.sortedBy { it.attachedAt }.firstOrNull { it.deviceId !in polling } ?: return
        val device = attached[next.deviceId] ?: return
        startPolling(device, next.family)
    }

    private fun startPolling(device: UsbDevice, family: BoardFamily) {
        val id = device.deviceId
        if (polling.isNotEmpty() && id !in polling) return
        if (!polling.add(id)) return
        devices[id]?.let { devices[id] = it.copy(active = true) }
        publish()
        runCatching {
            poller.execute {
                try {
                    while (id in polling) {
                        // Poll first: a board plugged in should start reporting within seconds,
                        // not after the gap that keeps consecutive sweeps from overlapping.
                        val text = poll(device, family)
                        if (id !in polling) break
                        if (text.isNotBlank()) {
                            val reading = Esp32Console.parse(text)
                            val sweep = Esp32Console.parseScan(text)
                            val board = devices[id] ?: break
                            devices[id] = board.copy(
                                reading = board.reading.merge(reading),
                                sweep = sweep,
                                raw = text.replace('\n', ' ').trim().take(160),
                            )
                            publish()
                            if (!sweep.isEmpty) onSweep?.invoke(sweep)
                        }
                        Thread.sleep(POLL_GAP_MS)
                    }
                } catch (t: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (t: Throwable) {
                    CrashLog.recordQuiet(context, "usb-telemetry", t)
                } finally {
                    polling.remove(id)
                    devices[id]?.let { devices[id] = it.copy(active = false) }
                    publish()
                    // The cable is free: hand it to the next board that is waiting for it.
                    pump()
                }
            }
        }.onFailure { polling.remove(id) }
    }

    /** One poll: open, ask, read, close. Empty when the board cannot be addressed. */
    private fun poll(device: UsbDevice, family: BoardFamily): String {
        // Skipped rather than queued: a probe is holding the port, and the next sweep is a few
        // seconds away. Two sessions on one console would interleave their commands.
        if (!portLock.tryLock()) return ""
        var conn: UsbDeviceConnection? = null
        var iface: UsbInterface? = null
        try {
            if (device.deviceId !in polling || device.deviceId in probing) return ""
            val mgr = manager ?: return ""
            if (!mgr.hasPermission(device)) return ""
            val port = findPort(device) ?: return ""
            iface = port.first
            val opened = runCatching { mgr.openDevice(device) }.getOrNull() ?: return ""
            conn = opened
            if (!opened.claimInterface(port.first, true)) return ""
            configure(opened, transportOf(device), port.first.id)
            return exchange(opened, port.second, port.third, Esp32Console.commands(family))
        } catch (t: Throwable) {
            return ""
        } finally {
            iface?.let { i -> conn?.let { c -> runCatching { c.releaseInterface(i) } } }
            conn?.let { c -> runCatching { c.close() } }
            portLock.unlock()
        }
    }

    /**
     * Sends the poll commands across the window and collects the replies. The read is
     * unconditional: a board streaming NMEA needs no command, and a firmware that does not know
     * a verb simply prints nothing for it.
     */
    private fun exchange(
        conn: UsbDeviceConnection,
        inEp: UsbEndpoint,
        outEp: UsbEndpoint?,
        commands: List<String>,
    ): String {
        val text = StringBuilder()
        val buf = ByteArray(512)
        val startedAt = SystemClock.elapsedRealtime()
        var sent = 0
        while (SystemClock.elapsedRealtime() - startedAt < POLL_READ_MS && text.length < POLL_TEXT) {
            val n = runCatching { conn.bulkTransfer(inEp, buf, buf.size, READ_TIMEOUT_MS) }.getOrDefault(-1)
            if (n > 0) appendPrintable(text, buf, n)
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (sent < commands.size && elapsed >= sent * COMMAND_GAP_MS) {
                val line = commands[sent].toByteArray()
                outEp?.let { runCatching { conn.bulkTransfer(it, line, line.size, 100) } }
                sent++
            }
        }
        return text.toString()
    }

    /** Reads the console for a couple of seconds, nudging it with the probe commands. */
    private fun listen(
        conn: UsbDeviceConnection,
        inEp: UsbEndpoint,
        outEp: UsbEndpoint?,
    ): String {
        val text = StringBuilder()
        val buf = ByteArray(512)
        val startedAt = SystemClock.elapsedRealtime()
        var next = 0
        while (SystemClock.elapsedRealtime() - startedAt < LISTEN_MS && text.length < MAX_TEXT) {
            val n = runCatching { conn.bulkTransfer(inEp, buf, buf.size, READ_TIMEOUT_MS) }.getOrDefault(-1)
            if (n > 0) appendPrintable(text, buf, n)
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (next < PROBES.size && elapsed >= next * PROBE_GAP_MS) {
                val line = PROBES[next].toByteArray()
                outEp?.let {
                    runCatching { conn.bulkTransfer(it, line, line.size, 100) }
                }
                next++
            }
        }
        return text.toString()
    }

    private fun appendPrintable(out: StringBuilder, buf: ByteArray, n: Int) {
        for (i in 0 until n) {
            val c = buf[i].toInt() and 0xFF
            when {
                c == 0x0A || c == 0x0D -> if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
                c in 0x20..0x7E -> out.append(c.toChar())
            }
        }
    }

    /**
     * Baud rates on a USB bridge are vendor control transfers, not a standard request, so each
     * family gets its own. Failures are ignored: an unconfigured port usually still reads the
     * descriptors' identity, which is the part that never depends on the baud rate.
     */
    private fun configure(
        conn: UsbDeviceConnection,
        transport: Transport,
        ifaceId: Int,
        altBaud: Boolean = false,
    ) {
        runCatching {
            when (transport) {
                Transport.NATIVE_USB, Transport.CDC_ACM -> {
                    // SET_LINE_CODING at the configured rate, one stop bit, no parity, 8 data.
                    val line = byteArrayOf(
                        (baud and 0xFF).toByte(),
                        ((baud ushr 8) and 0xFF).toByte(),
                        ((baud ushr 16) and 0xFF).toByte(),
                        ((baud ushr 24) and 0xFF).toByte(),
                        0x00, // bCharFormat = 1 stop
                        0x00, // bParityType = none
                        0x08, // bDataBits = 8
                    )
                    conn.controlTransfer(0x21, 0x20, 0, ifaceId, line, line.size, 200)
                    conn.controlTransfer(0x21, 0x22, 0x0003, ifaceId, null, 0, 200)
                }

                Transport.CP210X -> {
                    conn.controlTransfer(0x41, 0x00, 0x0001, ifaceId, null, 0, 200) // IFC_ENABLE
                    conn.controlTransfer(0x41, 0x03, 0x0800, ifaceId, null, 0, 200) // 8N1
                    conn.controlTransfer(0x41, 0x1E, baud and 0xFFFF, baud ushr 16, null, 0, 200)
                }

                Transport.CH34X -> {
                    conn.controlTransfer(0x40, 0xA1, 0x0000, ifaceId, null, 0, 200) // serial init
                    // CH340 derives its rate from a 12 MHz clock divided by two.
                    // Two divisor conventions are in circulation for the CH34x family; a second pass
                    // with the other one is how a board that read as noise gets identified.
                    val clock = if (altBaud) 12_000_000 else 6_000_000
                    val divisor = (clock / baud) and 0xFFFF
                    conn.controlTransfer(0x40, 0x9A, divisor, ifaceId, null, 0, 200)
                    conn.controlTransfer(0x40, 0xA4, 0x00FF, ifaceId, null, 0, 200) // no handshake
                }

                Transport.FTDI -> {
                    conn.controlTransfer(0x40, 0x00, 0x0000, 0, null, 0, 200) // reset
                    conn.controlTransfer(0x40, 0x03, 3_000_000 / baud, ifaceId, null, 0, 200)
                    conn.controlTransfer(0x40, 0x04, 0x0008, ifaceId, null, 0, 200) // 8N1
                }

                Transport.OTHER -> Unit
            }
        }
    }

    // ---------------- descriptors ----------------

    private fun findPort(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint?>? {
        var fallback: Triple<UsbInterface, UsbEndpoint, UsbEndpoint?>? = null
        for (i in 0 until device.interfaceCount) {
            val iface = runCatching { device.getInterface(i) }.getOrNull() ?: continue
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (e in 0 until iface.endpointCount) {
                val ep = runCatching { iface.getEndpoint(e) }.getOrNull() ?: continue
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) {
                    if (inEp == null) inEp = ep
                } else if (outEp == null) {
                    outEp = ep
                }
            }
            val input = inEp ?: continue
            val candidate = Triple(iface, input, outEp)
            // A writable interface wins: GhostESP answers nothing until it is spoken to, so a
            // read-only pick would leave it unidentified.
            if (outEp != null) return candidate
            if (fallback == null) fallback = candidate
        }
        return fallback
    }

    fun transportOf(device: UsbDevice): Transport = when (device.vendorId) {
        VID_ESPRESSIF -> Transport.NATIVE_USB
        VID_SILABS -> Transport.CP210X
        VID_WCH -> Transport.CH34X
        VID_FTDI -> Transport.FTDI
        else -> {
            var cdc = false
            for (i in 0 until device.interfaceCount) {
                val cls = runCatching { device.getInterface(i).interfaceClass }.getOrDefault(-1)
                if (cls == UsbConstants.USB_CLASS_COMM || cls == UsbConstants.USB_CLASS_CDC_DATA) cdc = true
            }
            if (cdc) Transport.CDC_ACM else Transport.OTHER
        }
    }

    /** Everything the device says about itself without opening the port. */
    private fun describe(device: UsbDevice): String = listOfNotNull(
        runCatching { device.productName }.getOrNull(),
        runCatching { device.manufacturerName }.getOrNull(),
        runCatching { device.serialNumber }.getOrNull(),
    ).joinToString(" ")

    /** Biscuit Pro/Ultra expose the C5 core and the WROOM module as two separate serial ports. */
    private fun dualPortPresent(self: UsbDevice): Boolean {
        val all = runCatching { manager?.deviceList?.values.orEmpty() }.getOrDefault(emptyList())
        val espressif = all.count { it.vendorId == VID_ESPRESSIF && it.deviceId != self.deviceId }
        val bridge = all.count {
            it.deviceId != self.deviceId &&
                (it.vendorId == VID_SILABS || it.vendorId == VID_WCH) &&
                it.vendorId != self.vendorId
        }
        return all.size > 1 && (espressif > 0 || bridge > 0)
    }

    // ---------------- roster ----------------

    private fun upsert(
        device: UsbDevice,
        hit: UsbBoardMatch.Hit,
        note: String,
        console: String = "",
    ) {
        val transport = transportOf(device)
        val chip = runCatching { device.productName }.getOrNull().orEmpty()
            .ifBlank { runCatching { device.manufacturerName }.getOrNull().orEmpty() }
        val base = when (hit.family) {
            BoardFamily.MARAUDER -> "ESP32 Marauder"
            BoardFamily.GHOSTESP -> "GhostESP"
            BoardFamily.BISCUIT -> "Biscuit"
            BoardFamily.ESP32 -> chip.ifBlank { "ESP32 board" }
        }
        val label = when {
            hit.version.isBlank() -> base
            base.endsWith(hit.version) -> base
            else -> "$base v${hit.version}"
        }
        val board = Board(
            deviceId = device.deviceId,
            vendorId = device.vendorId,
            productId = device.productId,
            label = if (note.isBlank()) label else "$label ($note)",
            family = hit.family,
            version = hit.version,
            transport = transport,
            chip = chip,
            raw = console.replace('\n', ' ').trim().take(160),
        )
        attached[device.deviceId] = device
        devices[device.deviceId] = board
        publish()
    }

    private fun publish() {
        val list = devices.values.sortedBy { it.attachedAt }
        _state.value = list
        onChange?.invoke(list)
    }
}

private fun Intent.device(): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
} else {
    @Suppress("DEPRECATION")
    getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
