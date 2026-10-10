/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.ui.menu

import android.content.res.Configuration
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import com.music.innertube.YouTube
import app.xan.music.LocalDatabase
import app.xan.music.LocalDownloadUtil
import app.xan.music.LocalListenTogetherManager
import app.xan.music.LocalPlayerConnection
import app.xan.music.R
import app.xan.music.constants.ListItemHeight
import app.xan.music.models.MediaMetadata
import app.xan.music.playback.ExoDownloadService
import app.xan.music.playback.buildDownloadRequest
import app.xan.music.playback.toDownloadTarget
import app.xan.music.ui.component.BottomSheetState
import app.xan.music.ui.component.ListDialog
import app.xan.music.ui.component.Material3MenuGroup
import app.xan.music.ui.component.Material3MenuItemData
import app.xan.music.ui.component.NewAction
import app.xan.music.ui.component.NewActionGrid
import app.xan.music.ui.component.VolumeSlider
import app.xan.music.ui.theme.rememberGlobalAccentColors
import app.xan.music.video.enqueueExternalVideoDownload
import app.xan.music.video.externalVideoInfo as buildExternalVideoInfo
import app.xan.music.video.externalVideoResult
import app.xan.music.video.selectedExternalVideoFormat
import app.xan.music.video.toExternalVideoFormat
import app.xan.music.video.observeExternalVideoDownloads
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round

@Composable
fun PlayerMenu(
    mediaMetadata: MediaMetadata?,
    navController: NavController,
    playerBottomSheetState: BottomSheetState,
    isQueueTrigger: Boolean? = false,
    onShowDetailsDialog: () -> Unit,
    onDismiss: () -> Unit,
) {
    mediaMetadata ?: return
    val context = LocalContext.current
    val ringtoneViewModel = app.xan.music.LocalRingtoneViewModel.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val playerVolume = playerConnection.service.playerVolume.collectAsState()
    
    // Cast state for volume control - safely access castConnectionHandler to prevent crashes
    val castHandler = remember(playerConnection) {
        try {
            playerConnection.service.castConnectionHandler
        } catch (e: Exception) {
            null
        }
    }
    val isCasting by castHandler?.isCasting?.collectAsState() ?: remember { mutableStateOf(false) }
    val castVolume by castHandler?.castVolume?.collectAsState() ?: remember { mutableFloatStateOf(1f) }
    val castDeviceName by castHandler?.castDeviceName?.collectAsState() ?: remember { mutableStateOf<String?>(null) }
    
    val librarySong by database.song(mediaMetadata.id).collectAsState(initial = null)
    val coroutineScope = rememberCoroutineScope()

    val download by LocalDownloadUtil.current.getDownload(mediaMetadata.id)
        .collectAsState(initial = null)
    val videoDownloads by remember(context) { observeExternalVideoDownloads(context) }.collectAsState(initial = emptyList())
    val videoDownload = videoDownloads.firstOrNull { it.mediaId == mediaMetadata.id }

    val artists =
        remember(mediaMetadata.artists) {
            mediaMetadata.artists.filter { it.id != null }
        }

    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }
    
    val listenTogetherManager = LocalListenTogetherManager.current
    // See Player.kt: gated on control mode, not on role.
    val canControlTogether = listenTogetherManager?.canControl?.collectAsState(initial = true)
    val isListenTogetherGuest = canControlTogether?.value == false

    AddToPlaylistDialog(
        isVisible = showChoosePlaylistDialog,
        onGetSong = { playlist ->
            database.transaction {
                insert(mediaMetadata)
            }
            coroutineScope.launch(Dispatchers.IO) {
                playlist.playlist.browseId?.let { YouTube.addToPlaylist(it, mediaMetadata.id) }
            }
            listOf(mediaMetadata.id)
        },
        onDismiss = {
            showChoosePlaylistDialog = false
        }
    )

    var showSelectArtistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showSelectArtistDialog) {
        ListDialog(
            onDismiss = { showSelectArtistDialog = false },
        ) {
            items(artists) { artist ->
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier =
                    Modifier
                        .fillParentMaxWidth()
                        .height(ListItemHeight)
                        .clickable {
                            navController.navigate("artist/${artist.id}")
                            showSelectArtistDialog = false
                            playerBottomSheetState.collapseSoft()
                            onDismiss()
                        }
                        .padding(horizontal = 24.dp),
                ) {
                    Text(
                        text = artist.name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    var showPitchTempoDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showPitchTempoDialog) {
        TempoPitchDialog(
            onDismiss = { showPitchTempoDialog = false },
        )
    }

    if (isQueueTrigger != true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp, bottom = 6.dp),
        ) {
            // Show Cast indicator when casting
            if (isCasting && castDeviceName != null) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.cast),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.casting_to, castDeviceName ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            // "Hide Volume Bar" only affects the player's own inline volume
            // row now — this menu copy is the only volume control left once
            // that row is hidden, so it always shows regardless of the setting.
            VolumeSlider(
                value = if (isCasting) castVolume else playerVolume.value,
                onValueChange = { volume ->
                    if (isCasting) {
                        castHandler?.setVolume(volume)
                    } else {
                        playerConnection.service.playerVolume.value = volume
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                accentColor = rememberGlobalAccentColors().first
            )
        }
    }

    Spacer(modifier = Modifier.height(20.dp))

    HorizontalDivider()

    Spacer(modifier = Modifier.height(12.dp))

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    LazyColumn(
        contentPadding = PaddingValues(
            start = 0.dp,
            top = 0.dp,
            end = 0.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
        ),
    ) {
        item {
            val startingRadioText = stringResource(R.string.starting_radio)
            NewActionGrid(
                actions = listOfNotNull(
                    if (!isListenTogetherGuest) {
                        NewAction(
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.radio),
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            text = stringResource(R.string.start_an_radio),
                            onClick = {
                                Toast.makeText(context, startingRadioText, Toast.LENGTH_SHORT).show()
                                playerConnection.startRadioSeamlessly()
                                onDismiss()
                            }
                        )
                    } else null,
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.playlist_add),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        text = stringResource(R.string.add_to_an_playlist),
                        onClick = { showChoosePlaylistDialog = true }
                    ),
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.share),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        text = stringResource(R.string.share),
                        onClick = {
                            val intent = android.content.Intent().apply {
                                action = android.content.Intent.ACTION_SEND
                                type = "text/plain"
                                putExtra(
                                    android.content.Intent.EXTRA_TEXT,
                                    "https://music.youtube.com/watch?v=${mediaMetadata.id}"
                                )
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, null))
                            onDismiss()
                        }
                    ),
                ),
                columns = if (isListenTogetherGuest) 2 else 3,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp)
            )
        }

        item {
            Material3MenuGroup(
                items = buildList {
                    // ── Single smart Retry button ────────────────────────────
                    // Always visible. Re-triggers the full resolution chain.
                    add(
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.retry_stream)) },
                            description = {
                                Text(stringResource(R.string.retry_stream_desc_yt))
                            },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.replay),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            onClick = {
                                val player = playerConnection.player
                                player.seekTo(0)
                                player.prepare()
                                player.play()
                                onDismiss()
                            }
                        )
                    )

                    if (artists.isNotEmpty()) {
                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.view_artist)) },
                                description = {
                                    Text(
                                        text = mediaMetadata.artists.joinToString { it.name },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.artist),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                },
                                onClick = {
                                    if (mediaMetadata.artists.size == 1) {
                                        navController.navigate("artist/${mediaMetadata.artists[0].id}")
                                        playerBottomSheetState.collapseSoft()
                                        onDismiss()
                                    } else {
                                        showSelectArtistDialog = true
                                    }
                                }
                            )
                        )
                    }
                    if (mediaMetadata.album != null) {
                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.view_album)) },
                                description = {
                                    Text(
                                        text = mediaMetadata.album.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.album),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                },
                                onClick = {
                                    navController.navigate("album/${mediaMetadata.album.id}")
                                    playerBottomSheetState.collapseSoft()
                                    onDismiss()
                                }
                            )
                        )
                    }
                    // Add to Library option
                    val isInLibrary = librarySong?.song?.inLibrary != null
                    add(
                        Material3MenuItemData(
                            title = { 
                                Text(
                                    text = stringResource(
                                        if (isInLibrary) R.string.remove_from_library
                                        else R.string.add_to_library
                                    )
                                )
                            },
                            icon = {
                                Icon(
                                    painter = painterResource(
                                        if (isInLibrary) R.drawable.library_add_check
                                        else R.drawable.library_add
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            onClick = {
                                playerConnection.toggleLibrary()
                                onDismiss()
                            }
                        )
                    )
                }
            )
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            Material3MenuGroup(
                items = listOf(
                    when {
                        videoDownload?.active == true -> {
                            Material3MenuItemData(
                                title = { Text(stringResource(R.string.downloading)) },
                                description = { Text(videoDownload.percent?.let { "$it% · ${videoDownload.resolution}" }
                                    ?: videoDownload.resolution) },
                                icon = { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) },
                                onClick = {
                                    WorkManager.getInstance(context).cancelWorkById(videoDownload.workId)
                                    onDismiss()
                                },
                            )
                        }
                        mediaMetadata.isExternalVideo &&
                            librarySong?.song?.isDownloaded == true &&
                            librarySong?.song?.isLocal == true &&
                            download?.state != Download.STATE_COMPLETED -> {
                            Material3MenuItemData(
                                title = { Text(stringResource(R.string.remove_download)) },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.offline),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                    )
                                },
                                onClick = {
                                    val fileUri = librarySong?.song?.externalVideoStreamUrl?.toUri()
                                    runCatching {
                                        when (fileUri?.scheme) {
                                            "file" -> fileUri.path?.let { java.io.File(it).delete() }
                                            null -> Unit
                                            else -> context.contentResolver.delete(fileUri, null, null)
                                        }
                                    }
                                    database.transaction {
                                        songArtistMap(mediaMetadata.id).forEach(::delete)
                                        getSongByIdBlocking(mediaMetadata.id)?.let { delete(it.song) }
                                    }
                                    onDismiss()
                                },
                            )
                        }
                        download?.state == Download.STATE_COMPLETED -> {
                            Material3MenuItemData(
                                title = {
                                    Text(
                                        text = stringResource(R.string.remove_download)
                                    )
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.offline),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                },
                                onClick = {
                                    DownloadService.sendRemoveDownload(
                                        context,
                                        ExoDownloadService::class.java,
                                        mediaMetadata.id,
                                        false,
                                    )
                                }
                            )
                        }

                        download?.state == Download.STATE_QUEUED || download?.state == Download.STATE_DOWNLOADING -> {
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.downloading)) },
                                icon = {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                },
                                onClick = {
                                    DownloadService.sendRemoveDownload(
                                        context,
                                        ExoDownloadService::class.java,
                                        mediaMetadata.id,
                                        false,
                                    )
                                }
                            )
                        }

                        else -> {
                            Material3MenuItemData(
                                title = {
                                    Text(
                                        text = stringResource(
                                            if (mediaMetadata.isExternalVideo) R.string.external_video_download
                                            else R.string.action_download,
                                        ),
                                    )
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.xan_icon_download),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                },
                                onClick = {
                                    if (mediaMetadata.isExternalVideo) {
                                        val source = externalVideoResult(mediaMetadata)
                                        val selected = mediaMetadata.selectedExternalVideoFormat(
                                            playerConnection.player.currentMediaItem?.localConfiguration?.uri?.toString(),
                                        )
                                        if (source == null || selected == null) {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.external_video_load_failed),
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        } else {
                                            val activeVideoFormat = playerConnection.player.videoFormat
                                            val activeAudioFormat = playerConnection.player.audioFormat
                                            coroutineScope.launch {
                                            runCatching {
                                                enqueueExternalVideoDownload(
                                                    context,
                                                    source,
                                                    buildExternalVideoInfo(mediaMetadata),
                                                    selected,
                                                    activeVideoFormat,
                                                    activeAudioFormat,
                                                )
                                            }.onSuccess {
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.external_video_offline_started),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                                onDismiss()
                                            }.onFailure { error ->
                                                Toast.makeText(
                                                    context,
                                                    error.message ?: context.getString(R.string.external_video_load_failed),
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                            }
                                        }
                                    } else {
                                        database.transaction { insert(mediaMetadata) }
                                        val downloadRequest = buildDownloadRequest(mediaMetadata.toDownloadTarget())
                                        DownloadService.sendAddDownload(
                                            context,
                                            ExoDownloadService::class.java,
                                            downloadRequest,
                                            true,
                                        )
                                    }
                                }
                            )
                        }
                    }
                )
            )
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            Material3MenuGroup(
                items = buildList {
                    add(
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.details)) },
                            description = { Text(text = stringResource(R.string.details_desc)) },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.xan_icon_about),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            onClick = {
                                onShowDetailsDialog()
                                onDismiss()
                            }
                        )
                    )

                    if (isQueueTrigger != true) {
                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.equalizer)) },
                                description = { Text(text = stringResource(R.string.equalizer_desc)) },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.xan_icon_equalizer),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                },
                                onClick = {
                                    navController.navigate("equalizer")
                                    onDismiss()
                                }
                            )
                        )
                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.advanced)) },
                                description = { Text(text = stringResource(R.string.advanced_desc)) },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.tune),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                },
                                onClick = {
                                    showPitchTempoDialog = true
                                }
                            )
                        )
                    }
                    add(
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.set_as_ringtone)) },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.notification),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            onClick = {
                                if (ringtoneViewModel.hasSettingsPermission(context)) {
                                    ringtoneViewModel.showTrimmer(
                                        mediaMetadata.id,
                                        mediaMetadata.title,
                                        mediaMetadata.artists.joinToString { it.name },
                                        mediaMetadata.duration
                                    )
                                } else {
                                    ringtoneViewModel.requestSettingsPermission(context)
                                }
                                onDismiss()
                            }
                        )
                    )
                    add(
                        Material3MenuItemData(
                            title = { Text(text = stringResource(R.string.ambient_mode)) },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.fullscreen),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            onClick = {
                                navController.navigate("ambient_mode")
                                playerBottomSheetState.collapseSoft()
                                onDismiss()
                            }
                        )
                    )
                }
            )
        }
    }
}

@Composable
fun TempoPitchDialog(onDismiss: () -> Unit) {
    val playerConnection = LocalPlayerConnection.current ?: return
    var tempo by remember {
        mutableFloatStateOf(playerConnection.player.playbackParameters.speed)
    }
    var transposeValue by remember {
        mutableIntStateOf(round(12 * log2(playerConnection.player.playbackParameters.pitch)).toInt())
    }
    val updatePlaybackParameters = {
        playerConnection.player.playbackParameters =
            PlaybackParameters(tempo, 2f.pow(transposeValue.toFloat() / 12))
    }
    val listenTogetherManager = app.xan.music.LocalListenTogetherManager.current
    val isInRoom = listenTogetherManager?.isInRoom ?: false

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.tempo_and_pitch))
        },
        dismissButton = {
            TextButton(
                onClick = {
                    tempo = 1f
                    transposeValue = 0
                    updatePlaybackParameters()
                },
            ) {
                Text(stringResource(R.string.reset))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        text = {
            Column {
                if (!isInRoom) {
                    ValueAdjuster(
                        icon = R.drawable.speed,
                        currentValue = tempo,
                        values = (0..35).map { round((0.25f + it * 0.05f) * 100) / 100 },
                        onValueUpdate = {
                            tempo = it
                            updatePlaybackParameters()
                        },
                        valueText = { "x$it" },
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                ValueAdjuster(
                    icon = R.drawable.discover_tune,
                    currentValue = transposeValue,
                    values = (-12..12).toList(),
                    onValueUpdate = {
                        transposeValue = it
                        updatePlaybackParameters()
                    },
                    valueText = { "${if (it > 0) "+" else ""}$it" },
                )
            }
        },
    )
}

@Composable
fun <T> ValueAdjuster(
    @DrawableRes icon: Int,
    currentValue: T,
    values: List<T>,
    onValueUpdate: (T) -> Unit,
    valueText: (T) -> String,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
        )

        IconButton(
            enabled = currentValue != values.first(),
            onClick = {
                onValueUpdate(values[values.indexOf(currentValue) - 1])
            },
        ) {
            Icon(
                painter = painterResource(R.drawable.remove),
                contentDescription = null,
            )
        }

        Text(
            text = valueText(currentValue),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(80.dp),
        )

        IconButton(
            enabled = currentValue != values.last(),
            onClick = {
                onValueUpdate(values[values.indexOf(currentValue) + 1])
            },
        ) {
            Icon(
                painter = painterResource(R.drawable.add),
                contentDescription = null,
            )
        }
    }
}
