package app.xan.music.platform.updater.downloadmanager

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.xan.music.R
import app.xan.music.platform.updater.getDownloadedApksDir
import app.xan.music.platform.updater.getUpdateApkFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class UpdateDownloadWorker(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val apkUrl = inputData.getString("apk_url") ?: return@withContext Result.failure()
        val version = inputData.getString("version") ?: "unknown"
        val fileSize = inputData.getString("file_size") ?: ""

        val downloadDir = getDownloadedApksDir(context)
        val finalApk = getUpdateApkFile(context, version)
        val apkPart = File(downloadDir, finalApk.name + ".part")
        var connection: HttpURLConnection? = null

        try {
            DownloadNotificationManager.initialize(context)
            DownloadNotificationManager.showDownloadStarting(version, fileSize)

            if (!downloadDir.exists() && !downloadDir.mkdirs()) {
                error("Could not create the update download directory")
            }

            val activeConnection = URL(apkUrl).openConnection() as HttpURLConnection
            connection = activeConnection
            activeConnection.requestMethod = "GET"
            activeConnection.connectTimeout = 15000
            activeConnection.readTimeout = 15000
            activeConnection.connect()

            if (activeConnection.responseCode != HttpURLConnection.HTTP_OK) {
                DownloadNotificationManager.showDownloadFailed(
                    version,
                    context.getString(R.string.server_error, activeConnection.responseCode),
                )
                return@withContext Result.failure()
            }

            val fileLength = activeConnection.contentLength
            var totalBytesRead = 0L
            activeConnection.inputStream.use { inputStream ->
                FileOutputStream(apkPart).use { outputStream ->
                    val buffer = ByteArray(8192)

                    while (true) {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) break
                        if (isStopped) throw CancellationException("Update download was stopped")

                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        if (fileLength > 0) {
                            val progress = (totalBytesRead.toFloat() / fileLength.toFloat() * 100).toInt()
                            DownloadNotificationManager.updateDownloadProgress(progress, version)
                            setProgress(workDataOf("progress" to progress.toFloat() / 100f))
                        }
                    }
                }
            }

            if (isStopped) throw CancellationException("Update download was stopped")
            if (apkPart.length() == 0L) error("Downloaded APK is empty")
            if (fileLength > 0 && totalBytesRead != fileLength.toLong()) {
                error("Update download was incomplete")
            }
            Files.move(apkPart.toPath(), finalApk.toPath(), StandardCopyOption.REPLACE_EXISTING)

            DownloadNotificationManager.showDownloadComplete(version, finalApk.absolutePath)
            Result.success(workDataOf("file_path" to finalApk.absolutePath))
        } catch (cancelled: CancellationException) {
            apkPart.delete()
            DownloadNotificationManager.cancelNotification()
            throw cancelled
        } catch (error: Exception) {
            apkPart.delete()
            DownloadNotificationManager.showDownloadFailed(
                version,
                error.message ?: context.getString(R.string.download_failed),
            )
            Result.failure()
        } finally {
            connection?.disconnect()
        }
    }
}
