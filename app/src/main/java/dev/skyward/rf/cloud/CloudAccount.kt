package dev.skyward.rf.cloud

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Email and password sign-in, against an account server the user owns.
 *
 * The app talks to Supabase's auth endpoints directly rather than pulling in an SDK: sign-in is
 * one POST, refresh is another, and both fit in this file. That keeps the APK small and, more to
 * the point, keeps the whole exchange auditable - nothing here is a vendor client that the user
 * has to trust with more than the two requests it makes.
 *
 * The password is never stored. What is stored is the refresh token, in this app's private
 * preferences, so a signed-in phone stays signed in until it is signed out or the token ages out.
 */
class CloudAccount(context: Context, private val http: OkHttpClient) {

    data class Session(
        val userId: String,
        val email: String,
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
    )

    data class State(
        val signedIn: Boolean = false,
        val email: String? = null,
        val busy: Boolean = false,
        val message: String? = null,
    )

    private val prefs = context.getSharedPreferences("intelgo_account", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    /** Guards against two screens refreshing at once and racing each other's tokens. */
    private val lock = Any()

    private var session: Session? = load()

    init {
        session?.let { _state.value = State(signedIn = true, email = it.email) }
    }

    val userId: String? get() = session?.userId

    val email: String? get() = session?.email

    /** Creates the account. Returns signed in, or a note about the confirmation email. */
    suspend fun signUp(baseUrl: String, apiKey: String, address: String, password: String) {
        val email = address.trim()
        val problem = check(baseUrl, apiKey, email, password)
        if (problem != null) {
            _state.value = _state.value.copy(message = problem)
            return
        }
        _state.value = _state.value.copy(busy = true, message = null)
        val (code, text) = token(
            baseUrl = baseUrl,
            apiKey = apiKey,
            grant = "signup",
            body = buildJsonObject {
                put("email", email)
                put("password", password)
            },
            endpoint = "/auth/v1/signup",
        )
        when {
            code !in 200..299 -> fail(text, code)
            adopt(text) -> Unit
            // Supabase answers 200 with a user but no session while the address is unconfirmed.
            else -> _state.value = State(
                signedIn = false,
                email = email,
                message = "Account created. Confirm the link it emails you, then sign in.",
            )
        }
    }

    suspend fun signIn(baseUrl: String, apiKey: String, address: String, password: String) {
        val email = address.trim()
        val problem = check(baseUrl, apiKey, email, password)
        if (problem != null) {
            _state.value = _state.value.copy(message = problem)
            return
        }
        _state.value = _state.value.copy(busy = true, message = null)
        val (code, text) = token(
            baseUrl = baseUrl,
            apiKey = apiKey,
            grant = "password",
            body = buildJsonObject {
                put("email", email)
                put("password", password)
            },
        )
        if (code in 200..299 && adopt(text)) return
        fail(text, code)
    }

    suspend fun signOut(baseUrl: String, apiKey: String) {
        val current = session
        session = null
        prefs.edit().clear().apply()
        _state.value = State(signedIn = false, message = "Signed out. The file on this phone is untouched.")
        if (current == null || baseUrl.isBlank()) return
        // Best effort: the local session is already gone, and a failed revoke changes nothing
        // the user can see.
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(baseUrl.trimEnd('/') + "/auth/v1/logout")
                    .header("apikey", apiKey)
                    .header("Authorization", "Bearer ${current.accessToken}")
                    .post(ByteArray(0).toRequestBody(mediaType))
                    .build()
                http.newCall(request).execute().close()
            }
        }
    }

    /**
     * A usable access token, refreshed when the last one is close to expiring.
     *
     * Null means signed out: either there was never a session, or the refresh token was refused
     * and the user has to sign in again.
     */
    suspend fun accessToken(baseUrl: String, apiKey: String): String? {
        val current = synchronized(lock) { session } ?: return null
        if (System.currentTimeMillis() < current.expiresAt) return current.accessToken
        if (baseUrl.isBlank() || apiKey.isBlank()) return current.accessToken
        val (code, text) = token(
            baseUrl = baseUrl,
            apiKey = apiKey,
            grant = "refresh_token",
            body = buildJsonObject { put("refresh_token", current.refreshToken) },
        )
        return when {
            code in 200..299 && adopt(text) -> session?.accessToken
            code == 400 || code == 401 -> {
                // The refresh token is spent or revoked. Keeping it would leave every request
                // failing with no explanation.
                synchronized(lock) {
                    session = null
                    prefs.edit().clear().apply()
                }
                _state.value = State(signedIn = false, message = "Session expired. Sign in again.")
                null
            }
            else -> current.accessToken
        }
    }

    private fun check(baseUrl: String, apiKey: String, email: String, password: String): String? = when {
        baseUrl.isBlank() || apiKey.isBlank() -> "Add the project URL and anon key first"
        !email.contains("@") || email.length < 5 -> "That does not look like an email address"
        password.length < 6 -> "The password needs at least 6 characters"
        else -> null
    }

    private fun fail(text: String, code: Int) {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        val detail = root?.let { obj ->
            listOf("msg", "error_description", "message", "error")
                .firstNotNullOfOrNull { key -> obj[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } }
        }
        val message = when {
            !detail.isNullOrBlank() -> detail
            code == 401 -> "Email or password is wrong"
            code == 429 -> "Too many attempts. Wait a minute and try again."
            else -> "HTTP $code"
        }
        _state.value = _state.value.copy(busy = false, message = message)
    }

    private suspend fun token(
        baseUrl: String,
        apiKey: String,
        grant: String,
        body: JsonObject,
        endpoint: String = "/auth/v1/token?grant_type=$grant",
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + endpoint)
            .header("apikey", apiKey)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(mediaType))
            .build()
        try {
            http.newCall(request).execute().use { resp ->
                resp.code to resp.body?.string().orEmpty().take(4000)
            }
        } catch (e: IOException) {
            -1 to (e.message ?: "network error")
        }
    }

    /** Stores a session from a token response; false when the response carried no session. */
    private fun adopt(text: String): Boolean {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        val access = root?.get("access_token")?.jsonPrimitive?.contentOrNull
        val refresh = root?.get("refresh_token")?.jsonPrimitive?.contentOrNull
        val user = root?.get("user")?.let { runCatching { it.jsonObject }.getOrNull() }
        val id = user?.get("id")?.jsonPrimitive?.contentOrNull
        val address = user?.get("email")?.jsonPrimitive?.contentOrNull
        if (access.isNullOrBlank() || refresh.isNullOrBlank() || id.isNullOrBlank()) return false
        val seconds = root?.get("expires_in")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
        // A minute of slack: a token that expires mid-request is a failed backup.
        val next = Session(
            userId = id,
            email = address ?: email ?: "",
            accessToken = access,
            refreshToken = refresh,
            expiresAt = System.currentTimeMillis() + (seconds * 1000L) - 60_000L,
        )
        synchronized(lock) { session = next }
        prefs.edit()
            .putString("userId", next.userId)
            .putString("email", next.email)
            .putString("access", next.accessToken)
            .putString("refresh", next.refreshToken)
            .putLong("expiresAt", next.expiresAt)
            .apply()
        _state.value = State(signedIn = true, email = next.email, message = "Signed in")
        return true
    }

    private fun load(): Session? {
        val id = prefs.getString("userId", null) ?: return null
        val refresh = prefs.getString("refresh", null) ?: return null
        return Session(
            userId = id,
            email = prefs.getString("email", null).orEmpty(),
            accessToken = prefs.getString("access", null).orEmpty(),
            refreshToken = refresh,
            expiresAt = prefs.getLong("expiresAt", 0L),
        )
    }
}
