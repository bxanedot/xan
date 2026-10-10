/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.utils

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import androidx.compose.ui.graphics.Shape
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
fun reportException(throwable: Throwable) {
    throwable.printStackTrace()
}

@Suppress("DEPRECATION")
fun setAppLocale(context: Context, locale: Locale?) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val localeManager = context.getSystemService(LocaleManager::class.java)
        localeManager.applicationLocales =
            if (locale == null) LocaleList.getEmptyLocaleList() else LocaleList(locale)
        return
    }

    // ComponentActivity does not use an AppCompat locale delegate, so update the
    // app resources directly on older Android versions and recreate the activity.
    val target = locale ?: Resources.getSystem().configuration.locales[0]
    Locale.setDefault(target)
    val config = Configuration(context.resources.configuration)
    config.setLocale(target)
    context.resources.updateConfiguration(config, context.resources.displayMetrics)
}

fun setAppLocale(context: Context, languageTag: String?) {
    setAppLocale(
        context,
        languageTag
            ?.takeIf { it.isNotBlank() }
            // The picker keeps the legacy "pt" preference for Brazilian
            // Portuguese, while Android needs a region to select values-pt-rBR.
            ?.let { if (it == "pt") "pt-BR" else it }
            ?.let(Locale::forLanguageTag),
    )
}

// A radius only ever yields three distinct shapes (single / first / last), but
// this is called from inside ~29 lazy-list item lambdas, so it used to allocate
// a fresh AbsoluteSmoothCornerShape per item per composition. The identity churn
// also missed Compose's outline cache — which keys on the shape instance — so
// every row rebuilt its corner Path on every draw. Cache per radius instead.
private val listItemShapes = ConcurrentHashMap<Dp, Array<Shape>>()

fun listItemShape(index: Int, count: Int, radius: Dp = 24.dp): Shape {
    val shapes = listItemShapes.getOrPut(radius) {
        val smoothness = 60
        arrayOf(
            AbsoluteSmoothCornerShape(
                cornerRadiusTL = radius, smoothnessAsPercentTL = smoothness,
                cornerRadiusTR = radius, smoothnessAsPercentTR = smoothness,
                cornerRadiusBL = radius, smoothnessAsPercentBL = smoothness,
                cornerRadiusBR = radius, smoothnessAsPercentBR = smoothness
            ),
            AbsoluteSmoothCornerShape(
                cornerRadiusTL = radius, smoothnessAsPercentTL = smoothness,
                cornerRadiusTR = radius, smoothnessAsPercentTR = smoothness,
                cornerRadiusBL = 0.dp, smoothnessAsPercentBL = 0,
                cornerRadiusBR = 0.dp, smoothnessAsPercentBR = 0
            ),
            AbsoluteSmoothCornerShape(
                cornerRadiusTL = 0.dp, smoothnessAsPercentTL = 0,
                cornerRadiusTR = 0.dp, smoothnessAsPercentTR = 0,
                cornerRadiusBL = radius, smoothnessAsPercentBL = smoothness,
                cornerRadiusBR = radius, smoothnessAsPercentBR = smoothness
            ),
        )
    }
    return when {
        count == 1 -> shapes[0]
        index == 0 -> shapes[1]
        index == count - 1 -> shapes[2]
        else -> RectangleShape
    }
}
