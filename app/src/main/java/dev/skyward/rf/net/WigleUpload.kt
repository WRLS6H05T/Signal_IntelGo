package dev.skyward.rf.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * WiGLE CSV upload, to the user's own account.
 *
 * WiGLE takes a file, not a JSON stream: the endpoint is a multipart POST carrying one `file`
 * part in WiGLE's CSV dialect, authenticated with the account's API name and token as HTTP
 * basic auth. There is no other way in, so the app uploads the log it already keeps rather than
 * reshaping it for a second API.
 *
 * The token is the user's own and is stored beside their other settings; nothing here talks to
 * a server belonging to this app, and no key shipped with it would be a key everyone shares.
 */
class WigleUpload(private val http: OkHttpClient) {

    data class Outcome(val ok: Boolean, val message: String)

    companion object {
        const val URL = "https://api.wigle.net/api/v2/file/upload"
        /** WiGLE answers with its own wording, but never an empty body on failure. */
        private const val MAX_BODY = 4_000
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun upload(
        csv: String,
        fileName: String,
        name: String,
        token: String,
    ): Outcome = withContext(Dispatchers.IO) {
        val user = name.trim()
        val key = token.trim()
        if (user.isBlank() || key.isBlank()) {
            return@withContext Outcome(false, "API name and token are required")
        }
        if (csv.lineSequence().count() <= 2) {
            return@withContext Outcome(false, "nothing logged to upload yet")
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                fileName,
                csv.toByteArray(Charsets.UTF_8).toRequestBody("text/csv".toMediaType()),
            )
            .addFormDataPart("donate", "false")
            .build()
        val request = Request.Builder()
            .url(URL)
            .header("Authorization", Credentials.basic(user, key))
            .header("Accept", "application/json")
            .post(body)
            .build()
        try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty().take(MAX_BODY)
                val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                val success = root?.get("success")?.jsonPrimitive?.contentOrNull?.toBoolean() == true
                val message = root?.get("message")?.jsonPrimitive?.contentOrNull
                val ok = resp.isSuccessful && success
                Outcome(
                    ok = ok,
                    message = when {
                        ok && !message.isNullOrBlank() -> message
                        ok -> "uploaded to WiGLE"
                        resp.code == 401 -> "WiGLE rejected the key (HTTP 401)"
                        !message.isNullOrBlank() -> message
                        else -> "HTTP ${resp.code}"
                    },
                )
            }
        } catch (e: IOException) {
            Outcome(false, e.message ?: "network error")
        }
    }
}
