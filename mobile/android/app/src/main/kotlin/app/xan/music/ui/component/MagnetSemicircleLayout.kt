package app.xan.music.ui.component

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal data class MagnetMenuPoint(val x: Float, val y: Float)

/** All dimensions are in dp; the last 56dp hosts the Magnet at its original position. */
internal data class MagnetSemicircleLayout(
    val width: Float,
    val height: Float,
    val magnet: MagnetMenuPoint,
    val items: List<MagnetMenuPoint>,
)

internal fun magnetSemicircleLayout(width: Float, onLeft: Boolean, itemCount: Int = 6): MagnetSemicircleLayout {
    require(width >= 240f)
    require(itemCount > 1)
    val radius = (width - 56f) / 2f
    val height = radius + 124f
    val points = List(itemCount) { index ->
        val fraction = index.toDouble() / (itemCount - 1)
        val angle = PI * if (onLeft) 1.0 - fraction else fraction
        MagnetMenuPoint(
            x = width / 2f + radius * cos(angle).toFloat(),
            y = radius + 28f - radius * sin(angle).toFloat(),
        )
    }
    return MagnetSemicircleLayout(
        width = width,
        height = height,
        magnet = MagnetMenuPoint(if (onLeft) 28f else width - 28f, height - 28f),
        items = points,
    )
}
