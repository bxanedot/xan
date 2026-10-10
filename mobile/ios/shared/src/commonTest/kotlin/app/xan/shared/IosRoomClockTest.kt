package app.xan.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosRoomClockTest {
    @Test
    fun matchedPongCorrectsDeviceClockBeforeAdvancingPlayback() {
        var wall = 1_000L
        var elapsed = 100L
        val clock = IosRoomClock({ wall }, { elapsed })
        assertEquals(5_000L, clock.playbackPosition(5_000, true, 9_000))
        val stamp = clock.beginPing()
        wall = 1_200L
        elapsed = 300L
        assertTrue(clock.acceptPong(stamp, 10_100L))
        assertEquals(6_200L, clock.playbackPosition(5_000, true, 9_000))
        assertEquals(5_000L, clock.playbackPosition(5_000, false, 9_000))
        assertEquals(6_000L, clock.playbackPosition(5_000, true, 9_000, 6_000))
    }

    @Test
    fun ignoresUnknownDuplicateAndSlowPongsAndClearsSamplesOnReconnect() {
        var elapsed = 0L
        val clock = IosRoomClock({ 1_000L }, { elapsed })
        assertFalse(clock.acceptPong(123L, 10_000L))
        val stamp = clock.beginPing()
        elapsed = 5_001L
        assertFalse(clock.acceptPong(stamp, 10_000L))
        clock.beginPing()
        elapsed += 100L
        assertTrue(clock.acceptPong(stamp, 10_000L))
        assertFalse(clock.acceptPong(stamp, 10_000L))
        clock.reset()
        assertEquals(200L, clock.playbackPosition(200, true, 10))
    }

    @Test
    fun resolutionDelayAdvancesOnlyPlayingTracksAndClampsToTheirDuration() {
        assertEquals(18_000L, advanceIosRoomPosition(10_000, true, 100, 8_100))
        assertEquals(10_000L, advanceIosRoomPosition(10_000, false, 100, 8_100))
        assertEquals(12_000L, advanceIosRoomPosition(10_000, true, 100, 8_100, 12_000))
        assertEquals(10_000L, advanceIosRoomPosition(10_000, true, 100, 99))
        assertEquals(Long.MAX_VALUE, advanceIosRoomPosition(Long.MAX_VALUE - 10, true, 0, 100))
    }
}
