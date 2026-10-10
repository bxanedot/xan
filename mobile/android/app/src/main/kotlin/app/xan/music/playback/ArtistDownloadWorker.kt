package app.xan.music.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.xan.music.R
import app.xan.music.constants.DataSaverEnabledKey
import app.xan.music.constants.HideExplicitKey
import app.xan.music.constants.HideVideoSongsKey
import app.xan.music.models.toMediaMetadata
import app.xan.music.utils.dataStore
import app.xan.music.utils.get
import com.music.innertube.YouTube
import com.music.innertube.models.filterExplicit
import com.music.innertube.models.filterVideoSongs
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.io.IOException

@EntryPoint
@InstallIn(SingletonComponent::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
interface ArtistDownloadDependencies {
    fun downloadUtil(): DownloadUtil
}

fun artistDownloadWorkName(artistId: String) = "artist-download:$artistId"

fun enqueueArtistDownload(context: Context, artistId: String) {
    val request = OneTimeWorkRequestBuilder<ArtistDownloadWorker>()
        .setInputData(workDataOf("artist_id" to artistId))
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()
    synchronized(artistDownloadLock) {
        context.getSharedPreferences("artist_download_catalogues", Context.MODE_PRIVATE).edit()
            .putBoolean("$artistId:stopped", false).putString("$artistId:token", request.id.toString()).commit()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            artistDownloadWorkName(artistId), ExistingWorkPolicy.REPLACE, request,
        )
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ArtistDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val notificationId = 200000 + (id.hashCode() and 0xffff)
    private fun notification(text: String) = NotificationCompat.Builder(applicationContext, CHANNEL)
        .setSmallIcon(R.drawable.xan_icon_download)
        .setContentTitle(applicationContext.getString(R.string.downloading))
        .setContentText(text)
        .setOngoing(true)
        .addAction(R.drawable.xan_icon_close, applicationContext.getString(android.R.string.cancel),
            WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
        .build()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            applicationContext.getString(R.string.downloading), NotificationManager.IMPORTANCE_LOW))
        return ForegroundInfo(notificationId, notification(applicationContext.getString(R.string.artist_download_resolving)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val artistId = inputData.getString("artist_id") ?: return@withContext Result.failure()
        val preferences = applicationContext.getSharedPreferences("artist_download_catalogues", Context.MODE_PRIVATE)
        fun checkRunning() {
            if (preferences.getBoolean("$artistId:stopped", false) ||
                preferences.getString("$artistId:token", null) != id.toString()) throw CancellationException()
        }
        try {
            checkRunning()
            setForeground(getForegroundInfo())
            val util = EntryPointAccessors.fromApplication(applicationContext,
                ArtistDownloadDependencies::class.java).downloadUtil()
            val hideExplicit = applicationContext.dataStore.get(HideExplicitKey, false)
            val hideVideos = applicationContext.dataStore.get(HideVideoSongsKey, false) ||
                applicationContext.dataStore.get(DataSaverEnabledKey, false)
            val page = YouTube.artist(artistId).getOrThrow()
            val songs = resolveArtistDownloadSongs(page, artistId,
                { YouTube.artistItems(it).getOrThrow() },
                { YouTube.artistItemsContinuation(it).getOrThrow() },
                { YouTube.album(it).getOrThrow() },
                onSongsDiscovered = { batch ->
                    val targets = batch.filterExplicit(hideExplicit).filterVideoSongs(hideVideos)
                        .map { it.toMediaMetadata().toDownloadTarget() }
                    coroutineContext.ensureActive()
                    synchronized(artistDownloadLock) {
                        checkRunning()
                        val pending = preferences.getStringSet("$artistId:pending", emptySet()).orEmpty() + targets.map { it.id }
                        preferences.edit().putStringSet("$artistId:pending", pending).commit()
                        downloadSongs(applicationContext, targets, util.downloads.value)
                    }
                })
                .filterExplicit(hideExplicit).filterVideoSongs(hideVideos)
            synchronized(artistDownloadLock) {
                checkRunning()
                preferences.edit().putStringSet(artistId, songs.map { it.id }.toSet())
                    .remove("$artistId:pending").commit()
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            app.xan.music.utils.reportException(error)
            if (error is IOException && runAttemptCount < 3) Result.retry()
            else {
                val manager = applicationContext.getSystemService(NotificationManager::class.java)
                manager.notify(notificationId + 65536, NotificationCompat.Builder(applicationContext, CHANNEL)
                    .setSmallIcon(R.drawable.error).setContentTitle(applicationContext.getString(R.string.download_failed))
                    .setContentText(applicationContext.getString(R.string.artist_download_error)).build())
                Result.failure()
            }
        }
    }

    companion object {
        private const val CHANNEL = "artist_download_preparation"
    }
}
