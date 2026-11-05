package dev.skyward.rf.desktop.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Google sign-in for the desktop host, done the way Google requires an installed app to do it.
 *
 * No embedded browser and no password ever seen by this process: the flow is the loopback one
 * from Google's own protocol documentation. A local HTTP server listens on a random port, the
 * system browser is opened at Google's consent page, and the code that comes back is exchanged
 * for tokens with a PKCE verifier that never left this machine.
 *
 * `client_id` comes from the environment or from `~/.signintelgo/google.json`, because it belongs
 * to the person running the app, not to the repository. With no client id the window says so and
 * the host still runs - a guest session is a working session.
 */
class GoogleAuth(
    private val scope: CoroutineScope,
    private val home: Path = Paths.get(System.getProperty("user.home"), ".signintelgo"),
) {

    companion object {
        private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        private const val SCOPE = "openid email profile"
        private const val ENV_CLIENT_ID = "SIGNAL_GOOGLE_CLIENT_ID"
        private const val ENV_CLIENT_SECRET = "SIGNAL_GOOGLE_CLIENT_SECRET"
        private const val SIGN_IN_TIMEOUT_MS = 180_000L

        /** RFC 7636 appendix B, so the transform is proven rather than assumed. */
        fun challengeFor(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }
    }

    data class Account(
        val email: String,
        val name: String,
        val picture: String = "",
        val expiresAt: Long = 0L,
    )

    sealed interface State {
        data class SignedOut(val detail: String = "not signed in") : State
        data class SigningIn(val detail: String) : State
        data class SignedIn(val account: Account) : State
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    private val _state = MutableStateFlow<State>(State.SignedOut())
    val state: StateFlow<State> = _state

    private val authFile: Path = home.resolve("auth.json")
    private val configFile: Path = home.resolve("google.json")
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(15)).build()

    init {
        scope.launch(Dispatchers.IO) { restore() }
    }

    /** The client id this machine is configured with, or blank. */
    fun clientId(): String {
        System.getenv(ENV_CLIENT_ID)?.takeIf { it.isNotBlank() }?.let { return it }
        val root = runCatching { json.parseToJsonElement(Files.readString(configFile)).jsonObject }.getOrNull()
        return root?.get("client_id")?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private fun clientSecret(): String {
        System.getenv(ENV_CLIENT_SECRET)?.takeIf { it.isNotBlank() }?.let { return it }
        val root = runCatching { json.parseToJsonElement(Files.readString(configFile)).jsonObject }.getOrNull()
        return root?.get("client_secret")?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    fun signIn() {
        scope.launch(Dispatchers.IO) {
            val clientId = clientId()
            if (clientId.isBlank()) {
                _state.value = State.SignedOut("no Google client id: set $ENV_CLIENT_ID or ${configFile}")
                return@launch
            }
            _state.value = State.SigningIn("waiting for the browser")
            val verifier = randomToken(48)
            val challenge = challengeFor(verifier)
            val state = randomToken(16)
            val server = runCatching { HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0) }.getOrNull()
            if (server == null) {
                _state.value = State.SignedOut("could not open the loopback listener")
                return@launch
            }
            val port = server.address.port
            val redirect = "http://127.0.0.1:$port"
            val code = CompletableFuture<String>()

            server.createContext("/") { exchange ->
                val query = parseQuery(exchange.requestURI.rawQuery.orEmpty())
                val returned = query["code"]
                val returnedState = query["state"]
                val error = query["error"]
                val body = when {
                    error != null -> "Sign-in was cancelled. You can close this tab."
                    returned == null -> "Waiting for Google..."
                    returnedState != state -> "State mismatch, so the code was ignored. Try again from the app."
                    else -> "Signal_IntelGo is signed in. You can close this tab."
                }
                val bytes = ("<!doctype html><meta charset=utf-8><title>Signal_IntelGo</title>" +
                    "<body style=\"font:16px system-ui;background:#07101c;color:#e2e8f0;padding:48px\">" +
                    "<h2 style=\"color:#38bdf8\">$body</h2></body>").toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                if (returned != null && returnedState == state) code.complete(returned)
                if (error != null) code.completeExceptionally(IllegalStateException(error))
            }
            server.start()

            val url = buildString {
                append(AUTH_ENDPOINT)
                append("?client_id=").append(URLEncoder.encode(clientId, "UTF-8"))
                append("&redirect_uri=").append(URLEncoder.encode(redirect, "UTF-8"))
                append("&response_type=code")
                append("&scope=").append(URLEncoder.encode(SCOPE, "UTF-8"))
                append("&code_challenge=").append(URLEncoder.encode(challenge, "UTF-8"))
                append("&code_challenge_method=S256")
                append("&state=").append(URLEncoder.encode(state, "UTF-8"))
                append("&access_type=offline")
                append("&prompt=consent")
            }
            val opened = runCatching { Desktop.getDesktop().browse(URI(url)) }.isSuccess
            if (!opened) {
                _state.value = State.SignedOut("could not open a browser; the auth URL is on stderr")
                System.err.println(url)
                server.stop(0)
                return@launch
            }
            val authCode = runCatching { code.get(SIGN_IN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrNull()
            server.stop(0)
            if (authCode == null) {
                _state.value = State.SignedOut("sign-in did not finish in time")
                return@launch
            }
            _state.value = State.SigningIn("exchanging the code")
            val tokens = exchangeCode(authCode, verifier, redirect, clientId)
            if (tokens == null) {
                _state.value = State.SignedOut("Google refused the token exchange")
                return@launch
            }
            persist(tokens)
            val account = tokens.account
            _state.value = if (account == null) {
                State.SignedOut("signed in, but the id token carried no account")
            } else {
                State.SignedIn(account)
            }
        }
    }

    fun signOut() {
        runCatching { Files.deleteIfExists(authFile) }
        _state.value = State.SignedOut("signed out")
    }

    // ---------------- tokens ----------------

    private data class Tokens(
        val accessToken: String,
        val idToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val account: Account?,
    )

    private fun exchangeCode(code: String, verifier: String, redirect: String, clientId: String): Tokens? {
        val form = buildString {
            append("code=").append(URLEncoder.encode(code, "UTF-8"))
            append("&client_id=").append(URLEncoder.encode(clientId, "UTF-8"))
            append("&redirect_uri=").append(URLEncoder.encode(redirect, "UTF-8"))
            append("&grant_type=authorization_code")
            append("&code_verifier=").append(URLEncoder.encode(verifier, "UTF-8"))
            val secret = clientSecret()
            if (secret.isNotBlank()) append("&client_secret=").append(URLEncoder.encode(secret, "UTF-8"))
        }
        return postForm(form)
    }

    private fun refresh(refreshToken: String, clientId: String): Tokens? {
        val form = buildString {
            append("client_id=").append(URLEncoder.encode(clientId, "UTF-8"))
            append("&grant_type=refresh_token")
            append("&refresh_token=").append(URLEncoder.encode(refreshToken, "UTF-8"))
            val secret = clientSecret()
            if (secret.isNotBlank()) append("&client_secret=").append(URLEncoder.encode(secret, "UTF-8"))
        }
        return postForm(form, previousRefresh = refreshToken)
    }

    private fun postForm(form: String, previousRefresh: String = ""): Tokens? {
        val request = HttpRequest.newBuilder(URI(TOKEN_ENDPOINT))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val response = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrNull() ?: return null
        if (response.statusCode() !in 200..299) return null
        val root = runCatching { json.parseToJsonElement(response.body()).jsonObject }.getOrNull() ?: return null
        val access = root.str("access_token") ?: return null
        val idToken = root.str("id_token").orEmpty()
        val refreshToken = root.str("refresh_token").takeUnless { it.isNullOrBlank() } ?: previousRefresh
        val expiresIn = root["expires_in"]?.jsonPrimitive?.intOrNull ?: 3600
        return Tokens(
            accessToken = access,
            idToken = idToken,
            refreshToken = refreshToken.orEmpty(),
            expiresAt = System.currentTimeMillis() + expiresIn * 1000L,
            account = accountFromIdToken(idToken),
        )
    }

    /**
     * Reads the account out of the id token for display.
     *
     * The token arrived over TLS directly from Google's token endpoint in response to our own
     * code, which is the case where the OIDC spec says a client may read the claims without a
     * separate signature check. Nothing here is used to authorise anything.
     */
    internal fun accountFromIdToken(idToken: String): Account? {
        val payload = idToken.split('.').getOrNull(1) ?: return null
        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val root = runCatching { json.parseToJsonElement(decoded).jsonObject }.getOrNull() ?: return null
        val email = root.str("email") ?: return null
        return Account(
            email = email,
            name = root.str("name") ?: email.substringBefore('@'),
            picture = root.str("picture").orEmpty(),
        )
    }

    private fun persist(tokens: Tokens) {
        runCatching {
            Files.createDirectories(home)
            val payload = buildJsonObject {
                put("access_token", tokens.accessToken)
                put("id_token", tokens.idToken)
                put("refresh_token", tokens.refreshToken)
                put("expires_at", tokens.expiresAt)
                put("email", tokens.account?.email.orEmpty())
                put("name", tokens.account?.name.orEmpty())
                put("picture", tokens.account?.picture.orEmpty())
            }
            Files.writeString(authFile, payload.toString())
            // Best effort: the file holds a refresh token, so it should not be world-readable.
            runCatching { Files.setPosixFilePermissions(authFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
        }
    }

    /** Restores a previous session, refreshing it when the access token has aged out. */
    private fun restore() {
        val root = runCatching { json.parseToJsonElement(Files.readString(authFile)).jsonObject }.getOrNull() ?: return
        val email = root.str("email").orEmpty()
        if (email.isBlank()) return
        val account = Account(
            email = email,
            name = root.str("name").orEmpty().ifBlank { email.substringBefore('@') },
            picture = root.str("picture").orEmpty(),
        )
        val expiresAt = root["expires_at"]?.jsonPrimitive?.intOrNull?.toLong() ?: 0L
        val refreshToken = root.str("refresh_token").orEmpty()
        val clientId = clientId()
        if (System.currentTimeMillis() < expiresAt - 60_000L || refreshToken.isBlank() || clientId.isBlank()) {
            _state.value = State.SignedIn(account.copy(expiresAt = expiresAt))
            return
        }
        val refreshed = refresh(refreshToken, clientId)
        if (refreshed != null) {
            persist(refreshed)
            _state.value = State.SignedIn(refreshed.account ?: account)
        } else {
            // A dead refresh token is not a reason to keep claiming the session.
            _state.value = State.SignedOut("session expired - sign in again")
        }
    }

    private fun randomToken(bytes: Int): String {
        val buffer = ByteArray(bytes)
        SecureRandom().nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    private fun parseQuery(raw: String): Map<String, String> =
        raw.split('&').filter { it.contains('=') }.associate { part ->
            val name = part.substringBefore('=')
            val value = java.net.URLDecoder.decode(part.substringAfter('='), "UTF-8")
            name to value
        }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
}
