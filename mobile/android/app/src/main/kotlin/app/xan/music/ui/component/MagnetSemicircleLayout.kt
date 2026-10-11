package app.xan.music.ui.component

import kotlin.math.cos
import kotlin.math.sin

internal data class MagnetMenuPoint(val x: Float, val y: Float)

/** All dimensions are in dp; the Magnet stays anchored at the popup's lower corner. */
internal data class MagnetSemicircleLayout(
    val width: Float,
    val height: Float,
    val magnet: MagnetMenuPoint,
    val items: List<MagnetMenuPoint>,
)

internal fun magnetSemicircleLayout(
    width: Float,
    onLeft: Boolean,
    itemCount: Int = 3,
    labelSpace: Float = 0f,
    pageIconGapDp: Float = 3f,
): MagnetSemicircleLayout {
    require(width >= 240f)
    require(itemCount > 1)
    val radius = (width - 56f) * 0.45f
    val height = radius + 124f + labelSpace
    val magnetX = if (onLeft) 28f else width - 28f
    val magnetY = height - 28f
    val pageIconIndex = 2 // Home, Search, Library/pages, Recognition
    val pageIconRadius = 28f + 26f + pageIconGapDp // Magnet edge + icon edge + gap
    val points = List(itemCount) { index ->
        val fraction = index.toFloat() / (itemCount - 1)
        // Keep the original inward-facing 80° arc. Only bring the Library/pages
        // icon closer to the Magnet; the other icons stay in their original places.
        val angle = Math.toRadians(
            if (onLeft) (-90f + 80f * fraction).toDouble()
            else (-90f - 80f * fraction).toDouble(),
        )
        val itemRadius = if (index == pageIconIndex) pageIconRadius else radius
        MagnetMenuPoint(
            x = magnetX + itemRadius * cos(angle).toFloat(),
            y = magnetY + itemRadius * sin(angle).toFloat(),
        )
    }
    return MagnetSemicircleLayout(
        width = width,
        height = height,
        magnet = MagnetMenuPoint(magnetX, magnetY),
        items = points,
    )
}
