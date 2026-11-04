package dev.skyward.rf.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * The WiGLE API, as WiGLE publishes it (swagger 3.1 at https://api.wigle.net/swagger.json).
 *
 * Everything here is one account's own data, authenticated with the API name and token from
 * wigle.net/account: upload a drive, list what has been uploaded, pull the KML of any upload, and
 * search the observations that account has contributed. Nothing is proxied through a server of
 * this app's, and no key ships inside it.
 *
 * WiGLE is explicit about pagination - a search answers with `searchAfter` and the next request
 * feeds it back - so the pages are exposed as they are rather than hidden behind a fetch-everything
 * loop that would burn the account's daily query allowance.
 */
class WigleApi(private val http: OkHttpClient) {

    data class Outcome(val ok: Boolean, val message: String)

    /** One file this account has uploaded, from `file/transactions`. */
    data class Upload(
        val transid: String,
        val fileName: String,
        val bytes: Long,
        val lines: Long,
        val discovered: Long,
        val total: Long,
        val status: String,
        val firstTime: String,
        val lastUpdate: String,
    )

    /** One network from a search: Wi-Fi, Bluetooth or cell, flattened to what the panel shows. */
    data class Wireless(
        val netid: String,
        val name: String,
        val lat: Double?,
        val lon: Double?,
        val type: String,
        val channel: Int,
        val encryption: String,
        val qos: Int,
        val firstSeen: String,
        val lastSeen: String,
    )

    data class UploadsPage(
        val ok: Boolean,
        val results: List<Upload> = emptyList(),
        val message: String = "",
    )

    data class SearchPage(
        val ok: Boolean,
        val total: Long = 0L,
        val results: List<Wireless> = emptyList(),
        /** Feed this back as the next page; blank means there is no next page. */
        val next: String = "",
        val message: String = "",
    )

    companion object {
        const val BASE = "https://api.wigle.net"

        /** WiGLE's own page size for site authentication. */
        const val PAGE = 100

        /** Errors and search answers are small; a KML is not, and is read whole. */
        private const val MAX_BODY = 4_000
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---------------- upload ----------------

    suspend fun upload(csv: String, fileName: String, name: String, token: String): Outcome =
        withContext(Dispatchers.IO) {
            val user = name.trim()
            val key = token.trim()
            if (user.isBlank() || key.isBlank()) return@withContext Outcome(false, "API name and token are required")
            if (csv.lineSequence().count() <= 2) return@withContext Outcome(false, "nothing logged to upload yet")
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
                .url("$BASE/api/v2/file/upload")
                .header("Authorization", Credentials.basic(user, key))
                .header("Accept", "application/json")
                .post(body)
                .build()
            try {
                http.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty().take(MAX_BODY)
                    val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                    val success = root?.get("success")?.jsonPrimitive?.contentOrNull?.toBoolean() == true
                    val ids = root?.get("results")?.jsonObject?.get("transids")?.let { ids ->
                        runCatching { ids.jsonArray }.getOrNull()
                    }.orEmpty()
                    val first = ids.firstOrNull()?.jsonObject?.let { row ->
                        row["transId"]?.jsonPrimitive?.contentOrNull
                    }
                    val ok = resp.isSuccessful && success
                    Outcome(
                        ok = ok,
                        message = when {
                            ok && !first.isNullOrBlank() -> "uploaded as $first"
                            ok -> "uploaded to WiGLE"
                            resp.code == 401 -> "WiGLE rejected the key (HTTP 401)"
                            else -> explain(resp.code, text)
                        },
                    )
                }
            } catch (e: IOException) {
                Outcome(false, e.message ?: "network error")
            }
        }

    /** Confirms the key belongs to an account, which is what the panel needs before a long job. */
    suspend fun verify(name: String, token: String): Outcome = withContext(Dispatchers.IO) {
        val (code, text) = call("$BASE/api/v2/profile/user".toHttpUrl(), name, token)
        if (code !in 200..299) return@withContext Outcome(false, explain(code, text))
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        val who = root?.get("userName")?.jsonPrimitive?.contentOrNull
            ?: root?.get("user")?.jsonPrimitive?.contentOrNull
            ?: "this account"
        Outcome(true, "Key works for $who")
    }

    // ---------------- upload history ----------------

    /** One page of this account's uploads, newest first. */
    suspend fun uploads(name: String, token: String, start: Int = 0): UploadsPage = withContext(Dispatchers.IO) {
        val url = "$BASE/api/v2/file/transactions".toHttpUrl().newBuilder()
            .addQueryParameter("pagestart", start.toString())
            .addQueryParameter("pageend", PAGE.toString())
            .build()
        val (code, text) = call(url, name, token)
        if (code !in 200..299) return@withContext UploadsPage(false, message = explain(code, text))
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return@withContext UploadsPage(false, message = "unreadable answer from WiGLE")
        val rows = runCatching { root["results"]?.jsonArray }.getOrNull().orEmpty()
        val list = rows.mapNotNull { row -> parseUpload(runCatching { row.jsonObject }.getOrNull()) }
        UploadsPage(true, list, "OK")
    }

    /** The KML summary WiGLE keeps for one upload; null when it refuses. */
    suspend fun kml(transid: String, name: String, token: String): Pair<String?, String> =
        withContext(Dispatchers.IO) {
            if (transid.isBlank()) return@withContext null to "no transaction id"
            val (code, text) = call("$BASE/api/v2/file/kml/$transid".toHttpUrl(), name, token, wholeBody = true)
            if (code in 200..299 && text.isNotBlank()) text to "OK" else null to explain(code, text)
        }

    // ---------------- search ----------------

    /**
     * Searches the observations this account has contributed.
     *
     * [kind] picks the WiGLE endpoint: "wifi", "bt" or "cell". [query] is matched against the name
     * field that endpoint uses, and anything that looks like a MAC is also sent as a network id.
     * [next] is the previous page's searchAfter.
     */
    suspend fun search(
        name: String,
        token: String,
        query: String,
        kind: String,
        next: String = "",
        onlyMine: Boolean = true,
    ): SearchPage = withContext(Dispatchers.IO) {
        val path = when (kind) {
            "bt" -> "/api/v2/bluetooth/search"
            "cell" -> "/api/v2/cell/search"
            else -> "/api/v2/network/search"
        }
        val q = query.trim()
        val builder = ("$BASE$path").toHttpUrl().newBuilder()
            .addQueryParameter("resultsPerPage", PAGE.toString())
        if (onlyMine) builder.addQueryParameter("onlymine", "true")
        if (next.isNotBlank()) builder.addQueryParameter("searchAfter", next)
        if (q.isNotBlank()) {
            if (q.contains(":")) builder.addQueryParameter("netid", q)
            if (kind == "bt") builder.addQueryParameter("name", q) else builder.addQueryParameter("ssid", q)
        }
        val (code, text) = call(builder.build(), name, token)
        if (code == 404) return@withContext SearchPage(true, 0, message = "no matches")
        if (code !in 200..299) return@withContext SearchPage(false, message = explain(code, text))
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return@withContext SearchPage(false, message = "unreadable answer from WiGLE")
        val rows = runCatching { root["results"]?.jsonArray }.getOrNull().orEmpty()
        val list = rows.mapNotNull { row -> parseWireless(runCatching { row.jsonObject }.getOrNull(), kind) }
        SearchPage(
            ok = true,
            total = root["totalResults"]?.jsonPrimitive?.longOrNull ?: list.size.toLong(),
            results = list,
            next = root["searchAfter"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            message = "OK",
        )
    }

    // ---------------- parsing ----------------

    private fun parseUpload(o: JsonObject?): Upload? {
        if (o == null) return null
        val id = o.str("transid") ?: o.str("transId") ?: return null
        return Upload(
            transid = id,
            fileName = o.str("fileName") ?: "",
            bytes = o.long("fileSize"),
            lines = o.long("fileLines"),
            discovered = o.long("discovered"),
            total = o.long("total"),
            status = o.str("status") ?: "",
            firstTime = o.str("firstTime") ?: "",
            lastUpdate = o.str("lastupdt") ?: o.str("lastupdate") ?: "",
        )
    }

    private fun parseWireless(o: JsonObject?, kind: String): Wireless? {
        if (o == null) return null
        val netid = o.str("netid") ?: o.str("networkId") ?: o.str("cellid") ?: return null
        val lat = o.dbl("trilat") ?: o.dbl("trilateratedLatitude") ?: o.dbl("latitude")
        val lon = o.dbl("trilong") ?: o.dbl("trilateratedLongitude") ?: o.dbl("longitude")
        return Wireless(
            netid = netid,
            name = o.str("ssid") ?: o.str("name") ?: "",
            lat = lat,
            lon = lon,
            type = o.str("type") ?: kind,
            channel = o.int("channel"),
            encryption = o.str("encryption") ?: "",
            qos = o.int("qos"),
            firstSeen = o.str("firsttime") ?: o.str("firstSeen") ?: "",
            lastSeen = o.str("lasttime") ?: o.str("lastSeen") ?: o.str("lastupdt") ?: "",
        )
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.long(key: String): Long =
        this[key]?.jsonPrimitive?.longOrNull ?: this[key]?.jsonPrimitive?.intOrNull?.toLong() ?: 0L

    private fun JsonObject.dbl(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

    private fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.intOrNull ?: 0

    // ---------------- transport ----------------

    private fun call(
        url: HttpUrl,
        name: String,
        token: String,
        wholeBody: Boolean = false,
    ): Pair<Int, String> {
        val user = name.trim()
        val key = token.trim()
        if (user.isBlank() || key.isBlank()) return -1 to "API name and token are required"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(user, key))
            .header("Accept", "application/json")
            .get()
            .build()
        return try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                resp.code to (if (wholeBody) text else text.take(MAX_BODY))
            }
        } catch (e: IOException) {
            -1 to (e.message ?: "network error")
        }
    }

    /** WiGLE says why it refused in the body, and the code alone is rarely actionable. */
    private fun explain(code: Int, text: String): String {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        val detail = root?.let { obj ->
            listOf("message", "error", "warning", "description")
                .firstNotNullOfOrNull { k -> obj[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } }
        }
        return when {
            code == 401 -> "WiGLE rejected the key or token (HTTP 401)"
            code == 429 -> "WiGLE's daily query limit is reached for this key"
            code == -1 -> detail ?: "network error"
            !detail.isNullOrBlank() -> detail
            else -> "HTTP $code"
        }
    }
}
