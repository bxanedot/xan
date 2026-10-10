package app.xan.music.video

import com.music.innertube.ExternalVideoFormat

internal enum class ExternalVideoDownloadRoute {
    ADAPTIVE_MANIFEST,
    PROGRESSIVE_FILE,
    SPLIT_TRACKS,
}

/** Keep manifest downloads on Media3 even when the extractor also describes video-only tracks. */
internal fun externalVideoDownloadRoute(format: ExternalVideoFormat): ExternalVideoDownloadRoute = when {
    format.adaptiveManifest -> ExternalVideoDownloadRoute.ADAPTIVE_MANIFEST
    format.videoOnly -> ExternalVideoDownloadRoute.SPLIT_TRACKS
    else -> ExternalVideoDownloadRoute.PROGRESSIVE_FILE
}

/** Refresh only an equivalent representation; changing containers requires a new download. */
internal fun findCompatibleRefreshedVideoFormat(
    formats: List<ExternalVideoFormat>,
    formatId: String,
    height: Int,
    extension: String,
    audioExtension: String?,
    videoCodec: String?,
    audioCodec: String?,
): ExternalVideoFormat? = formats.filter {
    it.videoOnly && !it.audioUrl.isNullOrBlank() && it.height == height &&
        it.extension.equals(extension, true) &&
        (audioExtension.isNullOrBlank() || it.audioExtension.equals(audioExtension, true)) &&
        (videoCodec.isNullOrBlank() || it.codec == videoCodec) &&
        (audioCodec.isNullOrBlank() || it.audioCodec == audioCodec)
}.let { candidates -> candidates.firstOrNull { it.id == formatId } ?: candidates.firstOrNull() }

internal fun videoContainerMatches(extension: String, probeFormat: String): Boolean {
    val detected = probeFormat.lowercase().split(',')
    return when (extension.lowercase()) {
        "mp4", "m4v", "mov" -> detected.any { it in setOf("mp4", "mov", "m4v") }
        "webm" -> "webm" in detected
        "mkv" -> "matroska" in detected
        "ts" -> "mpegts" in detected
        else -> extension.lowercase() in detected
    }
}
