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
import dev.skyward.rf.sdr.SdrStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * Which firmware family a board turned out to be.
 *
 * [SIGNALSENSOR] is this project's own sketch (`firmware/SignalSensor`): the one firmware that
 * answers sweeps in the shapes [Esp32Console] reads without pretending to be somebody else's.
 */
enum class BoardFamily { MARAUDER, GHOSTESP, BISCUIT, SIGNALSENSOR, ESP32 }

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
        // The sketch sets its own product string ("Signal_IntelGo sensor"), and an underscore
        // is not a word break: folded to a space so the name matches however it is spelled.
        val d = descriptor.lowercase().replace('_', ' ')
        if (d.contains("ghost")) return Hit(BoardFamily.GHOSTESP, versionOf(text))
        if (d.contains("marauder")) return Hit(BoardFamily.MARAUDER, versionOf(text))
        if (d.contains("biscuit")) return Hit(BoardFamily.BISCUIT, versionOf(text))
        if (d.contains("signal intelgo")) return Hit(BoardFamily.SIGNALSENSOR, versionOf(text))
        val t = text.trim()
        if (t.isEmpty()) return if (dualPort) Hit(BoardFamily.BISCUIT, "") else null
        val lower = t.lowercase()

        // The project's own sensor firmware names itself, and has to be taken before the verb
        // vocabulary below: its help text lists scanap and bledetect on purpose, so that an app
        // build that predates this family still finds two GhostESP words and polls it.
        if (lower.contains("signal intelgo sensor") || lower.contains("sig-sensor")) {
            return Hit(BoardFamily.SIGNALSENSOR, versionOf(t))
        }

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

        /**
         * How long to listen for a banner.
         *
         * Marauder answers immediately and GhostESP within ~1 s, but a board that booted just
         * before it was plugged in runs its first Wi-Fi and BLE sweeps from `loop()` before it
         * reads its console, and a blocking sweep keeps the port deaf for up to seven seconds.
         * Four seconds heard nothing on exactly that board and filed it as a bare ESP32.
         */
        private const val LISTEN_MS = 9_000L
        private const val READ_TIMEOUT_MS = 200
        private const val PROBE_GAP_MS = 380L
        private const val MAX_TEXT = 6_000
        /** How often the roster is reconciled against the bus when no broadcast arrives. */
        private const val WATCH_MS = 3_000L
        /**
         * How long each telemetry poll listens after its commands go out.
         *
         * A board answers its commands one after another, and a Wi-Fi sweep alone can hold it
         * for five seconds before the BLE sweep even starts. A six-second window cut the BLE
         * listing off every cycle that asked for both, so the window covers the whole queue.
         */
        private const val POLL_READ_MS = 11_000L
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
        /** Sanitised console snippet: what the board said on its last poll. */
        val raw: String = "",
        /**
         * The console text that identified the board, kept apart from [raw].
         *
         * [raw] is the latest poll transcript, which is what the card shows, but the banner that
         * names the silicon is only printed at boot and by `help`/`status`. Letting a poll
         * overwrite it is how a C5 stopped being dual band one cycle after it was plugged in,
         * which took the 5 GHz choices out of its card with it.
         */
        val banner: String = "",
        /** Numbers the board reported on its own console; empty until it answers a poll. */
        val reading: Esp32Console.Reading = Esp32Console.Reading(),
        /** The networks and advertisers the board's last sweep named. */
        val sweep: Esp32Console.Sweep = Esp32Console.Sweep(),
        /** True while this board holds the cable link; only one does at a time. */
        val active: Boolean = false,
        val attachedAt: Long = System.currentTimeMillis(),
    ) {
        val key: String get() = "usb:$deviceId"

        /**
         * The Wi-Fi silicon, read out of the console or the USB strings.
         *
         * A C5 (and a C6) hear 5 GHz where a classic ESP32 and an S3 do not, and the only
         * place the chip names itself is the boot banner: `ESP.getChipModel()` prints
         * "ESP32-C5" and the ROM prints `ESP-ROM:esp32c5-...`. [chip] is the USB product
         * string, which on these boards is the bridge or the JTAG unit, so searching it alone
         * classified every C5 as single band.
         */
        val silicon: String
            get() {
                val text = chip + " " + banner + " " + raw + " " + label
                return when {
                    Regex("(?i)esp32[- _]?c5\\b|\\bc5[- _]?mini\\b").containsMatchIn(text) -> "ESP32-C5"
                    Regex("(?i)esp32[- _]?c6\\b").containsMatchIn(text) -> "ESP32-C6"
                    Regex("(?i)esp32[- _]?s3\\b").containsMatchIn(text) -> "ESP32-S3"
                    Regex("(?i)esp32[- _]?c3\\b").containsMatchIn(text) -> "ESP32-C3"
                    else -> ""
                }
            }

        /**
         * True when the console named a 5 GHz radio: the C5 is the only mini board with one.
         *
         * Kept on the board, not only on the [Esp32Node] the roster is mirrored into, so a card
         * whose node lookup missed still knows what the cable is attached to.
         */
        val dualBand: Boolean get() = silicon.contains("C5", ignoreCase = true)

        val familyLabel: String
            get() = when (family) {
                BoardFamily.MARAUDER -> "ESP32 Marauder"
                BoardFamily.GHOSTESP -> "GhostESP"
                BoardFamily.BISCUIT -> "Biscuit"
                BoardFamily.SIGNALSENSOR -> "SignalSensor"
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

    /**
     * The sub-GHz receive command, or null when that radio is switched off.
     *
     * Pushed in from settings rather than read here: the poller has no business owning a
     * preference, and the verb is the user's because firmware builds disagree about it.
     */
    @Volatile
    var subGhzCommand: String? = null

    /**
     * The sweep this board has been assigned, or null while nothing has been chosen.
     *
     * Pushed in from settings for the same reason [subGhzCommand] is: which radios a board
     * sweeps is the user's choice, and the poller has no business owning a preference.
     */
    @Volatile
    var planOf: ((String) -> String?)? = null

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

    /**
     * Boards a terminal has taken off the poller.
     *
     * The poller reopens the port every few seconds and writes commands into it; a human typing
     * a firmware command cannot share that. While an id is in
     * here no probe starts and no poll runs, and the port belongs to the session alone.
     */
    private val manual = ConcurrentHashMap.newKeySet<Int>()

    private val poller = Executors.newCachedThreadPool { r ->
        Thread(r, "usb-telemetry").apply { isDaemon = true }
    }

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "usb-boards").apply { isDaemon = true }
    }

    /** Watches the bus itself, because the detach broadcast is the fast path and not the only one. */
    private var watchdog: Thread? = null

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
        ensureWatchdog()
    }

    fun stop() {
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
        watchdog?.interrupt()
        watchdog = null
        polling.clear()
        probing.clear()
        manual.clear()
        devices.clear()
        attached.clear()
        publish()
    }

    fun rescan() {
        val mgr = manager ?: return
        runCatching { for (device in mgr.deviceList.values) onAttached(device) }
    }

    private fun ensureWatchdog() {
        if (watchdog?.isAlive == true) return
        watchdog = Thread({
            while (registered) {
                runCatching { reconcile() }.onFailure { CrashLog.recordQuiet(context, "usb-watch", it) }
                runCatching { Thread.sleep(WATCH_MS) }
            }
        }, "usb-watch").apply { isDaemon = true }
        watchdog?.start()
    }

    /**
     * Reconciles the roster against the bus.
     *
     * The attach and detach broadcasts are the fast path, not the only one: a broadcast can be
     * missed - the process was paused when it arrived, or a chip fell off the bus as it reset -
     * and the roster still has to match the cable. A board that left disappears; a board that
     * came back appears again without a rescan.
     */
    private fun reconcile() {
        val mgr = manager ?: return
        // UsbManager keys its list by device name, so the ids are collected explicitly.
        val live = runCatching { mgr.deviceList.values.map { it.deviceId }.toSet() }.getOrNull() ?: return
        for (id in devices.keys.toList()) if (id !in live) drop(id)
        for (device in runCatching { mgr.deviceList.values.toList() }.getOrDefault(emptyList())) {
            val id = device.deviceId
            if (attached.containsKey(id) || id in probing || id in polling || id in manual) continue
            // Permission is asked for when a board is plugged in, not every few seconds: a board
            // that was refused must not re-raise the system dialog in a loop.
            if (!mgr.hasPermission(device)) continue
            probe(device)
        }
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
        manual.remove(deviceId)
        attached.remove(deviceId)
        publish()
        // The unplugged board's poller calls pump() from its own finally block as well; doing it
        // here means the next board takes the cable immediately rather than a cycle later.
        pump()
    }

    // ---------------- probe ----------------

    private fun probe(device: UsbDevice) {
        // An RTL-SDR is not a board: it is a raw I/Q receiver with no console, and opening it as
        // a serial port would both mislabel it and take the cable from the SDR stream.
        if (SdrStream.isDongle(device.vendorId, device.productId)) return
        val id = device.deviceId
        // A board that is already polled is left alone: sounding it again would send help and
        // version commands into the middle of its sweep for no reason.
        if (id in polling || id in manual) return
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
        val next = devices.values.sortedBy { it.attachedAt }
            .firstOrNull { it.deviceId !in polling && it.deviceId !in manual } ?: return
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
                        // Read the family every cycle rather than once: a board that names itself
                        // on a poll stops being the fallback it was filed as when the probe
                        // heard only the silence of its own boot sweep.
                        val current = devices[id]?.family ?: family
                        // Poll first: a board plugged in should start reporting within seconds,
                        // not after the gap that keeps consecutive sweeps from overlapping.
                        val text = poll(device, current)
                        if (id !in polling) break
                        if (text.isNotBlank()) {
                            val reading = Esp32Console.parse(text)
                            val sweep = Esp32Console.parseScan(text)
                            val board = devices[id] ?: break
                            // A transcript that names the firmware is a better identification
                            // than the silence the probe heard. Only the fallback is upgraded: a
                            // Marauder whose lore mentions `scanap` must not be relabelled by a
                            // sweep listing.
                            val better = if (board.family == BoardFamily.ESP32) {
                                UsbBoardMatch.classify(text, dualPortPresent(device), describe(device))
                                    ?.takeIf { it.family != BoardFamily.ESP32 }
                            } else {
                                null
                            }
                            val familyNow = better?.family ?: board.family
                            val version = better?.version?.takeIf { it.isNotBlank() } ?: board.version
                            devices[id] = board.copy(
                                family = familyNow,
                                version = version,
                                label = if (better == null) {
                                    board.label
                                } else {
                                    labelOf(familyNow, version, board.chip)
                                },
                                // Only a transcript that actually identified the board becomes
                                // its banner; a sweep listing names nothing.
                                banner = if (board.banner.isBlank() && better != null) {
                                    text.replace('\n', ' ').trim().take(200)
                                } else {
                                    board.banner
                                },
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
            if (device.deviceId in manual) return ""
            val mgr = manager ?: return ""
            if (!mgr.hasPermission(device)) return ""
            val port = findPort(device) ?: return ""
            iface = port.first
            val opened = runCatching { mgr.openDevice(device) }.getOrNull() ?: return ""
            conn = opened
            if (!opened.claimInterface(port.first, true)) return ""
            configure(opened, transportOf(device), port.first.id)
            // Only a board that reported a CC1101 is asked to sweep sub-GHz: sending the verb
            // to a board without the radio is how a poll transcript fills with errors.
            val subGhz = if (devices[device.deviceId]?.reading?.subGhz == true) subGhzCommand else null
            val plan = planOf?.invoke("usb:${device.deviceId}")
            // The band choices on the card come from the chip, and the chip only appears in the
            // banner: a board that was busy for the whole probe window is asked again on every
            // poll until it names itself.
            val unknownChip = devices[device.deviceId]?.silicon.isNullOrEmpty()
            return exchange(
                opened,
                port.second,
                port.third,
                Esp32Console.commandsFor(family, subGhz, plan, identify = unknownChip),
            )
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
        rate: Int = baud,
    ) {
        runCatching {
            when (transport) {
                Transport.NATIVE_USB, Transport.CDC_ACM -> {
                    // SET_LINE_CODING at the requested rate, one stop bit, no parity, 8 data.
                    val line = byteArrayOf(
                        (rate and 0xFF).toByte(),
                        ((rate ushr 8) and 0xFF).toByte(),
                        ((rate ushr 16) and 0xFF).toByte(),
                        ((rate ushr 24) and 0xFF).toByte(),
                        0x00, // bCharFormat = 1 stop
                        0x00, // bParityType = none
                        0x08, // bDataBits = 8
                    )
                    conn.controlTransfer(0x21, 0x20, 0, ifaceId, line, line.size, 200)
                    // Both lines released, deliberately. Asserting DTR or RTS pulls the board's
                    // EN or IO0 line through the bridge's inverter, which holds the chip in
                    // reset: a console that never prints its boot log. Nothing asserts the
                    // lines on open; only the terminal's RESET does, on request.
                    conn.controlTransfer(0x21, 0x22, 0x0000, ifaceId, null, 0, 200)
                }

                Transport.CP210X -> {
                    conn.controlTransfer(0x41, 0x00, 0x0001, ifaceId, null, 0, 200) // IFC_ENABLE
                    conn.controlTransfer(0x41, 0x03, 0x0800, ifaceId, null, 0, 200) // 8N1
                    conn.controlTransfer(0x41, 0x1E, rate and 0xFFFF, rate ushr 16, null, 0, 200)
                }

                Transport.CH34X -> {
                    conn.controlTransfer(0x40, 0xA1, 0x0000, ifaceId, null, 0, 200) // serial init
                    // CH340 derives its rate from a 12 MHz clock divided by two.
                    // Two divisor conventions are in circulation for the CH34x family; a second pass
                    // with the other one is how a board that read as noise gets identified.
                    val clock = if (altBaud) 12_000_000 else 6_000_000
                    val divisor = (clock / rate) and 0xFFFF
                    conn.controlTransfer(0x40, 0x9A, divisor, ifaceId, null, 0, 200)
                    conn.controlTransfer(0x40, 0xA4, 0x00FF, ifaceId, null, 0, 200) // no handshake
                }

                Transport.FTDI -> {
                    conn.controlTransfer(0x40, 0x00, 0x0000, 0, null, 0, 200) // reset
                    conn.controlTransfer(0x40, 0x03, 3_000_000 / rate, ifaceId, null, 0, 200)
                    conn.controlTransfer(0x40, 0x04, 0x0008, ifaceId, null, 0, 200) // 8N1
                }

                Transport.OTHER -> Unit
            }
        }
    }

    // ---------------- raw sessions ----------------

    /**
     * A raw, exclusive session on one board's port.
     *
     * The terminal needs the port as a byte pipe, not as a source of
     * telemetry: one wants a human's commands to arrive intact, the other needs the ROM
     * bootloader's framing to survive untouched. Closing is the only way the board goes back to
     * the poller, which is why every failure path closes as well.
     */
    class SerialSession internal constructor(
        val deviceId: Int,
        private val connection: UsbDeviceConnection,
        private val iface: UsbInterface,
        private val inEndpoint: UsbEndpoint,
        private val outEndpoint: UsbEndpoint?,
        private val onRelease: (Int) -> Unit,
    ) {
        private val closed = AtomicBoolean(false)

        val isOpen: Boolean get() = !closed.get()

        fun write(bytes: ByteArray): Boolean {
            if (closed.get()) return false
            val endpoint = outEndpoint ?: return false
            return runCatching { connection.bulkTransfer(endpoint, bytes, bytes.size, 500) >= 0 }
                .getOrDefault(false)
        }

        /** Reads whatever has arrived within [timeoutMs]; null when nothing did. */
        fun read(max: Int = 512, timeoutMs: Int = 200): ByteArray? {
            if (closed.get()) return null
            val buf = ByteArray(max)
            val n = runCatching { connection.bulkTransfer(inEndpoint, buf, max, timeoutMs) }.getOrDefault(-1)
            return if (n > 0) buf.copyOf(n) else null
        }

        /** SET_CONTROL_LINE_STATE: bit 0 is DTR, bit 1 is RTS. */
        fun setLines(dtr: Boolean, rts: Boolean) {
            if (closed.get()) return
            var value = 0
            if (dtr) value = value or 0x01
            if (rts) value = value or 0x02
            runCatching { connection.controlTransfer(0x21, 0x22, value, iface.id, null, 0, 200) }
        }

        /**
         * Pulses EN so the board restarts, then leaves both lines released.
         *
         * A console prints its boot log once per reset, so a board that has been running since
         * before it was plugged in shows an empty panel until it is restarted.
         */
        fun pulseReset() {
            if (closed.get()) return
            setLines(dtr = false, rts = true)
            runCatching { Thread.sleep(120) }
            setLines(dtr = false, rts = false)
            runCatching { Thread.sleep(150) }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { connection.releaseInterface(iface) }
            runCatching { connection.close() }
            onRelease(deviceId)
        }
    }

    /**
     * Takes a board off the poller and opens its port for the terminal.
     *
     * Returns null when the board is gone, the interface is busy, or a poll is still inside its
     * read window - a poll can hold the port for six seconds, so the wait has to outlast that.
     */
    fun openSession(deviceId: Int, baudRate: Int = baud, portIndex: Int = 0): SerialSession? {
        val device = attached[deviceId] ?: return null
        val mgr = manager ?: return null
        if (!mgr.hasPermission(device)) return null
        manual.add(deviceId)
        polling.remove(deviceId)
        if (!portLock.tryLock(9, TimeUnit.SECONDS)) {
            manual.remove(deviceId)
            pump()
            return null
        }
        var session: SerialSession? = null
        try {
            val port = findPorts(device).getOrNull(portIndex) ?: return null
            val conn = runCatching { mgr.openDevice(device) }.getOrNull() ?: return null
            if (!conn.claimInterface(port.first, true)) {
                runCatching { conn.close() }
                return null
            }
            configure(conn, transportOf(device), port.first.id, rate = baudRate)
            session = SerialSession(deviceId, conn, port.first, port.second, port.third) { id ->
                manual.remove(id)
                devices[id]?.let { devices[id] = it.copy(active = false) }
                publish()
                pump()
            }
            return session
        } catch (t: Throwable) {
            CrashLog.recordQuiet(context, "usb-session", t)
            return null
        } finally {
            portLock.unlock()
            if (session == null) {
                manual.remove(deviceId)
                pump()
            }
        }
    }

    // ---------------- descriptors ----------------

    /**
     * Every interface that could carry the console, best first.
     *
     * One device can offer more than one. A writable interface wins - GhostESP answers nothing
     * until it is spoken to - and then the widest packets, because a data interface has both
     * directions while a stub control interface often has neither. The list matters: a port that
     * never sends a byte is abandoned in favour of the next one rather than reported as a board
     * that will not answer.
     */
    private fun findPorts(device: UsbDevice): List<Triple<UsbInterface, UsbEndpoint, UsbEndpoint?>> {
        val candidates = ArrayList<Triple<UsbInterface, UsbEndpoint, UsbEndpoint?>>()
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
            candidates.add(Triple(iface, input, outEp))
        }
        return candidates.sortedWith(
            compareByDescending<Triple<UsbInterface, UsbEndpoint, UsbEndpoint?>> { it.third != null }
                .thenByDescending { it.second.maxPacketSize },
        )
    }

    private fun findPort(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint?>? =
        findPorts(device).firstOrNull()

    /** How many interfaces the terminal could try on this board. */
    fun portCount(deviceId: Int): Int {
        val device = attached[deviceId] ?: return 1
        return findPorts(device).size.coerceAtLeast(1)
    }

    /** True while the device is still on the cable under this id. */
    fun isAttached(deviceId: Int): Boolean = attached.containsKey(deviceId)

    /**
     * True while the USB host still lists this device.
     *
     * Identifying a board takes seconds, so the cable can come out in the middle of it. The bus,
     * not the roster, answers "is it still here" - the roster is what this class builds.
     */
    private fun onBus(deviceId: Int): Boolean {
        val live = runCatching { manager?.deviceList?.values?.toList() }.getOrNull() ?: return true
        return live.any { it.deviceId == deviceId }
    }

    /**
     * The device that has replaced [oldId].
     *
     * A chip with its own USB peripheral drops off the bus when EN pulses and enumerates again
     * with a new device id and the same vendor and product ids. Without this the app keeps a
     * session on a port that will never carry another byte, which is exactly what a SYNC that
     * times out with nothing received looks like.
     */
    fun replacementFor(vendorId: Int, productId: Int, oldId: Int): Board? =
        devices.values.firstOrNull {
            it.deviceId != oldId && it.vendorId == vendorId && it.productId == productId
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

    /** What the roster calls a board: its family, with a version the name does not already carry. */
    private fun labelOf(family: BoardFamily, version: String, chip: String): String {
        val base = when (family) {
            BoardFamily.MARAUDER -> "ESP32 Marauder"
            BoardFamily.GHOSTESP -> "GhostESP"
            BoardFamily.BISCUIT -> "Biscuit"
            BoardFamily.SIGNALSENSOR -> "SignalSensor"
            BoardFamily.ESP32 -> chip.ifBlank { "ESP32 board" }
        }
        return when {
            version.isBlank() -> base
            base.endsWith(version) -> base
            else -> "$base v$version"
        }
    }

    private fun upsert(
        device: UsbDevice,
        hit: UsbBoardMatch.Hit,
        note: String,
        console: String = "",
    ) {
        // A probe that outlived the cable must not write the board back in: re-adding it here is
        // what used to leave an unplugged board in the roster, and in the ESP32 card, until the
        // app was restarted.
        if (!onBus(device.deviceId)) {
            devices.remove(device.deviceId)
            attached.remove(device.deviceId)
            return
        }
        val transport = transportOf(device)
        val chip = runCatching { device.productName }.getOrNull().orEmpty()
            .ifBlank { runCatching { device.manufacturerName }.getOrNull().orEmpty() }
        val label = labelOf(hit.family, hit.version, chip)
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
            // The probe's transcript, kept: it is the only text that may name the silicon.
            banner = console.replace('\n', ' ').trim().take(200),
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
