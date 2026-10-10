package app.xan.music.playback

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.EOFException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class StreamRangeDataSourceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val bytes = ByteArray(23) { it.toByte() }
    private val request = DataSpec.Builder().setUri("https://media.example/audio").setKey("song").build()

    @Test
    fun `bounded requests expose the complete file across chunk boundaries`() {
        val source = RecordingSource(bytes)
        val stream = StreamRangeDataSource(source)
        val spec = request.buildUpon().setUriPositionOffset(100)
            .setCustomData(StreamRangePolicy(5, bytes.size.toLong())).build()

        assertEquals(23L, stream.open(spec))
        assertArrayEquals(bytes, stream.readAll())
        stream.close()
        assertEquals(listOf(0L, 5L, 10L, 15L, 20L), source.requests.map { it.position })
        assertEquals(listOf(5L, 5L, 5L, 5L, 3L), source.requests.map { it.length })
        assertEquals(listOf(100L, 100L, 100L, 100L, 100L), source.requests.map { it.uriPositionOffset })
    }

    @Test
    fun `resume keeps position and stops at the caller requested length`() {
        val source = RecordingSource(bytes)
        val stream = StreamRangeDataSource(source)
        val spec = request.buildUpon().setPosition(7).setLength(9)
            .setCustomData(StreamRangePolicy(5, bytes.size.toLong())).build()

        assertEquals(9L, stream.open(spec))
        assertArrayEquals(bytes.copyOfRange(7, 16), stream.readAll())
        stream.close()
        assertEquals(listOf(7L, 12L), source.requests.map { it.position })
        assertEquals(listOf(5L, 4L), source.requests.map { it.length })
    }

    @Test
    fun `unknown length reads through a short final chunk`() {
        val source = RecordingSource(bytes)
        val stream = StreamRangeDataSource(source)
        assertEquals(C.LENGTH_UNSET.toLong(), stream.open(request.buildUpon()
            .setCustomData(StreamRangePolicy(5, null)).build()))
        assertArrayEquals(bytes, stream.readAll())
        stream.close()
        assertEquals(5, source.requests.size)
    }

    @Test(expected = EOFException::class)
    fun `truncated advertised file fails instead of reporting a completed download`() {
        val stream = StreamRangeDataSource(RecordingSource(bytes))
        try {
            stream.open(request.buildUpon().setCustomData(StreamRangePolicy(5, 30)).build())
            stream.readAll()
        } finally {
            stream.close()
        }
    }

    @Test
    fun `fresh and cached stream resolution retain source headers and byte coordinates`() {
        val cached = CachedResolvedStream(
            url = "https://media.example/real-audio",
            expiresAtMillis = Long.MAX_VALUE,
            requestHeaders = mapOf("user-agent" to "extractor", "Referer" to "https://youtube.com/"),
            rangeLengthBytes = 5,
            contentLengthBytes = 23,
        )
        val resolved = cached.resolve(request.buildUpon().setPosition(7).setLength(9).setUriPositionOffset(100)
            .setHttpRequestHeaders(mapOf("User-Agent" to "stale", "Range" to "bytes=0-1")).build())
        assertEquals(7L, resolved.position)
        assertEquals(9L, resolved.length)
        assertEquals(100L, resolved.uriPositionOffset)
        assertEquals("extractor", resolved.httpRequestHeaders["user-agent"])
        assertFalse(resolved.httpRequestHeaders.keys.any { it.equals("Range", true) })
        assertEquals(StreamRangePolicy(5, 23), resolved.customData)
    }

    @Test
    fun `partial cache resolves the missing bytes and complete cache works without resolving`() {
        val cache = SimpleCache(temporaryFolder.newFolder(), NoOpCacheEvictor(),
            StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()))
        try {
            val original = request.buildUpon().setUri("song").build()
            val preload = CacheDataSource.Factory().setCache(cache)
                .setUpstreamDataSourceFactory { RecordingSource(bytes) }.createDataSource()
            CacheWriter(preload, original.buildUpon().setLength(4).build(), null, null).cache()

            val opened = mutableListOf<DataSpec>()
            var resolutions = 0
            val network = DataSource.Factory { RecordingSource(bytes, opened) }
            val resolving = ResolvingDataSource.Factory(network) { spec ->
                resolutions++
                spec.withUri(Uri.parse("https://media.example/resolved"))
            }
            val factory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(resolving)
            factory.createDataSource().let { source ->
                source.open(original)
                assertArrayEquals(bytes, source.readAll())
                source.close()
            }
            assertEquals(1, resolutions)
            assertEquals(4L, opened.single().position)
            assertEquals("https", opened.single().uri.scheme)

            factory.createDataSource().let { source ->
                source.open(original)
                assertArrayEquals(bytes, source.readAll())
                source.close()
            }
            assertEquals(1, resolutions)
        } finally {
            cache.release()
        }
    }

    private fun DataSource.readAll(): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4)
        while (true) {
            val count = read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT) return output.toByteArray()
            output.write(buffer, 0, count)
        }
    }

    private class RecordingSource(
        bytes: ByteArray,
        val requests: MutableList<DataSpec> = mutableListOf(),
    ) : DataSource {
        private val delegate = ByteArrayDataSource(bytes)
        override fun open(dataSpec: DataSpec): Long {
            requests.add(dataSpec)
            return delegate.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length)
        override fun close() = delegate.close()
        override fun getUri(): Uri? = delegate.uri
        override fun addTransferListener(transferListener: TransferListener) = delegate.addTransferListener(transferListener)
    }
}
