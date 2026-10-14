package app.xan.music.utils

import java.util.concurrent.ConcurrentHashMap

/** Tracks rejected player clients briefly so a refreshed stream uses another client. */
internal class StreamClientFailureTracker(
    private val ttlMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val failuresByVideo = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    fun markRejected(videoId: String, clientName: String) {
        if (videoId.isBlank() || clientName.isBlank()) return
        failuresByVideo.computeIfAbsent(videoId) { ConcurrentHashMap() }[clientName] = clock()
    }

    fun excludedFor(videoId: String): Set<String> {
        val failures = failuresByVideo[videoId] ?: return emptySet()
        val now = clock()
        val active = buildSet {
            failures.entries.forEach { (clientName, failedAt) ->
                val age = now - failedAt
                if (age in 0 until ttlMillis) {
                    add(clientName)
                } else {
                    failures.remove(clientName, failedAt)
                }
            }
        }
        if (failures.isEmpty()) failuresByVideo.remove(videoId, failures)
        return active
    }
}
