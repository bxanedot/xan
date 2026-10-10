package app.xan.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosMusicLibraryTest {
    @Test
    fun likesAndPlaylistEditsPersistAndRejectDuplicateTracks() {
        var stored: String? = null
        val library = IosMusicLibraryRepository(readSnapshot = { stored }, writeSnapshot = { stored = it })
        val track = track()

        assertTrue(library.toggleLiked(track))
        assertTrue(library.isLiked(track.id))
        val playlist = library.createPlaylist("Road trip")
        assertTrue(library.addToPlaylist(playlist.id, track))
        assertFalse(library.addToPlaylist(playlist.id, track))
        library.renamePlaylist(playlist.id, "Evening drive")

        val restored = IosMusicLibraryRepository(readSnapshot = { stored }, writeSnapshot = { stored = it })
        assertEquals(listOf(track), restored.state.value.likedSongs)
        assertEquals("Evening drive", restored.state.value.playlists.single().name)
        assertEquals(listOf(track), restored.state.value.playlists.single().tracks)
        assertFalse(restored.toggleLiked(track))
        assertFalse(restored.isLiked(track.id))
    }

    @Test
    fun playlistNamesAreValidatedAndCaseInsensitiveDuplicatesAreRejected() {
        val library = IosMusicLibraryRepository(readSnapshot = { null }, writeSnapshot = {})
        library.createPlaylist("Favorites")

        assertFailsWith<IllegalArgumentException> { library.createPlaylist(" favorites ") }
        assertFailsWith<IllegalArgumentException> { library.createPlaylist(" ") }
        assertFailsWith<IllegalArgumentException> { library.createPlaylist("x".repeat(61)) }
    }

    @Test
    fun spotifyPlaylistImportsPersistReplaceBySourceAndAvoidNameCollisions() {
        var stored: String? = null
        val library = IosMusicLibraryRepository(readSnapshot = { stored }, writeSnapshot = { stored = it })
        val localPlaylist = library.createPlaylist("Morning Mix")
        val firstTrack = track()
        val imported = library.importSpotifyPlaylist("37i9dQZF1DXcBWIGoYBM5M", "Morning Mix", listOf(firstTrack))

        assertEquals("spotify-37i9dQZF1DXcBWIGoYBM5M", imported.id)
        assertEquals("Morning Mix (Spotify)", imported.name)
        assertEquals(listOf(firstTrack), imported.tracks)
        assertEquals("Morning Mix", localPlaylist.name)

        val restored = IosMusicLibraryRepository(readSnapshot = { stored }, writeSnapshot = { stored = it })
        val refreshed = restored.importSpotifyPlaylist(
            "37i9dQZF1DXcBWIGoYBM5M",
            "Morning Mix",
            listOf(firstTrack, firstTrack.copy(id = "second123", title = "Another Song")),
        )
        assertEquals(2, refreshed.tracks.size)
        assertEquals(2, restored.state.value.playlists.size)
        assertEquals(2, restored.state.value.playlists.first { it.id == imported.id }.tracks.size)
    }

    @Test
    fun failedPersistenceDoesNotPublishAnUnwrittenChange() {
        val library = IosMusicLibraryRepository(
            readSnapshot = { null },
            writeSnapshot = { error("storage unavailable") },
        )

        assertFailsWith<IllegalStateException> { library.toggleLiked(track()) }
        assertTrue(library.state.value.likedSongs.isEmpty())
    }

    @Test
    fun downloadsPersistAndCanBeRemoved() {
        var stored: String? = null
        val library = IosMusicLibraryRepository(readSnapshot = { stored }, writeSnapshot = { stored = it })
        val track = track()
        val path = "/app-support/XAN/Downloads/Test Song-video123.m4a"

        library.saveDownloadedTrack(track, path)
        assertTrue(library.isDownloaded(track.id))
        assertEquals(path, library.state.value.downloadedTracks.single().localFilePath)

        val restored = IosMusicLibraryRepository(readSnapshot = { stored }, writeSnapshot = { stored = it })
        assertEquals(path, restored.state.value.downloadedTracks.single().localFilePath)
        assertEquals(track, restored.removeDownloadedTrack(track.id)?.copy(localFilePath = null))
        assertFalse(restored.isDownloaded(track.id))
    }

    @Test
    fun importedAudioPersistsSeparatelyFromDownloadsAndCanBeRemoved() {
        var stored: String? = null
        val library = IosMusicLibraryRepository(readSnapshot = { stored }, writeSnapshot = { stored = it })
        val imported = track().copy(id = "local-import-123", localFilePath = "/app-support/XAN/ImportedAudio/song.mp3")

        library.saveImportedTrack(imported)
        assertEquals(listOf(imported), library.state.value.importedTracks)
        assertTrue(library.state.value.downloadedTracks.isEmpty())

        val restored = IosMusicLibraryRepository(readSnapshot = { stored }, writeSnapshot = { stored = it })
        assertEquals(listOf(imported), restored.state.value.importedTracks)
        assertEquals(imported, restored.removeImportedTrack(imported.id))
        assertTrue(restored.state.value.importedTracks.isEmpty())
    }

    @Test
    fun importedAudioRequiresAnAbsoluteLocalFile() {
        val library = IosMusicLibraryRepository(readSnapshot = { null }, writeSnapshot = {})

        assertFailsWith<IllegalArgumentException> {
            library.saveImportedTrack(track().copy(id = "local-import-123", localFilePath = "relative/song.mp3"))
        }
        assertTrue(library.state.value.importedTracks.isEmpty())
    }

    @Test
    fun downloadedAudioPathsMustBeAbsoluteAndBounded() {
        val library = IosMusicLibraryRepository(readSnapshot = { null }, writeSnapshot = {})

        assertFailsWith<IllegalArgumentException> { library.saveDownloadedTrack(track(), "relative/file.m4a") }
        assertFailsWith<IllegalArgumentException> { library.saveDownloadedTrack(track(), "/bad\u0000path") }
        assertFailsWith<IllegalArgumentException> { library.saveDownloadedTrack(track(), "/" + "a".repeat(4096)) }
        assertTrue(library.state.value.downloadedTracks.isEmpty())
    }

    @Test
    fun malformedSnapshotStartsEmptyAndCanBeReplacedByAValidWrite() {
        var stored: String? = "not valid json"
        val library = IosMusicLibraryRepository(readSnapshot = { stored }, writeSnapshot = { stored = it })

        assertTrue(library.state.value.playlists.isEmpty())
        val created = library.createPlaylist("Fresh start")
        val restored = IosMusicLibraryRepository(readSnapshot = { stored }, writeSnapshot = { stored = it })
        assertEquals(created, restored.state.value.playlists.single())
    }

    @Test
    fun deleteAndRemoveAreIdempotent() {
        val library = IosMusicLibraryRepository(readSnapshot = { null }, writeSnapshot = {})
        val playlist = library.createPlaylist("Mix")
        library.addToPlaylist(playlist.id, track())

        assertTrue(library.removeFromPlaylist(playlist.id, "video123"))
        assertFalse(library.removeFromPlaylist(playlist.id, "video123"))
        assertTrue(library.deletePlaylist(playlist.id))
        assertFalse(library.deletePlaylist(playlist.id))
    }

    private fun track() = IosLibraryTrack(
        id = "video123",
        title = "Test Song",
        artist = "Test Artist",
        album = "Test Album",
        durationSeconds = 222,
        artworkUrl = "https://i.ytimg.com/cover.jpg",
    )
}
