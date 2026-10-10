package app.xan.music.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class LogoRotationTest {
    @Test
    fun `rotation integrates baseline and boosted speed without changing direction`() {
        assertEquals(15f, advanceLogoRotation(10f, STARTUP_LOGO_POWER, 0.1f), 0.001f)
        assertEquals(18f, advanceLogoRotation(10f, BOOSTED_LOGO_POWER, 0.1f), 0.001f)
    }

    @Test
    fun `crossing a full turn preserves the next angle`() {
        assertEquals(3f, advanceLogoRotation(358f, STARTUP_LOGO_POWER, 0.1f), 0.001f)
    }

    @Test
    fun `paused or stalled frames cannot jump or reverse the logo`() {
        assertEquals(15f, advanceLogoRotation(10f, STARTUP_LOGO_POWER, 5f), 0.001f)
        assertEquals(10f, advanceLogoRotation(10f, STARTUP_LOGO_POWER, -1f), 0.001f)
    }
}
