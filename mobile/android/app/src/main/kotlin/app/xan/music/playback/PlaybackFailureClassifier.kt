package app.xan.music.playback

/** Classifies definitive YouTube availability responses that should not be retried as transport failures. */
internal object PlaybackFailureClassifier {
    private val unavailablePhrases = listOf(
        "video unavailable",
        "video is unavailable",
        "video is not available",
        "video has been removed",
        "video is private",
    )

    fun isContentUnavailable(error: Throwable): Boolean {
        val visited = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Throwable, Boolean>(),
        )
        var current: Throwable? = error
        while (current != null && visited.add(current)) {
            val message = current.message?.lowercase().orEmpty()
            if (unavailablePhrases.any(message::contains)) return true
            current = current.cause
        }
        return false
    }
}
