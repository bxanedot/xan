package app.xan.desktop

/** Position-based fades recover immediately after a seek and never override the user's volume. */
internal fun desktopTrackFadeGain(positionMillis: Double, durationMillis: Double, seconds: Int): Double {
    if (seconds <= 0 || !positionMillis.isFinite()) return 1.0
    val knownDuration = durationMillis.isFinite() && durationMillis > 0.0
    val window = if (knownDuration) minOf(seconds * 1_000.0, durationMillis / 2) else seconds * 1_000.0
    if (window <= 0.0) return 1.0
    val fadeIn = (positionMillis / window).coerceIn(0.0, 1.0)
    val fadeOut = if (knownDuration) ((durationMillis - positionMillis) / window).coerceIn(0.0, 1.0) else 1.0
    return minOf(fadeIn, fadeOut)
}
