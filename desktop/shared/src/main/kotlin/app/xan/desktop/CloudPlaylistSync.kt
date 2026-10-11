package app.xan.desktop

import io.ktor.client.plugins.ResponseException
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Last confirmed cloud state; a local edit must never erase an unseen remote edit. */
@Serializable
internal data class CloudPlaylistSnapshot(val name: String, val videoIds: List<String>) {
    fun encode(): String = Json.encodeToString(this)

    companion object {
        fun decode(value: String?): CloudPlaylistSnapshot? = value?.let {
            runCatching { Json.decodeFromString<CloudPlaylistSnapshot>(it) }.getOrNull()
        }
    }
}

internal enum class CloudPlaylistSyncDecision { IN_SYNC, PUSH_LOCAL, PULL_REMOTE, CONFLICT }

internal fun cloudPlaylistSyncDecision(
    baseline: CloudPlaylistSnapshot?,
    local: CloudPlaylistSnapshot,
    remote: CloudPlaylistSnapshot,
): CloudPlaylistSyncDecision = when {
    local == remote -> CloudPlaylistSyncDecision.IN_SYNC
    baseline == null -> CloudPlaylistSyncDecision.CONFLICT
    remote == baseline -> CloudPlaylistSyncDecision.PUSH_LOCAL
    local == baseline -> CloudPlaylistSyncDecision.PULL_REMOTE
    else -> CloudPlaylistSyncDecision.CONFLICT
}

internal class CloudPlaylistConflict(message: String) : IllegalStateException(message)

/** Retry safe reads and explicitly rejected (429) edits, never an ambiguous mutation. */
internal suspend fun <T> cloudPlaylistRequest(
    readOnly: Boolean = true,
    pause: suspend (Long) -> Unit = { delay(it) },
    request: suspend () -> T,
): T {
    var attempt = 0
    while (true) {
        try {
            return request()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val response = (failure as? ResponseException)?.response
            val status = response?.status?.value
            val retryable = status == 429 || readOnly && (failure is IOException || status in 500..599)
            if (!retryable || ++attempt >= 4) throw failure
            val requestedDelay = response?.headers?.get("Retry-After")?.let(::cloudPlaylistRetryAfterMillis)
            // A long server cooldown belongs in a later user-triggered sync, rather than
            // retrying before Retry-After or keeping this operation open indefinitely.
            if (requestedDelay != null && requestedDelay > 60_000L) throw failure
            pause(requestedDelay ?: (800L shl (attempt - 1)))
        }
    }
}

internal fun cloudPlaylistRetryAfterMillis(value: String, nowMillis: Long = System.currentTimeMillis()): Long? =
    value.toLongOrNull()?.takeIf { it >= 0 }?.let { seconds ->
        if (seconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else seconds * 1_000L
    } ?: runCatching {
        (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis).coerceAtLeast(0)
    }.getOrNull()

/** Re-read around every edit, recording confirmed progress so interrupted sync can resume. */
internal suspend fun verifyCloudPlaylistMutation(
    before: CloudPlaylistSnapshot,
    expected: CloudPlaylistSnapshot?,
    read: suspend () -> CloudPlaylistSnapshot?,
    edit: suspend () -> Unit,
    record: (CloudPlaylistSnapshot?) -> Unit,
) {
    if (read() != before) throw CloudPlaylistConflict("${before.name} changed on another device; cloud edits were stopped.")
    var editFailure: Exception? = null
    try {
        cloudPlaylistRequest(readOnly = false) { edit() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        editFailure = failure
    }
    val actual = read()
    when {
        actual == expected -> record(actual) // An interrupted response may still have committed.
        actual == before && editFailure != null -> throw editFailure
        else -> throw CloudPlaylistConflict("${before.name} changed while syncing; remaining edits were stopped. Refresh its local copy before resolving the conflict.")
    }
}

/** Favorites are durable library data; adding one must not evict another. */
internal fun updatedDesktopFavorites(current: List<MusicTrack>, track: MusicTrack): Pair<List<MusicTrack>, Boolean> {
    val identity = track.videoId ?: track.localPath ?: error("This item cannot be saved to favorites.")
    val existing = current.indexOfFirst { (it.videoId ?: it.localPath) == identity }
    return if (existing < 0) (listOf(track) + current) to true
    else current.toMutableList().apply { removeAt(existing) } to false
}
