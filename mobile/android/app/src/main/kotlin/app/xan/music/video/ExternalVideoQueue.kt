package app.xan.music.video

import androidx.media3.common.MediaItem
import app.xan.music.extensions.metadata
import app.xan.music.playback.queues.Queue
import com.music.innertube.ExternalVideoSearchResult
import com.music.innertube.NewPipeExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Video recommendations never use the music radio endpoint. Resolve only the next playable video. */
class ExternalVideoQueue(
    private val title: String,
    private val firstItem: MediaItem,
    recommendations: List<ExternalVideoSearchResult>,
) : Queue {
    private val pending = ArrayDeque(recommendations.distinctBy { it.url })
    private val visited = mutableSetOf(firstItem.metadata?.externalVideoPageUrl)
    private var fallbackSearchAvailable = recommendations.isEmpty() && firstItem.metadata?.externalVideoServiceId == 0
    override val preloadItem = firstItem.metadata

    override suspend fun getInitialStatus() = Queue.Status(title, listOf(firstItem), 0)
    override fun hasNextPage() = pending.isNotEmpty() || fallbackSearchAvailable

    override suspend fun nextPage(): List<MediaItem> = withContext(Dispatchers.IO) {
        if (pending.isEmpty() && fallbackSearchAvailable) {
            fallbackSearchAvailable = false
            val metadata = firstItem.metadata
            val query = metadata?.externalVideoUploader?.takeIf(String::isNotBlank) ?: metadata?.title.orEmpty()
            pending.addAll(com.music.innertube.YouTube.searchExternalYouTubeVideos(query).getOrNull().orEmpty())
        }
        while (pending.isNotEmpty()) {
            val result = pending.removeFirst()
            if (!visited.add(result.url)) continue
            try {
                val info = NewPipeExtractor.getVideoInfo(result).getOrThrow()
                val format = info.autoPlaybackFormat() ?: continue
                val queue = buildExternalVideoQueue(result, info, format)
                return@withContext queue.getInitialStatus().items
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A removed or restricted recommendation does not block the next video.
            }
        }
        emptyList()
    }
}
