package app.xan.music.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import app.xan.music.constants.AppIconKey
import app.xan.music.constants.AppLogo
import app.xan.music.utils.rememberPreference

/** In-app logos use the selected transparent mark; launcher backgrounds are supplied by Android. */
@Composable
fun xanLogoPainter(inAppTile: Boolean = false): Painter {
    val (value) = rememberPreference(AppIconKey, AppLogo.WHITE.value)
    return xanLogoPainter(AppLogo.fromPreference(value), inAppTile)
}

/** The picker previews the mark used by the installed launcher icon. */
@Composable
fun xanLauncherMarkPainter(logo: AppLogo): Painter {
    val mark = painterResource(logo.markResource)
    return remember(mark, logo.normalizedMarkScale) {
        XanLogoPainter(mark, inAppTile = false, logoScale = logo.normalizedMarkScale)
    }
}

@Composable
private fun xanLogoPainter(logo: AppLogo, inAppTile: Boolean): Painter {
    val mark = painterResource(logo.markResource)
    return remember(mark, inAppTile, logo.normalizedMarkScale) {
        XanLogoPainter(mark, inAppTile, logo.normalizedMarkScale)
    }
}

private class XanLogoPainter(
    private val mark: Painter,
    private val inAppTile: Boolean,
    private val logoScale: Float,
) : Painter() {
    override val intrinsicSize = mark.intrinsicSize

    override fun DrawScope.onDraw() {
        if (inAppTile) {
            val side = minOf(size.width, size.height)
            drawRect(
                color = Color(0xFF121214),
                topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f),
                size = Size(side, side),
            )
        }
        val sourceSize = mark.intrinsicSize
        val scale = minOf(size.width / sourceSize.width, size.height / sourceSize.height) * 0.96f * logoScale
        val markSize = Size(sourceSize.width * scale, sourceSize.height * scale)
        withTransform({
            translate(
                left = (size.width - markSize.width) / 2f,
                top = (size.height - markSize.height) / 2f,
            )
        }) {
            with(mark) {
                draw(size = markSize)
            }
        }
    }
}
