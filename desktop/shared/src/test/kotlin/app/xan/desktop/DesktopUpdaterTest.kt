package app.xan.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class DesktopUpdaterTest {
    @Test
    fun desktopReleaseIsSelectedByPlatformAndInstallerIsVerifiedBeforeItIsSaved() {
        val directory = Files.createTempDirectory("xan-updates-")
        val bytes = "verified windows installer".toByteArray()
        val digest = bytes.sha256()
        lateinit var installerUrl: String
        val commitsPayload = """[{"sha":"abcdef0123456789","html_url":"https://github.com/bxanedot/xan/commit/abcdef0123456789","commit":{"message":"Improve desktop updates\n\nDetails","author":{"date":"2026-10-07T10:00:00Z"}}}]"""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/releases") { exchange ->
                val payload = releaseJson(
                    listOf(
                        release("v0.7.11", listOf(asset("xan-universal.apk", "0".repeat(64), 1, "http://127.0.0.1:${address.port}/apk"))),
                        release("v0.4.10", listOf(
                            asset("XAN-0.4.10-linux-arm64.deb", "0".repeat(64), 1, "http://127.0.0.1:${address.port}/arm"),
                            asset("XAN-0.4.10-windows-x64.msi", digest, bytes.size.toLong(), "http://127.0.0.1:${address.port}/installer"),
                        )),
                        release("v9.0.0", listOf(asset("XAN-9.0.0-windows-x64.msi", digest, bytes.size.toLong(), "http://127.0.0.1:${address.port}/prerelease")), prerelease = true),
                    ),
                )
                val response = payload.toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            createContext("/installer") { exchange ->
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            createContext("/commits") { exchange ->
                val response = commitsPayload.toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }
        installerUrl = "http://127.0.0.1:${server.address.port}/installer"
        try {
            val endpoint = java.net.URI("http://127.0.0.1:${server.address.port}/releases")
            val client = DesktopUpdateClient(endpoint, directory, "0.4.9", DesktopPlatform("Windows x64", "windows", "x64", setOf("msi")))

            val checked = kotlinx.coroutines.runBlocking { client.check() }
            assertEquals("0.7.11", checked.latestRelease?.version)
            val candidate = checked.compatibleUpdate
            assertNotNull(candidate)
            assertEquals("0.4.10", candidate!!.version)
            assertEquals("XAN-0.4.10-windows-x64.msi", candidate.name)
            assertEquals(installerUrl, candidate.downloadUrl)
            val commits = kotlinx.coroutines.runBlocking { client.recentCommits() }
            assertEquals("Improve desktop updates", commits.single().message)
            assertEquals("abcdef0", commits.single().sha.take(7))

            var progressBytes = 0L
            val downloaded = kotlinx.coroutines.runBlocking { client.download(candidate) { received, _ -> progressBytes = received } }
            assertEquals(bytes.toList(), Files.readAllBytes(downloaded).toList())
            assertEquals(bytes.size.toLong(), progressBytes)
            assertEquals(downloaded, client.findVerifiedDownload(candidate))

            Files.write(downloaded, "tampered".toByteArray())
            assertNull("A locally modified installer must not be considered verified.", client.findVerifiedDownload(candidate))
        } finally {
            server.stop(0)
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun androidOnlyReleasesDoNotAppearAsDesktopUpdatesAndClearKeepsUnrelatedFiles() {
        val directory = Files.createTempDirectory("xan-updates-clear-")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
        try {
            val payload = releaseJson(listOf(release("v0.7.11", listOf(asset("xan.apk", "0".repeat(64), 1, "https://github.com/bxanedot/xan/releases/download/v0.7.11/xan.apk")))))
            val endpoint = java.net.URI("http://127.0.0.1:${server.address.port}/releases")
            val client = DesktopUpdateClient(endpoint, directory, "0.4.9", DesktopPlatform("Windows x64", "windows", "x64", setOf("msi")))
            val checked = DesktopUpdateClient.parseReleases(payload, "0.4.9", DesktopPlatform("Windows x64", "windows", "x64", setOf("msi")), endpoint)
            assertEquals("0.7.11", checked.latestRelease?.version)
            assertNull(checked.compatibleUpdate)

            val installer = directory.resolve("XAN-0.4.10.msi")
            val sidecar = directory.resolve("XAN-0.4.10.msi.sha256")
            val unrelated = directory.resolve("keep-me.txt")
            Files.writeString(installer, "installer")
            Files.writeString(sidecar, "fake checksum")
            Files.writeString(unrelated, "keep")
            assertEquals(2, client.clearDownloads())
            assertFalse(Files.exists(installer))
            assertFalse(Files.exists(sidecar))
            assertTrue(Files.exists(unrelated))
        } finally {
            server.stop(0)
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun digestMismatchDiscardsThePartialInstaller() {
        val directory = Files.createTempDirectory("xan-updates-bad-digest-")
        val bytes = "not the expected installer".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/installer") { exchange ->
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            val endpoint = java.net.URI("http://127.0.0.1:${server.address.port}/releases")
            val release = DesktopRelease("v0.4.10", "XAN 0.4.10", "0.4.10", "", "", "")
            val asset = DesktopUpdateAsset("0.4.10", "XAN-0.4.10.msi", "http://127.0.0.1:${server.address.port}/installer", "0".repeat(64), bytes.size.toLong(), release)
            val client = DesktopUpdateClient(endpoint, directory, "0.4.9", DesktopPlatform("Windows x64", "windows", "x64", setOf("msi")))

            val failure = runCatching { kotlinx.coroutines.runBlocking { client.download(asset) } }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("SHA-256 verification failed"))
            assertNull(client.findVerifiedDownload(asset))
            assertEquals(0L, Files.list(directory).use { it.count() })
        } finally {
            server.stop(0)
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun commitParserRejectsLinksOutsideTheXanRepository() {
        val payload = """[
            {"sha":"1234567890abcdef","html_url":"https://github.com/bxanedot/xan/commit/1234567890abcdef","commit":{"message":"Valid change","author":{"date":"2026-10-07T10:00:00Z"}}},
            {"sha":"deadbeef","html_url":"https://example.com/commit/deadbeef","commit":{"message":"Untrusted link","author":{"date":"2026-10-07T10:00:00Z"}}}
        ]"""
        val commits = DesktopUpdateClient.parseRecentCommits(payload)
        assertEquals(1, commits.size)
        assertEquals("Valid change", commits.single().message)
    }

    private fun releaseJson(releases: List<kotlinx.serialization.json.JsonObject>) = buildJsonArray { releases.forEach(::add) }.toString()

    private fun release(tag: String, assets: List<kotlinx.serialization.json.JsonObject>, prerelease: Boolean = false) = buildJsonObject {
        put("tag_name", tag); put("name", "XAN $tag"); put("draft", false); put("prerelease", prerelease)
        put("published_at", "2026-10-07T10:00:00Z"); put("html_url", "https://github.com/bxanedot/xan/releases/tag/$tag")
        put("body", "Release notes for $tag")
        put("assets", buildJsonArray { assets.forEach(::add) })
    }

    private fun asset(name: String, digest: String, size: Long, url: String) = buildJsonObject {
        put("name", name); put("size", size); put("digest", "sha256:$digest"); put("browser_download_url", url)
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
}
