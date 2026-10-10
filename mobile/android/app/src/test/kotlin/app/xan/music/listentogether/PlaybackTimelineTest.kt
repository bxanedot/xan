package app.xan.music.listentogether

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimelineTest {
    @Test
    fun `playing position advances from the server timestamp`() {
        assertEquals(15_000L, extrapolatePlaybackPosition(10_000, true, 100_000, 105_000))
    }

    @Test
    fun `paused position stays fixed`() {
        assertEquals(10_000L, extrapolatePlaybackPosition(10_000, false, 100_000, 105_000))
    }

    @Test
    fun `missing or future timestamps do not move playback`() {
        assertEquals(10_000L, extrapolatePlaybackPosition(10_000, true, 0, 105_000))
        assertEquals(10_000L, extrapolatePlaybackPosition(10_000, true, 110_000, 105_000))
    }
}
