package app.xan.music.playback

import com.music.innertube.models.*
import com.music.innertube.pages.*
import java.text.Normalizer
import java.util.Locale

/** Shared artist catalogue collection, separate from download state/queueing. */
suspend fun resolveArtistDownloadSongs(
    page: ArtistPage,
    artistId: String,
    fetchItems: suspend (BrowseEndpoint) -> ArtistItemsPage,
    fetchContinuation: suspend (String) -> ArtistItemsContinuationPage,
    fetchAlbum: suspend (String) -> AlbumPage,
    onSongsDiscovered: suspend (List<SongItem>) -> Unit = {},
): List<SongItem> {
    val songs = mutableListOf<SongItem>()
    val albums = linkedMapOf<String, AlbumItem>()
    val endpoints = mutableSetOf<BrowseEndpoint>()
    val discoveredIds = mutableSetOf<String>()
    val artistName = normalizeArtistName(page.artist.title)

    fun SongItem.belongsToRequestedArtist(): Boolean = artists.any { artist ->
        artist.id == artistId ||
            (artist.id.isNullOrBlank() && normalizeArtistName(artist.name) == artistName)
    }

    suspend fun discover(items: List<YTItem>) {
        val batch = items.filterIsInstance<SongItem>()
            .filter { it.belongsToRequestedArtist() }
            .filter { discoveredIds.add(it.id) }
        songs += batch
        if (batch.isNotEmpty()) onSongsDiscovered(batch)
        items.filterIsInstance<AlbumItem>().forEach { albums[it.browseId] = it }
    }
    for (section in page.sections) {
        if (section.items.none { it is SongItem || it is AlbumItem }) continue
        discover(section.items)
        val endpoint = section.moreEndpoint
        if (endpoint != null && endpoints.add(endpoint)) {
            val first = fetchItems(endpoint)
            discover(first.items)
            var continuation = first.continuation
            val seen = mutableSetOf<String>()
            while (continuation != null) {
                check(seen.add(continuation)) { "Artist catalogue repeated a continuation token" }
                val next = fetchContinuation(continuation)
                discover(next.items)
                continuation = next.continuation
            }
        }
    }
    for (album in albums.values.toList()) {
        val albumArtists = album.artists.orEmpty()
        val albumBelongsToArtist = albumArtists.isEmpty() || albumArtists.any { artist ->
            artist.id == artistId ||
                (artist.id.isNullOrBlank() && normalizeArtistName(artist.name) == artistName)
        }
        if (albumBelongsToArtist) discover(fetchAlbum(album.browseId).songs)
    }
    return songs
}

private fun normalizeArtistName(name: String): String = Normalizer
    .normalize(name, Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "")
    .lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()
