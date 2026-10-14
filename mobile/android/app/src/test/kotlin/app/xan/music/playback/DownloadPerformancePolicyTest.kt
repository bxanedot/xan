package app.xan.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPerformancePolicyTest {
    @Test
    fun `adaptive scheduler starts at eight and grows after a healthy transfer window`() {
        val scheduler = AdaptiveDownloadController(maximumConcurrency = 12)

        assertEquals(8, scheduler.currentConcurrency)
        assertEquals(
            9,
            scheduler.observe(
                TransferWindow(
                    elapsedMillis = 10_000,
                    bytesTransferred = 20L * 1024 * 1024,
                    completedTransfers = 4,
                    failedTransfers = 0,
                    throttledResponses = 0,
                    queuedTransfers = 20,
                    activeTransfers = 8,
                    averageLatencyMillis = 150,
                    availableMemoryMb = 2048,
                    freeDiskBytes = 10L * 1024 * 1024 * 1024,
                ),
            ),
        )
    }

    @Test
    fun `throttling backs concurrency off quickly`() {
        val scheduler = AdaptiveDownloadController(initialConcurrency = 8)

        assertEquals(
            4,
            scheduler.observe(
                TransferWindow(
                    elapsedMillis = 10_000,
                    bytesTransferred = 2L * 1024 * 1024,
                    completedTransfers = 1,
                    failedTransfers = 2,
                    throttledResponses = 1,
                    queuedTransfers = 10,
                    activeTransfers = 8,
                    averageLatencyMillis = 2500,
                    availableMemoryMb = 2048,
                    freeDiskBytes = 10L * 1024 * 1024 * 1024,
                ),
            ),
        )
    }

    @Test
    fun `failure-heavy windows reduce concurrency and never go below the minimum`() {
        val scheduler = AdaptiveDownloadController(initialConcurrency = 3, minimumConcurrency = 2)
        val window = TransferWindow(
            elapsedMillis = 10_000,
            bytesTransferred = 0,
            completedTransfers = 0,
            failedTransfers = 5,
            throttledResponses = 0,
            queuedTransfers = 5,
            activeTransfers = 3,
            averageLatencyMillis = 1800,
            availableMemoryMb = 1024,
            freeDiskBytes = 2L * 1024 * 1024 * 1024,
        )

        assertEquals(2, scheduler.observe(window))
        assertEquals(2, scheduler.observe(window))
    }

    @Test
    fun `resource pressure clamps the configured concurrency`() {
        val scheduler = AdaptiveDownloadController(initialConcurrency = 8, maximumConcurrency = 16)

        assertEquals(
            2,
            scheduler.observe(
                TransferWindow(
                    elapsedMillis = 10_000,
                    bytesTransferred = 8L * 1024 * 1024,
                    completedTransfers = 2,
                    failedTransfers = 0,
                    throttledResponses = 0,
                    queuedTransfers = 20,
                    activeTransfers = 8,
                    averageLatencyMillis = 200,
                    availableMemoryMb = 128,
                    freeDiskBytes = 128L * 1024 * 1024,
                ),
            ),
        )
    }

    @Test
    fun `unlimited bandwidth mode does not schedule artificial waits`() {
        var nowNanos = 0L
        val waits = mutableListOf<Long>()
        val limiter = DownloadBandwidthLimiter(
            bytesPerSecond = 0,
            nanoTime = { nowNanos },
            sleepNanos = { duration -> waits += duration; nowNanos += duration },
        )

        limiter.acquire(1024 * 1024)
        limiter.acquire(1024 * 1024)

        assertTrue(waits.isEmpty())
    }

    @Test
    fun `custom bandwidth limit paces concurrent chunks at the aggregate rate`() {
        var nowNanos = 0L
        val waits = mutableListOf<Long>()
        val limiter = DownloadBandwidthLimiter(
            bytesPerSecond = 1024,
            nanoTime = { nowNanos },
            sleepNanos = { duration -> waits += duration; nowNanos += duration },
        )

        limiter.acquire(1024)
        limiter.acquire(1024)

        assertEquals(listOf(1_000_000_000L), waits)
    }
}
