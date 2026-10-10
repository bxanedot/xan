package app.xan.desktop

import app.xan.music.listentogether.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.People
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.net.http.WebSocket.Listener
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal data class DesktopListenTogetherState(
    val status: String = "Disconnected",
    val roomCode: String? = null,
    val userId: String? = null,
    val isHost: Boolean = false,
    val controlMode: String = ControlModes.OWNER,
    val users: List<UserInfo> = emptyList(),
    val joinRequests: List<JoinRequestPayload> = emptyList(),
    val notice: String? = null,
)

/** Desktop transport for the Android Listen Together JSON protocol. It uses
 * the same centralized room server and room URL contract as Android. */
internal object DesktopListenTogether {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(DesktopListenTogetherState())
    val state = _state.asStateFlow()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var username = "XAN listener"
    @Volatile private var sessionToken: String? = null
    @Volatile private var explicitDisconnect = false
    @Volatile private var reconnectAttempts = 0
    private val reconnecting = AtomicBoolean(false)
    private val pendingPingTimes = ConcurrentHashMap<Long, Long>()
    @Volatile private var serverTimeOffsetMs = 0L
    @Volatile private var hasServerTimeOffset = false
    @Volatile private var ignoreLocalPlaybackUntil = 0L

    fun createRoom(serverUrl: String, displayName: String) {
        scope.launch {
            runCatching {
                configureServer(serverUrl)
                username = displayName.trim().ifBlank { "XAN listener" }
                explicitDisconnect = false
                reconnectAttempts = 0
                DesktopBackend.clearListenTogetherSession()
                sessionToken = null
                val code = allocateRoom()
                openSocket(code) { send(MessageTypes.CREATE_ROOM, CreateRoomPayload(username)) }
            }.onFailure { reportError(it.message ?: "Could not create a Listen Together room.") }
        }
    }

    fun joinRoom(serverUrl: String, roomCode: String, displayName: String) {
        scope.launch {
            runCatching {
                configureServer(serverUrl)
                val code = roomCode.trim().uppercase()
                require(Regex("^[A-Z0-9]{4,12}$").matches(code)) { "Room codes must contain 4–12 letters or numbers." }
                username = displayName.trim().ifBlank { "XAN listener" }
                explicitDisconnect = false
                reconnectAttempts = 0
                DesktopBackend.clearListenTogetherSession()
                sessionToken = null
                openSocket(code) {
                    send(MessageTypes.JOIN_ROOM, JoinRoomPayload(code, username))
                    _state.value = _state.value.copy(status = "Waiting for approval", notice = "The room host must approve your request.")
                }
            }.onFailure { reportError(it.message ?: "Could not join the Listen Together room.") }
        }
    }

    fun resumeSavedSession() {
        val saved = DesktopBackend.listenTogetherSession() ?: return
        val server = DesktopBackend.listenTogetherServerUrl().takeIf(String::isNotBlank) ?: return
        scope.launch {
            runCatching {
                username = saved.third
                sessionToken = saved.first
                explicitDisconnect = false
                openSocket(saved.second) { send(MessageTypes.RECONNECT, ReconnectPayload(saved.first)) }
            }.onFailure { reportError(it.message ?: "Could not reconnect to the saved room.") }
        }
    }

    fun disconnect() {
        explicitDisconnect = true
        send(MessageTypes.LEAVE_ROOM)
        reconnecting.set(false)
        resetServerClock()
        sessionToken = null
        socket?.sendClose(WebSocket.NORMAL_CLOSURE, "User left room")
        socket = null
        DesktopBackend.clearListenTogetherSession()
        _state.value = DesktopListenTogetherState()
    }

    fun approveJoin(userId: String) {
        if (!_state.value.isHost || _state.value.status != "In room") return
        send(MessageTypes.APPROVE_JOIN, ApproveJoinPayload(userId))
        _state.value = _state.value.copy(joinRequests = _state.value.joinRequests.filterNot { it.userId == userId })
    }

    fun rejectJoin(userId: String) {
        if (!_state.value.isHost || _state.value.status != "In room") return
        send(MessageTypes.REJECT_JOIN, RejectJoinPayload(userId, "The host declined this request."))
        _state.value = _state.value.copy(joinRequests = _state.value.joinRequests.filterNot { it.userId == userId })
    }

    fun setControlMode(mode: String) {
        if (!state.value.isHost || mode !in setOf(ControlModes.OWNER, ControlModes.EVERYONE)) return
        send(MessageTypes.SET_CONTROL_MODE, SetControlModePayload(mode))
    }

    fun shutdown() {
        explicitDisconnect = true
        reconnecting.set(false)
        socket?.sendClose(WebSocket.NORMAL_CLOSURE, "Application closed")
        socket = null
    }

    fun publishPlayback(action: String, track: MusicTrack? = null, positionMillis: Long? = null) {
        if (state.value.roomCode == null || !canControl() || System.currentTimeMillis() < ignoreLocalPlaybackUntil) return
        val info = track?.let(::trackInfo)
        if (track != null && info == null) {
            _state.value = _state.value.copy(notice = "Only YouTube Music tracks can be synchronized to other devices.")
            return
        }
        send(MessageTypes.PLAYBACK_ACTION, PlaybackActionPayload(
            action = action,
            trackId = info?.id,
            position = positionMillis,
            trackInfo = info,
            queue = if (action == PlaybackActions.SYNC_QUEUE) DesktopPlayer.state.value.queue.mapNotNull(::trackInfo) else null,
            serverTime = serverTimeNowMillis(),
        ))
    }

    private fun configureServer(serverUrl: String) {
        val normalized = normalizeDesktopRoomServerUrl(serverUrl)
        require(normalized != null) { "Use a secure wss:// server origin without a path, query, or credentials." }
        DesktopBackend.setListenTogetherServerUrl(normalized)
        _state.value = _state.value.copy(status = "Connecting…", notice = null)
    }

    private fun allocateRoom(): String {
        val base = httpBase(DesktopBackend.listenTogetherServerUrl())
        val request = java.net.http.HttpRequest.newBuilder(URI("$base/api/rooms"))
            .timeout(Duration.ofSeconds(90)).POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build()
        val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "Room server returned HTTP ${response.statusCode()} while creating a room." }
        val code = json.decodeFromString<RoomAllocationResponse>(response.body()).roomCode.trim().uppercase()
        require(Regex("^[A-Z0-9]{4,12}$").matches(code)) { "The server returned an invalid room code." }
        return code
    }

    private fun openSocket(roomCode: String, onOpen: () -> Unit) {
        socket?.abort()
        resetServerClock()
        _state.value = DesktopListenTogetherState(status = "Connecting…", roomCode = roomCode)
        val base = DesktopBackend.listenTogetherServerUrl()
        client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(90))
            .buildAsync(URI("$base/room/$roomCode"), object : Listener {
                private val fragments = StringBuilder()
                override fun onOpen(webSocket: WebSocket) {
                    socket = webSocket
                    _state.value = _state.value.copy(status = "Connected", notice = null)
                    onOpen()
                    scope.launch {
                        sendClockPing(webSocket)
                        while (socket === webSocket && !explicitDisconnect) {
                            delay(25_000)
                            if (socket === webSocket) sendClockPing(webSocket)
                        }
                    }
                    webSocket.request(1)
                }
                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    fragments.append(data)
                    if (last) {
                        handleMessage(fragments.toString())
                        fragments.setLength(0)
                    }
                    webSocket.request(1)
                    return null
                }
                override fun onPing(webSocket: WebSocket, message: java.nio.ByteBuffer): CompletionStage<*>? {
                    webSocket.request(1)
                    return webSocket.sendPong(message)
                }
                override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                    socket = null
                    if (!explicitDisconnect) scheduleReconnect(roomCode)
                    return null
                }
                override fun onError(webSocket: WebSocket, error: Throwable) {
                    socket = null
                    reportError(error.message ?: "Listen Together connection failed.")
                    if (!explicitDisconnect) scheduleReconnect(roomCode)
                }
            }).whenComplete { _, error -> if (error != null) {
                reportError(error.cause?.message ?: error.message ?: "Could not connect to the Listen Together server.")
                if (!explicitDisconnect && sessionToken != null) scheduleReconnect(roomCode)
            } }
    }

    private fun scheduleReconnect(roomCode: String) {
        if (sessionToken.isNullOrBlank() || !reconnecting.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (!explicitDisconnect && socket == null) {
                    reconnectAttempts++
                    _state.value = _state.value.copy(status = "Reconnecting $reconnectAttempts…")
                    delay(desktopRoomReconnectDelayMillis(reconnectAttempts))
                    if (explicitDisconnect) break
                    val token = sessionToken ?: break
                    openSocket(roomCode) { send(MessageTypes.RECONNECT, ReconnectPayload(token)) }
                    delay(2_000)
                    if (socket != null) break
                }
            } finally {
                reconnecting.set(false)
            }
        }
    }

    private fun send(type: String, payload: Any? = null) {
        val payloadElement = when (payload) {
            null -> null
            is CreateRoomPayload -> json.encodeToJsonElement(payload)
            is JoinRoomPayload -> json.encodeToJsonElement(payload)
            is ApproveJoinPayload -> json.encodeToJsonElement(payload)
            is RejectJoinPayload -> json.encodeToJsonElement(payload)
            is ReconnectPayload -> json.encodeToJsonElement(payload)
            is PlaybackActionPayload -> json.encodeToJsonElement(payload)
            is SetControlModePayload -> json.encodeToJsonElement(payload)
            is PingPayload -> json.encodeToJsonElement(payload)
            is RequestSyncPayload -> json.encodeToJsonElement(payload)
            else -> error("Unsupported Listen Together message payload.")
        }
        val message = json.encodeToString(Message(type, payloadElement))
        socket?.sendText(message, true)
    }

    private fun handleMessage(raw: String) {
        runCatching {
            val message = json.decodeFromString<Message>(raw)
            val payload = message.payload?.jsonObject ?: buildJsonObject { }
            when (message.type) {
                MessageTypes.ROOM_CREATED -> {
                    val created = json.decodeFromJsonElement<RoomCreatedPayload>(payloadElement(message))
                    sessionToken = created.sessionToken
                    DesktopBackend.saveListenTogetherSession(created.sessionToken, created.roomCode, created.userId, username)
                    _state.value = _state.value.copy(status = "In room", roomCode = created.roomCode, userId = created.userId, isHost = true, notice = "Room created. Share code ${created.roomCode}.")
                    created.state?.let(::applyRoomState)
                }
                MessageTypes.JOIN_APPROVED -> {
                    val approved = json.decodeFromJsonElement<JoinApprovedPayload>(payloadElement(message))
                    sessionToken = approved.sessionToken
                    DesktopBackend.saveListenTogetherSession(approved.sessionToken, approved.roomCode, approved.userId, username)
                    applyRoomState(approved.state)
                    _state.value = _state.value.copy(status = "In room", roomCode = approved.roomCode, userId = approved.userId, isHost = approved.state.hostId == approved.userId, notice = "Joined room ${approved.roomCode}.")
                }
                MessageTypes.RECONNECTED -> {
                    val resumed = json.decodeFromJsonElement<ReconnectedPayload>(payloadElement(message))
                    reconnectAttempts = 0
                    applyRoomState(resumed.state)
                    _state.value = _state.value.copy(status = "In room", roomCode = resumed.roomCode, userId = resumed.userId, isHost = resumed.isHost, notice = null)
                }
                MessageTypes.SYNC_STATE -> {
                    val sync = json.decodeFromJsonElement<SyncStatePayload>(payloadElement(message))
                    sync.currentTrack?.let { applyTrack(it, sync.position, sync.isPlaying, sync.lastUpdate, sync.queue) }
                    if (sync.currentTrack == null) DesktopPlayer.clearExternalPlayback()
                }
                MessageTypes.SYNC_PLAYBACK -> applyPlayback(json.decodeFromJsonElement(payloadElement(message)))
                MessageTypes.JOIN_REQUEST -> {
                    val request = json.decodeFromJsonElement<JoinRequestPayload>(payloadElement(message))
                    if (_state.value.isHost) _state.value = _state.value.copy(joinRequests = (_state.value.joinRequests.filterNot { it.userId == request.userId } + request).take(32))
                }
                MessageTypes.USER_JOINED, MessageTypes.USER_LEFT, MessageTypes.USER_RECONNECTED, MessageTypes.USER_DISCONNECTED, MessageTypes.HOST_CHANGED, MessageTypes.CONTROL_MODE_CHANGED -> updateRoomFromPayload(message.type, payload)
                MessageTypes.JOIN_REJECTED -> {
                    disconnect()
                    reportError(payload["reason"]?.jsonPrimitive?.content ?: "The room request was declined.")
                }
                MessageTypes.ERROR -> reportError(payload["message"]?.jsonPrimitive?.content ?: "The Listen Together server reported an error.")
                MessageTypes.KICKED, MessageTypes.ROOM_CLOSED -> disconnect()
                MessageTypes.PONG -> acceptPong(json.decodeFromJsonElement(payload))
            }
        }.onFailure { reportError("Could not read the room server response: ${it.message ?: "invalid message"}") }
    }

    private fun payloadElement(message: Message) = message.payload ?: error("The server sent ${message.type} without a payload.")

    private fun applyRoomState(room: RoomState) {
        val self = _state.value.userId
        _state.value = _state.value.copy(roomCode = room.roomCode, controlMode = room.controlMode, users = room.users, isHost = self == room.hostId)
        room.currentTrack?.let { applyTrack(it, room.position, room.isPlaying, room.lastUpdate, room.queue) }
        if (room.currentTrack == null) DesktopPlayer.clearExternalPlayback()
    }

    private fun applyPlayback(payload: PlaybackActionPayload) {
        ignoreLocalPlaybackUntil = System.currentTimeMillis() + 2_000
        when (payload.action) {
            PlaybackActions.CHANGE_TRACK -> payload.trackInfo?.let {
                applyTrack(it, payload.position ?: 0, true, payload.serverTime, payload.queue)
            }
            PlaybackActions.PLAY -> {
                if (payload.trackInfo != null) applyTrack(payload.trackInfo, payload.position ?: 0, true, payload.serverTime, payload.queue)
                else {
                    payload.position?.let { position ->
                        DesktopPlayer.seekTo(extrapolatePlaybackPosition(position, true, payload.serverTime ?: 0L, serverTimeNowMillis()))
                    }
                    DesktopPlayer.setPlaying(true)
                }
            }
            PlaybackActions.PAUSE -> DesktopPlayer.setPlaying(false)
            PlaybackActions.SEEK -> payload.position?.let { position ->
                val isPlaying = DesktopPlayer.state.value.status == PlaybackStatus.PLAYING
                DesktopPlayer.seekTo(extrapolatePlaybackPosition(position, isPlaying, payload.serverTime ?: 0L, serverTimeNowMillis()))
            }
            PlaybackActions.SET_VOLUME -> payload.volume?.let(DesktopPlayer::setVolume)
            PlaybackActions.SKIP_NEXT, PlaybackActions.SKIP_PREV -> send(MessageTypes.REQUEST_SYNC, RequestSyncPayload())
            PlaybackActions.QUEUE_ADD -> payload.trackInfo?.let { DesktopPlayer.addRoomQueueItem(musicTrack(it), payload.insertNext == true) }
            PlaybackActions.QUEUE_REMOVE -> payload.trackId?.let(DesktopPlayer::removeRoomQueueItem)
            PlaybackActions.QUEUE_CLEAR -> DesktopPlayer.clearRoomQueueAfterCurrent()
            PlaybackActions.SYNC_QUEUE -> payload.queue?.let { queue ->
                val tracks = queue.map(::musicTrack)
                DesktopPlayer.setQueueForRoomSync(tracks)
            }
        }
    }

    private fun applyTrack(
        info: TrackInfo,
        position: Long,
        isPlaying: Boolean,
        lastUpdate: Long? = null,
        authoritativeQueue: List<TrackInfo>? = null,
    ) {
        ignoreLocalPlaybackUntil = System.currentTimeMillis() + 2_000
        val track = musicTrack(info)
        scope.launch {
            if (DesktopPlayer.state.value.track?.videoId != track.videoId) DesktopPlayer.play(track)
            authoritativeQueue?.let { queue -> DesktopPlayer.setQueueForRoomSync(queue.map(::musicTrack)) }
            if (position > 0 || lastUpdate != null) {
                delay(700)
                val adjustedPosition = lastUpdate?.let {
                    extrapolatePlaybackPosition(position, isPlaying, it, serverTimeNowMillis())
                } ?: position
                if (adjustedPosition > 0) DesktopPlayer.seekTo(adjustedPosition)
            }
            DesktopPlayer.setPlaying(isPlaying)
        }
    }

    private fun sendClockPing(webSocket: WebSocket) {
        if (socket !== webSocket || explicitDisconnect) return
        val clientTime = System.currentTimeMillis()
        pendingPingTimes[clientTime] = System.nanoTime() / 1_000_000L
        if (pendingPingTimes.size > 8) {
            pendingPingTimes.entries.sortedBy { it.value }.take(pendingPingTimes.size - 4).forEach { entry ->
                pendingPingTimes.remove(entry.key, entry.value)
            }
        }
        send(MessageTypes.PING, PingPayload(clientTime))
    }

    private fun acceptPong(payload: PongPayload) {
        val sentAt = pendingPingTimes.remove(payload.clientTime) ?: return
        val roundTripMs = (System.nanoTime() / 1_000_000L - sentAt).coerceAtLeast(0L)
        val sample = desktopServerClockOffsetSample(payload.clientTime, payload.serverTime, roundTripMs) ?: return
        serverTimeOffsetMs = smoothDesktopServerClockOffset(serverTimeOffsetMs.takeIf { hasServerTimeOffset }, sample)
        hasServerTimeOffset = true
    }

    private fun resetServerClock() {
        pendingPingTimes.clear()
        serverTimeOffsetMs = 0L
        hasServerTimeOffset = false
    }

    private fun serverTimeNowMillis(): Long = System.currentTimeMillis() + serverTimeOffsetMs

    private fun musicTrack(info: TrackInfo) = MusicTrack(
        title = info.title, artist = info.artist, album = info.album ?: "YouTube Music",
        colors = listOf(XanColors.CardLight, XanColors.Card), motif = "♪",
        videoId = info.id.takeIf { Regex("^[A-Za-z0-9_-]{11}$").matches(it) }, source = "YouTube Music", artworkUrl = info.thumbnail,
        durationSeconds = (info.duration / 1000).toInt().takeIf { it > 0 },
    )

    private fun trackInfo(track: MusicTrack): TrackInfo? = track.videoId?.let { id -> TrackInfo(
        id = id, title = track.title, artist = track.artist, album = track.album,
        duration = track.durationSeconds?.times(1000L) ?: 0L, thumbnail = track.artworkUrl,
    ) }

    private fun updateRoomFromPayload(type: String, payload: kotlinx.serialization.json.JsonObject) {
        when (type) {
            MessageTypes.CONTROL_MODE_CHANGED -> _state.value = _state.value.copy(controlMode = payload["control_mode"]?.jsonPrimitive?.content ?: ControlModes.OWNER)
            MessageTypes.HOST_CHANGED -> {
                val id = payload["new_host_id"]?.jsonPrimitive?.content
                _state.value = _state.value.copy(isHost = id != null && id == _state.value.userId)
                if (!_state.value.isHost) _state.value = _state.value.copy(joinRequests = emptyList())
            }
            MessageTypes.USER_JOINED, MessageTypes.USER_RECONNECTED, MessageTypes.USER_DISCONNECTED -> {
                val id = payload["user_id"]?.jsonPrimitive?.content ?: return
                val name = payload["username"]?.jsonPrimitive?.content ?: "Listener"
                val current = _state.value.users.filterNot { it.userId == id }
                val user = UserInfo(id, name, isHost = false, isConnected = type != MessageTypes.USER_DISCONNECTED)
                _state.value = _state.value.copy(users = current + user, joinRequests = _state.value.joinRequests.filterNot { it.userId == id })
            }
            MessageTypes.USER_LEFT -> {
                val id = payload["user_id"]?.jsonPrimitive?.content ?: return
                _state.value = _state.value.copy(users = _state.value.users.filterNot { it.userId == id })
            }
            else -> Unit
        }
    }

    private fun canControl(): Boolean = state.value.isHost || state.value.controlMode == ControlModes.EVERYONE
    private fun reportError(message: String) { _state.value = _state.value.copy(status = "Connection issue", notice = message) }
    private fun httpBase(url: String): String = url.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
}

internal fun desktopRoomReconnectDelayMillis(attempt: Int): Long = 1_000L shl (attempt - 1).coerceIn(0, 5)

@Composable
internal fun ListenTogetherSettingsDetail() {
    val state by DesktopListenTogether.state.collectAsState()
    var server by remember { mutableStateOf(DesktopBackend.listenTogetherServerUrl()) }
    var name by remember { mutableStateOf(System.getProperty("user.name")?.take(32) ?: "XAN listener") }
    var room by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(22.dp)).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.People, null, tint = XanColors.Accent)
            Text("Listen Together", color = XanColors.Text, style = MaterialTheme.typography.titleLarge)
        }
        Text("Join the same centralized rooms as XAN Android. The server must be the Listen Together WebSocket endpoint.", color = XanColors.Muted)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            desktopRoomServerProviders.forEach { provider ->
                FilterChip(selected = server == provider.url, onClick = { server = provider.url; DesktopBackend.setListenTogetherServerUrl(server) }, enabled = state.roomCode == null, label = { Text(provider.name) })
            }
        }
        if (XAN_ROOM_HUGGING_FACE_URL.isBlank()) {
            Text("Hugging Face needs a deployed Space URL. Select it above, then enter the Space's wss:// URL below.", color = XanColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Text("Everyone in a room must use the same server. Hugging Face Spaces may pause after idle.", color = XanColors.Muted, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(server, { server = it }, enabled = state.roomCode == null, label = { Text("Server URL") }, placeholder = { Text("wss://your-listen-together-server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(name, { name = it.take(40) }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(room, { room = it.uppercase().filter(Char::isLetterOrDigit).take(12) }, label = { Text("Room code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { DesktopListenTogether.createRoom(server, name) }, enabled = state.roomCode == null && state.status != "Connecting…" && server.isNotBlank()) { Text("Create room") }
            Button(onClick = { DesktopListenTogether.joinRoom(server, room, name) }, enabled = state.roomCode == null && state.status != "Connecting…" && room.length in 4..12 && server.isNotBlank()) { Text("Join room") }
            if (state.roomCode != null || state.status.contains("Connecting") || state.status.contains("Reconnecting")) {
                OutlinedButton(onClick = { DesktopListenTogether.disconnect() }) { Text("Leave") }
            }
        }
        HorizontalDivider(color = XanColors.Border)
        Text("${state.status}${state.roomCode?.let { " · Room $it" }.orEmpty()}", color = XanColors.Text, fontWeight = FontWeight.SemiBold)
        if (state.roomCode != null) {
            TextButton(onClick = {
                runCatching {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(state.roomCode), null)
                }
            }) { Text("Copy room code") }
        }
        state.notice?.let { Text(it, color = if (state.status == "Connection issue") MaterialTheme.colorScheme.error else XanColors.Muted) }
        if (state.users.isNotEmpty()) {
            Text("${state.users.size} in the room", color = XanColors.Muted)
            state.users.forEach { user -> Text("${user.username}${if (user.isHost) " · host" else ""}${if (user.isConnected) "" else " · reconnecting"}", color = XanColors.Text, fontSize = 14.sp) }
        }
        if (state.isHost && state.roomCode != null) {
            state.joinRequests.forEach { request ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${request.username} wants to join", color = XanColors.Text, modifier = Modifier.weight(1f))
                    Button(onClick = { DesktopListenTogether.approveJoin(request.userId) }) { Text("Accept") }
                    OutlinedButton(onClick = { DesktopListenTogether.rejectJoin(request.userId) }) { Text("Decline") }
                }
            }
            SettingSwitch(
                "Allow everyone to control playback",
                "When off, only the room host can change the shared track, queue, or playback position.",
                state.controlMode == ControlModes.EVERYONE,
            ) { DesktopListenTogether.setControlMode(if (it) ControlModes.EVERYONE else ControlModes.OWNER) }
        }
        if (state.roomCode != null && !state.isHost && state.controlMode == ControlModes.OWNER) {
            Text("Playback controls are managed by the room host.", color = XanColors.Muted)
        }
    }
}
