package app.xan.music.video

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.xan.music.R
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
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import androidx.work.Data

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ExternalVideoDownloadDependencies {
    fun database(): MusicDatabase
    fun downloadUtil(): app.xan.music.playback.DownloadUtil
}

/** Queue a video+audio stream pair and mux it into a playable file in Downloads. */
fun enqueueMergedExternalVideoDownload(
    context: Context,
    result: com.music.innertube.ExternalVideoSearchResult,
    info: com.music.innertube.ExternalVideoInfo,
    format: com.music.innertube.ExternalVideoFormat,
) {
    val audioUrl = format.audioUrl ?: throw IllegalArgumentException("An audio stream is not available for this quality.")
    val extension = format.extension.lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
        ?: throw IllegalArgumentException("This source did not report a supported video container.")
    val title = info.title.ifBlank { result.title }.safeDownloadTitle()
    val uploader = info.uploader.ifBlank { result.uploader }
    val request = OneTimeWorkRequestBuilder<ExternalVideoDownloadWorker>()
        .addTag(EXTERNAL_VIDEO_DOWNLOAD_TAG)
        .addTag("created:${System.currentTimeMillis()}")
        .addTag("media:${externalVideoMediaId(result, format)}")
        .addTag("title:$title")
        .addTag("resolution:${format.resolution}")
        .setInputData(workDataOf(
            ExternalVideoDownloadWorker.VIDEO_URL to format.url,
            ExternalVideoDownloadWorker.AUDIO_URL to audioUrl,
            ExternalVideoDownloadWorker.REFERER to result.url,
            ExternalVideoDownloadWorker.TITLE to title,
            ExternalVideoDownloadWorker.UPLOADER to uploader,
            ExternalVideoDownloadWorker.DESCRIPTION to result.description.orEmpty().take(2000),
            ExternalVideoDownloadWorker.EXTENSION to extension,
            ExternalVideoDownloadWorker.MIME_TYPE to (format.mimeType ?: "video/$extension"),
            ExternalVideoDownloadWorker.AUDIO_EXTENSION to (format.audioExtension ?: "m4a"),
            ExternalVideoDownloadWorker.MEDIA_ID to externalVideoMediaId(result, format),
            ExternalVideoDownloadWorker.SOURCE to result.serviceName,
            ExternalVideoDownloadWorker.DURATION to info.durationSeconds.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt().let { it ?: -1 },
            ExternalVideoDownloadWorker.THUMBNAIL to (info.thumbnailUrl ?: result.thumbnailUrl).orEmpty(),
            ExternalVideoDownloadWorker.RESOLUTION to format.resolution,
            ExternalVideoDownloadWorker.VIDEO_CODEC to format.codec.orEmpty(),
            ExternalVideoDownloadWorker.AUDIO_CODEC to format.audioCodec.orEmpty(),
            ExternalVideoDownloadWorker.VIDEO_HEIGHT to format.height,
            ExternalVideoDownloadWorker.FORMAT_ID to format.id,
            ExternalVideoDownloadWorker.SERVICE_ID to result.serviceId,
            ExternalVideoDownloadWorker.PAGE_ID to result.id,
        ))
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
    private val notificationId = 32000 + (id.hashCode() and 0xffff)
    @Volatile private var bytesDownloaded = 0L
    @Volatile private var bytesTotal = -1L
    @Volatile private var bytesPerSecond = 0L
    @Volatile private var stage = "download"
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
        val videoUrl = inputData.getString(VIDEO_URL) ?: return@withContext Result.failure()
        val audioUrl = inputData.getString(AUDIO_URL) ?: return@withContext Result.failure()
        val referer = inputData.getString(REFERER).orEmpty()
        val title = inputData.getString(TITLE).orEmpty().ifBlank { "XAN video" }
        val uploader = inputData.getString(UPLOADER).orEmpty()
        val description = inputData.getString(DESCRIPTION).orEmpty()
        val extension = inputData.getString(EXTENSION).orEmpty().ifBlank { "mp4" }
        val mimeType = inputData.getString(MIME_TYPE).orEmpty().ifBlank { "video/$extension" }
        val mediaId = inputData.getString(MEDIA_ID) ?: return@withContext Result.failure()
        val source = inputData.getString(SOURCE).orEmpty()
        val duration = inputData.getInt(DURATION, -1)
        val thumbnail = inputData.getString(THUMBNAIL).orEmpty().ifBlank { null }
        val resolution = inputData.getString(RESOLUTION).orEmpty()
        val formatId = inputData.getString(FORMAT_ID).orEmpty()
        val tempDirectory = File(applicationContext.cacheDir, "external-video-$id")
        var retainPartialFiles = false
        var publishedUri: android.net.Uri? = null

        try {
            setForeground(getForegroundInfo())
            check(tempDirectory.mkdirs() || tempDirectory.isDirectory) { "Could not prepare temporary storage." }
            val videoFile = File(tempDirectory, "video.${extension}")
            val audioExtension = inputData.getString(AUDIO_EXTENSION).orEmpty()
                .lowercase()
                .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
                ?: "audio"
            val audioFile = File(tempDirectory, "audio.$audioExtension")
            val outputFile = File(tempDirectory, "finished.$extension")
            var currentVideoUrl = videoUrl
            var currentAudioUrl = audioUrl
            try {
                download(currentVideoUrl, referer, videoFile, 0)
                download(currentAudioUrl, referer, audioFile, 1)
            } catch (expired: VideoHttpStatusException) {
                if (expired.code !in setOf(401, 403, 410)) throw expired
                val refreshed = refreshStreams(formatId)
                currentVideoUrl = refreshed.first
                currentAudioUrl = refreshed.second
                videoFile.delete()
                audioFile.delete()
                bytesDownloaded = 0L
                bytesTotal = -1L
                streamBytes.fill(0L)
                streamSizes.fill(-1L)
                download(currentVideoUrl, referer, videoFile, 0)
                download(currentAudioUrl, referer, audioFile, 1)
            }
            if (tempDirectory.usableSpace < videoFile.length() + audioFile.length()) {
                throw IOException("There is not enough storage to combine the video and audio.")
            }
            stage = "mux"
            reportProgress(force = true)
            mux(videoFile, audioFile, outputFile, title, uploader, description, extension)
            check(outputFile.isFile && outputFile.length() > 0) { "The downloaded video file is empty." }
            val verified = verifyMuxedVideo(outputFile, duration, extension)
            stage = "publish"
            reportProgress(force = true)
            val uri = publish(outputFile, title, uploader, description, mimeType, extension)
            publishedUri = uri
            registerDownloadedVideo(
                uri.toString(), mediaId, title, uploader, duration, thumbnail, source, resolution,
                mimeType, extension, verified.streams.first { it.type == "video" }.codec,
                verified.streams.first { it.type == "audio" }.codec, outputFile.length(),
            )
            publishedUri = null
            Result.success(workDataOf("download_uri" to uri.toString(), "file_size" to outputFile.length()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is IOException && runAttemptCount < 4 &&
                (error !is VideoHttpStatusException || error.retryable)) {
                retainPartialFiles = true
                Result.retry()
            } else Result.failure(Data.Builder().putAll(inputData)
                .putString("error", error.message ?: "Video download failed.").build())
        } finally {
            withContext(NonCancellable) {
                publishedUri?.let { uri ->
                    if (uri.scheme == "file") uri.path?.let { File(it).delete() }
                    else runCatching { applicationContext.contentResolver.delete(uri, null, null) }
                }
                if (!retainPartialFiles) tempDirectory.deleteRecursively()
            }
        }
    }

    private suspend fun download(url: String, referer: String, destination: File, stream: Int) {
        transferVideoStream(externalVideoHttpClient, url, buildMap {
            put("User-Agent", YouTubeClient.USER_AGENT_WEB)
            if (referer.isNotBlank()) put("Referer", referer)
        }, destination) { downloaded, total ->
            streamBytes[stream] = downloaded
            streamSizes[stream] = total
            bytesDownloaded = streamBytes.sum()
            bytesTotal = combinedVideoDownloadSize(streamSizes[0], streamSizes[1])
            reportProgress()
        }
        reportProgress(force = true)
    }

    private suspend fun reportProgress(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastProgressUpdate < 750) return
        if (lastProgressUpdate > 0) {
            bytesPerSecond = (bytesDownloaded - lastProgressBytes).coerceAtLeast(0L) * 1000L /
                (now - lastProgressUpdate).coerceAtLeast(1L)
        }
        lastProgressUpdate = now
        lastProgressBytes = bytesDownloaded
        setProgress(workDataOf("bytes_downloaded" to bytesDownloaded, "bytes_total" to bytesTotal,
            "bytes_per_second" to bytesPerSecond, "stage" to stage))
        setForeground(getForegroundInfo())
    }

    private fun refreshStreams(formatId: String): Pair<String, String> {
        val sourceId = inputData.getInt(SERVICE_ID, -1)
        val pageId = inputData.getString(PAGE_ID).orEmpty()
        val referer = inputData.getString(REFERER).orEmpty()
        if (sourceId < 0 || pageId.isBlank() || referer.isBlank()) {
            throw IOException("The video link expired and could not be refreshed.")
        }
        val result = ExternalVideoSearchResult(
            serviceId = sourceId,
            serviceName = inputData.getString(SOURCE).orEmpty(),
            id = pageId,
            url = referer,
            title = inputData.getString(TITLE).orEmpty(),
            uploader = inputData.getString(UPLOADER).orEmpty(),
            durationSeconds = inputData.getInt(DURATION, -1).toLong(),
            thumbnailUrl = inputData.getString(THUMBNAIL),
        )
        val info = NewPipeExtractor.getVideoInfo(result).getOrThrow()
        val wantedHeight = inputData.getInt(VIDEO_HEIGHT, -1)
        val refreshed = findCompatibleRefreshedVideoFormat(info.formats, formatId, wantedHeight,
            inputData.getString(EXTENSION).orEmpty(), inputData.getString(AUDIO_EXTENSION),
            inputData.getString(VIDEO_CODEC), inputData.getString(AUDIO_CODEC))
            ?: throw IOException("The selected video quality is no longer available.")
        return refreshed.url to (refreshed.audioUrl ?: throw IOException("The refreshed audio stream is unavailable."))
    }

    private fun verifyMuxedVideo(file: File, expectedDuration: Int, extension: String): com.arthenica.ffmpegkit.MediaInformation {
        val session = FFprobeKit.getMediaInformation(file.absolutePath)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            throw IOException("The downloaded video container could not be verified.")
        }
        val tracks = session.mediaInformation?.streams.orEmpty()
        if (tracks.none { it.type == "video" } || tracks.none { it.type == "audio" }) {
            throw IOException("The completed file does not contain both video and audio tracks.")
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
            externalVideoServiceId = inputData.getInt(SERVICE_ID, -1),
            externalVideoPageId = inputData.getString(PAGE_ID),
            externalVideoFormatId = inputData.getString(FORMAT_ID),
            externalVideoPageUrl = inputData.getString(REFERER),
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
        val title = inputData.getString(TITLE).orEmpty()
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

    private suspend fun publish(
        source: File,
        title: String,
        uploader: String,
        description: String,
        mimeType: String,
        extension: String,
    ): android.net.Uri {
        val name = "XAN - ${title.safeDownloadTitle()}.$extension"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // App-specific external storage works on Android 8 and 9 without a
            // storage permission, and remains playable from XAN's Downloads list.
            val downloadsDirectory = applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(applicationContext.filesDir, Environment.DIRECTORY_DOWNLOADS)
            val xanDirectory = File(downloadsDirectory, "XAN")
            if (!xanDirectory.mkdirs() && !xanDirectory.isDirectory) {
                throw IOException("Could not prepare the Downloads folder.")
            }
            val destination = File(xanDirectory, name)
            try {
                destination.outputStream().use { output -> source.inputStream().use { it.copyTo(output) } }
                return android.net.Uri.fromFile(destination)
            } catch (error: Exception) {
                destination.delete()
                throw error
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.TITLE, title)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.AUTHOR, uploader)
                put(MediaStore.Video.VideoColumns.DESCRIPTION, description)
            }
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/XAN")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = applicationContext.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create a file in Downloads.")
        try {
            val destination = resolver.openOutputStream(uri, "w")
                ?: throw IOException("Could not open the new Downloads file.")
            destination.use { output -> source.inputStream().use { it.copyTo(output) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    companion object {
        const val VIDEO_URL = "video_url"
        const val AUDIO_URL = "audio_url"
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
        private val externalVideoHttpClient = OkHttpClient.Builder()
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
