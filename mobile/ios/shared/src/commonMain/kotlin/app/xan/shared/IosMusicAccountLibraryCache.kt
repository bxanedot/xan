package app.xan.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A small on-device snapshot of the selected YouTube Music channel's collection. */
internal class IosMusicAccountLibraryCacheRepository(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(dataSyncId: String?): IosMusicAccountLibrary? {
        val cached = read(CACHE_KEY)?.let { raw ->
            runCatching { json.decodeFromString<Snapshot>(raw) }.getOrNull()
        } ?: return null
        if (cached.dataSyncId != normalizeIosDataSyncId(dataSyncId)) return null
        if (cached.playlists.size > MAX_ITEMS || cached.albums.size > MAX_ITEMS || cached.artists.size > MAX_ITEMS) return null
        return IosMusicAccountLibrary(
            playlists = cached.playlists.map { it.toModel() },
            albums = cached.albums.map { it.toModel() },
            artists = cached.artists.map { it.toModel() },
        )
    }

    fun save(dataSyncId: String?, library: IosMusicAccountLibrary) {
        val snapshot = Snapshot(
            dataSyncId = normalizeIosDataSyncId(dataSyncId),
            playlists = library.playlists.take(MAX_ITEMS).map(Playlist::from),
            albums = library.albums.take(MAX_ITEMS).map(Album::from),
            artists = library.artists.take(MAX_ITEMS).map(Artist::from),
        )
        write(CACHE_KEY, json.encodeToString(snapshot))
    }

    @Serializable
    private data class Snapshot(
        val dataSyncId: String?,
        val playlists: List<Playlist>,
        val albums: List<Album>,
        val artists: List<Artist>,
    )

    @Serializable
    private data class Playlist(
        val browseId: String,
        val playlistId: String?,
        val title: String,
        val subtitle: String?,
        val artworkUrl: String?,
    ) {
        fun toModel() = IosMusicHomeItem.Playlist(
            browseId = browseId,
            playlistId = playlistId,
            title = title,
            subtitle = subtitle,
            artworkUrl = safeIosArtworkUrl(artworkUrl),
        )

        companion object {
            fun from(value: IosMusicHomeItem.Playlist) = Playlist(
                value.browseId, value.playlistId, value.title, value.subtitle, safeIosArtworkUrl(value.artworkUrl),
            )
        }
    }

    @Serializable
    private data class Album(
        val browseId: String,
        val playlistId: String?,
        val name: String,
        val artist: String?,
        val year: Int?,
        val artworkUrl: String?,
    ) {
        fun toModel() = IosMusicAlbumResult(
            browseId, playlistId, name, artist, year, safeIosArtworkUrl(artworkUrl),
        )

        companion object {
            fun from(value: IosMusicAlbumResult) = Album(
                value.browseId, value.playlistId, value.name, value.artist, value.year, safeIosArtworkUrl(value.artworkUrl),
            )
        }
    }

    @Serializable
    private data class Artist(
        val browseId: String,
        val name: String,
        val subtitle: String?,
        val artworkUrl: String?,
    ) {
        fun toModel() = IosMusicArtistResult(browseId, name, subtitle, safeIosArtworkUrl(artworkUrl))

        companion object {
            fun from(value: IosMusicArtistResult) = Artist(
                value.browseId, value.name, value.subtitle, safeIosArtworkUrl(value.artworkUrl),
            )
        }
    }

    private companion object {
        const val CACHE_KEY = "youtube.account.library.cache.v1"
        const val MAX_ITEMS = 1_000
    }
}

internal object IosMusicAccountLibraryCache {
    private val repository = IosMusicAccountLibraryCacheRepository(
        read = ::readIosPreference,
        write = ::writeIosPreference,
    )

    fun load(dataSyncId: String?): IosMusicAccountLibrary? = repository.load(dataSyncId)
    fun save(dataSyncId: String?, library: IosMusicAccountLibrary) = repository.save(dataSyncId, library)
}
