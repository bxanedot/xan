package app.xan.shared

import app.xan.music.listentogether.extrapolatePlaybackPosition
import kotlin.time.Clock
import kotlin.time.TimeSource

private val roomClockOrigin = TimeSource.Monotonic.markNow()
internal fun iosRoomElapsedMillis(): Long = roomClockOrigin.elapsedNow().inWholeMilliseconds

/** Estimate server time using matched pings and monotonic round-trip timing. */
internal class IosRoomClock(
    private val wallMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val elapsedMillis: () -> Long = ::iosRoomElapsedMillis,
) {
    private val pending = mutableMapOf<Long, Long>()
    private var offset: Long? = null

    fun beginPing(): Long {
        val stamp = wallMillis()
        if (pending.size >= 4) pending.remove(pending.keys.first())
        pending[stamp] = elapsedMillis()
        return stamp
    }

    fun acceptPong(clientTime: Long, serverTime: Long): Boolean {
        val started = pending.remove(clientTime) ?: return false
        val roundTrip = elapsedMillis() - started
        if (clientTime <= 0L || serverTime <= 0L || roundTrip !in 0L..5_000L) return false
        val sample = serverTime - (clientTime + roundTrip / 2)
        val previous = offset
        offset = if (previous == null) sample else previous + (sample - previous) / 5
        return true
    }

    fun playbackPosition(position: Long, playing: Boolean, lastUpdate: Long, durationMillis: Long = 0L): Long {
        // Until the first matched pong, the device clock may be unrelated to
        // the server clock. Preserve the server position rather than guessing.
        val estimatedNow = offset?.let { wallMillis() + it } ?: lastUpdate
        val target = extrapolatePlaybackPosition(position, playing, lastUpdate, estimatedNow)
        return if (durationMillis > 0L) target.coerceAtMost(durationMillis) else target
    }

    fun reset() {
        pending.clear()
        offset = null
    }
}

/** Advance a received playing snapshot through time spent resolving its audio. */
internal fun advanceIosRoomPosition(position: Long, playing: Boolean, receivedAt: Long, now: Long, durationMillis: Long = 0L): Long {
    val elapsed = if (playing) (now - receivedAt).coerceAtLeast(0L) else 0L
    val safePosition = position.coerceAtLeast(0L)
    val target = if (elapsed > Long.MAX_VALUE - safePosition) Long.MAX_VALUE else safePosition + elapsed
    return if (durationMillis > 0L) target.coerceAtMost(durationMillis) else target
}
