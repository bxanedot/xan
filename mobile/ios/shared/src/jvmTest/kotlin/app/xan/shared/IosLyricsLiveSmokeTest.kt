package app.xan.shared

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt in with -Pxan.liveIosLyrics=true to check the LRCLIB client against the live service. */
class IosLyricsLiveSmokeTest {
    @Test
    fun fetchesMatchingLyricsFromLrclib() = runBlocking {
        assumeTrue("Set -Pxan.liveIosLyrics=true to enable the network smoke test.", java.lang.Boolean.getBoolean("xan.liveIosLyrics"))
        withTimeout(30_000) {
            IosLyrics.load("liveLyricsTrack", "Never Gonna Give You Up", "Rick Astley", 213)
        }
        val result = IosLyrics.state.value
        assertNotNull("LRCLIB did not provide a matching lyrics document: ${result.error}", result.document)
        assertFalse("The live lyrics document is empty.", result.document?.lines.isNullOrEmpty())
    }
}
