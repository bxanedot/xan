package app.xan.shared

import app.xan.music.listentogether.ControlModes
import app.xan.music.listentogether.PlaybackActions
import app.xan.music.listentogether.TrackInfo
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Implemented by the iOS host so resolved streams use the native AVPlayer. */
interface IosNativeAudioPlayer {
    fun playStream(url: String, title: String, artist: String, artworkUrl: String?, startPositionMillis: Long, autoplay: Boolean)
    fun playLocalFile(filePath: String, title: String, artist: String, artworkUrl: String?)
    fun replayCurrent()
    fun updatePlaybackModes(shuffleEnabled: Boolean, repeatMode: String, canChangeModes: Boolean)
    fun schedulePauseAfter(milliseconds: Long, generation: Long)
    fun cancelPauseTimer()
    fun resume()
    fun pause()
    fun seekTo(positionMillis: Long)
}

interface IosNativeAudioDownloader {
    fun downloadAudio(url: String, fileName: String, callback: IosAudioDownloadCallback)
    fun cancel()
    fun deleteDownloadedFile(filePath: String): Boolean
}

interface IosAudioDownloadCallback {
    fun onSuccess(filePath: String)
    fun onFailure(message: String)
}

data class IosMusicDownloadState(
    val trackId: String? = null,
    val title: String? = null,
    val isDownloading: Boolean = false,
    val queuedTrackIds: List<String> = emptyList(),
    val completedCount: Int = 0,
    val message: String? = null,
    val error: Boolean = false,
)

data class IosMusicPlaybackState(
    val videoId: String? = null,
    val title: String? = null,
    val isResolving: Boolean = false,
    val message: String? = null,
    val error: Boolean = false,
    val artist: String? = null,
    val positionMillis: Long = 0L,
    val durationMillis: Long = 0L,
    val isPlaying: Boolean = false,
)

data class IosSleepTimerState(
    val deadlineEpochMillis: Long? = null,
    val remainingMillis: Long? = null,
    val pauseAtTrackEnd: Boolean = false,
) {
    val isActive: Boolean get() = deadlineEpochMillis != null || pauseAtTrackEnd

    fun remainingAt(nowEpochMillis: Long): Long? =
        deadlineEpochMillis?.let { (it - nowEpochMillis).coerceAtLeast(0L) } ?: remainingMillis
}

internal fun isCurrentIosSleepTimerExpiry(
    timer: IosSleepTimerState,
    currentGeneration: Long,
    callbackGeneration: Long,
): Boolean = timer.isActive && currentGeneration == callbackGeneration

internal data class IosRoomPlaybackPlan(
    val trackToResolve: TrackInfo? = null,
    val positionMillis: Long? = null,
    val playing: Boolean? = null,
)

/** Converts room snapshots into only the native player changes they require. */
internal class IosRoomPlaybackPlanner {
    private var trackId: String? = null
    private var positionMillis: Long? = null
    private var playing: Boolean? = null

    fun update(track: TrackInfo, shouldPlay: Boolean, targetPositionMillis: Long, isResolving: Boolean): IosRoomPlaybackPlan {
        val position = targetPositionMillis.coerceAtLeast(0L)
        if (trackId != track.id) {
            trackId = track.id
            positionMillis = position
            playing = shouldPlay
            return IosRoomPlaybackPlan(trackToResolve = track, positionMillis = position, playing = shouldPlay)
        }
        if (isResolving) {
            // Keep the newest room state while a stream request is in flight.
            positionMillis = position
            playing = shouldPlay
            return IosRoomPlaybackPlan()
        }
        val seek = position.takeIf { it != positionMillis }
        val playback = shouldPlay.takeIf { it != playing }
        positionMillis = position
        playing = shouldPlay
        return IosRoomPlaybackPlan(positionMillis = seek, playing = playback)
    }

    fun reset() {
        trackId = null
        positionMillis = null
        playing = null
    }
}

internal fun shouldRouteIosPlayerCommandToRoom(room: IosRoomUiState): Boolean =
    room.roomCode != null && room.status == "In room"

internal fun canControlIosRoomPlayback(room: IosRoomUiState): Boolean =
    room.isHost || room.controlMode == ControlModes.EVERYONE

internal data class IosResolvedAudio(
    val url: String,
    val mimeType: String,
    val bitrate: Int,
)

/** Resolves a playable AAC stream and forwards it to the native iOS audio host. */
object IosMusicPlayback {
    private const val endpoint = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false"
    private const val MAX_DOWNLOAD_QUEUE_SIZE = 200
    private data class PlayerClient(val name: String, val version: String, val id: String, val userAgent: String, val ios: Boolean)
    private val playerClients = listOf(
        PlayerClient("IOS", "21.03.1", "5", "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)", ios = true),
        PlayerClient("WEB_REMIX", "1.20260213.01.00", "67", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_2 like Mac OS X) AppleWebKit/605.1.15 Version/18.2 Mobile/15E148 Safari/604.1", ios = false),
    )
    private val client = newRoomHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var nativePlayer: IosNativeAudioPlayer? = null
    private var nativeExtractor: IosNativeStreamExtractor? = null
    private var nativeDownloader: IosNativeAudioDownloader? = null
    private var resolutionJob: Job? = null
    private var downloadJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var sleepTimerGeneration = 0L
    private val downloadQueue = ArrayDeque<IosMusicSearchTrack>()
    private val streamResolutionMutex = Mutex()
    private val roomPlanner = IosRoomPlaybackPlanner()
    private data class RoomTarget(val track: TrackInfo, val playing: Boolean, val positionMillis: Long, val receivedAtMillis: Long)
    private var roomTarget: RoomTarget? = null
    private var activeRoomTrackId: String? = null
    private val mutableState = MutableStateFlow(IosMusicPlaybackState())
    val state = mutableState.asStateFlow()
    private val mutableDownloadState = MutableStateFlow(IosMusicDownloadState())
    val downloadState = mutableDownloadState.asStateFlow()
    private val mutableSleepTimerState = MutableStateFlow(IosSleepTimerState())
    val sleepTimerState = mutableSleepTimerState.asStateFlow()
    private val mutableQueueState = MutableStateFlow(
        restoredIosPlaybackModes(
            readIosPreference("rememberShuffleAndRepeat"),
            readIosPreference("shuffleMode"),
            readIosPreference("repeatMode"),
        ),
    )
    internal val queueState = mutableQueueState.asStateFlow()

    fun installNativePlayer(player: IosNativeAudioPlayer?) {
        nativePlayer = player
        publishPlaybackModes()
    }

    fun installNativeExtractor(extractor: IosNativeStreamExtractor?) {
        nativeExtractor = extractor
    }

    fun installNativeDownloader(downloader: IosNativeAudioDownloader?) {
        nativeDownloader = downloader
    }

    /** Receives one-second AVPlayer updates so shared UI such as timed lyrics can follow playback. */
    fun updateNativeProgress(positionMillis: Long, durationMillis: Long, playing: Boolean) {
        val current = mutableState.value
        if (current.videoId == null) return
        mutableState.value = current.copy(
            positionMillis = positionMillis.coerceAtLeast(0L),
            durationMillis = durationMillis.coerceAtLeast(0L),
            isPlaying = playing,
        )
        val timer = mutableSleepTimerState.value
        if (timer.pauseAtTrackEnd) {
            mutableSleepTimerState.value = timer.copy(remainingMillis = (durationMillis - positionMillis).coerceAtLeast(0L))
        }
        publishPlaybackModes()
    }

    /** Clears YouTube metadata when the native host switches to an unrelated local or URL source. */
    fun clearNativeTrack() {
        resolutionJob?.cancel()
        mutableState.value = IosMusicPlaybackState()
        mutableQueueState.value = emptyQueueWithModes()
    }

    internal fun download(track: IosMusicSearchTrack) = enqueueDownloads(listOf(track))

    internal fun downloadAll(tracks: List<IosMusicSearchTrack>) = enqueueDownloads(tracks)

    private fun enqueueDownloads(tracks: List<IosMusicSearchTrack>) {
        val downloader = nativeDownloader ?: run {
            mutableDownloadState.value = IosMusicDownloadState(
                message = "The iOS download service is not ready. Reopen XAN and try again.",
                error = true,
            )
            return
        }
        val activeState = mutableDownloadState.value
        val reservedIds = buildSet {
            if (activeState.isDownloading) activeState.trackId?.let(::add)
            downloadQueue.forEach { add(it.videoId) }
        }
        val pending = try {
            selectIosPendingDownloads(
                candidates = tracks,
                downloadedIds = IosMusicLibrary.state.value.downloadedTracks.mapTo(mutableSetOf()) { it.id },
                reservedIds = reservedIds,
                maxQueueSize = MAX_DOWNLOAD_QUEUE_SIZE,
            )
        } catch (failure: IllegalArgumentException) {
            mutableDownloadState.value = activeState.copy(
                queuedTrackIds = downloadQueue.map { it.videoId },
                message = failure.message ?: "The offline download queue is full.",
                error = true,
            )
            return
        }
        if (pending.isEmpty()) {
            if (!activeState.isDownloading) {
                mutableDownloadState.value = activeState.copy(message = "These tracks are already downloaded.", error = false)
            }
            return
        }
        downloadQueue.addAll(pending)
        if (downloadJob?.isActive == true) {
            mutableDownloadState.value = activeState.copy(
                queuedTrackIds = downloadQueue.map { it.videoId },
                message = "Added ${pending.size} ${if (pending.size == 1) "track" else "tracks"} to the offline download queue.",
                error = false,
            )
            return
        }
        downloadJob = scope.launch { runDownloadQueue(downloader) }
    }

    private suspend fun runDownloadQueue(downloader: IosNativeAudioDownloader) {
        var completed = 0
        var failed = 0
        var lastError: String? = null
        var lastTrack: IosMusicSearchTrack? = null
        while (downloadQueue.isNotEmpty()) {
            val track = downloadQueue.removeFirst()
            lastTrack = track
            mutableDownloadState.value = IosMusicDownloadState(
                trackId = track.videoId,
                title = track.title,
                isDownloading = true,
                queuedTrackIds = downloadQueue.map { it.videoId },
                completedCount = completed,
                message = "Preparing ${track.title}…",
            )
            var unindexedFilePath: String? = null
            try {
                val audio = resolveStream(track.videoId)
                mutableDownloadState.value = mutableDownloadState.value.copy(message = "Downloading ${track.title}…")
                val filePath = suspendCancellableCoroutine { continuation ->
                    downloader.downloadAudio(audio.url, iosDownloadFileName(track), object : IosAudioDownloadCallback {
                        override fun onSuccess(filePath: String) {
                            if (continuation.isActive) continuation.resume(filePath)
                        }

                        override fun onFailure(message: String) {
                            if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
                        }
                    })
                    continuation.invokeOnCancellation { downloader.cancel() }
                }
                unindexedFilePath = filePath
                IosMusicLibrary.saveDownloadedTrack(track.toLibraryTrack(), filePath)
                unindexedFilePath = null
                completed += 1
            } catch (cancelled: CancellationException) {
                unindexedFilePath?.let(downloader::deleteDownloadedFile)
                downloadQueue.clear()
                mutableDownloadState.value = IosMusicDownloadState(
                    trackId = track.videoId,
                    title = track.title,
                    completedCount = completed,
                    message = "Offline download cancelled.",
                )
                throw cancelled
            } catch (failure: Throwable) {
                unindexedFilePath?.let(downloader::deleteDownloadedFile)
                failed += 1
                lastError = failure.message ?: "Could not download ${track.title}."
            }
        }
        val summary = when {
            failed == 0 && completed == 1 -> "Saved ${lastTrack?.title ?: "track"} for offline playback."
            failed == 0 -> "Saved $completed tracks for offline playback."
            completed == 0 -> "Downloads failed: ${lastError ?: "check your connection and try again."}"
            else -> "Saved $completed tracks; $failed failed. ${lastError ?: "Check your connection and try again."}"
        }
        mutableDownloadState.value = IosMusicDownloadState(
            trackId = lastTrack?.videoId,
            title = lastTrack?.title,
            completedCount = completed,
            message = summary,
            error = failed > 0,
        )
    }

    /** Pause local or shared playback after the selected number of minutes. */
    fun startSleepTimer(minutes: Int) {
        require(minutes in 5..120 && minutes % 5 == 0) { "Choose a sleep timer between 5 and 120 minutes in 5-minute steps." }
        val generation = resetSleepTimerState()
        val deadline = Clock.System.now().toEpochMilliseconds() + minutes * 60_000L
        mutableSleepTimerState.value = IosSleepTimerState(deadlineEpochMillis = deadline, remainingMillis = minutes * 60_000L)
        nativePlayer?.schedulePauseAfter(minutes * 60_000L, generation)
        sleepTimerJob = scope.launch {
            while (true) {
                val remaining = requireNotNull(mutableSleepTimerState.value.remainingAt(Clock.System.now().toEpochMilliseconds()))
                if (remaining == 0L) {
                    handleNativeSleepTimerExpired(generation)
                    break
                }
                mutableSleepTimerState.value = IosSleepTimerState(deadlineEpochMillis = deadline, remainingMillis = remaining)
                kotlinx.coroutines.delay(minOf(1_000L, remaining))
            }
        }
    }

    /** Pause after the current track finishes, matching Android's end-of-song timer. */
    fun startSleepTimerAtTrackEnd() {
        resetSleepTimerState()
        val playback = mutableState.value
        val remaining = (playback.durationMillis - playback.positionMillis).takeIf { playback.durationMillis > 0L }?.coerceAtLeast(0L)
        mutableSleepTimerState.value = IosSleepTimerState(remainingMillis = remaining, pauseAtTrackEnd = true)
    }

    fun cancelSleepTimer() {
        resetSleepTimerState()
    }

    private fun resetSleepTimerState(): Long {
        sleepTimerGeneration = if (sleepTimerGeneration == Long.MAX_VALUE) 1L else sleepTimerGeneration + 1L
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        nativePlayer?.cancelPauseTimer()
        mutableSleepTimerState.value = IosSleepTimerState()
        return sleepTimerGeneration
    }

    /** Called by the native timer and by the shared countdown as a background-safe fallback. */
    fun handleNativeSleepTimerExpired(generation: Long): Boolean {
        if (!isCurrentIosSleepTimerExpiry(mutableSleepTimerState.value, sleepTimerGeneration, generation)) return false
        resetSleepTimerState()
        pauseForSleepTimer()
        return true
    }

    private fun pauseForSleepTimer() {
        if (shouldRouteIosPlayerCommandToRoom(IosListenTogether.state.value)) {
            routeNativeAction(PlaybackActions.PAUSE)
        }
        nativePlayer?.pause()
    }

    fun playDownloaded(track: IosLibraryTrack) {
        check(IosListenTogether.state.value.roomCode == null) { "Downloaded local files cannot be played for a Listen Together room." }
        val filePath = track.localFilePath ?: error("This track is not downloaded.")
        val player = nativePlayer ?: error("The iOS audio player is not ready. Reopen XAN and try again.")
        roomTarget = null
        activeRoomTrackId = null
        roomPlanner.reset()
        player.playLocalFile(filePath, track.title, track.artist, safeIosArtworkUrl(track.artworkUrl))
        mutableState.value = IosMusicPlaybackState(
            videoId = track.id,
            title = track.title,
            message = "Playing downloaded audio in XAN",
            artist = track.artist,
        )
        mutableQueueState.value = createQueue(listOf(track.toSearchTrack()))
    }

    fun playImportedAudio(track: IosLibraryTrack) {
        check(IosListenTogether.state.value.roomCode == null) { "Imported local audio cannot be played for a Listen Together room." }
        val filePath = track.localFilePath ?: error("This imported track has no local file.")
        val player = nativePlayer ?: error("The iOS audio player is not ready. Reopen XAN and try again.")
        roomTarget = null
        activeRoomTrackId = null
        roomPlanner.reset()
        mutableQueueState.value = emptyQueueWithModes()
        player.playLocalFile(filePath, track.title, track.artist, safeIosArtworkUrl(track.artworkUrl))
        setImportedAudioMetadata(track.id, track.title, track.artist)
    }

    fun setImportedAudioMetadata(trackId: String, title: String, artist: String) {
        mutableState.value = IosMusicPlaybackState(
            videoId = trackId,
            title = title,
            message = "Playing imported audio in XAN",
            artist = artist,
        )
    }

    fun removeDownload(trackId: String): Boolean {
        val track = IosMusicLibrary.state.value.downloadedTracks.firstOrNull { it.id == trackId } ?: return false
        val filePath = track.localFilePath ?: error("The saved download has no local file path.")
        val downloader = nativeDownloader ?: error("The iOS download service is not ready.")
        check(downloader.deleteDownloadedFile(filePath)) { "Could not remove the downloaded audio file." }
        return IosMusicLibrary.removeDownloadedTrack(trackId) != null
    }

    fun removeImportedTrack(trackId: String): Boolean {
        val track = IosMusicLibrary.state.value.importedTracks.firstOrNull { it.id == trackId } ?: return false
        val filePath = track.localFilePath ?: error("The imported audio has no local file path.")
        val downloader = nativeDownloader ?: error("The iOS local audio service is not ready.")
        if (mutableState.value.videoId == trackId) nativePlayer?.pause()
        check(downloader.deleteDownloadedFile(filePath)) { "Could not remove the imported audio file." }
        return IosMusicLibrary.removeImportedTrack(trackId) != null
    }

    fun play(videoId: String, title: String, artist: String, artworkUrl: String? = null) {
        val track = IosMusicSearchTrack(videoId, title, artist, null, null, safeIosArtworkUrl(artworkUrl))
        mutableQueueState.value = createQueue(listOf(track))
        playQueueTrack(track)
    }

    internal fun playQueue(tracks: List<IosMusicSearchTrack>, startIndex: Int = 0) {
        val queue = try {
            createQueue(tracks, startIndex)
        } catch (failure: IllegalArgumentException) {
            mutableQueueState.value = mutableQueueState.value.copy(message = failure.message ?: "Could not create a playback queue.")
            return
        }
        val selectedTrack = queue.currentTrack ?: return
        val room = IosListenTogether.state.value
        if (room.roomCode != null) {
            if (!canControlIosRoomPlayback(room)) return
            IosListenTogether.sendPlayback(PlaybackActions.QUEUE_CLEAR)
            IosListenTogether.sendPlayback(PlaybackActions.CHANGE_TRACK, selectedTrack.toRoomTrackInfo())
            queue.orderedTracks().drop(queue.currentIndex + 1).forEach { track ->
                IosListenTogether.sendPlayback(PlaybackActions.QUEUE_ADD, track.toRoomTrackInfo(), insertNext = false)
            }
            return
        }
        mutableQueueState.value = queue
        playQueueTrack(selectedTrack)
    }

    internal fun enqueueNext(track: IosMusicSearchTrack): Boolean {
        val room = IosListenTogether.state.value
        if (room.roomCode != null) {
            if (!canControlIosRoomPlayback(room)) return false
            IosListenTogether.sendPlayback(PlaybackActions.QUEUE_ADD, track.toRoomTrackInfo(), insertNext = true)
            return true
        }
        return try {
            mutableQueueState.value = enqueueIosPlaybackTrack(mutableQueueState.value, track)
            true
        } catch (failure: IllegalArgumentException) {
            mutableQueueState.value = mutableQueueState.value.copy(message = failure.message ?: "Could not add this track to the queue.")
            false
        }
    }

    fun removeFromQueue(trackId: String) {
        val room = IosListenTogether.state.value
        if (room.roomCode != null) {
            if (canControlIosRoomPlayback(room)) {
                val track = room.queue.firstOrNull { it.id == trackId } ?: return
                IosListenTogether.sendPlayback(PlaybackActions.QUEUE_REMOVE, track)
            }
            return
        }
        val oldQueue = mutableQueueState.value
        val removedCurrent = oldQueue.currentTrack?.videoId == trackId
        val updated = removeIosPlaybackQueueTrack(oldQueue, trackId)
        mutableQueueState.value = updated
        if (removedCurrent) {
            val next = updated.currentTrack
            if (next != null) playQueueTrack(next) else clearNativeTrack()
        }
    }

    fun clearUpcomingQueue() {
        val room = IosListenTogether.state.value
        if (room.roomCode != null) {
            if (canControlIosRoomPlayback(room)) IosListenTogether.sendPlayback(PlaybackActions.QUEUE_CLEAR)
            return
        }
        mutableQueueState.value = clearIosPlaybackQueueAfterCurrent(mutableQueueState.value)
    }

    fun toggleShuffle() = setShuffleEnabled(!mutableQueueState.value.shuffleEnabled)

    fun setShuffleEnabled(enabled: Boolean) {
        if (IosListenTogether.state.value.roomCode != null) return
        mutableQueueState.value = setIosQueueShuffle(mutableQueueState.value, enabled)
        persistPlaybackModes()
        publishPlaybackModes()
    }

    fun cycleRepeatMode() {
        if (IosListenTogether.state.value.roomCode != null) return
        val next = mutableQueueState.value.repeatMode.next()
        mutableQueueState.value = mutableQueueState.value.copy(repeatMode = next, message = "Repeat ${next.label}.")
        persistPlaybackModes()
        publishPlaybackModes()
    }

    fun setRememberShuffleAndRepeat(enabled: Boolean) {
        writeIosPreference("rememberShuffleAndRepeat", enabled.toString())
        if (enabled) persistPlaybackModes(force = true)
        publishPlaybackModes()
    }

    /** Called by AVPlayer when an item reaches its end. */
    fun handleNativeTrackEnded(): Boolean {
        if (mutableSleepTimerState.value.pauseAtTrackEnd) {
            resetSleepTimerState()
            pauseForSleepTimer()
            return true
        }
        val room = IosListenTogether.state.value
        if (shouldRouteIosPlayerCommandToRoom(room)) return routeNativeAction(PlaybackActions.SKIP_NEXT)
        val queue = mutableQueueState.value
        if (queue.currentTrack == null) return false
        if (shouldRepeatIosQueueCurrentAtEnd(queue)) {
            val player = nativePlayer ?: return false
            player.replayCurrent()
            return true
        }
        return advanceLocalQueue(direction = 1)
    }

    fun playQueueItem(trackId: String) {
        val room = IosListenTogether.state.value
        if (room.roomCode != null) {
            if (canControlIosRoomPlayback(room)) {
                room.queue.firstOrNull { it.id == trackId }?.let { track ->
                    IosListenTogether.sendPlayback(PlaybackActions.QUEUE_REMOVE, track)
                    IosListenTogether.sendPlayback(PlaybackActions.CHANGE_TRACK, track)
                }
            }
            return
        }
        val queue = mutableQueueState.value
        val index = queue.playbackPositionOf(trackId)
        if (index < 0) return
        mutableQueueState.value = queue.copy(currentIndex = index, message = null)
        mutableQueueState.value.currentTrack?.let(::playQueueTrack)
    }

    private fun playQueueTrack(track: IosMusicSearchTrack) {
        val pauseAtTransition = mutableSleepTimerState.value.pauseAtTrackEnd
        if (pauseAtTransition) {
            resetSleepTimerState()
            nativePlayer?.pause()
        }
        roomTarget = null
        activeRoomTrackId = null
        roomPlanner.reset()
        resolveAndPlay(track.videoId, track.title, track.artist, track.artworkUrl, startPositionMillis = 0L, autoplay = !pauseAtTransition, roomManaged = false)
    }

    fun syncRoomState(track: TrackInfo, shouldPlay: Boolean, positionMillis: Long) {
        publishPlaybackModes()
        val position = positionMillis.coerceAtLeast(0L)
        roomTarget = RoomTarget(track, shouldPlay, position, iosRoomElapsedMillis())
        val current = mutableState.value
        val isResolvingThisTrack = current.videoId == track.id && current.isResolving
        val plan = roomPlanner.update(track, shouldPlay, position, isResolvingThisTrack)
        if (plan.trackToResolve != null) {
            if (current.videoId == track.id && !current.isResolving && !current.error) {
                // Reuse an already-playing local copy of the same YouTube track.
                activeRoomTrackId = track.id
                applyRoomPlan(plan)
            } else {
                resolveAndPlay(
                    track.id,
                    track.title,
                    track.artist,
                    track.thumbnail,
                    startPositionMillis = plan.positionMillis ?: position,
                    autoplay = plan.playing ?: shouldPlay,
                    roomManaged = true,
                )
            }
            return
        }
        if (isResolvingThisTrack || current.error) return
        activeRoomTrackId = track.id
        applyRoomPlan(plan)
    }

    fun clearRoomSync(stopRoomPlayback: Boolean = false) {
        val resolvingRoomTrack = roomTarget != null && mutableState.value.isResolving
        if (resolvingRoomTrack) {
            resolutionJob?.cancel()
            mutableState.value = IosMusicPlaybackState()
        }
        if (stopRoomPlayback && (activeRoomTrackId != null || roomTarget != null)) nativePlayer?.pause()
        roomTarget = null
        activeRoomTrackId = null
        roomPlanner.reset()
        publishPlaybackModes()
    }

    /** Route lock-screen, headset, and native dock commands through the room
     * server while connected so every client observes the same action. */
    fun routeNativeAction(action: String): Boolean {
        val room = IosListenTogether.state.value
        if (shouldRouteIosPlayerCommandToRoom(room)) {
            if (canControlIosRoomPlayback(room)) IosListenTogether.sendPlayback(action)
            return true
        }
        return when (action) {
            PlaybackActions.SKIP_NEXT -> advanceLocalQueue(direction = 1)
            PlaybackActions.SKIP_PREV -> {
                if (mutableState.value.positionMillis > 3_000L) false else advanceLocalQueue(direction = -1)
            }
            else -> false
        }
    }

    private fun advanceLocalQueue(direction: Int): Boolean {
        val advanced = advanceIosPlaybackQueue(mutableQueueState.value, direction) ?: return false
        val track = advanced.currentTrack ?: return false
        mutableQueueState.value = advanced
        playQueueTrack(track)
        return true
    }

    private fun createQueue(tracks: List<IosMusicSearchTrack>, startIndex: Int = 0): IosPlaybackQueueState {
        val current = mutableQueueState.value
        return createIosPlaybackQueue(tracks, startIndex, current.shuffleEnabled, current.repeatMode)
    }

    private fun emptyQueueWithModes(): IosPlaybackQueueState {
        val current = mutableQueueState.value
        return IosPlaybackQueueState(shuffleEnabled = current.shuffleEnabled, repeatMode = current.repeatMode)
    }

    private fun persistPlaybackModes(force: Boolean = false) {
        if (!force && !rememberPlaybackModes()) return
        val current = mutableQueueState.value
        writeIosPreference("shuffleMode", current.shuffleEnabled.toString())
        writeIosPreference("repeatMode", current.repeatMode.name)
    }

    private fun rememberPlaybackModes(): Boolean =
        readIosPreference("rememberShuffleAndRepeat")?.toBooleanStrictOrNull() ?: true

    private fun publishPlaybackModes() {
        val current = mutableQueueState.value
        nativePlayer?.updatePlaybackModes(
            current.shuffleEnabled,
            current.repeatMode.name,
            IosListenTogether.state.value.roomCode == null,
        )
    }

    fun routeNativeSeek(positionMillis: Long): Boolean {
        val room = IosListenTogether.state.value
        if (!shouldRouteIosPlayerCommandToRoom(room)) return false
        if (canControlIosRoomPlayback(room)) IosListenTogether.seek(positionMillis.toDouble())
        return true
    }

    private fun applyRoomPlan(plan: IosRoomPlaybackPlan) {
        val player = nativePlayer ?: return
        plan.positionMillis?.let(player::seekTo)
        plan.playing?.let { if (it) player.resume() else player.pause() }
    }

    private fun resolveAndPlay(
        videoId: String,
        title: String,
        artist: String,
        artworkUrl: String?,
        startPositionMillis: Long,
        autoplay: Boolean,
        roomManaged: Boolean,
    ) {
        resolutionJob?.cancel()
        resolutionJob = scope.launch {
            mutableState.value = IosMusicPlaybackState(videoId, title, isResolving = true, message = "Preparing audio…", artist = artist)
            try {
                val stream = resolveStream(videoId)
                val player = nativePlayer ?: error("The iOS audio player is not ready. Reopen XAN and try again.")
                val latestRoomTarget = roomTarget?.takeIf { roomManaged && it.track.id == videoId }
                val position = latestRoomTarget?.let {
                    advanceIosRoomPosition(it.positionMillis, it.playing, it.receivedAtMillis, iosRoomElapsedMillis(), it.track.duration)
                } ?: startPositionMillis
                val shouldPlay = latestRoomTarget?.playing ?: autoplay
                player.playStream(stream.url, title, artist, safeIosArtworkUrl(artworkUrl), position, shouldPlay)
                activeRoomTrackId = videoId.takeIf { latestRoomTarget != null }
                mutableState.value = IosMusicPlaybackState(
                    videoId = videoId,
                    title = title,
                    artist = artist,
                    message = if (shouldPlay) "Playing in XAN" else "Ready in XAN",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutableState.value = IosMusicPlaybackState(
                    videoId = videoId,
                    title = title,
                    artist = artist,
                    message = failure.message ?: "XAN could not prepare this track.",
                    error = true,
                )
            }
        }
    }

    internal suspend fun resolveStream(videoId: String): IosResolvedAudio {
        require(videoId.matches(Regex("[A-Za-z0-9_-]{6,20}"))) { "This YouTube Music result has an invalid video ID." }
        return streamResolutionMutex.withLock {
            resolveIosAudioWithFallback(videoId, nativeExtractor) { resolveDirectStream(videoId) }
        }
    }

    private suspend fun resolveDirectStream(videoId: String): IosResolvedAudio {
        var lastFailure: Throwable? = null
        for (profile in playerClients) {
            try {
                return requestPlayer(videoId, profile)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                lastFailure = failure
            }
        }
        throw IllegalStateException(lastFailure?.message ?: "YouTube Music could not prepare this track.", lastFailure)
    }

    private suspend fun requestPlayer(videoId: String, profile: PlayerClient): IosResolvedAudio {
        val request = buildJsonObject {
            put(
                "context",
                iosYouTubeContext(
                    clientName = profile.name,
                    clientVersion = profile.version,
                    iosDevice = profile.ios,
                    dataSyncId = if (profile.name == "WEB_REMIX") iosActiveDataSyncId() else null,
                ),
            )
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val response = client.post(endpoint) {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Goog-Api-Format-Version", "1")
                append("X-YouTube-Client-Name", profile.id)
                append("X-YouTube-Client-Version", profile.version)
                append("Origin", "https://music.youtube.com")
                append("X-Origin", "https://music.youtube.com")
                append("Referer", "https://music.youtube.com/")
                append("User-Agent", profile.userAgent)
                IosCredentialStorage.youtubeCookie()?.let { cookie ->
                    append("Cookie", cookie)
                    if (profile.name == "WEB_REMIX" && iosLoginForBrowseEnabled()) {
                        iosSapisidAuthorization(cookie, IOS_YOUTUBE_MUSIC_ORIGIN)?.let { append("Authorization", it) }
                    }
                }
            }
            setBody(request.toString())
        }
        require(response.status.value in 200..299) { "YouTube Music playback returned HTTP ${response.status.value}." }
        return parseIosPlayerResponse(response.bodyAsText())
    }
}

internal fun restoredIosPlaybackModes(
    rememberShuffleAndRepeat: String?,
    shuffleMode: String?,
    repeatMode: String?,
): IosPlaybackQueueState {
    val remember = rememberShuffleAndRepeat?.toBooleanStrictOrNull() ?: true
    return IosPlaybackQueueState(
        shuffleEnabled = remember && (shuffleMode?.toBooleanStrictOrNull() ?: false),
        repeatMode = if (remember) IosRepeatMode.fromPreference(repeatMode) else IosRepeatMode.OFF,
    )
}

internal fun iosDownloadFileName(track: IosMusicSearchTrack): String {
    val metadata = listOf(track.artist, track.title)
        .filter(String::isNotBlank)
        .joinToString(" - ")
        .replace(Regex("[^A-Za-z0-9._ -]"), "_")
        .trim()
        .take(100)
        .ifBlank { "XAN audio" }
    return "$metadata-${track.videoId}.m4a"
}

internal fun selectIosPendingDownloads(
    candidates: List<IosMusicSearchTrack>,
    downloadedIds: Set<String>,
    reservedIds: Set<String>,
    maxQueueSize: Int,
): List<IosMusicSearchTrack> {
    require(maxQueueSize > 0) { "The offline download queue limit must be positive." }
    val pending = candidates.distinctBy { it.videoId }
        .filterNot { it.videoId in downloadedIds || it.videoId in reservedIds }
    require(reservedIds.size + pending.size <= maxQueueSize) {
        "XAN can queue up to $maxQueueSize tracks at once."
    }
    return pending
}

internal fun safeIosArtworkUrl(raw: String?): String? {
    val candidate = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 4096 } ?: return null
    val parsed = runCatching { io.ktor.http.Url(candidate) }.getOrNull() ?: return null
    if (parsed.protocol.name != "https") return null
    val host = parsed.host.lowercase()
    val trustedImageHost = host == "i.ytimg.com" ||
        host == "googleusercontent.com" || host.endsWith(".googleusercontent.com") ||
        host == "ggpht.com" || host.endsWith(".ggpht.com")
    return candidate.takeIf { trustedImageHost }
}

internal fun parseIosPlayerResponse(payload: String): IosResolvedAudio {
    val root = Json.parseToJsonElement(payload) as? JsonObject
        ?: error("YouTube Music returned an invalid playback response.")
    val playability = root["playabilityStatus"] as? JsonObject
    val status = playability?.get("status")?.jsonPrimitive?.contentOrNull
    if (status != null && status != "OK") {
        val reason = playability["reason"]?.jsonPrimitive?.contentOrNull
        error(reason ?: "YouTube Music cannot play this track right now ($status).")
    }
    val formats = root.nested("streamingData", "adaptiveFormats")?.asJsonArray().orEmpty()
    val playable = formats.mapNotNull { element ->
        val format = element.asJsonObject() ?: return@mapNotNull null
        val mime = format.text("mimeType")?.substringBefore(';')?.trim()?.lowercase() ?: return@mapNotNull null
        if (mime != "audio/mp4") return@mapNotNull null
        val url = format.text("url")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
        val safe = safeIosAudioStreamUrl(url) ?: return@mapNotNull null
        val bitrate = format.text("bitrate")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        IosResolvedAudio(safe, mime, bitrate)
    }
    return playable.maxByOrNull(IosResolvedAudio::bitrate)
        ?: error("YouTube Music returned no direct AAC stream for this track.")
}

private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.nested(vararg path: String): JsonElement? {
    var current: JsonElement? = this
    for (key in path) current = (current as? JsonObject)?.get(key) ?: return null
    return current
}

private fun JsonElement?.asJsonArray(): JsonArray? = this as? JsonArray
private fun JsonElement?.asJsonObject(): JsonObject? = this as? JsonObject
