package app.xan.music.playback

import com.music.innertube.models.response.BrowseResponse
import com.music.innertube.pages.ArtistItemsContinuationPage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistContinuationTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val song = """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist"}]}}}],"playlistItemData":{"videoId":"song-id"},"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.com/art.jpg"}]}}}}}"""

    @Test fun `song shelf continuation retains songs and next page`() {
        val response = json.decodeFromString<BrowseResponse>("""{"responseContext":{},"continuationContents":{"musicShelfContinuation":{"contents":[$song],"continuations":[{"nextContinuationData":{"continuation":"next-page"}}]}}}""")
        val page = ArtistItemsContinuationPage.fromResponse(response)
        assertEquals(listOf("song-id"), page.items.map { it.id })
        assertEquals("next-page", page.continuation)
    }

    @Test fun `playlist shelf accepts embedded continuation token`() {
        val response = json.decodeFromString<BrowseResponse>("""{"responseContext":{},"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[$song,{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"embedded-next"}}}}]}}}""")
        val page = ArtistItemsContinuationPage.fromResponse(response)
        assertEquals(listOf("song-id"), page.items.map { it.id })
        assertEquals("embedded-next", page.continuation)
    }
}

