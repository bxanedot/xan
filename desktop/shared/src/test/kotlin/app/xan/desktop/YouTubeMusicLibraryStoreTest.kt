package app.xan.desktop

import java.nio.file.Files
import java.util.Comparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicLibraryStoreTest {
    @Test
    fun syncReplacesOneChannelSnapshotAtomicallyAndKeepsChannelsSeparate() {
        val directory = Files.createTempDirectory("xan-youtube-library-")
        val database = directory.resolve("catalogue.sqlite3")
        try {
            val store = CatalogStore(database)
            val firstSync = listOf(
                entry("channel-a", "video-1", "song", "Song one", position = 0),
                entry("channel-a", "album-1", "album", "Album one", position = 0),
                entry("channel-a", "artist-1", "artist", "Artist one", position = 0),
                entry("channel-a", "playlist-1", "playlist", "Playlist one", position = 0),
                entry("channel-a", "video-1", "song", "Duplicate row", position = 9),
            )
            store.replaceYouTubeLibrary("channel-a", firstSync, syncedAt = 10L)
            store.replaceYouTubeLibrary("channel-b", listOf(entry("channel-b", "video-b", "song", "Other channel song")), syncedAt = 11L)

            val reopened = CatalogStore(database)
            assertEquals(listOf("Song one"), reopened.youtubeLibrary("channel-a").filter { it.kind == "song" }.map { it.title })
            assertEquals("Other channel song", reopened.youtubeLibrary("channel-b").single().title)
            assertEquals(1, reopened.youtubeLibrarySync("channel-a")?.songCount)
            assertEquals(1, reopened.youtubeLibrarySync("channel-a")?.albumCount)
            assertEquals(10L, reopened.youtubeLibrarySync("channel-a")?.syncedAt)

            reopened.replaceYouTubeLibrary("channel-a", listOf(entry("channel-a", "video-2", "song", "Replacement")), syncedAt = 12L)
            assertEquals(listOf("Replacement"), reopened.youtubeLibrary("channel-a").map { it.title })
            assertEquals("Other channel song", reopened.youtubeLibrary("channel-b").single().title)
            assertEquals(1, reopened.youtubeLibrarySync("channel-a")?.songCount)
            assertEquals(0, reopened.youtubeLibrarySync("channel-a")?.albumCount)
            assertEquals(12L, reopened.youtubeLibrarySync("channel-a")?.syncedAt)

            reopened.replaceYouTubeLibrary("channel-a", emptyList(), syncedAt = 13L)
            assertTrue(reopened.youtubeLibrary("channel-a").isEmpty())
            assertNotNull(reopened.youtubeLibrarySync("channel-a"))
            assertEquals(0, reopened.youtubeLibrarySync("channel-a")?.songCount)
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun invalidReplacementLeavesPreviousSnapshotIntact() {
        val directory = Files.createTempDirectory("xan-youtube-library-invalid-")
        try {
            val store = CatalogStore(directory.resolve("catalogue.sqlite3"))
            val original = entry("channel-a", "video-1", "song", "Existing song")
            store.replaceYouTubeLibrary("channel-a", listOf(original), syncedAt = 1L)

            assertFalse(runCatching {
                store.replaceYouTubeLibrary("channel-a", listOf(entry("channel-b", "video-2", "song", "Wrong owner")), syncedAt = 2L)
            }.isSuccess)
            assertEquals(listOf(original.copy(syncedAt = 1L)), store.youtubeLibrary("channel-a"))
            assertEquals(1L, store.youtubeLibrarySync("channel-a")?.syncedAt)
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun entry(owner: String, id: String, kind: String, title: String, position: Int = 0) = YouTubeLibraryEntry(
        ownerId = owner,
        itemId = id,
        kind = kind,
        title = title,
        artist = "Test artist",
        album = "Test album",
        videoId = id.takeIf { kind == "song" },
        collectionId = id.takeIf { kind != "song" },
        position = position,
        syncedAt = 99L,
    )
}
