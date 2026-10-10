package app.xan.music.platform.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleasesTest {
    @Test
    fun `atom fallback parses release notes and universal apk link`() {
        val releases = GitHubReleases.parseAtomFeed(feed, includePrereleases = false)

        assertEquals(1, releases.size)
        val release = releases.single()
        assertEquals("v0.9.7", release.tagName)
        assertEquals("XAN v0.9.7", release.title)
        assertEquals("https://github.com/bxanedot/xan/releases/tag/v0.9.7", release.htmlUrl)
        assertEquals("2026-10-09T15:48:47Z", release.publishedAt)
        assertFalse(release.prerelease)
        assertEquals(
            "https://github.com/bxanedot/xan/releases/download/v0.9.7/xan.apk",
            release.assets.single().downloadUrl,
        )
        assertEquals("xan.apk", release.assets.single().name)
        assertTrue(release.body.contains("### Listen Together"))
        assertTrue(release.body.contains("- Room creation & code-based joining."))
    }

    @Test
    fun `atom fallback includes prereleases only when requested`() {
        val stableOnly = GitHubReleases.parseAtomFeed(feed, includePrereleases = false)
        val allReleases = GitHubReleases.parseAtomFeed(feed, includePrereleases = true)

        assertEquals(listOf("v0.9.7"), stableOnly.map(GitHubRelease::tagName))
        assertEquals(listOf("v0.9.7", "v0.10.0-rc1"), allReleases.map(GitHubRelease::tagName))
        assertTrue(allReleases.last().prerelease)
    }

    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <entry>
            <title>XAN v0.9.7</title>
            <updated>2026-10-09T15:48:47Z</updated>
            <link rel="alternate" href="https://github.com/bxanedot/xan/releases/tag/v0.9.7"/>
            <content type="html">&lt;h2&gt;0.9.7&lt;/h2&gt;&lt;h3&gt;Listen Together&lt;/h3&gt;&lt;ul&gt;&lt;li&gt;Room creation &amp;amp; code-based joining.&lt;/li&gt;&lt;/ul&gt;</content>
          </entry>
          <entry>
            <title>v0.10.0-rc1</title>
            <updated>2026-10-10T00:00:00Z</updated>
            <link rel="alternate" href="https://github.com/bxanedot/xan/releases/tag/v0.10.0-rc1"/>
            <content type="html">&lt;h3&gt;Preview&lt;/h3&gt;&lt;p&gt;Testing.&lt;/p&gt;</content>
          </entry>
        </feed>
    """.trimIndent()
}
