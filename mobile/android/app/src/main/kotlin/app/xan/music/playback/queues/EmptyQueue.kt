/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.playback.queues

import androidx.media3.common.MediaItem
import app.xan.music.models.MediaMetadata

object EmptyQueue : Queue {
    override val preloadItem: MediaMetadata? = null

    override suspend fun getInitialStatus() = Queue.Status(null, emptyList(), -1)

    override fun hasNextPage() = false

    override suspend fun nextPage() = emptyList<MediaItem>()
}
