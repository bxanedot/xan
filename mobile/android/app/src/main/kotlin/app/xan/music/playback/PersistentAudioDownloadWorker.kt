/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.playback

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.offline.Download
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.xan.music.constants.PreserveDownloadsAfterUninstallKey
import app.xan.music.db.entities.SongEntity
import app.xan.music.utils.dataStore
import app.xan.music.utils.get
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

private const val AUDIO_PUBLISH_WORK_PREFIX = "xan-audio-publish:"

fun enqueuePersistentAudioPublish(context: Context, mediaId: String) {
    val request = OneTimeWorkRequestBuilder<PersistentAudioDownloadWorker>()
        .setInputData(androidx.work.workDataOf("media_id" to mediaId))
        .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
        .build()
    WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
        "$AUDIO_PUBLISH_WORK_PREFIX$mediaId",
        ExistingWorkPolicy.KEEP,
        request,
    )
}

@OptIn(UnstableApi::class)
class PersistentAudioDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val mediaId = inputData.getString("media_id") ?: return@withContext Result.failure()
        val util = EntryPointAccessors.fromApplication(
            applicationContext,
            ArtistDownloadDependencies::class.java,
        ).downloadUtil()

        try {
            val download = util.downloadManager.downloadIndex.getDownload(mediaId)
                ?: return@withContext Result.success()
            if (download.state != Download.STATE_COMPLETED) return@withContext Result.retry()

            val format = util.database.format(mediaId).first()
            val metadata = decodeDownloadTarget(mediaId, download.request.data)
            val mimeType = format?.mimeType
                ?.takeIf { it.startsWith("audio/", ignoreCase = true) }
                ?: "audio/webm"
            val extension = extensionFor(mimeType)
            val key = download.request.customCacheKey ?: mediaId
            val expectedBytes = download.contentLength.takeIf { it > 0 }
                ?: ContentMetadata.getContentLength(util.downloadCache.getContentMetadata(key))
            if (expectedBytes > 0 && download.bytesDownloaded > 0 && download.bytesDownloaded != expectedBytes) {
                throw IOException("Completed audio size does not match the downloaded byte count.")
            }
            if (expectedBytes > 0 && !util.downloadCache.isCached(key, 0, expectedBytes)) {
                throw IOException("The completed audio file is missing cached bytes; retry the download to repair it.")
            }

            val keepAfterUninstall = applicationContext.dataStore.get(PreserveDownloadsAfterUninstallKey, true)
            val existingUri = if (keepAfterUninstall) findExistingPublicCopy(mediaId, expectedBytes) else null
            val uri = existingUri ?: if (keepAfterUninstall) {
                publishToMediaStore(
                    util.downloadCache, download, key, expectedBytes, mediaId, metadata.title,
                    metadata.artists.firstOrNull().orEmpty(), metadata.albumTitle.orEmpty(), mimeType, extension,
                )
            } else {
                publishToAppStorage(
                    util.downloadCache, download, key, expectedBytes, mediaId, metadata.title,
                    mimeType, extension,
                )
            }
            verifyPlayableAudio(uri)
            util.database.getSongById(mediaId)?.let { song ->
                util.database.update(
                    song.song.copy(
                        isDownloaded = true,
                        persistentMediaUri = uri.toString(),
                        dateDownload = song.song.dateDownload ?: LocalDateTime.now(),
                    ),
                )
            }
            Result.success(androidx.work.workDataOf("published_uri" to uri.toString()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.e(error, "Could not publish completed audio download %s", mediaId)
            if (error is IOException && runAttemptCount < 8) Result.retry()
            else Result.failure(androidx.work.workDataOf("error" to (error.message ?: "Could not save audio.")))
        }
    }

    private fun findExistingPublicCopy(mediaId: String, expectedBytes: Long): Uri? {
        val token = encodeMediaId(mediaId)
        val resolver = applicationContext.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DISPLAY_NAME,
            )
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf("${Environment.DIRECTORY_MUSIC}/XAN/"),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME))
                    val foundToken = Regex("\\[xan-id-([A-Za-z0-9_-]+)]").find(displayName)?.groupValues?.getOrNull(1)
                    if (foundToken != token) continue
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                    val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE))
                    val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                    val integrityMatches = size > 0 && (expectedBytes <= 0 || size == expectedBytes) &&
                        runCatching { verifyPlayableAudio(uri); true }.getOrDefault(false)
                    if (integrityMatches) return uri
                    resolver.delete(uri, null, null)
                }
            }
            return null
        }
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "XAN")
        val candidate = directory.listFiles()?.firstOrNull {
            Regex("\\[xan-id-([A-Za-z0-9_-]+)]").find(it.name)?.groupValues?.getOrNull(1) == token
        } ?: return null
        if (candidate.length() <= 0 || (expectedBytes > 0 && candidate.length() != expectedBytes)) {
            candidate.delete()
            return null
        }
        return Uri.fromFile(candidate).takeIf { runCatching { verifyPlayableAudio(it); true }.getOrDefault(false) }
    }

    private fun publishToMediaStore(
        cache: androidx.media3.datasource.cache.SimpleCache,
        download: Download,
        key: String,
        expectedBytes: Long,
        mediaId: String,
        title: String,
        artist: String,
        album: String,
        mimeType: String,
        extension: String,
    ): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "XAN")
            if (!folder.mkdirs() && !folder.isDirectory) throw IOException("Could not create Music/XAN.")
            val name = safeName(title, mediaId, extension)
            val destination = uniqueDestination(File(folder, name))
            val temporary = File(folder, ".${destination.name}.partial")
            try {
                copyFromCache(cache, download, key, expectedBytes, temporary)
                if (!temporary.renameTo(destination)) throw IOException("Could not finalize the saved audio file.")
                verifyPlayableAudio(Uri.fromFile(destination))
                MediaScannerConnection.scanFile(applicationContext, arrayOf(destination.absolutePath), arrayOf(mimeType), null)
                return Uri.fromFile(destination)
            } catch (error: Exception) {
                temporary.delete()
                throw error
            }
        }

        val displayName = safeName(title, mediaId, extension)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.ARTIST, artist)
            put(MediaStore.Audio.Media.ALBUM, album)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/XAN")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        // MediaStore's audio schema does not expose a description field consistently;
        // the reversible ID token in DISPLAY_NAME lets a reinstall restore the original row.
        val uri = applicationContext.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create Music/XAN audio media.")
        try {
            val descriptor = applicationContext.contentResolver.openFileDescriptor(uri, "w")
                ?: throw IOException("Could not write Music/XAN audio media.")
            val copiedHash = descriptor.use { parcel ->
                java.io.FileOutputStream(parcel.fileDescriptor).use { output ->
                    val hash = copyFromCache(cache, download, key, expectedBytes, output)
                    output.fd.sync()
                    hash
                }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            if (applicationContext.contentResolver.update(uri, values, null, null) <= 0) {
                throw IOException("Could not finalize Music/XAN audio media.")
            }
            val savedHash = applicationContext.contentResolver.openInputStream(uri)?.use(::sha256)
                ?: throw IOException("Could not verify Music/XAN audio media.")
            if (savedHash != copiedHash) throw IOException("Saved audio failed its read-back integrity check.")
            verifyPlayableAudio(uri)
            return uri
        } catch (error: Exception) {
            applicationContext.contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun publishToAppStorage(
        cache: androidx.media3.datasource.cache.SimpleCache,
        download: Download,
        key: String,
        expectedBytes: Long,
        mediaId: String,
        title: String,
        mimeType: String,
        extension: String,
    ): Uri {
        val folder = File(applicationContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: applicationContext.filesDir, "XAN")
        if (!folder.mkdirs() && !folder.isDirectory) throw IOException("Could not prepare app-specific music storage.")
        val destination = File(folder, safeName(title, mediaId, extension))
        if (destination.isFile && destination.length() > 0 &&
            runCatching { verifyPlayableAudio(Uri.fromFile(destination)); true }.getOrDefault(false)
        ) return Uri.fromFile(destination)
        if (destination.exists() && !destination.delete()) {
            throw IOException("Could not replace the incomplete app-specific audio file.")
        }
        val temporary = File(folder, ".${destination.name}.partial")
        try {
            copyFromCache(cache, download, key, expectedBytes, temporary)
            if (!temporary.renameTo(destination)) throw IOException("Could not finalize the app-specific audio file.")
            verifyPlayableAudio(Uri.fromFile(destination))
            return Uri.fromFile(destination)
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private fun copyFromCache(
        cache: androidx.media3.datasource.cache.SimpleCache,
        download: Download,
        key: String,
        expectedBytes: Long,
        destination: File,
    ): String {
        val copiedHash = java.io.FileOutputStream(destination).use { output ->
            val hash = copyFromCache(cache, download, key, expectedBytes, output)
            output.fd.sync()
            hash
        }
        if (destination.length() <= 0 || sha256(destination) != copiedHash) {
            throw IOException("Audio failed its file integrity check after copying.")
        }
        return copiedHash
    }

    private fun copyFromCache(
        cache: androidx.media3.datasource.cache.SimpleCache,
        download: Download,
        key: String,
        expectedBytes: Long,
        output: java.io.OutputStream,
    ): String {
        val source = CacheDataSource.Factory().setCache(cache).createDataSource()
        val specBuilder = DataSpec.Builder().setUri(download.request.uri).setKey(key)
        if (expectedBytes > 0) specBuilder.setLength(expectedBytes) else specBuilder.setLength(C.LENGTH_UNSET.toLong())
        var copied = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            source.open(specBuilder.build())
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                copied += count
            }
        } finally {
            source.close()
        }
        if (copied <= 0 || (expectedBytes > 0 && copied != expectedBytes)) {
            throw IOException("Audio copy ended at $copied bytes; expected $expectedBytes.")
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verifyPlayableAudio(uri: Uri) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(applicationContext, uri, emptyMap())
            val hasAudioTrack = (0 until extractor.trackCount).any { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            if (!hasAudioTrack) throw IOException("The saved file has no readable audio track.")
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("The saved file could not be parsed as playable audio.", error)
        } finally {
            extractor.release()
        }
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

    private fun safeName(title: String, mediaId: String, extension: String): String {
        val cleanTitle = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ").trim().take(90).ifBlank { "XAN audio" }
        return "$cleanTitle [xan-id-${encodeMediaId(mediaId)}].$extension"
    }

    private fun uniqueDestination(proposed: File): File {
        if (!proposed.exists()) return proposed
        var suffix = 1
        while (true) {
            val candidate = File(proposed.parentFile, "${proposed.nameWithoutExtension} ($suffix).${proposed.extension}")
            if (!candidate.exists()) return candidate
            suffix++
        }
    }

    private fun encodeMediaId(mediaId: String): String =
        android.util.Base64.encodeToString(
            mediaId.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )

    private fun extensionFor(mimeType: String): String = when (mimeType.substringBefore(';').lowercase()) {
        "audio/mpeg" -> "mp3"
        "audio/mp4", "audio/x-m4a" -> "m4a"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/ogg", "audio/opus" -> "ogg"
        "audio/aac" -> "aac"
        "audio/wav", "audio/x-wav" -> "wav"
        else -> "webm"
    }
}
