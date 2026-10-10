package app.xan.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopSleepTimerTest {
    @Test
    fun acceptsAndroidTimerStepsFromFiveThroughOneHundredTwentyMinutes() {
        assertTrue(isValidDesktopSleepTimerMinutes(5))
        assertTrue(isValidDesktopSleepTimerMinutes(30))
        assertTrue(isValidDesktopSleepTimerMinutes(120))
        assertFalse(isValidDesktopSleepTimerMinutes(0))
        assertFalse(isValidDesktopSleepTimerMinutes(7))
        assertFalse(isValidDesktopSleepTimerMinutes(125))
    }

    @Test
    fun countdownUsesTheDeadlineAndClampsAtZero() {
        assertEquals(10_000L, desktopSleepTimerRemainingMillis(90_000_000_000L, 80_000_000_000L))
        assertEquals(0L, desktopSleepTimerRemainingMillis(90_000_000_000L, 90_000_000_000L))
        assertEquals(0L, desktopSleepTimerRemainingMillis(90_000_000_000L, 95_000_000_000L))
    }

    @Test
    fun staleTimerCallbacksCannotStopAReplacementTimer() {
        assertFalse(isCurrentDesktopSleepTimerExpiry(true, currentGeneration = 8, callbackGeneration = 7))
        assertTrue(isCurrentDesktopSleepTimerExpiry(true, currentGeneration = 8, callbackGeneration = 8))
        assertFalse(isCurrentDesktopSleepTimerExpiry(false, currentGeneration = 8, callbackGeneration = 8))
    }
}
