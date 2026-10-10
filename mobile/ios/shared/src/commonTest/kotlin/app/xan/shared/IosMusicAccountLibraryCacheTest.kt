package app.xan.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosMusicAccountLibraryCacheTest {
    @Test
    fun restoresCachedCollectionOnlyForItsSelectedChannel() {
        var stored: String? = null
        val cache = IosMusicAccountLibraryCacheRepository(
            read = { stored },
            write = { _, value -> stored = value },
        )
        val library = IosMusicAccountLibrary(
            playlists = listOf(IosMusicHomeItem.Playlist("VLPLplaylist", "PLplaylist", "Saved mix", "12 songs", "https://i.ytimg.com/playlist.jpg")),
            albums = listOf(IosMusicAlbumResult("MPREbalbum", "OLAKalbum", "Saved album", "Artist", 2025, "https://i.ytimg.com/album.jpg")),
            artists = listOf(IosMusicArtistResult("UCartist", "Saved artist", "Artist", "https://i.ytimg.com/artist.jpg")),
        )

        cache.save("channel123||account456", library)

        assertEquals(library, cache.load("channel123||account456"))
        assertNull(cache.load("anotherChannel||account456"))
    }

    @Test
    fun invalidOrUnsafeCachedDataIsIgnored() {
        val cache = IosMusicAccountLibraryCacheRepository(
            read = { "{\"dataSyncId\":null,\"playlists\":[{\"browseId\":\"id\",\"playlistId\":null,\"title\":\"Saved\",\"subtitle\":null,\"artworkUrl\":\"https://evil.example/a.jpg\"}],\"albums\":[],\"artists\":[]}" },
            write = { _, _ -> },
        )

        assertEquals(null, cache.load(null)?.playlists?.single()?.artworkUrl)
        assertNull(IosMusicAccountLibraryCacheRepository({ "not json" }, { _, _ -> }).load(null))
    }
}
