package app.xan.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class IosMusicSearchTest {
    @Test
    fun extractsSongMetadataFromTheInnerTubeSearchResponse() {
        val tracks = parseIosMusicSearchResponse(
            """
                {
                  "contents": {
                    "tabbedSearchResultsRenderer": {
                      "tabs": [{
                        "tabRenderer": {
                          "content": {
                            "sectionListRenderer": {
                              "contents": [{
                                "musicShelfRenderer": {
                                  "contents": [{
                                    "musicResponsiveListItemRenderer": {
                                      "flexColumns": [
                                        {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Test Song"}]}}},
                                        {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Test Artist","navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist"}}},{"text":" · "},{"text":"Test Album"},{"text":" · "},{"text":"3:42"}]}}}
                                      ],
                                      "playlistItemData":{"videoId":"video123"},
                                      "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://images.example/cover.jpg"}]}}}
                                    }
                                  }]
                                }
                              }]
                            }
                          }
                        }
                      }]
                    }
                  }
                }
            """.trimIndent(),
        )

        assertEquals(1, tracks.size)
        assertEquals("video123", tracks.single().videoId)
        assertEquals("Test Song", tracks.single().title)
        assertEquals("Test Artist", tracks.single().artist)
        assertEquals("Test Album", tracks.single().album)
        assertEquals(222, tracks.single().durationSeconds)
        assertEquals("https://images.example/cover.jpg", tracks.single().artworkUrl)
    }

    @Test
    fun ignoresRowsWithoutPlayableVideoIds() {
        val payload = """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"No video id"}]}}}]}}"""
        assertEquals(emptyList(), parseIosMusicSearchResponse(payload))
        assertEquals(emptyList(), parseIosMusicSearchResponse("{}"))
    }

    @Test
    fun extractsPlayableVideoSearchRows() {
        val videos = parseIosMusicSearchResponse(
            """
                {"contents":{"musicShelfRenderer":{"contents":[{
                  "musicResponsiveListItemRenderer":{
                    "flexColumns":[
                      {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Live Session"}]}}},
                      {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Example Artist"},{"text":" · "},{"text":"4:08"}]}}}
                    ],
                    "navigationEndpoint":{"watchEndpoint":{"videoId":"video456"}},
                    "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/live.jpg"}]}}}
                  }
                }]}}}
            """.trimIndent(),
        )

        assertEquals(1, videos.size)
        assertEquals("video456", videos.single().videoId)
        assertEquals("Live Session", videos.single().title)
        assertEquals("Example Artist", videos.single().artist)
        assertEquals(248, videos.single().durationSeconds)
        assertEquals("https://i.ytimg.com/live.jpg", videos.single().artworkUrl)
    }

    @Test
    fun mapsSearchResultsToTheSharedRoomTrackContract() {
        val roomTrack = IosMusicSearchTrack("video123", "Test Song", "Test Artist", "Test Album", 222, "https://i.ytimg.com/cover.jpg")
            .toRoomTrackInfo()

        assertEquals("video123", roomTrack.id)
        assertEquals("Test Song", roomTrack.title)
        assertEquals("Test Artist", roomTrack.artist)
        assertEquals("Test Album", roomTrack.album)
        assertEquals(222_000L, roomTrack.duration)
        assertEquals("https://i.ytimg.com/cover.jpg", roomTrack.thumbnail)
    }

    @Test
    fun extractsArtistAndAlbumSearchMetadata() {
        val artistResults = parseIosMusicArtistResults(
            """
                {"contents":{"musicShelfRenderer":{"contents":[{
                  "musicResponsiveListItemRenderer":{
                    "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Test Artist"}]}}}],
                    "navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist123"}},
                    "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/artist.jpg"}]}}}
                  }
                }]}}}
            """.trimIndent(),
        )
        val albumResults = parseIosMusicAlbumResults(
            """
                {"contents":{"musicShelfRenderer":{"contents":[{
                  "musicResponsiveListItemRenderer":{
                    "flexColumns":[
                      {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Test Album"}]}}},
                      {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Test Artist"},{"text":" · "},{"text":"2024"}]}}}
                    ],
                    "navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_album123"}},
                    "overlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchPlaylistEndpoint":{"playlistId":"OLAKalbum123"}}}}}},
                    "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/album.jpg"}]}}}
                  }
                }]}}}
            """.trimIndent(),
        )

        assertEquals("UCartist123", artistResults.single().browseId)
        assertEquals("Test Artist", artistResults.single().name)
        assertEquals("https://i.ytimg.com/artist.jpg", artistResults.single().artworkUrl)
        assertEquals("MPREb_album123", albumResults.single().browseId)
        assertEquals("OLAKalbum123", albumResults.single().playlistId)
        assertEquals("Test Album", albumResults.single().name)
        assertEquals("Test Artist", albumResults.single().artist)
        assertEquals(2024, albumResults.single().year)
    }

    @Test
    fun extractsOpenableCommunityAndFeaturedPlaylistResults() {
        val playlists = parseIosMusicPlaylistResults(
            """
                {"contents":{"musicShelfRenderer":{"contents":[
                  {"musicResponsiveListItemRenderer":{
                    "flexColumns":[
                      {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Community Mix"}]}}},
                      {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Playlist"},{"text":" · "},{"text":"12 songs"}]}}}
                    ],
                    "navigationEndpoint":{"browseEndpoint":{"browseId":"VLcommunity123","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PLAYLIST"}}}},
                    "overlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchPlaylistEndpoint":{"playlistId":"PLcommunity123"}}}}}},
                    "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/community.jpg"}]}}}
                  }},
                  {"musicResponsiveListItemRenderer":{
                    "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Featured Mix"}]}}}],
                    "navigationEndpoint":{"browseEndpoint":{"browseId":"VLfeatured456"}},
                    "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/featured.jpg"}]}}}
                  }}
                ]}}}
            """.trimIndent(),
        )

        assertEquals(listOf("VLcommunity123", "VLfeatured456"), playlists.map(IosMusicHomeItem.Playlist::browseId))
        assertEquals("PLcommunity123", playlists.first().playlistId)
        assertEquals("Community Mix", playlists.first().title)
        assertEquals("Playlist · 12 songs", playlists.first().subtitle)
        assertEquals("https://i.ytimg.com/community.jpg", playlists.first().artworkUrl)
    }
}
