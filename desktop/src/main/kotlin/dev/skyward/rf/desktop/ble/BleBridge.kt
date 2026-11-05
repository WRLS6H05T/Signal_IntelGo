package dev.skyward.rf.desktop.ble

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * One thing the Bluetooth adapter told us.
 *
 * Deliberately transport-shaped rather than app-shaped: this is what came off the air, not what
 * the host decided it means. The decoding lives in [dev.skyward.rf.desktop.host.DeviceHost], so
 * the same events could be produced by another platform's bridge without touching the host.
 */
sealed interface BleEvent {

    data class Ready(val adapter: String) : BleEvent

    data class Failed(val message: String) : BleEvent

    data class Advert(
        val address: String,
        val name: String,
        val rssi: Int,
        val serviceUuids: List<String>,
        /** Company id -> raw payload, as the advertisement carried it. */
        val manufacturer: Map<Int, ByteArray>,
        /** 128-bit service UUID (lowercase) -> raw payload. */
        val serviceData: Map<String, ByteArray>,
        val seenAt: Long,
    ) : BleEvent

    data class Connected(val address: String, val mtu: Int) : BleEvent

    data class Disconnected(val address: String) : BleEvent

    data class Notified(val address: String, val uuid: String, val data: ByteArray) : BleEvent

    data class Note(val message: String) : BleEvent
}

/**
 * The Windows Bluetooth Low Energy adapter, through a small Python process.
 *
 * Windows has no usable BLE API from the JVM. The alternatives are all worse than the obvious
 * one: WinRT through JNA is hundreds of lines of ABI that break silently, and every pure-Java BLE
 * library is Linux-only. `bleak` drives the same WinRT stack through CPython, so the bridge is a
 * hundred lines of Python and one process, and the app keeps a normal Kotlin interface.
 *
 * The protocol is JSON lines: the app writes commands to the child's stdin, the child writes
 * events to its stdout. Nothing is shared but text, so a bridge crash is a disconnect rather than
 * a broken-looking app.
 */
class BleBridge(
    private val script: File = defaultScript(),
    private val python: String = pythonExecutable(),
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _events = MutableSharedFlow<BleEvent>(replay = 8, extraBufferCapacity = 512)

    val events: SharedFlow<BleEvent> = _events

    private val lock = Any()

    private var process: Process? = null

    private var started = false

    private var exitNote = ""

    /** Where the bridge last went, so a failed start can be reported instead of looking idle. */
    val lastNote: String get() = exitNote

    fun start(): Boolean {
        synchronized(lock) {
            if (started) return true
            if (!script.exists()) {
                exitNote = "ble bridge script missing: ${script.absolutePath}"
                emit(BleEvent.Failed(exitNote))
                return false
            }
            val builder = ProcessBuilder(python, script.absolutePath)
                .redirectErrorStream(false)
            builder.redirectError(ProcessBuilder.Redirect.PIPE)
            val proc = runCatching { builder.start() }.getOrElse { error ->
                exitNote = "could not start $python: ${error.message}"
                emit(BleEvent.Failed(exitNote))
                return false
            }
            process = proc
            started = true
            startReader(proc, stdout = true)
            startReader(proc, stdout = false)
            return true
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            runCatching { sendCommand(buildJsonObject { put("cmd", "quit") }) }
            val proc = process
            process = null
            if (proc != null) {
                runCatching { proc.waitFor(1500, TimeUnit.MILLISECONDS) }
                if (proc.isAlive) runCatching { proc.destroyForcibly() }
            }
        }
    }

    fun scan(on: Boolean): Boolean {
        if (!on) return send(buildJsonObject { put("cmd", "scan"); put("on", false) })
        return send(buildJsonObject { put("cmd", "scan"); put("on", true) })
    }

    fun connect(address: String): Boolean =
        send(buildJsonObject { put("cmd", "connect"); put("address", address) })

    fun disconnect(address: String): Boolean =
        send(buildJsonObject { put("cmd", "disconnect"); put("address", address) })

    /**
     * Writes one chunk to a characteristic.
     *
     * Windows BLE takes one outstanding write at a time; the host paces these (see
     * [dev.skyward.rf.desktop.host.DeviceHost]) rather than firing a whole message at once.
     */
    fun write(address: String, characteristic: String, data: ByteArray): Boolean =
        send(
            buildJsonObject {
                put("cmd", "write")
                put("address", address)
                put("characteristic", characteristic)
                put("data", Base64.getEncoder().encodeToString(data))
            },
        )

    // ---------------- plumbing ----------------

    private fun send(command: JsonObject): Boolean {
        synchronized(lock) {
            if (!started || process == null) return false
            return runCatching {
                sendCommand(command)
                true
            }.getOrDefault(false)
        }
    }

    private fun sendCommand(command: JsonObject) {
        val proc = process ?: return
        val writer = proc.outputStream
        writer.write((command.toString() + "\n").toByteArray(Charsets.UTF_8))
        writer.flush()
    }

    private fun startReader(proc: Process, stdout: Boolean) {
        val stream = if (stdout) proc.inputStream else proc.errorStream
        Thread({
            runCatching {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines ->
                    lines.forEach { line ->
                        if (stdout) {
                            handleLine(line)
                        } else if (line.isNotBlank()) {
                            emit(BleEvent.Note(line.trim().take(300)))
                        }
                    }
                }
            }
            if (stdout) emit(BleEvent.Failed("the BLE bridge stopped"))
        }, if (stdout) "ble-bridge-out" else "ble-bridge-err").apply {
            isDaemon = true
            start()
        }
    }

    private fun emit(event: BleEvent) {
        _events.tryEmit(event)
    }

    /** Parses one bridge line. Public so a JVM test can prove the wire format without a radio. */
    fun handleLine(line: String) {
        val event = BleEventCodec.decode(line, json) ?: return
        // A bridge that says why it failed is more useful than one that says nothing.
        if (event is BleEvent.Failed) exitNote = event.message
        emit(event)
    }

    companion object {

        private const val ENV_PYTHON = "SIGNALGO_PYTHON"
        private const val PROP_BRIDGE = "signalg.ble.bridge"

        fun pythonExecutable(): String {
            System.getenv(ENV_PYTHON)?.takeIf { it.isNotBlank() }?.let { return it }
            return if (System.getProperty("os.name").lowercase().contains("win")) "python" else "python3"
        }

        /**
         * Finds the bridge whether the app is run from Gradle (module or root directory), from an
         * installed image (beside the app) or pointed at one by hand.
         */
        fun defaultScript(): File {
            System.getProperty(PROP_BRIDGE)?.takeIf { it.isNotBlank() }?.let { return File(it) }
            val candidates = listOf(
                File("tools/ble_bridge.py"),
                File("desktop/tools/ble_bridge.py"),
                File("ble_bridge.py"),
                File(System.getProperty("user.dir"), "desktop/tools/ble_bridge.py"),
            )
            return candidates.firstOrNull { it.exists() } ?: candidates[1]
        }
    }
}

/** The bridge's JSON-line vocabulary, apart from the process so it can be tested. */
object BleEventCodec {

    fun decode(line: String, json: Json = Json { ignoreUnknownKeys = true; isLenient = true }): BleEvent? {
        val text = line.trim()
        if (text.isEmpty() || !text.startsWith("{")) return null
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val kind = root["ev"]?.jsonPrimitive?.contentOrNull ?: return null
        return when (kind) {
            "ready" -> BleEvent.Ready(root.str("adapter").ifBlank { "default" })
            "error" -> BleEvent.Failed(root.str("message").ifBlank { "BLE bridge error" })
            "log" -> BleEvent.Note(root.str("message"))
            "advert" -> BleEvent.Advert(
                address = root.str("address"),
                name = root.str("name"),
                rssi = root["rssi"]?.jsonPrimitive?.intOrNull ?: 0,
                serviceUuids = root["service_uuids"]?.let { element ->
                    runCatching {
                        element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull?.lowercase() }
                    }.getOrNull()
                }.orEmpty(),
                manufacturer = root["manufacturer"]?.let { element ->
                    runCatching {
                        element.jsonObject.mapNotNull { (key, value) ->
                            val id = key.toIntOrNull() ?: return@mapNotNull null
                            val bytes = runCatching { Base64.getDecoder().decode(value.jsonPrimitive.content) }
                                .getOrNull() ?: return@mapNotNull null
                            id to bytes
                        }.toMap()
                    }.getOrNull()
                }.orEmpty(),
                serviceData = root["service_data"]?.let { element ->
                    runCatching {
                        element.jsonObject.mapNotNull { (key, value) ->
                            val bytes = runCatching { Base64.getDecoder().decode(value.jsonPrimitive.content) }
                                .getOrNull() ?: return@mapNotNull null
                            key.lowercase() to bytes
                        }.toMap()
                    }.getOrNull()
                }.orEmpty(),
                seenAt = System.currentTimeMillis(),
            )
            "connected" -> BleEvent.Connected(
                address = root.str("address"),
                mtu = root["mtu"]?.jsonPrimitive?.intOrNull ?: 185,
            )
            "disconnected" -> BleEvent.Disconnected(root.str("address"))
            "notify" -> BleEvent.Notified(
                address = root.str("address"),
                uuid = root.str("uuid").lowercase(),
                data = runCatching {
                    Base64.getDecoder().decode(root.str("data"))
                }.getOrNull() ?: return null,
            )
            else -> null
        }
    }

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: ""
}
