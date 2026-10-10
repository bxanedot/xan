package app.xan.shared

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt in with -Pxan.liveIosYouTubeMusicLyrics=true to check the InnerTube next/browse lyrics flow. */
class IosYouTubeMusicLyricsLiveSmokeTest {
    @Test
    fun fetchesLyricsFromYouTubeMusicTrackEndpoint() = runBlocking {
        assumeTrue(
            "Set -Pxan.liveIosYouTubeMusicLyrics=true to enable the network smoke test.",
            java.lang.Boolean.getBoolean("xan.liveIosYouTubeMusicLyrics"),
        )
        val track = withTimeout(30_000) {
            IosMusicSearch.search("Never Gonna Give You Up Rick Astley").firstOrNull()
        }
        assertNotNull("YouTube Music search did not find the smoke-test track.", track)
        val lyrics = withTimeout(30_000) { IosMusicCatalog.lyrics(requireNotNull(track).videoId) }
        assertNotNull("YouTube Music returned no lyrics browse endpoint or lyrics text.", lyrics)
        assertFalse("YouTube Music returned empty lyrics text.", lyrics.isNullOrBlank())
    }
}
