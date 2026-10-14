package app.xan.music.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.xan.music.constants.AutomaticDownloadRetryKey
import app.xan.music.utils.dataStore
import app.xan.music.utils.get
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AudioDownloadRecoveryDependencies {
    fun downloadUtil(): DownloadUtil
}

/** Keep failed Media3 requests in its persistent index and re-submit eligible ones later. */
@UnstableApi
class AudioDownloadRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val mediaId = inputData.getString(KEY_MEDIA_ID) ?: return Result.failure()
        if (!applicationContext.dataStore.get(AutomaticDownloadRetryKey, true)) return Result.success()

        val util = EntryPointAccessors.fromApplication(
            applicationContext,
            AudioDownloadRecoveryDependencies::class.java,
        ).downloadUtil()
        val download = runCatching { util.downloadManager.downloadIndex.getDownload(mediaId) }
            .getOrNull() ?: run {
                clearRetryCount(applicationContext, mediaId)
                return Result.success()
            }
        if (download.state != Download.STATE_FAILED) return Result.success()

        val attempt = inputData.getInt(KEY_ATTEMPT, 0)
        if (attempt > DownloadRecoveryPolicy.MAX_RETRIES) return Result.success()
        return runCatching {
            DownloadService.sendAddDownload(
                applicationContext,
                ExoDownloadService::class.java,
                download.request,
                false,
            )
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }

    companion object {
        private const val KEY_MEDIA_ID = "media_id"
        private const val KEY_ATTEMPT = "attempt"
        private const val RETRY_PREFS = "xan_audio_download_retries"
        private const val WORK_PREFIX = "xan-audio-recovery:"

        fun schedule(context: Context, download: Download, error: Throwable?) {
            val httpError = generateSequence(error) { it.cause }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                .firstOrNull()
            val httpStatus = httpError?.responseCode
            val retryPrefs = context.getSharedPreferences(RETRY_PREFS, Context.MODE_PRIVATE)
            val retriesSoFar = retryPrefs.getInt(download.request.id, 0)
            if (!DownloadRecoveryPolicy.shouldRetry(httpStatus, error?.message, retriesSoFar)) return

            val attempt = retriesSoFar + 1
            retryPrefs.edit().putInt(download.request.id, attempt).apply()
            val request = OneTimeWorkRequestBuilder<AudioDownloadRecoveryWorker>()
                .setInputData(workDataOf(KEY_MEDIA_ID to download.request.id, KEY_ATTEMPT to attempt))
                .setInitialDelay(
                    DownloadRecoveryPolicy.delayMillis(
                        retryCount = attempt,
                        retryAfterHeader = httpError?.headerFields
                            ?.entries
                            ?.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
                            ?.value
                            ?.firstOrNull(),
                    ),
                    TimeUnit.MILLISECONDS,
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "$WORK_PREFIX${download.request.id}",
                // A previous recovery job may still be RUNNING when the retried
                // download fails again. KEEP discards the new attempt in that
                // case, leaving the download permanently FAILED.
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun retryNow(context: Context, download: Download) {
            clearRetryCount(context, download.request.id)
            DownloadService.sendAddDownload(
                context.applicationContext,
                ExoDownloadService::class.java,
                download.request,
                false,
            )
        }

        fun clearRetryCount(context: Context, mediaId: String) {
            context.getSharedPreferences(RETRY_PREFS, Context.MODE_PRIVATE)
                .edit().remove(mediaId).apply()
        }
    }
}
