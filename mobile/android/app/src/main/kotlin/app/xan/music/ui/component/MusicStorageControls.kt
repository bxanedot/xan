package app.xan.music.ui.component

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.documentfile.provider.DocumentFile
import androidx.work.WorkManager
import app.xan.music.R
import app.xan.music.constants.MusicCopyEnabledKey
import app.xan.music.constants.MusicCopyFolderKey
import app.xan.music.constants.MusicCopyFormatsKey
import app.xan.music.constants.MusicCopyStatusKey
import app.xan.music.playback.MUSIC_COPY_WORK
import app.xan.music.playback.MusicCopyFormat
import app.xan.music.playback.enqueueMusicCopy
import app.xan.music.utils.dataStore
import app.xan.music.utils.rememberPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared by first-run setup and Content settings; the default cache is always retained. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MusicStorageControls(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val folder by rememberPreference(MusicCopyFolderKey, "")
    var formats by rememberPreference(MusicCopyFormatsKey, setOf("MP3"))
    var enabled by rememberPreference(MusicCopyEnabledKey, false)
    val status by rememberPreference(MusicCopyStatusKey, "")
    var selecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val workManager = remember(context) { WorkManager.getInstance(context) }
    val work by remember(workManager) { workManager.getWorkInfosForUniqueWorkFlow(MUSIC_COPY_WORK) }
        .collectAsState(initial = emptyList())
    val busy = work.any { !it.state.isFinished }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            selecting = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        check(DocumentFile.fromTreeUri(context, uri)?.let { it.isDirectory && it.canWrite() } == true) {
                            context.getString(R.string.music_copy_folder_unavailable)
                        }
                        context.dataStore.edit {
                            it[MusicCopyFolderKey] = uri.toString()
                            it[MusicCopyEnabledKey] = true
                            it[MusicCopyStatusKey] = context.getString(R.string.music_copy_queued)
                        }
                        enqueueMusicCopy(context)
                    }
                    error = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = context.getString(R.string.music_copy_error,
                        failure.message ?: context.getString(R.string.music_copy_unknown_error))
                } finally {
                    selecting = false
                }
            }
        }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.music_storage_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.music_copy_description), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.music_copy_keep_default), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.music_copy_formats), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MusicCopyFormat.entries.forEach { format ->
                    val selected = format.name in formats
                    FilterChip(
                        selected = selected,
                        enabled = !busy && !selecting,
                        onClick = {
                            // Keep at least one target format; zero formats would silently save nothing.
                            if (!selected) formats = formats + format.name
                            else if (formats.size > 1) formats = formats - format.name
                        },
                        label = { Text(format.name) },
                    )
                }
            }
            Text(stringResource(R.string.music_copy_lossless_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (folder.isBlank()) stringResource(R.string.music_copy_default_folder)
                else stringResource(R.string.music_copy_selected_folder, Uri.decode(folder.substringAfterLast('/'))),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && !selecting,
                onClick = { picker.launch(folder.takeIf { it.isNotBlank() }?.let(Uri::parse)) },
            ) { Text(stringResource(R.string.music_copy_action)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.music_copy_automatic), modifier = Modifier.weight(1f))
                Switch(checked = enabled && folder.isNotBlank(), enabled = folder.isNotBlank() && !selecting,
                    onCheckedChange = { enabled = it })
            }
            if (busy || selecting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (busy) {
                TextButton(onClick = { workManager.cancelUniqueWork(MUSIC_COPY_WORK) }) {
                    Text(stringResource(R.string.music_copy_cancel))
                }
            }
        }
    }
}
