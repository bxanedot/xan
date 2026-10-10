package app.xan.shared

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class IosListenBrainzState(
    val username: String? = null,
    val connected: Boolean = false,
    val enabled: Boolean = false,
    val message: String? = null,
    val error: Boolean = false,
)

internal data class IosListenBrainzTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMillis: Long,
)

private const val LISTENBRAINZ_API_BASE = "https://api.listenbrainz.org/1"
private const val MAX_LISTENBRAINZ_RESPONSE_BYTES = 128 * 1024
private const val MIN_VALID_LISTEN_TIMESTAMP = 1_033_430_400L

/** ListenBrainz token validation, now-playing, and listen submission for iOS. */
object IosListenBrainz {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = newRoomHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tokenStore: IosListenBrainzTokenStore? = null
    private var activeListen: ActiveListen? = null
    private val mutableState = MutableStateFlow(currentState())
    val state = mutableState.asStateFlow()

    private data class ActiveListen(
        var track: IosListenBrainzTrack,
        val startedAtSeconds: Long,
        var lastPositionMillis: Long,
        var listenedMillis: Long = 0L,
        var wasPlaying: Boolean = true,
        var singleSubmitted: Boolean = false,
    )

    fun install(store: IosListenBrainzTokenStore?) {
        tokenStore = store
        refreshState()
    }

    fun refreshState() {
        val previous = mutableState.value
        mutableState.value = currentState().copy(message = previous.message, error = previous.error)
    }

    fun reportFailure(message: String) {
        mutableState.value = currentState().copy(message = message.take(240), error = true)
    }

    suspend fun connect(tokenRaw: String): String {
        val token = tokenRaw.trim()
        require(token.length in 1..512 && token.all { it in '\u0021'..'\u007e' }) {
            "Enter a valid ListenBrainz user token."
        }
        try {
            val username = validateToken(token)
            val store = tokenStore ?: error("iOS Keychain is unavailable. Reopen XAN and try again.")
            check(store.saveListenBrainzToken(token)) { "Keychain could not save the ListenBrainz token." }
            writeIosPreference("listenbrainz.username", username)
            activeListen = null
            mutableState.value = currentState().copy(message = "Connected to ListenBrainz as $username.")
            return username
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            mutableState.value = currentState().copy(message = failure.message ?: "ListenBrainz connection failed.", error = true)
            throw failure
        }
    }

    fun setEnabled(enabled: Boolean) {
        writeIosPreference("listenbrainz.enabled", enabled.toString())
        if (!enabled) activeListen = null
        mutableState.value = currentState().copy(message = if (enabled) "ListenBrainz scrobbling enabled." else "ListenBrainz scrobbling paused.")
    }

    fun disconnect() {
        val store = tokenStore ?: error("iOS Keychain is unavailable. Reopen XAN and try again.")
        check(store.saveListenBrainzToken(null)) { "Keychain could not remove the ListenBrainz token." }
        writeIosPreference("listenbrainz.enabled", "false")
        writeIosPreference("listenbrainz.username", "")
        activeListen = null
        mutableState.value = currentState().copy(message = "ListenBrainz disconnected.")
    }

    /** Called for each native playback-state update; pauses do not count toward listening time. */
    fun onPlaybackStateChanged(previous: IosMusicPlaybackState?, current: IosMusicPlaybackState, album: String? = null) {
        val settings = mutableState.value
        if (!settings.connected || !settings.enabled) {
            activeListen = null
            return
        }

        val previousId = previous?.videoId
        val currentId = current.videoId
        var active = activeListen
        if (active != null && active.track.id == previousId && active.track.id == currentId && previous.isPlaying && current.isPlaying) {
            val delta = current.positionMillis - active.lastPositionMillis
            if (delta in 0L..5_000L) active.listenedMillis += delta
        }
        if (active != null && active.track.id != currentId) {
            submitSingleIfQualified(active)
            active = null
            activeListen = null
        }
        if (currentId == null) return

        val title = current.title?.trim().orEmpty()
        val artist = current.artist?.trim().orEmpty()
        if (title.isBlank() || artist.isBlank()) return
        if (active == null && current.isPlaying) {
            active = ActiveListen(
                track = IosListenBrainzTrack(currentId, title, artist, album?.takeIf(String::isNotBlank), current.durationMillis),
                startedAtSeconds = validTimestamp(),
                lastPositionMillis = current.positionMillis.coerceAtLeast(0L),
            )
            activeListen = active
            submitPlayingNow(active.track, current.positionMillis)
        } else if (active != null && active.track.id == currentId) {
            active.track = active.track.copy(
                title = title,
                artist = artist,
                album = album ?: active.track.album,
                durationMillis = current.durationMillis.takeIf { it > 0L } ?: active.track.durationMillis,
            )
            active.lastPositionMillis = current.positionMillis.coerceAtLeast(0L)
            if (current.isPlaying && !active.wasPlaying) submitPlayingNow(active.track, current.positionMillis)
            active.wasPlaying = current.isPlaying
            submitSingleIfQualified(active)
        }
    }

    private suspend fun validateToken(token: String): String {
        val response = client.get("$LISTENBRAINZ_API_BASE/validate-token") {
            header("Authorization", "Token $token")
            header("User-Agent", "XAN/$XAN_IOS_VERSION (https://github.com/bxanedot/xan)")
        }
        val root = responseObject(response.status.value, response.bodyAsText(), "ListenBrainz token check")
        return parseIosListenBrainzUsername(root)
    }

    private fun submitPlayingNow(track: IosListenBrainzTrack, positionMillis: Long) {
        submit("playing_now", track, null, positionMillis)
    }

    private fun submitSingleIfQualified(active: ActiveListen) {
        val threshold = iosListenBrainzThresholdMillis(active.track.durationMillis) ?: return
        if (active.listenedMillis < threshold || active.singleSubmitted) return
        active.singleSubmitted = true
        submit("single", active.track, active.startedAtSeconds, active.listenedMillis)
    }

    private fun submit(
        listenType: String,
        track: IosListenBrainzTrack,
        listenedAtSeconds: Long?,
        positionMillis: Long,
    ) {
        val token = tokenStore?.loadListenBrainzToken()?.takeIf(String::isNotBlank) ?: return
        val payload = iosListenBrainzPayload(listenType, track, listenedAtSeconds, positionMillis)
        scope.launch {
            try {
                val response = client.post("$LISTENBRAINZ_API_BASE/submit-listens") {
                    header("Authorization", "Token $token")
                    header("User-Agent", "XAN/$XAN_IOS_VERSION (https://github.com/bxanedot/xan)")
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
                responseObject(response.status.value, response.bodyAsText(), "ListenBrainz submission")
                mutableState.value = currentState().copy(
                    message = if (listenType == "single") "Listen submitted to ListenBrainz." else "ListenBrainz now-playing updated.",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutableState.value = currentState().copy(message = failure.message ?: "ListenBrainz submission failed.", error = true)
            }
        }
    }

    private suspend fun responseObject(status: Int, body: String, operation: String): JsonObject {
        require(body.encodeToByteArray().size <= MAX_LISTENBRAINZ_RESPONSE_BYTES) { "$operation returned an oversized response." }
        check(status in 200..299) { "$operation failed (HTTP $status)${body.take(240).takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}" }
        return runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse { error("$operation returned an invalid response.") }
    }

    private fun validTimestamp(): Long {
        val now = IosLastFm.currentUnixTimeSeconds()
        return now.takeIf { it >= MIN_VALID_LISTEN_TIMESTAMP } ?: MIN_VALID_LISTEN_TIMESTAMP
    }

    private fun currentState(): IosListenBrainzState {
        val connected = !tokenStore?.loadListenBrainzToken().isNullOrBlank()
        val enabled = readIosPreference("listenbrainz.enabled")?.toBooleanStrictOrNull() ?: false
        val username = readIosPreference("listenbrainz.username")?.takeIf(String::isNotBlank)
        return IosListenBrainzState(username, connected, enabled)
    }
}

internal fun iosListenBrainzThresholdMillis(durationMillis: Long): Long? {
    if (durationMillis <= 0L) return null
    return minOf(durationMillis / 2L, 240_000L)
}

internal fun parseIosListenBrainzUsername(root: JsonObject): String {
    check(root["valid"]?.jsonPrimitive?.contentOrNull.equals("true", ignoreCase = true)) {
        "ListenBrainz rejected this token. Check it and try again."
    }
    return root["user_name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)?.take(200)
        ?: "ListenBrainz user"
}

internal fun iosListenBrainzPayload(
    listenType: String,
    track: IosListenBrainzTrack,
    listenedAtSeconds: Long?,
    positionMillis: Long,
): String {
    require(listenType == "single" || listenType == "playing_now")
    require(track.title.isNotBlank() && track.artist.isNotBlank())
    val trackMetadata = buildJsonObject {
        put("artist_name", track.artist)
        put("track_name", track.title)
        track.album?.takeIf(String::isNotBlank)?.let { put("release_name", it) }
        putJsonObject("additional_info") {
            if (track.durationMillis > 0L) put("duration_ms", track.durationMillis)
            if (listenType == "playing_now") put("position_ms", positionMillis.coerceAtLeast(0L))
            put("submission_client", "XAN")
            put("submission_client_version", XAN_IOS_VERSION)
        }
    }
    return buildJsonObject {
        put("listen_type", listenType)
        putJsonArray("payload") {
            add(buildJsonObject {
                if (listenType == "single") put("listened_at", listenedAtSeconds?.coerceAtLeast(MIN_VALID_LISTEN_TIMESTAMP) ?: MIN_VALID_LISTEN_TIMESTAMP)
                put("track_metadata", trackMetadata)
            })
        }
    }.toString()
}
