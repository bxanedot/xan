package app.xan.music.ui.component

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import app.xan.music.R
import app.xan.music.video.ExternalVideoDownloadState
import app.xan.music.video.cancelExternalVideoDownload
import app.xan.music.video.pauseExternalVideoDownload
import app.xan.music.video.retryExternalVideoDownload
import app.xan.music.video.resumeExternalVideoDownload

@Composable
fun ExternalVideoDownloadList(downloads: List<ExternalVideoDownloadState>) {
    val context = LocalContext.current
    downloads.filter { it.state != WorkInfo.State.SUCCEEDED && (it.state != WorkInfo.State.CANCELLED || it.paused) }
        .forEach { download ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(download.title, style = MaterialTheme.typography.titleMedium)
                val transferred = Formatter.formatShortFileSize(context, download.bytesDownloaded)
                val total = download.bytesTotal.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) }
                val speed = Formatter.formatShortFileSize(context, download.bytesPerSecond)
                Text(
                    listOfNotNull(
                        download.resolution.takeIf(String::isNotBlank),
                        download.percent?.let { "$it%" },
                        if (total == null) transferred else "$transferred / $total",
                        if (download.state == WorkInfo.State.RUNNING) "$speed/s" else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (download.active) {
                    if (download.percent == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { download.percent!! / 100f }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(when (download.stage) {
                        "mux" -> R.string.video_download_combining
                        "publish" -> R.string.video_download_saving
                        else -> if (download.state == WorkInfo.State.RUNNING) R.string.downloading else R.string.video_download_queued
                    }), style = MaterialTheme.typography.bodySmall)
                } else if (download.paused) {
                    Text(stringResource(R.string.video_download_paused), style = MaterialTheme.typography.bodySmall)
                } else download.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row {
                    if (download.paused) {
                        TextButton(onClick = {
                            runCatching { resumeExternalVideoDownload(context, download) }
                                .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                        }) { Text(stringResource(R.string.resume_download)) }
                    } else if (download.state == WorkInfo.State.FAILED) {
                        TextButton(onClick = {
                            runCatching { retryExternalVideoDownload(context, download) }
                                .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                        }) { Text(stringResource(R.string.retry)) }
                    }
                    if (download.active) {
                        TextButton(onClick = {
                            runCatching { pauseExternalVideoDownload(context, download) }
                                .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                        }) { Text(stringResource(R.string.pause)) }
                        TextButton(onClick = { cancelExternalVideoDownload(context, download) }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    }
                }
            }
        }
}
