package app.xan.music.constants

import app.xan.music.R

private const val DEFAULT_VISIBLE_MARK_FRACTION = 616f / 1024f

enum class AppLogo(
    val value: String,
    val launcherAlias: String,
    /** Fraction of the source canvas occupied by visible logo pixels. */
    val visibleMarkFraction: Float,
    val markResource: Int,
) {
    WHITE("white", "DefaultIcon", DEFAULT_VISIBLE_MARK_FRACTION, R.drawable.xan_launcher_white_mark),
    GOLD("gold", "GoldIcon", DEFAULT_VISIBLE_MARK_FRACTION, R.drawable.xan_gold_mark),
    HORIZON("horizon", "OriginalIcon", DEFAULT_VISIBLE_MARK_FRACTION, R.drawable.xan_mark),
    AURORA("aurora", "AuroraIcon", DEFAULT_VISIBLE_MARK_FRACTION, R.drawable.xan_logo_aurora_mark),
    SUNSET("sunset", "SunsetIcon", DEFAULT_VISIBLE_MARK_FRACTION, R.drawable.xan_logo_sunset_mark),
    GREEN("green", "GreenIcon", DEFAULT_VISIBLE_MARK_FRACTION, R.drawable.xan_logo_green_mark),
    PRISM("prism", "PrismIcon", DEFAULT_VISIBLE_MARK_FRACTION, R.drawable.xan_logo_prism_mark),
    LILAC("lilac", "LilacIcon", DEFAULT_VISIBLE_MARK_FRACTION, R.drawable.xan_logo_lilac_mark),
    BLUE("blue", "BlueIcon", DEFAULT_VISIBLE_MARK_FRACTION, R.drawable.xan_logo_blue_mark);

    /** Draws every variant to the same visible size as the default white mark. */
    val normalizedMarkScale: Float
        get() = DEFAULT_VISIBLE_MARK_FRACTION / visibleMarkFraction

    companion object {
        fun fromPreference(value: String?): AppLogo =
            entries.firstOrNull { it.value == value }
                ?: if (value == "original") HORIZON else WHITE
    }
}
