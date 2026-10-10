package app.xan.music.playback

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** Back off once, then wait for a usable connection without consuming offline retries. */
internal suspend fun awaitNetworkRetry(connected: StateFlow<Boolean>, backoffMs: Long) {
    delay(backoffMs)
    connected.first { it }
}
