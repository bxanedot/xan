package app.xan.desktop

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run explicitly with -Dxan.liveSmoke=true to exercise the real InnerTube home feed. */
class HomeFeedLiveSmokeTest {
    @Test
    fun innerTubeHomeFeedLoadsAndMapsWithoutFailure() = runBlocking {
        assumeTrue("Set -Dxan.liveSmoke=true to enable the network smoke test.", java.lang.Boolean.getBoolean("xan.liveSmoke"))
        val feed = DesktopBackend.homeFeed()
        assertNotNull(feed)
    }
}
