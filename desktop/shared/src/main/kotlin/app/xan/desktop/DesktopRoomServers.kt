package app.xan.desktop

import java.net.URI

internal data class DesktopRoomServerProvider(val name: String, val url: String)

internal val desktopRoomServerProviders = listOf(
    DesktopRoomServerProvider("Cloudflare", XAN_ROOM_CLOUDFLARE_URL),
    DesktopRoomServerProvider("Hugging Face", XAN_ROOM_HUGGING_FACE_URL),
)

internal fun resolveDesktopRoomServerUrl(saved: String): String =
    normalizeDesktopRoomServerUrl(saved)
        ?.takeUnless { runCatching { URI(it).host?.endsWith(".onrender.com", ignoreCase = true) == true }.getOrDefault(false) }
        ?: normalizeDesktopRoomServerUrl(XAN_ROOM_CLOUDFLARE_URL).orEmpty()

internal fun normalizeDesktopRoomServerUrl(value: String): String? {
    val candidate = value.trim().trimEnd('/')
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    if (uri.scheme !in setOf("ws", "wss") || uri.host.isNullOrBlank()) return null
    if (uri.userInfo != null || uri.query != null || uri.fragment != null || uri.path.orEmpty().isNotEmpty()) return null
    val host = uri.host.lowercase().removePrefix("[").removeSuffix("]")
    if (uri.scheme == "ws" && host !in setOf("localhost", "127.0.0.1", "::1")) return null
    return candidate
}
