package dev.skyward.rf.desktop.wardrive

import dev.skyward.rf.desktop.wardrive.DeskDrives
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Drives pulled off a phone that is plugged in.
 *
 * The phone keeps its sessions in internal storage, which a cable cannot see, so it mirrors them to
 * its own external files folder - `Android/data/<package>/files/SignalIntelGo/wardrives`. On
 * Windows that folder is not a drive letter (MTP is a shell namespace, not a filesystem), so the
 * pull goes through `adb`, which is the one USB path a JVM can read. When the SDK is not installed
 * the same folder is still looked for on any mounted drive, which covers a phone left in USB
 * storage mode and any card that was copied off one.
 *
 * Importing is idempotent: each session is written under its own id, so pulling twice leaves one
 * file per drive rather than duplicates.
 */
class UsbImport(private val drives: DeskDrives, home: File) {

    /** Where a pull is staged: a phone that vanishes mid-copy must not leave part-files behind. */
    private val scratch = File(home, "usb-pull")

    /** What the Setup pane shows: whether a cable path exists, and what it last did. */
    data class State(
        val adb: String = "",
        val devices: Int = 0,
        val imported: Int = 0,
        val note: String = "no phone attached",
        val at: Long = 0L,
    )

    private companion object {
        const val PACKAGE = "dev.skyward.rf"
        const val REMOTE = "/sdcard/Android/data/$PACKAGE/files/SignalIntelGo"
        const val TIMEOUT_S = 25L
    }

    /** The adb binary: the SDK's copy first, then whatever PATH offers. */
    fun adb(): File? {
        val home = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        val candidates = buildList {
            if (!home.isNullOrBlank()) add(File(home, "platform-tools/adb.exe"))
            val local = System.getenv("LOCALAPPDATA")
            if (!local.isNullOrBlank()) add(File(local, "Android/Sdk/platform-tools/adb.exe"))
        }
        candidates.firstOrNull { it.isFile }?.let { return it }
        val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, "adb.exe") }
            .firstOrNull { it.isFile }
        return onPath
    }

    /**
     * Copies one folder of session files in, wherever it came from.
     *
     * Looks in `wardrives/` first and then the folder itself, because a phone's export and a
     * hand-copied directory do not lay the files out the same way.
     */
    fun importFolder(folder: File, from: String = "phone via USB"): Int {
        if (!folder.isDirectory) return 0
        val files = buildList {
            val inner = File(folder, "wardrives")
            addAll((if (inner.isDirectory) inner else folder).listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toList().orEmpty())
        }
        var imported = 0
        for (file in files) {
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            if (drives.save(text, from) != null) imported++
        }
        return imported
    }

    /** `adb devices -l`, counting only devices that are actually ready. */
    fun devices(adb: File): List<String> {
        val out = run(adb, "devices") ?: return emptyList()
        return out.lineSequence()
            .drop(1)
            .filter { it.contains("\tdevice") || it.endsWith(" device") }
            .map { it.substringBefore('\t').substringBefore(' ') }
            .filter { it.isNotBlank() }
            .toList()
    }

    /** One pass: find a device, pull its export folder into a scrape directory, import it. */
    fun pull(adb: File): State {
        val serials = devices(adb)
        if (serials.isEmpty()) {
            return State(adb = adb.path, devices = 0, note = "no phone attached")
        }
        var total = 0
        for (serial in serials) {
            scratch.mkdirs()
            // Pulled into the scrape folder, then read from there: a failed pull leaves the files
            // already imported on the desk untouched.
            run(adb, "-s", serial, "pull", REMOTE, scratch.path)
            total += importFolder(File(scratch, "SignalIntelGo"))
            total += importFolder(scratch)
        }
        return State(
            adb = adb.path,
            devices = serials.size,
            imported = total,
            note = if (total == 0) "phone attached · nothing to import yet" else "imported $total drive(s)",
            at = System.currentTimeMillis(),
        )
    }

    private fun run(adb: File, vararg args: String): String? = runCatching {
        val process = ProcessBuilder(adb.path, *args)
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        text
    }.getOrNull()
}
