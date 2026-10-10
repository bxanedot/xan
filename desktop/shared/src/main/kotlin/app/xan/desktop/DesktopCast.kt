package app.xan.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import su.litvak.chromecast.api.v2.ChromeCast
import su.litvak.chromecast.api.v2.ChromeCasts
import su.litvak.chromecast.api.v2.ChromeCastsListener
import su.litvak.chromecast.api.v2.Media
import su.litvak.chromecast.api.v2.MediaStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

internal data class CastDevice(val name: String, val address: String, internal val native: ChromeCast)
internal data class CastSnapshot(
    val devices: List<CastDevice> = emptyList(),
    val connectedName: String? = null,
    val status: String? = null,
    val busy: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val volume: Float = 0.8f,
)

/** Google Cast V2 sender wrapper. Discovery is mDNS; playback is relayed from XAN over the local network. */
internal object DesktopCast {
    private val _state = MutableStateFlow(CastSnapshot())
    val state: StateFlow<CastSnapshot> = _state.asStateFlow()
    private val relay = CastMediaRelay()
    private val discoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var connected: ChromeCast? = null
    @Volatile private var connectedName: String? = null
    @Volatile private var discoveryStarted = false
    private val discoveryListener = object : ChromeCastsListener {
        override fun newChromeCastDiscovered(chromeCast: ChromeCast) = updateDevice(chromeCast)
        override fun chromeCastRemoved(chromeCast: ChromeCast) {
            _state.value = _state.value.copy(devices = _state.value.devices.filterNot { it.address == chromeCast.address })
            if (connected?.address == chromeCast.address) {
                runCatching { connected?.disconnect() }
                connected = null
                connectedName = null
                relay.close()
                DesktopPlayer.clearExternalPlayback()
                _state.value = _state.value.copy(connectedName = null, status = "Cast receiver disconnected.", busy = false)
            }
        }
    }

    private fun updateDevice(device: ChromeCast) {
        val item = CastDevice(device.name ?: "Chromecast", device.address, device)
        _state.value = _state.value.copy(
            devices = (_state.value.devices.filterNot { it.address == item.address } + item).sortedBy { it.name.lowercase() },
            status = null,
        )
    }

    fun startDiscovery() {
        if (discoveryStarted) return
        _state.value = _state.value.copy(status = "Searching for Cast devices…")
        ChromeCasts.registerListener(discoveryListener)
        runCatching { ChromeCasts.startDiscovery() }
            .onSuccess {
                discoveryStarted = true
                ChromeCasts.get().forEach(::updateDevice)
                reportEmptyScan()
            }
            .onFailure {
                _state.value = _state.value.copy(status = "Cast discovery failed: ${it.message ?: "network unavailable"}")
                runCatching { DiagnosticLog.warn(DesktopBackend.diagnosticsDirectory(), "cast_discovery_failed", "Google Cast device discovery failed.", it) }
            }
    }

    fun refreshDiscovery() {
        _state.value = _state.value.copy(status = "Searching for Cast devices…")
        runCatching {
            if (discoveryStarted) ChromeCasts.restartDiscovery() else startDiscovery()
            ChromeCasts.get().forEach(::updateDevice)
            reportEmptyScan()
        }.onFailure {
            _state.value = _state.value.copy(status = "Cast discovery failed: ${it.message ?: "network unavailable"}")
            DiagnosticLog.warn(DesktopBackend.diagnosticsDirectory(), "cast_discovery_failed", "Google Cast device discovery failed.", it)
        }
    }

    private fun reportEmptyScan() = discoveryScope.launch {
        delay(8_000)
        if (_state.value.devices.isEmpty() && _state.value.status?.startsWith("Searching") == true) {
            _state.value = _state.value.copy(status = "No Cast devices found on this network.")
        }
    }

    suspend fun cast(device: CastDevice, track: MusicTrack, source: URI) = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(busy = true, status = "Connecting to ${device.name}…")
        runCatching {
            val receiver = device.native.apply { setRequestTimeout(8_000); setAutoReconnect(false) }
            if (!receiver.isConnected) receiver.connect()
            receiver.launchApp(DEFAULT_MEDIA_RECEIVER)
            val relayUri = relay.publish(source, device.address)
            val metadata = mutableMapOf<String, Any>(
                Media.METADATA_TYPE to Media.MetadataType.MUSIC_TRACK.ordinal,
                Media.METADATA_TITLE to track.title,
                Media.METADATA_ARTIST to track.artist,
            )
            track.artworkUrl?.takeIf(String::isNotBlank)?.let { metadata[Media.METADATA_IMAGES] = listOf(mapOf("url" to it)) }
            val contentType = when {
                source.scheme == "file" -> java.net.URLConnection.guessContentTypeFromName(source.path) ?: "audio/mp4"
                else -> "audio/mp4"
            }
            val media = Media(relayUri.toString(), contentType, track.durationSeconds?.toDouble(), Media.StreamType.BUFFERED, metadata, emptyMap(), emptyMap(), emptyList())
            receiver.load(media)
            connected = receiver
            connectedName = device.name
            _state.value = _state.value.copy(connectedName = device.name, busy = false, status = "Playing on ${device.name}", isPlaying = true,
                positionMillis = 0, durationMillis = track.durationSeconds?.times(1000L) ?: 0L)
            DesktopPlayer.onCastPlaybackStarted()
        }.onFailure {
            relay.close()
            _state.value = _state.value.copy(connectedName = null, busy = false, status = "Could not cast ${track.title}: ${it.message ?: "receiver unavailable"}")
            runCatching { DiagnosticLog.warn(DesktopBackend.diagnosticsDirectory(), "cast_playback_failed", "Could not start playback on the selected Cast receiver.", it, mapOf("device" to device.name)) }
            throw IllegalStateException(_state.value.status, it)
        }.getOrThrow()
    }

    suspend fun togglePlayback() = withContext(Dispatchers.IO) {
        val receiver = connected ?: error("No Cast receiver is connected.")
        val state = receiver.mediaStatus?.playerState
        if (state == MediaStatus.PlayerState.PLAYING) receiver.pause() else receiver.play()
        _state.value = _state.value.copy(isPlaying = state != MediaStatus.PlayerState.PLAYING)
    }

    suspend fun pause() = withContext(Dispatchers.IO) {
        val receiver = connected ?: return@withContext
        if (receiver.mediaStatus?.playerState == MediaStatus.PlayerState.PLAYING) receiver.pause()
        _state.value = _state.value.copy(isPlaying = false, status = "Paused on ${connectedName ?: "Cast device"}")
    }

    suspend fun seek(positionMillis: Long) = withContext(Dispatchers.IO) {
        val receiver = connected ?: error("No Cast receiver is connected.")
        receiver.seek((positionMillis.coerceAtLeast(0) / 1000.0))
        _state.value = _state.value.copy(positionMillis = positionMillis.coerceAtLeast(0))
    }

    suspend fun playOnCurrentReceiver(track: MusicTrack, source: URI) {
        val receiver = connected ?: error("No Cast receiver is connected.")
        cast(CastDevice(connectedName ?: receiver.name ?: "Chromecast", receiver.address, receiver), track, source)
    }

    suspend fun refreshStatus() = withContext(Dispatchers.IO) {
        val receiver = connected ?: return@withContext
        val status = runCatching { receiver.mediaStatus }.getOrElse {
            _state.value = _state.value.copy(status = "Lost the connection to ${connectedName ?: "the Cast receiver"}.")
            runCatching { DiagnosticLog.warn(DesktopBackend.diagnosticsDirectory(), "cast_status_failed", "Could not read playback state from the Cast receiver.", it) }
            throw it
        } ?: return@withContext
        _state.value = _state.value.copy(
            isPlaying = status.playerState == MediaStatus.PlayerState.PLAYING,
            positionMillis = (status.currentTime * 1000).toLong().coerceAtLeast(0),
            durationMillis = ((status.media?.duration ?: 0.0) * 1000).toLong().coerceAtLeast(0),
            volume = status.volume?.level ?: _state.value.volume,
        )
        DesktopPlayer.updateExternalPlayback(
            isPlaying = status.playerState == MediaStatus.PlayerState.PLAYING,
            positionMillis = (status.currentTime * 1000).toLong(),
            durationMillis = ((status.media?.duration ?: 0.0) * 1000).toLong(),
        )
    }

    suspend fun setVolume(value: Float) = withContext(Dispatchers.IO) {
        require(value in 0f..1f)
        (connected ?: error("No Cast receiver is connected.")).setVolume(value)
        _state.value = _state.value.copy(volume = value)
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { connected?.stopApp() }
        runCatching { connected?.disconnect() }
        connected = null
        connectedName = null
        relay.close()
        DesktopPlayer.clearExternalPlayback()
        _state.value = _state.value.copy(connectedName = null, status = "Disconnected from Cast receiver.", busy = false)
    }

    fun shutdown() {
        relay.close()
        DesktopPlayer.clearExternalPlayback()
        runCatching { ChromeCasts.unregisterListener(discoveryListener) }
        if (discoveryStarted) runCatching { ChromeCasts.stopDiscovery() }
        discoveryStarted = false
        discoveryScope.cancel()
        runCatching { connected?.disconnect() }
        connected = null
        connectedName = null
    }

    private const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"
}
