package app.xan.music.video

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.media.MediaScannerConnection
import android.webkit.MimeTypeMap
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.xan.music.R
import app.xan.music.constants.AutomaticDownloadRetryKey
import app.xan.music.constants.ResumeInterruptedDownloadsKey
import app.xan.music.constants.PreserveDownloadsAfterUninstallKey
import app.xan.music.db.MusicDatabase
import app.xan.music.db.entities.FormatEntity
import app.xan.music.models.MediaMetadata
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.music.innertube.NewPipeExtractor
import com.music.innertube.ExternalVideoSearchResult
import com.music.innertube.models.YouTubeClient
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import androidx.work.Data
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import app.xan.music.playback.DownloadUtil
import app.xan.music.playback.DownloadRecoveryPolicy
import app.xan.music.utils.dataStore
import app.xan.music.utils.get

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ExternalVideoDownloadDependencies {
    fun database(): MusicDatabase
    fun downloadUtil(): app.xan.music.playback.DownloadUtil
}

private class AdaptiveVideoDownloadException(returnCode: ReturnCode?) :
    IOException("Could not download this adaptive video stream (${returnCode ?: "unknown"}).")

/** Queue a video stream, optionally pair it with audio, and publish it in Movies/XAN. */
fun enqueueMergedExternalVideoDownload(
    context: Context,
    result: com.music.innertube.ExternalVideoSearchResult,
    info: com.music.innertube.ExternalVideoInfo,
    format: com.music.innertube.ExternalVideoFormat,
) {
    val adaptive = format.adaptiveManifest
    val extension = (if (adaptive) "mp4" else format.extension.lowercase()).takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
        ?: throw IllegalArgumentException("This source did not report a supported video container.")
    val mimeType = if (adaptive) "video/mp4" else format.mimeType
        ?.takeIf { it.startsWith("video/", ignoreCase = true) }
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.takeIf { it.startsWith("video/", ignoreCase = true) }
        ?: "video/mp4"
    val title = info.title.ifBlank { result.title }.safeDownloadTitle()
    val uploader = info.uploader.ifBlank { result.uploader }
    val mediaId = externalVideoMediaId(result, format)
    val requestDirectory = File(context.applicationContext.filesDir, "video-download-requests").apply { mkdirs() }
    val requestName = MessageDigest.getInstance("SHA-256").digest(mediaId.toByteArray())
        .joinToString("") { "%02x".format(it) }
    val requestFile = File(requestDirectory, "$requestName.json")
    // Signed URLs and descriptions can exceed WorkManager's 10 KiB Data limit.
    // Keep the full request in private app storage and queue only its small reference.
    val parameters = JSONObject(mapOf(
        ExternalVideoDownloadWorker.VIDEO_URL to format.url,
        ExternalVideoDownloadWorker.AUDIO_URL to format.audioUrl.orEmpty(),
        ExternalVideoDownloadWorker.ADAPTIVE_MANIFEST to adaptive,
        ExternalVideoDownloadWorker.REFERER to result.url,
        ExternalVideoDownloadWorker.TITLE to title,
        ExternalVideoDownloadWorker.UPLOADER to uploader,
        ExternalVideoDownloadWorker.DESCRIPTION to result.description.orEmpty().take(2000),
        ExternalVideoDownloadWorker.EXTENSION to extension,
        ExternalVideoDownloadWorker.MIME_TYPE to mimeType,
        ExternalVideoDownloadWorker.AUDIO_EXTENSION to (format.audioExtension ?: "m4a"),
        ExternalVideoDownloadWorker.MEDIA_ID to mediaId,
        ExternalVideoDownloadWorker.SOURCE to result.serviceName,
        ExternalVideoDownloadWorker.DURATION to (info.durationSeconds.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() ?: -1),
        ExternalVideoDownloadWorker.THUMBNAIL to (info.thumbnailUrl ?: result.thumbnailUrl).orEmpty(),
        ExternalVideoDownloadWorker.RESOLUTION to format.resolution,
        ExternalVideoDownloadWorker.VIDEO_CODEC to format.codec.orEmpty(),
        ExternalVideoDownloadWorker.AUDIO_CODEC to format.audioCodec.orEmpty(),
        ExternalVideoDownloadWorker.VIDEO_HEIGHT to format.height,
        ExternalVideoDownloadWorker.FORMAT_ID to format.id,
        ExternalVideoDownloadWorker.SERVICE_ID to result.serviceId,
        ExternalVideoDownloadWorker.PAGE_ID to result.id,
    ))
    val pendingRequest = File.createTempFile(requestName, ".tmp", requestDirectory)
    try {
        pendingRequest.writeText(parameters.toString())
        Files.move(pendingRequest.toPath(), requestFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } finally { pendingRequest.delete() }
    val request = OneTimeWorkRequestBuilder<ExternalVideoDownloadWorker>()
        .addTag(EXTERNAL_VIDEO_DOWNLOAD_TAG)
        .addTag("created:${System.currentTimeMillis()}")
        .addTag("media:${externalVideoMediaId(result, format)}")
        .addTag("title:$title")
        .addTag("resolution:${format.resolution}")
        .setInputData(workDataOf(ExternalVideoDownloadWorker.REQUEST_FILE to requestFile.absolutePath))
        .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
        .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
        "external-video:${externalVideoMediaId(result, format)}",
        ExistingWorkPolicy.KEEP,
        request,
    )
}

class ExternalVideoDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val downloadUtil: DownloadUtil by lazy {
        EntryPointAccessors.fromApplication(applicationContext, ExternalVideoDownloadDependencies::class.java)
            .downloadUtil()
    }
    private val requestFile: File? by lazy {
        inputData.getString(REQUEST_FILE)?.let { path ->
            val file = File(path).canonicalFile
            require(file.parentFile == File(applicationContext.filesDir, "video-download-requests").canonicalFile) {
                "Invalid video download request."
            }
            file
        }
    }
    private val resumeDownloadsEnabled by lazy {
        applicationContext.dataStore.get(ResumeInterruptedDownloadsKey, true)
    }
    private val parameters: JSONObject by lazy {
        requestFile?.let { JSONObject(it.readText()) } ?: JSONObject(inputData.keyValueMap)
    }
    private fun requestString(key: String): String? = parameters.optString(key).takeUnless { it.isBlank() || it == "null" }
    private fun requestInt(key: String, default: Int): Int = parameters.optInt(key, default)
    private val notificationId = 32000 + (id.hashCode() and 0xffff)
    @Volatile private var bytesDownloaded = 0L
    @Volatile private var bytesTotal = -1L
    @Volatile private var bytesPerSecond = 0L
    @Volatile private var stage = "download"
    private val progressLock = Any()
    private val streamBytes = longArrayOf(0L, 0L)
    private val streamSizes = longArrayOf(-1L, -1L)
    private var lastProgressUpdate = 0L
    private var lastProgressBytes = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, applicationContext.getString(R.string.downloading), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.xan_icon_download)
            .setContentTitle(applicationContext.getString(R.string.downloading))
            .setContentText(downloadProgressText())
            .setProgress(100, if (bytesTotal > 0) ((bytesDownloaded * 100) / bytesTotal).toInt().coerceIn(0, 99) else 0, bytesTotal <= 0 || stage != "download")
            .setOngoing(true)
            .addAction(
                R.drawable.xan_icon_close,
                applicationContext.getString(android.R.string.cancel),
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            )
            .build()
        return ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            parameters // Validate the persisted request before starting a foreground transfer.
        } catch (error: Exception) {
            return@withContext Result.failure(workDataOf("error" to "Could not open the saved video download request: ${error.message}"))
        }
        val videoUrl = requestString(VIDEO_URL) ?: return@withContext Result.failure()
        val audioUrl = requestString(AUDIO_URL)
        val adaptiveManifest = parameters.optBoolean(ADAPTIVE_MANIFEST, false)
        val referer = requestString(REFERER).orEmpty()
        val title = requestString(TITLE).orEmpty().ifBlank { "XAN video" }
        val uploader = requestString(UPLOADER).orEmpty()
        val description = requestString(DESCRIPTION).orEmpty()
        val extension = requestString(EXTENSION).orEmpty().ifBlank { "mp4" }
        val mimeType = requestString(MIME_TYPE).orEmpty().ifBlank { "video/$extension" }
        val mediaId = requestString(MEDIA_ID) ?: return@withContext Result.failure()
        val source = requestString(SOURCE).orEmpty()
        val duration = requestInt(DURATION, -1)
        val thumbnail = requestString(THUMBNAIL).orEmpty().ifBlank { null }
        val resolution = requestString(RESOLUTION).orEmpty()
        val formatId = requestString(FORMAT_ID).orEmpty()
        val tempDirectory = File(applicationContext.cacheDir, "external-video-$id")
        var retainPartialFiles = false
        var removeRequest = false
        var publishedUri: android.net.Uri? = null
        var externalVideoSlotAcquired = false
        var externalVideoSlotCount = 0
        val automaticRetryEnabled = applicationContext.dataStore.get(AutomaticDownloadRetryKey, true)

        try {
            setForeground(getForegroundInfo())
            check(tempDirectory.mkdirs() || tempDirectory.isDirectory) { "Could not prepare temporary storage." }
            val videoFile = File(tempDirectory, "video.$extension")
            val audioExtension = requestString(AUDIO_EXTENSION).orEmpty()
                .lowercase()
                .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
                ?: "audio"
            val audioFile = File(tempDirectory, "audio.$audioExtension")
            val outputFile = File(tempDirectory, "finished.$extension")
            stage = "download"
            reportProgress(force = true)
            externalVideoSlotCount = downloadUtil.acquireExternalVideoSlot(
                requestedSlots = if (!adaptiveManifest && audioUrl != null) 2 else 1,
            )
            externalVideoSlotAcquired = true
            if (adaptiveManifest) {
                try {
                    remuxAdaptiveManifest(videoUrl, referer, outputFile, title, uploader, description)
                } catch (expired: AdaptiveVideoDownloadException) {
                    // Adaptive manifests often contain short-lived signed URLs too.
                    // Resolve the same video/format again before asking WorkManager
                    // to retry the stale URL; the final failure remains visible.
                    val refreshedManifest = refreshStreams(formatId, requireAudio = false).first
                    outputFile.delete()
                    remuxAdaptiveManifest(refreshedManifest, referer, outputFile, title, uploader, description)
                }
                downloadUtil.releaseExternalVideoSlot(externalVideoSlotCount)
                externalVideoSlotAcquired = false
            } else {
                var currentVideoUrl = videoUrl
                var currentAudioUrl = audioUrl
                try {
                    downloadTracksTogether(
                        currentVideoUrl,
                        currentAudioUrl,
                        referer,
                        videoFile,
                        audioFile,
                    )
                } catch (expired: VideoHttpStatusException) {
                    if (expired.code !in setOf(401, 403, 410)) throw expired
                    val refreshed = refreshStreams(formatId, requireAudio = currentAudioUrl != null)
                    currentVideoUrl = refreshed.first
                    if (currentAudioUrl != null) currentAudioUrl = refreshed.second
                    videoFile.delete()
                    audioFile.delete()
                    bytesDownloaded = 0L
                    bytesTotal = -1L
                    streamBytes.fill(0L)
                    streamSizes.fill(-1L)
                    downloadTracksTogether(
                        currentVideoUrl,
                        currentAudioUrl,
                        referer,
                        videoFile,
                        audioFile,
                    )
                }
                downloadUtil.releaseExternalVideoSlot(externalVideoSlotCount)
                externalVideoSlotAcquired = false
                val requiredBytes = videoFile.length() + if (currentAudioUrl != null) audioFile.length() else 0L
                if (tempDirectory.usableSpace < requiredBytes) {
                    throw IOException("There is not enough storage to finish this video.")
                }
                if (currentAudioUrl != null) {
                    stage = "mux"
                    reportProgress(force = true)
                    mux(videoFile, audioFile, outputFile, title, uploader, description, extension)
                } else {
                    videoFile.copyTo(outputFile, overwrite = true)
                }
            }
            check(outputFile.isFile && outputFile.length() > 0) { "The downloaded video file is empty." }
            val verified = verifyMuxedVideo(outputFile, duration, extension)
            stage = "publish"
            reportProgress(force = true)
            val uri = publish(outputFile, mediaId, title, uploader, description, mimeType, extension)
            publishedUri = uri
            registerDownloadedVideo(
                uri.toString(), mediaId, title, uploader, duration, thumbnail, source, resolution,
                mimeType, extension, verified.streams.first { it.type == "video" }.codec,
                verified.streams.firstOrNull { it.type == "audio" }?.codec, outputFile.length(),
            )
            publishedUri = null
            removeRequest = true
            Result.success(workDataOf("download_uri" to uri.toString(), "file_size" to outputFile.length()))
        } catch (cancelled: CancellationException) {
            removeRequest = true
            throw cancelled
        } catch (error: LinkageError) {
            Result.failure(Data.Builder().putAll(inputData)
                .putString("error", "This video format is not supported on this device.").build())
        } catch (error: Exception) {
            if (automaticRetryEnabled && error is IOException && runAttemptCount < DownloadRecoveryPolicy.MAX_RETRIES &&
                (error !is VideoHttpStatusException || error.retryable)) {
                retainPartialFiles = true
                Result.retry()
            } else Result.failure(Data.Builder().putAll(inputData)
                .putString("error", error.message ?: "Video download failed.").build())
        } finally {
            withContext(NonCancellable) {
                if (externalVideoSlotAcquired) downloadUtil.releaseExternalVideoSlot(externalVideoSlotCount)
                downloadUtil.finishExternalDownloadSpeed(id.toString())
                publishedUri?.let { uri ->
                    if (uri.scheme == "file") uri.path?.let { File(it).delete() }
                    else runCatching { applicationContext.contentResolver.delete(uri, null, null) }
                }
                if (!retainPartialFiles) {
                    tempDirectory.deleteRecursively()
                }
                if (removeRequest) requestFile?.delete()
            }
        }
    }

    /** Video-only sources expose separate video and audio URLs; transfer both at once. */
    private suspend fun downloadTracksTogether(
        videoUrl: String,
        audioUrl: String?,
        referer: String,
        videoFile: File,
        audioFile: File,
    ) = coroutineScope {
        val videoDownload = async(Dispatchers.IO) {
            download(videoUrl, referer, videoFile, 0)
        }
        val audioDownload = audioUrl?.let { url ->
            async(Dispatchers.IO) {
                download(url, referer, audioFile, 1)
            }
        }
        videoDownload.await()
        audioDownload?.await()
    }

    private suspend fun download(url: String, referer: String, destination: File, stream: Int) {
        transferVideoStream(
            client = externalVideoHttpClient,
            url = url,
            headers = buildMap {
                put("User-Agent", YouTubeClient.USER_AGENT_WEB)
                if (referer.isNotBlank()) put("Referer", referer)
            },
            destination = destination,
            onProgress = { downloaded, total ->
                synchronized(progressLock) {
                    streamBytes[stream] = downloaded
                    streamSizes[stream] = total
                    bytesDownloaded = streamBytes.sum()
                    bytesTotal = if (requestString(AUDIO_URL) == null) streamSizes[0]
                        else combinedVideoDownloadSize(streamSizes[0], streamSizes[1])
                }
                reportProgress()
            },
            onBytesTransferred = { count -> downloadUtil.limitDownloadBytes(count) },
            beforeRead = { downloadUtil.waitForBackgroundDownloadPermission() },
            allowResume = resumeDownloadsEnabled,
        )
        reportProgress(force = true)
    }

    private suspend fun reportProgress(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val progress = synchronized(progressLock) {
            if (!force && now - lastProgressUpdate < 750) return
            if (lastProgressUpdate > 0) {
                bytesPerSecond = (bytesDownloaded - lastProgressBytes).coerceAtLeast(0L) * 1000L /
                    (now - lastProgressUpdate).coerceAtLeast(1L)
            }
            lastProgressUpdate = now
            lastProgressBytes = bytesDownloaded
            ProgressSnapshot(bytesDownloaded, bytesTotal, bytesPerSecond, stage)
        }
        downloadUtil.recordExternalDownloadSpeed(id.toString(),
            if (progress.stage == "download") progress.bytesPerSecond else 0L)
        setProgress(workDataOf("bytes_downloaded" to progress.bytesDownloaded, "bytes_total" to progress.bytesTotal,
            "bytes_per_second" to progress.bytesPerSecond, "stage" to progress.stage))
        setForeground(getForegroundInfo())
    }

    private data class ProgressSnapshot(
        val bytesDownloaded: Long,
        val bytesTotal: Long,
        val bytesPerSecond: Long,
        val stage: String,
    )

    private fun refreshStreams(formatId: String, requireAudio: Boolean): Pair<String, String?> {
        val sourceId = requestInt(SERVICE_ID, -1)
        val pageId = requestString(PAGE_ID).orEmpty()
        val referer = requestString(REFERER).orEmpty()
        if (sourceId < 0 || pageId.isBlank() || referer.isBlank()) {
            throw IOException("The video link expired and could not be refreshed.")
        }
        val result = ExternalVideoSearchResult(
            serviceId = sourceId,
            serviceName = requestString(SOURCE).orEmpty(),
            id = pageId,
            url = referer,
            title = requestString(TITLE).orEmpty(),
            uploader = requestString(UPLOADER).orEmpty(),
            durationSeconds = requestInt(DURATION, -1).toLong(),
            thumbnailUrl = requestString(THUMBNAIL),
        )
        val info = NewPipeExtractor.getVideoInfo(result).getOrThrow()
        val wantedHeight = requestInt(VIDEO_HEIGHT, -1)
        val refreshed = findCompatibleRefreshedVideoFormat(info.formats, formatId, wantedHeight,
            requestString(EXTENSION).orEmpty(), requestString(AUDIO_EXTENSION),
            requestString(VIDEO_CODEC), requestString(AUDIO_CODEC), requireAudio)
            ?: throw IOException("The selected video quality is no longer available.")
        return refreshed.url to if (requireAudio) {
            refreshed.audioUrl ?: throw IOException("The refreshed audio stream is unavailable.")
        } else null
    }

    private fun verifyMuxedVideo(file: File, expectedDuration: Int, extension: String): com.arthenica.ffmpegkit.MediaInformation {
        val session = FFprobeKit.getMediaInformation(file.absolutePath)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            throw IOException("The downloaded video container could not be verified.")
        }
        val tracks = session.mediaInformation?.streams.orEmpty()
        if (tracks.none { it.type == "video" }) {
            throw IOException("The completed file does not contain a video track.")
        }
        val duration = session.mediaInformation?.duration?.toDoubleOrNull()
        if (duration == null || duration <= 0 ||
            (expectedDuration > 0 && duration + 3 < expectedDuration * 0.98)) {
            throw IOException("The downloaded video is incomplete.")
        }
        val information = session.mediaInformation ?: throw IOException("The downloaded video has no media information.")
        if (!videoContainerMatches(extension, information.format.orEmpty())) {
            throw IOException("The downloaded video does not match the selected file format.")
        }
        return information
    }

    private suspend fun registerDownloadedVideo(
        uri: String,
        mediaId: String,
        title: String,
        uploader: String,
        duration: Int,
        thumbnail: String?,
        source: String,
        resolution: String,
        mimeType: String,
        extension: String,
        videoCodec: String?,
        audioCodec: String?,
        fileSize: Long,
    ) {
        val database = EntryPointAccessors.fromApplication(
            applicationContext,
            ExternalVideoDownloadDependencies::class.java,
        ).database()
        val metadata = MediaMetadata(
            id = mediaId,
            title = title,
            artists = listOfNotNull(uploader.takeIf(String::isNotBlank)?.let { MediaMetadata.Artist(null, it) }),
            duration = duration,
            thumbnailUrl = thumbnail,
            musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
            isExternalVideo = true,
            externalVideoSource = source,
            externalVideoServiceId = requestInt(SERVICE_ID, -1),
            externalVideoPageId = requestString(PAGE_ID),
            externalVideoFormatId = requestString(FORMAT_ID),
            externalVideoPageUrl = requestString(REFERER),
            externalVideoStreamUrl = uri,
            externalVideoMimeType = mimeType,
            externalVideoCodec = videoCodec,
            externalVideoAudioCodec = audioCodec,
            externalVideoExtension = extension,
            externalVideoResolution = resolution,
        )
        database.withTransaction {
            val previous = getSongByIdBlocking(mediaId)
            if (previous == null) {
                insert(metadata) { song ->
                    song.copy(isDownloaded = true, dateDownload = LocalDateTime.now(), isLocal = true, isVideo = true)
                }
            } else {
                update(previous, metadata)
                update(metadata.toSongEntity().copy(
                    liked = previous.song.liked, likedDate = previous.song.likedDate,
                    inLibrary = previous.song.inLibrary, isDownloaded = true,
                    dateDownload = LocalDateTime.now(), isLocal = true, isVideo = true,
                ))
            }
            updateDownloadedInfo(mediaId, true, LocalDateTime.now())
            upsert(FormatEntity(
                id = mediaId, itag = -1, mimeType = mimeType,
                codecs = listOfNotNull(videoCodec, audioCodec).filter(String::isNotBlank).joinToString(", "),
                bitrate = if (duration > 0) (fileSize * 8 / duration).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0,
                sampleRate = null, contentLength = fileSize, loudnessDb = null, playbackUrl = null,
            ))
        }
    }

    private fun downloadProgressText(): String {
        val title = requestString(TITLE).orEmpty()
        val percent = when (stage) {
            "mux" -> "Combining audio and video"
            "publish" -> "Saving video"
            else -> if (bytesTotal > 0) "${(bytesDownloaded * 100 / bytesTotal).toInt().coerceIn(0, 99)}%" else "…"
        }
        val speed = when {
            bytesPerSecond >= 1024 * 1024 -> "${bytesPerSecond / (1024 * 1024)} MB/s"
            bytesPerSecond >= 1024 -> "${bytesPerSecond / 1024} KB/s"
            else -> "${bytesPerSecond} B/s"
        }
        val transferred = Formatter.formatShortFileSize(applicationContext, bytesDownloaded)
        val size = if (bytesTotal > 0) {
            "$transferred / ${Formatter.formatShortFileSize(applicationContext, bytesTotal)}"
        } else {
            transferred
        }
        return "$title · $percent · $size · $speed"
    }

    private suspend fun mux(
        video: File,
        audio: File,
        output: File,
        title: String,
        uploader: String,
        description: String,
        extension: String,
    ) {
        val arguments = buildList {
            addAll(listOf("-y", "-i", video.absolutePath, "-i", audio.absolutePath,
                "-map", "0:v:0", "-map", "1:a:0", "-c", "copy"))
            if (extension.equals("mp4", ignoreCase = true) || extension.equals("m4v", ignoreCase = true)) {
                addAll(listOf("-movflags", "+faststart"))
            }
            addAll(listOf("-metadata", "title=$title"))
            if (uploader.isNotBlank()) addAll(listOf("-metadata", "artist=$uploader"))
            if (description.isNotBlank()) addAll(listOf("-metadata", "comment=$description"))
            add(output.absolutePath)
        }
        val finished = CompletableDeferred<Unit>()
        val session = try {
            FFmpegKit.executeWithArgumentsAsync(arguments.toTypedArray()) { result ->
                if (ReturnCode.isSuccess(result.returnCode)) finished.complete(Unit)
                else finished.completeExceptionally(IOException("Could not combine the video and audio tracks (${result.returnCode})."))
            }
        } catch (error: LinkageError) {
            throw IOException("Video combining is unavailable on this device.", error)
        }
        try {
            finished.await()
        } catch (cancelled: CancellationException) {
            FFmpegKit.cancel(session.sessionId)
            withContext(NonCancellable) { runCatching { finished.await() } }
            throw cancelled
        }
    }

    private suspend fun remuxAdaptiveManifest(
        manifestUrl: String,
        referer: String,
        output: File,
        title: String,
        uploader: String,
        description: String,
    ) {
        stage = "download"
        bytesTotal = -1L
        val headers = buildString {
            append("User-Agent: ").append(YouTubeClient.USER_AGENT_WEB).append("\r\n")
            if (referer.isNotBlank()) append("Referer: ").append(referer).append("\r\n")
        }
        val arguments = buildList {
            addAll(listOf("-y", "-headers", headers, "-i", manifestUrl,
                "-map", "0:v:0", "-map", "0:a:0?", "-c", "copy", "-movflags", "+faststart"))
            addAll(listOf("-metadata", "title=$title"))
            if (uploader.isNotBlank()) addAll(listOf("-metadata", "artist=$uploader"))
            if (description.isNotBlank()) addAll(listOf("-metadata", "comment=$description"))
            add(output.absolutePath)
        }
        val finished = CompletableDeferred<Unit>()
        val session = try {
            FFmpegKit.executeWithArgumentsAsync(arguments.toTypedArray()) { result ->
                if (ReturnCode.isSuccess(result.returnCode)) finished.complete(Unit)
                else finished.completeExceptionally(AdaptiveVideoDownloadException(result.returnCode))
            }
        } catch (error: LinkageError) {
            throw IOException("Adaptive video downloads are unavailable on this device.", error)
        }
        try {
            finished.await()
        } catch (cancelled: CancellationException) {
            FFmpegKit.cancel(session.sessionId)
            withContext(NonCancellable) { runCatching { finished.await() } }
            throw cancelled
        }
    }

    private suspend fun publish(
        source: File,
        mediaId: String,
        title: String,
        uploader: String,
        description: String,
        mimeType: String,
        extension: String,
    ): android.net.Uri {
        val mediaToken = android.util.Base64.encodeToString(
            mediaId.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
        val name = "XAN - ${title.safeDownloadTitle()} [xan-id-$mediaToken].$extension"
        val expectedHash = sha256(source)
        val keepAfterUninstall = applicationContext.dataStore.get(PreserveDownloadsAfterUninstallKey, true)
        if (!keepAfterUninstall) {
            val folder = File(
                applicationContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: applicationContext.filesDir,
                "XAN",
            )
            if (!folder.mkdirs() && !folder.isDirectory) throw IOException("Could not prepare app-specific video storage.")
            val destination = File(folder, name)
            if (destination.isFile && destination.length() == source.length() && sha256(destination) == expectedHash) {
                return android.net.Uri.fromFile(destination)
            }
            if (destination.exists() && !destination.delete()) throw IOException("Could not replace an incomplete XAN video copy.")
            val temporary = File(folder, ".${destination.name}.partial")
            try {
                copyAtomically(source, temporary, destination, expectedHash)
                return android.net.Uri.fromFile(destination)
            } catch (error: Exception) {
                temporary.delete()
                throw error
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                throw IOException("Allow storage access to save videos in the public Movies folder.")
            }
            val moviesDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val xanDirectory = File(moviesDirectory, "XAN")
            if (!xanDirectory.mkdirs() && !xanDirectory.isDirectory) {
                throw IOException("Could not prepare the Downloads folder.")
            }
            val destination = File(xanDirectory, name)
            if (destination.isFile && destination.length() == source.length() && sha256(destination) == expectedHash) {
                return android.net.Uri.fromFile(destination)
            }
            if (destination.exists() && !destination.delete()) throw IOException("Could not replace an incomplete XAN video copy.")
            val temporary = File(xanDirectory, ".${destination.name}.partial")
            try {
                copyAtomically(source, temporary, destination, expectedHash)
                MediaScannerConnection.scanFile(applicationContext, arrayOf(destination.absolutePath), arrayOf(mimeType), null)
                return android.net.Uri.fromFile(destination)
            } catch (error: Exception) {
                temporary.delete()
                destination.delete()
                throw error
            }
        }
        findExistingVideoCopy(name, source.length(), expectedHash)?.let { return it }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.TITLE, title)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.AUTHOR, uploader)
                put(MediaStore.Video.VideoColumns.DESCRIPTION, description)
            }
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/XAN")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = applicationContext.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create a file in Movies.")
        try {
            val descriptor = resolver.openFileDescriptor(uri, "w")
                ?: throw IOException("Could not open the new Movies file.")
            descriptor.use { parcel ->
                java.io.FileOutputStream(parcel.fileDescriptor).use { output ->
                    source.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
                    output.fd.sync()
                }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) <= 0) throw IOException("Could not finalize the video in Movies/XAN.")
            if (sha256(resolver.openInputStream(uri) ?: throw IOException("Could not verify the saved video.")) != expectedHash) {
                throw IOException("The saved video did not pass its read-back integrity check.")
            }
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun findExistingVideoCopy(name: String, expectedBytes: Long, expectedHash: String): android.net.Uri? {
        val resolver = applicationContext.contentResolver
        resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.SIZE),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(name, "${Environment.DIRECTORY_MOVIES}/XAN/"),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE))
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                if (size == expectedBytes && runCatching {
                        resolver.openInputStream(uri)?.use(::sha256) == expectedHash
                    }.getOrDefault(false)
                ) return uri
                resolver.delete(uri, null, null)
            }
        }
        return null
    }

    private fun copyAtomically(source: File, temporary: File, destination: File, expectedHash: String) {
        java.io.FileOutputStream(temporary).use { output ->
            source.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
            output.fd.sync()
        }
        if (temporary.length() != source.length() || sha256(temporary) != expectedHash) {
            throw IOException("The saved video did not pass its file integrity check.")
        }
        if (!temporary.renameTo(destination)) throw IOException("Could not atomically finalize the saved video.")
    }

    private fun sha256(file: File): String = file.inputStream().use(::sha256)

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        input.use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val REQUEST_FILE = "request_file"
        const val VIDEO_URL = "video_url"
        const val AUDIO_URL = "audio_url"
        const val ADAPTIVE_MANIFEST = "adaptive_manifest"
        const val AUDIO_EXTENSION = "audio_extension"
        const val REFERER = "referer"
        const val TITLE = "title"
        const val UPLOADER = "uploader"
        const val DESCRIPTION = "description"
        const val EXTENSION = "extension"
        const val MIME_TYPE = "mime_type"
        const val MEDIA_ID = "media_id"
        const val SOURCE = "source"
        const val DURATION = "duration"
        const val THUMBNAIL = "thumbnail"
        const val RESOLUTION = "resolution"
        const val VIDEO_CODEC = "video_codec"
        const val AUDIO_CODEC = "audio_codec"
        const val VIDEO_HEIGHT = "video_height"
        const val FORMAT_ID = "format_id"
        const val SERVICE_ID = "service_id"
        const val PAGE_ID = "page_id"
        private const val CHANNEL = "external_video_downloads"
        private val externalVideoHttpClient = com.music.innertube.externalVideoHttpClientBuilder()
            .followRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}

private fun String.safeDownloadTitle(): String = replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(120)
    .ifBlank { "XAN video" }
