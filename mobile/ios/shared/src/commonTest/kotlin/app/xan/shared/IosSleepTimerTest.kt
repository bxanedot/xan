package app.xan.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosSleepTimerTest {
    @Test
    fun timedSleepTimerCountsDownAndClampsAtItsDeadline() {
        val timer = IosSleepTimerState(deadlineEpochMillis = 90_000L, remainingMillis = 30_000L)

        assertEquals(10_000L, timer.remainingAt(80_000L))
        assertEquals(0L, timer.remainingAt(90_000L))
        assertEquals(0L, timer.remainingAt(100_000L))
        assertTrue(timer.isActive)
    }

    @Test
    fun endOfTrackTimerHasNoWallClockDeadlineAndCanBeCleared() {
        val timer = IosSleepTimerState(remainingMillis = 45_000L, pauseAtTrackEnd = true)

        assertTrue(timer.isActive)
        assertEquals(45_000L, timer.remainingAt(90_000L))
        assertNull(IosSleepTimerState().remainingAt(90_000L))
        assertFalse(IosSleepTimerState().isActive)
    }

    @Test
    fun staleNativeCallbacksCannotExpireAReplacementTimer() {
        val timer = IosSleepTimerState(deadlineEpochMillis = 90_000L, remainingMillis = 30_000L)

        assertFalse(isCurrentIosSleepTimerExpiry(timer, currentGeneration = 8, callbackGeneration = 7))
        assertTrue(isCurrentIosSleepTimerExpiry(timer, currentGeneration = 8, callbackGeneration = 8))
        assertFalse(isCurrentIosSleepTimerExpiry(IosSleepTimerState(), currentGeneration = 8, callbackGeneration = 8))
    }
}
