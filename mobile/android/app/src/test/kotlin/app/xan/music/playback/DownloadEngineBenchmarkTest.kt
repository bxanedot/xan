package app.xan.music.playback

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Repeatable loopback throughput/integrity check of XAN's production audio data-source wrapper. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadEngineBenchmarkTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `downloads one hundred complete synthetic songs within the four minute target`() = runBlocking {
        val songs = List(SONG_COUNT) { index -> wavSong(index) }
        val requests = AtomicInteger()
        val active = AtomicInteger()
        val peakConcurrency = AtomicInteger()
        val bytesTransferred = AtomicLong()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newFixedThreadPool(16)
            createContext("/songs/") { exchange ->
                val index = exchange.requestURI.path.substringAfterLast('/').toIntOrNull()
                val song = index?.let(songs::getOrNull)
                if (song == null) {
                    exchange.sendResponseHeaders(404, -1)
                    exchange.close()
                    return@createContext
                }
                requests.incrementAndGet()
                exchange.responseHeaders.add("Content-Type", "audio/wav")
                exchange.responseHeaders.add("ETag", "\"song-$index-v1\"")
                exchange.sendResponseHeaders(200, song.size.toLong())
                exchange.responseBody.use { it.write(song) }
            }
            start()
        }
        val httpClient = OkHttpClient.Builder().build()
        val limiter = DownloadBandwidthLimiter(bytesPerSecond = 0L)
        val sourceFactory = RateLimitedDataSource.Factory(
            OkHttpDataSource.Factory(httpClient),
            limiter,
        )
        val transferSlots = Semaphore(CONCURRENCY)
        val transferTimesMs = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val startedAt = System.nanoTime()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}/songs"
            songs.mapIndexed { index, expected ->
                async(Dispatchers.IO) {
                    transferSlots.withPermit {
                        val nowActive = active.incrementAndGet()
                        peakConcurrency.updateAndGet { previous -> maxOf(previous, nowActive) }
                        val transferStarted = System.nanoTime()
                        val source = sourceFactory.createDataSource()
                        try {
                            source.open(DataSpec.Builder().setUri(Uri.parse("$baseUrl/$index")).build())
                            val bytes = ByteArrayOutputStream(expected.size)
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = source.read(buffer, 0, buffer.size)
                                if (count < 0) break
                                if (count > 0) {
                                    bytes.write(buffer, 0, count)
                                    bytesTransferred.addAndGet(count.toLong())
                                }
                            }
                            val actual = bytes.toByteArray()
                            assertArrayEquals("Song $index should match its source bytes.", expected, actual)
                            File(temporary.root, "song-$index.wav").writeBytes(actual)
                            transferTimesMs += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - transferStarted)
                        } finally {
                            source.close()
                            active.decrementAndGet()
                        }
                    }
                }
            }.awaitAll()

            val elapsedNanos = System.nanoTime() - startedAt
            val elapsedSeconds = elapsedNanos / 1_000_000_000.0
            val totalBytes = songs.sumOf { it.size.toLong() }
            val averageBytesPerSecond = totalBytes / elapsedSeconds
            assertEquals("Each song should be fetched once.", SONG_COUNT, requests.get())
            assertEquals(totalBytes, bytesTransferred.get())
            assertEquals("Every completed song should be written to the test library.", SONG_COUNT, temporary.root.listFiles()?.size)
            assertTrue("The audio source should use multiple transfers in parallel.", peakConcurrency.get() > 1)
            assertTrue("The 100-song benchmark must finish within four minutes.", elapsedNanos < TimeUnit.MINUTES.toNanos(4))

            println(
                "AUDIO_DOWNLOAD_BENCHMARK songs=$SONG_COUNT bytes=$totalBytes " +
                    "elapsed_ms=${TimeUnit.NANOSECONDS.toMillis(elapsedNanos)} " +
                    "avg_mib_s=${"%.2f".format(averageBytesPerSecond / (1024.0 * 1024.0))} " +
                    "peak_concurrency=${peakConcurrency.get()} failures=0 retries=0 " +
                    "transfer_ms_min=${transferTimesMs.minOrNull()} " +
                    "transfer_ms_avg=${transferTimesMs.average().toLong()} " +
                    "transfer_ms_max=${transferTimesMs.maxOrNull()} fixture=generated-wav " +
                    "source=loopback path=RateLimitedDataSource+OkHttp",
            )
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
            httpClient.dispatcher.executorService.shutdownNow()
            httpClient.connectionPool.evictAll()
        }
    }

    private fun wavSong(index: Int): ByteArray {
        val dataSize = SONG_BYTES - WAV_HEADER_BYTES
        val sampleRate = 22_050
        val frequency = 220.0 + index * 3.0
        val pcm = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        val sampleCount = dataSize / 2
        repeat(sampleCount) { sample ->
            val value = (kotlin.math.sin(2.0 * Math.PI * frequency * sample / sampleRate) * Short.MAX_VALUE * 0.15).toInt()
            pcm.putShort(value.toShort())
        }
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(SONG_BYTES - 8)
        header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)
        return header.array() + pcm.array()
    }

    private companion object {
        const val SONG_COUNT = 100
        const val SONG_BYTES = 256 * 1024
        const val WAV_HEADER_BYTES = 44
        const val CONCURRENCY = 8
    }
}
