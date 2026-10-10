package app.xan.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class IosLibraryTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSeconds: Int? = null,
    val artworkUrl: String? = null,
    val localFilePath: String? = null,
)

@Serializable
data class IosLibraryPlaylist(
    val id: String,
    val name: String,
    val tracks: List<IosLibraryTrack> = emptyList(),
)

@Serializable
data class IosMusicLibrarySnapshot(
    val likedSongs: List<IosLibraryTrack> = emptyList(),
    val playlists: List<IosLibraryPlaylist> = emptyList(),
    val downloadedTracks: List<IosLibraryTrack> = emptyList(),
    val importedTracks: List<IosLibraryTrack> = emptyList(),
)

internal fun IosMusicSearchTrack.toLibraryTrack() = IosLibraryTrack(
    id = videoId,
    title = title,
    artist = artist,
    album = album,
    durationSeconds = durationSeconds,
    artworkUrl = safeIosArtworkUrl(artworkUrl),
)

internal fun IosLibraryTrack.toSearchTrack() = IosMusicSearchTrack(
    videoId = id,
    title = title,
    artist = artist,
    album = album,
    durationSeconds = durationSeconds,
    artworkUrl = safeIosArtworkUrl(artworkUrl),
)

/** Local, restart-persistent library state. Cloud account synchronization is not implemented yet. */
internal class IosMusicLibraryRepository(
    readSnapshot: () -> String?,
    private val writeSnapshot: (String) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableState = MutableStateFlow(readSnapshot()?.let(::decodeSnapshot) ?: IosMusicLibrarySnapshot())
    val state = mutableState.asStateFlow()

    fun isLiked(trackId: String): Boolean = mutableState.value.likedSongs.any { it.id == trackId }

    fun isDownloaded(trackId: String): Boolean = mutableState.value.downloadedTracks.any { it.id == trackId }

    fun saveDownloadedTrack(track: IosLibraryTrack, localFilePath: String) {
        require(localFilePath.startsWith("/") && localFilePath.length <= MAX_FILE_PATH_LENGTH && '\u0000' !in localFilePath) {
            "The downloaded audio path is invalid."
        }
        val downloaded = track.copy(localFilePath = localFilePath)
        validateTrack(downloaded)
        update { snapshot ->
            snapshot.copy(downloadedTracks = listOf(downloaded) + snapshot.downloadedTracks.filterNot { it.id == downloaded.id })
        }
    }

    fun removeDownloadedTrack(trackId: String): IosLibraryTrack? {
        val removed = mutableState.value.downloadedTracks.firstOrNull { it.id == trackId } ?: return null
        update { snapshot -> snapshot.copy(downloadedTracks = snapshot.downloadedTracks.filterNot { it.id == trackId }) }
        return removed
    }

    fun saveImportedTrack(track: IosLibraryTrack) {
        require(track.localFilePath != null) { "An imported audio track must have a local file." }
        validateTrack(track)
        update { snapshot ->
            snapshot.copy(importedTracks = listOf(track) + snapshot.importedTracks.filterNot { it.id == track.id })
        }
    }

    fun removeImportedTrack(trackId: String): IosLibraryTrack? {
        val removed = mutableState.value.importedTracks.firstOrNull { it.id == trackId } ?: return null
        update { snapshot -> snapshot.copy(importedTracks = snapshot.importedTracks.filterNot { it.id == trackId }) }
        return removed
    }

    fun toggleLiked(track: IosLibraryTrack): Boolean {
        validateTrack(track)
        val wasLiked = isLiked(track.id)
        update { snapshot ->
            snapshot.copy(
                likedSongs = if (wasLiked) snapshot.likedSongs.filterNot { it.id == track.id }
                else listOf(track) + snapshot.likedSongs,
            )
        }
        return !wasLiked
    }

    fun createPlaylist(rawName: String): IosLibraryPlaylist {
        val name = rawName.trim()
        require(name.isNotEmpty()) { "Enter a playlist name." }
        require(name.length <= MAX_PLAYLIST_NAME_LENGTH) { "Playlist names must be 60 characters or fewer." }
        require(mutableState.value.playlists.none { it.name.equals(name, ignoreCase = true) }) {
            "A playlist with this name already exists."
        }

        val usedIds = mutableState.value.playlists.mapTo(mutableSetOf(), IosLibraryPlaylist::id)
        var ordinal = 1
        var id: String
        do {
            id = "ios-playlist-$ordinal"
            ordinal += 1
        } while (id in usedIds)
        val playlist = IosLibraryPlaylist(id = id, name = name)
        update { it.copy(playlists = it.playlists + playlist) }
        return playlist
    }

    fun importSpotifyPlaylist(spotifyId: String, rawName: String, tracks: List<IosLibraryTrack>): IosLibraryPlaylist {
        require(spotifyId.matches(Regex("[A-Za-z0-9]{22}"))) { "This Spotify playlist has an invalid ID." }
        val baseName = rawName.trim().take(MAX_PLAYLIST_NAME_LENGTH).ifBlank { "Spotify playlist" }
        val playlistId = "spotify-$spotifyId"
        val distinctTracks = tracks.distinctBy(IosLibraryTrack::id)
        distinctTracks.forEach(::validateTrack)

        val before = mutableState.value.playlists
        val storedPlaylist = before.firstOrNull { it.id == playlistId }
        var candidateName = baseName
        var suffixIndex = 0
        while (before.any { it.id != playlistId && it.name.equals(candidateName, ignoreCase = true) }) {
            suffixIndex += 1
            val suffix = if (suffixIndex == 1) " (Spotify)" else " (Spotify $suffixIndex)"
            candidateName = (baseName.take((MAX_PLAYLIST_NAME_LENGTH - suffix.length).coerceAtLeast(1)).trimEnd() + suffix)
                .take(MAX_PLAYLIST_NAME_LENGTH)
        }
        val imported = IosLibraryPlaylist(
            id = playlistId,
            name = candidateName,
            tracks = distinctTracks,
        )
        update { snapshot ->
            snapshot.copy(
                playlists = if (storedPlaylist == null) snapshot.playlists + imported
                else snapshot.playlists.map { if (it.id == playlistId) imported else it },
            )
        }
        return imported
    }

    fun renamePlaylist(playlistId: String, rawName: String) {
        val name = rawName.trim()
        require(name.isNotEmpty()) { "Enter a playlist name." }
        require(name.length <= MAX_PLAYLIST_NAME_LENGTH) { "Playlist names must be 60 characters or fewer." }
        require(mutableState.value.playlists.none { it.id != playlistId && it.name.equals(name, ignoreCase = true) }) {
            "A playlist with this name already exists."
        }
        require(mutableState.value.playlists.any { it.id == playlistId }) { "This playlist no longer exists." }
        update { snapshot ->
            snapshot.copy(playlists = snapshot.playlists.map { if (it.id == playlistId) it.copy(name = name) else it })
        }
    }

    fun deletePlaylist(playlistId: String): Boolean {
        if (mutableState.value.playlists.none { it.id == playlistId }) return false
        update { snapshot -> snapshot.copy(playlists = snapshot.playlists.filterNot { it.id == playlistId }) }
        return true
    }

    fun addToPlaylist(playlistId: String, track: IosLibraryTrack): Boolean {
        validateTrack(track)
        val playlist = mutableState.value.playlists.firstOrNull { it.id == playlistId }
            ?: error("This playlist no longer exists.")
        if (playlist.tracks.any { it.id == track.id }) return false
        update { snapshot ->
            snapshot.copy(playlists = snapshot.playlists.map {
                if (it.id == playlistId) it.copy(tracks = it.tracks + track) else it
            })
        }
        return true
    }

    fun removeFromPlaylist(playlistId: String, trackId: String): Boolean {
        val playlist = mutableState.value.playlists.firstOrNull { it.id == playlistId } ?: return false
        if (playlist.tracks.none { it.id == trackId }) return false
        update { snapshot ->
            snapshot.copy(playlists = snapshot.playlists.map {
                if (it.id == playlistId) it.copy(tracks = it.tracks.filterNot { track -> track.id == trackId }) else it
            })
        }
        return true
    }

    private fun update(transform: (IosMusicLibrarySnapshot) -> IosMusicLibrarySnapshot) {
        val next = transform(mutableState.value)
        // Publish only after the platform store confirms the write.
        writeSnapshot(json.encodeToString(next))
        mutableState.value = next
    }

    private fun decodeSnapshot(raw: String): IosMusicLibrarySnapshot =
        runCatching { json.decodeFromString<IosMusicLibrarySnapshot>(raw) }
            .getOrDefault(IosMusicLibrarySnapshot())

    private fun validateTrack(track: IosLibraryTrack) {
        require(track.id.matches(Regex("[A-Za-z0-9_-]{6,80}"))) { "This track has an invalid YouTube ID." }
        require(track.title.isNotBlank() && track.title.length <= MAX_TRACK_TEXT_LENGTH) { "This track has invalid title metadata." }
        require(track.artist.isNotBlank() && track.artist.length <= MAX_TRACK_TEXT_LENGTH) { "This track has invalid artist metadata." }
        require(track.album == null || track.album.length <= MAX_TRACK_TEXT_LENGTH) { "This track has invalid album metadata." }
        require(track.durationSeconds == null || track.durationSeconds >= 0) { "This track has an invalid duration." }
        require(track.localFilePath == null || (track.localFilePath.startsWith("/") && track.localFilePath.length <= MAX_FILE_PATH_LENGTH && '\u0000' !in track.localFilePath)) {
            "This track has an invalid local audio path."
        }
    }

    private companion object {
        const val MAX_PLAYLIST_NAME_LENGTH = 60
        const val MAX_TRACK_TEXT_LENGTH = 300
        const val MAX_FILE_PATH_LENGTH = 4096
    }
}

/** iOS local persistence uses NSUserDefaults; desktop JVM tests use their own preference node. */
object IosMusicLibrary {
    private val repository = IosMusicLibraryRepository(
        readSnapshot = { readIosPreference(PREFERENCE_KEY) },
        writeSnapshot = { writeIosPreference(PREFERENCE_KEY, it) },
    )

    val state = repository.state
    fun isLiked(trackId: String): Boolean = repository.isLiked(trackId)
    fun isDownloaded(trackId: String): Boolean = repository.isDownloaded(trackId)
    fun saveDownloadedTrack(track: IosLibraryTrack, localFilePath: String) = repository.saveDownloadedTrack(track, localFilePath)
    fun removeDownloadedTrack(trackId: String): IosLibraryTrack? = repository.removeDownloadedTrack(trackId)
    fun saveImportedTrack(track: IosLibraryTrack) = repository.saveImportedTrack(track)
    fun registerImportedAudio(
        id: String,
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int,
        localFilePath: String,
    ) = repository.saveImportedTrack(
        IosLibraryTrack(
            id = id,
            title = title,
            artist = artist,
            album = album,
            durationSeconds = durationSeconds.takeIf { it > 0 },
            localFilePath = localFilePath,
        ),
    )
    fun removeImportedTrack(trackId: String): IosLibraryTrack? = repository.removeImportedTrack(trackId)
    fun toggleLiked(track: IosLibraryTrack): Boolean = repository.toggleLiked(track)
    fun createPlaylist(name: String): IosLibraryPlaylist = repository.createPlaylist(name)
    fun importSpotifyPlaylist(spotifyId: String, name: String, tracks: List<IosLibraryTrack>): IosLibraryPlaylist =
        repository.importSpotifyPlaylist(spotifyId, name, tracks)
    fun renamePlaylist(playlistId: String, name: String) = repository.renamePlaylist(playlistId, name)
    fun deletePlaylist(playlistId: String): Boolean = repository.deletePlaylist(playlistId)
    fun addToPlaylist(playlistId: String, track: IosLibraryTrack): Boolean = repository.addToPlaylist(playlistId, track)
    fun removeFromPlaylist(playlistId: String, trackId: String): Boolean = repository.removeFromPlaylist(playlistId, trackId)

    private const val PREFERENCE_KEY = "library.snapshot.v1"
}
