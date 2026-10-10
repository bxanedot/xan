package app.xan.shared

internal enum class IosRepeatMode {
    OFF,
    ALL,
    ONE;

    val label: String get() = when (this) {
        OFF -> "off"
        ALL -> "all"
        ONE -> "one"
    }

    fun next(): IosRepeatMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromPreference(value: String?): IosRepeatMode = entries.firstOrNull { it.name == value } ?: OFF
    }
}

internal data class IosPlaybackQueueState(
    val tracks: List<IosMusicSearchTrack> = emptyList(),
    val currentIndex: Int = -1,
    val message: String? = null,
    val playOrder: List<Int> = tracks.indices.toList(),
    val shuffleEnabled: Boolean = false,
    val repeatMode: IosRepeatMode = IosRepeatMode.OFF,
) {
    val currentTrack: IosMusicSearchTrack?
        get() = playOrder.getOrNull(currentIndex)?.let(tracks::getOrNull)

    val upcomingCount: Int
        get() = (playOrder.size - currentIndex - 1).coerceAtLeast(0)

    fun playbackPositionOf(trackId: String): Int =
        playOrder.indexOfFirst { tracks.getOrNull(it)?.videoId == trackId }

    fun orderedTracks(): List<IosMusicSearchTrack> = playOrder.mapNotNull(tracks::getOrNull)
}

private const val MAX_IOS_PLAYBACK_QUEUE_SIZE = 200
private val IOS_PLAYBACK_VIDEO_ID = Regex("[A-Za-z0-9_-]{6,20}")

internal fun createIosPlaybackQueue(
    tracks: List<IosMusicSearchTrack>,
    startIndex: Int = 0,
    shuffleEnabled: Boolean = false,
    repeatMode: IosRepeatMode = IosRepeatMode.OFF,
): IosPlaybackQueueState {
    require(tracks.size <= MAX_IOS_PLAYBACK_QUEUE_SIZE) { "Playback queues can contain up to $MAX_IOS_PLAYBACK_QUEUE_SIZE tracks." }
    if (tracks.isEmpty()) return IosPlaybackQueueState(shuffleEnabled = shuffleEnabled, repeatMode = repeatMode)
    require(startIndex in tracks.indices) { "Choose a track in this playback queue." }
    require(tracks.all { it.videoId.matches(IOS_PLAYBACK_VIDEO_ID) }) { "This queue contains an invalid YouTube video ID." }
    val unique = tracks.distinctBy(IosMusicSearchTrack::videoId)
    val selectedIndex = unique.indexOfFirst { it.videoId == tracks[startIndex].videoId }
    val order = if (shuffleEnabled) {
        listOf(selectedIndex) + unique.indices.filterNot { it == selectedIndex }.shuffled()
    } else {
        unique.indices.toList()
    }
    return IosPlaybackQueueState(
        tracks = unique,
        currentIndex = if (shuffleEnabled) 0 else selectedIndex,
        playOrder = order,
        shuffleEnabled = shuffleEnabled,
        repeatMode = repeatMode,
    )
}

internal fun enqueueIosPlaybackTrack(
    state: IosPlaybackQueueState,
    track: IosMusicSearchTrack,
    insertNext: Boolean = true,
): IosPlaybackQueueState {
    require(track.videoId.matches(IOS_PLAYBACK_VIDEO_ID)) { "This YouTube video ID is invalid." }
    if (state.tracks.any { it.videoId == track.videoId }) {
        return state.copy(message = "${track.title} is already in the queue.")
    }
    require(state.tracks.size < MAX_IOS_PLAYBACK_QUEUE_SIZE) {
        "The playback queue is full. Remove a track before adding another."
    }
    val updatedTracks = state.tracks + track
    val newTrackIndex = updatedTracks.lastIndex
    val insertionIndex = if (insertNext && state.currentIndex >= 0) {
        (state.currentIndex + 1).coerceAtMost(state.playOrder.size)
    } else {
        state.playOrder.size
    }
    val order = state.playOrder.toMutableList().apply { add(insertionIndex, newTrackIndex) }
    val currentIndex = when {
        state.currentIndex < 0 -> -1
        insertionIndex <= state.currentIndex -> state.currentIndex + 1
        else -> state.currentIndex
    }
    return state.copy(
        tracks = updatedTracks,
        playOrder = order,
        currentIndex = currentIndex,
        message = "Added ${track.title} ${if (insertNext) "to play next" else "to the queue"}.",
    )
}

internal fun setIosQueueShuffle(
    state: IosPlaybackQueueState,
    enabled: Boolean,
): IosPlaybackQueueState {
    if (state.shuffleEnabled == enabled) return state
    if (state.tracks.isEmpty()) return state.copy(shuffleEnabled = enabled, message = if (enabled) "Shuffle enabled." else "Shuffle disabled.")

    val currentTrackIndex = state.playOrder.getOrNull(state.currentIndex)
    val order = if (enabled) {
        if (currentTrackIndex == null) {
            state.playOrder.shuffled()
        } else {
            val played = state.playOrder.take(state.currentIndex + 1)
            played + state.playOrder.drop(state.currentIndex + 1).shuffled()
        }
    } else {
        state.tracks.indices.toList()
    }
    val nextCurrentIndex = when {
        currentTrackIndex == null -> -1
        enabled -> order.indexOf(currentTrackIndex)
        else -> order.indexOf(currentTrackIndex)
    }
    return state.copy(
        playOrder = order,
        currentIndex = nextCurrentIndex,
        shuffleEnabled = enabled,
        message = if (enabled) "Shuffle enabled." else "Shuffle disabled.",
    )
}

internal fun advanceIosPlaybackQueue(state: IosPlaybackQueueState, direction: Int): IosPlaybackQueueState? {
    require(direction == -1 || direction == 1) { "Queue navigation must move one track at a time." }
    if (state.playOrder.isEmpty()) return null
    val targetIndex = if (direction > 0) {
        when {
            state.currentIndex < 0 -> 0
            state.currentIndex + 1 < state.playOrder.size -> state.currentIndex + 1
            state.repeatMode == IosRepeatMode.ALL -> 0
            else -> return null
        }
    } else {
        when {
            state.currentIndex > 0 -> state.currentIndex - 1
            state.repeatMode == IosRepeatMode.ALL -> state.playOrder.lastIndex
            else -> return null
        }
    }
    return state.copy(currentIndex = targetIndex, message = null)
}

internal fun shouldRepeatIosQueueCurrentAtEnd(state: IosPlaybackQueueState): Boolean =
    state.currentTrack != null &&
        (state.repeatMode == IosRepeatMode.ONE || (state.repeatMode == IosRepeatMode.ALL && state.playOrder.size == 1))

internal fun removeIosPlaybackQueueTrack(state: IosPlaybackQueueState, trackId: String): IosPlaybackQueueState {
    val removedTrackIndex = state.tracks.indexOfFirst { it.videoId == trackId }
    if (removedTrackIndex < 0) return state
    val removedOrderPosition = state.playOrder.indexOf(removedTrackIndex)
    val currentTrackId = state.currentTrack?.videoId
    val remainingTracks = state.tracks.toMutableList().apply { removeAt(removedTrackIndex) }
    val remainingOrder = state.playOrder
        .filterNot { it == removedTrackIndex }
        .map { if (it > removedTrackIndex) it - 1 else it }
    val currentIndex = when {
        remainingTracks.isEmpty() || state.currentIndex < 0 -> -1
        currentTrackId == trackId -> removedOrderPosition.coerceAtMost(remainingOrder.lastIndex)
        else -> remainingOrder.indexOfFirst { remainingTracks.getOrNull(it)?.videoId == currentTrackId }
    }
    return state.copy(
        tracks = remainingTracks,
        playOrder = remainingOrder,
        currentIndex = currentIndex,
        message = "Removed from the queue.",
    )
}

internal fun clearIosPlaybackQueueAfterCurrent(state: IosPlaybackQueueState): IosPlaybackQueueState {
    val current = state.currentTrack ?: return IosPlaybackQueueState(
        message = "Queue cleared.",
        shuffleEnabled = state.shuffleEnabled,
        repeatMode = state.repeatMode,
    )
    return IosPlaybackQueueState(
        tracks = listOf(current),
        currentIndex = 0,
        message = "Removed upcoming tracks.",
        playOrder = listOf(0),
        shuffleEnabled = state.shuffleEnabled,
        repeatMode = state.repeatMode,
    )
}
