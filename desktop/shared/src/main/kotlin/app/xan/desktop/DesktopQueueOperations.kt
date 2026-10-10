package app.xan.desktop

internal data class DesktopQueueState(val tracks: List<MusicTrack>, val currentIndex: Int)

/** Queue mutations received from the room host never replace the current track. */
internal object DesktopQueueOperations {
    fun add(state: DesktopQueueState, track: MusicTrack, insertNext: Boolean): DesktopQueueState {
        val insertionIndex = if (insertNext) {
            (state.currentIndex + 1).coerceIn(0, state.tracks.size)
        } else {
            state.tracks.size
        }
        val tracks = state.tracks.toMutableList().apply { add(insertionIndex, track) }
        val currentIndex = if (insertionIndex <= state.currentIndex) state.currentIndex + 1 else state.currentIndex
        return DesktopQueueState(tracks, currentIndex)
    }

    fun removeFuture(state: DesktopQueueState, videoId: String): DesktopQueueState {
        val firstFutureIndex = (state.currentIndex + 1).coerceAtLeast(0)
        val removeIndex = (firstFutureIndex until state.tracks.size).firstOrNull { state.tracks[it].videoId == videoId }
            ?: return state
        val tracks = state.tracks.toMutableList().apply { removeAt(removeIndex) }
        val currentIndex = when {
            tracks.isEmpty() -> -1
            removeIndex < state.currentIndex -> state.currentIndex - 1
            else -> state.currentIndex.coerceAtMost(tracks.lastIndex)
        }
        return DesktopQueueState(tracks, currentIndex)
    }

    fun clearFuture(state: DesktopQueueState): DesktopQueueState {
        val keepCount = (state.currentIndex + 1).coerceIn(0, state.tracks.size)
        val tracks = state.tracks.take(keepCount)
        return DesktopQueueState(tracks, state.currentIndex.coerceIn(-1, (tracks.size - 1).coerceAtLeast(-1)))
    }
}
