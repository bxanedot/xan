package app.xan.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IosMusicHomeTest {
    @Test
    fun parsesAndroidStyleMoodEndpointsAndMusicShelves() {
        val page = parseIosMusicHomePage(
            """
                {
                  "contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {
                    "header": {"chipCloudRenderer": {"chips": [{"chipCloudChipRenderer": {
                      "text": {"runs": [{"text": "Focus"}]},
                      "navigationEndpoint": {"browseEndpoint": {"browseId": "FEmusic_moods_focus", "params": "focus-params"}}
                    }}]}},
                    "contents": [{"musicCarouselShelfRenderer": {
                      "header": {"musicCarouselShelfBasicHeaderRenderer": {"title": {"runs": [{"text": "Made for you"}]}}},
                      "contents": [
                        {"musicTwoRowItemRenderer": {
                          "title": {"runs": [{"text": "Focus Song"}]},
                          "subtitle": {"runs": [{"text": "Song"}, {"text": " · "}, {"text": "Singer", "navigationEndpoint": {"browseEndpoint": {"browseId": "UCartist123"}}}]},
                          "thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [{"url": "https://i.ytimg.com/song.jpg"}]}}},
                          "navigationEndpoint": {"watchEndpoint": {"videoId": "video123"}}
                        }}
                      ]
                    }}]
                  }}}}]}}
                }
            """.trimIndent(),
        )

        assertEquals("Focus", page.chips.single().title)
        assertEquals("FEmusic_moods_focus", page.chips.single().browseId)
        assertEquals("focus-params", page.chips.single().params)
        assertEquals("Made for you", page.sections.single().title)
        val song = assertIs<IosMusicHomeItem.Song>(page.sections.single().items.single())
        assertEquals("video123", song.track.videoId)
        assertEquals("Focus Song", song.track.title)
        assertEquals("Singer", song.track.artist)
        assertEquals("https://i.ytimg.com/song.jpg", song.track.artworkUrl)
    }

    @Test
    fun parsesArtistAlbumAndPlaylistShelvesWithOpenableBrowseIds() {
        val page = parseIosMusicHomePage(
            """
                {
                  "contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {
                    "contents": [{"musicCarouselShelfRenderer": {
                      "header": {"musicCarouselShelfBasicHeaderRenderer": {"title": {"runs": [{"text": "Collections"}]}}},
                      "contents": [
                        {"musicTwoRowItemRenderer": {"title": {"runs": [{"text": "The Artist"}]}, "subtitle": {"runs": [{"text": "Artist"}]}, "navigationEndpoint": {"browseEndpoint": {"browseId": "UCartist123"}}}},
                        {"musicTwoRowItemRenderer": {"title": {"runs": [{"text": "The Album"}]}, "subtitle": {"runs": [{"text": "The Artist"}, {"text": " · "}, {"text": "2025"}]}, "navigationEndpoint": {"browseEndpoint": {"browseId": "MPREb_album123"}}}},
                        {"musicTwoRowItemRenderer": {"title": {"runs": [{"text": "The Playlist"}]}, "subtitle": {"runs": [{"text": "A short description"}]}, "navigationEndpoint": {"browseEndpoint": {"browseId": "VLPLplaylist123"}}}}
                      ]
                    }}]
                  }}}}]}}
                }
            """.trimIndent(),
        )

        val items = page.sections.single().items
        val artist = assertIs<IosMusicHomeItem.Artist>(items[0])
        assertEquals("UCartist123", artist.artist.browseId)
        assertEquals("The Artist", artist.artist.name)
        val album = assertIs<IosMusicHomeItem.Album>(items[1])
        assertEquals("MPREb_album123", album.album.browseId)
        assertEquals(2025, album.album.year)
        val playlist = assertIs<IosMusicHomeItem.Playlist>(items[2])
        assertEquals("VLPLplaylist123", playlist.browseId)
        assertEquals("The Playlist", playlist.title)
        assertEquals("A short description", playlist.subtitle)
    }

    @Test
    fun parsesAndDeduplicatesAccountLibraryArtistsAlbumsAndPlaylists() {
        val library = parseIosMusicAccountLibrary(
            listOf(
                """
                    {
                      "contents": [
                        {"musicTwoRowItemRenderer": {"title": {"runs": [{"text": "The Artist"}]}, "subtitle": {"runs": [{"text": "Artist"}]}, "navigationEndpoint": {"browseEndpoint": {"browseId": "UCartist123"}}}},
                        {"musicTwoRowItemRenderer": {"title": {"runs": [{"text": "The Album"}]}, "subtitle": {"runs": [{"text": "The Artist"}, {"text": " · "}, {"text": "2025"}]}, "navigationEndpoint": {"browseEndpoint": {"browseId": "MPREb_album123"}}}},
                        {"musicTwoRowItemRenderer": {"title": {"runs": [{"text": "The Playlist"}]}, "subtitle": {"runs": [{"text": "Playlist"}]}, "navigationEndpoint": {"browseEndpoint": {"browseId": "VLPLplaylist123"}}}},
                        {"musicTwoRowItemRenderer": {"title": {"runs": [{"text": "The Artist"}]}, "subtitle": {"runs": [{"text": "Artist"}]}, "navigationEndpoint": {"browseEndpoint": {"browseId": "UCartist123"}}}}
                      ]
                    }
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("UCartist123"), library.artists.map(IosMusicArtistResult::browseId))
        assertEquals(listOf("MPREb_album123"), library.albums.map(IosMusicAlbumResult::browseId))
        assertEquals(listOf("VLPLplaylist123"), library.playlists.map(IosMusicHomeItem.Playlist::browseId))
        assertEquals("The Playlist", library.playlists.single().title)
    }
}
