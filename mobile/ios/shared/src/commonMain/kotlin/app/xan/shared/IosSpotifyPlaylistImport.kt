package app.xan.shared

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal data class IosSpotifyPlaylistTrack(
    val id: String,
    val title: String,
    val artist: String,
    val durationMillis: Int,
)

internal data class IosSpotifyPlaylistPreview(
    val id: String,
    val name: String,
    val tracks: List<IosSpotifyPlaylistTrack>,
    val mayBeTruncated: Boolean,
)

internal data class IosSpotifyImportReport(
    val playlistName: String,
    val matchedCount: Int,
    val unmatchedTracks: List<IosSpotifyPlaylistTrack>,
    val mayBeTruncated: Boolean,
) {
    fun summary(): String = buildString {
        append("Imported ").append(matchedCount).append(" matched tracks into ").append(playlistName)
        append("; ").append(unmatchedTracks.size).append(" could not be matched.")
        if (mayBeTruncated) append(" Spotify only exposed a public preview of up to 50 tracks.")
    }
}

private val SPOTIFY_PLAYLIST_ID_PATTERN = Regex("^[A-Za-z0-9]{22}$")
private val SPOTIFY_PLAYLIST_URL_PATTERN = Regex(
    "^https://(?:(?:open|www)\\.)?spotify\\.com/(?:intl-[a-z]{2}/)?playlist/([A-Za-z0-9]{22})(?:[/?#].*)?$",
    RegexOption.IGNORE_CASE,
)
private val SPOTIFY_NEXT_DATA_SCRIPT_PATTERN = Regex(
    "<script\\b(?=[^>]*\\bid\\s*=\\s*[\"']__NEXT_DATA__[\"'])[^>]*>",
    RegexOption.IGNORE_CASE,
)

/** Imports Spotify's unauthenticated public playlist preview and matches it to YouTube Music. */
internal object IosSpotifyPlaylistImporter {
    private const val PREVIEW_LIMIT = 50
    private const val MAX_HTML_BYTES = 4 * 1024 * 1024
    private const val MAX_HTML_CHARS = 4 * 1024 * 1024
    private const val SEARCH_TIMEOUT_MILLIS = 15_000L
    private const val MATCH_THRESHOLD = 0.67
    private val json = Json { ignoreUnknownKeys = true }
    private val client = newRoomHttpClient()

    suspend fun import(link: String): IosSpotifyImportReport {
        val playlistId = parseIosSpotifyPlaylistId(link)
            ?: throw IllegalArgumentException("Enter a valid Spotify playlist URL or playlist ID.")
        val response = client.get("https://open.spotify.com/embed/playlist/$playlistId") {
            header("Accept", "text/html")
            header(
                "User-Agent",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_2 like Mac OS X) AppleWebKit/605.1.15 Version/18.2 Mobile/15E148 Safari/604.1",
            )
        }
        require(response.status.value in 200..299) {
            "Spotify could not open this public playlist (${response.status.value})."
        }
        val contentLength = response.headers["Content-Length"]?.toLongOrNull()
        require(contentLength == null || contentLength in 1..MAX_HTML_BYTES.toLong()) {
            "Spotify returned an oversized playlist page."
        }
        val html = response.bodyAsText()
        require(html.encodeToByteArray().size <= MAX_HTML_BYTES && html.length <= MAX_HTML_CHARS) {
            "Spotify returned an oversized playlist page."
        }
        val preview = parseIosSpotifyPlaylistHtml(playlistId, html)
            ?: throw IllegalStateException("Spotify did not provide a public playlist preview. Check that the playlist is public.")
        require(preview.tracks.isNotEmpty()) { "This Spotify playlist has no tracks in its public preview." }

        val semaphore = Semaphore(4)
        val outcomes = coroutineScope {
            preview.tracks.map { source ->
                async {
                    semaphore.withPermit {
                        try {
                            val query = listOf(source.artist, source.title).filter(String::isNotBlank).joinToString(" ")
                            val candidates = withTimeoutOrNull(SEARCH_TIMEOUT_MILLIS) { IosMusicSearch.search(query) }
                                ?: return@withPermit MatchOutcome(error = "YouTube Music search timed out.")
                            val best = candidates.mapNotNull { candidate ->
                                val score = scoreIosSpotifyMatch(source, candidate)
                                if (score < MATCH_THRESHOLD) null else ScoredMatch(score, candidate)
                            }.maxByOrNull { it.score }
                            MatchOutcome(track = best?.track)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            MatchOutcome(error = failure.message ?: "YouTube Music search failed.")
                        }
                    }
                }
            }.awaitAll()
        }
        val matchedTracks = outcomes.mapNotNull { it.track }.distinctBy(IosMusicSearchTrack::videoId)
        val unmatched = preview.tracks.filterIndexed { index, _ -> outcomes[index].track == null }
        val failureCount = outcomes.count { it.error != null }
        if (matchedTracks.isEmpty() && failureCount == outcomes.size) {
            throw IllegalStateException(outcomes.firstNotNullOfOrNull { it.error } ?: "YouTube Music could not search this playlist.")
        }

        val savedPlaylist = IosMusicLibrary.importSpotifyPlaylist(
            spotifyId = preview.id,
            name = preview.name,
            tracks = matchedTracks.map(IosMusicSearchTrack::toLibraryTrack),
        )
        return IosSpotifyImportReport(savedPlaylist.name, matchedTracks.size, unmatched, preview.mayBeTruncated)
    }

    private data class MatchOutcome(val track: IosMusicSearchTrack? = null, val error: String? = null)
    private data class ScoredMatch(val score: Double, val track: IosMusicSearchTrack)
}

internal fun parseIosSpotifyPlaylistId(raw: String): String? {
    val value = raw.trim()
    if (value.isEmpty() || value.length > 2_048) return null
    if (SPOTIFY_PLAYLIST_ID_PATTERN.matches(value)) return value
    if (value.startsWith("spotify:", ignoreCase = true)) {
        val parts = value.split(':')
        return parts.takeIf { it.size == 3 && it[1].equals("playlist", ignoreCase = true) }
            ?.get(2)?.takeIf(SPOTIFY_PLAYLIST_ID_PATTERN::matches)
    }
    // Keep the accepted URL form narrow: only Spotify HTTPS playlist pages are fetched.
    return SPOTIFY_PLAYLIST_URL_PATTERN.matchEntire(value)?.groupValues?.getOrNull(1)
}

internal fun parseIosSpotifyPlaylistHtml(expectedId: String, html: String): IosSpotifyPlaylistPreview? {
    if (!SPOTIFY_PLAYLIST_ID_PATTERN.matches(expectedId) || html.isEmpty() || html.length > 4 * 1024 * 1024) return null
    val script = SPOTIFY_NEXT_DATA_SCRIPT_PATTERN.find(html) ?: return null
    val start = script.range.last + 1
    val end = html.indexOf("</script>", start, ignoreCase = true)
    if (end <= start) return null
    val root = runCatching { Json.parseToJsonElement(html.substring(start, end)) as? JsonObject }.getOrNull() ?: return null
    val entity = (((root["props"] as? JsonObject)?.get("pageProps") as? JsonObject)
        ?.get("state") as? JsonObject)?.get("data").asJsonObject()?.get("entity").asJsonObject() ?: return null
    if (entity.string("type") != "playlist" || entity.string("id") != expectedId) return null
    val name = entity.string("name")?.trim()?.takeIf(String::isNotEmpty)?.take(60) ?: return null
    val trackList = entity["trackList"] as? JsonArray ?: return null
    val tracks = trackList.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        if (item.string("entityType") != "track") return@mapNotNull null
        val uri = item.string("uri") ?: return@mapNotNull null
        val id = uri.removePrefix("spotify:track:").takeIf(SPOTIFY_PLAYLIST_ID_PATTERN::matches)
            ?.takeIf { uri == "spotify:track:$it" } ?: return@mapNotNull null
        val title = item.string("title")?.trim()?.takeIf(String::isNotEmpty)?.take(300) ?: return@mapNotNull null
        val artist = item.string("subtitle")?.trim()?.take(300).orEmpty()
        val duration = (item["duration"] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(0) ?: 0
        IosSpotifyPlaylistTrack(id, title, artist, duration)
    }.distinctBy(IosSpotifyPlaylistTrack::id)
    return IosSpotifyPlaylistPreview(expectedId, name, tracks, trackList.size >= 50)
}

internal fun scoreIosSpotifyMatch(source: IosSpotifyPlaylistTrack, candidate: IosMusicSearchTrack): Double {
    val titleScore = tokenDiceSimilarity(normalizeSpotifyMatchText(source.title), normalizeSpotifyMatchText(candidate.title))
    val artistScore = tokenDiceSimilarity(normalizeSpotifyMatchText(source.artist), normalizeSpotifyMatchText(candidate.artist))
    if (titleScore < 0.5 || artistScore < 0.3) return 0.0
    val durationScore = if (source.durationMillis <= 0 || candidate.durationSeconds == null) 0.5 else {
        val difference = kotlin.math.abs(source.durationMillis.toLong() - candidate.durationSeconds.toLong() * 1_000L)
        when {
            difference <= 2_000L -> 1.0
            difference <= 5_000L -> 0.8
            difference <= 10_000L -> 0.5
            difference <= 30_000L -> 0.2
            else -> 0.0
        }
    }
    return titleScore * 0.45 + artistScore * 0.35 + durationScore * 0.20
}

private fun normalizeSpotifyMatchText(raw: String): String = raw.lowercase()
    .replace(Regex("\\s*\\((?:feat|ft|remaster|remix|live|version)[^)]*\\)", RegexOption.IGNORE_CASE), " ")
    .filter(Char::isLetterOrDigit)

private fun tokenDiceSimilarity(left: String, right: String): Double {
    if (left.isEmpty() || right.isEmpty()) return 0.0
    if (left == right) return 1.0
    val leftPairs = if (left.length == 1) setOf(left) else left.windowed(2).toSet()
    val rightPairs = if (right.length == 1) setOf(right) else right.windowed(2).toSet()
    val overlap = leftPairs.count { it in rightPairs }
    return (2.0 * overlap) / (leftPairs.size + rightPairs.size)
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun kotlinx.serialization.json.JsonElement?.asJsonObject(): JsonObject? = this as? JsonObject
