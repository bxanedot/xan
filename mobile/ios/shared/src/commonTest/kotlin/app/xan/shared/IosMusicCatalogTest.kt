package app.xan.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosMusicCatalogTest {
    @Test
    fun extractsYouTubeMusicLyricsBrowseEndpointAndDescriptionText() {
        val nextPayload = """
            {"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{
              "watchNextTabbedResultsRenderer":{"tabs":[{}, {"tabRenderer":{"endpoint":{"browseEndpoint":{
                "browseId":"MPLYt_lyrics_page","params":"lyrics-params"
              }}}}]}
            }}}}
        """.trimIndent()
        val endpoint = parseIosYouTubeMusicLyricsEndpoint(nextPayload)

        assertEquals(IosYouTubeMusicLyricsEndpoint("MPLYt_lyrics_page", "lyrics-params"), endpoint)
        assertNull(parseIosYouTubeMusicLyricsEndpoint("{}"))
        assertNull(parseIosYouTubeMusicLyricsEndpoint(nextPayload.replace("MPLYt_lyrics_page", "bad id")))

        val lyricsPayload = """
            {"contents":{"sectionListRenderer":{"contents":[{"musicDescriptionShelfRenderer":{
              "description":{"runs":[{"text":"First line\n"},{"text":"Second line"}]}
            }}]}}}
        """.trimIndent()
        assertEquals("First line\nSecond line", parseIosYouTubeMusicLyrics(lyricsPayload))
        assertNull(parseIosYouTubeMusicLyrics("{}"))
    }

    @Test
    fun artistPageProvidesShortDescriptionArtworkAndPlayableSongs() {
        val page = parseIosMusicArtistPage(
            """
                {
                  "header": {
                    "musicImmersiveHeaderRenderer": {
                      "title": {"runs": [{"text": "Test Artist"}]},
                      "description": {"runs": [{"text": "Artist "}, {"text": "description."}]},
                      "monthlyListenerCount": {"runs": [{"text": "1.2M monthly listeners"}]},
                      "thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [{"url": "https://i.ytimg.com/artist.jpg"}]}}}
                    }
                  },
                  "contents": {
                    "sectionListRenderer": {
                      "contents": [{
                        "musicDescriptionShelfRenderer": {"description": {"runs": [{"text": "Artist "}, {"text": "description."}]}},
                        "musicShelfRenderer": {"contents": [{
                          "musicResponsiveListItemRenderer": {
                            "flexColumns": [
                              {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Test Song"}]}}},
                              {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "3:42"}]}}}
                            ],
                            "playlistItemData": {"videoId": "video123"},
                            "thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [{"url": "https://i.ytimg.com/song.jpg"}]}}}
                          }
                        }]}
                      }]
                    }
                  }
                }
            """.trimIndent(),
            "UCartist123",
        )

        assertEquals("UCartist123", page.browseId)
        assertEquals("Test Artist", page.name)
        assertEquals("Artist description.", page.description)
        assertEquals("1.2M monthly listeners", page.monthlyListeners)
        assertEquals("https://i.ytimg.com/artist.jpg", page.artworkUrl)
        assertEquals("video123", page.songs.single().videoId)
        assertEquals("Test Artist", page.songs.single().artist)
    }

    @Test
    fun albumPageParsesArtistYearSongsAndRejectsUnsafeArtwork() {
        val page = parseIosMusicAlbumPage(
            """
                {
                  "contents": {
                    "twoColumnBrowseResultsRenderer": {
                      "tabs": [{
                        "tabRenderer": {"content": {"sectionListRenderer": {"contents": [
                          {"musicResponsiveHeaderRenderer": {
                            "title": {"runs": [{"text": "Test Album"}]},
                            "straplineTextOne": {"runs": [{"text": "Test Artist"}]},
                            "subtitle": {"runs": [{"text": "2024"}]},
                            "thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [{"url": "http://untrusted.invalid/cover.jpg"}]}}}
                          }},
                          {"musicPlaylistShelfRenderer": {"contents": [{
                            "musicResponsiveListItemRenderer": {
                              "flexColumns": [
                                {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Album Song"}]}}},
                                {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Test Artist"}, {"text": " · "}, {"text": "3:12"}]}}}
                              ],
                              "playlistItemData": {"videoId": "albumSong1"},
                              "thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [{"url": "https://i.ytimg.com/song.jpg"}]}}}
                            }
                          }]}}
                        ]}}}
                      }]
                    }
                  }
                }
            """.trimIndent(),
            "MPREb_album123",
        )

        assertEquals("Test Album", page.name)
        assertEquals("Test Artist", page.artist)
        assertEquals("2024", page.year)
        assertNull(page.artworkUrl)
        assertEquals("albumSong1", page.songs.single().videoId)
    }
}
