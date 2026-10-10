package app.xan.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.EOFException

/** Applies an extractor's range policy below the cache, preserving a continuous byte stream. */
internal class StreamRangeDataSource(private val upstream: DataSource) : DataSource {
    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = StreamRangeDataSource(upstream.createDataSource())
    }

    private var request: DataSpec? = null
    private var policy: StreamRangePolicy? = null
    private var totalRead = 0L
    private var chunkRemaining = 0L
    private var opened = false
    private var ended = false

    override fun open(dataSpec: DataSpec): Long {
        request = dataSpec
        policy = (dataSpec.customData as? StreamRangePolicy)?.takeIf { it.chunkLength > 0 }
        totalRead = 0
        ended = false
        if (policy == null) {
            opened = true
            return upstream.open(dataSpec)
        }
        openChunk()
        return when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            policy?.contentLength != null -> (policy!!.contentLength!! - dataSpec.position).coerceAtLeast(0)
            else -> C.LENGTH_UNSET.toLong()
        }
    }

    private fun openChunk() {
        val original = checkNotNull(request)
        val range = checkNotNull(policy)
        val remaining = if (original.length >= 0) original.length - totalRead else C.LENGTH_UNSET.toLong()
        val length = streamRequestLength(original.position + totalRead, remaining, range)
        if (length == 0L) {
            ended = true
            return
        }
        if (opened) upstream.close()
        opened = true
        try {
            upstream.open(original.buildUpon().setPosition(original.position + totalRead).setLength(length).build())
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            // Unknown-size streams can end exactly on a chunk boundary.
            if (error.responseCode == 416 && totalRead > 0 && range.contentLength == null && original.length < 0) {
                ended = true
                return
            }
            throw error
        }
        chunkRemaining = length
        val contentRange = upstream.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }
            ?.value?.firstOrNull()
        val total = contentRange?.substringAfterLast('/')?.toLongOrNull()?.takeIf { it > 0 }
        if (total != null) {
            policy = range.copy(contentLength = total)
            chunkRemaining = minOf(chunkRemaining, (total - original.position - totalRead).coerceAtLeast(0))
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (policy == null) return upstream.read(buffer, offset, length)
        if (ended) return C.RESULT_END_OF_INPUT
        if (chunkRemaining == 0L) openChunk()
        if (ended) return C.RESULT_END_OF_INPUT
        val count = upstream.read(buffer, offset, minOf(length.toLong(), chunkRemaining).toInt())
        if (count == C.RESULT_END_OF_INPUT) {
            val original = checkNotNull(request)
            val knownEnd = policy?.contentLength
            if ((knownEnd != null && original.position + totalRead < knownEnd) ||
                (original.length >= 0 && totalRead < original.length)) {
                throw EOFException("Media stream ended before its advertised length")
            }
            ended = true
            return count
        }
        totalRead += count
        chunkRemaining -= count
        return count
    }

    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)
    override fun close() {
        try {
            if (opened) upstream.close()
        } finally {
            opened = false
            request = null
            policy = null
        }
    }
}
