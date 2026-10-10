package app.xan.desktop

import com.music.innertube.models.BrowseEndpoint
import com.music.innertube.pages.HomePage
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopBrandingTest {
    @Test
    fun migratesTheOldTwoLogoPreferenceAndTheAndroidOriginalName() {
        assertEquals(AppLogoVariant.GOLD, AppLogoVariant.fromPreferences(null))
        assertEquals(AppLogoVariant.GOLD, AppLogoVariant.fromPreferences(null, legacyGold = true))
        assertEquals(AppLogoVariant.HORIZON, AppLogoVariant.fromPreferences(null, legacyGold = false))
        assertEquals(AppLogoVariant.HORIZON, AppLogoVariant.fromPreferences("original"))
        assertEquals(AppLogoVariant.GOLD, AppLogoVariant.fromPreferences("removed-light"))
    }

    @Test
    fun homeMoodRequestUsesTheChipBrowseIdAndParamsTogether() {
        val chip = HomePage.Chip(
            title = "Focus",
            endpoint = BrowseEndpoint(browseId = "FEmusic_moods_and_genres_category", params = "focus-params"),
            deselectEndPoint = null,
        )

        assertEquals(
            HomeFeedRequest(browseId = "FEmusic_moods_and_genres_category", params = "focus-params"),
            chip.toHomeFeedRequest(),
        )
    }
}
