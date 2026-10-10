package app.xan.music.video

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import app.xan.music.playback.DownloadTarget
import app.xan.music.playback.buildDownloadRequest
import com.music.innertube.ExternalVideoFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A stable description used when a freshly loaded manifest has new group objects. */
internal data class ActiveVideoTrack(
    val id: String?,
    val width: Int,
    val height: Int,
    val mimeType: String?,
    val codecs: String?,
    val bitrate: Int,
)

internal fun Format.activeVideoTrack() = ActiveVideoTrack(id, width, height, sampleMimeType, codecs, bitrate)

internal fun matchingActiveVideoTrack(active: ActiveVideoTrack, candidates: List<ActiveVideoTrack>): Int? {
    if (active.height <= 0) return null
    val matchingQuality = candidates.withIndex().filter { (_, candidate) ->
        candidate.height == active.height &&
            (active.width <= 0 || candidate.width <= 0 || candidate.width == active.width) &&
            (active.mimeType == null || candidate.mimeType == active.mimeType) &&
            (active.codecs == null || candidate.codecs == null || candidate.codecs == active.codecs)
    }
    return matchingQuality.firstOrNull { (_, candidate) -> active.id != null && candidate.id == active.id }?.index
        ?: matchingQuality.minByOrNull { (_, candidate) ->
            kotlin.math.abs(candidate.bitrate.toLong() - active.bitrate.toLong())
        }?.index
}

/** Resolve stream keys for the quality actually rendering, including its audio. */
suspend fun buildAdaptiveVideoDownloadRequest(
    context: Context,
    target: DownloadTarget,
    manifest: ExternalVideoFormat,
    activeVideo: Format?,
    activeAudio: Format?,
): DownloadRequest = withContext(Dispatchers.Main.immediate) {
    require(activeVideo != null && activeVideo.height > 0) {
        "Wait for the video to start before downloading Auto quality."
    }
    val sourceFactory = OkHttpDataSource.Factory(com.music.innertube.externalVideoHttpClientBuilder().build())
        .setUserAgent(EXTERNAL_VIDEO_USER_AGENT)
        .setDefaultRequestProperties(buildMap {
            target.externalVideoPageUrl?.takeIf(String::isNotBlank)?.let { put("Referer", it) }
        })
    val helper = DownloadHelper.forMediaItem(
        context,
        MediaItem.Builder().setUri(manifest.url).setMimeType(manifest.mimeType).build(),
        sourceFactory,
    )
    try {
        withTimeout(30_000L) {
            suspendCancellableCoroutine<Unit> { continuation ->
                helper.prepare(object : DownloadHelper.Callback {
                    override fun onPrepared(helper: DownloadHelper, liveContent: Boolean) {
                        if (!continuation.isActive) return
                        if (liveContent) continuation.resumeWithException(IOException("Live streams cannot be saved as a complete offline video."))
                        else continuation.resume(Unit)
                    }

                    override fun onPrepareError(helper: DownloadHelper, error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                })
            }
        }
        check(helper.periodCount > 0) { "This manifest has no downloadable video tracks." }
        for (period in 0 until helper.periodCount) {
            val groups = helper.getTrackGroups(period)
            val parameters = TrackSelectionParameters.Builder(context)
                .clearViewportSizeConstraints()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .setForceHighestSupportedBitrate(true)
            var foundVideo = false
            for (groupIndex in 0 until groups.length) {
                val group = groups[groupIndex]
                if (group.type == C.TRACK_TYPE_VIDEO && !foundVideo) {
                    val match = matchingActiveVideoTrack(
                        activeVideo.activeVideoTrack(),
                        (0 until group.length).map { group.getFormat(it).activeVideoTrack() },
                    )
                    if (match != null) {
                        parameters.setOverrideForType(TrackSelectionOverride(group, match))
                        foundVideo = true
                    }
                }
                if (group.type == C.TRACK_TYPE_AUDIO && activeAudio != null) {
                    val audioIndex = (0 until group.length).firstOrNull { track ->
                        val candidate = group.getFormat(track)
                        candidate.id == activeAudio.id && candidate.sampleMimeType == activeAudio.sampleMimeType
                    }
                    if (audioIndex != null) parameters.setOverrideForType(TrackSelectionOverride(group, audioIndex))
                }
            }
            check(foundVideo) { "The playing video quality is no longer available to download. Select another quality and try again." }
            helper.replaceTrackSelections(period, parameters.build())
        }
        val metadata = target.copy(externalVideoResolution = "${activeVideo.height}p")
        val data = buildDownloadRequest(metadata, manifest.url.toUri(), manifest.mimeType, false).data
        helper.getDownloadRequest(target.id, data).also {
            check(it.streamKeys.isNotEmpty()) { "The source did not expose the selected video tracks for downloading." }
        }
    } finally {
        helper.release()
    }
}
