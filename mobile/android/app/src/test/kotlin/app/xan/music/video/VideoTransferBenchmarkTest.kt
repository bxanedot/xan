package app.xan.music.video

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Local integrity/throughput check for XAN's resumable video transfer function. */
class VideoTransferBenchmarkTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `concurrent video streams finish and match their source bytes`() = runBlocking {
        val videos = List(VIDEO_COUNT) { index ->
            ByteArray(VIDEO_BYTES) { offset -> ((offset * 31 + index * 17) and 0xff).toByte() }
        }
        val requests = AtomicInteger()
        val active = AtomicInteger()
        val peakConcurrency = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newFixedThreadPool(16)
            createContext("/videos/") { exchange ->
                val index = exchange.requestURI.path.substringAfterLast('/').toIntOrNull()
                val video = index?.let(videos::getOrNull)
                if (video == null) {
                    exchange.sendResponseHeaders(404, -1)
                    exchange.close()
                    return@createContext
                }
                requests.incrementAndGet()
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                exchange.responseHeaders.add("ETag", "\"video-$index-v1\"")
                exchange.sendResponseHeaders(200, video.size.toLong())
                exchange.responseBody.use { it.write(video) }
            }
            start()
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val transferSlots = Semaphore(VIDEO_CONCURRENCY)
        val bytesTransferred = AtomicInteger()
        val startedAt = System.nanoTime()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}/videos"
            videos.mapIndexed { index, expected ->
                async(Dispatchers.IO) {
                    transferSlots.withPermit {
                        val nowActive = active.incrementAndGet()
                        peakConcurrency.updateAndGet { previous -> maxOf(previous, nowActive) }
                        val output = File(temporary.root, "video-$index.bin")
                        try {
                            transferVideoStream(
                                client = client,
                                url = "$baseUrl/$index",
                                headers = emptyMap(),
                                destination = output,
                                onProgress = { _, _ -> },
                                onBytesTransferred = { bytesTransferred.addAndGet(it) },
                            )
                            assertEquals("Video $index should match its source bytes.", sha256(expected), sha256(output.readBytes()))
                            assertEquals(expected.size.toLong(), output.length())
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                }
            }.awaitAll()

            val elapsedNanos = System.nanoTime() - startedAt
            val totalBytes = videos.sumOf { it.size.toLong() }
            val mibPerSecond = totalBytes / (elapsedNanos / 1_000_000_000.0) / (1024.0 * 1024.0)
            assertEquals(VIDEO_COUNT, requests.get())
            assertEquals(totalBytes.toInt(), bytesTransferred.get())
            assertTrue("Video streams should use the configured parallel slots.", peakConcurrency.get() > 1)
            assertTrue("The local video fixture benchmark exceeded its four-minute bound.", elapsedNanos < TimeUnit.MINUTES.toNanos(4))

            println(
                "VIDEO_DOWNLOAD_BENCHMARK videos=$VIDEO_COUNT bytes=$totalBytes " +
                    "elapsed_ms=${TimeUnit.NANOSECONDS.toMillis(elapsedNanos)} avg_mib_s=${"%.2f".format(mibPerSecond)} " +
                    "peak_concurrency=${peakConcurrency.get()} failures=0 retries=0 integrity=sha256 " +
                    "fixture=synthetic-video-payload source=loopback",
            )
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(data)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val VIDEO_COUNT = 20
        const val VIDEO_BYTES = 2 * 1024 * 1024
        const val VIDEO_CONCURRENCY = 8
    }
}
