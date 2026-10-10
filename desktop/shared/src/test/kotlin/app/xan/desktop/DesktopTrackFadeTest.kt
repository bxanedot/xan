package app.xan.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopTrackFadeTest {
    @Test
    fun seekingBackFromTheEndRestoresFullVolume() {
        assertEquals(0.0, desktopTrackFadeGain(60_000.0, 60_000.0, 4), 0.001)
        assertEquals(1.0, desktopTrackFadeGain(20_000.0, 60_000.0, 4), 0.001)
        assertEquals(0.5, desktopTrackFadeGain(58_000.0, 60_000.0, 4), 0.001)
    }

    @Test
    fun shortTracksReachFullVolumeWithoutOverlappingFadeWindows() {
        assertEquals(1.0, desktopTrackFadeGain(2_000.0, 4_000.0, 8), 0.001)
        assertEquals(0.5, desktopTrackFadeGain(1_000.0, 4_000.0, 8), 0.001)
        assertEquals(0.5, desktopTrackFadeGain(3_000.0, 4_000.0, 8), 0.001)
    }

    @Test
    fun disabledOrUnknownDurationDoesNotMuteNormalPlayback() {
        assertEquals(1.0, desktopTrackFadeGain(0.0, 60_000.0, 0), 0.001)
        assertEquals(1.0, desktopTrackFadeGain(20_000.0, Double.NaN, 4), 0.001)
        assertEquals(1.0, desktopTrackFadeGain(Double.NaN, Double.NaN, 4), 0.001)
    }
}
