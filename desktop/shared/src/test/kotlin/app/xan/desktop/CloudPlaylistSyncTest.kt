package app.xan.desktop

import java.io.IOException
import java.nio.file.Files
import java.util.Comparator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPlaylistSyncTest {
    @Test
    fun mergeDecisionPreservesConflictingEdits() {
        val base = CloudPlaylistSnapshot("Mix", listOf("one", "two"))
        val local = base.copy(videoIds = listOf("two", "one"))
        val remote = base.copy(name = "Road mix")

        assertEquals(CloudPlaylistSyncDecision.IN_SYNC, cloudPlaylistSyncDecision(base, base, base))
        assertEquals(CloudPlaylistSyncDecision.PUSH_LOCAL, cloudPlaylistSyncDecision(base, local, base))
        assertEquals(CloudPlaylistSyncDecision.PULL_REMOTE, cloudPlaylistSyncDecision(base, base, remote))
        assertEquals(CloudPlaylistSyncDecision.CONFLICT, cloudPlaylistSyncDecision(base, local, remote))
        assertEquals(CloudPlaylistSyncDecision.CONFLICT, cloudPlaylistSyncDecision(null, local, base))
    }

    @Test
    fun playlistSnapshotsRoundTripAndMalformedCacheIsIgnored() {
        val snapshot = CloudPlaylistSnapshot("Road \"mix\"", listOf("abc", "def"))
        assertEquals(snapshot, CloudPlaylistSnapshot.decode(snapshot.encode()))
        assertNull(CloudPlaylistSnapshot.decode("not json"))
    }

    @Test
    fun verifiedMutationChecksBeforeAndAfterAndRecordsOnlyExpectedState() = runBlocking {
        val before = CloudPlaylistSnapshot("Mix", listOf("one"))
        val expected = before.copy(videoIds = listOf("one", "two"))
        var actual = before
        var recorded: CloudPlaylistSnapshot? = null

        verifyCloudPlaylistMutation(
            before = before,
            expected = expected,
            read = { actual },
            edit = { actual = expected },
            record = { recorded = it },
        )
        assertEquals(expected, recorded)

        actual = before
        assertThrows(CloudPlaylistConflict::class.java) {
            runBlocking {
                verifyCloudPlaylistMutation(
                    before = before,
                    expected = expected,
                    read = { actual },
                    edit = { actual = before.copy(videoIds = listOf("remote edit")) },
                    record = { recorded = it },
                )
            }
        }
        assertEquals(expected, recorded)
    }

    @Test
    fun safeRetryHonorsRetryAfterAndFavoritesDoNotEvictOlderItems() = runBlocking {
        val delays = mutableListOf<Long>()
        var attempts = 0
        val result = cloudPlaylistRequest(pause = { delays += it }) {
            attempts++
            if (attempts == 1) throw IOException("temporary network failure")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(listOf(800L), delays)

        val existing = (0 until 100).map { favorite("song-$it") }
        val (updated, added) = updatedDesktopFavorites(existing, favorite("song-100"))
        assertTrue(added)
        assertEquals(101, updated.size)
        assertEquals("song-100", updated.first().videoId)
        assertEquals("song-99", updated.last().videoId)
        assertFalse(updatedDesktopFavorites(updated, favorite("song-50")).second)
    }

    @Test
    fun playlistOrderingAndCloudDeletionOutboxSurviveDatabaseReopen() {
        val directory = Files.createTempDirectory("xan-cloud-playlist-")
        try {
            val database = directory.resolve("catalog.sqlite3")
            val store = CatalogStore(database)
            val first = store.createPlaylist("First")
            val second = store.createPlaylist("Second")
            val third = store.createPlaylist("Third")
            assertEquals(listOf("First", "Second", "Third"), store.playlists().map { it.name })
            assertTrue(store.movePlaylist(third.id, 0))
            assertEquals(listOf("Third", "First", "Second"), store.playlists().map { it.name })

            val baseline = CloudPlaylistSnapshot("First", listOf("one", "two"))
            store.linkYoutubePlaylist(first.id, "channel", "remote-playlist")
            store.recordYoutubePlaylistSnapshot(first.id, "channel", baseline)
            store.deletePlaylist(first.id)

            val reopened = CatalogStore(database)
            assertEquals(listOf("Third", "Second"), reopened.playlists().map { it.name })
            val pending = reopened.pendingCloudPlaylistDeletions("channel").single()
            assertEquals("remote-playlist", pending.remoteId)
            assertEquals(baseline, pending.baseline)
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun favorite(videoId: String) = MusicTrack(
        title = videoId,
        artist = "Test artist",
        album = "Test album",
        colors = listOf(XanColors.CardLight, XanColors.Card),
        motif = "♫",
        videoId = videoId,
        source = "YouTube Music",
    )
}
