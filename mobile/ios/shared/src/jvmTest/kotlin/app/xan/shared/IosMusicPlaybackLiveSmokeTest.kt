package app.xan.shared

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt in with -Dxan.liveIosPlayback=true to verify InnerTube returns a direct iOS AAC stream. */
class IosMusicPlaybackLiveSmokeTest {
    @Test
    fun resolvesDirectAacStreamForASearchResult() = runBlocking {
        assumeTrue("Set xan.liveIosPlayback=true to enable the network smoke test.", java.lang.Boolean.getBoolean("xan.liveIosPlayback"))
        val track = withTimeout(30_000) { IosMusicSearch.search("Daft Punk") }.first()
        val stream = withTimeout(30_000) { IosMusicPlayback.resolveStream(track.videoId) }
        assertTrue("Expected an HTTPS Google Video audio URL.", stream.url.startsWith("https://") && stream.url.contains("googlevideo.com"))
        assertTrue("Expected an AAC stream.", stream.mimeType == "audio/mp4")
    }
}
