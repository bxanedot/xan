/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.playback

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import app.xan.music.db.entities.Song
import app.xan.music.models.MediaMetadata
import app.xan.music.models.toMediaMetadata
import org.json.JSONArray
import org.json.JSONObject

/**
 * Metadata carried with an offline download.
 *
 * Media3's cache only knows the media id and opaque request.data bytes. Keeping the
 * presentation metadata here makes downloaded tracks self-describing even when the
 * network is unavailable by the time the download finishes.
 */
data class DownloadTarget(
    val id: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val thumbnailUrl: String? = null,
    val albumId: String? = null,
    val albumTitle: String? = null,
    val duration: Int = -1,
    val externalVideoSource: String? = null,
    val externalVideoServiceId: Int? = null,
    val externalVideoPageId: String? = null,
    val externalVideoFormatId: String? = null,
    val externalVideoPageUrl: String? = null,
    val externalVideoStreamUrl: String? = null,
    val externalVideoMimeType: String? = null,
    val externalVideoCodec: String? = null,
    val externalVideoAudioCodec: String? = null,
    val externalVideoExtension: String? = null,
    val externalVideoResolution: String? = null,
    val externalVideoAdaptive: Boolean = false,
)

fun MediaMetadata.toDownloadTarget() =
    DownloadTarget(
        id = id,
        title = title,
        artists = artists.map { it.name }.filter { it.isNotBlank() },
        thumbnailUrl = thumbnailUrl,
        albumId = album?.id,
        albumTitle = album?.title,
        duration = duration,
        externalVideoSource = externalVideoSource,
        externalVideoServiceId = externalVideoServiceId,
        externalVideoPageId = externalVideoPageId,
        externalVideoFormatId = externalVideoFormatId,
        externalVideoPageUrl = externalVideoPageUrl,
        externalVideoStreamUrl = externalVideoStreamUrl,
        externalVideoMimeType = externalVideoMimeType,
        externalVideoCodec = externalVideoCodec,
        externalVideoAudioCodec = externalVideoAudioCodec,
        externalVideoExtension = externalVideoExtension,
        externalVideoResolution = externalVideoResolution,
        externalVideoAdaptive = externalVideoAdaptive,
    )

fun Song.toDownloadTarget() = toMediaMetadata().toDownloadTarget()

private fun DownloadTarget.toRequestData(): ByteArray =
    JSONObject()
        .put("v", 1)
        .put("id", id)
        .put("title", title)
        .put("artists", JSONArray(artists))
        .put("thumbnailUrl", thumbnailUrl ?: JSONObject.NULL)
        .put("albumId", albumId ?: JSONObject.NULL)
        .put("albumTitle", albumTitle ?: JSONObject.NULL)
        .put("duration", duration)
        .put("externalVideoSource", externalVideoSource ?: JSONObject.NULL)
        .put("externalVideoServiceId", externalVideoServiceId ?: JSONObject.NULL)
        .put("externalVideoPageId", externalVideoPageId ?: JSONObject.NULL)
        .put("externalVideoFormatId", externalVideoFormatId ?: JSONObject.NULL)
        .put("externalVideoPageUrl", externalVideoPageUrl ?: JSONObject.NULL)
        .put("externalVideoStreamUrl", externalVideoStreamUrl ?: JSONObject.NULL)
        .put("externalVideoMimeType", externalVideoMimeType ?: JSONObject.NULL)
        .put("externalVideoCodec", externalVideoCodec ?: JSONObject.NULL)
        .put("externalVideoAudioCodec", externalVideoAudioCodec ?: JSONObject.NULL)
        .put("externalVideoExtension", externalVideoExtension ?: JSONObject.NULL)
        .put("externalVideoResolution", externalVideoResolution ?: JSONObject.NULL)
        .put("externalVideoAdaptive", externalVideoAdaptive)
        .toString()
        .toByteArray(Charsets.UTF_8)

/**
 * Backwards compatible decoder: old XAN builds stored only a plain UTF-8 title.
 */
fun decodeDownloadTarget(id: String, data: ByteArray): DownloadTarget {
    val raw = data.toString(Charsets.UTF_8).trim()
    if (raw.isBlank()) return DownloadTarget(id = id, title = "Unknown")

    return runCatching {
        val json = JSONObject(raw)
        val artistsJson = json.optJSONArray("artists")
        val artists =
            buildList {
                if (artistsJson != null) {
                    for (index in 0 until artistsJson.length()) {
                        artistsJson.optString(index)
                            .trim()
                            .takeIf { it.isNotEmpty() }
                            ?.let(::add)
                    }
                }
            }

        DownloadTarget(
            id = json.optString("id").takeIf { it.isNotBlank() } ?: id,
            title = json.optString("title").takeIf { it.isNotBlank() } ?: "Unknown",
            artists = artists,
            thumbnailUrl =
                json.optString("thumbnailUrl")
                    .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) },
            albumId =
                json.optString("albumId")
                    .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) },
            albumTitle =
                json.optString("albumTitle")
                    .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) },
            duration = json.optInt("duration", -1),
            externalVideoSource = json.optString("externalVideoSource").takeUnless { it.isBlank() || it.equals("null", true) },
            externalVideoServiceId = json.optInt("externalVideoServiceId", Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE },
            externalVideoPageId = json.optString("externalVideoPageId").takeUnless { it.isBlank() || it.equals("null", true) },
            externalVideoFormatId = json.optString("externalVideoFormatId").takeUnless { it.isBlank() || it.equals("null", true) },
            externalVideoPageUrl = json.optString("externalVideoPageUrl").takeUnless { it.isBlank() || it.equals("null", true) },
            externalVideoStreamUrl = json.optString("externalVideoStreamUrl").takeUnless { it.isBlank() || it.equals("null", true) },
            externalVideoMimeType = json.optString("externalVideoMimeType").takeUnless { it.isBlank() || it.equals("null", true) },
            externalVideoCodec = json.optString("externalVideoCodec").takeUnless { it.isBlank() || it.equals("null", true) },
            externalVideoAudioCodec = json.optString("externalVideoAudioCodec").takeUnless { it.isBlank() || it.equals("null", true) },
            externalVideoExtension = json.optString("externalVideoExtension").takeUnless { it.isBlank() || it.equals("null", true) },
            externalVideoResolution = json.optString("externalVideoResolution").takeUnless { it.isBlank() || it.equals("null", true) },
            externalVideoAdaptive = json.optBoolean("externalVideoAdaptive", false),
        )
    }.getOrElse {
        DownloadTarget(id = id, title = raw)
    }
}

fun downloadDisplayTitle(download: Download): String =
    decodeDownloadTarget(download.request.id, download.request.data).title

@UnstableApi
fun buildDownloadRequest(target: DownloadTarget): DownloadRequest =
    buildDownloadRequest(target, target.id.toUri())

/** Build an offline request for a source URL while keeping the standard song path unchanged. */
@UnstableApi
fun buildDownloadRequest(
    target: DownloadTarget,
    uri: Uri,
    mimeType: String? = null,
    useCustomCacheKey: Boolean = true,
): DownloadRequest {
    val builder = DownloadRequest.Builder(target.id, uri)
        .setData(target.toRequestData())
    if (useCustomCacheKey) builder.setCustomCacheKey(target.id)
    mimeType?.takeIf(String::isNotBlank)?.let(builder::setMimeType)
    return builder.build()
}

fun shouldQueueDownload(state: Int?): Boolean = when (state) {
    null, Download.STATE_FAILED, Download.STATE_STOPPED -> true
    else -> false
}

fun shouldCancelDownload(state: Int?): Boolean = when (state) {
    Download.STATE_QUEUED,
    Download.STATE_DOWNLOADING,
    Download.STATE_FAILED,
    Download.STATE_STOPPED,
    Download.STATE_RESTARTING,
    -> true

    else -> false
}

/** Queue songs while preserving their full display metadata in the request. */
@UnstableApi
fun downloadSongs(
    context: Context,
    songs: List<DownloadTarget>,
    downloads: Map<String, Download>,
) {
    songs.distinctBy { it.id }.forEach { song ->
        if (!shouldQueueDownload(downloads[song.id]?.state)) return@forEach
        DownloadService.sendAddDownload(
            context,
            ExoDownloadService::class.java,
            buildDownloadRequest(song),
            true,
        )
    }
}

@UnstableApi
fun downloadSong(
    context: Context,
    target: DownloadTarget,
    downloads: Map<String, Download>,
) = downloadSongs(context, listOf(target), downloads)

/** Compatibility overload for call sites that only have id/title. */
@UnstableApi
fun downloadSong(
    context: Context,
    id: String,
    title: String,
    downloads: Map<String, Download>,
) = downloadSongs(context, listOf(DownloadTarget(id, title)), downloads)

@UnstableApi
fun cancelDownloads(
    context: Context,
    ids: List<String>,
    downloads: Map<String, Download>,
) {
    ids.forEach { id ->
        if (!shouldCancelDownload(downloads[id]?.state)) return@forEach
        DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, id, false)
    }
}

@UnstableApi
fun removeDownloads(
    context: Context,
    ids: List<String>,
) {
    ids.forEach { id ->
        DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, id, false)
    }
}
