package dev.skyward.rf.wardrive

import android.content.Context
import java.io.File

/**
 * The drives, where a cable can reach them.
 *
 * A session lives in the app's internal storage, which neither MTP nor `adb pull` can read, so every
 * drive is mirrored into the app's external files folder the moment it is closed:
 * `Android/data/<package>/files/SignalIntelGo/wardrives/session_<id>.json`. Nothing else is copied
 * and nothing is deleted: the file is the same JSON the peer link sends, so the desk imports it with
 * the same reader it uses for a drive that arrived over Bluetooth.
 *
 * The mirror is refreshed whenever a drive ends and once when the app starts, which is what makes a
 * cable pull work without the user having to press anything on the phone first.
 */
class UsbExport(private val context: Context, private val wardrive: WardriveEngine) {

    /** The folder a cable sees, shown in the app so the path is never a guess. */
    fun folder(): String =
        context.getExternalFilesDir(null)?.let { File(it, "SignalIntelGo/wardrives").path }
            ?: "external storage unavailable"

    /** Writes every drive out; returns how many files were written. */
    fun export(): Int {
        val root = context.getExternalFilesDir(null) ?: return 0
        val out = File(root, "SignalIntelGo/wardrives").apply { mkdirs() }
        var written = 0
        for (session in wardrive.sessions.value) {
            val json = wardrive.sessionJson(session.id) ?: continue
            if (runCatching { File(out, "session_${session.id}.json").writeText(json) }.isSuccess) written++
        }
        runCatching {
            File(root, "SignalIntelGo/readme.txt").writeText(
                "Signal IntelGo drives for the desktop command centre.\n" +
                    "Plug the phone in and use the desk's Setup > USB import, or run:\n" +
                    "  adb pull /sdcard/Android/data/dev.skyward.rf/files/SignalIntelGo\n" +
                    "Files here are the sessions as recorded; the app rewrites them after every drive.\n",
            )
        }
        return written
    }
}
