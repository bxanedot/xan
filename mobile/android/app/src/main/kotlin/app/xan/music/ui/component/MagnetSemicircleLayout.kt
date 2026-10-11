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
): MagnetSemicircleLayout {
    require(width >= 240f)
    require(itemCount > 1)
    val originalRadius = (width - 56f) * 0.45f
    val firstAngle = Math.toRadians(-90.0)
    val firstX = originalRadius * cos(firstAngle)
    val firstY = originalRadius * sin(firstAngle)
    val rotation = Math.toRadians(45.0)
    val cosRotation = cos(rotation)
    val sinRotation = sin(rotation)
    val horizontalDirection = if (onLeft) -1f else 1f
    val itemOffsets = List(itemCount) { index ->
        val fraction = index.toDouble() / (itemCount - 1)
        // Rotate and translate the existing arc as one rigid group. This brings
        // its first icon to a 5dp edge gap from the Magnet without changing any
        // of the icons' existing distances from one another.
        val angle = Math.toRadians(-90.0 - 80.0 * fraction)
        val originalX = originalRadius * cos(angle)
        val originalY = originalRadius * sin(angle)
        val deltaX = originalX - firstX
        val deltaY = originalY - firstY
        MagnetMenuPoint(
            x = horizontalDirection * (deltaX * cosRotation - deltaY * sinRotation).toFloat(),
            y = -59f + (deltaX * sinRotation + deltaY * cosRotation).toFloat(),
        )
    }
    val height = 56f - itemOffsets.minOf { it.y } + labelSpace
    val magnetX = if (onLeft) 28f else width - 28f
    val magnetY = height - 28f
    val points = itemOffsets.map { MagnetMenuPoint(magnetX + it.x, magnetY + it.y) }
    return MagnetSemicircleLayout(
        width = width,
        height = height,
        magnet = MagnetMenuPoint(magnetX, magnetY),
        items = points,
    )
}
