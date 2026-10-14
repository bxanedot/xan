/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.utils

import android.content.Context
import app.xan.music.R
import app.xan.music.constants.PendingArtistSubscriptionSyncKey
import app.xan.music.constants.YtmSyncKey
import app.xan.music.db.MusicDatabase
import app.xan.music.db.entities.ArtistEntity
import app.xan.music.extensions.isSyncEnabled
import app.xan.music.utils.dataStore
import app.xan.music.utils.get
import com.music.innertube.YouTube
import java.time.LocalDateTime
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class ArtistFollowException(
    val reason: Reason,
    cause: Throwable? = null,
) : Exception(cause) {
    enum class Reason {
        CHANNEL_UNAVAILABLE,
        REQUEST_FAILED,
    }
}

/**
 * Toggles the local artist follow first so following always works in XAN.
 * When YouTube Music sync is enabled and an account is connected, mirror the
 * change to that account as well.
 */
suspend fun toggleArtistFollowing(
    context: Context,
    database: MusicDatabase,
    artist: ArtistEntity,
): Boolean {
    val current = withContext(Dispatchers.IO) {
        database.getArtistById(artist.id)
    } ?: artist
    val shouldFollow = current.bookmarkedAt == null
    val now = LocalDateTime.now()

    database.withTransaction {
        val latest = getArtistById(current.id)
        val base = latest ?: current
        val updated = base.copy(
            name = artist.name.ifBlank { base.name },
            thumbnailUrl = artist.thumbnailUrl ?: base.thumbnailUrl,
            channelId = artist.channelId ?: base.channelId,
            bookmarkedAt = if (shouldFollow) base.bookmarkedAt ?: now else null,
            lastUpdateTime = now,
        )
        if (latest == null) insert(updated) else update(updated)
    }

    // Local follows remain useful without a YouTube login or while sync is off.
    // Keep enabled-sync changes queued so a later account sync can apply them.
    val syncRequested = withContext(Dispatchers.IO) { context.dataStore.get(YtmSyncKey, true) }
    if (!syncRequested) return shouldFollow
    rememberPendingArtistSubscription(context, current.id, shouldFollow)
    if (!withContext(Dispatchers.IO) { context.isSyncEnabled() }) return shouldFollow

    try {
        val channelId = current.channelId?.takeIf(String::isNotBlank)
            ?: current.id.takeIf { it.startsWith("UC") }
            ?: YouTube.getChannelId(current.id).takeIf(String::isNotBlank)
            ?: throw ArtistFollowException(ArtistFollowException.Reason.CHANNEL_UNAVAILABLE)

        YouTube.subscribeChannel(channelId, shouldFollow).getOrElse { error ->
            throw ArtistFollowException(ArtistFollowException.Reason.REQUEST_FAILED, error)
        }
        clearPendingArtistSubscription(context, current.id)
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    }

    return shouldFollow
}

private suspend fun rememberPendingArtistSubscription(
    context: Context,
    artistId: String,
    shouldFollow: Boolean,
) {
    context.dataStore.edit { preferences ->
        val current = preferences[PendingArtistSubscriptionSyncKey].orEmpty()
        preferences[PendingArtistSubscriptionSyncKey] = current
            .filterNot { it.substringAfter('|', missingDelimiterValue = "") == artistId }
            .toSet() + "$artistId|${if (shouldFollow) 1 else 0}"
    }
}

private suspend fun clearPendingArtistSubscription(context: Context, artistId: String) {
    context.dataStore.edit { preferences ->
        preferences[PendingArtistSubscriptionSyncKey] = preferences[PendingArtistSubscriptionSyncKey]
            .orEmpty()
            .filterNot { it.substringAfter('|', missingDelimiterValue = "") == artistId }
            .toSet()
    }
}

internal suspend fun pendingArtistSubscriptionChanges(context: Context): Map<String, Boolean> {
    val entries = context.dataStore.data.first()[PendingArtistSubscriptionSyncKey].orEmpty()
    return entries.mapNotNull { entry ->
        val separator = entry.lastIndexOf('|')
        if (separator <= 0) return@mapNotNull null
        val artistId = entry.substring(0, separator)
        val shouldFollow = when (entry.substring(separator + 1)) {
            "1" -> true
            "0" -> false
            else -> return@mapNotNull null
        }
        artistId to shouldFollow
    }.toMap()
}

internal suspend fun syncPendingArtistSubscriptions(
    context: Context,
    database: MusicDatabase,
) = withContext(Dispatchers.IO) {
    if (!context.isSyncEnabled()) return@withContext

    pendingArtistSubscriptionChanges(context).forEach { (artistId, shouldFollow) ->
        try {
            val artist = database.getArtistById(artistId)
            val channelId = artist?.channelId?.takeIf(String::isNotBlank)
                ?: artistId.takeIf { it.startsWith("UC") }
                ?: YouTube.getChannelId(artistId).takeIf(String::isNotBlank)
                ?: throw ArtistFollowException(ArtistFollowException.Reason.CHANNEL_UNAVAILABLE)
            YouTube.subscribeChannel(channelId, shouldFollow).getOrThrow()
            if (artist != null && artist.channelId != channelId) {
                database.update(artist.copy(channelId = channelId))
            }
            clearPendingArtistSubscription(context, artistId)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            timber.log.Timber.w(error, "Could not sync artist subscription for $artistId")
        }
    }
}

fun Context.artistFollowErrorMessage(error: Throwable): String =
    getString(R.string.artist_follow_failed)
