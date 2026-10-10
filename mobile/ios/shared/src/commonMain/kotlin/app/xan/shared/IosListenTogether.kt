package app.xan.shared

import app.xan.music.listentogether.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.client.request.post
import io.ktor.http.Url
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/** iOS transport for the same centralized Listen Together room server and
 * serialized contract that Android uses. */
object IosListenTogether {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = newRoomHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutableState = MutableStateFlow(IosRoomUiState())
    val state = mutableState.asStateFlow()
    private var session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession? = null
    private var activeRoomCode: String? = null
    private var serverUrl = ""
    private var username = "XAN listener"
    private var sessionToken: String? = null
    private var userId: String? = null
    private var host = false
    private var reconnectCount = 0
    private var reconnecting = false
    private var leaving = false
    private var backgrounded = false
    private var resuming = false
    private var sessionStore: RoomSessionStore? = null
    private val clock = IosRoomClock()

    fun installSessionStore(store: RoomSessionStore) {
        sessionStore = store
        store.load()?.let { saved ->
            serverUrl = saved.serverUrl
            username = saved.username
            sessionToken = saved.sessionToken
            activeRoomCode = saved.roomCode
            scope.launch { reconnectSaved(saved) }
        }
    }

    fun savedServer(): String = resolveIosRoomServerUrl(readIosPreference("room.server"))

    fun savedUsername(): String = readIosPreference("room.username") ?: ""

    fun savePreferences(server: String, displayName: String) {
        writeIosPreference("room.server", server.trim())
        writeIosPreference("room.username", displayName.trim())
    }

    /** Close the suspended socket but keep its Keychain token so foregrounding
     * can resume the same room instead of creating a new identity. */
    fun onBackground() {
        backgrounded = true
        if (activeRoomCode == null || leaving) return
        val old = session
        session = null
        scope.launch { runCatching { old?.close() } }
        mutableState.value = mutableState.value.copy(status = "Paused in background")
    }

    fun onForeground() {
        backgrounded = false
        val saved = sessionStore?.load() ?: return
        if (!leaving && !resuming && !reconnecting && session == null) {
            reconnectCount = 0
            scope.launch { reconnectSaved(saved) }
        }
    }

    fun createRoom(rawServer: String, displayName: String) = scope.launch {
        runCatching {
            configure(rawServer, displayName)
            val base = httpBase(serverUrl)
            val response = client.post(base + "/api/rooms")
            require(response.status.value in 200..299) { "Room server returned HTTP ${response.status.value}." }
            val allocated = json.decodeFromString<RoomAllocationResponse>(response.bodyAsText()).roomCode
            openRoom(allocated.trim().uppercase(), create = true)
        }.onFailure { mutableState.value = mutableState.value.copy(status = "Disconnected", error = it.message ?: "Could not create room.") }
    }

    fun joinRoom(rawServer: String, code: String, displayName: String) = scope.launch {
        runCatching {
            configure(rawServer, displayName)
            val room = code.trim().uppercase()
            require(Regex("^[A-Z0-9]{4,12}$").matches(room)) { "Room codes must contain 4–12 letters or numbers." }
            openRoom(room, create = false)
        }.onFailure { mutableState.value = mutableState.value.copy(status = "Disconnected", error = it.message ?: "Could not join room.") }
    }

    fun leave() = scope.launch {
        leaving = true
        val oldSocket = session
        session = null
        activeRoomCode = null
        sessionToken = null
        userId = null
        host = false
        clock.reset()
        sessionStore?.save(null)
        mutableState.value = IosRoomUiState()
        runCatching { oldSocket?.send(Frame.Text(json.encodeToString(Message(MessageTypes.LEAVE_ROOM)))) }
        runCatching { oldSocket?.close() }
    }

    fun sendPlayback(
        action: String,
        track: TrackInfo? = null,
        position: Long? = null,
        insertNext: Boolean? = null,
    ) = scope.launch {
        val socket = session ?: return@launch
        if (!host && mutableState.value.controlMode == ControlModes.OWNER) return@launch
        val payload = buildJsonObject {
            put("action", action)
            track?.let { put("track_info", json.encodeToJsonElement(it)); put("track_id", it.id) }
            position?.let { put("position", it) }
            insertNext?.let { put("insert_next", it) }
        }
        socket.send(Frame.Text(json.encodeToString(Message(MessageTypes.PLAYBACK_ACTION, payload))))
    }

    fun seek(positionMs: Double) {
        if (positionMs.isFinite() && positionMs >= 0.0) {
            sendPlayback(PlaybackActions.SEEK, position = positionMs.toLong())
        }
    }

    fun setControlMode(mode: String) = scope.launch {
        if (!host || mode !in listOf(ControlModes.OWNER, ControlModes.EVERYONE)) return@launch
        session?.send(Frame.Text(json.encodeToString(Message(MessageTypes.SET_CONTROL_MODE, buildJsonObject { put("control_mode", mode) }))))
    }

    fun approveJoin(userId: String) = scope.launch {
        if (!host || mutableState.value.status != "In room") return@launch
        session?.send(Frame.Text(json.encodeToString(Message(MessageTypes.APPROVE_JOIN, json.encodeToJsonElement(ApproveJoinPayload(userId))))))
        mutableState.value = mutableState.value.copy(joinRequests = mutableState.value.joinRequests.filterNot { it.userId == userId })
    }

    fun rejectJoin(userId: String) = scope.launch {
        if (!host || mutableState.value.status != "In room") return@launch
        session?.send(Frame.Text(json.encodeToString(Message(MessageTypes.REJECT_JOIN, json.encodeToJsonElement(RejectJoinPayload(userId, "The host declined this request."))))))
        mutableState.value = mutableState.value.copy(joinRequests = mutableState.value.joinRequests.filterNot { it.userId == userId })
    }

    private fun configure(raw: String, name: String) {
        serverUrl = validateRoomServerUrl(raw)
        username = name.trim().ifBlank { "XAN listener" }
        savePreferences(serverUrl, username)
        leaving = false
        backgrounded = false
        clock.reset()
        mutableState.value = IosRoomUiState(status = "Connecting…")
    }

    private suspend fun openRoom(code: String, create: Boolean) {
        activeRoomCode = code
        val wsUrl = serverUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/room/$code"
        mutableState.value = IosRoomUiState(status = "Connecting…", roomCode = code)
        val socket = client.webSocketSession { url(wsUrl) }
        if (backgrounded) {
            socket.close()
            return
        }
        session = socket
        socket.send(Frame.Text(if (create) json.encodeToString(Message(MessageTypes.CREATE_ROOM, json.encodeToJsonElement(CreateRoomPayload(username)))) else json.encodeToString(Message(MessageTypes.JOIN_ROOM, json.encodeToJsonElement(JoinRoomPayload(code, username))))))
        mutableState.value = mutableState.value.copy(status = if (create) "Connected" else "Waiting for approval", error = null)
        startPing(socket)
        readSocket(socket)
        if (!leaving && !backgrounded && sessionToken != null) reconnect()
    }

    private suspend fun handle(raw: String) {
        runCatching {
            val message = json.decodeFromString<Message>(raw)
            val payload = message.payload ?: return
            when (message.type) {
                MessageTypes.ROOM_CREATED -> {
                    val result = json.decodeFromString<RoomCreatedPayload>(payload.toString())
                    sessionToken = result.sessionToken
                    userId = result.userId
                    reconnectCount = 0
                    host = true
                    sessionStore?.save(SavedRoomSession(result.sessionToken, result.roomCode, serverUrl, username))
                    result.state?.let { state -> mutableState.value = mutableState.value.copy(users = state.users, controlMode = state.controlMode, currentTrack = state.currentTrack, playing = state.isPlaying, position = clock.playbackPosition(state.position, state.isPlaying, state.lastUpdate, (state.currentTrack?.duration ?: 0)), positionReceivedAtMillis = iosRoomElapsedMillis(), queue = state.queue) }
                    mutableState.value = mutableState.value.copy(status = "In room", roomCode = result.roomCode, isHost = true, error = null, message = "Share room code ${result.roomCode}.")
                }
                MessageTypes.JOIN_APPROVED -> {
                    val result = json.decodeFromString<JoinApprovedPayload>(payload.toString())
                    sessionToken = result.sessionToken
                    userId = result.userId
                    reconnectCount = 0
                    host = result.state.hostId == result.userId
                    sessionStore?.save(SavedRoomSession(result.sessionToken, result.roomCode, serverUrl, username))
                    mutableState.value = mutableState.value.copy(status = "In room", roomCode = result.roomCode, isHost = host, users = result.state.users, controlMode = result.state.controlMode, currentTrack = result.state.currentTrack, playing = result.state.isPlaying, position = clock.playbackPosition(result.state.position, result.state.isPlaying, result.state.lastUpdate, (result.state.currentTrack?.duration ?: 0)), positionReceivedAtMillis = iosRoomElapsedMillis(), queue = result.state.queue, error = null)
                }
                MessageTypes.RECONNECTED -> {
                    val result = json.decodeFromString<ReconnectedPayload>(payload.toString())
                    reconnectCount = 0
                    userId = result.userId
                    host = result.isHost
                    mutableState.value = mutableState.value.copy(status = "In room", roomCode = result.roomCode, isHost = host, users = result.state.users, controlMode = result.state.controlMode, currentTrack = result.state.currentTrack, playing = result.state.isPlaying, position = clock.playbackPosition(result.state.position, result.state.isPlaying, result.state.lastUpdate, (result.state.currentTrack?.duration ?: 0)), positionReceivedAtMillis = iosRoomElapsedMillis(), queue = result.state.queue, error = null)
                }
                MessageTypes.SYNC_STATE -> {
                    val result = json.decodeFromString<SyncStatePayload>(payload.toString())
                    mutableState.value = mutableState.value.copy(currentTrack = result.currentTrack, playing = result.isPlaying, controlMode = result.controlMode ?: mutableState.value.controlMode, position = clock.playbackPosition(result.position, result.isPlaying, result.lastUpdate, (result.currentTrack?.duration ?: 0)), positionReceivedAtMillis = iosRoomElapsedMillis(), queue = result.queue ?: mutableState.value.queue)
                }
                MessageTypes.USER_JOINED -> {
                    val result = json.decodeFromString<UserJoinedPayload>(payload.toString())
                    val list = mutableState.value.users.filterNot { it.userId == result.userId } + UserInfo(result.userId, result.username, false)
                    mutableState.value = mutableState.value.copy(users = list, joinRequests = mutableState.value.joinRequests.filterNot { it.userId == result.userId }, message = "${result.username} joined.")
                }
                MessageTypes.JOIN_REQUEST -> {
                    val request = json.decodeFromString<JoinRequestPayload>(payload.toString())
                    if (host) mutableState.value = mutableState.value.copy(joinRequests = (mutableState.value.joinRequests.filterNot { it.userId == request.userId } + request).take(32))
                }
                MessageTypes.USER_LEFT -> {
                    val result = json.decodeFromString<UserLeftPayload>(payload.toString())
                    mutableState.value = mutableState.value.copy(users = mutableState.value.users.filterNot { it.userId == result.userId }, message = "${result.username} left.")
                }
                MessageTypes.USER_RECONNECTED, MessageTypes.USER_DISCONNECTED -> {
                    val result = json.decodeFromString<UserReconnectedPayload>(payload.toString())
                    val online = message.type == MessageTypes.USER_RECONNECTED
                    val list = mutableState.value.users.map { if (it.userId == result.userId) it.copy(isConnected = online) else it }
                    mutableState.value = mutableState.value.copy(users = list)
                }
                MessageTypes.HOST_CHANGED -> {
                    val result = json.decodeFromString<HostChangedPayload>(payload.toString())
                    host = result.newHostId == userId
                    mutableState.value = mutableState.value.copy(isHost = host, joinRequests = if (host) mutableState.value.joinRequests else emptyList())
                }
                MessageTypes.CONTROL_MODE_CHANGED -> {
                    val result = json.decodeFromString<ControlModeChangedPayload>(payload.toString())
                    mutableState.value = mutableState.value.copy(controlMode = result.controlMode)
                }
                MessageTypes.SYNC_PLAYBACK -> {
                    val result = json.decodeFromString<PlaybackActionPayload>(payload.toString())
                    val previous = mutableState.value
                    val track = result.trackInfo ?: previous.currentTrack
                    val playing = when (result.action) { PlaybackActions.PLAY, PlaybackActions.CHANGE_TRACK -> true; PlaybackActions.PAUSE -> false; else -> previous.playing }
                    val position = result.position?.let { clock.playbackPosition(it, playing, result.serverTime ?: 0L, (track?.duration ?: 0)) }
                        ?: advanceIosRoomPosition(previous.position, previous.playing, previous.positionReceivedAtMillis, iosRoomElapsedMillis(), (track?.duration ?: 0))
                    mutableState.value = previous.copy(currentTrack = track, playing = playing, position = position, positionReceivedAtMillis = iosRoomElapsedMillis(), queue = if (result.action == PlaybackActions.SYNC_QUEUE) result.queue ?: previous.queue else previous.queue)
                    if (result.action in setOf(PlaybackActions.SKIP_NEXT, PlaybackActions.SKIP_PREV, PlaybackActions.QUEUE_ADD, PlaybackActions.QUEUE_REMOVE, PlaybackActions.QUEUE_CLEAR)) {
                        session?.send(Frame.Text(json.encodeToString(Message(MessageTypes.REQUEST_SYNC, json.encodeToJsonElement(RequestSyncPayload())))))
                    }
                }
                MessageTypes.PONG -> {
                    val result = json.decodeFromString<PongPayload>(payload.toString())
                    if (clock.acceptPong(result.clientTime, result.serverTime)) {
                        session?.send(Frame.Text(json.encodeToString(Message(MessageTypes.REQUEST_SYNC, json.encodeToJsonElement(RequestSyncPayload(lightweight = true))))))
                    }
                }
                MessageTypes.ERROR -> mutableState.value = mutableState.value.copy(error = json.decodeFromString<ErrorPayload>(payload.toString()).message)
                MessageTypes.JOIN_REJECTED -> {
                    val reason = json.decodeFromString<JoinRejectedPayload>(payload.toString()).reason
                    leaving = true
                    activeRoomCode = null
                    sessionToken = null
                    sessionStore?.save(null)
                    mutableState.value = IosRoomUiState(error = reason)
                }
                MessageTypes.KICKED -> {
                    val reason = json.decodeFromString<KickedPayload>(payload.toString()).reason
                    sessionStore?.save(null)
                    sessionToken = null
                    leaving = true
                    activeRoomCode = null
                    mutableState.value = mutableState.value.copy(status = "Disconnected", roomCode = null, isHost = false, currentTrack = null, playing = false, position = 0, queue = emptyList(), error = reason)
                }
                MessageTypes.ROOM_CLOSED -> {
                    val reason = json.decodeFromString<RoomClosedPayload>(payload.toString()).reason ?: "The room was closed."
                    sessionStore?.save(null)
                    sessionToken = null
                    leaving = true
                    activeRoomCode = null
                    mutableState.value = mutableState.value.copy(status = "Disconnected", roomCode = null, isHost = false, currentTrack = null, playing = false, position = 0, queue = emptyList(), error = reason)
                }
            }
        }.onFailure { mutableState.value = mutableState.value.copy(error = it.message ?: "Could not read a room update.") }
    }

    private suspend fun reconnectSaved(saved: SavedRoomSession) {
        if (resuming || leaving || backgrounded) return
        resuming = true
        val code = saved.roomCode
        val url = saved.serverUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/room/$code"
        try {
            val socket = client.webSocketSession { url(url) }
            if (backgrounded) {
                socket.close()
                resuming = false
                return
            }
            session = socket
            startPing(socket)
            socket.send(Frame.Text(json.encodeToString(Message(MessageTypes.RECONNECT, json.encodeToJsonElement(ReconnectPayload(saved.sessionToken))))))
            // Do not route native controls to the server until it has accepted
            // the saved token and returned a RECONNECTED snapshot.
            mutableState.value = IosRoomUiState(status = "Reconnecting…", roomCode = code)
            resuming = false
            readSocket(socket)
            if (!leaving && !backgrounded && sessionToken != null) reconnect()
        } catch (cancelled: CancellationException) {
            resuming = false
            session = null
            throw cancelled
        } catch (failure: Throwable) {
            resuming = false
            session = null
            mutableState.value = IosRoomUiState(status = "Disconnected", roomCode = code, error = failure.message ?: "Could not restore the saved room session.")
            if (!leaving && !backgrounded && sessionToken != null) reconnect()
        }
    }

    private suspend fun reconnect() {
        if (reconnecting) return
        val code = activeRoomCode ?: return
        val token = sessionToken ?: return
        reconnecting = true
        try {
            while (!leaving && !backgrounded && session == null && kotlinx.coroutines.currentCoroutineContext().isActive) {
                reconnectCount++
                mutableState.value = mutableState.value.copy(status = "Reconnecting $reconnectCount…")
                delay(roomReconnectDelayMillis(reconnectCount))
                try {
                    val wsUrl = serverUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/room/$code"
                    val socket = client.webSocketSession { url(wsUrl) }
                    session = socket
                    startPing(socket)
                    socket.send(Frame.Text(json.encodeToString(Message(MessageTypes.RECONNECT, json.encodeToJsonElement(ReconnectPayload(token))))))
                    mutableState.value = mutableState.value.copy(status = "Reconnecting…", error = null)
                    readSocket(socket)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    session = null
                    mutableState.value = mutableState.value.copy(status = "Disconnected", error = failure.message ?: "Room reconnection failed.")
                }
            }
        } finally {
            reconnecting = false
        }
    }

    private fun startPing(socket: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession) = scope.launch {
        clock.reset()
        while (isActive && session === socket) {
            runCatching { socket.send(Frame.Text(json.encodeToString(Message(MessageTypes.PING, json.encodeToJsonElement(PingPayload(clock.beginPing())))))) }
            delay(10_000)
        }
    }

    private suspend fun readSocket(socket: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession) {
        try {
            for (frame in socket.incoming) if (frame is Frame.Text && session === socket) handle(frame.readText())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Normal closes and transport errors share the reconnect path.
        } finally {
            if (session === socket) session = null
        }
    }

    private fun httpBase(url: String) = url.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
}

internal fun roomReconnectDelayMillis(attempt: Int): Long = 1_000L shl (attempt - 1).coerceIn(0, 5)

interface RoomSessionStore {
    fun load(): SavedRoomSession?
    fun save(session: SavedRoomSession?)
}

data class SavedRoomSession(
    val sessionToken: String,
    val roomCode: String,
    val serverUrl: String,
    val username: String,
)

data class IosRoomUiState(
    val status: String = "Disconnected",
    val roomCode: String? = null,
    val isHost: Boolean = false,
    val controlMode: String = ControlModes.OWNER,
    val users: List<UserInfo> = emptyList(),
    val joinRequests: List<JoinRequestPayload> = emptyList(),
    val currentTrack: TrackInfo? = null,
    val playing: Boolean = false,
    val position: Long = 0,
    val positionReceivedAtMillis: Long = 0,
    val queue: List<TrackInfo> = emptyList(),
    val message: String? = null,
    val error: String? = null,
)


internal fun validateRoomServerUrl(raw: String): String {
    val normalized = raw.trim().trimEnd('/')
    require(normalized.startsWith("wss://") || normalized.startsWith("ws://")) {
        "Use a Listen Together URL starting with wss:// or ws://."
    }
    val parsed = Url(normalized)
    require(parsed.host.isNotBlank() && parsed.fragment.isEmpty()) { "Enter a server origin without a fragment." }
    require(parsed.user.isNullOrEmpty() && parsed.password.isNullOrEmpty()) { "Do not include a username or password in the server URL." }
    require((parsed.encodedPath.isEmpty() || parsed.encodedPath == "/") && parsed.parameters.isEmpty()) { "Enter only the room server origin, without a path or query." }
    require(parsed.protocol.name == "wss" || parsed.host.lowercase().removePrefix("[").removeSuffix("]") in listOf("localhost", "127.0.0.1", "::1")) {
        "Remote room servers must use wss:// to protect your session."
    }
    return normalized
}

expect fun newRoomHttpClient(): HttpClient
expect fun readIosPreference(key: String): String?
expect fun writeIosPreference(key: String, value: String)
