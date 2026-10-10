package app.xan.music.listentogether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListenTogetherServersTest {
    @Test fun `empty settings migrate to the operated default without replacing custom servers`() {
        assertEquals(ListenTogetherServers.defaultUrl(ListenTogetherProvider.CLOUDFLARE), ListenTogetherServers.resolveServerUrl(""))
        assertEquals("wss://my-sync.example.com", ListenTogetherServers.resolveServerUrl("wss://my-sync.example.com/"))
        assertEquals(ListenTogetherServers.defaultUrl(ListenTogetherProvider.CLOUDFLARE), ListenTogetherServers.resolveServerUrl("wss://xan-listen-together.onrender.com"))
        assertEquals(ListenTogetherProvider.CLOUDFLARE, ListenTogetherServers.providerForUrl(""))
        assertEquals(ListenTogetherProvider.CLOUDFLARE, ListenTogetherServers.providerForUrl("wss://my-sync.example.com"))
    }

    @Test fun `provider choices have stable persisted identifiers`() {
        assertEquals(ListenTogetherProvider.CLOUDFLARE, ListenTogetherProvider.fromStorage("cloudflare"))
        assertEquals(ListenTogetherProvider.HUGGING_FACE, ListenTogetherProvider.fromStorage("huggingface"))
        assertEquals(ListenTogetherProvider.CLOUDFLARE, ListenTogetherProvider.fromStorage("render"))
        assertEquals(ListenTogetherProvider.CLOUDFLARE, ListenTogetherProvider.fromStorage("custom"))
        assertEquals(ListenTogetherProvider.CLOUDFLARE, ListenTogetherProvider.fromStorage("unknown"))
    }

    @Test fun `configured Android providers retain Cloudflare as default and expose Hugging Face`() {
        assertEquals("wss://xan-listen-together.bxane.workers.dev", ListenTogetherServers.defaultServerUrl)
        assertEquals(ListenTogetherProvider.CLOUDFLARE, ListenTogetherServers.providerForUrl("wss://xan-listen-together.onrender.com"))
        assertEquals("wss://xan-listen-together.bxane.workers.dev", ListenTogetherServers.defaultUrl(ListenTogetherProvider.CLOUDFLARE))
        assertEquals(setOf(ListenTogetherProvider.CLOUDFLARE, ListenTogetherProvider.HUGGING_FACE), ListenTogetherProvider.entries.toSet())
    }

    @Test fun `normalizes valid websocket endpoints`() {
        assertEquals("wss://sync.example.com", ListenTogetherServers.normalizeServerUrl(" wss://sync.example.com/ "))
        assertEquals("ws://localhost:8787/sync", ListenTogetherServers.normalizeServerUrl("ws://localhost:8787/sync"))
    }

    @Test fun `rejects malformed or unsafe websocket endpoints`() {
        listOf("", "https://sync.example.com", "wss://", "wss://user@sync.example.com", "wss://sync.example.com?token=x")
            .forEach { assertNull(ListenTogetherServers.normalizeServerUrl(it)) }
    }
}
