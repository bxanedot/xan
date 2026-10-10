package app.xan.desktop

import org.junit.Assert.*
import org.junit.Test

class DesktopRoomServersTest {
    @Test fun defaultsUseCurrentAndroidProvidersAndMigrateLegacyRenderEndpoints() {
        assertEquals(XAN_ROOM_CLOUDFLARE_URL, resolveDesktopRoomServerUrl(""))
        assertEquals(XAN_ROOM_CLOUDFLARE_URL, resolveDesktopRoomServerUrl("wss://"))
        assertEquals("wss://custom.example.test", resolveDesktopRoomServerUrl(" wss://custom.example.test/ "))
        assertTrue(desktopRoomServerProviders.any { it.name == "Cloudflare" && it.url == XAN_ROOM_CLOUDFLARE_URL })
        assertTrue(desktopRoomServerProviders.any { it.name == "Hugging Face" })
        assertEquals(XAN_ROOM_CLOUDFLARE_URL, resolveDesktopRoomServerUrl("wss://xan-listen-together.onrender.com"))
    }

    @Test fun rejectsEndpointsThatWouldLeakCredentialsOrAppendRoomRoutesIncorrectly() {
        listOf("https://rooms.example.test", "ws://rooms.example.test", "wss://user:secret@rooms.example.test", "wss://rooms.example.test/api", "wss://rooms.example.test?token=secret", "wss://rooms.example.test#room").forEach {
            assertNull(it, normalizeDesktopRoomServerUrl(it))
        }
        assertEquals("ws://localhost:8080", normalizeDesktopRoomServerUrl("ws://localhost:8080/"))
        assertEquals("ws://[::1]:8080", normalizeDesktopRoomServerUrl("ws://[::1]:8080"))
    }
}
