package app.xan.music.playback

import androidx.media3.exoplayer.offline.Download
import com.music.innertube.models.SongItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ArtistPlaylistBuilderTest {
    private fun song(id: String) = SongItem(id = id, title = id, artists = emptyList(), thumbnail = "")

    @Test fun `one artist includes its full available list once`() = runBlocking {
        val songs = collectArtistPlaylistSongs(listOf("artist")) { listOf(song("a"), song("a"), song("b")) }
        assertEquals(listOf("a", "b"), songs.map { it.id })
    }

    @Test fun `multiple artists deduplicate shared songs and repeated artists in stable order`() = runBlocking {
        val calls = mutableListOf<String>()
        val songs = collectArtistPlaylistSongs(listOf("first", "second", "first")) {
            calls += it
            if (it == "first") listOf(song("a"), song("shared")) else listOf(song("shared"), song("b"))
        }
        assertEquals(listOf("first", "second"), calls)
        assertEquals(listOf("a", "shared", "b"), songs.map { it.id })
    }

    @Test fun `failed artist fetch propagates instead of silently creating an incomplete playlist`() = runBlocking {
        try {
            collectArtistPlaylistSongs(listOf("first", "failed")) {
                if (it == "failed") error("catalogue unavailable") else listOf(song("a"))
            }
            fail("Expected collection failure")
        } catch (error: IllegalStateException) {
            assertEquals("catalogue unavailable", error.message)
        }
    }

    @Test fun `stop targets only this artists unfinished downloads and preserves completed files`() {
        assertEquals(listOf("queued", "active", "pending"), artistDownloadIdsToPause(
            listOf("done", "queued", "active", "pending", "queued", "removing"),
            mapOf("done" to Download.STATE_COMPLETED, "queued" to Download.STATE_QUEUED,
                "active" to Download.STATE_DOWNLOADING, "removing" to Download.STATE_REMOVING,
                "other-artist" to Download.STATE_DOWNLOADING)))
    }
}
