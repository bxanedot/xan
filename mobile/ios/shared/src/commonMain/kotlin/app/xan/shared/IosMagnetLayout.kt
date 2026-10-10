package app.xan.shared

internal data class IosMagnetPosition(val x: Float, val y: Float)

/** Snap a normalized Magnet position to whichever window edge is nearest. */
internal fun dockIosMagnet(x: Float, y: Float, maxX: Float, maxY: Float): IosMagnetPosition {
    val width = maxX.coerceAtLeast(0f)
    val height = maxY.coerceAtLeast(0f)
    val normalizedX = x.coerceIn(0f, 1f)
    val normalizedY = y.coerceIn(0f, 1f)
    val distanceToEdges = listOf(
        normalizedX * width,
        (1f - normalizedX) * width,
        normalizedY * height,
        (1f - normalizedY) * height,
    )
    return when (distanceToEdges.indices.minByOrNull(distanceToEdges::get)) {
        0 -> IosMagnetPosition(0f, normalizedY)
        1 -> IosMagnetPosition(1f, normalizedY)
        2 -> IosMagnetPosition(normalizedX, 0f)
        else -> IosMagnetPosition(normalizedX, 1f)
    }
}
