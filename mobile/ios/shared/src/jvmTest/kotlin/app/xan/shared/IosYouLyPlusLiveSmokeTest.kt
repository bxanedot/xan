package app.xan.shared

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt in with -Pxan.liveYouLyPlus=true to check the LyricsPlus mirror pool. */
class IosYouLyPlusLiveSmokeTest {
    @Test
    fun fetchesWordSyncedLyricsFromLyricsPlus() = runBlocking {
        assumeTrue("Set -Pxan.liveYouLyPlus=true to enable the network smoke test.", java.lang.Boolean.getBoolean("xan.liveYouLyPlus"))
        val result = withTimeout(30_000) {
            IosLyrics.fetchYouLyPlus("liveLyricsTrack", "Never Gonna Give You Up", "Rick Astley", 213, "Whenever You Need Somebody")
        }
        assertNotNull("LyricsPlus did not return a matching lyric document.", result)
        assertFalse("LyricsPlus returned an empty lyric document.", result?.lines.isNullOrEmpty())
        assertFalse("LyricsPlus returned no word timing.", result?.lines?.none { it.words.isNotEmpty() } ?: true)
    }
}
