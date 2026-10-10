package app.xan.music.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.edit
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.xan.music.R
import app.xan.music.constants.MusicCopyEnabledKey
import app.xan.music.constants.MusicCopyFolderKey
import app.xan.music.constants.MusicCopyFormatsKey
import app.xan.music.constants.MusicCopyStatusKey
import app.xan.music.utils.dataStore
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import org.json.JSONObject

const val MUSIC_COPY_WORK = "music-copy"

/** Snapshot the destination and formats; changing settings never redirects an in-flight copy. */
suspend fun enqueueMusicCopy(context: Context, songId: String? = null, automatic: Boolean = false) {
    val settings = context.dataStore.data.first()
    val folder = settings[MusicCopyFolderKey]?.takeIf { it.isNotBlank() } ?: return
    if ((songId != null || automatic) && settings[MusicCopyEnabledKey] != true) return
    val formats = MusicCopyFormat.selected(settings[MusicCopyFormatsKey] ?: setOf("MP3"))
    if (formats.isEmpty()) return
    val request = OneTimeWorkRequestBuilder<MusicCopyWorker>()
        .setInputData(workDataOf(
            "folder" to folder,
            "formats" to formats.map { it.name }.toTypedArray(),
            "song_id" to songId,
        ))
        .build()
    // One serial chain bounds CPU and temporary disk use, even during bulk downloads.
    WorkManager.getInstance(context).enqueueUniqueWork(MUSIC_COPY_WORK,
        ExistingWorkPolicy.APPEND_OR_REPLACE, request)
}

@androidx.annotation.OptIn(UnstableApi::class)
class MusicCopyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private var lastFailure: String? = null
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            applicationContext.getString(R.string.music_copy_action), NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.xan_icon_download)
            .setContentTitle(applicationContext.getString(R.string.music_copy_action))
            .setContentText(applicationContext.getString(R.string.music_copy_running))
            .setOngoing(true)
            .addAction(R.drawable.xan_icon_close, applicationContext.getString(android.R.string.cancel),
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return ForegroundInfo(31000, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private suspend fun status(message: String) {
        applicationContext.dataStore.edit { it[MusicCopyStatusKey] = message }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val uri = inputData.getString("folder")?.let(Uri::parse) ?: return@withContext Result.failure()
            val formats = MusicCopyFormat.selected(inputData.getStringArray("formats")?.toSet().orEmpty())
            require(formats.isNotEmpty())
            val folder = DocumentFile.fromTreeUri(applicationContext, uri)
                ?.takeIf { it.isDirectory && it.canWrite() }
                ?: throw IOException(applicationContext.getString(R.string.music_copy_folder_unavailable))
            setForeground(getForegroundInfo())
            val util = EntryPointAccessors.fromApplication(applicationContext,
                ArtistDownloadDependencies::class.java).downloadUtil()
            val songId = inputData.getString("song_id")
            val downloads = buildList {
                util.downloadManager.downloadIndex.getDownloads(Download.STATE_COMPLETED).use { cursor ->
                    while (cursor.moveToNext()) {
                        if (songId == null || cursor.download.request.id == songId) add(cursor.download)
                    }
                }
            }
            if (downloads.isEmpty()) {
                status(applicationContext.getString(R.string.music_copy_no_downloads))
                return@withContext Result.success()
            }
            var copied = 0
            var skipped = 0
            var failed = 0
            downloads.forEachIndexed { index, download ->
                currentCoroutineContext().ensureActive()
                status(applicationContext.getString(R.string.music_copy_progress, index + 1, downloads.size))
                try {
                    val counts = copyDownload(util, download, folder, formats)
                    copied += counts.first
                    skipped += counts.second
                    failed += counts.third
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failed += formats.size
                    lastFailure = error.message
                    Timber.e(error, "Could not copy music %s", download.request.id)
                }
            }
            val summary = applicationContext.getString(R.string.music_copy_finished, copied, skipped, failed)
            status(if (failed > 0) "$summary\n${lastFailure.orEmpty()}" else summary)
            // Returning success permits later independent copies after a per-track failure.
            Result.success(workDataOf("copied" to copied, "skipped" to skipped, "failed" to failed))
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { status(applicationContext.getString(R.string.music_copy_cancelled)) }
            throw cancelled
        } catch (error: Exception) {
            Timber.e(error, "Music copy failed")
            status(applicationContext.getString(R.string.music_copy_error,
                error.message ?: applicationContext.getString(R.string.music_copy_unknown_error)))
            Result.failure()
        }
    }

    private suspend fun copyDownload(
        util: DownloadUtil, download: Download, folder: DocumentFile, formats: List<MusicCopyFormat>,
    ): Triple<Int, Int, Int> {
        val ledger = applicationContext.getSharedPreferences("music_copy_files", Context.MODE_PRIVATE)
        fun ledgerKey(format: MusicCopyFormat) = musicCopyIdentity(
            "${folder.uri}|${download.request.id}|${download.startTimeMs}|${format.name}")
        val pending = formats.filter { format ->
            recoverPublication(folder, ledger, ledgerKey(format))
            val saved = ledger.getString(ledgerKey(format), null)
            saved == null || DocumentFile.fromSingleUri(applicationContext, Uri.parse(saved))
                ?.let { it.exists() && it.length() > 0 } != true
        }
        if (pending.isEmpty()) return Triple(0, formats.size, 0)
        val temp = File(applicationContext.cacheDir, "music-copy-$id")
        check(temp.mkdirs() || temp.isDirectory)
        try {
            val source = File(temp, "source.audio")
            val format = util.database.format(download.request.id).first()
            val length = download.contentLength.takeIf { it > 0 } ?: format?.contentLength ?: -1
            val key = download.request.customCacheKey ?: download.request.id
            // A copy must never fetch network bytes or replace/remove the default offline cache.
            check(length > 0 && util.downloadCache.isCached(key, 0, length)) {
                applicationContext.getString(R.string.music_copy_source_unavailable)
            }
            val dataSource = CacheDataSource.Factory().setCache(util.downloadCache).createDataSource()
            try {
                dataSource.open(DataSpec.Builder().setUri(download.request.uri).setKey(key).setLength(length).build())
                source.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = length
                    while (remaining > 0) {
                        currentCoroutineContext().ensureActive()
                        val read = dataSource.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read <= 0) throw IOException(applicationContext.getString(R.string.music_copy_source_unavailable))
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            } finally {
                dataSource.close()
            }
            val metadata = decodeDownloadTarget(download.request.id, download.request.data)
            var copied = 0
            var failed = 0
            for (outputFormat in pending) {
                currentCoroutineContext().ensureActive()
                val output = File(temp, "converted.${outputFormat.extension}")
                try {
                    convert(outputFormat.conversionArguments(source.absolutePath, output.absolutePath,
                        metadata.title, metadata.artists.joinToString(", ")))
                    check(output.length() > 0) { "Conversion produced no audio" }
                    publish(output, folder, outputFormat,
                        musicCopyFileName(metadata.title, download.request.id, outputFormat), ledger, ledgerKey(outputFormat))
                    copied++
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failed++
                    lastFailure = error.message
                    Timber.e(error, "Music conversion failed: %s / %s", download.request.id, outputFormat)
                } finally {
                    output.delete()
                }
            }
            return Triple(copied, formats.size - pending.size, failed)
        } finally {
            temp.deleteRecursively()
        }
    }

    private suspend fun convert(arguments: List<String>) {
        val finished = CompletableDeferred<Unit>()
        val session = try {
            FFmpegKit.executeWithArgumentsAsync(arguments.toTypedArray(), { result ->
                if (ReturnCode.isSuccess(result.returnCode)) finished.complete(Unit)
                else finished.completeExceptionally(IOException("Audio conversion failed (${result.returnCode})"))
            })
        } catch (error: LinkageError) {
            throw IOException("Audio conversion is unavailable on this device", error)
        }
        try {
            finished.await()
        } catch (cancelled: CancellationException) {
            FFmpegKit.cancel(session.sessionId)
            // Wait for native writes to stop before deleting temporary files.
            withContext(NonCancellable) { runCatching { finished.await() } }
            throw cancelled
        }
    }

    /** Reconcile the rename/ledger window after process death without overwriting another file. */
    private suspend fun recoverPublication(folder: DocumentFile, ledger: SharedPreferences, key: String) {
        val record = ledger.getString("pending:$key", null)?.let(::JSONObject) ?: return
        val published = folder.findFile(record.getString("name"))
        if (published != null && published.length() == record.getLong("size")) {
            val activeContext = currentCoroutineContext()
            val checksum = applicationContext.contentResolver.openInputStream(published.uri)?.use {
                musicCopyChecksum(it) { activeContext.ensureActive() }
            }
            if (checksum == record.getString("checksum")) {
                check(ledger.edit().putString(key, published.uri.toString()).remove("pending:$key").commit()) {
                    "Could not save music copy recovery record"
                }
                return
            }
        }
        // The final file is absent or has different bytes. Delete only our recorded temporary file.
        DocumentFile.fromSingleUri(applicationContext, Uri.parse(record.getString("temporary")))?.let {
            if (it.exists() && it.name?.startsWith(".xan-") == true) it.delete()
        }
        check(ledger.edit().remove("pending:$key").commit()) { "Could not save music copy recovery record" }
    }

    private suspend fun publish(
        source: File, folder: DocumentFile, format: MusicCopyFormat, name: String,
        ledger: SharedPreferences, key: String,
    ) {
        var destination = name
        var suffix = 1
        while (folder.findFile(destination) != null) {
            destination = "${name.substringBeforeLast('.')} (${suffix++}).${format.extension}"
        }
        val document = folder.createFile(format.mimeType, ".xan-$id-${format.name}.partial")
            ?: throw IOException(applicationContext.getString(R.string.music_copy_folder_unavailable))
        var complete = false
        try {
            val activeContext = currentCoroutineContext()
            val checksum = source.inputStream().use { musicCopyChecksum(it) { activeContext.ensureActive() } }
            val record = JSONObject().put("name", destination).put("size", source.length())
                .put("checksum", checksum).put("temporary", document.uri.toString()).toString()
            check(ledger.edit().putString("pending:$key", record).commit()) {
                "Could not save music copy recovery record"
            }
            val stream = applicationContext.contentResolver.openOutputStream(document.uri, "w")
                ?: throw IOException(applicationContext.getString(R.string.music_copy_folder_unavailable))
            stream.use { output ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (!document.renameTo(destination)) throw IOException("Could not finish copied audio file")
            complete = true
            // A failed commit leaves the publication record intact for the next recovery pass.
            check(ledger.edit().putString(key, document.uri.toString()).remove("pending:$key").commit()) {
                "Could not save music copy recovery record"
            }
        } finally {
            if (!complete) document.delete()
        }
    }

    companion object {
        private const val CHANNEL = "music_copies"
    }
}
