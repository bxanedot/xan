package app.xan.desktop

import com.music.innertube.models.AlbumItem
import com.music.innertube.models.Artist
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.WatchEndpoint
import com.music.innertube.pages.HomePage
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopHomeFeedTest {
    @Test
    fun preservesPlayableAndBrowsableItemKindsInRecommendations() {
        val section = HomePage.Section(
            title = "Made for bxane",
            label = null,
            thumbnail = null,
            endpoint = null,
            items = listOf(
                SongItem("song-1", "Song", listOf(Artist("Singer", "UC1")), thumbnail = "https://img.example/song.jpg"),
                AlbumItem("MPREb_album", "OLAK_album", title = "Album", artists = listOf(Artist("Singer", "UC1")), thumbnail = "https://img.example/album.jpg"),
                ArtistItem("UC1", "Singer", "https://img.example/artist.jpg", shuffleEndpoint = WatchEndpoint(), radioEndpoint = WatchEndpoint()),
                PlaylistItem("VLPL1", "Playlist", Artist("Singer", "UC1"), "12 songs", "https://img.example/playlist.jpg", WatchEndpoint(), WatchEndpoint(), WatchEndpoint()),
            ),
        )
        val emptySection = section.copy(title = "Empty", items = emptyList())

        val shelves = DesktopBackend.homeFeedShelves(listOf(section, emptySection))

        assertEquals(listOf("Made for bxane"), shelves.map { it.first })
        assertEquals(listOf("song", "album", "artist", "playlist"), shelves.single().second.map { it.kind })
        assertEquals(listOf("Song", "Album", "Singer", "Playlist"), shelves.single().second.map { it.title })
        assertEquals("UC1", shelves.single().second[2].collectionId)
    }
}
