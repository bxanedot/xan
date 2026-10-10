package app.xan.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAccountCodecTest {
    @Test
    fun preservesChannelIdentityAndOptionalProfileFields() {
        val channels = listOf(
            SavedYouTubeChannel("UCPrimary", "Primary", "@primary", "https://img.example/1.jpg"),
            SavedYouTubeChannel("UCBrand", "Brand channel"),
        )

        assertEquals(channels, YouTubeAccountCodec.decode(YouTubeAccountCodec.encode(channels)))
    }

    @Test
    fun dropsInvalidAndDuplicateChannelRows() {
        val serialized = """[
            {"dataSyncId":"UC1","name":"One"},
            {"dataSyncId":"UC1","name":"Duplicate"},
            {"dataSyncId":"","name":"Invalid"},
            {"dataSyncId":"UC2","name":""}
        ]"""

        assertEquals(listOf(SavedYouTubeChannel("UC1", "One")), YouTubeAccountCodec.decode(serialized))
    }

    @Test
    fun malformedStoredDataLoadsAsEmpty() {
        assertTrue(YouTubeAccountCodec.decode("not-json").isEmpty())
    }

    @Test
    fun signedInBrowseDefaultsOnLikeAndroidAndCanBeDisabled() {
        assertTrue(useAccountForBrowse(null))
        assertTrue(useAccountForBrowse("true"))
        assertEquals(false, useAccountForBrowse("false"))
        assertTrue(useAccountForBrowse("invalid"))
    }
}
