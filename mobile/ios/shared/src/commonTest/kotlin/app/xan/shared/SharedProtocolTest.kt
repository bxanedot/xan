package app.xan.shared

import app.xan.music.listentogether.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class SharedProtocolTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun roomMessagesMatchAndroidJsonContract() {
        val message = Message(MessageTypes.JOIN_ROOM, json.encodeToJsonElement(JoinRoomPayload("AB12CD", "Bxane")))
        val encoded = json.encodeToString(message)
        assertTrue(encoded.contains("\"type\":\"join_room\""))
        assertTrue(encoded.contains("\"room_code\":\"AB12CD\""))
        assertEquals(MessageTypes.JOIN_ROOM, json.decodeFromString<Message>(encoded).type)
    }

    @Test
    fun remoteRoomServersRequireTlsAndBareOrigin() {
        assertEquals("wss://rooms.example.com", validateRoomServerUrl("  wss://rooms.example.com/  "))
        assertFailsWith<IllegalArgumentException> { validateRoomServerUrl("ws://rooms.example.com") }
        assertFailsWith<IllegalArgumentException> { validateRoomServerUrl("wss://user:pass@rooms.example.com") }
        assertFailsWith<IllegalArgumentException> { validateRoomServerUrl("wss://rooms.example.com/path") }
        assertEquals("ws://[::1]:8080", validateRoomServerUrl("ws://[::1]:8080"))
    }

    @Test
    fun roomStateAcceptsNewAndLegacyDefaults() {
        val oldRoom = json.decodeFromString<RoomState>("""{"room_code":"AB12CD","host_id":"h1","users":[],"is_playing":false,"position":0,"last_update":0}""")
        assertEquals(ControlModes.OWNER, oldRoom.controlMode)
        val nextRoom = oldRoom.copy(controlMode = ControlModes.EVERYONE)
        assertEquals(ControlModes.EVERYONE, json.decodeFromString<RoomState>(json.encodeToString(nextRoom)).controlMode)
    }
}
