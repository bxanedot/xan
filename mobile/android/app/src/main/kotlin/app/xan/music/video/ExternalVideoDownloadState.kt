package app.xan.music.video

import android.content.Context
import android.content.ContentUris
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.room.withTransaction
import app.xan.music.db.MusicDatabase
import app.xan.music.db.entities.ArtistEntity
import app.xan.music.db.entities.SongArtistMap
import app.xan.music.db.entities.SongEntity
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

const val EXTERNAL_VIDEO_DOWNLOAD_TAG = "external-video-download"

internal fun restoredExternalVideoMediaId(displayName: String): String? {
    val token = Regex("\\[xan-id-([A-Za-z0-9_-]+)]").find(displayName)?.groupValues?.getOrNull(1)
        ?: return null
    return runCatching {
        String(java.util.Base64.getUrlDecoder().decode(token), Charsets.UTF_8)
    }.getOrNull()?.takeIf { it.startsWith(EXTERNAL_VIDEO_MEDIA_ID_PREFIX) }
}

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
    val paused: Boolean,
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
                val retryData = info.outputData.takeIf { it.getString(ExternalVideoDownloadWorker.REQUEST_FILE) != null }
                    ?: tag("request:")?.let { path ->
                        Data.Builder().putString(ExternalVideoDownloadWorker.REQUEST_FILE, path).build()
                    }
                    ?: info.outputData
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
                    retryData = retryData,
                    paused = info.state == WorkInfo.State.CANCELLED && videoPauseMarker(context, mediaId).isFile,
                )
            }.distinctBy { it.mediaId }
        }

fun retryExternalVideoDownload(context: Context, download: ExternalVideoDownloadState) {
    videoPauseMarker(context, download.mediaId).delete()
    enqueueExternalVideoDownload(context, download, requirePersistedRequest = false)
}

fun pauseExternalVideoDownload(context: Context, download: ExternalVideoDownloadState) {
    val marker = videoPauseMarker(context, download.mediaId)
    check(marker.parentFile?.mkdirs() != false || marker.parentFile?.isDirectory == true) {
        "Could not save the paused download state."
    }
    marker.writeText(download.mediaId)
    WorkManager.getInstance(context.applicationContext).cancelWorkById(download.workId)
}

fun resumeExternalVideoDownload(context: Context, download: ExternalVideoDownloadState) {
    enqueueExternalVideoDownload(context, download, requirePersistedRequest = true)
}

fun cancelExternalVideoDownload(context: Context, download: ExternalVideoDownloadState) {
    videoPauseMarker(context, download.mediaId).delete()
    WorkManager.getInstance(context.applicationContext).cancelWorkById(download.workId)
}

private fun enqueueExternalVideoDownload(
    context: Context,
    download: ExternalVideoDownloadState,
    requirePersistedRequest: Boolean,
) {
    val persistedRequest = download.retryData.getString(ExternalVideoDownloadWorker.REQUEST_FILE)
    require(persistedRequest?.let { File(it).isFile } == true ||
        (!requirePersistedRequest && download.retryData.getString(ExternalVideoDownloadWorker.VIDEO_URL) != null)) {
        "Open this video and download it again to refresh its source."
    }
    val request = OneTimeWorkRequestBuilder<ExternalVideoDownloadWorker>()
        .setInputData(download.retryData)
        .addTag(EXTERNAL_VIDEO_DOWNLOAD_TAG)
        .addTag("media:${download.mediaId}")
        .addTag("title:${download.title}")
        .addTag("resolution:${download.resolution}")
        .addTag("request:${persistedRequest.orEmpty()}")
        .addTag("created:${System.currentTimeMillis()}")
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
        "external-video:${download.mediaId}", ExistingWorkPolicy.KEEP, request,
    )
}

internal fun videoPauseMarker(context: Context, mediaId: String): File {
    val name = MessageDigest.getInstance("SHA-256").digest(mediaId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return File(File(context.applicationContext.filesDir, "video-download-pauses"), "$name.paused")
}

internal fun externalVideoTempDirectory(context: Context, mediaId: String): File {
    val name = MessageDigest.getInstance("SHA-256").digest(mediaId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return File(context.applicationContext.cacheDir, "external-video-$name")
}

/** Reconcile saved video records and restore XAN's public Movies/XAN files after reinstall. */
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

    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return@withContext

    val isScopedStorage = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val projection = buildList {
        add(MediaStore.Video.Media._ID)
        add(MediaStore.Video.Media.TITLE)
        add(MediaStore.Video.Media.DISPLAY_NAME)
        add(MediaStore.Video.Media.DURATION)
        add(MediaStore.MediaColumns.SIZE)
        add(MediaStore.MediaColumns.MIME_TYPE)
        add(MediaStore.MediaColumns.DATE_ADDED)
        if (isScopedStorage) {
            add(MediaStore.MediaColumns.RELATIVE_PATH)
            add(MediaStore.MediaColumns.AUTHOR)
        } else {
            add(MediaStore.Video.Media.DATA)
        }
    }.toTypedArray()
    val collections = if (isScopedStorage) {
        MediaStore.getExternalVolumeNames(context).map { volume -> MediaStore.Video.Media.getContentUri(volume) }
    } else {
        listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
    }
    for (collection in collections) {
        val selection = if (isScopedStorage) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        } else {
            "${MediaStore.Video.Media.DATA} LIKE ?"
        }
        val selectionArgs = if (isScopedStorage) arrayOf("${android.os.Environment.DIRECTORY_MOVIES}/XAN/")
        else arrayOf("%/${android.os.Environment.DIRECTORY_MOVIES}/XAN/%")
        try {
            context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val authorIndex = if (isScopedStorage) cursor.getColumnIndex(MediaStore.MediaColumns.AUTHOR) else -1

                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(nameIndex).orEmpty()
                    val mediaId = restoredExternalVideoMediaId(displayName) ?: continue
                    val byteCount = cursor.getLong(sizeIndex)
                    if (byteCount <= 0) continue
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idIndex))
                    val title = cursor.getString(titleIndex)?.trim().takeUnless { it.isNullOrBlank() }
                        ?: displayName.removePrefix("XAN - ").substringBeforeLast(" [xan-id-").substringBeforeLast('.')
                    val artist = if (authorIndex >= 0) cursor.getString(authorIndex)?.trim()?.takeIf(String::isNotBlank) else null
                    val mime = cursor.getString(mimeIndex)?.takeIf(String::isNotBlank)
                    val extension = displayName.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
                    val durationSeconds = (cursor.getLong(durationIndex) / 1000L).toInt().takeIf { it > 0 }
                    val addedAt = cursor.getLong(dateIndex).takeIf { it > 0 }
                        ?.let { runCatching { LocalDateTime.ofInstant(Instant.ofEpochSecond(it), ZoneId.systemDefault()) }.getOrNull() }
                    val existing = database.getSongByIdBlocking(mediaId)

                    database.withTransaction {
                        val current = existing?.song
                        upsert(
                            (current ?: SongEntity(id = mediaId, title = title)).copy(
                                title = current?.title?.takeIf(String::isNotBlank) ?: title,
                                duration = current?.duration?.takeIf { it > 0 } ?: durationSeconds ?: -1,
                                thumbnailUrl = current?.thumbnailUrl?.takeIf(String::isNotBlank)
                                    ?: "android.resource://${context.packageName}/${app.xan.music.R.drawable.xan_default_album_art}",
                                dateDownload = current?.dateDownload ?: addedAt ?: LocalDateTime.now(),
                                isLocal = true,
                                isDownloaded = true,
                                isVideo = true,
                                persistentMediaUri = uri.toString(),
                                externalVideoSource = current?.externalVideoSource ?: "Local",
                                externalVideoServiceId = current?.externalVideoServiceId ?: DIRECT_FILE_SERVICE_ID,
                                externalVideoStreamUrl = uri.toString(),
                                externalVideoMimeType = mime ?: current?.externalVideoMimeType,
                                externalVideoExtension = extension ?: current?.externalVideoExtension,
                            ),
                        )
                        if (existing?.artists.isNullOrEmpty() && !artist.isNullOrBlank()) {
                            val artistId = artistByName(artist)?.id ?: ArtistEntity.generateArtistId().also { id ->
                                insert(ArtistEntity(id = id, name = artist))
                            }
                            insert(SongArtistMap(songId = mediaId, artistId = artistId, position = 0))
                        }
                    }
                }
            }
        } catch (_: SecurityException) {
            // Media access can be revoked while a scan is in progress.
            return@withContext
        }
    }
}
