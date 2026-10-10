package app.xan.music.ui.component

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.xan.music.R
import app.xan.music.playback.enqueueArtistPlaylist
import com.music.innertube.YouTube
import com.music.innertube.models.ArtistItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun ArtistPlaylistDialog(onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(linkedMapOf<String, String>()) }
    var results by remember { mutableStateOf(emptyList<ArtistItem>()) }
    var continuation by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var requestId by rememberSaveable { mutableStateOf(context.getSharedPreferences("artist_playlist_jobs", Context.MODE_PRIVATE).getString("latest_job", null)) }
    var work by remember { mutableStateOf<WorkInfo?>(null) }
    var checkingWork by remember { mutableStateOf(requestId != null) }
    LaunchedEffect(requestId) {
        work = null
        checkingWork = requestId != null
        requestId?.let { WorkManager.getInstance(context).getWorkInfoByIdFlow(UUID.fromString(it)).collect { info -> work = info; checkingWork = false } }
    }
    val busy = requestId != null && (checkingWork || work?.state?.isFinished == false)
    fun search(more: Boolean = false) {
        searchJob?.cancel()
        searchJob = scope.launch {
            searching = true
            searchError = false
            if (!more) { results = emptyList(); continuation = null }
            try {
                val page = if (more) YouTube.searchContinuation(continuation ?: return@launch).getOrThrow()
                    else YouTube.search(query.trim(), YouTube.SearchFilter.FILTER_ARTIST).getOrThrow()
                val artists = page.items.filterIsInstance<ArtistItem>()
                results = (if (more) results + artists else artists).distinctBy { it.id }
                continuation = page.continuation
                searched = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { searchError = true }
            finally { searching = false }
        }
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.artist_playlist)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.artist_playlist_description), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.artist_playlist_name)) },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.artist_playlist_search)) },
                        singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
                    TextButton(onClick = { search() }, enabled = query.isNotBlank() && !busy && !searching) {
                        Text(stringResource(R.string.search))
                    }
                }
                if (selected.isNotEmpty()) Text(stringResource(R.string.artist_playlist_selected, selected.size), style = MaterialTheme.typography.labelLarge)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                    items(selected.toList(), key = { "selected:${it.first}" }) { (id, title) ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { selected = LinkedHashMap(selected).apply { remove(id) } }) {
                            Checkbox(true, { selected = LinkedHashMap(selected).apply { remove(id) } }, enabled = !busy)
                            Text(title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    items(results.filter { it.id !in selected }, key = { it.id }) { artist ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { selected = LinkedHashMap(selected).apply { put(artist.id, artist.title) } }) {
                            Checkbox(false, { selected = LinkedHashMap(selected).apply { put(artist.id, artist.title) } }, enabled = !busy)
                            Text(artist.title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (continuation != null) item { TextButton({ search(true) }, enabled = !searching && !busy) { Text(stringResource(R.string.artist_playlist_more)) } }
                }
                if (searching || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (searchError) Text(stringResource(R.string.artist_playlist_search_error), color = MaterialTheme.colorScheme.error)
                else if (searched && results.isEmpty() && !searching) Text(stringResource(R.string.artist_playlist_no_artists))
                when (work?.state) {
                    WorkInfo.State.SUCCEEDED -> Text(stringResource(R.string.artist_playlist_created, work!!.outputData.getInt("songs", 0)))
                    WorkInfo.State.FAILED -> Text(stringResource(if (work!!.outputData.getBoolean("empty", false)) R.string.artist_playlist_empty else R.string.artist_playlist_failed), color = MaterialTheme.colorScheme.error)
                    WorkInfo.State.CANCELLED -> Text(stringResource(R.string.artist_playlist_cancelled))
                    else -> if (busy) Text(stringResource(R.string.artist_playlist_progress,
                        work?.progress?.getInt("artists", 0) ?: 0, work?.progress?.getInt("total", selected.size) ?: selected.size,
                        work?.progress?.getInt("songs", 0) ?: 0))
                }
            }
        },
        confirmButton = {
            if (work?.state == WorkInfo.State.SUCCEEDED) TextButton({
                val id = work?.outputData?.getString("playlist_id")
                context.getSharedPreferences("artist_playlist_jobs", Context.MODE_PRIVATE).edit().remove("latest_job").apply()
                requestId = null
                if (id != null) onCreated(id)
            }) { Text(stringResource(R.string.artist_playlist_open)) }
            else TextButton({ checkingWork = true; requestId = enqueueArtistPlaylist(context, name.trim(), selected.keys.toList()).toString() },
                enabled = name.isNotBlank() && selected.isNotEmpty() && !busy) { Text(stringResource(R.string.create_playlist)) }
        },
        dismissButton = {
            Row {
                if (work?.state == WorkInfo.State.SUCCEEDED) TextButton({
                    context.getSharedPreferences("artist_playlist_jobs", Context.MODE_PRIVATE).edit().remove("latest_job").apply()
                    requestId = null
                    work = null
                    selected = linkedMapOf()
                    name = ""
                }) { Text(stringResource(R.string.artist_playlist_another)) }
                TextButton(onDismiss) { Text(stringResource(R.string.artist_playlist_close)) }
            }
        },
    )
}
