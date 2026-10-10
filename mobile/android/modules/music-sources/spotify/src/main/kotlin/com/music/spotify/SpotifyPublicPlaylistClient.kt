package com.music.spotify

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.concurrent.TimeUnit

data class SpotifyPublicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Int,
)

data class SpotifyPublicPlaylist(
    val id: String,
    val name: String,
    val thumbnailUrl: String?,
    val tracks: List<SpotifyPublicTrack>,
    val mayBeTruncated: Boolean,
)

/** Reads only the track preview Spotify publishes in its unauthenticated playlist embed. */
object SpotifyPublicPlaylistClient {
    private const val PREVIEW_LIMIT = 50
    private val trackIdRegex = Regex("^[A-Za-z0-9]{22}$")
    private val nextDataScript = Regex(
        """<script\b(?=[^>]*\bid\s*=\s*["']__NEXT_DATA__["'])[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(15, TimeUnit.SECONDS)
                    readTimeout(30, TimeUnit.SECONDS)
                }
            }
            expectSuccess = false
        }
    }

    suspend fun fetch(link: String): SpotifyPublicPlaylist {
        val id = SpotifyPlaylistRefParser.parse(link)
            ?: throw IllegalArgumentException("Enter a valid Spotify playlist link.")
        val response = client.get("https://open.spotify.com/embed/playlist/$id") {
            header(HttpHeaders.Accept, "text/html")
            header(
                HttpHeaders.UserAgent,
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            )
        }
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Spotify could not open this public playlist (${response.status.value}).")
        }
        return parseHtml(id, response.bodyAsText())
            ?: throw IllegalStateException("Spotify did not provide a public playlist preview. Check that the playlist is public.")
    }

    internal fun parseHtml(expectedId: String, html: String): SpotifyPublicPlaylist? {
        val start = nextDataScript.find(html)?.range?.last?.plus(1) ?: return null
        val end = html.indexOf("</script>", start, ignoreCase = true)
        if (end < 0) return null

        val root = runCatching { json.parseToJsonElement(html.substring(start, end)) as? JsonObject }
            .getOrNull() ?: return null
        val props = root["props"] as? JsonObject ?: return null
        val pageProps = props["pageProps"] as? JsonObject ?: return null
        val state = pageProps["state"] as? JsonObject ?: return null
        val data = state["data"] as? JsonObject ?: return null
        val entity = data["entity"] as? JsonObject ?: return null
        if (entity.string("type") != "playlist" || entity.string("id") != expectedId) return null

        val name = entity.string("name")?.takeIf(String::isNotBlank) ?: return null
        val trackList = entity["trackList"] as? JsonArray ?: return null
        val sources = (entity["coverArt"] as? JsonObject)?.get("sources") as? JsonArray
        val thumbnailUrl = sources?.firstNotNullOfOrNull { (it as? JsonObject)?.string("url") }

        val tracks = trackList.mapNotNull { item ->
            val track = item as? JsonObject ?: return@mapNotNull null
            if (track.string("entityType") != "track") return@mapNotNull null
            val uri = track.string("uri") ?: return@mapNotNull null
            val id = uri.removePrefix("spotify:track:").takeIf(trackIdRegex::matches)
                ?.takeIf { uri == "spotify:track:$it" } ?: return@mapNotNull null
            val title = track.string("title")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val artist = track.string("subtitle").orEmpty()
            val durationMs = (track["duration"] as? JsonPrimitive)?.intOrNull ?: 0
            SpotifyPublicTrack(id, title, artist, durationMs)
        }.distinctBy { it.id }

        return SpotifyPublicPlaylist(
            id = expectedId,
            name = name,
            thumbnailUrl = thumbnailUrl,
            tracks = tracks,
            mayBeTruncated = trackList.size >= PREVIEW_LIMIT,
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
