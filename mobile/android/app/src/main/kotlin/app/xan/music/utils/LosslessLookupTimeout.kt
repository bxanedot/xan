package app.xan.music.utils

import kotlinx.coroutines.withTimeoutOrNull

internal const val LOSSLESS_SOURCE_LOOKUP_TIMEOUT_MS = 1_000L

/** Lossless source discovery is optional; null means continue with regular High audio. */
internal suspend fun <T> tryLosslessLookupWithinDeadline(lookup: suspend () -> T?): T? =
    withTimeoutOrNull(LOSSLESS_SOURCE_LOOKUP_TIMEOUT_MS) { lookup() }
