package app.xan.desktop

import java.nio.file.Files
import java.util.Comparator
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class CatalogPlaylistEditingTest {
    @Test
    fun playlistTrackAddRemoveAndReorderArePersistentAndDuplicateSafe() {
        val directory = Files.createTempDirectory("xan-playlist-edit-")
        try {
            val store = CatalogStore(directory.resolve("catalog.sqlite3"))
            val playlist = store.createPlaylist("Road trip")
            val one = track("one", "First")
            val two = track("two", "Second")
            val three = track("three", "Third")

            assertTrue(store.addPlaylistTrack(playlist.id, one))
            assertTrue(store.addPlaylistTrack(playlist.id, two))
            assertTrue(store.addPlaylistTrack(playlist.id, three))
            assertFalse(store.addPlaylistTrack(playlist.id, two))
            assertEquals(listOf("one", "two", "three"), store.playlistTracks(playlist.id).map { it.id })

            assertTrue(store.movePlaylistTrack(playlist.id, from = 2, to = 0))
            assertEquals(listOf("three", "one", "two"), CatalogStore(directory.resolve("catalog.sqlite3")).playlistTracks(playlist.id).map { it.id })
            assertFalse(store.movePlaylistTrack(playlist.id, from = 10, to = 0))

            assertTrue(store.removePlaylistTrack(playlist.id, "one"))
            assertFalse(store.removePlaylistTrack(playlist.id, "missing"))
            assertEquals(listOf("three", "two"), store.playlistTracks(playlist.id).map { it.id })
            assertEquals(2, store.playlists().single().trackCount)

            store.updatePlaylistMetadata(playlist.id, "  Road trip 2026  ", "  Songs for the drive  ")
            val reopened = CatalogStore(directory.resolve("catalog.sqlite3"))
            val edited = reopened.playlists().single()
            assertEquals("Road trip 2026", edited.name)
            assertEquals("Songs for the drive", edited.description)
            assertEquals(listOf("three", "two"), reopened.playlistTracks(playlist.id).map { it.id })

            store.updatePlaylistMetadata(playlist.id, "Road trip 2026", "   ")
            assertEquals(null, CatalogStore(directory.resolve("catalog.sqlite3")).playlists().single().description)
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun track(id: String, title: String) = CatalogTrack(
        id = id,
        title = title,
        artist = "Test artist",
        album = "Test album",
        source = "local",
        addedAt = 1,
    )
}
