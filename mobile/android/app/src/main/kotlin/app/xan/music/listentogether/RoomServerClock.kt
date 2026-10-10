/*
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.listentogether

import kotlin.math.max

/** Estimates the server's Unix clock from monotonic client timestamps. */
internal class RoomServerClock(
    private val elapsedRealtime: () -> Long,
) {
    private var serverEpochOffsetMs: Double? = null
    private var bestNetworkRoundTripMs = Long.MAX_VALUE

    @Synchronized
    fun reset() {
        serverEpochOffsetMs = null
        bestNetworkRoundTripMs = Long.MAX_VALUE
    }

    /** Returns true only when the first usable clock sample is accepted. */
    @Synchronized
    fun recordSample(
        clientSentAtElapsedMs: Long,
        serverReceivedAtEpochMs: Long,
        serverSentAtEpochMs: Long,
    ): Boolean {
        val clientReceivedAtElapsedMs = elapsedRealtime()
        if (clientSentAtElapsedMs <= 0L || clientSentAtElapsedMs > clientReceivedAtElapsedMs) return false
        val fullRoundTripMs = clientReceivedAtElapsedMs - clientSentAtElapsedMs
        if (fullRoundTripMs > MAX_SAMPLE_AGE_MS) return false
        if (serverReceivedAtEpochMs <= 0L || serverSentAtEpochMs < serverReceivedAtEpochMs) return false

        val serverWorkMs = serverSentAtEpochMs - serverReceivedAtEpochMs
        val networkRoundTripMs = max(0L, fullRoundTripMs - serverWorkMs)
        if (networkRoundTripMs > MAX_NETWORK_ROUND_TRIP_MS) return false

        // The response leaves the server halfway through the network round trip.
        val sampleOffsetMs = serverSentAtEpochMs + networkRoundTripMs / 2.0 - clientReceivedAtElapsedMs
        val previousOffsetMs = serverEpochOffsetMs
        bestNetworkRoundTripMs = minOf(bestNetworkRoundTripMs, networkRoundTripMs)
        val weight = if (networkRoundTripMs <= bestNetworkRoundTripMs + GOOD_SAMPLE_MARGIN_MS) {
            GOOD_SAMPLE_WEIGHT
        } else {
            NOISY_SAMPLE_WEIGHT
        }
        serverEpochOffsetMs = previousOffsetMs?.let { it + weight * (sampleOffsetMs - it) } ?: sampleOffsetMs
        return previousOffsetMs == null
    }

    @Synchronized
    fun nowServerEpochMs(): Long? = serverEpochOffsetMs?.let { (elapsedRealtime() + it).toLong() }

    companion object {
        private const val MAX_SAMPLE_AGE_MS = 60_000L
        private const val MAX_NETWORK_ROUND_TRIP_MS = 5_000L
        private const val GOOD_SAMPLE_MARGIN_MS = 50L
        private const val GOOD_SAMPLE_WEIGHT = 0.25
        private const val NOISY_SAMPLE_WEIGHT = 0.05
    }
}
