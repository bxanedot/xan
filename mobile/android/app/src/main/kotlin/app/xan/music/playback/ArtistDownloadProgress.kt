package app.xan.music.playback

import androidx.media3.exoplayer.offline.Download

data class ArtistDownloadProgress(val completed: Int, val total: Int, val failed: Int)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun artistDownloadProgress(ids: List<String>, states: Map<String, Int>): ArtistDownloadProgress {
    val unique = ids.distinct()
    return ArtistDownloadProgress(
        completed = unique.count { states[it] == Download.STATE_COMPLETED },
        total = unique.size,
        failed = unique.count { states[it] == Download.STATE_FAILED },
    )
}
