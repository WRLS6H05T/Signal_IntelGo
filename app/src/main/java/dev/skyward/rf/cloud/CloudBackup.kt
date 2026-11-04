package dev.skyward.rf.cloud

import android.content.Context
import dev.skyward.rf.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Keeps a copy of the app's data on the user's account, so uninstalling is not losing it.
 *
 * The copy is one archive of `files/data`, the same directory everything else already writes to:
 * the drive sessions, the per-signal log, the logged registry and the settings file. Uploading
 * the directory rather than each feature's state means a new feature that writes a file is
 * backed up without anyone remembering to add it here.
 *
 * Supabase's REST layer takes rows, so the archive is cut into pieces and stored one per row.
 * The rows are replaced on every upload instead of merged: a shorter backup must not be stitched
 * together with leftovers from a longer one.
 */
class CloudBackup(
    private val dataDir: File,
    private val http: OkHttpClient,
    private val account: CloudAccount,
    private val settings: () -> Settings,
    context: Context,
) {

    data class Outcome(val ok: Boolean, val message: String)

    data class State(
        val busy: Boolean = false,
        val lastAt: Long = 0L,
        val message: String = "",
    )

    private val prefs = context.getSharedPreferences("intelgo_backup", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        State(
            lastAt = prefs.getLong("lastAt", 0L),
            message = prefs.getString("lastMessage", "").orEmpty(),
        ),
    )
    val state: StateFlow<State> = _state

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    /** The table the archive lives in; see the README for the two statements that create it. */
    private val table = "/rest/v1/backups"

    suspend fun backupNow(): Outcome = withContext(Dispatchers.IO) {
        val cfg = settings()
        val url = cfg.cloudUrl.trim().trimEnd('/')
        val key = cfg.cloudKey.trim()
        if (url.isBlank() || key.isBlank()) return@withContext said(false, "Add the project URL and anon key first")
        val token = account.accessToken(url, key) ?: return@withContext said(false, "Sign in first")
        val uid = account.userId ?: return@withContext said(false, "Sign in first")

        _state.value = _state.value.copy(busy = true, message = "Backing up...")
        try {
            val bundle = BackupArchive.pack(dataDir)
            val count = bundle.chunks.size

            val (clearCode, _) = call(url, key, token, "$table?user_id=eq.$uid", "DELETE", prefer = null)
            if (clearCode !in 200..299) {
                return@withContext said(false, "The account table refused the clear (HTTP $clearCode)")
            }

            bundle.chunks.forEachIndexed { idx, chunk ->
                val body = buildJsonArray {
                    addJsonObject {
                        put("user_id", uid)
                        put("idx", idx)
                        put("payload", chunk)
                    }
                }.toString()
                val (code, text) = call(url, key, token, table, "POST", body, prefer = "return=minimal")
                if (code !in 200..299) {
                    return@withContext said(
                        false,
                        "Upload stopped at piece ${idx + 1} of $count (HTTP $code${apiHint(text, code)})",
                    )
                }
            }

            val now = System.currentTimeMillis()
            prefs.edit().putLong("lastAt", now).apply()
            _state.value = State(
                busy = false,
                lastAt = now,
                message = "Backed up ${bundle.manifest.files} files, ${bundle.encodedChars / 1024} kB",
            )
            saveMessage(_state.value.message)
            Outcome(true, _state.value.message)
        } catch (e: IOException) {
            said(false, e.message ?: "network error")
        } catch (e: IllegalStateException) {
            said(false, e.message ?: "backup failed")
        }
    }

    suspend fun restoreNow(): Outcome = withContext(Dispatchers.IO) {
        val cfg = settings()
        val url = cfg.cloudUrl.trim().trimEnd('/')
        val key = cfg.cloudKey.trim()
        if (url.isBlank() || key.isBlank()) return@withContext said(false, "Add the project URL and anon key first")
        val token = account.accessToken(url, key) ?: return@withContext said(false, "Sign in first")
        val uid = account.userId ?: return@withContext said(false, "Sign in first")

        _state.value = _state.value.copy(busy = true, message = "Fetching the account copy...")
        try {
            val path = "$table?user_id=eq.$uid&select=idx,payload&order=idx.asc"
            val (code, text) = call(url, key, token, path, "GET", null, prefer = null)
            if (code !in 200..299) {
                return@withContext said(false, "The account table refused the read (HTTP $code${apiHint(text, code)})")
            }
            val rows = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull()
                ?: return@withContext said(false, "The account copy could not be read")
            if (rows.isEmpty()) return@withContext said(false, "No backup on this account yet")

            val encoded = ByteArrayOutputStream().use { out ->
                rows.forEach { row ->
                    val piece = row.jsonObject["payload"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    out.write(piece.toByteArray(Charsets.UTF_8))
                }
                out.toString(Charsets.UTF_8.name())
            }
            val manifest = BackupArchive.unpack(encoded, dataDir)
                ?: return@withContext said(false, "The account copy is not readable")
            val restored = if (manifest.files > 0) "${manifest.files} files" else "the account copy"
            said(true, "Restored $restored. Close and reopen Signal_IntelGo to load it.")
        } catch (e: IOException) {
            said(false, e.message ?: "network error")
        } catch (e: IllegalStateException) {
            said(false, e.message ?: "restore failed")
        }
    }

    /**
     * The automatic copy taken when the app leaves the foreground.
     *
     * It only runs when something actually changed since the last upload, so a phone left in a
     * pocket does not spend data re-sending an unchanged archive.
     */
    suspend fun autoBackup(): Outcome? {
        if (!account.state.value.signedIn) return null
        val cfg = settings()
        if (cfg.cloudUrl.isBlank() || cfg.cloudKey.isBlank()) return null
        val newest = newestChange()
        val last = _state.value.lastAt
        if (newest == 0L || newest <= last) return null
        return backupNow()
    }

    private fun newestChange(): Long = runCatching {
        dataDir.walkTopDown()
            .filter { it.isFile }
            .filter { !it.relativeTo(dataDir).invariantSeparatorsPath.startsWith("tle/") }
            .maxOfOrNull { it.lastModified() } ?: 0L
    }.getOrDefault(0L)

    private fun said(ok: Boolean, message: String): Outcome {
        _state.value = _state.value.copy(busy = false, message = message)
        saveMessage(message)
        return Outcome(ok, message)
    }

    private fun saveMessage(message: String) {
        prefs.edit().putString("lastMessage", message).apply()
    }

    /** Supabase says why it refused in the body; the code alone is not actionable. */
    private fun apiHint(text: String, code: Int): String {
        if (code == 404) return " - is the backups table created?"
        if (code == 401 || code == 403) return " - the row policies are missing"
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return ""
        val detail = listOf("message", "msg", "error_description", "hint", "details")
            .firstNotNullOfOrNull { root[it]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
        return if (detail.isNullOrBlank()) "" else " - $detail"
    }

    private fun call(
        url: String,
        key: String,
        token: String,
        path: String,
        method: String,
        body: String? = null,
        prefer: String? = null,
    ): Pair<Int, String> {
        val builder = Request.Builder()
            .url(url + path)
            .header("apikey", key)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
        if (prefer != null) builder.header("Prefer", prefer)
        val payload = (body ?: "").toRequestBody(mediaType)
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> builder.post(payload)
        }
        return try {
            http.newCall(builder.build()).execute().use { resp ->
                resp.code to resp.body?.string().orEmpty().take(4_000)
            }
        } catch (e: IOException) {
            -1 to (e.message ?: "network error")
        }
    }
}
