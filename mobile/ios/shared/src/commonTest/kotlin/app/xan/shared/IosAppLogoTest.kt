package app.xan.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class IosAppLogoTest {
    @Test
    fun keepsTheSelectedLogoAndMigratesOlderPreferences() {
        assertEquals(IosAppLogo.GOLD, IosAppLogo.fromPreferences("gold"))
        assertEquals(IosAppLogo.HORIZON, IosAppLogo.fromPreferences("original"))
        assertEquals(IosAppLogo.HORIZON, IosAppLogo.fromPreferences(null, legacyAndroidLogo = true))
        assertEquals(IosAppLogo.GOLD, IosAppLogo.fromPreferences(null, legacyAndroidLogo = false))
        assertEquals(IosAppLogo.GOLD, IosAppLogo.fromPreferences("removed-light"))
    }
}
