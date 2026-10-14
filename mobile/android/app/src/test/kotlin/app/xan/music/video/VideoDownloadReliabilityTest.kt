package app.xan.music.video

import com.music.innertube.ExternalVideoFormat
import com.music.innertube.ExternalVideoSearchResult
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoDownloadReliabilityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `public video filenames restore their stable XAN media identity`() {
        val mediaId = "${EXTERNAL_VIDEO_MEDIA_ID_PREFIX}0123456789abcdef01234567"
        val token = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mediaId.toByteArray(Charsets.UTF_8))
        assertEquals(mediaId, restoredExternalVideoMediaId("XAN - title [xan-id-$token].mp4"))
        assertNull(restoredExternalVideoMediaId("ordinary-video.mp4"))
        assertNull(restoredExternalVideoMediaId("XAN - title [xan-id-invalid].mp4"))
    }

    @Test fun `fresh signed URLs keep one download identity but different qualities do not`() {
        val source = ExternalVideoSearchResult(0, "YouTube", "video-id", "https://youtube.com/watch?v=video-id", "title", "artist", 10, null)
        val first = format("1080-mp4", "mp4", 1080)
        assertEquals(externalVideoMediaId(source, first), externalVideoMediaId(source, first.copy(url = "https://cdn.example/renewed?sig=new")))
        assertNotEquals(externalVideoMediaId(source, first), externalVideoMediaId(source, first.copy(id = "720", height = 720, resolution = "720p")))
        assertNotEquals(externalVideoMediaId(source, first), externalVideoMediaId(source, first.copy(extension = "webm")))
    }

    @Test fun `expired URL refresh cannot silently change the output container or codec`() {
        val wrong = format("old", "webm", 720).copy(codec = "vp9", audioExtension = "webm", audioCodec = "opus")
        val correct = format("new", "mp4", 720)
        assertEquals(correct, findCompatibleRefreshedVideoFormat(listOf(wrong, correct), "old", 720, "mp4", "m4a", "avc1", "mp4a"))
        assertNull(findCompatibleRefreshedVideoFormat(listOf(wrong), "old", 720, "mp4", "m4a", "avc1", "mp4a"))
    }

    @Test fun `auto adaptive download matches active track rather than highest manifest quality`() {
        val low = ActiveVideoTrack("360", 640, 360, "video/avc", "avc1", 200_000)
        val high = low.copy(id = "1080", width = 1920, height = 1080, bitrate = 3_000_000)
        assertEquals(0, matchingActiveVideoTrack(low, listOf(low, high)))
        assertNull(matchingActiveVideoTrack(low, listOf(high)))
        assertNull(matchingActiveVideoTrack(low.copy(height = -1), listOf(low, high)))
    }

    @Test fun `adaptive manifest takes the segmented downloader route before split streams`() {
        val manifest = format("dash", "mpd", 720).copy(adaptiveManifest = true)
        assertEquals(ExternalVideoDownloadRoute.ADAPTIVE_MANIFEST, externalVideoDownloadRoute(manifest))
        assertEquals(ExternalVideoDownloadRoute.SPLIT_TRACKS, externalVideoDownloadRoute(manifest.copy(adaptiveManifest = false)))
        assertEquals(ExternalVideoDownloadRoute.PROGRESSIVE_FILE, externalVideoDownloadRoute(manifest.copy(videoOnly = false, adaptiveManifest = false)))
    }

    @Test fun `both stream sizes are required for overall progress`() {
        assertEquals(-1L, combinedVideoDownloadSize(10_000, -1))
        assertEquals(-1L, combinedVideoDownloadSize(-1, 5_000))
        assertEquals(15_000L, combinedVideoDownloadSize(10_000, 5_000))
    }

    @Test fun `probe container validation rejects mislabeled outputs`() {
        assertTrue(videoContainerMatches("mp4", "mov,mp4,m4a,3gp,3g2,mj2"))
        assertTrue(videoContainerMatches("webm", "matroska,webm"))
        assertFalse(videoContainerMatches("mp4", "matroska,webm"))
        assertFalse(videoContainerMatches("webm", "mov,mp4,m4a,3gp,3g2,mj2"))
    }

    @Test fun `retry resumes validated partial data and reconstructs exact file`() = runBlocking {
        val bytes = ByteArray(1024) { (it % 251).toByte() }
        val destination = temporary.newFile("video.mp4")
        destination.writeBytes(bytes.copyOfRange(0, 300))
        withServer { server, url ->
            writeResume(destination, url)
            server.createContext("/video") { exchange ->
                assertEquals("bytes=300-", exchange.requestHeaders.getFirst("Range"))
                assertEquals("\"version-1\"", exchange.requestHeaders.getFirst("If-Range"))
                exchange.responseHeaders.add("ETag", "\"version-1\"")
                exchange.responseHeaders.add("Content-Range", "bytes 300-1023/1024")
                exchange.sendResponseHeaders(206, 724)
                exchange.responseBody.use { it.write(bytes, 300, 724) }
            }
            transferVideoStream(client, url, emptyMap(), destination, onProgress = { _, _ -> })
        }
        assertArrayEquals(bytes, destination.readBytes())
    }

    @Test fun `server ignoring range replaces partial bytes instead of appending`() = runBlocking {
        val bytes = "a complete new media file".toByteArray()
        val destination = temporary.newFile("video.mp4").apply { writeText("stale prefix") }
        withServer { server, url ->
            writeResume(destination, url)
            server.createContext("/video") { exchange ->
                exchange.responseHeaders.add("ETag", "\"version-2\"")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            transferVideoStream(client, url, emptyMap(), destination, onProgress = { _, _ -> })
        }
        assertArrayEquals(bytes, destination.readBytes())
    }

    @Test fun `wrong resumed range fails without corrupting existing bytes`() = runBlocking {
        val destination = temporary.newFile("video.mp4").apply { writeText("safe") }
        withServer { server, url ->
            writeResume(destination, url)
            server.createContext("/video") { exchange ->
                exchange.responseHeaders.add("Content-Range", "bytes 2-5/6")
                exchange.sendResponseHeaders(206, 4)
                exchange.responseBody.use { it.write("oops".toByteArray()) }
            }
            try {
                transferVideoStream(client, url, emptyMap(), destination, onProgress = { _, _ -> })
                fail("An incorrect Content-Range must not be accepted")
            } catch (_: IOException) { }
        }
        assertFalse("The invalid partial must be removed so the retry starts from zero", destination.exists())
        assertFalse(File(destination.parentFile, "${destination.name}.resume").exists())
    }

    @Test fun `changed source validator discards partial data instead of appending`() = runBlocking {
        val destination = temporary.newFile("video.mp4").apply { writeText("old prefix") }
        withServer { server, url ->
            writeResume(destination, url)
            server.createContext("/video") { exchange ->
                assertEquals("\"version-1\"", exchange.requestHeaders.getFirst("If-Range"))
                exchange.responseHeaders.add("ETag", "\"version-2\"")
                exchange.responseHeaders.add("Content-Range", "bytes 10-15/16")
                exchange.sendResponseHeaders(206, 6)
                exchange.responseBody.use { it.write("new data".toByteArray().copyOf(6)) }
            }
            try {
                transferVideoStream(client, url, emptyMap(), destination, onProgress = { _, _ -> })
                fail("A resumed response for a changed representation must fail")
            } catch (_: IOException) { }
        }
        assertFalse("Changed-source partial data should be discarded", destination.exists())
        assertFalse(File(destination.parentFile, "${destination.name}.resume").exists())
    }

    @Test fun `truncated body cannot report a successful download`() = runBlocking {
        val destination = temporary.newFile("video.mp4")
        withServer { server, url ->
            server.createContext("/video") { exchange ->
                exchange.sendResponseHeaders(200, 1024)
                runCatching { exchange.responseBody.use { it.write(ByteArray(12)) } }
                exchange.close()
            }
            try {
                transferVideoStream(client, url, emptyMap(), destination, onProgress = { _, _ -> })
                fail("A truncated response must fail")
            } catch (_: IOException) { }
        }
    }

    @Test fun `disabled resume restarts from zero and discards saved partial bytes`() = runBlocking {
        val bytes = "fresh representation".toByteArray()
        val destination = temporary.newFile("video.mp4").apply { writeText("stale prefix") }
        withServer { server, url ->
            writeResume(destination, url)
            server.createContext("/video") { exchange ->
                assertNull(exchange.requestHeaders.getFirst("Range"))
                exchange.responseHeaders.add("ETag", "\"version-2\"")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            transferVideoStream(
                client, url, emptyMap(), destination,
                onProgress = { _, _ -> },
                allowResume = false,
            )
        }
        assertArrayEquals(bytes, destination.readBytes())
        assertFalse(File(destination.parentFile, "${destination.name}.resume").exists())
    }

    @Test fun `temporary HTTP failures retry while permanent client errors do not`() {
        assertTrue(VideoHttpStatusException(429).retryable)
        assertTrue(VideoHttpStatusException(503).retryable)
        assertFalse(VideoHttpStatusException(404).retryable)
        assertFalse(VideoHttpStatusException(403).retryable)
    }

    private suspend fun withServer(block: suspend (HttpServer, String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try { block(server, "http://127.0.0.1:${server.address.port}/video") }
        finally { server.stop(0) }
    }

    private fun writeResume(file: File, url: String) {
        Properties().apply { setProperty("url", url); setProperty("etag", "\"version-1\"") }
            .also { properties -> File(file.parentFile, "${file.name}.resume").outputStream().use { properties.store(it, null) } }
    }

    private fun format(id: String, extension: String, height: Int) = ExternalVideoFormat(
        id, "https://cdn.example/video?sig=old", "video/$extension", extension, "${height}p", height, 1_000,
        videoOnly = true, audioUrl = "https://cdn.example/audio", audioExtension = "m4a", codec = "avc1", audioCodec = "mp4a",
    )

    private val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
}
