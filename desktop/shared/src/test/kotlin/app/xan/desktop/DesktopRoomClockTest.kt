package app.xan.desktop

import app.xan.music.listentogether.extrapolatePlaybackPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopRoomClockTest {
    @Test
    fun estimatesAndSmoothsServerClockOffsetFromPingRoundTrip() {
        assertEquals(500L, desktopServerClockOffsetSample(clientTimeMs = 1_000, serverTimeMs = 1_600, roundTripMs = 200))
        assertEquals(200L, smoothDesktopServerClockOffset(previousOffsetMs = 100, sampleOffsetMs = 500))
        assertEquals(-100L, smoothDesktopServerClockOffset(previousOffsetMs = null, sampleOffsetMs = -100))
    }

    @Test
    fun ignoresInvalidOrSlowClockSamples() {
        assertNull(desktopServerClockOffsetSample(clientTimeMs = 0, serverTimeMs = 1_600, roundTripMs = 100))
        assertNull(desktopServerClockOffsetSample(clientTimeMs = 1_000, serverTimeMs = 0, roundTripMs = 100))
        assertNull(desktopServerClockOffsetSample(clientTimeMs = 1_000, serverTimeMs = 1_600, roundTripMs = 5_001))
    }

    @Test
    fun playbackPositionUsesServerTimeOnlyWhilePlaying() {
        assertEquals(2_500L, extrapolatePlaybackPosition(2_000, true, 1_000, 1_500))
        assertEquals(2_000L, extrapolatePlaybackPosition(2_000, false, 1_000, 1_500))
        assertEquals(2_000L, extrapolatePlaybackPosition(2_000, true, 0, 1_500))
    }
}
