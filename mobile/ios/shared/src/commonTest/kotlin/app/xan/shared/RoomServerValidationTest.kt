package app.xan.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoomServerValidationTest {
    @Test
    fun acceptsSecureRemoteOriginAndNormalizesTrailingSlash() {
        assertEquals("wss://rooms.example.test", validateRoomServerUrl("  wss://rooms.example.test/  "))
    }

    @Test
    fun allowsInsecureTransportOnlyForLoopbackDevelopment() {
        assertEquals("ws://localhost:8080", validateRoomServerUrl("ws://localhost:8080"))
        assertEquals("ws://127.0.0.1:8080", validateRoomServerUrl("ws://127.0.0.1:8080"))
        assertFailsWith<IllegalArgumentException> { validateRoomServerUrl("ws://rooms.example.test") }
    }

    @Test
    fun rejectsCredentialsPathsAndQueries() {
        assertFailsWith<IllegalArgumentException> { validateRoomServerUrl("wss://user:secret@rooms.example.test") }
        assertFailsWith<IllegalArgumentException> { validateRoomServerUrl("wss://rooms.example.test/api") }
        assertFailsWith<IllegalArgumentException> { validateRoomServerUrl("wss://rooms.example.test?token=secret") }
        assertFailsWith<IllegalArgumentException> { validateRoomServerUrl("wss://rooms.example.test#room") }
        assertFailsWith<IllegalArgumentException> { validateRoomServerUrl("wss://") }
    }

    @Test
    fun usesAndroidDefaultForEmptyLegacySettingsAndPreservesCustomEndpoints() {
        assertEquals(XAN_ROOM_CLOUDFLARE_URL, resolveIosRoomServerUrl(null))
        assertEquals(XAN_ROOM_CLOUDFLARE_URL, resolveIosRoomServerUrl("wss://"))
        assertEquals("wss://rooms.example.test", resolveIosRoomServerUrl(" wss://rooms.example.test/ "))
        assertEquals(XAN_ROOM_CLOUDFLARE_URL, resolveIosRoomServerUrl("wss://xan-listen-together.onrender.com"))
        assertTrue(iosRoomServerProviders.any { it.name == "Cloudflare" && it.url == XAN_ROOM_CLOUDFLARE_URL })
        assertTrue(iosRoomServerProviders.any { it.name == "Hugging Face" })
    }

    @Test
    fun storesNonSecretRoomPreferencesThroughThePlatformStore() {
        val key = "test.room.server.${kotlin.random.Random.nextLong()}"
        writeIosPreference(key, "wss://rooms.example.test")
        assertEquals("wss://rooms.example.test", assertNotNull(readIosPreference(key)))
    }
}
