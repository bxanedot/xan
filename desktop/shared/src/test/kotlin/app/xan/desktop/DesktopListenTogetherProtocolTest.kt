package app.xan.desktop

import app.xan.music.listentogether.CreateRoomPayload
import app.xan.music.listentogether.JoinRoomPayload
import app.xan.music.listentogether.Message
import app.xan.music.listentogether.MessageTypes
import app.xan.music.listentogether.PingPayload
import app.xan.music.listentogether.PongPayload
import app.xan.music.listentogether.RequestSyncPayload
import app.xan.music.listentogether.RoomCreatedPayload
import app.xan.music.listentogether.RoomState
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopListenTogetherProtocolTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun desktopCreateAndJoinFramesUseAndroidSharedJsonContract() {
        val create = Message(
            MessageTypes.CREATE_ROOM,
            json.encodeToJsonElement(CreateRoomPayload.serializer(), CreateRoomPayload("bxane")),
        )
        val createJson = json.parseToJsonElement(json.encodeToString(create)).jsonObject
        assertEquals("create_room", createJson["type"]?.jsonPrimitive?.content)
        assertEquals("bxane", createJson["payload"]?.jsonObject?.get("username")?.jsonPrimitive?.content)

        val join = Message(
            MessageTypes.JOIN_ROOM,
            json.encodeToJsonElement(JoinRoomPayload.serializer(), JoinRoomPayload("AB12CD", "bxane")),
        )
        val joinJson = json.parseToJsonElement(json.encodeToString(join)).jsonObject
        assertEquals("join_room", joinJson["type"]?.jsonPrimitive?.content)
        assertEquals("AB12CD", joinJson["payload"]?.jsonObject?.get("room_code")?.jsonPrimitive?.content)
    }

    @Test
    fun desktopDecodesAndroidRoomCreatedResponseAndLegacyOptionalRoomState() {
        val raw = """{"type":"room_created","payload":{"room_code":"A1B2C3","user_id":"host-1","session_token":"secret"}}"""
        val message = json.decodeFromString<Message>(raw)
        assertEquals(MessageTypes.ROOM_CREATED, message.type)
        val payload = json.decodeFromString<RoomCreatedPayload>(message.payload.toString())
        assertEquals("A1B2C3", payload.roomCode)
        assertEquals("host-1", payload.userId)
        assertEquals("secret", payload.sessionToken)
        assertEquals(null, payload.state)

        val state = json.decodeFromString<RoomState>("""{"room_code":"A1B2C3","host_id":"host-1","users":[],"is_playing":false,"position":0,"last_update":1}""")
        assertTrue(state.users.isEmpty())
        assertEquals("owner", state.controlMode)
    }

    @Test
    fun desktopClockAndLightweightSyncFramesMatchTheUpdatedAndroidProtocol() {
        val ping = Message(
            MessageTypes.PING,
            json.encodeToJsonElement(PingPayload.serializer(), PingPayload(clientTime = 1_700_000_000_000)),
        )
        val pingPayload = json.parseToJsonElement(json.encodeToString(ping)).jsonObject["payload"]!!.jsonObject
        assertEquals("1700000000000", pingPayload["client_time"]?.jsonPrimitive?.content)

        val pong = json.decodeFromString<PongPayload>("""{"client_time":1700000000000,"server_time":1700000000250}""")
        assertEquals(1_700_000_000_000, pong.clientTime)
        assertEquals(1_700_000_000_250, pong.serverTime)

        val request = Message(
            MessageTypes.REQUEST_SYNC,
            json.encodeToJsonElement(RequestSyncPayload.serializer(), RequestSyncPayload(lightweight = true)),
        )
        val requestPayload = json.parseToJsonElement(json.encodeToString(request)).jsonObject["payload"]!!.jsonObject
        assertEquals("true", requestPayload["lightweight"]?.jsonPrimitive?.content)
    }

    @Test
    fun listenTogetherServerUrlIsValidatedAndPersisted() {
        val original = DesktopBackend.listenTogetherServerUrl()
        try {
            DesktopBackend.setListenTogetherServerUrl("  wss://listen.example.test/  ")
            assertEquals("wss://listen.example.test", DesktopBackend.listenTogetherServerUrl())
            assertTrue(runCatching { DesktopBackend.setListenTogetherServerUrl("https://listen.example.test") }.isFailure)
        } finally {
            DesktopBackend.setListenTogetherServerUrl(original)
        }
    }
}
