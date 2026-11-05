package dev.skyward.rf.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Tiny JSON-on-disk store. No database: the data volume here is small and highly portable. */
class FileStore(private val dir: File) {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    fun file(name: String): File = File(dir, name)

    fun readText(name: String): String? = runCatching {
        val f = File(dir, name)
        if (f.exists()) f.readText() else null
    }.getOrNull()

    fun writeText(name: String, text: String) {
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, name)
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(text)
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        }
    }

    inline fun <reified T> read(name: String): T? {
        val text = readText(name) ?: return null
        return runCatching { json.decodeFromString<T>(text) }.getOrNull()
    }

    inline fun <reified T> write(name: String, value: T) {
        writeText(name, json.encodeToString(value))
    }

    fun delete(name: String) {
        runCatching { File(dir, name).delete() }
    }

    fun list(prefix: String, suffix: String): List<String> = runCatching {
        dir.listFiles()?.filter { it.name.startsWith(prefix) && it.name.endsWith(suffix) }
            ?.map { it.name }?.sorted().orEmpty()
    }.getOrDefault(emptyList())
}
