package app.xan.music.platform.updater

import app.xan.music.platform.github.GitHubRelease
import app.xan.music.platform.github.GitHubReleaseAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateApkSelectorTest {
    @Test
    fun `standard release apk is universal on non arm devices`() {
        val apk = asset("xan.apk")

        val selected = compatibleUpdateApk(release(apk), listOf("x86_64", "x86"))

        assertEquals(apk, selected)
    }

    @Test
    fun `matching architecture variant is preferred over universal apk`() {
        val universal = asset("xan.apk", 10)
        val optimized = asset("xan-x86_64.apk", 20)

        val selected = compatibleUpdateApk(
            release(universal, optimized),
            listOf("x86_64", "x86"),
        )

        assertEquals(optimized, selected)
    }

    @Test
    fun `universal apk is retained when architecture variants do not match`() {
        val universal = asset("xan-universal.apk")
        val arm64 = asset("xan-arm64-v8a.apk", 20)

        val selected = compatibleUpdateApk(release(arm64, universal), listOf("x86_64"))

        assertEquals(universal, selected)
    }

    @Test
    fun `debug and unsigned packages are ignored`() {
        val debug = asset("xan-debug.apk")
        val unsigned = asset("xan-unsigned.apk", 20)

        assertNull(compatibleUpdateApk(release(debug, unsigned), listOf("arm64-v8a")))
    }

    private fun asset(name: String, size: Long = 1) =
        GitHubReleaseAsset(name, size, "https://example.invalid/$name")

    private fun release(vararg assets: GitHubReleaseAsset) = GitHubRelease(
        tagName = "v0.9.0",
        title = "XAN 0.9.0",
        body = "",
        publishedAt = "2026-10-08T00:00:00Z",
        prerelease = false,
        htmlUrl = "https://github.com/bxanedot/xan/releases/tag/v0.9.0",
        assets = assets.toList(),
    )
}
