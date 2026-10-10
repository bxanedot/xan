package app.xan.shared

import app.xan.music.listentogether.TrackInfo
import io.ktor.client.request.post
import io.ktor.client.request.headers
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class IosMusicSearchTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationSeconds: Int?,
    val artworkUrl: String?,
)

internal data class IosMusicArtistResult(
    val browseId: String,
    val name: String,
    val subtitle: String?,
    val artworkUrl: String?,
)

internal data class IosMusicAlbumResult(
    val browseId: String,
    val playlistId: String?,
    val name: String,
    val artist: String?,
    val year: Int?,
    val artworkUrl: String?,
)

internal fun IosMusicSearchTrack.toRoomTrackInfo(): TrackInfo = TrackInfo(
    id = videoId,
    title = title,
    artist = artist,
    album = album,
    duration = (durationSeconds?.toLong()?.coerceAtLeast(0L) ?: 0L) * 1_000L,
    thumbnail = artworkUrl,
)

/** YouTube Music metadata search shared by the iPhone and iPad clients. */
internal object IosMusicSearch {
    private const val endpoint = "https://music.youtube.com/youtubei/v1/search"
    private const val clientVersion = "1.20260213.01.00"
    private const val songFilter = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
    private const val videoFilter = "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D"
    private const val artistFilter = "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"
    private const val albumFilter = "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"
    private const val featuredPlaylistFilter = "EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ%3D"
    private const val communityPlaylistFilter = "EgeKAQQoAEABagoQAxAEEAoQCRAF"
    private val json = Json { ignoreUnknownKeys = true }
    private val client = newRoomHttpClient()

    suspend fun search(query: String): List<IosMusicSearchTrack> {
        return parseIosMusicSearchResponse(searchPayload(query, songFilter))
    }

    suspend fun searchVideos(query: String): List<IosMusicSearchTrack> =
        parseIosMusicSearchResponse(searchPayload(query, videoFilter))

    suspend fun searchArtists(query: String): List<IosMusicArtistResult> =
        parseIosMusicArtistResults(searchPayload(query, artistFilter))

    suspend fun searchAlbums(query: String): List<IosMusicAlbumResult> =
        parseIosMusicAlbumResults(searchPayload(query, albumFilter))

    suspend fun searchCommunityPlaylists(query: String): List<IosMusicHomeItem.Playlist> =
        parseIosMusicPlaylistResults(searchPayload(query, communityPlaylistFilter))

    suspend fun searchFeaturedPlaylists(query: String): List<IosMusicHomeItem.Playlist> =
        parseIosMusicPlaylistResults(searchPayload(query, featuredPlaylistFilter))

    private suspend fun searchPayload(query: String, filter: String): String {
        val normalized = normalizeQuery(query)

        val request = buildJsonObject {
            put("context", iosYouTubeContext())
            put("query", normalized)
            put("params", filter)
        }
        val response = client.post(endpoint) {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Goog-Api-Format-Version", "1")
                append("X-YouTube-Client-Name", "67")
                append("X-YouTube-Client-Version", clientVersion)
                append("Origin", "https://music.youtube.com")
                append("X-Origin", "https://music.youtube.com")
                append("Referer", "https://music.youtube.com/")
                append("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_2 like Mac OS X) AppleWebKit/605.1.15 Version/18.2 Mobile/15E148 Safari/604.1")
                IosCredentialStorage.youtubeCookie()?.let { cookie ->
                    append("Cookie", cookie)
                    if (iosLoginForBrowseEnabled()) {
                        iosSapisidAuthorization(cookie, IOS_YOUTUBE_MUSIC_ORIGIN)?.let { append("Authorization", it) }
                    }
                }
            }
            setBody(request.toString())
        }
        require(response.status.value in 200..299) { "YouTube Music search returned HTTP ${response.status.value}." }
        return response.bodyAsText()
    }
}

private fun normalizeQuery(query: String): String = query.trim().also { normalized ->
    require(normalized.isNotEmpty()) { "Enter a song, artist, or album to search." }
    require(normalized.length <= 200) { "Search text is too long." }
}

internal fun parseIosMusicSearchResponse(payload: String): List<IosMusicSearchTrack> {
    return musicResponsiveRows(payload).mapNotNull(::parseSearchTrack).distinctBy(IosMusicSearchTrack::videoId)
}

internal fun parseIosMusicArtistResults(payload: String): List<IosMusicArtistResult> =
    musicResponsiveRows(payload).mapNotNull(::parseArtistResult).distinctBy(IosMusicArtistResult::browseId)

internal fun parseIosMusicAlbumResults(payload: String): List<IosMusicAlbumResult> =
    musicResponsiveRows(payload).mapNotNull(::parseAlbumResult).distinctBy(IosMusicAlbumResult::browseId)

internal fun parseIosMusicPlaylistResults(payload: String): List<IosMusicHomeItem.Playlist> =
    musicResponsiveRows(payload).mapNotNull(::parsePlaylistResult).distinctBy(IosMusicHomeItem.Playlist::browseId)

internal fun parseIosAccountLibraryRenderer(renderer: JsonObject): IosMusicHomeItem? {
    val browseId = renderer.nestedText("navigationEndpoint", "browseEndpoint", "browseId") ?: return null
    val pageType = renderer.nestedText(
        "navigationEndpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs",
        "browseEndpointContextMusicConfig", "pageType",
    ).orEmpty()
    return when {
        browseId.startsWith("UC") || pageType.contains("ARTIST", ignoreCase = true) ->
            parseArtistResult(renderer)?.let { IosMusicHomeItem.Artist(it) }
        browseId.startsWith("MPREb_") || browseId.startsWith("OLAK") || pageType.contains("ALBUM", ignoreCase = true) ->
            parseAlbumResult(renderer)?.let { IosMusicHomeItem.Album(it) }
        browseId.startsWith("VL") || browseId.startsWith("PL") || pageType.contains("PLAYLIST", ignoreCase = true) ->
            parsePlaylistResult(renderer)
        else -> null
    }
}

private fun musicResponsiveRows(payload: String): List<JsonObject> {
    val root = Json.parseToJsonElement(payload)
    val renderers = mutableListOf<JsonObject>()
    root.visitJsonObjects { value -> value["musicResponsiveListItemRenderer"]?.asJsonObject()?.let(renderers::add) }
    return renderers
}

internal fun parseSearchTrack(renderer: JsonObject): IosMusicSearchTrack? {
    val columns = renderer["flexColumns"]?.asJsonArray().orEmpty()
    val titleRuns = columns.getOrNull(0)?.asJsonObject()
        ?.get("musicResponsiveListItemFlexColumnRenderer")?.asJsonObject()
        ?.get("text")?.asJsonObject()?.get("runs")?.asJsonArray().orEmpty()
    val secondaryRuns = columns.getOrNull(1)?.asJsonObject()
        ?.get("musicResponsiveListItemFlexColumnRenderer")?.asJsonObject()
        ?.get("text")?.asJsonObject()?.get("runs")?.asJsonArray().orEmpty()
    val title = titleRuns.firstOrNull()?.asJsonObject()?.text("text")?.trim().orEmpty()
    if (title.isEmpty()) return null

    val videoId = renderer.nestedText("playlistItemData", "videoId")
        ?: renderer.nestedText("navigationEndpoint", "watchEndpoint", "videoId")
        ?: renderer.nestedText("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint", "videoId")
        ?: return null
    val labels = secondaryRuns.mapNotNull { it.asJsonObject()?.text("text")?.trim()?.takeIf(String::isNotEmpty) }
    val artist = labels.firstOrNull { !it.matches(Regex("[0-9:]+")) && it !in setOf("·", "•", "|", ",") }
        ?: "YouTube Music"
    val album = labels.dropWhile { it != artist }.drop(1)
        .firstOrNull { !it.matches(Regex("[0-9:]+")) && it !in setOf("·", "•", "|") }
    val duration = labels.lastOrNull()?.takeIf { it.matches(Regex("\\d{1,2}:\\d{2}(?::\\d{2})?")) }
        ?.split(':')?.mapNotNull(String::toIntOrNull)?.fold(0) { total, part -> total * 60 + part }
    val thumbnails = renderer.nested("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")?.asJsonArray()
    val artwork = thumbnails?.lastOrNull()?.asJsonObject()?.text("url")

    return IosMusicSearchTrack(videoId, title, artist, album, duration, artwork)
}

private fun parseArtistResult(renderer: JsonObject): IosMusicArtistResult? {
    val browseId = renderer.nestedText("navigationEndpoint", "browseEndpoint", "browseId") ?: return null
    val name = renderer.columnLabels().firstOrNull()?.takeIf(String::isNotBlank) ?: return null
    val subtitle = renderer.columnLabels().drop(1).firstOrNull()?.takeIf(String::isNotBlank)
    return IosMusicArtistResult(browseId, name, subtitle, renderer.artworkUrl())
}

private fun parseAlbumResult(renderer: JsonObject): IosMusicAlbumResult? {
    val browseId = renderer.nestedText("navigationEndpoint", "browseEndpoint", "browseId") ?: return null
    val labels = renderer.columnLabels()
    val name = labels.firstOrNull()?.takeIf(String::isNotBlank) ?: return null
    val playlistId = renderer.nestedText(
        "overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer",
        "playNavigationEndpoint", "watchEndpoint", "playlistId",
    ) ?: renderer.nestedText(
        "overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer",
        "playNavigationEndpoint", "anyWatchEndpoint", "playlistId",
    ) ?: renderer.nestedText(
        "overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer",
        "playNavigationEndpoint", "watchPlaylistEndpoint", "playlistId",
    )
    val metadata = labels.drop(1)
    val year = metadata.firstNotNullOfOrNull { it.toIntOrNull() }
    val artist = metadata.firstOrNull {
        it !in setOf("·", "•", "|") && it != year?.toString() && !it.matches(Regex("\\d{1,2}:\\d{2}"))
    }
    return IosMusicAlbumResult(browseId, playlistId, name, artist, year, renderer.artworkUrl())
}

private fun parsePlaylistResult(renderer: JsonObject): IosMusicHomeItem.Playlist? {
    val browseId = renderer.nestedText("navigationEndpoint", "browseEndpoint", "browseId") ?: return null
    val labels = renderer.columnLabels()
    val title = labels.firstOrNull()?.takeIf(String::isNotBlank) ?: return null
    val playlistId = renderer.nestedText(
        "overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer",
        "playNavigationEndpoint", "watchEndpoint", "playlistId",
    ) ?: renderer.nestedText(
        "overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer",
        "playNavigationEndpoint", "watchPlaylistEndpoint", "playlistId",
    ) ?: renderer.nestedText(
        "overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer",
        "playNavigationEndpoint", "anyWatchEndpoint", "playlistId",
    )
    return IosMusicHomeItem.Playlist(
        browseId = browseId,
        playlistId = playlistId,
        title = title,
        subtitle = labels.drop(1).filterNot { it in setOf("·", "•", "|") }.joinToString(" · ").ifBlank { null },
        artworkUrl = renderer.artworkUrl()?.let(::safeIosArtworkUrl),
    )
}

private fun JsonObject.columnLabels(): List<String> = this["flexColumns"]?.asJsonArray().orEmpty().flatMap { column ->
    column.asJsonObject()?.nested("musicResponsiveListItemFlexColumnRenderer", "text", "runs")
        .asJsonArray().orEmpty().mapNotNull { it.asJsonObject()?.text("text")?.trim()?.takeIf(String::isNotEmpty) }
}

private fun JsonObject.artworkUrl(): String? = nested("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")
    ?.asJsonArray()?.lastOrNull()?.asJsonObject()?.text("url")

private fun JsonElement.visitJsonObjects(visit: (JsonObject) -> Unit) {
    when (this) {
        is JsonObject -> {
            visit(this)
            values.forEach { it.visitJsonObjects(visit) }
        }
        is JsonArray -> forEach { it.visitJsonObjects(visit) }
        else -> Unit
    }
}

private fun JsonElement?.asJsonObject(): JsonObject? = this as? JsonObject
private fun JsonElement?.asJsonArray(): JsonArray? = this as? JsonArray
private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.nested(vararg path: String): JsonElement? {
    var current: JsonElement? = this
    for (key in path) current = current.asJsonObject()?.get(key) ?: return null
    return current
}

private fun JsonObject.nestedText(vararg path: String): String? = nested(*path)?.jsonPrimitive?.contentOrNull
