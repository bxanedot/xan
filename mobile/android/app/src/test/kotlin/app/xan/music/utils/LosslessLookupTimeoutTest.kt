package app.xan.music.utils

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LosslessLookupTimeoutTest {
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `lossless lookup is cancelled at the one second deadline for High fallback`() = runTest {
        var lookupCancelled = false
        var lookupCompleted = false

        val result = tryLosslessLookupWithinDeadline {
            try {
                delay(10_000L)
                lookupCompleted = true
                "flac stream"
            } finally {
                lookupCancelled = true
            }
        }

        assertNull(result)
        assertFalse(lookupCompleted)
        assertTrue(lookupCancelled)
        assertEquals(LOSSLESS_SOURCE_LOOKUP_TIMEOUT_MS, testScheduler.currentTime)
    }
}
