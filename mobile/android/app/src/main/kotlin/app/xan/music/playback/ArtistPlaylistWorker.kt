package app.xan.music.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.xan.music.R
import app.xan.music.db.MusicDatabase
import app.xan.music.db.entities.PlaylistEntity
import app.xan.music.db.entities.PlaylistSongMap
import app.xan.music.models.toMediaMetadata
import app.xan.music.constants.*
import app.xan.music.utils.dataStore
import app.xan.music.utils.get
import com.music.innertube.YouTube
import com.music.innertube.models.filterExplicit
import com.music.innertube.models.filterVideoSongs
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.util.UUID

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ArtistPlaylistDependencies { fun database(): MusicDatabase }

fun enqueueArtistPlaylist(context: Context, name: String, artists: List<String>): UUID {
    val playlistId = PlaylistEntity.generatePlaylistId()
    val request = OneTimeWorkRequestBuilder<ArtistPlaylistWorker>()
        .setInputData(workDataOf("playlist_id" to playlistId))
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
    context.getSharedPreferences("artist_playlist_jobs", Context.MODE_PRIVATE).edit()
        .putString(playlistId, JSONObject().put("name", name).put("artists", JSONArray(artists.distinct())).toString())
        .putString("latest_job", request.id.toString()).commit()
    WorkManager.getInstance(context).enqueueUniqueWork("artist-playlist:$playlistId", ExistingWorkPolicy.KEEP, request)
    return request.id
}

class ArtistPlaylistWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("artist_playlists",
            applicationContext.getString(R.string.artist_playlist), NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "artist_playlists")
            .setSmallIcon(R.drawable.playlist_add).setContentTitle(applicationContext.getString(R.string.artist_playlist))
            .setContentText(applicationContext.getString(R.string.artist_playlist_creating)).setOngoing(true)
            .addAction(R.drawable.xan_icon_close, applicationContext.getString(android.R.string.cancel),
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return ForegroundInfo(400000 + (id.hashCode() and 0xffff), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val playlistId = inputData.getString("playlist_id") ?: return@withContext Result.failure()
        try {
            setForeground(getForegroundInfo())
            val database = EntryPointAccessors.fromApplication(applicationContext, ArtistPlaylistDependencies::class.java).database()
            val existing = database.playlist(playlistId).first()
            if (existing != null) return@withContext Result.success(workDataOf("playlist_id" to playlistId, "songs" to existing.songCount))
            val descriptor = JSONObject(applicationContext.getSharedPreferences("artist_playlist_jobs", Context.MODE_PRIVATE)
                .getString(playlistId, null) ?: return@withContext Result.failure())
            val artists = descriptor.getJSONArray("artists").let { array -> (0 until array.length()).map { array.getString(it) } }
            val hideExplicit = applicationContext.dataStore.get(HideExplicitKey, false)
            val hideVideos = applicationContext.dataStore.get(HideVideoSongsKey, false) || applicationContext.dataStore.get(DataSaverEnabledKey, false)
            var done = 0
            val discovered = mutableSetOf<String>()
            val songs = collectArtistPlaylistSongs(artists) { artistId ->
                val page = YouTube.artist(artistId).getOrThrow()
                resolveArtistDownloadSongs(page, artistId, { YouTube.artistItems(it).getOrThrow() },
                    { YouTube.artistItemsContinuation(it).getOrThrow() }, { YouTube.album(it).getOrThrow() },
                    onSongsDiscovered = { batch ->
                        ensureActive()
                        discovered.addAll(batch.filterExplicit(hideExplicit).filterVideoSongs(hideVideos).map { it.id })
                        setProgress(workDataOf("artists" to done, "total" to artists.size, "songs" to discovered.size))
                    }).filterExplicit(hideExplicit).filterVideoSongs(hideVideos).also { done++ }
            }
            ensureActive()
            if (songs.isEmpty()) return@withContext Result.failure(workDataOf("empty" to true))
            database.withTransaction {
                insert(PlaylistEntity(id = playlistId, name = descriptor.getString("name"),
                    bookmarkedAt = LocalDateTime.now(), thumbnailUrl = songs.first().thumbnail))
                songs.forEachIndexed { index, song ->
                    insert(song.toMediaMetadata())
                    insert(PlaylistSongMap(playlistId = playlistId, songId = song.id, position = index))
                }
            }
            Result.success(workDataOf("playlist_id" to playlistId, "songs" to songs.size))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            app.xan.music.utils.reportException(error)
            if (error is java.io.IOException && runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
