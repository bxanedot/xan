package app.xan.shared

import io.ktor.client.request.headers
import io.ktor.client.request.post
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal data class IosMusicArtistPage(
    val browseId: String,
    val name: String,
    val description: String?,
    val subscriberCount: String?,
    val monthlyListeners: String?,
    val artworkUrl: String?,
    val songs: List<IosMusicSearchTrack>,
)

internal data class IosMusicAlbumPage(
    val browseId: String,
    val name: String,
    val artist: String?,
    val year: String?,
    val description: String?,
    val artworkUrl: String?,
    val songs: List<IosMusicSearchTrack>,
)

internal data class IosMusicPlaylistPage(
    val browseId: String,
    val name: String,
    val description: String?,
    val artworkUrl: String?,
    val songs: List<IosMusicSearchTrack>,
)

private const val MAX_IOS_MUSIC_LYRICS_CHARS = 192 * 1024

/** Browse details from the same YouTube Music InnerTube service used by search. */
internal object IosMusicCatalog {
    private const val endpoint = "https://music.youtube.com/youtubei/v1/browse"
    private const val nextEndpoint = "https://music.youtube.com/youtubei/v1/next"
    private const val clientVersion = "1.20260213.01.00"
    private val client = newRoomHttpClient()

    suspend fun artist(browseId: String): IosMusicArtistPage =
        parseIosMusicArtistPage(requestBrowse(browseId), browseId)

    suspend fun album(browseId: String): IosMusicAlbumPage =
        parseIosMusicAlbumPage(requestBrowse(browseId), browseId)

    suspend fun playlist(browseId: String): IosMusicPlaylistPage =
        parseIosMusicPlaylistPage(requestBrowse(browseId), browseId)

    suspend fun lyrics(videoId: String): String? {
        require(videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) { "This YouTube Music track has an invalid video ID." }
        val request = buildJsonObject {
            put("context", iosYouTubeContext(dataSyncId = iosActiveDataSyncId()))
            put("videoId", videoId)
        }
        val cookie = IosCredentialStorage.youtubeCookie()
        val response = client.post(nextEndpoint) {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Goog-Api-Format-Version", "1")
                append("X-YouTube-Client-Name", "67")
                append("X-YouTube-Client-Version", clientVersion)
                append("Origin", IOS_YOUTUBE_MUSIC_ORIGIN)
                append("X-Origin", IOS_YOUTUBE_MUSIC_ORIGIN)
                append("Referer", "$IOS_YOUTUBE_MUSIC_ORIGIN/")
                append("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_2 like Mac OS X) AppleWebKit/605.1.15 Version/18.2 Mobile/15E148 Safari/604.1")
                cookie?.let { savedCookie ->
                    append("Cookie", savedCookie)
                    if (iosLoginForBrowseEnabled()) {
                        iosSapisidAuthorization(savedCookie, IOS_YOUTUBE_MUSIC_ORIGIN)?.let { append("Authorization", it) }
                    }
                }
            }
            setBody(request.toString())
        }
        require(response.status.value in 200..299) { "YouTube Music lyrics lookup returned HTTP ${response.status.value}." }
        val lyricsEndpoint = parseIosYouTubeMusicLyricsEndpoint(response.bodyAsText()) ?: return null
        return parseIosYouTubeMusicLyrics(requestBrowse(lyricsEndpoint.browseId, lyricsEndpoint.params))
    }

    suspend fun home(browseId: String = "FEmusic_home", params: String? = null): IosMusicHomePage =
        parseIosMusicHomePage(requestBrowse(browseId, params))

    suspend fun accountLibrary(): IosMusicAccountLibrary = coroutineScope {
        require(IosCredentialStorage.hasYouTubeCookie()) {
            "Connect your YouTube Music account in Settings to load its playlists, albums, and artists."
        }
        val dataSyncId = normalizeIosDataSyncId(readIosPreference("account.dataSyncId"))
        val playlists = async { requestBrowse("FEmusic_liked_playlists", dataSyncId = dataSyncId, authenticate = true) }
        val albums = async { requestBrowse("FEmusic_liked_albums", dataSyncId = dataSyncId, authenticate = true) }
        val artists = async { requestBrowse("FEmusic_library_corpus_artists", dataSyncId = dataSyncId, authenticate = true) }
        parseIosMusicAccountLibrary(listOf(playlists.await(), albums.await(), artists.await()))
    }

    private suspend fun requestBrowse(
        browseId: String,
        params: String? = null,
        dataSyncId: String? = iosActiveDataSyncId(),
        authenticate: Boolean = iosLoginForBrowseEnabled(),
    ): String {
        require(browseId.matches(Regex("[A-Za-z0-9_-]{4,200}"))) { "This YouTube Music page has an invalid ID." }
        val request = buildJsonObject {
            put("context", iosYouTubeContext(dataSyncId = dataSyncId))
            put("browseId", browseId)
            params?.takeIf(String::isNotBlank)?.let { put("params", it) }
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
                    if (authenticate) {
                        iosSapisidAuthorization(cookie, IOS_YOUTUBE_MUSIC_ORIGIN)?.let { append("Authorization", it) }
                    }
                }
            }
            setBody(request.toString())
        }
        require(response.status.value in 200..299) { "YouTube Music page returned HTTP ${response.status.value}." }
        return response.bodyAsText()
    }
}

internal fun parseIosMusicArtistPage(payload: String, browseId: String): IosMusicArtistPage {
    val root = Json.parseToJsonElement(payload).asObjectOrNull()
        ?: error("YouTube Music returned an invalid artist page.")
    val immersive = root.nestedObject("header", "musicImmersiveHeaderRenderer")
    val visual = root.nestedObject("header", "musicVisualHeaderRenderer")
    val detail = root.nestedObject("header", "musicDetailHeaderRenderer")
    val standard = root.nestedObject("header", "musicHeaderRenderer")
    val name = sequenceOf(immersive, visual, detail, standard)
        .filterNotNull()
        .mapNotNull { it.nestedText("title", "runs", "0", "text") }
        .firstOrNull { it.isNotBlank() }
        ?: error("YouTube Music did not include an artist name.")
    val description = root.findDescription()
        ?: immersive?.nestedText("description", "runs", "0", "text")
        ?: immersive?.allRunText("description")?.takeIf(String::isNotBlank)
    val subscriberCount = immersive?.nestedText("subscriptionButton2", "subscribeButtonRenderer", "subscriberCountWithSubscribeText", "runs", "0", "text")
        ?: immersive?.nestedText("subscriptionButton", "subscribeButtonRenderer", "longSubscriberCountText", "runs", "0", "text")
        ?: immersive?.nestedText("subscriptionButton", "subscribeButtonRenderer", "shortSubscriberCountText", "runs", "0", "text")
    val monthlyListeners = immersive?.nestedText("monthlyListenerCount", "runs", "0", "text")
    val artwork = sequenceOf(immersive, visual, detail)
        .filterNotNull()
        .mapNotNull(JsonObject::thumbnailUrl)
        .firstOrNull()
    val songs = root.trackRows().map { track ->
        if (track.artist == "YouTube Music") track.copy(artist = name) else track
    }.distinctBy(IosMusicSearchTrack::videoId)

    return IosMusicArtistPage(browseId, name, description, subscriberCount, monthlyListeners, artwork, songs)
}

internal fun parseIosMusicAlbumPage(payload: String, browseId: String): IosMusicAlbumPage {
    val root = Json.parseToJsonElement(payload).asObjectOrNull()
        ?: error("YouTube Music returned an invalid album page.")
    val responsive = root.findObject("musicResponsiveHeaderRenderer")
    val detail = root.nestedObject("header", "musicDetailHeaderRenderer")
    val name = responsive?.nestedText("title", "runs", "0", "text")
        ?: detail?.nestedText("title", "runs", "0", "text")
        ?: error("YouTube Music did not include an album name.")
    val artist = responsive?.allRunText("straplineTextOne")?.takeIf(String::isNotBlank)
        ?: responsive?.allRunText("subtitle")?.takeIf(String::isNotBlank)
        ?: detail?.allRunText("subtitle")?.takeIf(String::isNotBlank)
    val year = sequenceOf(responsive?.allRunText("subtitle"), detail?.allRunText("subtitle"))
        .filterNotNull()
        .flatMap { it.split(Regex("[·•|]" )).asSequence() }
        .map(String::trim)
        .firstOrNull { it.matches(Regex("\\d{4}")) }
    val description = root.findDescription()
    val artwork = responsive?.thumbnailUrl() ?: detail?.thumbnailUrl()
    val songs = root.trackRows().distinctBy(IosMusicSearchTrack::videoId)
    return IosMusicAlbumPage(browseId, name, artist, year, description, artwork, songs)
}

internal fun parseIosMusicPlaylistPage(payload: String, browseId: String): IosMusicPlaylistPage {
    val root = Json.parseToJsonElement(payload).asObjectOrNull()
        ?: error("YouTube Music returned an invalid playlist page.")
    val responsive = root.findObject("musicResponsiveHeaderRenderer")
    val editable = root.findObject("musicEditablePlaylistDetailHeaderRenderer")
    val detail = root.findObject("musicDetailHeaderRenderer")
    val name = sequenceOf(responsive, editable, detail)
        .filterNotNull()
        .mapNotNull { it.nestedText("title", "runs", "0", "text") }
        .firstOrNull(String::isNotBlank)
        ?: error("YouTube Music did not include a playlist name.")
    val artwork = sequenceOf(responsive, editable, detail).filterNotNull()
        .mapNotNull(JsonObject::thumbnailUrl).firstOrNull()
    val songs = root.trackRows().distinctBy(IosMusicSearchTrack::videoId)
    return IosMusicPlaylistPage(browseId, name, root.findDescription(), artwork, songs)
}

internal data class IosYouTubeMusicLyricsEndpoint(val browseId: String, val params: String?)

internal fun parseIosYouTubeMusicLyricsEndpoint(payload: String): IosYouTubeMusicLyricsEndpoint? {
    val root = runCatching { Json.parseToJsonElement(payload).asObjectOrNull() }.getOrNull() ?: return null
    val endpoint = root.nested(
        "contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer",
        "watchNextTabbedResultsRenderer", "tabs", "1", "tabRenderer", "endpoint", "browseEndpoint",
    ).asObjectOrNull() ?: return null
    val browseId = endpoint.text("browseId")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{4,200}")) } ?: return null
    val params = endpoint.text("params")?.takeIf(String::isNotBlank)
    return IosYouTubeMusicLyricsEndpoint(browseId, params)
}

internal fun parseIosYouTubeMusicLyrics(payload: String): String? {
    if (payload.isBlank() || payload.length > MAX_IOS_MUSIC_LYRICS_CHARS) return null
    val root = runCatching { Json.parseToJsonElement(payload).asObjectOrNull() }.getOrNull() ?: return null
    val shelf = root.findObject("musicDescriptionShelfRenderer") ?: return null
    val text = shelf.nested("description", "runs").asArrayOrNull()?.textRuns()?.joinToString("")?.trim()
    return text?.takeIf(String::isNotBlank)?.take(MAX_IOS_MUSIC_LYRICS_CHARS)
}

private fun JsonObject.findDescription(): String? {
    val descriptions = mutableListOf<String>()
    visitObjects { value ->
        val runs = value["musicDescriptionShelfRenderer"].asObjectOrNull()
            ?.nested("description", "runs")?.asArrayOrNull()
        if (runs != null) {
            descriptions += runs.textRuns()
        }
    }
    return descriptions.joinToString("").trim().takeIf(String::isNotBlank)
}

private fun JsonObject.trackRows(): List<IosMusicSearchTrack> {
    val renderers = mutableListOf<JsonObject>()
    visitObjects { value -> value["musicResponsiveListItemRenderer"].asObjectOrNull()?.let(renderers::add) }
    return renderers.mapNotNull(::parseSearchTrack)
}

private fun JsonObject.findObject(key: String): JsonObject? {
    var found: JsonObject? = null
    visitObjects { value -> if (found == null) found = value[key].asObjectOrNull() }
    return found
}

private fun JsonObject.visitObjects(visit: (JsonObject) -> Unit) {
    visit(this)
    values.forEach { value ->
        when (value) {
            is JsonObject -> value.visitObjects(visit)
            is JsonArray -> value.forEach { item -> item.asObjectOrNull()?.visitObjects(visit) }
            else -> Unit
        }
    }
}

private fun JsonObject.thumbnailUrl(): String? =
    nested("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails").asArrayOrNull()
        ?.lastOrNull()?.asObjectOrNull()?.text("url")?.let(::safeIosArtworkUrl)

private fun JsonObject.allRunText(key: String): String? = nested("$key", "runs").asArrayOrNull()?.textRuns()?.joinToString("")

private fun JsonObject.nestedText(vararg path: String): String? = nested(*path)?.asObjectOrNull()?.text("text")
    ?: nested(*path)?.jsonPrimitive?.contentOrNull

private fun JsonObject.nestedObject(vararg path: String): JsonObject? = nested(*path).asObjectOrNull()

private fun JsonObject.nested(vararg path: String): JsonElement? {
    var current: JsonElement? = this
    for (part in path) {
        current = when {
            part.toIntOrNull() != null -> current.asArrayOrNull()?.getOrNull(part.toInt())
            else -> current.asObjectOrNull()?.get(part)
        } ?: return null
    }
    return current
}

private fun JsonArray.textRuns(): List<String> = mapNotNull { it.asObjectOrNull()?.text("text") }
private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject
private fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray
