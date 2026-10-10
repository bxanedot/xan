package com.music.innertube

import com.music.innertube.models.YouTubeClient
import com.music.innertube.models.response.PlayerResponse
import io.ktor.http.URLBuilder
import io.ktor.http.parseQueryString
import com.music.innertube.models.IpVersion
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.extractor.StreamingService
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.text.NumberFormat

class NewPipeDownloaderImpl(
    proxy: Proxy?,
    proxyAuth: String? = null,
) : Downloader() {
    private val client =
        OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val addresses = Dns.SYSTEM.lookup(hostname)
                    return when (YouTube.ipVersion) {
                        IpVersion.IPV4 -> addresses.filter { it is Inet4Address }.ifEmpty { addresses }
                        IpVersion.IPV6 -> addresses.filter { it is Inet6Address }.ifEmpty { addresses }
                        IpVersion.AUTO -> addresses
                    }
                }
            })
            .proxySelector(object : ProxySelector() {
                override fun select(uri: URI?): List<Proxy> = listOfNotNull(YouTube.proxy ?: Proxy.NO_PROXY)
                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
            })
            .proxyAuthenticator { _, response ->
                YouTube.proxyAuth?.let { auth ->
                    response.request.newBuilder()
                        .header("Proxy-Authorization", auth)
                        .build()
                } ?: response.request
            }
            .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBuilder =
            okhttp3.Request
                .Builder()
                .method(httpMethod, dataToSend?.toRequestBody())
                .url(url)
                .addHeader("User-Agent", YouTubeClient.USER_AGENT_WEB)

        headers.forEach { (headerName, headerValueList) ->
            if (headerValueList.size > 1) {
                requestBuilder.removeHeader(headerName)
                headerValueList.forEach { headerValue ->
                    requestBuilder.addHeader(headerName, headerValue)
                }
            } else if (headerValueList.size == 1) {
                requestBuilder.header(headerName, headerValueList[0])
            }
        }

        val response = client.newCall(requestBuilder.build()).execute()

        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }

        val responseBodyToReturn = response.body.string()
        val latestUrl = response.request.url.toString()
        return Response(response.code, response.message, response.headers.toMultimap(), responseBodyToReturn, latestUrl)
    }
}

class NewPipeUtils(
    downloader: Downloader,
) {
    init {
        NewPipe.init(downloader)
    }

    fun getSignatureTimestamp(videoId: String): Result<Int> =
        runCatching {
            YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
        }

    fun getStreamUrl(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
    ): String? =
        try {
            val url =
                format.url ?: format.signatureCipher?.let { signatureCipher ->
                    val params = parseQueryString(signatureCipher)
                    val obfuscatedSignature =
                        params["s"]
                            ?: throw ParsingException("Could not parse cipher signature")
                    val signatureParam =
                        params["sp"]
                            ?: throw ParsingException("Could not parse cipher signature parameter")
                    val url =
                        params["url"]?.let { URLBuilder(it) }
                            ?: throw ParsingException("Could not parse cipher url")
                    url.parameters[signatureParam] =
                        YoutubeJavaScriptPlayerManager.deobfuscateSignature(
                            videoId,
                            obfuscatedSignature,
                        )
                    url.toString()
                } ?: throw ParsingException("Could not find format url")

            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                videoId,
                url,
            )
        } catch (e: Exception) {
            // Don't print stack trace - caller handles errors
            null
        }
}

/** A video search result from one of NewPipe Extractor's supported services. */
data class ExternalVideoSearchResult(
    val serviceId: Int,
    val serviceName: String,
    val id: String,
    val url: String,
    val title: String,
    val uploader: String,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
    val viewCountText: String? = null,
    val publishedTimeText: String? = null,
    val description: String? = null,
    val channelId: String? = null,
    val directFileExtension: String? = null,
    val directFileMimeType: String? = null,
)

/** One video representation exposed by a source page. */
data class ExternalVideoFormat(
    val id: String,
    val url: String,
    val mimeType: String?,
    val extension: String,
    val resolution: String,
    val height: Int,
    val bitrate: Int,
    val videoOnly: Boolean,
    val adaptiveManifest: Boolean = false,
    val audioUrl: String? = null,
    val audioMimeType: String? = null,
    val audioExtension: String? = null,
    val codec: String? = null,
    val audioCodec: String? = null,
)

data class ExternalVideoInfo(
    val title: String,
    val uploader: String,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
    val formats: List<ExternalVideoFormat>,
)

/** A page from YouTube's regular video home feed. */
data class ExternalVideoHomePage(
    val videos: List<ExternalVideoSearchResult>,
    val continuation: String?,
)

object NewPipeExtractor {
    /** NewPipe's built-in YouTube search is superseded by XAN's InnerTube search. */
    const val YOUTUBE_SERVICE_ID = 0

    @Volatile
    private var newPipeDownloader: NewPipeDownloaderImpl? = null
    private var newPipeUtils: NewPipeUtils? = null
    private var isInitialized = false

    @Synchronized
    fun init() {
        if (!isInitialized) {
            newPipeDownloader = NewPipeDownloaderImpl(
                proxy = YouTube.proxy,
                proxyAuth = YouTube.proxyAuth
            )
            newPipeUtils = NewPipeUtils(newPipeDownloader!!)
            isInitialized = true
        }
    }

    fun getSignatureTimestamp(videoId: String): Result<Int> {
        init()
        return newPipeUtils?.getSignatureTimestamp(videoId)
            ?: Result.failure(Exception("NewPipeUtils not initialized"))
    }

    fun getStreamUrl(
        format: PlayerResponse.StreamingData.Format,
        videoId: String
    ): String? {
        init()
        return newPipeUtils?.getStreamUrl(format, videoId)
    }

    fun newPipePlayer(videoId: String): List<Pair<Int, String>> {
        init()
        return try {
            val streamInfo = StreamInfo.getInfo(
                NewPipe.getService(0),
                "https://www.youtube.com/watch?v=$videoId"
            )
            val streamsList = streamInfo.audioStreams + streamInfo.videoStreams + streamInfo.videoOnlyStreams
            streamsList.mapNotNull {
                (it.itagItem?.id ?: return@mapNotNull null) to it.content
            }
        } catch (e: Exception) {
            // Don't print stack trace - caller handles errors
            emptyList()
        }
    }

    /**
     * Search all registered services for video and live-video results. Audio-only
     * results are ignored so they do not appear in XAN's video fallback.
     * Individual service failures do not hide results from the other services.
     */
    fun searchVideos(
        query: String,
        maxPerService: Int = 8,
        excludedServiceIds: Set<Int> = emptySet(),
    ): List<ExternalVideoSearchResult> {
        init()
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return emptyList()

        return ServiceList.all().filterNot { it.serviceId in excludedServiceIds }.flatMap { service ->
            runCatching {
                val searchInfo = SearchInfo.getInfo(service.getSearchExtractor(normalizedQuery))
                searchInfo.relatedItems
                    .filterIsInstance<StreamInfoItem>()
                    .filter { it.streamType == StreamType.VIDEO_STREAM || it.streamType == StreamType.LIVE_STREAM }
                    .take(maxPerService)
                    .map { item ->
                        ExternalVideoSearchResult(
                            serviceId = service.serviceId,
                            serviceName = service.serviceInfo.name,
                            id = item.url,
                            url = item.url,
                            title = item.name,
                            uploader = item.uploaderName.orEmpty(),
                            durationSeconds = item.duration,
                            thumbnailUrl = item.thumbnails.firstOrNull()?.url,
                            viewCountText = item.viewCount
                                .takeIf { it >= 0 }
                                ?.let { "${NumberFormat.getIntegerInstance().format(it)} views" },
                            publishedTimeText = item.textualUploadDate,
                            description = item.shortDescription,
                        )
                    }
            }.getOrDefault(emptyList())
        }.distinctBy { it.serviceId to it.url }
    }

    /** Resolve a pasted video page URL on any registered service. */
    fun resolveVideoUrl(url: String): Result<ExternalVideoSearchResult> = runCatching {
        init()
        val service = ServiceList.all().firstOrNull { candidate ->
            runCatching { candidate.getLinkTypeByUrl(url) == StreamingService.LinkType.STREAM }.getOrDefault(false)
        } ?: throw IllegalArgumentException("This video page is not supported")
        val info = StreamInfo.getInfo(service, url)
        ExternalVideoSearchResult(
            serviceId = service.serviceId,
            serviceName = service.serviceInfo.name,
            id = info.id,
            url = info.url,
            title = info.name,
            uploader = info.uploaderName.orEmpty(),
            durationSeconds = info.duration,
            thumbnailUrl = info.thumbnails.firstOrNull()?.url,
        )
    }

    /** Resolve every stream representation offered by a supported video page. */
    fun getVideoInfo(result: ExternalVideoSearchResult): Result<ExternalVideoInfo> = runCatching {
        init()
        val service = ServiceList.all().firstOrNull { it.serviceId == result.serviceId }
            ?: throw IllegalArgumentException("Video source is no longer registered")
        val info = StreamInfo.getInfo(service, result.url)
        val thumbnail = info.thumbnails.firstOrNull()?.url ?: result.thumbnailUrl
        val audioStreams = info.audioStreams
            .filter(AudioStream::isUrl)
            .mapNotNull { stream ->
                val url = stream.content?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val format = stream.format ?: return@mapNotNull null
                Triple(stream, url, format)
            }
        val formats = (info.videoStreams + info.videoOnlyStreams)
            .filter(VideoStream::isUrl)
            .mapNotNull { stream ->
                val url = stream.content?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val mediaFormat = stream.format
                val audioTrack = if (stream.isVideoOnly) {
                    audioStreams
                        .filter { (_, _, audioFormat) ->
                            val audioExtension = audioFormat.suffix?.lowercase()
                            when (mediaFormat?.suffix?.lowercase()) {
                                "mp4", "m4v" -> audioExtension in setOf("m4a", "mp4")
                                "webm" -> audioExtension == "webm"
                                else -> audioExtension == mediaFormat?.suffix?.lowercase()
                            }
                        }
                        .maxByOrNull { (audio, _, _) -> audio.averageBitrate }
                } else null
                ExternalVideoFormat(
                    id = stream.id.ifBlank { "${stream.resolution}-${mediaFormat?.suffix.orEmpty()}" },
                    url = url,
                    mimeType = mediaFormat?.mimeType,
                    extension = mediaFormat?.suffix?.takeIf(String::isNotBlank)
                        ?: url.substringBefore('?').substringAfterLast('.', "bin"),
                    resolution = stream.resolution,
                    height = stream.height,
                    bitrate = stream.bitrate,
                    videoOnly = stream.isVideoOnly,
                    audioUrl = audioTrack?.second,
                    audioMimeType = audioTrack?.third?.mimeType,
                    audioExtension = audioTrack?.third?.suffix,
                    codec = stream.codec,
                    audioCodec = audioTrack?.first?.codec,
                )
            }
            .distinctBy { it.url }
            .sortedWith(compareByDescending<ExternalVideoFormat> { it.height }.thenByDescending { it.bitrate })
            .toMutableList()

        // Adaptive HLS stays adaptive during playback. It is intentionally marked
        // non-downloadable as a file: saving only the manifest would not save the
        // video segments.
        info.hlsUrl?.takeIf(String::isNotBlank)?.let { hlsUrl ->
            formats.add(
                0,
                ExternalVideoFormat(
                    id = "adaptive-hls",
                    url = hlsUrl,
                    mimeType = "application/x-mpegURL",
                    extension = "m3u8",
                    resolution = "Adaptive HLS",
                    height = 0,
                    bitrate = 0,
                    videoOnly = false,
                    adaptiveManifest = true,
                ),
            )
        }
        info.dashMpdUrl?.takeIf(String::isNotBlank)?.let { dashUrl ->
            formats.add(
                0,
                ExternalVideoFormat(
                    id = "adaptive-dash",
                    url = dashUrl,
                    mimeType = "application/dash+xml",
                    extension = "mpd",
                    resolution = "Adaptive DASH",
                    height = 0,
                    bitrate = 0,
                    videoOnly = false,
                    adaptiveManifest = true,
                ),
            )
        }

        ExternalVideoInfo(
            title = info.name,
            uploader = info.uploaderName.orEmpty().ifBlank { result.uploader },
            durationSeconds = info.duration,
            thumbnailUrl = thumbnail,
            formats = formats,
        )
    }
}
