package dev.skyward.rf.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Writes an export to the cache and hands it to the system share sheet. */
fun shareExport(context: Context, fileName: String, mime: String, content: String) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, fileName)
    file.writeText(content)
    share(context, file, fileName, mime)
}

/** The same hand-off for something that arrived as bytes, such as a file pulled off a device. */
fun shareBytes(context: Context, fileName: String, mime: String, bytes: ByteArray) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, fileName)
    file.writeBytes(bytes)
    share(context, file, fileName, mime)
}

/** Best effort from the extension; the share sheet cares more about the stream than the type. */
fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "json" -> "application/json"
    "txt", "csv", "lang", "log" -> "text/plain"
    else -> "application/octet-stream"
}

private fun share(context: Context, file: File, fileName: String, mime: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Export $fileName"))
}
