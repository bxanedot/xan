package app.xan.music.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DownloadRecoveryPolicyTest {
    @Test
    fun `temporary server failures retry while permanent client failures stay blocked`() {
        assertTrue(DownloadRecoveryPolicy.shouldRetry(408, "timeout", 0))
        assertTrue(DownloadRecoveryPolicy.shouldRetry(425, "retry later", 0))
        assertTrue(DownloadRecoveryPolicy.shouldRetry(429, "rate limited", 0))
        assertTrue(DownloadRecoveryPolicy.shouldRetry(503, "unavailable", 0))
        assertFalse(DownloadRecoveryPolicy.shouldRetry(404, "not found", 0))
        assertFalse(DownloadRecoveryPolicy.shouldRetry(400, "bad request", 0))
    }

    @Test
    fun `expired signed stream gets two refresh attempts then remains blocked`() {
        assertTrue(DownloadRecoveryPolicy.shouldRetry(403, "forbidden", 0))
        assertTrue(DownloadRecoveryPolicy.shouldRetry(403, "forbidden", 1))
        assertFalse(DownloadRecoveryPolicy.shouldRetry(403, "forbidden", 2))
        assertTrue(DownloadRecoveryPolicy.shouldRetry(410, "expired", 0))
    }

    @Test
    fun `storage and permission failures are not retried as network failures`() {
        assertFalse(DownloadRecoveryPolicy.shouldRetry(null, "No space left on device", 0))
        assertFalse(DownloadRecoveryPolicy.shouldRetry(503, "Permission denied writing file", 0))
    }

    @Test
    fun `retry sequence is bounded and delay grows with full jitter`() {
        assertTrue(DownloadRecoveryPolicy.shouldRetry(null, "connection reset", 7))
        assertFalse(DownloadRecoveryPolicy.shouldRetry(null, "connection reset", 8))
        val first = DownloadRecoveryPolicy.delayMillis(1)
        val third = DownloadRecoveryPolicy.delayMillis(3)
        assertTrue(first in 10_000L..20_000L)
        assertTrue(third in 40_000L..80_000L)
    }

    @Test
    fun `retry honors numeric and HTTP-date Retry-After values with a finite cap`() {
        assertEquals(5_000L, DownloadRecoveryPolicy.delayMillis(1, "5", nowMillis = 0L))
        val tenSecondsBefore = Instant.parse("2015-10-21T07:27:50Z").toEpochMilli()
        assertEquals(
            10_000L,
            DownloadRecoveryPolicy.delayMillis(
                1,
                "Wed, 21 Oct 2015 07:28:00 GMT",
                nowMillis = tenSecondsBefore,
            ),
        )
        assertEquals(30 * 60_000L, DownloadRecoveryPolicy.delayMillis(1, "86400", nowMillis = 0L))
    }
}
