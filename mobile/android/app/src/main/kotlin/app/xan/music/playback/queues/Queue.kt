/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.playback.queues

import androidx.media3.common.MediaItem
import app.xan.music.extensions.metadata
import app.xan.music.models.MediaMetadata

interface Queue {
    val preloadItem: MediaMetadata?

    suspend fun getInitialStatus(): Status

    fun hasNextPage(): Boolean

    suspend fun nextPage(): List<MediaItem>

    data class Status(
        val title: String?,
        val items: List<MediaItem>,
        val mediaItemIndex: Int,
        val position: Long = 0L,
    ) {
        fun filterPlaybackDomain(video: Boolean): Status {
            val selected = items.getOrNull(mediaItemIndex)?.mediaId
            val filtered = items.filter { (it.metadata?.isExternalVideo == true) == video }
            return copy(items = filtered, mediaItemIndex = filtered.indexOfFirst { it.mediaId == selected }.coerceAtLeast(0))
        }
        private fun withFilteredItems(filtered: List<MediaItem>): Status {
            val selected = items.getOrNull(mediaItemIndex)?.mediaId
            return copy(items = filtered, mediaItemIndex = filtered.indexOfFirst { it.mediaId == selected }.coerceAtLeast(0))
        }
        fun filterExplicit(enabled: Boolean = true) =
            if (enabled) {
                withFilteredItems(items.filterExplicit())
            } else {
                this
            }

        fun filterVideoSongs(disableVideos: Boolean = false) =
            if (disableVideos) {
                withFilteredItems(items.filterVideoSongs(true))
            } else {
                this
            }
    }
}

fun List<MediaItem>.filterExplicit(enabled: Boolean = true) =
    if (enabled) {
        filterNot {
            it.metadata?.explicit == true
        }
    } else {
        this
    }

fun List<MediaItem>.filterVideoSongs(disableVideos: Boolean = false) =
    if (disableVideos) {
        filterNot { it.metadata?.isVideoSong == true && it.metadata?.isExternalVideo != true }
    } else {
        this
    }
