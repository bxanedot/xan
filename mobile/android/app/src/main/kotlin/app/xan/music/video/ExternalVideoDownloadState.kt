package app.xan.music.video

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.xan.music.db.MusicDatabase
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

const val EXTERNAL_VIDEO_DOWNLOAD_TAG = "external-video-download"

data class ExternalVideoDownloadState(
    val workId: UUID,
    val mediaId: String,
    val title: String,
    val resolution: String,
    val state: WorkInfo.State,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    val bytesPerSecond: Long,
    val stage: String,
    val error: String?,
    val retryData: Data,
) {
    val active: Boolean get() = !state.isFinished
    val percent: Int? get() = downloadPercent(bytesDownloaded, bytesTotal, state == WorkInfo.State.SUCCEEDED)
}

/** 100% means publication and validation succeeded, even after all input bytes arrive. */
internal fun downloadPercent(downloaded: Long, total: Long, completed: Boolean): Int? =
    when {
        completed -> 100
        total <= 0 -> null
        else -> (100.0 * downloaded.coerceAtLeast(0) / total).toInt().coerceIn(0, 99)
    }

fun observeExternalVideoDownloads(context: Context) =
    WorkManager.getInstance(context.applicationContext)
        .getWorkInfosByTagFlow(EXTERNAL_VIDEO_DOWNLOAD_TAG)
        .map { work ->
            work.sortedByDescending { info ->
                info.tags.firstOrNull { it.startsWith("created:") }?.removePrefix("created:")?.toLongOrNull() ?: 0L
            }.mapNotNull { info ->
                fun tag(prefix: String) = info.tags.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
                val mediaId = tag("media:") ?: return@mapNotNull null
                ExternalVideoDownloadState(
                    workId = info.id,
                    mediaId = mediaId,
                    title = tag("title:").orEmpty(),
                    resolution = tag("resolution:").orEmpty(),
                    state = info.state,
                    bytesDownloaded = info.progress.getLong("bytes_downloaded", 0L),
                    bytesTotal = info.progress.getLong("bytes_total", -1L),
                    bytesPerSecond = info.progress.getLong("bytes_per_second", 0L),
                    stage = info.progress.getString("stage").orEmpty(),
                    error = info.outputData.getString("error"),
                    retryData = info.outputData,
                )
            }.distinctBy { it.mediaId }
        }

fun retryExternalVideoDownload(context: Context, download: ExternalVideoDownloadState) {
    val persistedRequest = download.retryData.getString(ExternalVideoDownloadWorker.REQUEST_FILE)
    require(persistedRequest?.let { File(it).isFile } == true ||
        download.retryData.getString(ExternalVideoDownloadWorker.VIDEO_URL) != null) {
        "Open this video and download it again to refresh its source."
    }
    val request = OneTimeWorkRequestBuilder<ExternalVideoDownloadWorker>()
        .setInputData(download.retryData)
        .addTag(EXTERNAL_VIDEO_DOWNLOAD_TAG)
        .addTag("media:${download.mediaId}")
        .addTag("title:${download.title}")
        .addTag("resolution:${download.resolution}")
        .addTag("created:${System.currentTimeMillis()}")
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
        "external-video:${download.mediaId}", ExistingWorkPolicy.KEEP, request,
    )
}

/** Check app-owned saved videos when opening Downloads, including after a restart. */
suspend fun reconcileDownloadedVideoFiles(context: Context, database: MusicDatabase) = withContext(Dispatchers.IO) {
    database.downloadedSongsByNameAsc().first()
        .filter { it.song.isLocal && it.song.isVideo }
        .forEach { song ->
            val value = song.song.externalVideoStreamUrl ?: return@forEach
            val uri = Uri.parse(value)
            val exists = when (uri.scheme) {
                "file" -> uri.path?.let { File(it).isFile && File(it).length() > 0 } == true
                "content" -> try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length != 0L } == true
                } catch (_: FileNotFoundException) {
                    false
                } catch (_: SecurityException) {
                    // Permission loss isn't evidence that a file was deleted.
                    return@forEach
                }
                else -> return@forEach
            }
            if (!exists) database.updateDownloadedInfo(song.id, false, null)
        }
}
