package app.xan.music.playback

import com.music.innertube.models.SongItem

suspend fun collectArtistPlaylistSongs(
    artistIds: List<String>,
    loadArtistSongs: suspend (String) -> List<SongItem>,
): List<SongItem> {
    val songs = linkedMapOf<String, SongItem>()
    for (artistId in artistIds.distinct()) {
        loadArtistSongs(artistId).forEach { songs.putIfAbsent(it.id, it) }
    }
    return songs.values.toList()
}

fun artistDownloadIdsToPause(ids: List<String>, states: Map<String, Int>): List<String> =
    ids.distinct().filter { states[it] != androidx.media3.exoplayer.offline.Download.STATE_COMPLETED &&
        states[it] != androidx.media3.exoplayer.offline.Download.STATE_REMOVING }
