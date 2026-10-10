package app.xan.desktop

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal const val MAGNET_ATTACHMENT_SIZE = 52f

/** Places a vertical attachment stack inward from whichever window edge is nearest. */
internal fun magnetStackCenters(width: Float, height: Float, hubCenter: Offset, count: Int = 6): List<Offset> {
    require(count > 0)
    if (count == 1) return listOf(hubCenter)
    val edge = listOf(
        "bottom" to height - hubCenter.y,
        "top" to hubCenter.y,
        "right" to width - hubCenter.x,
        "left" to hubCenter.x,
    ).minBy { it.second }.first
    val gap = 8f
    val requiredSpan = (MAGNET_ATTACHMENT_SIZE + gap) * (count - 1) + MAGNET_ATTACHMENT_SIZE
    val vertical = (edge == "left" || edge == "right") && height >= requiredSpan + 24f
    val available = ((if (vertical) height else width) - MAGNET_ATTACHMENT_SIZE).coerceAtLeast(0f)
    val step = minOf(MAGNET_ATTACHMENT_SIZE + gap, available / (count - 1))
    val actualSpan = step * (count - 1)
    val centerX = when (edge) {
        "right" -> hubCenter.x - 68f
        "left" -> hubCenter.x + 68f
        else -> hubCenter.x
    }.coerceIn(MAGNET_ATTACHMENT_SIZE / 2f, (width - MAGNET_ATTACHMENT_SIZE / 2f).coerceAtLeast(MAGNET_ATTACHMENT_SIZE / 2f))
    val centerY = when (edge) {
        "bottom" -> hubCenter.y - 68f
        "top" -> hubCenter.y + 68f
        else -> hubCenter.y
    }
    return List(count) { index ->
        if (vertical) {
            val start = (centerY - actualSpan / 2f).coerceIn(MAGNET_ATTACHMENT_SIZE / 2f, MAGNET_ATTACHMENT_SIZE / 2f + (height - actualSpan - MAGNET_ATTACHMENT_SIZE).coerceAtLeast(0f))
            Offset(centerX, start + index * step)
        } else {
            val start = (centerX - actualSpan / 2f).coerceIn(MAGNET_ATTACHMENT_SIZE / 2f, MAGNET_ATTACHMENT_SIZE / 2f + (width - actualSpan - MAGNET_ATTACHMENT_SIZE).coerceAtLeast(0f))
            Offset(start + index * step, centerY.coerceIn(MAGNET_ATTACHMENT_SIZE / 2f, (height - MAGNET_ATTACHMENT_SIZE / 2f).coerceAtLeast(MAGNET_ATTACHMENT_SIZE / 2f)))
        }
    }
}

/** Places the six actions on the inward-facing half-circle of the nearest window edge. */
internal fun magnetSemicircleCenters(width: Float, height: Float, hubCenter: Offset, count: Int = 6): List<Offset> {
    require(count > 1)
    // Put bottom before right so the default bottom-right attachment opens the same
    // upper semicircle as Android instead of clipping half its controls off-screen.
    val edges = listOf(
        "bottom" to height - hubCenter.y,
        "top" to hubCenter.y,
        "right" to width - hubCenter.x,
        "left" to hubCenter.x,
    )
    val edge = edges.minBy { it.second }.first
    if (edge == "bottom" || edge == "top") {
        val onRight = hubCenter.x >= width / 2f
        val horizontalRoom = if (onRight) (hubCenter.x - MAGNET_ATTACHMENT_SIZE / 2f) / 2f
            else (width - MAGNET_ATTACHMENT_SIZE / 2f - hubCenter.x) / 2f
        val verticalRoom = if (edge == "bottom") hubCenter.y - MAGNET_ATTACHMENT_SIZE / 2f - 68f
            else height - hubCenter.y - MAGNET_ATTACHMENT_SIZE / 2f - 68f
        val radius = minOf(138f, (width - 56f) / 2f, horizontalRoom, verticalRoom).coerceAtLeast(1f)
        val centerX = if (onRight) hubCenter.x - radius else hubCenter.x + radius
        val centerY = if (edge == "bottom") hubCenter.y - 68f else hubCenter.y + 68f
        return List(count) { index ->
            val fraction = index.toDouble() / (count - 1)
            val angle = PI * fraction
            val direction = if (onRight) 1f else -1f
            Offset(
                centerX + direction * radius * cos(angle).toFloat(),
                centerY + (if (edge == "bottom") -1f else 1f) * radius * sin(angle).toFloat(),
            )
        }
    }

    val rightEdge = edge == "right"
    val centerX = if (rightEdge) hubCenter.x - 68f else hubCenter.x + 68f
    val horizontalRoom = if (rightEdge) centerX - MAGNET_ATTACHMENT_SIZE / 2f
        else width - MAGNET_ATTACHMENT_SIZE / 2f - centerX
    val radius = minOf(138f, horizontalRoom, (height - MAGNET_ATTACHMENT_SIZE) / 2f).coerceAtLeast(1f)
    val centerY = hubCenter.y.coerceIn(
        MAGNET_ATTACHMENT_SIZE / 2f + radius,
        (height - MAGNET_ATTACHMENT_SIZE / 2f - radius).coerceAtLeast(MAGNET_ATTACHMENT_SIZE / 2f + radius),
    )
    return List(count) { index ->
        val fraction = index.toDouble() / (count - 1)
        val angle = -PI / 2.0 + PI * fraction
        Offset(
            (centerX + (if (rightEdge) -1f else 1f) * cos(angle).toFloat() * radius)
                .coerceIn(MAGNET_ATTACHMENT_SIZE / 2f, (width - MAGNET_ATTACHMENT_SIZE / 2f).coerceAtLeast(MAGNET_ATTACHMENT_SIZE / 2f)),
            centerY + sin(angle).toFloat() * radius,
        )
    }
}
