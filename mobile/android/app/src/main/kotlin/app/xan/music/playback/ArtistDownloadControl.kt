package app.xan.music.playback

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.exoplayer.offline.DownloadService
import androidx.work.WorkManager

internal val artistDownloadLock = Any()

fun artistDownloadKnownIds(preferences: SharedPreferences, artistId: String): List<String> =
    (preferences.getStringSet(artistId, emptySet()).orEmpty() +
        preferences.getStringSet("$artistId:pending", emptySet()).orEmpty()).toList()

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun stopArtistDownload(context: Context, artistId: String, util: DownloadUtil) {
    synchronized(artistDownloadLock) {
        val preferences = context.getSharedPreferences("artist_download_catalogues", Context.MODE_PRIVATE)
        preferences.edit().putBoolean("$artistId:stopped", true).commit()
        WorkManager.getInstance(context).cancelUniqueWork(artistDownloadWorkName(artistId))
        artistDownloadIdsToPause(artistDownloadKnownIds(preferences, artistId),
            util.downloads.value.mapValues { it.value.state }).forEach {
            DownloadService.sendSetStopReason(context, ExoDownloadService::class.java, it, 1, true)
        }
    }
}
