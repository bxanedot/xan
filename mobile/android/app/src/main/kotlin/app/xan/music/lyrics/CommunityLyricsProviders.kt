package app.xan.music.lyrics

import android.content.Context
import app.xan.music.betterlyrics.TTMLParser
import app.xan.music.constants.EnableBiniLyricsKey
import app.xan.music.constants.EnableUnisonLyricsKey
import app.xan.music.utils.dataStore
import app.xan.music.utils.get
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

/** Direct, read-only clients for the BiniLyrics and Unison public APIs. */
private object CommunityLyricsHttp {
    const val BINI_SEARCH = "https://lyrics-api.binimum.org/"
    const val UNISON = "https://unison.boidu.dev/lyrics"

    val client by lazy {
        HttpClient(OkHttp) {
            expectSuccess = true
            install(HttpTimeout) {
                connectTimeoutMillis = 4_000
                requestTimeoutMillis = 8_000
                socketTimeoutMillis = 8_000
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
    }
}

@Serializable
private data class BiniSearchResponse(
    val results: List<BiniSearchResult> = emptyList(),
)

@Serializable
private data class BiniSearchResult(
    @SerialName("timing_type") val timingType: String? = null,
    @SerialName("lyricsUrl") val lyricsUrl: String? = null,
)

@Serializable
private data class UnisonResponse(
    val success: Boolean = false,
    val data: UnisonLyrics? = null,
)

@Serializable
private data class UnisonLyrics(
    val format: String = "",
    val lyrics: String = "",
)

object BiniLyricsProvider : LyricsProvider {
    override val name = "BiniLyrics"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableBiniLyricsKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = getLyrics(LyricsTrackMetadata(id, title, artist, duration, album))

    override suspend fun getLyrics(track: LyricsTrackMetadata): Result<String> = runCatching {
        val response = CommunityLyricsHttp.client.get(CommunityLyricsHttp.BINI_SEARCH) {
            if (!track.isrc.isNullOrBlank()) {
                parameter("isrc", track.isrc)
            } else {
                parameter("track", track.title)
                parameter("artist", track.artist)
                track.album?.takeIf(String::isNotBlank)?.let { parameter("album", it) }
                track.duration.takeIf { it > 0 }?.let { parameter("duration", it) }
            }
        }.body<BiniSearchResponse>()

        // The API ranks matches. Prefer a word-timed candidate when multiple
        // records are returned, retaining the server's order within each tier.
        val candidate = response.results.firstOrNull { it.timingType.equals("word", true) }
            ?: response.results.firstOrNull()
            ?: error("BiniLyrics returned no matching song")
        val lyricsUrl = candidate.lyricsUrl?.takeIf(::isTrustedBiniLyricsUrl)
            ?: error("BiniLyrics returned an untrusted lyrics URL")
        val lyricsResponse = CommunityLyricsHttp.client.get(lyricsUrl)
        require(isTrustedBiniLyricsUrl(lyricsResponse.call.request.url.toString())) {
            "BiniLyrics redirected to an untrusted host"
        }
        val ttml = lyricsResponse.bodyAsText()
            .takeIf(String::isNotBlank) ?: error("BiniLyrics returned empty TTML")
        val lines = TTMLParser.parseTTML(ttml)
        TTMLParser.toLRC(lines).takeIf(String::isNotBlank)
            ?: error("BiniLyrics TTML did not contain renderable lyrics")
    }

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
        callback: (String) -> Unit,
    ) {
        getLyrics(id, title, artist, duration, album).onSuccess(callback)
    }

    internal fun isTrustedBiniLyricsUrl(rawUrl: String): Boolean = runCatching {
        val parsed = URI(rawUrl)
        parsed.scheme.equals("https", ignoreCase = true) &&
            parsed.host.orEmpty().lowercase() in setOf("lyrics-storage.binimum.org", "lrc.red") &&
            (parsed.port == -1 || parsed.port == 443) &&
            parsed.userInfo == null
    }.getOrDefault(false)
}

object UnisonLyricsProvider : LyricsProvider {
    override val name = "Unison"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableUnisonLyricsKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = getLyrics(LyricsTrackMetadata(id, title, artist, duration, album))

    override suspend fun getLyrics(track: LyricsTrackMetadata): Result<String> = runCatching {
        val response = CommunityLyricsHttp.client.get(CommunityLyricsHttp.UNISON) {
            if (YOUTUBE_VIDEO_ID.matches(track.id)) {
                parameter("v", track.id)
            } else {
                parameter("song", track.title)
                parameter("artist", track.artist)
                track.album?.takeIf(String::isNotBlank)?.let { parameter("album", it) }
                track.duration.takeIf { it > 0 }?.let { parameter("duration", it) }
            }
        }.body<UnisonResponse>()

        val data = response.data?.takeIf { response.success && it.lyrics.isNotBlank() }
            ?: error("Unison did not find lyrics for this track")
        when (data.format.lowercase()) {
            "ttml" -> TTMLParser.toLRC(TTMLParser.parseTTML(data.lyrics))
                .takeIf(String::isNotBlank) ?: error("Unison returned malformed TTML")
            "lrc", "plain" -> data.lyrics
            else -> error("Unison returned unsupported format: ${data.format}")
        }
    }

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
        callback: (String) -> Unit,
    ) {
        getLyrics(id, title, artist, duration, album).onSuccess(callback)
    }

    private val YOUTUBE_VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
}
