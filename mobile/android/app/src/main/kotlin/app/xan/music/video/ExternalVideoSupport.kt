/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.video

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MediaItem
import androidx.media3.common.Format
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_VIDEO
import androidx.core.net.toUri
import androidx.media3.exoplayer.offline.DownloadService
import app.xan.music.models.MediaMetadata
import app.xan.music.models.MediaMetadata.ExternalVideoPlaybackFormat
import app.xan.music.playback.DownloadTarget
import app.xan.music.playback.ExoDownloadService
import app.xan.music.playback.buildDownloadRequest
import app.xan.music.playback.queues.ListQueue
import com.music.innertube.models.YouTubeClient
import com.music.innertube.ExternalVideoFormat
import com.music.innertube.ExternalVideoInfo
import com.music.innertube.ExternalVideoSearchResult
import dagger.hilt.android.EntryPointAccessors
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

const val EXTERNAL_VIDEO_MEDIA_ID_PREFIX = "external-video:"
val EXTERNAL_VIDEO_USER_AGENT: String = YouTubeClient.USER_AGENT_WEB

/** Treat a pasted file URL as a source result even when its host has no extractor. */
fun directVideoResultFromQuery(
    query: String,
    responseMimeType: String? = null,
    suggestedFilename: String? = null,
): ExternalVideoSearchResult? {
    val raw = query.trim()
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
    val urlExtension = uri.path.substringAfterLast('.', "").lowercase()
    val nameExtension = suggestedFilename
        ?.substringAfterLast('.', "")
        ?.lowercase()
        .orEmpty()
    val extension = when {
        urlExtension in DIRECT_VIDEO_EXTENSIONS -> urlExtension
        nameExtension in DIRECT_VIDEO_EXTENSIONS -> nameExtension
        else -> urlExtension.takeIf { it.isNotBlank() }
            ?.takeIf { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)?.startsWith("video/") == true }
            ?: nameExtension.takeIf { it.isNotBlank() }
                ?.takeIf { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)?.startsWith("video/") == true }
    }
    val mimeType = responseMimeType?.substringBefore(';')?.trim()?.takeIf(String::isNotBlank)
        ?: extension?.let(MimeTypeMap.getSingleton()::getMimeTypeFromExtension)
    if (
        extension == null && mimeType?.startsWith("video/", ignoreCase = true) != true &&
        mimeType !in ADAPTIVE_VIDEO_MIME_TYPES
    ) return null
    val resolvedExtension = extension
        ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        ?: if (mimeType in ADAPTIVE_VIDEO_MIME_TYPES) {
            if (mimeType == "application/dash+xml") "mpd" else "m3u8"
        } else "bin"

    val fileName = (suggestedFilename?.substringAfterLast('/') ?: uri.path.substringAfterLast('/'))
        .takeIf(String::isNotBlank)
        ?.let(Uri::decode)
        ?.substringBeforeLast('.', missingDelimiterValue = "")
        ?.takeIf(String::isNotBlank)
        ?: uri.host
    return ExternalVideoSearchResult(
        serviceId = DIRECT_FILE_SERVICE_ID,
        serviceName = uri.host,
        id = raw,
        url = raw,
        title = fileName,
        uploader = uri.host,
        durationSeconds = -1,
        thumbnailUrl = null,
        directFileExtension = resolvedExtension,
        directFileMimeType = mimeType,
    )
}

/** Probe non-page URLs without extensions, such as signed CDN links, by response type. */
fun probeDirectVideoUrl(query: String): ExternalVideoSearchResult? {
    val raw = query.trim()
    directVideoResultFromQuery(raw)?.let { return it }
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return null

    val request = Request.Builder().url(raw).head().header("User-Agent", EXTERNAL_VIDEO_USER_AGENT).build()
    return runCatching {
        directVideoProbeClient.newCall(request).execute().use { response ->
            val mimeType = response.header("Content-Type")?.substringBefore(';')?.trim()
            if (!response.isSuccessful) return@use null
            val dispositionName = response.header("Content-Disposition")
                ?.substringAfter("filename=", "")
                ?.trim()
                ?.trim('"', '\'')
                ?.takeIf(String::isNotBlank)
            directVideoResultFromQuery(raw, mimeType, dispositionName)
        }
    }.getOrNull()
}

fun directVideoInfo(result: ExternalVideoSearchResult): ExternalVideoInfo {
    val extension = result.directFileExtension ?: result.url.toUri().lastPathSegment.orEmpty()
        .substringAfterLast('.', "bin")
        .lowercase()
    val mimeType = result.directFileMimeType ?: when (extension) {
        "m3u8" -> "application/x-mpegURL"
        "mpd" -> "application/dash+xml"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }
    return ExternalVideoInfo(
        title = result.title,
        uploader = result.uploader,
        durationSeconds = -1,
        thumbnailUrl = null,
        formats = listOf(
            ExternalVideoFormat(
                id = "direct-file",
                url = result.url,
                mimeType = mimeType,
                extension = extension,
                resolution = "Original file",
                height = 0,
                bitrate = 0,
                videoOnly = false,
                adaptiveManifest = extension in ADAPTIVE_VIDEO_EXTENSIONS,
            ),
        ),
    )
}

/**
 * Pick a reliable automatic playback format. Prefer a complete progressive
 * stream, then a video stream with its compatible audio track. Adaptive
 * manifests remain a fallback for sources that do not expose direct streams.
 */
fun ExternalVideoInfo.autoPlaybackFormat(): ExternalVideoFormat? {
    val quality = compareBy<ExternalVideoFormat> { it.height }.thenBy { it.bitrate }
    return formats
        .filter { !it.adaptiveManifest && !it.videoOnly }
        .maxWithOrNull(quality)
        ?: formats
            .filter { !it.adaptiveManifest && it.videoOnly && !it.audioUrl.isNullOrBlank() }
            .maxWithOrNull(quality)
        ?: formats
            .filter { it.adaptiveManifest && !it.videoOnly }
            .maxWithOrNull(quality)
        ?: formats.filterNot { it.adaptiveManifest }.maxWithOrNull(quality)
        ?: formats.maxWithOrNull(quality)
}

fun buildExternalVideoQueue(
    result: ExternalVideoSearchResult,
    info: ExternalVideoInfo,
    format: ExternalVideoFormat,
): ExternalVideoQueue {
    val mediaId = externalVideoMediaId(result, format)
    val duration = info.durationSeconds.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() ?: -1
    val metadata = MediaMetadata(
        id = mediaId,
        title = info.title.ifBlank { result.title },
        artists = listOfNotNull(info.uploader.ifBlank { result.uploader }.takeIf(String::isNotBlank)
            ?.let { MediaMetadata.Artist(id = null, name = it) }),
        duration = duration,
        thumbnailUrl = info.thumbnailUrl ?: result.thumbnailUrl,
        musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
        isExternalVideo = true,
        externalVideoDescription = result.description,
        externalVideoViewCountText = result.viewCountText,
        externalVideoPublishedTimeText = result.publishedTimeText,
        externalVideoSource = result.serviceName,
        externalVideoServiceId = result.serviceId,
        externalVideoPageId = result.id,
        externalVideoFormatId = format.id,
        externalVideoPageUrl = result.url,
        externalVideoUploader = info.uploader.ifBlank { result.uploader },
        externalVideoResolution = format.resolution,
        externalVideoExtension = format.extension,
        externalVideoMimeType = format.mimeType,
        externalVideoCodec = format.codec,
        externalVideoAudioCodec = format.audioCodec,
        externalVideoAudioUrl = format.audioUrl,
        externalVideoAudioMimeType = format.audioMimeType,
        externalVideoVideoOnly = format.videoOnly,
        externalVideoFormats = info.formats.map { it.toPlaybackFormat() },
    )
    return ExternalVideoQueue(
        result.serviceName,
        buildExternalVideoMediaItem(metadata, format.toPlaybackFormat()),
        info.relatedVideos,
    )
}

fun buildExternalVideoMediaItem(
    metadata: MediaMetadata,
    format: ExternalVideoPlaybackFormat,
): MediaItem {
    // Rebuilding the same quality must retain its download/cache identity. Signed
    // stream URLs change on each extraction and are not a media identity.
    val mediaId = externalVideoResult(metadata)?.let { externalVideoMediaId(it, format.toExternalVideoFormat()) }
        ?: metadata.id
    val selectedMetadata = metadata.copy(
        id = mediaId,
        externalVideoFormatId = format.id,
        externalVideoResolution = format.resolution,
        externalVideoExtension = format.extension,
        externalVideoMimeType = format.mimeType,
        externalVideoCodec = format.codec,
        externalVideoAudioCodec = format.audioCodec,
        externalVideoStreamUrl = format.url,
        externalVideoAdaptive = format.adaptiveManifest,
        externalVideoAudioUrl = format.audioUrl,
        externalVideoAudioMimeType = format.audioMimeType,
        externalVideoVideoOnly = format.videoOnly,
    )
    val mediaMetadata = androidx.media3.common.MediaMetadata.Builder()
        .setTitle(selectedMetadata.title)
        .setArtist(selectedMetadata.artists.firstOrNull()?.name)
        .setDisplayTitle(selectedMetadata.title)
        .setArtworkUri(selectedMetadata.thumbnailUrl?.takeIf(String::isNotBlank)?.toUri())
        .setMediaType(MEDIA_TYPE_VIDEO)
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .build()
    val mediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(format.url)
        .apply { if (!format.adaptiveManifest) setCustomCacheKey(mediaId) }
        .setMimeType(format.mimeType?.takeIf(String::isNotBlank))
        .setTag(selectedMetadata)
        .setMediaMetadata(mediaMetadata)
        .build()
    return mediaItem
}

private fun ExternalVideoFormat.toPlaybackFormat() = ExternalVideoPlaybackFormat(
    id = id,
    url = url,
    mimeType = mimeType,
    extension = extension,
    resolution = resolution,
    height = height,
    bitrate = bitrate,
    videoOnly = videoOnly,
    adaptiveManifest = adaptiveManifest,
    audioUrl = audioUrl,
    audioMimeType = audioMimeType,
    audioExtension = audioExtension,
    codec = codec,
    audioCodec = audioCodec,
)

fun externalVideoResult(metadata: MediaMetadata): ExternalVideoSearchResult? {
    val serviceId = metadata.externalVideoServiceId ?: return null
    val url = metadata.externalVideoPageUrl ?: return null
    return ExternalVideoSearchResult(
        serviceId = serviceId,
        serviceName = metadata.externalVideoSource.orEmpty(),
        id = metadata.externalVideoPageId ?: url,
        url = url,
        title = metadata.title,
        uploader = metadata.externalVideoUploader.orEmpty(),
        durationSeconds = metadata.duration.toLong(),
        thumbnailUrl = metadata.thumbnailUrl,
        viewCountText = metadata.externalVideoViewCountText,
        publishedTimeText = metadata.externalVideoPublishedTimeText,
        description = metadata.externalVideoDescription,
    )
}

fun externalVideoInfo(metadata: MediaMetadata): ExternalVideoInfo = ExternalVideoInfo(
    title = metadata.title,
    uploader = metadata.externalVideoUploader.orEmpty(),
    durationSeconds = metadata.duration.toLong(),
    thumbnailUrl = metadata.thumbnailUrl,
    formats = metadata.externalVideoFormats.map { it.toExternalVideoFormat() },
)

fun ExternalVideoPlaybackFormat.toExternalVideoFormat() = ExternalVideoFormat(
    id = id,
    url = url,
    mimeType = mimeType,
    extension = extension,
    resolution = resolution,
    height = height,
    bitrate = bitrate,
    videoOnly = videoOnly,
    adaptiveManifest = adaptiveManifest,
    audioUrl = audioUrl,
    audioMimeType = audioMimeType,
    audioExtension = audioExtension,
    codec = codec,
    audioCodec = audioCodec,
)

/** Find the format the player is actually using; Auto resolves to its current stream. */
fun MediaMetadata.selectedExternalVideoFormat(currentUri: String? = externalVideoStreamUrl): ExternalVideoFormat? {
    val formats = externalVideoFormats.map { it.toExternalVideoFormat() }
    return formats.firstOrNull { it.url == currentUri }
        ?: formats.firstOrNull {
            it.resolution == externalVideoResolution && it.extension.equals(externalVideoExtension, true) &&
                it.mimeType == externalVideoMimeType
        }
        ?: formats.firstOrNull { it.resolution == externalVideoResolution }
}

/**
 * Queue a selected stream in XAN's persistent offline download manager. Separate
 * audio/video streams are muxed to a file in Android's public Downloads folder.
 */
@UnstableApi
suspend fun enqueueExternalVideoDownload(
    context: Context,
    result: ExternalVideoSearchResult,
    info: ExternalVideoInfo,
    format: ExternalVideoFormat,
    activeVideoFormat: Format? = null,
    activeAudioFormat: Format? = null,
): Boolean {
    val uri = Uri.parse(format.url)
    require(uri.scheme == "https" || uri.scheme == "http") { "This video URL cannot be downloaded." }

    val title = info.title.ifBlank { result.title }
    val uploader = info.uploader.ifBlank { result.uploader }
    val mediaId = externalVideoMediaId(result, format)
    when (externalVideoDownloadRoute(format)) {
        ExternalVideoDownloadRoute.ADAPTIVE_MANIFEST -> {
            val target = externalVideoDownloadTarget(mediaId, title, uploader, result, info, format)
            persistExternalVideoTarget(context, target)
            val request = buildAdaptiveVideoDownloadRequest(
                context, target, format, activeVideoFormat, activeAudioFormat,
            )
            DownloadService.sendAddDownload(context, ExoDownloadService::class.java, request, true)
        }
        ExternalVideoDownloadRoute.PROGRESSIVE_FILE -> {
            val target = externalVideoDownloadTarget(mediaId, title, uploader, result, info, format)
            persistExternalVideoTarget(context, target)
            val request = buildDownloadRequest(target, uri, format.mimeType)
            DownloadService.sendAddDownload(context, ExoDownloadService::class.java, request, true)
        }
        ExternalVideoDownloadRoute.SPLIT_TRACKS -> {
            require(!format.audioUrl.isNullOrBlank()) {
                "This video quality has no matching audio stream and cannot be downloaded. Select another quality."
            }
            enqueueMergedExternalVideoDownload(context, result, info, format)
        }
    }
    return true
}

private suspend fun persistExternalVideoTarget(context: Context, target: DownloadTarget) {
    val dependencies = EntryPointAccessors.fromApplication(
        context.applicationContext,
        ExternalVideoDownloadDependencies::class.java,
    )
    dependencies.downloadUtil().persistQueuedTarget(target)
}

private fun externalVideoDownloadTarget(
    id: String,
    title: String,
    uploader: String,
    result: ExternalVideoSearchResult,
    info: ExternalVideoInfo,
    format: ExternalVideoFormat,
) = DownloadTarget(
    id = id,
    title = title,
    artists = listOfNotNull(uploader.takeIf(String::isNotBlank)),
    thumbnailUrl = info.thumbnailUrl ?: result.thumbnailUrl,
    duration = info.durationSeconds.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() ?: -1,
    externalVideoSource = result.serviceName,
    externalVideoPageUrl = result.url,
    externalVideoServiceId = result.serviceId,
    externalVideoPageId = result.id,
    externalVideoFormatId = format.id,
    externalVideoStreamUrl = format.url,
    externalVideoMimeType = format.mimeType,
    externalVideoCodec = format.codec,
    externalVideoAudioCodec = format.audioCodec,
    externalVideoExtension = format.extension,
    externalVideoResolution = format.resolution,
    externalVideoAdaptive = format.adaptiveManifest,
)

fun externalVideoMediaId(result: ExternalVideoSearchResult, format: ExternalVideoFormat): String =
    externalVideoMediaId(
        "${result.serviceId}|${result.id.ifBlank { result.url }}",
        "${format.id}|${format.extension.lowercase()}|${format.resolution}|${format.codec.orEmpty()}",
    )

private fun externalVideoMediaId(baseIdentity: String, formatId: String): String {
    val identity = "$baseIdentity|$formatId"
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { byte -> "%02x".format(byte) }
    return "$EXTERNAL_VIDEO_MEDIA_ID_PREFIX$digest"
}

const val DIRECT_FILE_SERVICE_ID = -1

private val DIRECT_VIDEO_EXTENSIONS = setOf(
    "3g2", "3gp", "asf", "avi", "divx", "dv", "f4v", "flv", "m1v", "m2p", "m2t", "m2ts", "m2v",
    "m4v", "mkv", "mov", "mp2", "mp4", "mpe", "mpeg", "mpg", "mpv", "mxf", "nsv", "nut", "ogm",
    "ogv", "qt", "rm", "rmvb", "roq", "ts", "vob", "viv", "webm", "wmv", "yuv", "m3u8", "mpd",
)

private val ADAPTIVE_VIDEO_EXTENSIONS = setOf("m3u8", "mpd")
private val ADAPTIVE_VIDEO_MIME_TYPES = setOf("application/x-mpegURL", "application/vnd.apple.mpegurl", "application/dash+xml")
private val directVideoProbeClient = OkHttpClient.Builder()
    .followRedirects(true)
    .callTimeout(8, TimeUnit.SECONDS)
    .build()
