package app.xan.music.playback

import com.music.innertube.models.*
import com.music.innertube.pages.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistDownloadResolverTest {
    private fun song(id: String) = SongItem(id, id, listOf(Artist("Artist", "UCartist")), thumbnail = "art")
    private val album = AlbumItem(browseId = "album", playlistId = "playlist", title = "Album", artists = null, thumbnail = "art")
    private val endpoint = BrowseEndpoint("songs")
    private fun page(sections: List<ArtistSection>) = ArtistPage(
        ArtistItem("UCartist", "Artist", null, shuffleEndpoint = null, radioEndpoint = null), sections, null, null)

    @Test fun `collects album songs even when no song preview exists`() = runBlocking {
        val actual = resolveArtistDownloadSongs(page(listOf(ArtistSection("Albums", listOf(album), null))), "UCartist",
            { error("No endpoint") }, { error("No continuation") },
            { AlbumPage(album, listOf(song("deep-cut")), emptyList()) })
        assertEquals(listOf("deep-cut"), actual.map { it.id })
    }

    @Test fun `merges every song section and every page without duplicate ids`() = runBlocking {
        val actual = resolveArtistDownloadSongs(page(listOf(
            ArtistSection("Songs", listOf(song("a")), endpoint),
            ArtistSection("Videos", listOf(song("video")), null),
            ArtistSection("Albums", listOf(album), null))),
            "UCartist",
            { ArtistItemsPage("Songs", listOf(song("a"), song("b")), "next") },
            { ArtistItemsContinuationPage(listOf(song("c")), null) },
            { AlbumPage(album, listOf(song("b"), song("deep-cut")), emptyList()) })
        assertEquals(listOf("a", "b", "c", "video", "deep-cut"), actual.map { it.id })
    }

    @Test(expected = IllegalStateException::class)
    fun `a missing page is an error instead of a successful partial catalogue`() = runBlocking {
        resolveArtistDownloadSongs(page(listOf(ArtistSection("Songs", listOf(song("a")), endpoint))), "UCartist",
            { ArtistItemsPage("Songs", listOf(song("b")), "next") },
            { error("Network failed") }, { error("No album") })
        Unit
    }

    @Test fun `queues each discovery before waiting for the next page and deduplicates batches`() = runBlocking {
        val queued = mutableListOf<String>()
        val actual = resolveArtistDownloadSongs(page(listOf(
            ArtistSection("Songs", listOf(song("a")), endpoint),
            ArtistSection("Albums", listOf(album), null))),
            "UCartist",
            {
                assertEquals(listOf("a"), queued)
                ArtistItemsPage("Songs", listOf(song("a"), song("b")), "next")
            },
            {
                assertEquals(listOf("a", "b"), queued)
                ArtistItemsContinuationPage(listOf(song("b"), song("c")), null)
            },
            {
                assertEquals(listOf("a", "b", "c"), queued)
                AlbumPage(album, listOf(song("c"), song("deep-cut")), emptyList())
            },
            { batch -> queued += batch.map { it.id } })
        assertEquals(listOf("a", "b", "c", "deep-cut"), queued)
        assertEquals(queued, actual.map { it.id })
    }

    @Test fun `already queued discoveries survive a later collection failure`() = runBlocking {
        val queued = mutableListOf<String>()
        try {
            resolveArtistDownloadSongs(page(listOf(ArtistSection("Songs", listOf(song("a")), endpoint))), "UCartist",
                { error("Next page failed") }, { error("No continuation") }, { error("No album") },
                { batch -> queued += batch.map { it.id } })
            error("Expected collection failure")
        } catch (expected: IllegalStateException) {
            assertEquals("Next page failed", expected.message)
        }
        assertEquals(listOf("a"), queued)
    }

    @Test fun `filters compilation album tracks by artist id and name fallback`() = runBlocking {
        val requestedArtistAlbum = album.copy(artists = listOf(Artist("Artist", "UCartist")))
        val unrelatedAlbumSong = SongItem("other", "other", listOf(Artist("Someone Else", "UCother")), thumbnail = "art")
        val matchingNameFallback = SongItem("fallback", "fallback", listOf(Artist(" ARTIST ", null)), thumbnail = "art")
        val actual = resolveArtistDownloadSongs(
            page(listOf(ArtistSection("Albums", listOf(requestedArtistAlbum), null))),
            "UCartist",
            { error("No endpoint") }, { error("No continuation") },
            { AlbumPage(requestedArtistAlbum, listOf(song("by-id"), unrelatedAlbumSong, matchingNameFallback), emptyList()) },
        )
        assertEquals(listOf("by-id", "fallback"), actual.map { it.id })
    }

    @Test fun `skips albums credited to a different artist`() = runBlocking {
        val unrelatedAlbum = album.copy(artists = listOf(Artist("Someone Else", "UCother")))
        val actual = resolveArtistDownloadSongs(
            page(listOf(ArtistSection("Albums", listOf(unrelatedAlbum), null))),
            "UCartist",
            { error("No endpoint") }, { error("No continuation") },
            { error("Unrelated album should not be fetched") },
        )
        assertEquals(emptyList<String>(), actual.map { it.id })
    }
}

