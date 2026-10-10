package app.xan.desktop

import app.xan.music.listentogether.ControlModes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class DesktopTransportAction { PLAY_PAUSE, PREVIOUS, NEXT, STOP }

/** All desktop entry points share room permissions, Cast routing, and queue semantics. */
internal object DesktopTransportControls {
    private val actions = Mutex()

    suspend fun perform(action: DesktopTransportAction) = actions.withLock {
        val room = DesktopListenTogether.state.value
        if (room.roomCode != null && !room.isHost && room.controlMode != ControlModes.EVERYONE) {
            DesktopPlayer.setNotice("The room host controls playback.")
            return@withLock
        }
        try {
            if (DesktopCast.state.value.connectedName == null) {
                when (action) {
                    DesktopTransportAction.PLAY_PAUSE -> DesktopPlayer.togglePlayPause()
                    DesktopTransportAction.PREVIOUS -> DesktopPlayer.previous()
                    DesktopTransportAction.NEXT -> DesktopPlayer.next()
                    DesktopTransportAction.STOP -> DesktopPlayer.stop()
                }
            } else when (action) {
                DesktopTransportAction.PLAY_PAUSE -> DesktopCast.togglePlayback()
                DesktopTransportAction.STOP -> { DesktopCast.pause(); DesktopCast.seek(0) }
                DesktopTransportAction.PREVIOUS, DesktopTransportAction.NEXT -> {
                    val snapshot = DesktopPlayer.state.value
                    if (action == DesktopTransportAction.PREVIOUS && snapshot.positionMillis > 5_000) {
                        DesktopCast.seek(0)
                        return@withLock
                    }
                    val index = when {
                        action == DesktopTransportAction.PREVIOUS && snapshot.queueIndex > 0 -> snapshot.queueIndex - 1
                        action == DesktopTransportAction.PREVIOUS && snapshot.repeatMode == RepeatMode.ALL -> snapshot.queue.lastIndex
                        action == DesktopTransportAction.NEXT && snapshot.repeatMode == RepeatMode.ONE -> snapshot.queueIndex
                        action == DesktopTransportAction.NEXT && snapshot.queueIndex + 1 in snapshot.queue.indices -> snapshot.queueIndex + 1
                        action == DesktopTransportAction.NEXT && snapshot.repeatMode == RepeatMode.ALL -> 0
                        else -> return@withLock
                    }
                    val track = snapshot.queue.getOrNull(index) ?: return@withLock
                    DesktopCast.playOnCurrentReceiver(track, DesktopBackend.resolveTrackUri(track))
                    DesktopPlayer.setExternalTrack(track, index)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            DiagnosticLog.warn(DesktopBackend.diagnosticsDirectory(), "transport_control_failed", "Could not control playback.", failure)
            DesktopPlayer.setNotice(failure.message ?: "Could not control playback.")
        }
    }
}
