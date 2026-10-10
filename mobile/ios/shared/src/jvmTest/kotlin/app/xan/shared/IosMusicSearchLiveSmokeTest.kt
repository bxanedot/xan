package app.xan.shared

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt in with -Dxan.liveIosSearch=true to check the shared InnerTube client. */
class IosMusicSearchLiveSmokeTest {
    @Test
    fun fetchesSongMetadataFromYouTubeMusic() = runBlocking {
        assumeTrue("Set xan.liveIosSearch=true to enable the network smoke test.", java.lang.Boolean.getBoolean("xan.liveIosSearch"))
        val results = withTimeout(30_000) { IosMusicSearch.search("Daft Punk") }
        assertFalse("The YouTube Music search returned no song results.", results.isEmpty())
        assertFalse("Search results are missing track metadata.", results.any { it.videoId.isBlank() || it.title.isBlank() || it.artist.isBlank() })
    }
}
