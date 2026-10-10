package app.xan.desktop

internal fun desktopServerClockOffsetSample(clientTimeMs: Long, serverTimeMs: Long, roundTripMs: Long): Long? {
    if (clientTimeMs <= 0L || serverTimeMs <= 0L || roundTripMs !in 0L..5_000L) return null
    return serverTimeMs - (clientTimeMs + roundTripMs / 2)
}

internal fun smoothDesktopServerClockOffset(previousOffsetMs: Long?, sampleOffsetMs: Long): Long =
    previousOffsetMs?.let { (it * 0.75 + sampleOffsetMs * 0.25).toLong() } ?: sampleOffsetMs
