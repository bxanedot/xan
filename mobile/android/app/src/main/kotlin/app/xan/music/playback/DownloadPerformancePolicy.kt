package app.xan.music.playback

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** Measurements gathered over one download-manager sampling window. */
internal data class TransferWindow(
    val elapsedMillis: Long,
    val bytesTransferred: Long,
    val completedTransfers: Int,
    val failedTransfers: Int,
    val throttledResponses: Int,
    val queuedTransfers: Int,
    val activeTransfers: Int,
    val averageLatencyMillis: Long,
    val availableMemoryMb: Long,
    val freeDiskBytes: Long,
)

/** Additive concurrency controller; sustained health grows in small steps, throttling backs off fast. */
internal class AdaptiveDownloadController(
    initialConcurrency: Int = DEFAULT_INITIAL_CONCURRENCY,
    minimumConcurrency: Int = DEFAULT_MIN_CONCURRENCY,
    maximumConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
    adaptive: Boolean = true,
) {
    private val minimum = minimumConcurrency.coerceIn(1, DEFAULT_MAX_CONCURRENCY)
    private var maximum = maximumConcurrency.coerceIn(minimum, DEFAULT_MAX_CONCURRENCY)
    private var adaptiveEnabled = adaptive

    var currentConcurrency: Int = initialConcurrency.coerceIn(minimum, maximum)
        private set

    fun setMaximumConcurrency(value: Int): Int {
        maximum = value.coerceIn(minimum, DEFAULT_MAX_CONCURRENCY)
        currentConcurrency = currentConcurrency.coerceAtMost(maximum)
        return currentConcurrency
    }

    fun setAdaptiveEnabled(enabled: Boolean): Int {
        adaptiveEnabled = enabled
        if (!enabled) currentConcurrency = maximum
        return currentConcurrency
    }

    fun observe(window: TransferWindow): Int {
        val resourceCeiling = resourceConcurrencyCeiling(
            availableMemoryMb = window.availableMemoryMb,
            freeDiskBytes = window.freeDiskBytes,
            configuredMaximum = maximum,
        )
        currentConcurrency = currentConcurrency.coerceAtMost(resourceCeiling)

        if (!adaptiveEnabled || window.queuedTransfers <= 0 || window.elapsedMillis <= 0) {
            return currentConcurrency
        }

        val attempts = window.completedTransfers + window.failedTransfers
        val failureRatio = if (attempts == 0) 0.0 else window.failedTransfers.toDouble() / attempts
        val bytesPerSecond = window.bytesTransferred.coerceAtLeast(0L) * 1_000L / window.elapsedMillis

        currentConcurrency = when {
            window.throttledResponses > 0 -> max(minimum, currentConcurrency / 2)
            attempts >= MIN_FAILURE_SAMPLE_COUNT && failureRatio >= FAILURE_BACKOFF_RATIO ->
                max(minimum, currentConcurrency - 1)
            window.failedTransfers > 0 && bytesPerSecond == 0L && window.averageLatencyMillis >= SLOW_FAILURE_LATENCY_MS ->
                max(minimum, currentConcurrency - 1)
            window.completedTransfers > 0 &&
                window.activeTransfers >= currentConcurrency &&
                bytesPerSecond > 0 &&
                window.averageLatencyMillis <= HEALTHY_LATENCY_MS ->
                min(maximum, currentConcurrency + 1)
            else -> currentConcurrency
        }

        currentConcurrency = currentConcurrency.coerceAtMost(resourceCeiling)
        return currentConcurrency
    }

    companion object {
        const val DEFAULT_INITIAL_CONCURRENCY = 8
        const val DEFAULT_MIN_CONCURRENCY = 2
        const val DEFAULT_MAX_CONCURRENCY = 16
        private const val MIN_FAILURE_SAMPLE_COUNT = 4
        private const val FAILURE_BACKOFF_RATIO = 0.5
        private const val HEALTHY_LATENCY_MS = 2_000L
        private const val SLOW_FAILURE_LATENCY_MS = 1_500L
        private const val LOW_MEMORY_MB = 200L
        private const val REDUCED_MEMORY_MB = 400L
        private const val MIN_FREE_DISK_BYTES = 256L * 1024L * 1024L

        fun resourceConcurrencyCeiling(
            availableMemoryMb: Long,
            freeDiskBytes: Long,
            configuredMaximum: Int,
        ): Int = when {
            freeDiskBytes in 0 until MIN_FREE_DISK_BYTES || availableMemoryMb in 0 until LOW_MEMORY_MB ->
                min(configuredMaximum, 2)
            availableMemoryMb in LOW_MEMORY_MB until REDUCED_MEMORY_MB -> min(configuredMaximum, 4)
            else -> configuredMaximum
        }
    }
}

/** Audio requests and video streams share the same active-transfer ceiling. */
internal fun canReserveVideoTransferSlots(
    activeAudioTransfers: Int,
    activeVideoSlots: Int,
    requestedVideoSlots: Int,
    transferLimit: Int,
): Boolean = requestedVideoSlots > 0 &&
    activeAudioTransfers.coerceAtLeast(0) + activeVideoSlots.coerceAtLeast(0) + requestedVideoSlots <=
    transferLimit.coerceAtLeast(1)

/** Decorates either audio or video upstreams while keeping the limiter aggregate across requests. */
internal class RateLimitedDataSource(
    private val upstream: DataSource,
    private val limiter: DownloadBandwidthLimiter,
) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long = upstream.open(dataSpec)

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = upstream.read(buffer, offset, length)
        if (count > 0) limiter.acquire(count)
        return count
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    internal class Factory(
        private val upstream: DataSource.Factory,
        private val limiter: DownloadBandwidthLimiter,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = RateLimitedDataSource(
            upstream = upstream.createDataSource(),
            limiter = limiter,
        )
    }
}

/** A process-wide pacing queue. A limit of zero is truly uncapped. */
internal class DownloadBandwidthLimiter(
    bytesPerSecond: Long = 0L,
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleepNanos: (Long) -> Unit = ::sleepForNanos,
) {
    private val lock = Any()
    private var nextPermitNanos = nanoTime()

    @Volatile
    var bytesPerSecond: Long = bytesPerSecond.coerceAtLeast(0L)
        private set

    fun updateLimit(value: Long) {
        synchronized(lock) {
            bytesPerSecond = value.coerceAtLeast(0L)
            nextPermitNanos = nanoTime()
        }
    }

    fun acquire(byteCount: Int) {
        if (byteCount <= 0) return
        val waitNanos = synchronized(lock) {
            val limit = bytesPerSecond
            if (limit <= 0L) return
            val now = nanoTime()
            val scheduled = max(now, nextPermitNanos)
            val transferDuration = ceil(byteCount.toDouble() * NANOS_PER_SECOND / limit)
                .toLong()
                .coerceAtLeast(1L)
            nextPermitNanos = scheduled + transferDuration
            scheduled - now
        }
        if (waitNanos > 0L) sleepNanos(waitNanos)
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L

        fun sleepForNanos(duration: Long) {
            if (duration <= 0L) return
            val millis = duration / 1_000_000L
            val nanos = (duration % 1_000_000L).toInt()
            Thread.sleep(millis, nanos)
        }
    }
}
