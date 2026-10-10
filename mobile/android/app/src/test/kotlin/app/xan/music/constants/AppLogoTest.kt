package app.xan.music.constants

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLogoTest {
    @Test
    fun newAndUnknownPreferencesUseWhite() {
        assertEquals(AppLogo.WHITE, AppLogo.fromPreference(null))
        assertEquals(AppLogo.WHITE, AppLogo.fromPreference("removed-icon"))
    }

    @Test
    fun previousOriginalChoiceMigratesToHorizon() {
        assertEquals(AppLogo.HORIZON, AppLogo.fromPreference("original"))
    }

    @Test
    fun launcherOptionsKeepDefaultAliasAndHaveUniqueEntries() {
        assertEquals("DefaultIcon", AppLogo.WHITE.launcherAlias)
        assertEquals(9, AppLogo.entries.size)
        assertEquals(AppLogo.entries.size, AppLogo.entries.map { it.launcherAlias }.toSet().size)
        assertEquals(AppLogo.WHITE, AppLogo.fromPreference("white"))
        assertEquals(AppLogo.AURORA, AppLogo.fromPreference("aurora"))
        assertEquals(AppLogo.HORIZON, AppLogo.fromPreference("horizon"))
    }

    @Test
    fun everyLogoVariantNormalizesToTheDefaultVisibleMarkSize() {
        AppLogo.entries.forEach { logo ->
            assertEquals(
                AppLogo.WHITE.visibleMarkFraction,
                logo.visibleMarkFraction * logo.normalizedMarkScale,
                0.001f,
            )
        }
    }
}
