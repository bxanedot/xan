package app.xan.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopSystemTrayTest {
    @Test
    fun closingHidesTheWindowOnlyWhenTrayCanKeepActivePlaybackRunning() {
        assertTrue(shouldHideToSystemTray(true, PlaybackStatus.PLAYING))
        assertTrue(shouldHideToSystemTray(true, PlaybackStatus.BUFFERING))
        assertTrue(shouldHideToSystemTray(true, PlaybackStatus.RESOLVING))
        assertFalse(shouldHideToSystemTray(true, PlaybackStatus.PAUSED))
        assertFalse(shouldHideToSystemTray(true, PlaybackStatus.IDLE))
        assertFalse(shouldHideToSystemTray(true, PlaybackStatus.ERROR))
        assertFalse(shouldHideToSystemTray(false, PlaybackStatus.PLAYING))
    }
}
