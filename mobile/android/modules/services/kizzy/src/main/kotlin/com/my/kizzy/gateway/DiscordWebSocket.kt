package com.my.kizzy.gateway

import com.my.kizzy.gateway.entities.Heartbeat
import com.my.kizzy.gateway.entities.Identify.Companion.toIdentifyPayload
import com.my.kizzy.gateway.entities.Payload
import com.my.kizzy.gateway.entities.Ready
import com.my.kizzy.gateway.entities.Resume
import com.my.kizzy.gateway.entities.op.OpCode
import com.my.kizzy.gateway.entities.op.OpCode.DISPATCH
import com.my.kizzy.gateway.entities.op.OpCode.HEARTBEAT
import com.my.kizzy.gateway.entities.op.OpCode.HELLO
import com.my.kizzy.gateway.entities.op.OpCode.IDENTIFY
import com.my.kizzy.gateway.entities.op.OpCode.INVALID_SESSION
import com.my.kizzy.gateway.entities.op.OpCode.PRESENCE_UPDATE
import com.my.kizzy.gateway.entities.op.OpCode.RECONNECT
import com.my.kizzy.gateway.entities.op.OpCode.RESUME
import com.my.kizzy.gateway.entities.presence.Presence
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.logging.Level
import java.util.logging.Level.INFO
import java.util.logging.Logger
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Modified by Zion Huang
 */
open class DiscordWebSocket(
    private val token: String,
    private val os: String = "Android",
    private val browser: String = "Discord Android",
    private val device: String = "Generic Android Device",
) : CoroutineScope {
    private val logger = Logger.getLogger(DiscordWebSocket::class.java.name)
    private val gatewayUrl = "wss://gateway.discord.gg/?v=9&encoding=json"
    private var websocket: DefaultClientWebSocketSession? = null
    private var sequence = 0
    private var sessionId: String? = null
    private var heartbeatInterval = 0L
    private var resumeGatewayUrl: String? = null
    private var heartbeatJob: Job? = null
    private val scopeJob = SupervisorJob()
    private var connectJob: Job? = null
    private var connected = false
    private var lastPresence: Presence? = null
    private var client: HttpClient = HttpClient {
        install(WebSockets)
    }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var reconnectionJob: Job? = null
    private var currentReconnectDelay = INITIAL_RECONNECT_DELAY

    override val coroutineContext: CoroutineContext
        get() = scopeJob + Dispatchers.IO

    fun connect() {
        if (connected || connectJob?.isActive == true) {
            logger.info("Gateway already connected.")
            return
        }
        reconnectionJob?.cancel()
        reconnectionJob = null
        connectJob = launch {
            try {
                val url = resumeGatewayUrl ?: gatewayUrl
                logger.info("Connecting to Discord Gateway at $url")
                websocket = client.webSocketSession(url) {
                    header("User-Agent", "Discord-Android/314013;RNA")
                    header("Accept-Language", "en-US")
                    header("Cache-Control", "no-cache")
                    header("Pragma", "no-cache")
                }
                logger.info("Discord Gateway socket opened; waiting for READY.")
                // start receiving messages
                websocket!!.incoming.receiveAsFlow()
                    .collect {
                        when (it) {
                            is Frame.Text -> {
                                val jsonString = it.readText()
                                onMessage(json.decodeFromString(jsonString))
                            }

                            else -> {}
                        }
                    }
                handleClose()
            } catch (e: Exception) {
                logger.severe("Gateway connection error: ${e.stackTraceToString()}")
                scheduleReconnection()
            }
        }
    }

    private fun scheduleReconnection() {
        if (reconnectionJob?.isActive == true || !scopeJob.isActive) {
            return
        }
        heartbeatJob?.cancel()
        connected = false
        reconnectionJob = launch {
            delay(currentReconnectDelay)
            currentReconnectDelay = (currentReconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY)
            reconnectionJob = null
            logger.info("Attempting to reconnect...")
            connect()
        }
    }


    private suspend fun handleClose() {
        heartbeatJob?.cancel()
        connected = false
        val close = websocket?.closeReason?.await()
        logger.warning("Gateway closed with code: ${close?.code}, reason: ${close?.message}, can_reconnect: ${close?.code?.toInt() == 4000}")
        scheduleReconnection()
    }

    private suspend fun onMessage(payload: Payload) {
        logger.info("Gateway received: op=${payload.op}, seq=${payload.s}, event=${payload.t}")
        payload.s?.let {
            sequence = it
        }
        when (payload.op) {
            DISPATCH -> payload.handleDispatch()
            HEARTBEAT -> sendHeartBeat()
            RECONNECT -> reconnectWebSocket()
            INVALID_SESSION -> handleInvalidSession()
            HELLO -> payload.handleHello()
            else -> {}
        }
    }

    open fun Payload.handleDispatch() {
        when (this.t.toString()) {
            "READY" -> {
                val ready = json.decodeFromJsonElement<Ready>(this.d!!)
                sessionId = ready.sessionId
                resumeGatewayUrl = ready.resumeGatewayUrl + "/?v=9&encoding=json"
                logger.info("Gateway READY: resume_gateway_url updated to $resumeGatewayUrl, session_id updated to $sessionId")
                connected = true
                currentReconnectDelay = INITIAL_RECONNECT_DELAY
                lastPresence?.let { presence -> launch { send(PRESENCE_UPDATE, presence) } }
                return
            }

            "RESUMED" -> {
                logger.info("Gateway: Session Resumed")
                connected = true
                currentReconnectDelay = INITIAL_RECONNECT_DELAY
                lastPresence?.let { presence -> launch { send(PRESENCE_UPDATE, presence) } }
            }

            else -> {}
        }
    }

    private suspend inline fun handleInvalidSession() {
        logger.warning("Gateway: Handling Invalid Session. Sending Identify after 150ms")
        delay(150)
        sendIdentify()
    }

    private suspend inline fun Payload.handleHello() {
        if (sequence > 0 && !sessionId.isNullOrBlank()) {
            sendResume()
        } else {
            sendIdentify()
        }
        heartbeatInterval = json.decodeFromJsonElement<Heartbeat>(this.d!!).heartbeatInterval
        logger.info("Gateway: Setting heartbeatInterval=$heartbeatInterval")
        startHeartbeatJob(heartbeatInterval)
    }

    private suspend fun sendHeartBeat() {
        logger.info("Gateway: Sending $HEARTBEAT with seq: $sequence")
        send(
            op = HEARTBEAT,
            d = if (sequence == 0) "null" else sequence.toString(),
        )
    }

    private suspend inline fun reconnectWebSocket() {
        websocket?.close(
            CloseReason(
                code = 4000,
                message = "Attempting to reconnect"
            )
        )
    }

    private suspend fun sendIdentify() {
        logger.info("Gateway: Sending $IDENTIFY")
        send(
            op = IDENTIFY,
            d = token.toIdentifyPayload(
                os = os,
                browser = browser,
                device = device
            )
        )
    }

    private suspend fun sendResume() {
        logger.info("Gateway: Sending $RESUME")
        send(
            op = RESUME,
            d = Resume(
                seq = sequence,
                sessionId = sessionId,
                token = token
            )
        )
    }

    private fun startHeartbeatJob(interval: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = launch {
            while (isActive) {
                sendHeartBeat()
                delay(interval)
            }
        }
    }

    private fun isSocketConnectedToAccount(): Boolean {
        return connected && websocket?.isActive == true
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun isWebSocketConnected(): Boolean {
        return connected && websocket?.isActive == true
    }

    private suspend inline fun <reified T> send(op: OpCode, d: T?) {
        if (websocket?.isActive == true) {
            val payload = json.encodeToString(
                Payload(
                    op = op,
                    d = json.encodeToJsonElement(d),
                )
            )
            if (op == IDENTIFY) {
                logger.info("Gateway sending payload: [REDACTED IDENTIFY PAYLOAD]")
            } else {
                logger.info("Gateway sending payload: $payload")
            }
            websocket?.send(Frame.Text(payload))
        }
    }

    fun close() {
        reconnectionJob?.cancel()
        connectJob?.cancel()
        heartbeatJob?.cancel()
        heartbeatJob = null
        scopeJob.cancel()
        resumeGatewayUrl = null
        sessionId = null
        connected = false
        lastPresence = null
        runBlocking {
            websocket?.close()
            logger.severe("Gateway: Connection to gateway closed")
        }
        client.close()
    }

    suspend fun sendActivity(presence: Presence) {
        withTimeout(15_000) {
            while (!isSocketConnectedToAccount()) {
                delay(100.milliseconds)
            }
        }
        logger.info("Gateway: Sending $PRESENCE_UPDATE")
        send(
            op = PRESENCE_UPDATE,
            d = presence
        )
        lastPresence = presence
    }
    companion object {
        private val INITIAL_RECONNECT_DELAY = 1.seconds
        private val MAX_RECONNECT_DELAY = 60.seconds
    }
}
