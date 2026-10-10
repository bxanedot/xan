package app.xan.shared

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.client.request.forms.FormDataContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class IosLastFmState(
    val username: String? = null,
    val connected: Boolean = false,
    val scrobblingEnabled: Boolean = false,
    val sendNowPlaying: Boolean = false,
    val message: String? = null,
    val error: Boolean = false,
)

private const val LAST_FM_API_KEY = "api-key"
private const val LAST_FM_API_SECRET = "api-secret"
private const val LAST_FM_SESSION = "session-key"
private const val LAST_FM_PENDING_TOKEN = "pending-auth-token"
private const val LAST_FM_ENDPOINT = "https://ws.audioscrobbler.com/2.0/"
private const val MAX_LAST_FM_RESPONSE_BYTES = 128 * 1024

/** Last.fm authorization and playback submissions for iOS. Secrets are delegated to the iOS Keychain. */
object IosLastFm {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = newRoomHttpClient()
    private var secretStore: IosLastFmSecretStore? = null
    private var lastScrobbledTrackId: String? = null
    private val mutableState = MutableStateFlow(currentState())
    val state = mutableState.asStateFlow()

    fun install(store: IosLastFmSecretStore?) {
        secretStore = store
        refreshState()
    }

    fun refreshState() {
        val previous = mutableState.value
        mutableState.value = currentState().copy(message = previous.message, error = previous.error)
    }

    fun hasPendingAuthorization(): Boolean = !secretStore?.loadLastFmSecret(LAST_FM_PENDING_TOKEN).isNullOrBlank()

    fun currentUnixTimeSeconds(): Long = secretStore?.currentUnixTimeSeconds()?.coerceAtLeast(0L) ?: 0L

    fun reportFailure(message: String) {
        mutableState.value = currentState().copy(message = message.take(240), error = true)
    }

    suspend fun beginAuthorization(apiKeyRaw: String, secretRaw: String): String {
        val apiKey = apiKeyRaw.trim()
        val secret = secretRaw.trim()
        require(apiKey.matches(Regex("[A-Za-z0-9]{8,128}"))) { "Enter a valid Last.fm API key." }
        require(secret.matches(Regex("[A-Za-z0-9]{8,256}"))) { "Enter a valid Last.fm shared secret." }
        val store = secretStore ?: error("iOS Keychain is unavailable. Reopen XAN and try again.")
        check(store.saveLastFmSecret(LAST_FM_API_KEY, apiKey)) { "Keychain could not save the Last.fm API key." }
        if (!store.saveLastFmSecret(LAST_FM_API_SECRET, secret)) {
            store.saveLastFmSecret(LAST_FM_API_KEY, null)
            error("Keychain could not save the Last.fm shared secret.")
        }
        try {
            val token = lastFmApiCall("auth.getToken", apiKey, secret).string("token")
                ?.takeIf { it.length in 1..512 && it.all { c -> c in '\u0021'..'\u007e' } }
                ?: error("Last.fm did not return an authorization token.")
            check(store.saveLastFmSecret(LAST_FM_PENDING_TOKEN, token)) { "Keychain could not save the temporary authorization token." }
            val url = URLBuilder("https://www.last.fm/api/auth/").apply {
                parameters.append("api_key", apiKey)
                parameters.append("token", token)
            }.buildString()
            check(store.openLastFmAuthorizationUrl(url)) { "XAN could not open the Last.fm authorization page." }
            mutableState.value = currentState().copy(message = "Approve XAN in Last.fm, then return here and finish connecting.")
            return token
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            mutableState.value = currentState().copy(message = failure.message ?: "Last.fm authorization could not start.", error = true)
            throw failure
        }
    }

    suspend fun finishAuthorization(): String {
        val store = secretStore ?: error("iOS Keychain is unavailable. Reopen XAN and try again.")
        val token = store.loadLastFmSecret(LAST_FM_PENDING_TOKEN)?.takeIf(String::isNotBlank)
            ?: error("Start Last.fm authorization first, then approve XAN in your browser.")
        val apiKey = store.loadLastFmSecret(LAST_FM_API_KEY) ?: error("The Last.fm API key is missing.")
        val secret = store.loadLastFmSecret(LAST_FM_API_SECRET) ?: error("The Last.fm shared secret is missing.")
        try {
            val session = lastFmApiCall("auth.getSession", apiKey, secret, extra = mapOf("token" to token))
                .objectValue("session") ?: error("Last.fm did not return an account session.")
            val sessionKey = session.string("key")?.takeIf { it.length in 1..512 && it.all { c -> c in '\u0021'..'\u007e' } }
                ?: error("Last.fm returned an invalid session key.")
            val username = session.string("name")?.trim()?.takeIf(String::isNotEmpty)?.take(200)
                ?: error("Last.fm did not return the account name.")
            check(store.saveLastFmSecret(LAST_FM_SESSION, sessionKey)) { "Keychain could not save the Last.fm session." }
            store.saveLastFmSecret(LAST_FM_PENDING_TOKEN, null)
            writeIosPreference("lastfm.username", username)
            lastScrobbledTrackId = null
            mutableState.value = currentState().copy(message = "Connected to Last.fm as $username.")
            return username
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            mutableState.value = currentState().copy(message = failure.message ?: "Last.fm could not finish authorization.", error = true)
            throw failure
        }
    }

    fun setScrobblingEnabled(enabled: Boolean) {
        writeIosPreference("lastfm.scrobblingEnabled", enabled.toString())
        mutableState.value = currentState().copy(message = if (enabled) "Last.fm scrobbling enabled." else "Last.fm scrobbling paused.")
    }

    fun setSendNowPlaying(enabled: Boolean) {
        writeIosPreference("lastfm.sendNowPlaying", enabled.toString())
        mutableState.value = currentState().copy(message = if (enabled) "Last.fm now-playing updates enabled." else "Last.fm now-playing updates paused.")
    }

    suspend fun disconnect() {
        val store = secretStore ?: error("iOS Keychain is unavailable. Reopen XAN and try again.")
        val names = listOf(LAST_FM_API_KEY, LAST_FM_API_SECRET, LAST_FM_SESSION, LAST_FM_PENDING_TOKEN)
        val failed = names.filterNot { store.saveLastFmSecret(it, null) }
        if (failed.isNotEmpty()) error("Keychain could not remove all Last.fm credentials.")
        writeIosPreference("lastfm.username", "")
        lastScrobbledTrackId = null
        mutableState.value = currentState().copy(message = "Last.fm disconnected.")
    }

    fun hasScrobbled(trackId: String): Boolean = lastScrobbledTrackId == trackId

    suspend fun updateNowPlaying(title: String, artist: String, album: String?, durationSeconds: Int?) {
        if (!mutableState.value.connected || !mutableState.value.sendNowPlaying) return
        requireTrackMetadata(title, artist)
        val store = secretStore ?: return
        val apiKey = store.loadLastFmSecret(LAST_FM_API_KEY) ?: return
        val secret = store.loadLastFmSecret(LAST_FM_API_SECRET) ?: return
        val session = store.loadLastFmSecret(LAST_FM_SESSION) ?: return
        lastFmApiCall(
            "track.updateNowPlaying", apiKey, secret, session,
            buildMap {
                put("artist", artist.trim().take(300))
                put("track", title.trim().take(300))
                album?.trim()?.takeIf(String::isNotEmpty)?.let { put("album", it.take(300)) }
                durationSeconds?.takeIf { it > 0 }?.let { put("duration", it.coerceAtMost(86_400).toString()) }
            },
        )
    }

    suspend fun scrobble(trackId: String, title: String, artist: String, album: String?, durationSeconds: Int?, startedAtSeconds: Long) {
        if (!mutableState.value.connected || !mutableState.value.scrobblingEnabled || hasScrobbled(trackId)) return
        requireTrackMetadata(title, artist)
        val store = secretStore ?: return
        val apiKey = store.loadLastFmSecret(LAST_FM_API_KEY) ?: return
        val secret = store.loadLastFmSecret(LAST_FM_API_SECRET) ?: return
        val session = store.loadLastFmSecret(LAST_FM_SESSION) ?: return
        lastFmApiCall(
            "track.scrobble", apiKey, secret, session,
            buildMap {
                put("artist[0]", artist.trim().take(300))
                put("track[0]", title.trim().take(300))
                put("timestamp[0]", startedAtSeconds.coerceAtLeast(0L).toString())
                album?.trim()?.takeIf(String::isNotEmpty)?.let { put("album[0]", it.take(300)) }
                durationSeconds?.takeIf { it > 0 }?.let { put("duration[0]", it.coerceAtMost(86_400).toString()) }
            },
        )
        lastScrobbledTrackId = trackId
        mutableState.value = mutableState.value.copy(message = "Scrobbled $title to Last.fm.", error = false)
    }

    private fun currentState(): IosLastFmState {
        val store = secretStore
        val connected = listOf(LAST_FM_API_KEY, LAST_FM_API_SECRET, LAST_FM_SESSION).all { !store?.loadLastFmSecret(it).isNullOrBlank() }
        val username = readIosPreference("lastfm.username")?.takeIf(String::isNotBlank)
        val enabled = readIosPreference("lastfm.scrobblingEnabled")?.toBooleanStrictOrNull() ?: false
        val nowPlaying = readIosPreference("lastfm.sendNowPlaying")?.toBooleanStrictOrNull() ?: false
        return IosLastFmState(username, connected, enabled, nowPlaying)
    }

    private suspend fun lastFmApiCall(
        method: String,
        apiKey: String,
        secret: String,
        sessionKey: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): JsonObject {
        val params = mutableMapOf("method" to method, "api_key" to apiKey).apply {
            sessionKey?.let { put("sk", it) }
            putAll(extra)
        }
        val signatureInput = lastFmSignatureInput(params, secret)
        val signature = requireNotNull(secretStore).md5Hex(signatureInput).lowercase()
        require(signature.matches(Regex("[0-9a-f]{32}"))) { "The iOS host returned an invalid Last.fm request signature." }
        val response = client.post(LAST_FM_ENDPOINT) {
            header("User-Agent", "XAN/$XAN_IOS_VERSION (https://github.com/bxanedot/xan)")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(FormDataContent(Parameters.build {
                params.forEach { (key, value) -> append(key, value) }
                append("api_sig", signature)
                append("format", "json")
            }))
        }
        val body = response.bodyAsText()
        require(body.encodeToByteArray().size <= MAX_LAST_FM_RESPONSE_BYTES) { "Last.fm returned an oversized response." }
        require(response.status.value in 200..299) { "Last.fm returned HTTP ${response.status.value}." }
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: error("Last.fm returned an invalid response.")
        val error = root["error"].primitiveString()
        if (error != null) {
            val message = root["message"].primitiveString()?.take(240) ?: "Last.fm request failed."
            error("Last.fm error $error: $message")
        }
        return root
    }
}

internal fun lastFmSignatureInput(params: Map<String, String>, secret: String): String =
    params.entries.sortedBy { it.key }.joinToString(separator = "") { (key, value) -> key + value } + secret

internal fun lastFmScrobbleThresholdMillis(durationMillis: Long): Long? {
    if (durationMillis <= 30_000L) return null
    return minOf(durationMillis / 2L, 50_000L).coerceAtLeast(0L)
}

private fun requireTrackMetadata(title: String, artist: String) {
    require(title.isNotBlank() && title.length <= 300) { "The track title is unavailable or too long for Last.fm." }
    require(artist.isNotBlank() && artist.length <= 300) { "The track artist is unavailable or too long for Last.fm." }
}

private fun JsonObject.string(key: String): String? = this[key].primitiveString()
private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonElement?.primitiveString(): String? = runCatching { this?.jsonPrimitive?.contentOrNull }.getOrNull()
