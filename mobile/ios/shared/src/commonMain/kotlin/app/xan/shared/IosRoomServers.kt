package app.xan.shared

internal data class IosRoomServerProvider(val name: String, val url: String)

internal val iosRoomServerProviders = listOf(
    IosRoomServerProvider("Cloudflare", XAN_ROOM_CLOUDFLARE_URL),
    IosRoomServerProvider("Hugging Face", XAN_ROOM_HUGGING_FACE_URL),
)

internal fun resolveIosRoomServerUrl(saved: String?): String {
    val normalized = runCatching { validateRoomServerUrl(saved.orEmpty()) }.getOrElse { return XAN_ROOM_CLOUDFLARE_URL }
    val host = normalized.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
    if (host.endsWith(".onrender.com")) return XAN_ROOM_CLOUDFLARE_URL
    return normalized
}
