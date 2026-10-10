package app.xan.shared

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import app.xan.shared.resources.Res
import app.xan.shared.resources.chillax_bold
import app.xan.shared.resources.chillax_extralight
import app.xan.shared.resources.chillax_light
import app.xan.shared.resources.chillax_medium
import app.xan.shared.resources.chillax_regular
import app.xan.shared.resources.chillax_semibold
import app.xan.shared.resources.google_sans_flex
import app.xan.shared.resources.outfit
import app.xan.shared.resources.plus_jakarta_sans
import app.xan.shared.resources.sans_flex

internal data class IosFontOption(val id: String, val label: String)

internal object IosTypeface {
    val options = listOf(
        IosFontOption("system", "System"),
        IosFontOption("google_sans", "Google Sans"),
        IosFontOption("sans_flex", "Sans Flex"),
        IosFontOption("outfit", "Outfit"),
        IosFontOption("plus_jakarta_sans", "Plus Jakarta Sans"),
    )

    fun fromPreference(value: String?): String =
        value?.takeIf { candidate -> options.any { it.id == candidate } } ?: "system"

    fun label(id: String): String = options.firstOrNull { it.id == id }?.label ?: "System"

    @Composable
    fun appFontFamily(id: String): FontFamily = when (fromPreference(id)) {
        "google_sans" -> FontFamily(
            Font(Res.font.google_sans_flex, FontWeight.Normal),
            Font(Res.font.google_sans_flex, FontWeight.Medium),
            Font(Res.font.google_sans_flex, FontWeight.Bold),
        )
        "sans_flex" -> FontFamily(
            Font(Res.font.sans_flex, FontWeight.Normal),
            Font(Res.font.sans_flex, FontWeight.Medium),
            Font(Res.font.sans_flex, FontWeight.Bold),
        )
        "outfit" -> FontFamily(
            Font(Res.font.outfit, FontWeight.Normal),
            Font(Res.font.outfit, FontWeight.Medium),
            Font(Res.font.outfit, FontWeight.Bold),
        )
        "plus_jakarta_sans" -> FontFamily(
            Font(Res.font.plus_jakarta_sans, FontWeight.Normal),
            Font(Res.font.plus_jakarta_sans, FontWeight.Medium),
            Font(Res.font.plus_jakarta_sans, FontWeight.Bold),
        )
        else -> FontFamily.Default
    }

    @Composable
    fun brandFontFamily(enabled: Boolean, fallback: FontFamily): FontFamily = if (!enabled) {
        fallback
    } else {
        FontFamily(
            Font(Res.font.chillax_extralight, FontWeight.ExtraLight),
            Font(Res.font.chillax_light, FontWeight.Light),
            Font(Res.font.chillax_regular, FontWeight.Normal),
            Font(Res.font.chillax_medium, FontWeight.Medium),
            Font(Res.font.chillax_semibold, FontWeight.SemiBold),
            Font(Res.font.chillax_bold, FontWeight.Bold),
        )
    }

    fun typography(base: Typography, family: FontFamily): Typography = base.copy(
        displayLarge = base.displayLarge.withFont(family),
        displayMedium = base.displayMedium.withFont(family),
        displaySmall = base.displaySmall.withFont(family),
        headlineLarge = base.headlineLarge.withFont(family),
        headlineMedium = base.headlineMedium.withFont(family),
        headlineSmall = base.headlineSmall.withFont(family),
        titleLarge = base.titleLarge.withFont(family),
        titleMedium = base.titleMedium.withFont(family),
        titleSmall = base.titleSmall.withFont(family),
        bodyLarge = base.bodyLarge.withFont(family),
        bodyMedium = base.bodyMedium.withFont(family),
        bodySmall = base.bodySmall.withFont(family),
        labelLarge = base.labelLarge.withFont(family),
        labelMedium = base.labelMedium.withFont(family),
        labelSmall = base.labelSmall.withFont(family),
    )

    private fun TextStyle.withFont(family: FontFamily) = copy(fontFamily = family)
}
