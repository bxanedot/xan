package app.xan.desktop

import javafx.application.Platform
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

internal enum class PlaybackStatus { IDLE, RESOLVING, BUFFERING, PLAYING, PAUSED, ERROR }
internal enum class RepeatMode { OFF, ALL, ONE }

internal data class PlaybackSnapshot(
    val track: MusicTrack? = null,
    val playbackSessionId: Long = 0,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val volume: Float = 0.8f,
    val audioSpectrum: List<Float> = emptyList(),
    val queue: List<MusicTrack> = emptyList(),
    val queueIndex: Int = -1,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val listenedMillis: Long = 0,
    val startedAtEpochSeconds: Long? = null,
    val sleepTimerDeadlineEpochMillis: Long? = null,
    val sleepTimerRemainingMillis: Long? = null,
    val sleepTimerPauseAtTrackEnd: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
)

/** One app-wide playback pipeline for local files and YouTube audio streams. */
internal object DesktopPlayer {
    private val _state = MutableStateFlow(PlaybackSnapshot(volume = DesktopBackend.playerVolume()))
    val state: StateFlow<PlaybackSnapshot> = _state.asStateFlow()
    private val fxStarted = CompletableFuture<Unit>()
    private val requests = AtomicLong(0)
    private val fallbackAttempts = ConcurrentHashMap.newKeySet<Long>()
    private val videoViews = ConcurrentHashMap.newKeySet<MediaView>()
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sleepTimerLock = Any()
    private var sleepTimerJob: Job? = null
    private val sleepTimerGeneration = AtomicLong(0)
    @Volatile private var toolkitStartRequested = false
    @Volatile private var mediaPlayer: MediaPlayer? = null
    @Volatile private var externalLastSampleMillis = 0L

    fun attachVideoView(view: MediaView, playbackSessionId: Long) {
        runOnFxAsync {
            videoViews += view
            val snapshot = _state.value
            view.mediaPlayer = mediaPlayer.takeIf { snapshot.playbackSessionId == playbackSessionId && snapshot.track?.kind == "video" }
        }
    }

    fun detachVideoView(view: MediaView) {
        runOnFxAsync {
            videoViews -= view
            view.mediaPlayer = null
        }
    }

    suspend fun play(track: MusicTrack) = playQueue(listOf(track), 0)

    suspend fun playQueue(
        tracks: List<MusicTrack>,
        startIndex: Int = 0,
        resolveTrackUri: suspend (MusicTrack) -> java.net.URI = DesktopBackend::resolveTrackUri,
    ) {
        require(tracks.isNotEmpty()) { "There are no songs to play." }
        require(startIndex in tracks.indices)
        _state.update { current -> current.copy(queue = tracks.toList(), queueIndex = startIndex) }
        startAt(startIndex, resolveTrackUri)
    }

    suspend fun next() {
        val snapshot = _state.value
        when {
            snapshot.repeatMode == RepeatMode.ONE && snapshot.queueIndex in snapshot.queue.indices -> startAt(snapshot.queueIndex)
            snapshot.queueIndex + 1 in snapshot.queue.indices -> startAt(snapshot.queueIndex + 1)
            snapshot.repeatMode == RepeatMode.ALL && snapshot.queue.isNotEmpty() -> startAt(0)
        }
        if (snapshot.repeatMode == RepeatMode.OFF && snapshot.queueIndex + 1 !in snapshot.queue.indices) stop()
    }

    suspend fun previous() {
        val snapshot = _state.value
        if (snapshot.positionMillis > 5_000) seekTo(0)
        else if (snapshot.queueIndex > 0) startAt(snapshot.queueIndex - 1)
        else seekTo(0)
    }

    fun enqueueNext(track: MusicTrack) {
        val snapshot = _state.value
        if (snapshot.track == null || snapshot.status in setOf(PlaybackStatus.IDLE, PlaybackStatus.ERROR)) {
            callbackScope.launch { play(track) }
            return
        }
        val insertionIndex = (snapshot.queueIndex + 1).coerceIn(0, snapshot.queue.size)
        val queue = snapshot.queue.toMutableList().apply { add(insertionIndex, track) }
        _state.update { current -> current.copy(queue = queue, notice = "Added to the queue: ${track.title}") }
    }

    suspend fun playQueuePosition(index: Int) {
        val snapshot = _state.value
        if (index in snapshot.queue.indices) playQueue(snapshot.queue, index)
    }

    fun moveQueueItem(from: Int, to: Int): Boolean {
        val snapshot = _state.value
        if (from !in snapshot.queue.indices || to !in snapshot.queue.indices || from == to) return false
        val queue = snapshot.queue.toMutableList()
        val moved = queue.removeAt(from)
        queue.add(to, moved)
        val currentIndex = when {
            snapshot.queueIndex == from -> to
            from < snapshot.queueIndex && to >= snapshot.queueIndex -> snapshot.queueIndex - 1
            from > snapshot.queueIndex && to <= snapshot.queueIndex -> snapshot.queueIndex + 1
            else -> snapshot.queueIndex
        }
        _state.update { current -> current.copy(queue = queue, queueIndex = currentIndex) }
        return true
    }

    suspend fun removeQueueItem(index: Int): Boolean {
        val snapshot = _state.value
        if (index !in snapshot.queue.indices) return false
        val queue = snapshot.queue.toMutableList().apply { removeAt(index) }
        if (index == snapshot.queueIndex) {
            if (queue.isEmpty()) {
                stop()
                _state.update { current -> current.copy(queue = emptyList(), queueIndex = -1) }
            } else {
                val nextIndex = index.coerceAtMost(queue.lastIndex)
                _state.update { current -> current.copy(queue = queue, queueIndex = nextIndex) }
                startAt(nextIndex)
            }
        } else {
            _state.update { current -> current.copy(queue = queue, queueIndex = if (index < snapshot.queueIndex) snapshot.queueIndex - 1 else snapshot.queueIndex) }
        }
        return true
    }

    suspend fun shuffle(tracks: List<MusicTrack> = _state.value.queue) {
        if (tracks.isEmpty()) {
            _state.update { current -> current.copy(notice = "Add songs to your library or queue before using Shuffle.") }
            return
        }
        val shuffled = tracks.shuffled()
        playQueue(shuffled, 0)
    }

    fun cycleRepeatMode(): RepeatMode {
        val next = when (_state.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        _state.update { current -> current.copy(repeatMode = next) }
        return next
    }

    fun startSleepTimer(minutes: Int) {
        require(isValidDesktopSleepTimerMinutes(minutes)) { "Choose a sleep timer between 5 and 120 minutes in 5-minute steps." }
        val durationMillis = minutes * 60_000L
        val deadline = System.currentTimeMillis() + durationMillis
        val deadlineNanos = System.nanoTime() + durationMillis * 1_000_000L
        synchronized(sleepTimerLock) {
            val generation = clearSleepTimerStateLocked()
            _state.update { current -> current.copy(sleepTimerDeadlineEpochMillis = deadline, sleepTimerRemainingMillis = durationMillis) }
            sleepTimerJob = callbackScope.launch {
                while (generation == sleepTimerGeneration.get()) {
                    val remaining = desktopSleepTimerRemainingMillis(deadlineNanos, System.nanoTime())
                    if (remaining == 0L) {
                        handleSleepTimerExpired(generation)
                        break
                    }
                    val stillCurrent = synchronized(sleepTimerLock) {
                        if (generation != sleepTimerGeneration.get()) false
                        else {
                            _state.update { current -> current.copy(sleepTimerDeadlineEpochMillis = deadline, sleepTimerRemainingMillis = remaining) }
                            true
                        }
                    }
                    if (!stillCurrent) break
                    kotlinx.coroutines.delay(minOf(1_000L, remaining))
                }
            }
        }
    }

    fun startSleepTimerAtTrackEnd() {
        if (DesktopCast.state.value.connectedName != null) {
            setNotice("Disconnect from Cast to use the end-of-track sleep timer.")
            return
        }
        val alreadyAtEnd = synchronized(sleepTimerLock) {
            clearSleepTimerStateLocked()
            val snapshot = _state.value
            val remaining = (snapshot.durationMillis - snapshot.positionMillis).takeIf { snapshot.durationMillis > 0L }?.coerceAtLeast(0L)
            if (remaining == 0L) true
            else {
                _state.update { current -> current.copy(sleepTimerPauseAtTrackEnd = true, sleepTimerRemainingMillis = remaining) }
                false
            }
        }
        if (alreadyAtEnd) pauseForSleepTimer()
    }

    fun cancelSleepTimer() {
        synchronized(sleepTimerLock) { clearSleepTimerStateLocked() }
    }

    fun onCastPlaybackStarted() {
        val canceled = synchronized(sleepTimerLock) {
            if (!_state.value.sleepTimerPauseAtTrackEnd) false
            else {
                clearSleepTimerStateLocked()
                true
            }
        }
        if (canceled) setNotice("The end-of-track sleep timer was cleared because playback moved to Cast.")
    }

    /** Invalidates callbacks before clearing state, so a racing expiry cannot cancel a replacement timer. */
    private fun clearSleepTimerStateLocked(): Long {
        val generation = sleepTimerGeneration.updateAndGet { if (it == Long.MAX_VALUE) 1L else it + 1L }
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _state.update { current -> current.copy(
            sleepTimerDeadlineEpochMillis = null,
            sleepTimerRemainingMillis = null,
            sleepTimerPauseAtTrackEnd = false,
        ) }
        return generation
    }

    private fun handleSleepTimerExpired(generation: Long): Boolean {
        val expired = synchronized(sleepTimerLock) {
            if (!isCurrentDesktopSleepTimerExpiry(_state.value.sleepTimerDeadlineEpochMillis != null, sleepTimerGeneration.get(), generation)) false
            else {
                clearSleepTimerStateLocked()
                true
            }
        }
        if (expired) pauseForSleepTimer()
        return expired
    }

    private fun pauseForSleepTimer() {
        DesktopListenTogether.publishPlayback(app.xan.music.listentogether.PlaybackActions.PAUSE)
        if (DesktopCast.state.value.connectedName != null) {
            callbackScope.launch {
                runCatching { DesktopCast.pause() }
                    .onFailure { setNotice("Could not pause Cast playback when the sleep timer ended: ${it.message ?: "receiver unavailable"}") }
            }
        } else setPlaying(false)
    }

    private fun pauseForSleepTimerAtTrackEnd(): Boolean {
        val shouldPause = synchronized(sleepTimerLock) {
            if (!_state.value.sleepTimerPauseAtTrackEnd) false
            else {
                clearSleepTimerStateLocked()
                true
            }
        }
        if (shouldPause) pauseForSleepTimer()
        return shouldPause
    }

    internal fun setRepeatModeForTest(mode: RepeatMode) {
        _state.update { current -> current.copy(repeatMode = mode) }
    }

    suspend fun stop() {
        requests.incrementAndGet()
        if (!toolkitStartRequested) {
            _state.update { current -> current.copy(status = PlaybackStatus.IDLE, positionMillis = 0, audioSpectrum = emptyList(), error = null) }
            return
        }
        runOnFx {
            videoViews.forEach { it.mediaPlayer = null }
            mediaPlayer?.stop()
            mediaPlayer?.dispose()
            mediaPlayer = null
            _state.update { current -> current.copy(status = PlaybackStatus.IDLE, positionMillis = 0, audioSpectrum = emptyList(), error = null) }
        }
    }

    fun shutdown() {
        cancelSleepTimer()
        requests.incrementAndGet()
        if (!toolkitStartRequested) return

        // Do not let the process/test worker exit while JavaFX still owns the media player.
        // In particular, stop/dispose may still be using a local file that the caller removes
        // as soon as shutdown returns.
        if (Platform.isFxApplicationThread()) {
            runCatching { shutdownOnFxThread() }
                .onFailure { logShutdownFailure(it) }
            return
        }

        val complete = CompletableFuture<Unit>()
        try {
            fxStarted.get(FX_START_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            Platform.runLater {
                runCatching { shutdownOnFxThread() }
                    .fold(complete::complete) { complete.completeExceptionally(it) }
            }
            complete.get(FX_ACTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            logShutdownFailure(interrupted)
        } catch (failure: Throwable) {
            logShutdownFailure(failure)
        }
    }

    private fun shutdownOnFxThread() {
        var cleanupFailure: Throwable? = null
        fun attempt(action: () -> Unit) {
            runCatching(action).onFailure { failure ->
                val previousFailure = cleanupFailure
                if (previousFailure == null) cleanupFailure = failure
                else previousFailure.addSuppressed(failure)
            }
        }
        attempt {
            videoViews.forEach { it.mediaPlayer = null }
            videoViews.clear()
        }

        val player = mediaPlayer
        try {
            attempt { player?.stop() }
            attempt { player?.dispose() }
        } finally {
            mediaPlayer = null
            attempt { Platform.exit() }
        }
        cleanupFailure?.let { throw it }
    }

    private fun logShutdownFailure(failure: Throwable) {
        runCatching {
            DiagnosticLog.warn(
                DesktopBackend.diagnosticsDirectory(),
                "desktop_media_shutdown_failed",
                "The desktop media engine did not finish shutting down cleanly.",
                failure,
            )
        }
    }

    fun togglePlayPause() {
        val current = mediaPlayer ?: return
        runOnFxAsync {
            if (current.status == MediaPlayer.Status.PLAYING || current.status == MediaPlayer.Status.STALLED) current.pause()
            else current.play()
        }
    }

    fun setPlaying(playing: Boolean) {
        val current = mediaPlayer ?: return
        runOnFxAsync {
            if (playing && current.status != MediaPlayer.Status.PLAYING) current.play()
            else if (!playing && current.status == MediaPlayer.Status.PLAYING) current.pause()
        }
    }

    fun pauseLocalForCast() {
        mediaPlayer?.let { player -> runOnFxAsync { player.pause() } }
    }

    fun setExternalTrack(track: MusicTrack, queueIndex: Int? = null) {
        val snapshot = _state.value
        val identity: (MusicTrack) -> String = { it.videoId ?: it.localPath ?: "${it.title}:${it.artist}" }
        val index = queueIndex?.takeIf { it in snapshot.queue.indices && identity(snapshot.queue[it]) == identity(track) }
            ?: snapshot.queue.indexOfFirst { identity(it) == identity(track) }
        val queue = if (index >= 0) snapshot.queue else snapshot.queue + track
        _state.update { current -> current.copy(
            track = track,
            playbackSessionId = current.playbackSessionId + 1,
            status = PlaybackStatus.PLAYING,
            positionMillis = 0,
            durationMillis = track.durationSeconds?.times(1000L) ?: 0L,
            queue = queue,
            queueIndex = if (index >= 0) index else queue.lastIndex,
            listenedMillis = 0,
            startedAtEpochSeconds = System.currentTimeMillis() / 1000,
            notice = null,
            error = null,
        ) }
        externalLastSampleMillis = System.currentTimeMillis()
    }

    fun setQueueForRoomSync(tracks: List<MusicTrack>) {
        val snapshot = _state.value
        val currentId = snapshot.track?.videoId
        val currentIndex = tracks.indexOfFirst { it.videoId == currentId }
        _state.update { current -> current.copy(
            queue = tracks,
            queueIndex = when {
                tracks.isEmpty() -> -1
                currentIndex >= 0 -> currentIndex
                else -> snapshot.queueIndex.coerceIn(0, tracks.lastIndex)
            },
        ) }
    }

    fun addRoomQueueItem(track: MusicTrack, insertNext: Boolean) {
        val snapshot = _state.value
        val updated = DesktopQueueOperations.add(DesktopQueueState(snapshot.queue, snapshot.queueIndex), track, insertNext)
        _state.update { current -> current.copy(queue = updated.tracks, queueIndex = updated.currentIndex) }
    }

    fun removeRoomQueueItem(videoId: String) {
        val snapshot = _state.value
        val updated = DesktopQueueOperations.removeFuture(DesktopQueueState(snapshot.queue, snapshot.queueIndex), videoId)
        _state.update { current -> current.copy(queue = updated.tracks, queueIndex = updated.currentIndex) }
    }

    fun clearRoomQueueAfterCurrent() {
        val snapshot = _state.value
        val updated = DesktopQueueOperations.clearFuture(DesktopQueueState(snapshot.queue, snapshot.queueIndex))
        _state.update { current -> current.copy(queue = updated.tracks, queueIndex = updated.currentIndex) }
    }

    fun updateExternalPlayback(isPlaying: Boolean, positionMillis: Long, durationMillis: Long) {
        val snapshot = _state.value
        if (snapshot.track == null) return
        val now = System.currentTimeMillis()
        val elapsed = if (isPlaying && snapshot.status == PlaybackStatus.PLAYING && externalLastSampleMillis > 0) {
            (now - externalLastSampleMillis).coerceIn(0, 2_000)
        } else 0L
        externalLastSampleMillis = now
        _state.update { current -> current.copy(
            status = if (isPlaying) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED,
            positionMillis = positionMillis.coerceAtLeast(0),
            durationMillis = durationMillis.coerceAtLeast(0),
            listenedMillis = current.listenedMillis + elapsed,
        ) }
    }

    fun clearExternalPlayback() {
        externalLastSampleMillis = 0
        val snapshot = _state.value
        if (snapshot.track != null && snapshot.status != PlaybackStatus.IDLE) {
            _state.update { current -> current.copy(status = PlaybackStatus.PAUSED) }
        }
    }

    fun seekTo(positionMillis: Long) {
        val player = mediaPlayer ?: return
        val safe = positionMillis.coerceIn(0, _state.value.durationMillis.coerceAtLeast(0))
        runOnFxAsync { player.seek(javafx.util.Duration.millis(safe.toDouble())) }
    }

    fun setVolume(volume: Float) {
        val safe = volume.coerceIn(0f, 1f)
        DesktopBackend.setPlayerVolume(safe)
        _state.update { current -> current.copy(volume = safe) }
        mediaPlayer?.let { player -> runOnFxAsync {
            if (mediaPlayer === player) applyAudioSettings(player, DesktopBackend.equalizerSettings())
        } }
    }

    fun setEqualizerSettings(settings: EqualizerSettings) {
        DesktopBackend.setEqualizerSettings(settings)
        mediaPlayer?.let { player ->
            runOnFxAsync {
                runCatching { if (mediaPlayer === player) applyAudioSettings(player, settings) }
                    .onFailure { _state.update { current -> current.copy(notice = "Equalizer could not be applied: ${it.message ?: "audio engine error"}") } }
            }
        }
    }

    suspend fun equalizerRuntimeSnapshot(): EqualizerRuntimeSnapshot? {
        val player = mediaPlayer ?: return null
        val complete = CompletableFuture<EqualizerRuntimeSnapshot?>()
        runOnFxAsync { complete.complete(if (mediaPlayer === player) XanEqualizer.runtimeSnapshot(player) else null) }
        return withContext(Dispatchers.IO) { complete.get(FX_ACTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
    }

    fun setNotice(message: String?) {
        _state.update { current -> current.copy(notice = message) }
    }

    private fun applyAudioSettings(player: MediaPlayer, settings: EqualizerSettings) {
        XanEqualizer.apply(player, settings, _state.value.volume.toDouble())
        applyFadeVolume(player, settings)
    }

    private fun applyFadeVolume(player: MediaPlayer, settings: EqualizerSettings = DesktopBackend.equalizerSettings()) {
        val seconds = if (_state.value.track?.kind == "video") 0 else DesktopBackend.djTransitionSeconds()
        val gain = desktopTrackFadeGain(player.currentTime.toMillis(), player.totalDuration.toMillis(), seconds)
        player.volume = XanEqualizer.effectiveVolume(_state.value.volume.toDouble(), settings) * gain
    }

    private suspend fun startAt(
        index: Int,
        resolveTrackUri: suspend (MusicTrack) -> java.net.URI = DesktopBackend::resolveTrackUri,
    ) {
        externalLastSampleMillis = 0
        val pauseAtTransition = synchronized(sleepTimerLock) {
            val shouldPause = _state.value.sleepTimerPauseAtTrackEnd && _state.value.track != null
            if (shouldPause) clearSleepTimerStateLocked()
            shouldPause
        }
        val request = requests.incrementAndGet()
        val previousPlayer = mediaPlayer
        if (previousPlayer != null) runOnFxAsync {
            if (mediaPlayer === previousPlayer && requests.get() == request) {
                runCatching { previousPlayer.pause() }
            }
        }
        val track = _state.value.queue.getOrNull(index) ?: return
        _state.update { current -> current.copy(
            track = track, playbackSessionId = request, queueIndex = index, positionMillis = 0, listenedMillis = 0, startedAtEpochSeconds = null,
            durationMillis = track.durationSeconds?.times(1000L) ?: 0,
            status = PlaybackStatus.RESOLVING, audioSpectrum = emptyList(), error = null, notice = null,
        ) }
        val uri = runCatching {
            val timeout = if (track.kind == "video") DesktopBackend.VIDEO_PREPARATION_TIMEOUT_MILLIS else STREAM_RESOLVE_TIMEOUT_MILLIS
            withTimeout(timeout) { resolveTrackUri(track) }
        }.getOrElse { failure ->
            if (failure is CancellationException && failure !is kotlinx.coroutines.TimeoutCancellationException) throw failure
            DiagnosticLog.warn(DesktopBackend.diagnosticsDirectory(), "playback_resolve_failed", "Could not resolve the selected audio source.", failure, mapOf("source" to (track.source ?: "unknown")))
            if (requests.get() == request) _state.update { current -> current.copy(status = PlaybackStatus.ERROR, error = failure.message ?: "Could not open this audio source.") }
            return
        }
        if (requests.get() != request) return
        startToolkit()
        val startupFailure = runCatching { awaitFxStarted() }.exceptionOrNull()
        if (startupFailure != null) {
            DiagnosticLog.warn(DesktopBackend.diagnosticsDirectory(), "playback_toolkit_start_failed", "The desktop audio engine did not start.", startupFailure)
            if (requests.get() == request) _state.update { current -> current.copy(
                status = PlaybackStatus.ERROR,
                error = "The desktop audio engine could not start: ${startupFailure.message ?: "startup timed out"}",
            ) }
            return
        }
        val configured = CompletableFuture<Unit>()
        runOnFxAsync {
            try {
                if (requests.get() == request) configurePlayer(uri, track, request, autoplay = !pauseAtTransition)
                configured.complete(Unit)
            } catch (failure: Throwable) {
                configured.completeExceptionally(failure)
            }
        }
        val setupFailure = runCatching {
            withContext(Dispatchers.IO) { configured.get(FX_ACTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
        }.exceptionOrNull()
        if (setupFailure != null) {
            val failure = setupFailure.cause ?: setupFailure
            DiagnosticLog.warn(DesktopBackend.diagnosticsDirectory(), "playback_setup_failed", "Could not configure the desktop media engine.", failure)
            if (requests.get() == request && !retryRemotePlaybackLocally(uri, track, request, failure, autoplay = !pauseAtTransition)) {
                _state.update { current -> current.copy(
                    status = PlaybackStatus.ERROR,
                    error = failure.message ?: "The desktop media engine could not open this audio source.",
                ) }
            }
            return
        }
        if (requests.get() == request && _state.value.status == PlaybackStatus.RESOLVING) {
            _state.update { current -> current.copy(status = PlaybackStatus.BUFFERING) }
        }
        callbackScope.launch {
            while (requests.get() == request) {
                kotlinx.coroutines.delay(250)
                if (requests.get() == request && _state.value.status == PlaybackStatus.PLAYING) {
                    _state.update { current -> current.copy(listenedMillis = current.listenedMillis + 250) }
                }
            }
        }
    }

    private fun updatePlayback(request: Long, transform: (PlaybackSnapshot) -> PlaybackSnapshot) {
        _state.update { current ->
            if (requests.get() == request && current.playbackSessionId == request) transform(current) else current
        }
    }

    /** Called on the JavaFX thread for both direct streams and the local AAC recovery file. */
    private fun configurePlayer(uri: java.net.URI, track: MusicTrack, request: Long, autoplay: Boolean = true) {
        videoViews.forEach { it.mediaPlayer = null }
        mediaPlayer?.stop()
        mediaPlayer?.dispose()
        val media = Media(uri.toString())
        val nextPlayer = MediaPlayer(media)
        val equalizerSettings = DesktopBackend.equalizerSettings()
        applyAudioSettings(nextPlayer, equalizerSettings)
        nextPlayer.setOnReady {
            if (requests.get() != request) { nextPlayer.dispose(); return@setOnReady }
            updatePlayback(request) { current -> current.copy(
                status = PlaybackStatus.BUFFERING,
                durationMillis = nextPlayer.totalDuration.toMillis().toLong().coerceAtLeast(0),
                error = null,
                notice = null,
            ) }
            applyFadeVolume(nextPlayer)
            if (autoplay) nextPlayer.play() else nextPlayer.pause()
        }
        nextPlayer.setOnPlaying {
            if (requests.get() == request) updatePlayback(request) { current -> current.copy(
                status = PlaybackStatus.PLAYING,
                startedAtEpochSeconds = current.startedAtEpochSeconds ?: System.currentTimeMillis() / 1000,
            ) }
            if (requests.get() == request) applyFadeVolume(nextPlayer)
        }
        nextPlayer.setOnPaused { if (requests.get() == request) updatePlayback(request) { current -> current.copy(status = PlaybackStatus.PAUSED, audioSpectrum = emptyList()) } }
        nextPlayer.setOnStalled { if (requests.get() == request) updatePlayback(request) { current -> current.copy(status = PlaybackStatus.BUFFERING) } }
        nextPlayer.setOnError {
            if (requests.get() == request) {
                val error = nextPlayer.error ?: media.error
                if (!retryRemotePlaybackLocally(uri, track, request, error, autoplay)) {
                    DiagnosticLog.warn(DesktopBackend.diagnosticsDirectory(), "playback_engine_failed", "The desktop media engine could not play the selected source.", error)
                    finishPlaybackError(request, error ?: IllegalStateException("The audio stream could not be decoded."))
                }
            }
        }
        nextPlayer.currentTimeProperty().addListener { _, _, time ->
            if (requests.get() == request) {
                updatePlayback(request) { current -> current.copy(positionMillis = time.toMillis().toLong().coerceAtLeast(0)) }
                applyFadeVolume(nextPlayer)
            }
        }
        nextPlayer.audioSpectrumNumBands = 48
        nextPlayer.audioSpectrumInterval = 0.08
        nextPlayer.setAudioSpectrumListener { _, _, magnitudes, _ ->
            if (requests.get() == request && _state.value.status == PlaybackStatus.PLAYING) {
                updatePlayback(request) { current -> current.copy(audioSpectrum = magnitudes.map { ((it + 60f) / 60f).coerceIn(0f, 1f) }) }
            }
        }
        nextPlayer.setOnEndOfMedia {
            if (requests.get() != request || mediaPlayer !== nextPlayer) return@setOnEndOfMedia
            callbackScope.launch {
                if (requests.get() != request || mediaPlayer !== nextPlayer) return@launch
                if (pauseForSleepTimerAtTrackEnd()) return@launch
                if (requests.get() != request || mediaPlayer !== nextPlayer) return@launch
                when (_state.value.repeatMode) {
                    RepeatMode.ONE -> startAt(_state.value.queueIndex)
                    RepeatMode.OFF, RepeatMode.ALL -> next()
                }
            }
        }
        mediaPlayer = nextPlayer
        videoViews.forEach { it.mediaPlayer = nextPlayer.takeIf { track.kind == "video" } }
    }

    private fun retryRemotePlaybackLocally(uri: java.net.URI, track: MusicTrack, request: Long, failure: Throwable?, autoplay: Boolean = true): Boolean {
        if (uri.scheme != "https" || track.videoId.isNullOrBlank() || !fallbackAttempts.add(request)) return false
        DiagnosticLog.warn(
            DesktopBackend.diagnosticsDirectory(),
            "remote_media_rejected",
            "The embedded media engine rejected a remote stream; retrying with a local AAC file.",
            failure,
        )
        updatePlayback(request) { current -> current.copy(status = PlaybackStatus.RESOLVING, error = null, notice = "Preparing compatible in-app audio…") }
        callbackScope.launch {
            val fallback = runCatching {
                withTimeout(PLAYBACK_FALLBACK_TIMEOUT_MILLIS) { DesktopBackend.downloadPlaybackFallback(track) }
            }
            if (requests.get() != request) {
                fallbackAttempts.remove(request)
                return@launch
            }
            fallback.fold(
                onSuccess = { path ->
                    runOnFxAsync {
                        try {
                            if (requests.get() == request) configurePlayer(path.toUri(), track, request, autoplay)
                        } catch (failure: Throwable) {
                            finishPlaybackError(request, failure)
                        } finally {
                            fallbackAttempts.remove(request)
                        }
                    }
                },
                onFailure = {
                    fallbackAttempts.remove(request)
                    finishPlaybackError(request, it)
                },
            )
        }
        return true
    }

    private fun finishPlaybackError(request: Long, failure: Throwable) {
        if (requests.get() == request) updatePlayback(request) { current -> current.copy(
            status = PlaybackStatus.ERROR,
            error = failure.message ?: "The embedded player could not open this audio source.",
            notice = null,
        ) }
    }

    @Synchronized
    private fun startToolkit() {
        if (toolkitStartRequested) return
        toolkitStartRequested = true
        try {
            Platform.startup {
                Platform.setImplicitExit(false)
                fxStarted.complete(Unit)
            }
        } catch (alreadyStarted: IllegalStateException) {
            runCatching { Platform.runLater { Platform.setImplicitExit(false); fxStarted.complete(Unit) } }
                .onFailure { fxStarted.completeExceptionally(it) }
        } catch (failure: Throwable) {
            fxStarted.completeExceptionally(failure)
        }
    }

    private suspend fun runOnFx(action: () -> Unit) {
        startToolkit()
        awaitFxStarted()
        val complete = CompletableFuture<Unit>()
        try {
            Platform.runLater {
            runCatching(action).fold(complete::complete) { complete.completeExceptionally(it) }
            }
        } catch (failure: Throwable) {
            complete.completeExceptionally(failure)
        }
        withContext(Dispatchers.IO) { complete.get(FX_ACTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
    }

    private suspend fun awaitFxStarted() {
        withContext(Dispatchers.IO) { fxStarted.get(FX_START_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
    }

    private fun runOnFxAsync(action: () -> Unit) {
        startToolkit()
        fxStarted.whenComplete { _, failure ->
            if (failure != null) {
                DiagnosticLog.warn(DesktopBackend.diagnosticsDirectory(), "desktop_media_thread_unavailable", "A desktop audio action was skipped because the media thread did not start.", failure)
                return@whenComplete
            }
            runCatching { Platform.runLater(action) }
                .onFailure { DiagnosticLog.warn(DesktopBackend.diagnosticsDirectory(), "desktop_media_action_failed", "A desktop audio action could not be scheduled.", it) }
        }
    }

    private const val FX_START_TIMEOUT_MILLIS = 20_000L
    private const val FX_ACTION_TIMEOUT_MILLIS = 20_000L
    private const val STREAM_RESOLVE_TIMEOUT_MILLIS = 150_000L
    private const val PLAYBACK_FALLBACK_TIMEOUT_MILLIS = 120_000L
}

internal fun isValidDesktopSleepTimerMinutes(minutes: Int): Boolean = minutes in 5..120 && minutes % 5 == 0

internal fun desktopSleepTimerRemainingMillis(deadlineNanos: Long, nowNanos: Long): Long =
    ((deadlineNanos - nowNanos) / 1_000_000L).coerceAtLeast(0L)

internal fun isCurrentDesktopSleepTimerExpiry(timerActive: Boolean, currentGeneration: Long, callbackGeneration: Long): Boolean =
    timerActive && currentGeneration == callbackGeneration

internal fun scrobbleThresholdMillis(durationMillis: Long): Long? {
    if (durationMillis <= 30_000) return null
    return minOf(durationMillis / 2, 240_000)
}

internal fun isDesktopPlayableAudioMimeType(mimeType: String): Boolean {
    val normalized = mimeType.lowercase()
    return normalized.startsWith("audio/mp4") && normalized.contains("mp4a")
}
