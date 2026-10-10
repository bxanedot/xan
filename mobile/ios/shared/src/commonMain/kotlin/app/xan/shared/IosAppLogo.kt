package app.xan.shared

import app.xan.shared.resources.Res
import app.xan.shared.resources.xan_logo_aurora
import app.xan.shared.resources.xan_logo_blue
import app.xan.shared.resources.xan_logo_gold
import app.xan.shared.resources.xan_logo_green
import app.xan.shared.resources.xan_logo_horizon
import app.xan.shared.resources.xan_logo_lilac
import app.xan.shared.resources.xan_logo_prism
import app.xan.shared.resources.xan_logo_sunset
import app.xan.shared.resources.xan_logo_white
import org.jetbrains.compose.resources.DrawableResource

/** iOS logo choices mirror Android's AppLogo catalog and migrate the earlier two-logo setting. */
internal enum class IosAppLogo(
    val preferenceValue: String,
    val label: String,
    val drawable: DrawableResource,
) {
    WHITE("white", "White", Res.drawable.xan_logo_white),
    GOLD("gold", "Gold", Res.drawable.xan_logo_gold),
    HORIZON("horizon", "Horizon", Res.drawable.xan_logo_horizon),
    AURORA("aurora", "Aurora", Res.drawable.xan_logo_aurora),
    SUNSET("sunset", "Sunset", Res.drawable.xan_logo_sunset),
    GREEN("green", "Green", Res.drawable.xan_logo_green),
    PRISM("prism", "Prism", Res.drawable.xan_logo_prism),
    LILAC("lilac", "Lilac", Res.drawable.xan_logo_lilac),
    BLUE("blue", "Blue", Res.drawable.xan_logo_blue);

    companion object {
        fun fromPreferences(value: String?, legacyAndroidLogo: Boolean? = null): IosAppLogo =
            entries.firstOrNull { it.preferenceValue == value?.lowercase() }
                ?: when {
                    value.equals("original", ignoreCase = true) -> HORIZON
                    legacyAndroidLogo == true -> HORIZON
                    else -> GOLD
                }
    }
}
