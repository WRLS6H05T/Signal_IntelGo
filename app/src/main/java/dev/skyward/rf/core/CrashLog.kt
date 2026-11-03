package dev.skyward.rf.core

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort crash recorder.
 *
 * The app is usually field-tested without a cable, so an uncaught exception is written to
 * `Android/data/<pkg>/files/crashes/` (app-specific external storage: no permission needed,
 * removed with the app). The newest report is readable from the map HUD and can be shared
 * straight out of the app, which is the only way to get a stack trace off the phone when ADB
 * cannot see it.
 */
object CrashLog {

    private const val DIR = "crashes"
    private const val KEEP = 5

    /** Wraps the existing handler so the system still shows its own crash dialog. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
        markSession(app)
    }

    /**
     * One line per launch, on external storage next to the crash reports. With no cable handy
     * this is the only way to tell which build is actually running on the phone, and when.
     */
    fun markSession(context: Context) {
        runCatching {
            val dir = dir(context).apply { mkdirs() }
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val file = File(dir, "session.txt")
            if (file.length() > 8192) file.delete()
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            file.appendText(
                "$stamp  v${info.versionName ?: "?"}  " +
                    "${Build.MANUFACTURER} ${Build.MODEL}  API ${Build.VERSION.SDK_INT}\n",
            )
        }
    }

    fun record(context: Context, thread: Thread, error: Throwable): File? = runCatching {
        val dir = dir(context).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash-$stamp-${error.javaClass.simpleName}.txt")
        file.writeText(
            buildString {
                appendLine("Signal_IntelGo crash report")
                appendLine("time   : " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                appendLine("thread : ${thread.name}")
                appendLine("model  : ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("abi    : ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")
                appendLine("error  : ${error.javaClass.name}: ${error.message}")
                appendLine()
                appendLine(error.stackTraceToString())
            },
        )
        dir.listFiles()?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
        file
    }.getOrNull()

    /**
     * Soft rendering faults: recorded without killing the process, so a bad frame leaves a
     * trace we can read later instead of only being visible as a crash.
     */
    fun recordQuiet(context: Context, tag: String, error: Throwable) {
        runCatching {
            val dir = dir(context).apply { mkdirs() }
            val file = File(dir, "render-errors.txt")
            if (file.length() > 32_768) file.delete()
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            file.appendText(
                "$stamp  [$tag]  ${error.javaClass.name}: ${error.message}\n" +
                    error.stackTraceToString() + "\n\n",
            )
        }
    }

    /** Newest report - hard crash or soft render fault - or null when there is none. */
    fun latest(context: Context): String? = runCatching {
        val soft = File(dir(context), "render-errors.txt").takeIf { it.length() > 0 }
        val hard = dir(context).listFiles()
            ?.filter { it.name.startsWith("crash-") }
            ?.maxByOrNull { it.lastModified() }
        listOfNotNull(soft, hard).maxByOrNull { it.lastModified() }?.readText()
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { dir(context).listFiles()?.forEach { it.delete() } }
    }

    private fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, DIR)
}
