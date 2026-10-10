/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.listentogether

import app.xan.music.BuildConfig
import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
data class ListenTogetherServer(
    val name: String,
    val url: String,
    val location: String,
    val operator: String
)

enum class ListenTogetherProvider(
    val storageValue: String,
    val displayName: String,
    val setupGuideUrl: String?,
    val dashboardUrl: String?,
) {
    CLOUDFLARE(
        "cloudflare",
        "Cloudflare",
        "https://github.com/bxanedot/xan/blob/main/backend/listen-together/README.md#cloudflare-worker",
        "https://dash.cloudflare.com/",
    ),
    HUGGING_FACE(
        "huggingface",
        "Hugging Face",
        "https://github.com/bxanedot/xan/blob/main/backend/listen-together/README.huggingface.md",
        "https://huggingface.co/new-space",
    ),
    ;

    companion object {
        fun fromStorage(value: String): ListenTogetherProvider =
            entries.firstOrNull { it.storageValue == value } ?: CLOUDFLARE
    }
}

object ListenTogetherServers {
    fun defaultUrl(provider: ListenTogetherProvider): String = when (provider) {
        ListenTogetherProvider.CLOUDFLARE -> BuildConfig.LISTEN_TOGETHER_CLOUDFLARE_URL
        ListenTogetherProvider.HUGGING_FACE -> BuildConfig.LISTEN_TOGETHER_HUGGING_FACE_URL
    }

    val servers: List<ListenTogetherServer> = ListenTogetherProvider.entries.mapNotNull { provider ->
        normalizeServerUrl(defaultUrl(provider))?.let { url ->
            ListenTogetherServer(provider.displayName, url, provider.displayName, "XAN")
        }
    }

    val defaultServerUrl: String = defaultUrl(ListenTogetherProvider.CLOUDFLARE)

    // Older installs may have persisted an empty URL. Honour custom endpoints,
    // but let those installs use the operated default without a data reset.
    fun resolveServerUrl(savedUrl: String): String {
        val normalized = normalizeServerUrl(savedUrl) ?: return defaultServerUrl
        val host = runCatching { URI(normalized).host }.getOrNull()
        return if (host?.endsWith(".onrender.com", ignoreCase = true) == true) defaultServerUrl else normalized
    }

    fun providerForUrl(url: String): ListenTogetherProvider =
        ListenTogetherProvider.entries.firstOrNull {
            defaultUrl(it).isNotBlank() && defaultUrl(it) == resolveServerUrl(url)
        } ?: ListenTogetherProvider.CLOUDFLARE

    /** Normalize a user-entered WebSocket endpoint and reject malformed URLs early. */
    fun normalizeServerUrl(value: String): String? {
        val candidate = value.trim().trimEnd('/')
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("ws", "wss") || uri.host.isNullOrBlank()) return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        return candidate
    }

    fun findByUrl(url: String): ListenTogetherServer? = servers.firstOrNull { it.url == url }
}
