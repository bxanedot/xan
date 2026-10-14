package app.xan.music.playback

import java.util.concurrent.ThreadLocalRandom
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.min

/** Retry policy shared by persistent audio downloads and its JVM tests. */
internal object DownloadRecoveryPolicy {
    const val MAX_RETRIES = 8
    private const val INITIAL_DELAY_MS = 20_000L
    private const val MAX_DELAY_MS = 30 * 60_000L

    fun shouldRetry(httpStatus: Int?, errorMessage: String?, retryCount: Int): Boolean {
        if (retryCount >= MAX_RETRIES) return false
        val message = errorMessage.orEmpty().lowercase()
        if (listOf("not enough storage", "no space left", "permission denied", "unauthorized", "unsupported format")
                .any(message::contains)
        ) return false

        return when (httpStatus) {
            408, 425, 429 -> true
            401, 403, 410 -> retryCount < 2 // allow a fresh authorized stream URL to be resolved
            in 500..599 -> true
            in 400..499 -> false
            else -> true // network resets, timeouts, and unknown transient I/O errors
        }
    }

    /** Exponential backoff with full jitter; the cap prevents a job being stranded for days. */
    fun delayMillis(
        retryCount: Int,
        retryAfterHeader: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
        random: ThreadLocalRandom = ThreadLocalRandom.current(),
    ): Long {
        retryAfterHeader?.trim()?.takeIf(String::isNotEmpty)?.let { header ->
            val serverDelay = header.toLongOrNull()?.let { seconds ->
                if (seconds <= 0L) 0L else if (seconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else seconds * 1_000L
            } ?: runCatching {
                ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli().minus(nowMillis).coerceAtLeast(0L)
            }.getOrNull()
            if (serverDelay != null && serverDelay > 0L) return min(MAX_DELAY_MS, serverDelay)
        }
        val exponent = retryCount.coerceIn(1, MAX_RETRIES) - 1
        val ceiling = min(MAX_DELAY_MS, INITIAL_DELAY_MS * (1L shl exponent))
        return random.nextLong(ceiling / 2, ceiling + 1)
    }
}
