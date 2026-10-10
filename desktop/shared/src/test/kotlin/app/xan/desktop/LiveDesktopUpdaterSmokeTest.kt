package app.xan.desktop

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URI
import java.nio.file.Files

/** Opt in with -Dxan.liveUpdate=true to exercise the public GitHub Releases and commits APIs. */
class LiveDesktopUpdaterSmokeTest {
    @Test
    fun publicGithubReleaseAndCommitMetadataCanBeRead() = runBlocking {
        assumeTrue("Set -Dxan.liveUpdate=true to enable the network updater smoke test.", java.lang.Boolean.getBoolean("xan.liveUpdate"))
        val directory = Files.createTempDirectory("xan-live-updater-")
        try {
            val client = DesktopUpdateClient(
                URI("https://api.github.com/repos/bxanedot/xan/releases?per_page=100"),
                directory,
                XAN_DESKTOP_VERSION,
                DesktopPlatform.current(),
            )
            val check = client.check()
            assertNotNull("The public XAN releases API returned no stable release.", check.latestRelease)
            check.latestDesktopAsset?.let { asset ->
                assertTrue(asset.name.startsWith("XAN-", ignoreCase = true))
                assertTrue(asset.sha256.matches(Regex("(?i)[a-f0-9]{64}")))
            }
            val commits = client.recentCommits()
            assertTrue("The XAN commits API returned no usable commits.", commits.isNotEmpty())
            assertTrue(commits.all { it.url.startsWith("https://github.com/bxanedot/xan/commit/") })
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
