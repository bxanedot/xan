package app.xan.desktop

import com.music.innertube.pages.HomePage

/** Logo variants and preference migration aligned with Android's AppLogo choices. */
internal enum class AppLogoVariant(
    val preferenceValue: String,
    val label: String,
    val resource: String,
) {
    WHITE("white", "White", "brand/xan_logo_white.png"),
    GOLD("gold", "Gold", "brand/xan_logo_gold.png"),
    HORIZON("horizon", "Horizon", "brand/xan_logo_horizon.png"),
    AURORA("aurora", "Aurora", "brand/xan_logo_aurora.png"),
    SUNSET("sunset", "Sunset", "brand/xan_logo_sunset.png"),
    GREEN("green", "Green", "brand/xan_logo_green.png"),
    PRISM("prism", "Prism", "brand/xan_logo_prism.png"),
    LILAC("lilac", "Lilac", "brand/xan_logo_lilac.png"),
    BLUE("blue", "Blue", "brand/xan_logo_blue.png");

    companion object {
        fun fromPreferences(value: String?, legacyGold: Boolean? = null): AppLogoVariant =
            entries.firstOrNull { it.preferenceValue == value?.lowercase() }
                ?: when {
                    value.equals("original", ignoreCase = true) -> HORIZON
                    legacyGold == false -> HORIZON
                    else -> GOLD
                }
    }
}

internal data class HomeFeedRequest(
    val browseId: String = "FEmusic_home",
    val params: String? = null,
)

/** Android sends both parts of a Home chip's browse endpoint. */
internal fun HomePage.Chip.toHomeFeedRequest(): HomeFeedRequest? = endpoint
    ?.takeIf { it.browseId.isNotBlank() }
    ?.let { HomeFeedRequest(browseId = it.browseId, params = it.params) }
