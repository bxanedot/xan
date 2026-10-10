package app.xan.desktop

import com.music.innertube.YouTube
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run with -Dxan.liveSmoke=true to check the external discovery feeds. */
class SearchDiscoveryLiveSmokeTest {
    @Test
    fun appleChartsReturnSongsAndAlbums() = runBlocking {
        assumeTrue("Set -Dxan.liveSmoke=true to enable the network smoke test.", java.lang.Boolean.getBoolean("xan.liveSmoke"))
        val charts = AppleChartsClient.fetch()
        assertTrue("Apple Music returned no songs", charts.songs.isNotEmpty())
        assertTrue("Apple Music returned no albums", charts.albums.isNotEmpty())
    }

    @Test
    fun innerTubeDiscoveryPagesLoad() = runBlocking {
        assumeTrue("Set -Dxan.liveSmoke=true to enable the network smoke test.", java.lang.Boolean.getBoolean("xan.liveSmoke"))
        assertTrue("YouTube Music returned no Explore sections", YouTube.moodAndGenres().getOrThrow().isNotEmpty())
        assertTrue("YouTube Music returned no new releases", YouTube.explore().getOrThrow().newReleaseAlbums.isNotEmpty())
    }
}
