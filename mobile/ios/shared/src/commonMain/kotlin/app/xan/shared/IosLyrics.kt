package app.xan.shared

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal data class IosLyricsWord(val startMillis: Long, val endMillis: Long?, val text: String)

internal data class IosLyricsLine(
    val startMillis: Long?,
    val text: String,
    val words: List<IosLyricsWord> = emptyList(),
)

internal data class IosLyricsDocument(
    val lines: List<IosLyricsLine>,
    val isSynced: Boolean,
    val provider: String = "LRCLIB",
)

private const val MAX_IOS_LYRICS_LINES = 5_000
private const val MAX_IOS_LYRICS_TEXT_CHARS = 192 * 1024
private const val MAX_IOS_PROVIDER_RESPONSE_BYTES = 512 * 1024
internal val DEFAULT_IOS_LYRICS_PROVIDER_ORDER = listOf(
    "BetterLyrics", "BiniLyrics", "Unison", "Musixmatch", "YouLyPlus", "Paxsenix",
    "LrcLib", "Kugou", "SimpMusic", "YouTubeSubtitle", "YouTubeMusic",
)

internal fun normalizeIosLyricsProviderOrder(raw: String?): List<String> {
    val saved = raw.orEmpty().split(',').map(String::trim)
        .filter { it in DEFAULT_IOS_LYRICS_PROVIDER_ORDER }.distinct()
    return saved + DEFAULT_IOS_LYRICS_PROVIDER_ORDER.filterNot { it in saved }
}

internal fun moveIosLyricsProvider(order: List<String>, provider: String, direction: Int): List<String> {
    val normalized = normalizeIosLyricsProviderOrder(order.joinToString(","))
    val index = normalized.indexOf(provider)
    val destination = (index + direction.coerceIn(-1, 1)).coerceIn(0, normalized.lastIndex)
    if (index < 0 || destination == index) return normalized
    return normalized.toMutableList().also { values ->
        val moved = values.removeAt(index)
        values.add(destination, moved)
    }
}

internal data class IosLyricsState(
    val trackId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val isLoading: Boolean = false,
    val document: IosLyricsDocument? = null,
    val error: String? = null,
)

private data class IosLyricsProviderAttempt(
    val priority: Int,
    val document: IosLyricsDocument? = null,
    val errorMessage: String? = null,
)

/** Resolves enabled lyric providers concurrently, preferring richer timing and saved priority. */
internal object IosLyrics {
    private const val endpoint = "https://lrclib.net/api/search"
    private const val betterLyricsEndpoint = "https://api.betterlyrics.org/getLyrics"
    private const val biniLyricsEndpoint = "https://lyrics-api.binimum.org/"
    private const val unisonLyricsEndpoint = "https://unison.boidu.dev/lyrics"
    private const val paxsenixBaseUrl = "https://lyrics.paxsenix.org"
    private const val appleMusicBaseUrl = "https://amp-api.music.apple.com/v1/catalog/us"
    private const val kuGouSongSearchEndpoint = "https://mobileservice.kugou.com/api/v3/search/song"
    private const val kuGouLyricsEndpoint = "https://lyrics.kugou.com/search"
    private const val kuGouLyricsDownloadEndpoint = "https://lyrics.kugou.com/download"
    private const val musixmatchBaseUrl = "https://apic.musixmatch.com/ws/1.1"
    private const val musixmatchAppId = "web-desktop-app-v1.0"
    private const val musixmatchSigningSecret = "RJDefUswhwjkZDeM"
    private val youLyPlusServers = listOf(
        "https://lyricsplus.prjktla.my.id",
        "https://lyricsplus.atomix.one",
        "https://lyricsplus.binimum.org",
        "https://lyricsplus.prjktla.workers.dev",
        "https://lyricsplus-seven.vercel.app",
        "https://lyrics-plus-backend.vercel.app",
    )
    private val simpMusicEndpoints = listOf(
        "https://api-lyrics.simpmusic.org/v1",
        "https://xan-yt-music-server.onrender.com/v1",
    )
    private const val transcriptEndpoint = "$IOS_YOUTUBE_MUSIC_ORIGIN/youtubei/v1/get_transcript"
    private const val transcriptApiKey = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX3"
    private const val transcriptClientVersion = "2.20260213.00.00"
    private const val MAX_RESPONSE_BYTES = 512 * 1024
    private const val MAX_TRANSCRIPT_RESPONSE_BYTES = 2 * 1024 * 1024
    private const val MAX_LYRICS_CHARS = 192 * 1024
    private val json = Json { ignoreUnknownKeys = true }
    private val client = newRoomHttpClient()
    private val cache = linkedMapOf<String, IosLyricsDocument>()
    private var lastWorkingYouLyPlusServer: String? = null
    private var cachedAppleMusicDeveloperToken: String? = null
    private var nativeCrypto: IosNativeCrypto? = null
    private var cachedMusixmatchToken: String? = null
    private val musixmatchTokenMutex = Mutex()
    private val mutableState = MutableStateFlow(IosLyricsState())
    private var rateLimitMark: TimeMark? = null
    private var rateLimitDelayMillis = 0L
    val state = mutableState.asStateFlow()

    fun providerEnabled(provider: String): Boolean = readIosPreference(providerPreferenceKey(provider))?.toBooleanStrictOrNull() ?: true

    fun installNativeCrypto(crypto: IosNativeCrypto?) {
        nativeCrypto = crypto
    }

    fun providerOrder(): List<String> = normalizeIosLyricsProviderOrder(readIosPreference("lyrics.providerOrder"))

    fun setProviderOrder(order: List<String>) {
        writeIosPreference("lyrics.providerOrder", normalizeIosLyricsProviderOrder(order.joinToString(",")).joinToString(","))
    }

    fun moveProvider(provider: String, direction: Int): List<String> = providerOrder().let { current ->
        moveIosLyricsProvider(current, provider, direction).also(::setProviderOrder)
    }

    fun setProviderEnabled(provider: String, enabled: Boolean) = writeIosPreference(providerPreferenceKey(provider), enabled.toString())

    suspend fun load(trackId: String, title: String, artist: String, durationSeconds: Int? = null, album: String? = null) {
        require(trackId.matches(Regex("[A-Za-z0-9_-]{6,100}"))) { "This track has an invalid ID." }
        val normalizedTitle = title.trim().take(200)
        val normalizedArtist = artist.trim().take(200)
        val normalizedAlbum = album?.trim()?.take(200)?.takeIf(String::isNotEmpty)
        require(normalizedTitle.isNotEmpty() && normalizedArtist.isNotEmpty()) { "Track title and artist are required to find lyrics." }

        val elapsedSinceRateLimit = rateLimitMark?.elapsedNow()?.inWholeMilliseconds ?: 0L
        val cooldownRemaining = (rateLimitDelayMillis - elapsedSinceRateLimit).coerceAtLeast(0L)
        if (cooldownRemaining > 0L) {
            mutableState.value = IosLyricsState(
                trackId = trackId,
                title = normalizedTitle,
                artist = normalizedArtist,
                error = "LRCLIB asked XAN to wait ${((cooldownRemaining + 999) / 1_000).coerceAtLeast(1)} seconds before another request.",
            )
            return
        }

        cache[trackId]?.let { cached ->
            mutableState.value = IosLyricsState(trackId, normalizedTitle, normalizedArtist, document = cached)
            return
        }
        mutableState.value = IosLyricsState(trackId, normalizedTitle, normalizedArtist, isLoading = true)
        val queryVariants = buildList {
            add(Query(trackName = cleanLyricsTitle(normalizedTitle), artistName = primaryLyricsArtist(normalizedArtist)))
            add(Query(trackName = cleanLyricsTitle(normalizedTitle)))
            add(Query(query = "${primaryLyricsArtist(normalizedArtist)} ${cleanLyricsTitle(normalizedTitle)}"))
            if (cleanLyricsTitle(normalizedTitle) != normalizedTitle) add(Query(trackName = normalizedTitle, artistName = normalizedArtist))
        }.distinct()

        try {
            suspend fun lookupLrcLib(): IosLyricsDocument? {
                for ((index, query) in queryVariants.withIndex()) {
                    val candidates = try {
                        withTimeoutOrNull(8_000L) { fetch(query) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (rateLimited: IosLyricsRateLimitedException) {
                        throw rateLimited
                    } catch (_: Throwable) {
                        null
                    }
                    if (candidates != null) {
                        chooseLyricsCandidate(candidates, normalizedTitle, normalizedArtist, durationSeconds)?.let { return it }
                    }
                    if (index != queryVariants.lastIndex) delay(250L)
                }
                return null
            }

            val videoId = trackId.takeIf { it.matches(YOUTUBE_VIDEO_ID) }
            val providerFetchers: Map<String, suspend () -> IosLyricsDocument?> = linkedMapOf(
                "BetterLyrics" to { fetchBetterLyrics(normalizedTitle, normalizedArtist, durationSeconds, normalizedAlbum) },
                "BiniLyrics" to { fetchBiniLyrics(normalizedTitle, normalizedArtist, durationSeconds, normalizedAlbum) },
                "Unison" to { fetchUnisonLyrics(trackId, normalizedTitle, normalizedArtist, durationSeconds, normalizedAlbum) },
                "Musixmatch" to { fetchMusixmatch(normalizedTitle, normalizedArtist, durationSeconds, normalizedAlbum) },
                "YouLyPlus" to { fetchYouLyPlus(trackId, normalizedTitle, normalizedArtist, durationSeconds, normalizedAlbum) },
                "Paxsenix" to { fetchPaxsenix(normalizedTitle, normalizedArtist, durationSeconds, normalizedAlbum) },
                "LrcLib" to ::lookupLrcLib,
                "Kugou" to { fetchKuGou(normalizedTitle, normalizedArtist, durationSeconds, normalizedAlbum) },
                "SimpMusic" to {
                    videoId?.let { fetchSimpMusicLyrics(it, durationSeconds) }
                },
                "YouTubeSubtitle" to {
                    videoId?.let { fetchYouTubeTranscript(it) }
                },
                "YouTubeMusic" to {
                    videoId?.let { IosMusicCatalog.lyrics(it) }
                        ?.let { parseIosLyrics(it, synced = false)?.copy(provider = "YouTube Music") }
                },
            )
            val attempts = coroutineScope {
                providerOrder().mapIndexed { priority, provider ->
                    async {
                        val fetchProvider = providerFetchers[provider]
                        if (!providerEnabled(provider) || fetchProvider == null) {
                            return@async IosLyricsProviderAttempt(priority)
                        }
                        val providerTimeoutMillis = when (provider) {
                            "Paxsenix" -> 20_000L
                            "LrcLib" -> 35_000L
                            "Kugou" -> 10_000L
                            "SimpMusic" -> 12_000L
                            "YouTubeSubtitle" -> 8_000L
                            "YouTubeMusic" -> 15_000L
                            else -> 7_000L
                        }
                        try {
                            IosLyricsProviderAttempt(
                                priority = priority,
                                document = withTimeoutOrNull(providerTimeoutMillis) { fetchProvider() },
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            IosLyricsProviderAttempt(priority = priority, errorMessage = failure.message)
                        }
                    }
                }.awaitAll()
            }
            val winner = rankIosLyricsDocuments(attempts.mapNotNull { attempt ->
                attempt.document?.let { attempt.priority to it }
            })
            if (winner != null) {
                cache[trackId] = winner
                while (cache.size > 100) cache.remove(cache.keys.first())
                mutableState.value = IosLyricsState(trackId, normalizedTitle, normalizedArtist, document = winner)
                return
            }
            mutableState.value = IosLyricsState(
                trackId = trackId,
                title = normalizedTitle,
                artist = normalizedArtist,
                error = attempts.firstNotNullOfOrNull(IosLyricsProviderAttempt::errorMessage)?.take(240)
                    ?: "Lyrics are unavailable for this track.",
            )
        } catch (cancelled: CancellationException) {
            if (mutableState.value.trackId == trackId) mutableState.value = IosLyricsState()
            throw cancelled
        } catch (failure: Throwable) {
            mutableState.value = IosLyricsState(
                trackId = trackId,
                title = normalizedTitle,
                artist = normalizedArtist,
                error = failure.message?.take(240) ?: "Could not load lyrics.",
            )
        }
    }

    fun clear() {
        mutableState.value = IosLyricsState()
    }

    private suspend fun fetch(query: Query): List<Candidate> {
        val response = client.get(endpoint) {
            header("User-Agent", "XAN/$XAN_IOS_VERSION (https://github.com/bxanedot/xan)")
            query.trackName?.let { parameter("track_name", it) }
            query.artistName?.let { parameter("artist_name", it) }
            query.query?.let { parameter("q", it) }
        }
        if (response.status.value == 429) {
            val retryAfterSeconds = response.headers["Retry-After"]?.toLongOrNull()?.coerceIn(1L, 3_600L) ?: 60L
            rateLimitMark = TimeSource.Monotonic.markNow()
            rateLimitDelayMillis = retryAfterSeconds * 1_000L
            throw IosLyricsRateLimitedException(retryAfterSeconds)
        }
        require(response.status.value in 200..299) { "LRCLIB returned HTTP ${response.status.value}." }
        val body = response.bodyAsText()
        require(body.encodeToByteArray().size <= MAX_RESPONSE_BYTES) { "LRCLIB returned an oversized response." }
        val rows = json.parseToJsonElement(body) as? JsonArray ?: error("LRCLIB returned an invalid response.")
        return rows.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val lyrics = listOfNotNull(row.string("syncedLyrics"), row.string("plainLyrics"))
                .firstOrNull { it.isNotBlank() && it.length <= MAX_LYRICS_CHARS } ?: return@mapNotNull null
            Candidate(
                title = row.string("trackName").orEmpty(),
                artist = row.string("artistName").orEmpty(),
                durationSeconds = row.string("duration")?.toDoubleOrNull(),
                synced = row.string("syncedLyrics")?.takeIf { it.isNotBlank() && it.length <= MAX_LYRICS_CHARS },
                plain = row.string("plainLyrics")?.takeIf { it.isNotBlank() && it.length <= MAX_LYRICS_CHARS },
            )
        }
    }

    private suspend fun fetchYouTubeTranscript(videoId: String): IosLyricsDocument? {
        val request = buildJsonObject {
            put("context", iosYouTubeContext(clientName = "WEB", clientVersion = transcriptClientVersion, dataSyncId = null))
            put("params", encodeYouTubeTranscriptParams(videoId))
        }
        val response = client.post(transcriptEndpoint) {
            parameter("key", transcriptApiKey)
            parameter("prettyPrint", false)
            contentType(ContentType.Application.Json)
            headers {
                append("X-Goog-Api-Format-Version", "1")
                append("X-YouTube-Client-Name", "1")
                append("X-YouTube-Client-Version", transcriptClientVersion)
                append("X-Origin", IOS_YOUTUBE_MUSIC_ORIGIN)
                append("Referer", "$IOS_YOUTUBE_MUSIC_ORIGIN/")
                append("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")
            }
            setBody(request.toString())
        }
        require(response.status.value in 200..299) { "YouTube captions returned HTTP ${response.status.value}." }
        val payload = response.bodyAsText()
        require(payload.encodeToByteArray().size <= MAX_TRANSCRIPT_RESPONSE_BYTES) { "YouTube returned an oversized captions response." }
        return parseIosYouTubeTranscript(payload)
    }

    private suspend fun fetchSimpMusicLyrics(videoId: String, durationSeconds: Int?): IosLyricsDocument? {
        for (base in simpMusicEndpoints) {
            try {
                val response = client.get("$base/$videoId") {
                    header("User-Agent", "XAN/$XAN_IOS_VERSION (https://github.com/bxanedot/xan)")
                }
                if (response.status.value !in 200..299) continue
                val body = response.bodyAsText()
                require(body.encodeToByteArray().size <= MAX_RESPONSE_BYTES) { "SimpMusic returned an oversized lyrics response." }
                parseIosSimpMusicLyrics(body, durationSeconds)?.let { return it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Try the XAN configured fallback after the upstream provider.
            }
        }
        return null
    }

    private suspend fun fetchBetterLyrics(title: String, artist: String, durationSeconds: Int?, album: String?): IosLyricsDocument? {
        val response = client.get(betterLyricsEndpoint) {
            header("User-Agent", "XAN/$XAN_IOS_VERSION (https://github.com/bxanedot/xan)")
            parameter("s", title)
            parameter("a", artist)
            parameter("d", durationSeconds?.takeIf { it > 0 } ?: -1)
            parameter("al", album.orEmpty())
        }
        if (response.status.value !in 200..299) return null
        val body = response.bodyAsText()
        require(body.encodeToByteArray().size <= MAX_RESPONSE_BYTES) { "BetterLyrics returned an oversized response." }
        val ttml = runCatching { (json.parseToJsonElement(body) as? JsonObject)?.string("ttml") }.getOrNull() ?: return null
        return parseIosTtmlLyrics(ttml, "BetterLyrics")
    }

    private suspend fun fetchBiniLyrics(title: String, artist: String, durationSeconds: Int?, album: String?): IosLyricsDocument? {
        val search = client.get(biniLyricsEndpoint) {
            header("User-Agent", "XAN/$XAN_IOS_VERSION (https://github.com/bxanedot/xan)")
            parameter("track", title)
            parameter("artist", artist)
            album?.takeIf(String::isNotBlank)?.let { parameter("album", it) }
            durationSeconds?.takeIf { it > 0 }?.let { parameter("duration", it) }
        }
        if (search.status.value !in 200..299) return null
        val body = search.bodyAsText()
        require(body.encodeToByteArray().size <= MAX_RESPONSE_BYTES) { "BiniLyrics returned an oversized search response." }
        val lyricsUrl = parseIosBiniLyricsUrl(body) ?: return null
        val lyrics = client.get(lyricsUrl) {
            header("User-Agent", "XAN/$XAN_IOS_VERSION (https://github.com/bxanedot/xan)")
        }
        require(isTrustedIosBiniLyricsUrl(lyrics.call.request.url.toString())) {
            "BiniLyrics redirected to an untrusted host."
        }
        require(lyrics.status.value in 200..299) { "BiniLyrics returned HTTP ${lyrics.status.value}." }
        val ttml = lyrics.bodyAsText()
        require(ttml.encodeToByteArray().size <= MAX_RESPONSE_BYTES) { "BiniLyrics returned oversized lyrics." }
        return parseIosTtmlLyrics(ttml, "BiniLyrics")
    }

    private suspend fun fetchUnisonLyrics(trackId: String, title: String, artist: String, durationSeconds: Int?, album: String?): IosLyricsDocument? {
        val response = client.get(unisonLyricsEndpoint) {
            header("User-Agent", "XAN/$XAN_IOS_VERSION (https://github.com/bxanedot/xan)")
            if (trackId.matches(YOUTUBE_VIDEO_ID)) {
                parameter("v", trackId)
            } else {
                parameter("song", title)
                parameter("artist", artist)
                album?.takeIf(String::isNotBlank)?.let { parameter("album", it) }
                durationSeconds?.takeIf { it > 0 }?.let { parameter("duration", it) }
            }
        }
        if (response.status.value !in 200..299) return null
        val body = response.bodyAsText()
        require(body.encodeToByteArray().size <= MAX_RESPONSE_BYTES) { "Unison returned an oversized response." }
        return parseIosUnisonLyrics(body)
    }

    internal suspend fun fetchMusixmatch(title: String, artist: String, durationSeconds: Int?, album: String?): IosLyricsDocument? {
        val duration = durationSeconds?.takeIf { it > 0 } ?: 0
        val searchBody = signedMusixmatchRequest(
            path = "/track.search",
            parameters = listOf(
                "q_track" to title,
                "q_artist" to artist,
                "f_has_lyrics" to "1",
                "s_track_rating" to "desc",
                "quorum_factor" to "1",
                "page_size" to "10",
                "page" to "1",
            ),
        ) ?: return null
        val tracks = parseIosMusixmatchTracks(searchBody)
        val bestTrack = tracks.maxByOrNull { track ->
            val titleKey = title.trim().lowercase()
            val trackTitle = track.title.trim().lowercase()
            var score = when {
                trackTitle == titleKey -> 80.0
                trackTitle.contains(titleKey) || titleKey.contains(trackTitle) -> 40.0
                else -> 0.0
            }
            if (track.artist.trim().lowercase().contains(artist.trim().lowercase())) score += 40.0
            track.durationSeconds?.let { trackDuration ->
                val difference = kotlin.math.abs(trackDuration - duration)
                score += when {
                    difference <= 2 -> 30.0
                    difference <= 5 -> 15.0
                    difference <= 10 -> 5.0
                    else -> -20.0
                }
            }
            score
        } ?: return null

        if (bestTrack.hasSubtitles) {
            val subtitleBody = signedMusixmatchRequest(
                path = "/track.subtitle.get",
                parameters = listOf("track_id" to bestTrack.id.toString(), "subtitle_format" to "mxm"),
            )
            if (subtitleBody != null) parseIosMusixmatchSubtitle(subtitleBody)?.let { return it }
        }
        val plainBody = signedMusixmatchRequest(
            path = "/track.lyrics.get",
            parameters = listOf("track_id" to bestTrack.id.toString()),
        ) ?: return null
        return parseIosMusixmatchPlainLyrics(plainBody)
    }

    private suspend fun signedMusixmatchRequest(
        path: String,
        parameters: List<Pair<String, String>>,
        includeToken: Boolean = true,
        retryUnauthorized: Boolean = true,
    ): String? {
        val crypto = nativeCrypto ?: return null
        val token = if (includeToken) getMusixmatchToken() ?: return null else null
        val queryParameters = buildList {
            add("app_id" to musixmatchAppId)
            addAll(parameters)
            token?.let { add("usertoken" to it) }
        }
        val date = formatIosMusixmatchDate(crypto.currentUnixTimeSeconds())
        val url = buildIosMusixmatchSignedUrl(musixmatchBaseUrl, path, queryParameters, date) { message ->
            crypto.hmacSha256Base64(message, musixmatchSigningSecret)
        }
        val response = client.get(url) {
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        }
        if (includeToken && retryUnauthorized && response.status.value in setOf(401, 402)) {
            cachedMusixmatchToken = null
            val refreshed = getMusixmatchToken() ?: return null
            val refreshedParameters = queryParameters.filterNot { it.first == "usertoken" } + ("usertoken" to refreshed)
            val refreshedUrl = buildIosMusixmatchSignedUrl(musixmatchBaseUrl, path, refreshedParameters, date) { message ->
                crypto.hmacSha256Base64(message, musixmatchSigningSecret)
            }
            val retry = client.get(refreshedUrl) {
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }
            if (retry.status.value !in 200..299) return null
            return retry.bodyAsText().also { require(it.encodeToByteArray().size <= MAX_RESPONSE_BYTES) { "Musixmatch returned an oversized response." } }
        }
        if (response.status.value !in 200..299) return null
        return response.bodyAsText().also { require(it.encodeToByteArray().size <= MAX_RESPONSE_BYTES) { "Musixmatch returned an oversized response." } }
    }

    private suspend fun getMusixmatchToken(): String? = musixmatchTokenMutex.withLock {
        cachedMusixmatchToken?.let { return@withLock it }
        val payload = signedMusixmatchRequest(
            path = "/token.get",
            parameters = emptyList(),
            includeToken = false,
            retryUnauthorized = false,
        ) ?: return@withLock null
        parseIosMusixmatchToken(payload)?.also { cachedMusixmatchToken = it }
    }

    internal suspend fun fetchPaxsenix(title: String, artist: String, durationSeconds: Int?, album: String?): IosLyricsDocument? {
        val cleanedTitle = cleanLyricsTitle(title)
        val cleanedArtist = primaryLyricsArtist(artist)
        val queries = buildList {
            add("$cleanedTitle $cleanedArtist")
            add(cleanedTitle)
            album?.takeIf(String::isNotBlank)?.let { add("$cleanedTitle $cleanedArtist $it") }
        }.distinct()
        for (query in queries) {
            val songs = fetchAppleMusicSongs(query)
            val expectedTitle = normalizeLyricsMatch(cleanedTitle)
            val expectedArtist = normalizeLyricsMatch(cleanedArtist)
            val ranked = songs.mapNotNull { song ->
                val titleScore = lyricsSimilarity(expectedTitle, normalizeLyricsMatch(song.title))
                val artistScore = lyricsSimilarity(expectedArtist, normalizeLyricsMatch(song.artist))
                if (titleScore < 0.72 || artistScore < 0.45) return@mapNotNull null
                val durationDifference = if (durationSeconds != null && durationSeconds > 0 && song.durationSeconds != null) {
                    kotlin.math.abs(durationSeconds - song.durationSeconds)
                } else null
                if (durationDifference != null && durationDifference > 12.0) return@mapNotNull null
                song to (titleScore * 4.0 + artistScore * 3.0 - (durationDifference ?: 0.0) * 0.08)
            }.sortedByDescending { it.second }.take(10)
            if (ranked.isEmpty()) continue

            var best: IosLyricsDocument? = null
            var bestQuality = 0
            for ((song, _) in ranked) {
                val document = fetchPaxsenixTrackLyrics(song.id) ?: continue
                val quality = iosLyricsSyncQuality(document)
                if (quality > bestQuality) {
                    best = document
                    bestQuality = quality
                }
                if (quality >= 3) break
            }
            if (best != null) return best
        }
        return null
    }

    private suspend fun fetchAppleMusicSongs(query: String): List<IosAppleMusicSong> {
        val token = cachedAppleMusicDeveloperToken ?: fetchAppleMusicDeveloperToken().also { cachedAppleMusicDeveloperToken = it }
        val response = client.get("$appleMusicBaseUrl/search") {
            header("Authorization", "Bearer $token")
            header("Origin", "https://music.apple.com")
            header("Referer", "https://music.apple.com/")
            header("User-Agent", "XAN/$XAN_IOS_VERSION (https://github.com/bxanedot/xan)")
            header("Accept", "application/json")
            parameter("term", query)
            parameter("types", "songs")
            parameter("limit", 25)
            parameter("l", "en-US")
            parameter("platform", "web")
            parameter("format[resources]", "map")
            parameter("include[songs]", "artists")
            parameter("extend", "artistUrl")
        }
        if (response.status.value !in 200..299) return emptyList()
        val payload = response.bodyAsText()
        require(payload.encodeToByteArray().size <= MAX_RESPONSE_BYTES) { "Paxsenix Apple Music search response was too large." }
        return parseIosAppleMusicSongs(payload)
    }

    private suspend fun fetchAppleMusicDeveloperToken(): String {
        val page = client.get("https://beta.music.apple.com") {
            header("User-Agent", "XAN/$XAN_IOS_VERSION (https://github.com/bxanedot/xan)")
        }
        require(page.status.value in 200..299) { "Apple Music token page returned HTTP ${page.status.value}." }
        val pageBody = page.bodyAsText()
        require(pageBody.encodeToByteArray().size <= 2 * 1024 * 1024) { "Apple Music token page was too large." }
        val scriptPath = Regex("""/assets/index~[^/"']+\.js""").find(pageBody)?.value
            ?: error("Apple Music token script was not found.")
        val script = client.get("https://beta.music.apple.com$scriptPath") {
            header("User-Agent", "XAN/$XAN_IOS_VERSION (https://github.com/bxanedot/xan)")
        }
        require(script.status.value in 200..299) { "Apple Music token script returned HTTP ${script.status.value}." }
        val scriptBody = script.bodyAsText()
        require(scriptBody.encodeToByteArray().size <= 12 * 1024 * 1024) { "Apple Music token script was too large." }
        return Regex("""eyJ[a-zA-Z0-9_-]{20,}\.[a-zA-Z0-9_-]{20,}\.[a-zA-Z0-9_-]+""")
            .find(scriptBody)?.value?.take(8_192)
            ?: error("Apple Music developer token was not found.")
    }

    private suspend fun fetchPaxsenixTrackLyrics(trackId: String): IosLyricsDocument? {
        val response = client.get("$paxsenixBaseUrl/apple-music/lyrics") {
            header("User-Agent", "XAN/$XAN_IOS_VERSION")
            parameter("id", trackId)
        }
        if (response.status.value !in 200..299) return null
        val payload = response.bodyAsText()
        require(payload.encodeToByteArray().size <= MAX_RESPONSE_BYTES) { "Paxsenix returned an oversized lyrics response." }
        return parseIosPaxsenixLyrics(payload)
    }

    internal suspend fun fetchKuGou(title: String, artist: String, durationSeconds: Int?, album: String?): IosLyricsDocument? {
        val normalizedTitle = title.replace(Regex("\\([^)]*\\)|（.*?）|「.*?」|『.*?』|<.*?>|《.*?》|〈.*?〉|＜.*?＞"), "").trim()
        val normalizedArtist = artist.replace(", ", "、").replace(" & ", "、").replace(".", "")
        val keyword = buildString {
            append(normalizedTitle)
            append(" - ")
            append(normalizedArtist)
            album?.takeIf(String::isNotBlank)?.let { append(" ").append(it) }
        }
        val songs = client.get(kuGouSongSearchEndpoint) {
            header("User-Agent", "XAN/$XAN_IOS_VERSION (https://github.com/bxanedot/xan)")
            parameter("version", 9108)
            parameter("plat", 0)
            parameter("pagesize", 8)
            parameter("showtype", 0)
            parameter("keyword", keyword)
        }
        if (songs.status.value !in 200..299) return null
        val songPayload = songs.bodyAsText()
        require(songPayload.encodeToByteArray().size <= MAX_RESPONSE_BYTES) { "KuGou song search response was too large." }
        val expectedDuration = durationSeconds?.takeIf { it > 0 }
        val hashes = parseIosKuGouSongHashes(songPayload)
            .filter { expectedDuration == null || kotlin.math.abs(it.durationSeconds - expectedDuration) <= 8 }
        var candidate: IosKuGouCandidate? = null
        for (song in hashes) {
            candidate = fetchKuGouCandidate(hash = song.hash)
            if (candidate != null) break
        }
        if (candidate == null) candidate = fetchKuGouCandidate(keyword = keyword, durationSeconds = expectedDuration)
        val selected = candidate ?: return null
        val response = client.get(kuGouLyricsDownloadEndpoint) {
            header("User-Agent", "XAN/$XAN_IOS_VERSION (https://github.com/bxanedot/xan)")
            parameter("fmt", "lrc")
            parameter("charset", "utf8")
            parameter("client", "pc")
            parameter("ver", 1)
            parameter("id", selected.id)
            parameter("accesskey", selected.accessKey)
        }
        if (response.status.value !in 200..299) return null
        val payload = response.bodyAsText()
        require(payload.encodeToByteArray().size <= MAX_RESPONSE_BYTES) { "KuGou lyrics response was too large." }
        return parseIosKuGouLyrics(payload)
    }

    private suspend fun fetchKuGouCandidate(hash: String? = null, keyword: String? = null, durationSeconds: Int? = null): IosKuGouCandidate? {
        val response = client.get(kuGouLyricsEndpoint) {
            header("User-Agent", "XAN/$XAN_IOS_VERSION (https://github.com/bxanedot/xan)")
            parameter("ver", 1)
            parameter("man", "yes")
            parameter("client", "pc")
            hash?.let { parameter("hash", it) }
            if (keyword != null) {
                durationSeconds?.times(1_000)?.let { parameter("duration", it) }
                parameter("keyword", keyword)
            }
        }
        if (response.status.value !in 200..299) return null
        val payload = response.bodyAsText()
        require(payload.encodeToByteArray().size <= MAX_RESPONSE_BYTES) { "KuGou lyric search response was too large." }
        return parseIosKuGouCandidates(payload).firstOrNull()
    }

    internal suspend fun fetchYouLyPlus(trackId: String, title: String, artist: String, durationSeconds: Int?, album: String?): IosLyricsDocument? {
        val preferredServer = lastWorkingYouLyPlusServer
        val servers = if (preferredServer == null) youLyPlusServers else listOf(preferredServer) + youLyPlusServers.filterNot { it == preferredServer }
        val results = coroutineScope {
            servers.mapIndexed { index, baseUrl ->
                async {
                    try {
                        val document = withTimeoutOrNull(6_000L) {
                            val response = client.get("$baseUrl/v2/lyrics/get") {
                                header("User-Agent", "XAN/$XAN_IOS_VERSION (https://github.com/bxanedot/xan)")
                                header("X-Client-Package", "XAN <https://github.com/bxanedot/xan>")
                                parameter("title", title)
                                parameter("artist", artist)
                                durationSeconds?.takeIf { it > 0 }?.let { parameter("duration", it) }
                                album?.takeIf(String::isNotBlank)?.let { parameter("album", it) }
                                parameter("id", trackId)
                            }
                            if (response.status.value !in 200..299) return@withTimeoutOrNull null
                            val body = response.bodyAsText()
                            if (body.encodeToByteArray().size > MAX_RESPONSE_BYTES) return@withTimeoutOrNull null
                            parseIosYouLyPlusLyrics(body, title, artist, durationSeconds)
                        }
                        index to (baseUrl to document)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        index to (baseUrl to null)
                    }
                }
            }.awaitAll()
        }
        val winner = results.mapNotNull { (index, result) ->
            val (server, document) = result
            document?.let { Triple(index, server, it) }
        }.maxWithOrNull(
            compareBy<Triple<Int, String, IosLyricsDocument>> { iosLyricsSyncQuality(it.third) }
                .thenBy { -it.first },
        ) ?: return null
        lastWorkingYouLyPlusServer = winner.second
        return winner.third
    }

    private fun chooseLyricsCandidate(
        candidates: List<Candidate>,
        title: String,
        artist: String,
        durationSeconds: Int?,
    ): IosLyricsDocument? {
        val expectedTitle = normalizeLyricsMatch(cleanLyricsTitle(title))
        val expectedArtist = normalizeLyricsMatch(primaryLyricsArtist(artist))
        return candidates.mapNotNull { candidate ->
            val titleSimilarity = lyricsSimilarity(expectedTitle, normalizeLyricsMatch(candidate.title))
            val artistSimilarity = lyricsSimilarity(expectedArtist, normalizeLyricsMatch(candidate.artist))
            if (titleSimilarity < 0.72 || artistSimilarity < 0.45) return@mapNotNull null
            val durationDifference = if (durationSeconds != null && durationSeconds > 0 && candidate.durationSeconds != null) {
                kotlin.math.abs(durationSeconds - candidate.durationSeconds)
            } else null
            if (durationDifference != null && durationDifference > 12.0) return@mapNotNull null
            val text = candidate.synced ?: candidate.plain ?: return@mapNotNull null
            val document = parseIosLyrics(text, candidate.synced != null) ?: return@mapNotNull null
            val score = titleSimilarity * 4.0 + artistSimilarity * 3.0 +
                (if (candidate.synced != null) 1.0 else 0.0) - (durationDifference ?: 0.0) * 0.08
            score to document
        }.maxByOrNull { it.first }?.second
    }

    private data class Query(val trackName: String? = null, val artistName: String? = null, val query: String? = null)
    private data class Candidate(
        val title: String,
        val artist: String,
        val durationSeconds: Double?,
        val synced: String?,
        val plain: String?,
    )

    private val YOUTUBE_VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
}

private fun providerPreferenceKey(provider: String): String = when (provider.lowercase()) {
    "simpmusic" -> "lyrics.simpMusicEnabled"
    "betterlyrics" -> "lyrics.betterLyricsEnabled"
    "binilyrics" -> "lyrics.biniLyricsEnabled"
    "unison" -> "lyrics.unisonEnabled"
    "musixmatch" -> "lyrics.musixmatchEnabled"
    "paxsenix" -> "lyrics.paxsenixEnabled"
    "youlyplus" -> "lyrics.youLyPlusEnabled"
    "kugou" -> "lyrics.kugouEnabled"
    "lrclib" -> "lyrics.lrclibEnabled"
    "youtubesubtitle" -> "lyrics.youtubeSubtitleEnabled"
    "youtubemusic" -> "lyrics.youtubeMusicEnabled"
    else -> error("Unknown iOS lyrics provider.")
}

internal fun parseIosBiniLyricsUrl(payload: String): String? {
    if (payload.isBlank() || payload.length > 128 * 1024) return null
    val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
    val results = root["results"] as? JsonArray ?: return null
    val candidates = results.mapNotNull { row ->
        val item = row as? JsonObject ?: return@mapNotNull null
        val url = item.string("lyricsUrl") ?: return@mapNotNull null
        val timing = item.string("timing_type").orEmpty()
        timing to url
    }
    val ordered = candidates.sortedByDescending { it.first.equals("word", ignoreCase = true) }
    return ordered.firstOrNull { isTrustedIosBiniLyricsUrl(it.second) }?.second
}

internal fun isTrustedIosBiniLyricsUrl(rawUrl: String): Boolean =
    Regex("^https://(?:lyrics-storage\\.binimum\\.org|lrc\\.red)(?::443)?(?:/|\\?|#|$)", RegexOption.IGNORE_CASE)
        .containsMatchIn(rawUrl.trim())

internal fun parseIosUnisonLyrics(payload: String): IosLyricsDocument? {
    if (payload.isBlank() || payload.length > 512 * 1024) return null
    val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
    if (root["success"]?.jsonPrimitive?.contentOrNull != "true") return null
    val data = root["data"] as? JsonObject ?: return null
    val format = data.string("format")?.lowercase() ?: return null
    val lyrics = data.string("lyrics")?.takeIf(String::isNotBlank) ?: return null
    return when (format) {
        "ttml" -> parseIosTtmlLyrics(lyrics, "Unison")
        "lrc", "plain" -> parseIosLyrics(lyrics, synced = format == "lrc")?.copy(provider = "Unison")
        else -> null
    }
}

internal fun parseIosYouLyPlusLyrics(
    payload: String,
    expectedTitle: String,
    expectedArtist: String,
    expectedDurationSeconds: Int?,
): IosLyricsDocument? {
    if (payload.isBlank() || payload.length > MAX_IOS_PROVIDER_RESPONSE_BYTES) return null
    val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
    val resultTitle = root.string("trackName")
    val resultArtist = root.string("artistName")
    val expectedTitleKey = normalizeLyricsMatch(cleanLyricsTitle(expectedTitle))
    val expectedArtistKey = normalizeLyricsMatch(primaryLyricsArtist(expectedArtist))
    if (!resultTitle.isNullOrBlank() && lyricsSimilarity(expectedTitleKey, normalizeLyricsMatch(resultTitle)) < 0.72) return null
    if (!resultArtist.isNullOrBlank() && lyricsSimilarity(expectedArtistKey, normalizeLyricsMatch(resultArtist)) < 0.45) return null
    val metadata = root["metadata"] as? JsonObject
    val resultDuration = root.string("duration")?.toDoubleOrNull()
        ?: metadata?.string("totalDuration")?.let(::parseIosTtmlTime)?.div(1_000.0)
    if (expectedDurationSeconds != null && expectedDurationSeconds > 0 && resultDuration != null &&
        kotlin.math.abs(expectedDurationSeconds - resultDuration) > 12.0
    ) return null

    val choices = mutableListOf<IosLyricsDocument>()
    (root["lyrics"] as? JsonArray)?.let { rows ->
        val lines = rows.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val wordItems = (row["syllabus"] as? JsonArray).orEmpty().mapIndexedNotNull { index, wordElement ->
                val wordRow = wordElement as? JsonObject ?: return@mapIndexedNotNull null
                val wordText = wordRow.string("text")?.takeIf(String::isNotEmpty) ?: return@mapIndexedNotNull null
                val start = wordRow.string("time")?.toLongOrNull() ?: return@mapIndexedNotNull null
                val duration = wordRow.string("duration")?.toLongOrNull()?.takeIf { it >= 0L }
                val formattedText = if (index < ((row["syllabus"] as? JsonArray)?.size ?: 0) - 1 && !wordText.endsWith(" ")) "$wordText " else wordText
                IosLyricsWord(start, duration?.let { start + it }, formattedText)
            }
            val start = row.string("time")?.toLongOrNull() ?: wordItems.firstOrNull()?.startMillis
            val text = row.string("text")?.takeIf(String::isNotBlank) ?: wordItems.joinToString("") { it.text }.trim()
            if (text.isBlank()) return@mapNotNull null
            IosLyricsLine(start, text, wordItems)
        }.take(MAX_IOS_LYRICS_LINES)
        if (lines.isNotEmpty()) choices += IosLyricsDocument(lines, isSynced = lines.any { it.startMillis != null }, provider = "YouLyPlus")
    }
    root.string("syncedLyrics")?.let { parseIosLyrics(it, synced = true)?.copy(provider = "YouLyPlus")?.let(choices::add) }
    root.string("plainLyrics")?.let { parseIosLyrics(it, synced = false)?.copy(provider = "YouLyPlus")?.let(choices::add) }
    return choices.maxByOrNull(::iosLyricsSyncQuality)
}

internal data class IosMusixmatchTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val durationSeconds: Int?,
    val hasSubtitles: Boolean,
)

internal fun parseIosMusixmatchToken(payload: String): String? {
    if (payload.isBlank() || payload.length > MAX_IOS_PROVIDER_RESPONSE_BYTES) return null
    val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
    val message = root["message"] as? JsonObject ?: return null
    val status = (message["header"] as? JsonObject)?.string("status_code")?.toIntOrNull()
    if (status != null && status != 200) return null
    return ((message["body"] as? JsonObject)?.string("user_token"))?.takeIf { it.length in 1..512 }
}

internal fun parseIosMusixmatchTracks(payload: String): List<IosMusixmatchTrack> {
    if (payload.isBlank() || payload.length > MAX_IOS_PROVIDER_RESPONSE_BYTES) return emptyList()
    val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return emptyList()
    val message = root["message"] as? JsonObject ?: return emptyList()
    val rows = (((message["body"] as? JsonObject)?.get("track_list")) as? JsonArray).orEmpty()
    return rows.take(25).mapNotNull { element ->
        val track = ((element as? JsonObject)?.get("track")) as? JsonObject ?: return@mapNotNull null
        val id = track.string("track_id")?.toLongOrNull()?.takeIf { it > 0L } ?: return@mapNotNull null
        val title = track.string("track_name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val artist = track.string("artist_name").orEmpty()
        IosMusixmatchTrack(
            id,
            title.take(200),
            artist.take(200),
            track.string("track_length")?.toIntOrNull()?.takeIf { it > 0 },
            track.string("has_subtitles") == "1",
        )
    }
}

internal fun parseIosMusixmatchSubtitle(payload: String): IosLyricsDocument? {
    if (payload.isBlank() || payload.length > MAX_IOS_PROVIDER_RESPONSE_BYTES) return null
    val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
    val message = root["message"] as? JsonObject ?: return null
    val subtitleBody = (((message["body"] as? JsonObject)?.get("subtitle")) as? JsonObject)
        ?.string("subtitle_body")?.takeIf(String::isNotBlank) ?: return null
    if (subtitleBody.length > MAX_IOS_PROVIDER_RESPONSE_BYTES) return null
    val rows = runCatching { Json.parseToJsonElement(subtitleBody) as? JsonArray }.getOrNull() ?: return null
    val lines = rows.take(MAX_IOS_LYRICS_LINES).mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val text = row.string("text")?.takeIf(String::isNotBlank)?.take(1_000) ?: return@mapNotNull null
        val seconds = (((row["time"] as? JsonObject)?.string("total"))?.toDoubleOrNull()) ?: return@mapNotNull null
        val startMillis = (seconds * 1_000.0).toLong().takeIf { it >= 0L } ?: return@mapNotNull null
        IosLyricsLine(startMillis, text)
    }
    return lines.takeIf { it.isNotEmpty() }?.let { IosLyricsDocument(it, isSynced = true, provider = "Musixmatch") }
}

internal fun parseIosMusixmatchPlainLyrics(payload: String): IosLyricsDocument? {
    if (payload.isBlank() || payload.length > MAX_IOS_PROVIDER_RESPONSE_BYTES) return null
    val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
    val message = root["message"] as? JsonObject ?: return null
    val lyrics = (((message["body"] as? JsonObject)?.get("lyrics")) as? JsonObject)
        ?.string("lyrics_body")?.takeIf(String::isNotBlank) ?: return null
    return parseIosLyrics(lyrics.take(MAX_IOS_LYRICS_TEXT_CHARS), synced = false)?.copy(provider = "Musixmatch")
}

internal fun buildIosMusixmatchSignedUrl(
    baseUrl: String,
    path: String,
    parameters: List<Pair<String, String>>,
    date: String,
    sign: (String) -> String,
): String {
    val unsigned = "$baseUrl$path?${parameters.joinToString("&") { (key, value) -> "${encodeIosFormParameter(key)}=${encodeIosFormParameter(value)}" }}"
    val signature = sign(unsigned + date)
    return "$unsigned&signature=${encodeIosFormParameter(signature)}&signature_protocol=sha256"
}

internal fun formatIosMusixmatchDate(epochSeconds: Long): String {
    val days = epochSeconds.coerceAtLeast(0L) / 86_400L
    val z = days + 719_468L
    val era = z / 146_097L
    val dayOfEra = z - era * 146_097L
    val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
    var year = yearOfEra + era * 400L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val monthPrime = (5L * dayOfYear + 2L) / 153L
    val day = dayOfYear - (153L * monthPrime + 2L) / 5L + 1L
    val month = monthPrime + if (monthPrime < 10L) 3L else -9L
    if (month <= 2L) year += 1L
    return year.toString().padStart(4, '0') + month.toString().padStart(2, '0') + day.toString().padStart(2, '0')
}

private fun encodeIosFormParameter(value: String): String = buildString {
    val hex = "0123456789ABCDEF"
    for (byte in value.encodeToByteArray()) {
        val octet = byte.toInt() and 0xFF
        val char = octet.toChar()
        when {
            char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-._*" -> append(char)
            char == ' ' -> append('+')
            else -> append('%').append(hex[octet shr 4]).append(hex[octet and 0x0F])
        }
    }
}

internal data class IosAppleMusicSong(
    val id: String,
    val title: String,
    val artist: String,
    val durationSeconds: Double?,
)

internal fun parseIosAppleMusicSongs(payload: String): List<IosAppleMusicSong> {
    if (payload.isBlank() || payload.length > MAX_IOS_PROVIDER_RESPONSE_BYTES) return emptyList()
    val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return emptyList()
    val songIds = (((root["results"] as? JsonObject)?.get("songs") as? JsonObject)?.get("data") as? JsonArray)
        .orEmpty().take(25).mapNotNull { (it as? JsonObject)?.string("id") }
    val resources = ((root["resources"] as? JsonObject)?.get("songs") as? JsonObject) ?: return emptyList()
    return songIds.mapNotNull { id ->
        val attributes = (((resources[id] as? JsonObject)?.get("attributes")) as? JsonObject) ?: return@mapNotNull null
        val title = attributes.string("name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val artist = attributes.string("artistName")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        IosAppleMusicSong(
            id = id.take(128),
            title = title.take(200),
            artist = artist.take(200),
            durationSeconds = attributes.string("durationInMillis")?.toDoubleOrNull()?.div(1_000.0),
        )
    }
}

internal fun parseIosPaxsenixLyrics(payload: String): IosLyricsDocument? {
    if (payload.isBlank() || payload.length > MAX_IOS_PROVIDER_RESPONSE_BYTES) return null
    val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
    root.string("ttmlContent")?.takeIf(String::isNotBlank)?.let { parseIosTtmlLyrics(it, "Paxsenix")?.let { document -> return document } }

    val lines = mutableListOf<IosLyricsLine>()
    var totalChars = 0
    for (element in (root["content"] as? JsonArray).orEmpty()) {
        if (lines.size >= MAX_IOS_LYRICS_LINES || totalChars >= MAX_IOS_LYRICS_TEXT_CHARS) break
        val row = element as? JsonObject ?: continue
        val words = (row["text"] as? JsonArray).orEmpty().mapNotNull { textElement ->
            val textRow = textElement as? JsonObject ?: return@mapNotNull null
            val text = textRow.string("text")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val start = textRow.string("timestamp")?.toLongOrNull() ?: return@mapNotNull null
            val end = textRow.string("endtime")?.toLongOrNull()?.takeIf { it >= start }
            IosLyricsWord(start, end, text.take(500))
        }.take(MAX_IOS_LYRICS_LINES)
        val lineStart = row.string("timestamp")?.toLongOrNull() ?: words.firstOrNull()?.startMillis
        val lineText = row.string("lineText")?.takeIf(String::isNotBlank)
            ?: words.joinToString(" ") { it.text }.takeIf(String::isNotBlank)
        if (lineText.isNullOrBlank()) continue
        val text = lineText.take(1_000)
        if (totalChars + text.length > MAX_IOS_LYRICS_TEXT_CHARS) break
        lines += IosLyricsLine(lineStart, text, words)
        totalChars += text.length
    }
    if (lines.isNotEmpty()) {
        return IosLyricsDocument(lines, isSynced = lines.any { it.startMillis != null }, provider = "Paxsenix")
    }

    for (key in listOf("elrcMultiPerson", "elrc")) {
        root.string(key)?.takeIf(String::isNotBlank)?.let { parseIosLyrics(it, synced = true)?.copy(provider = "Paxsenix")?.let { document -> return document } }
    }
    return root.string("plain")?.takeIf(String::isNotBlank)?.let { parseIosLyrics(it, synced = false)?.copy(provider = "Paxsenix") }
}

internal data class IosKuGouSongHash(val durationSeconds: Int, val hash: String)
internal data class IosKuGouCandidate(val id: Long, val accessKey: String)

internal fun parseIosKuGouSongHashes(payload: String): List<IosKuGouSongHash> {
    if (payload.isBlank() || payload.length > MAX_IOS_PROVIDER_RESPONSE_BYTES) return emptyList()
    val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return emptyList()
    val rows = (((root["data"] as? JsonObject)?.get("info")) as? JsonArray).orEmpty()
    return rows.take(25).mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val duration = row.string("duration")?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        val hash = row.string("hash")?.takeIf { it.length in 8..128 && it.all { char -> char.isLetterOrDigit() } } ?: return@mapNotNull null
        IosKuGouSongHash(duration, hash)
    }
}

internal fun parseIosKuGouCandidates(payload: String): List<IosKuGouCandidate> {
    if (payload.isBlank() || payload.length > MAX_IOS_PROVIDER_RESPONSE_BYTES) return emptyList()
    val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return emptyList()
    return (root["candidates"] as? JsonArray).orEmpty().take(25).mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val id = row.string("id")?.toLongOrNull()?.takeIf { it > 0L } ?: return@mapNotNull null
        val accessKey = row.string("accesskey")?.takeIf { it.length in 1..512 && it.all { char -> char.isLetterOrDigit() || char == '-' || char == '_' } }
            ?: return@mapNotNull null
        IosKuGouCandidate(id, accessKey)
    }
}

@OptIn(ExperimentalEncodingApi::class)
internal fun parseIosKuGouLyrics(payload: String): IosLyricsDocument? {
    if (payload.isBlank() || payload.length > MAX_IOS_PROVIDER_RESPONSE_BYTES) return null
    val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
    val encoded = root.string("content")?.takeIf(String::isNotBlank) ?: return null
    if (encoded.length > MAX_IOS_PROVIDER_RESPONSE_BYTES) return null
    val lyrics = runCatching { Base64.Default.decode(encoded).decodeToString() }.getOrNull() ?: return null
    if (lyrics.encodeToByteArray().size > MAX_IOS_PROVIDER_RESPONSE_BYTES) return null
    return parseIosLyrics(lyrics, synced = true)?.copy(provider = "KuGou")
}

private fun iosLyricsSyncQuality(document: IosLyricsDocument): Int = when {
    document.lines.any { it.words.isNotEmpty() } -> 3
    document.isSynced -> 2
    else -> 1
}

internal fun rankIosLyricsDocuments(candidates: List<Pair<Int, IosLyricsDocument>>): IosLyricsDocument? =
    candidates.sortedWith(
        compareByDescending<Pair<Int, IosLyricsDocument>> { iosLyricsSyncQuality(it.second) }
            .thenBy { it.first },
    ).firstOrNull()?.second

internal fun parseIosTtmlLyrics(ttml: String, provider: String): IosLyricsDocument? {
    if (ttml.isBlank() || ttml.length > 512 * 1024) return null
    if (Regex("<!\\s*(?:DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE).containsMatchIn(ttml)) return null
    val paragraph = Regex("<(?:[A-Za-z0-9_.-]+:)?p\\b([^>]*)>(.*?)</(?:[A-Za-z0-9_.-]+:)?p\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    val beginAttribute = Regex("(?:^|\\s)(?:[A-Za-z0-9_.-]+:)?begin\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    val endAttribute = Regex("(?:^|\\s)(?:[A-Za-z0-9_.-]+:)?end\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    val span = Regex("<(?:[A-Za-z0-9_.-]+:)?span\\b([^>]*)>(.*?)</(?:[A-Za-z0-9_.-]+:)?span\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    val lines = mutableListOf<IosLyricsLine>()
    var totalChars = 0
    for (match in paragraph.findAll(ttml)) {
        val timedWords = span.findAll(match.groupValues[2]).mapNotNull { wordMatch ->
            val wordBegin = beginAttribute.find(wordMatch.groupValues[1])?.groupValues?.get(1) ?: return@mapNotNull null
            val wordStartMillis = parseIosTtmlTime(wordBegin) ?: return@mapNotNull null
            val wordEndMillis = endAttribute.find(wordMatch.groupValues[1])?.groupValues?.get(1)?.let(::parseIosTtmlTime)
            val wordText = decodeIosTtmlText(wordMatch.groupValues[2]).takeIf(String::isNotBlank) ?: return@mapNotNull null
            IosLyricsWord(wordStartMillis, wordEndMillis, wordText)
        }.take(MAX_IOS_LYRICS_LINES).toList()
        val startMillis = beginAttribute.find(match.groupValues[1])?.groupValues?.get(1)?.let(::parseIosTtmlTime)
            ?: timedWords.firstOrNull()?.startMillis ?: continue
        val text = decodeIosTtmlText(match.groupValues[2])
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(1_000)
            .takeIf(String::isNotEmpty) ?: continue
        if (Regex("&[A-Za-z][A-Za-z0-9]+;").containsMatchIn(text)) continue
        if (lines.size >= MAX_IOS_LYRICS_LINES || totalChars + text.length > MAX_IOS_LYRICS_TEXT_CHARS) break
        lines += IosLyricsLine(startMillis, text, timedWords)
        totalChars += text.length
    }
    lines.sortBy { it.startMillis }
    return lines.takeIf { it.isNotEmpty() }?.let { IosLyricsDocument(it, isSynced = true, provider = provider) }
}

private fun decodeIosTtmlText(raw: String): String = raw
    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
    .replace(Regex("<[^>]+>"), "")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&#39;", "'")
    .replace("&amp;", "&")
    .replace(Regex("&#(x[0-9a-fA-F]+|[0-9]+);")) { entity ->
        val value = entity.groupValues[1]
        val codePoint = if (value.startsWith("x", ignoreCase = true)) value.drop(1).toIntOrNull(16) else value.toIntOrNull()
        codePoint?.takeIf { it in 0..0xFFFF && it !in 0xD800..0xDFFF }?.toChar()?.toString().orEmpty()
    }

private fun parseIosTtmlTime(raw: String): Long? {
    val parts = raw.trim().removeSuffix("s").split(':')
    val seconds = when (parts.size) {
        1 -> parts[0].toDoubleOrNull()
        2 -> parts[0].toDoubleOrNull()?.times(60.0)?.plus(parts[1].toDoubleOrNull() ?: return null)
        3 -> parts[0].toDoubleOrNull()?.times(3_600.0)
            ?.plus((parts[1].toDoubleOrNull() ?: return null) * 60.0)
            ?.plus(parts[2].toDoubleOrNull() ?: return null)
        else -> null
    }?.takeIf { it >= 0.0 } ?: return null
    return (seconds * 1_000.0).toLong()
}

internal fun parseIosSimpMusicLyrics(payload: String, durationSeconds: Int?): IosLyricsDocument? {
    if (payload.isBlank() || payload.length > 512 * 1024) return null
    val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
    if (root.string("type") != "success") return null
    val rows = root["data"] as? JsonArray ?: return null
    data class Candidate(val duration: Int?, val rich: String?, val synced: String?, val plain: String?)
    val candidates = rows.mapNotNull { row ->
        val objectRow = row as? JsonObject ?: return@mapNotNull null
        Candidate(
            duration = objectRow.string("durationSeconds")?.toIntOrNull(),
            rich = objectRow.string("richSyncLyrics")?.takeIf(String::isNotBlank),
            synced = objectRow.string("syncedLyrics")?.takeIf(String::isNotBlank),
            plain = objectRow.string("plainLyric")?.takeIf(String::isNotBlank),
        )
    }.filter { candidate ->
        val expected = durationSeconds?.takeIf { it > 0 }
        expected == null || candidate.duration?.let { kotlin.math.abs(expected - it) <= 10 } == true
    }.sortedBy { candidate ->
        val expected = durationSeconds?.takeIf { it > 0 }
        if (expected != null && candidate.duration != null) kotlin.math.abs(expected - candidate.duration) else 0
    }
    for (candidate in candidates) {
        val choices = buildList {
            candidate.rich?.let { add(it to true) }
            candidate.synced?.let { add(it to true) }
            candidate.plain?.let { add(it to false) }
        }
        for ((text, isSynced) in choices) {
            val document = parseIosLyrics(text, synced = isSynced)
                ?.takeIf { it.lines.isNotEmpty() }
                ?: continue
            return document.copy(provider = "SimpMusic")
        }
    }
    return null
}

private class IosLyricsRateLimitedException(retryAfterSeconds: Long) :
    IllegalStateException("LRCLIB rate limit reached. Retry in $retryAfterSeconds seconds.")

/** The same video ID transcript endpoint used by Android's YouTubeSubtitleLyricsProvider. */
internal fun parseIosYouTubeTranscript(payload: String): IosLyricsDocument? {
    if (payload.isBlank() || payload.length > 2 * 1024 * 1024) return null
    val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
    val actions = root["actions"] as? JsonArray ?: return null
    val groups = actions.asSequence()
        .mapNotNull { it as? JsonObject }
        .mapNotNull { it["updateEngagementPanelAction"] as? JsonObject }
        .mapNotNull { it["content"] as? JsonObject }
        .mapNotNull { it["transcriptRenderer"] as? JsonObject }
        .mapNotNull { it["body"] as? JsonObject }
        .mapNotNull { it["transcriptBodyRenderer"] as? JsonObject }
        .mapNotNull { it["cueGroups"] as? JsonArray }
        .firstOrNull() ?: return null

    val lines = buildList {
        for (group in groups) {
            if (size >= MAX_IOS_LYRICS_LINES) break
            val cueGroup = group.asObjectOrNull()?.get("transcriptCueGroupRenderer").asObjectOrNull() ?: continue
            val cue = (cueGroup["cues"] as? JsonArray)?.firstOrNull().asObjectOrNull()
                ?.get("transcriptCueRenderer").asObjectOrNull() ?: continue
            val startMillis = cue["startOffsetMs"].stringValue()?.toLongOrNull()
                ?.takeIf { it >= 0L } ?: continue
            val cueText = cue["cue"].asObjectOrNull()?.get("simpleText").stringValue()
                ?.trim('♪', ' ', '\n', '\r', '\t')
                ?.takeIf { it.isNotEmpty() && it.length <= 1_000 } ?: continue
            add(IosLyricsLine(startMillis, cueText))
        }
    }.distinct().sortedBy { it.startMillis }
    return lines.takeIf { it.isNotEmpty() }?.let { IosLyricsDocument(it, isSynced = true, provider = "YouTube Subtitle") }
}

/** Encodes YouTube's transcript params: newline, vertical-tab, then the video ID. */
internal fun encodeYouTubeTranscriptParams(videoId: String): String {
    require(videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) { "This video has an invalid ID." }
    val input = "\n\u000b$videoId".encodeToByteArray()
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    return buildString((input.size + 2) / 3 * 4) {
        var index = 0
        while (index < input.size) {
            val first = input[index++].toInt() and 0xff
            val hasSecond = index < input.size
            val second = if (hasSecond) input[index++].toInt() and 0xff else 0
            val hasThird = index < input.size
            val third = if (hasThird) input[index++].toInt() and 0xff else 0
            append(alphabet[first ushr 2])
            append(alphabet[((first and 0x03) shl 4) or (second ushr 4)])
            append(if (hasSecond) alphabet[((second and 0x0f) shl 2) or (third ushr 6)] else '=')
            append(if (hasThird) alphabet[third and 0x3f] else '=')
        }
    }
}

private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement?.stringValue(): String? = runCatching { this?.jsonPrimitive?.contentOrNull }.getOrNull()

internal fun parseIosLyrics(raw: String, synced: Boolean): IosLyricsDocument? {
    if (raw.isBlank() || raw.length > 192 * 1024) return null
    val timestamp = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    val wordTimestamp = Regex("<(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?>")
    val parsed = mutableListOf<IosLyricsLine>()
    for (row in raw.lineSequence().take(MAX_IOS_LYRICS_LINES + 1)) {
        if (parsed.size >= MAX_IOS_LYRICS_LINES) break
        val marks = timestamp.findAll(row).toList()
        if (marks.isEmpty()) {
            val plain = row.trim().takeIf { it.isNotEmpty() && !it.startsWith("[") }
            if (plain != null) parsed += IosLyricsLine(null, plain)
            continue
        }
        val rawText = row.substring(marks.last().range.last + 1)
        val wordMarks = wordTimestamp.findAll(rawText).toList()
        val words = wordMarks.mapIndexedNotNull { index, wordMark ->
            val afterTimestamp = wordMark.range.last + 1
            val nextTimestamp = wordMarks.getOrNull(index + 1)?.range?.first ?: rawText.length
            val wordText = rawText.substring(afterTimestamp, nextTimestamp)
            if (wordText.isBlank()) return@mapIndexedNotNull null
            parseIosLrcTime(wordMark.groupValues)?.let { IosLyricsWord(it, null, wordText) }
        }
        val text = rawText.replace(wordTimestamp, "").trim()
        if (text.isEmpty()) continue
        for (mark in marks) {
            val startMillis = parseIosLrcTime(mark.groupValues) ?: continue
            parsed += IosLyricsLine(startMillis, text, words)
            if (parsed.size >= MAX_IOS_LYRICS_LINES) break
        }
    }
    val lines = parsed.distinct().sortedWith(compareBy<IosLyricsLine> { it.startMillis ?: Long.MIN_VALUE })
    return lines.takeIf { it.isNotEmpty() }?.let { IosLyricsDocument(it, synced && it.any { line -> line.startMillis != null }) }
}

private fun parseIosLrcTime(parts: List<String>): Long? {
    val minutes = parts.getOrNull(1)?.toLongOrNull() ?: return null
    val seconds = parts.getOrNull(2)?.toLongOrNull()?.takeIf { it < 60 } ?: return null
    val fraction = parts.getOrNull(3).orEmpty().padEnd(3, '0').take(3).toLongOrNull() ?: 0L
    return (minutes * 60 + seconds) * 1_000 + fraction
}

internal fun activeIosLyricsLine(lines: List<IosLyricsLine>, positionMillis: Long): Int {
    var low = 0
    var high = lines.lastIndex
    var active = -1
    while (low <= high) {
        val middle = (low + high) ushr 1
        val timestamp = lines[middle].startMillis
        if (timestamp == null || timestamp <= positionMillis) {
            if (timestamp != null) active = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return active
}

internal fun activeIosLyricsWord(words: List<IosLyricsWord>, positionMillis: Long): Int {
    var active = -1
    for ((index, word) in words.withIndex()) {
        if (word.startMillis > positionMillis) break
        if (word.endMillis == null || positionMillis <= word.endMillis) active = index
    }
    return active
}

private fun cleanLyricsTitle(value: String): String = value
    .replace(Regex("\\s*\\(.*?(official|video|audio|lyrics|remaster|remix|live|acoustic|version|edit).*?\\)", RegexOption.IGNORE_CASE), "")
    .replace(Regex("\\s*\\[.*?(official|video|audio|lyrics|remaster|remix|live|acoustic|version|edit).*?\\]", RegexOption.IGNORE_CASE), "")
    .replace(Regex("\\s*[-|].*$"), "")
    .trim()

private fun primaryLyricsArtist(value: String): String = value
    .split(Regex("\\s+(?:&|and|feat\\.?|ft\\.?|featuring|with|x)\\s+|,", RegexOption.IGNORE_CASE))
    .firstOrNull()?.trim().orEmpty()

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun normalizeLyricsMatch(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)

private fun lyricsSimilarity(left: String, right: String): Double = when {
    left.isEmpty() || right.isEmpty() -> 0.0
    left == right -> 1.0
    left.contains(right) || right.contains(left) -> minOf(left.length, right.length).toDouble() / maxOf(left.length, right.length)
    else -> 0.0
}
