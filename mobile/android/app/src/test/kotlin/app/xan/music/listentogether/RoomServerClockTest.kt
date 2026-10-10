package app.xan.music.listentogether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomServerClockTest {
    private var elapsedMs = 1_000L
    private val clock = RoomServerClock { elapsedMs }

    @Test
    fun estimatesServerEpochAndAdvancesMonotonically() {
        assertTrue(clock.recordSample(clientSentAtElapsedMs = 900, serverReceivedAtEpochMs = 5_000, serverSentAtEpochMs = 5_020))
        assertEquals(5_060L, clock.nowServerEpochMs())

        elapsedMs += 250
        assertEquals(5_310L, clock.nowServerEpochMs())
    }

    @Test
    fun rejectsInvalidAndUnreasonablySlowSamples() {
        assertFalse(clock.recordSample(clientSentAtElapsedMs = 1_001, serverReceivedAtEpochMs = 5_000, serverSentAtEpochMs = 5_010))
        assertFalse(clock.recordSample(clientSentAtElapsedMs = 900, serverReceivedAtEpochMs = 5_010, serverSentAtEpochMs = 5_000))
        elapsedMs = 70_001
        assertFalse(clock.recordSample(clientSentAtElapsedMs = 1, serverReceivedAtEpochMs = 5_000, serverSentAtEpochMs = 5_010))
        assertNull(clock.nowServerEpochMs())
    }

    @Test
    fun fallsBackToOneTimestampFromOlderServers() {
        assertTrue(clock.recordSample(clientSentAtElapsedMs = 900, serverReceivedAtEpochMs = 5_010, serverSentAtEpochMs = 5_010))
        assertEquals(5_060L, clock.nowServerEpochMs())
    }

    @Test
    fun resetClearsTheEstimatedClock() {
        clock.recordSample(clientSentAtElapsedMs = 900, serverReceivedAtEpochMs = 5_000, serverSentAtEpochMs = 5_020)
        clock.reset()
        assertNull(clock.nowServerEpochMs())
    }
}
