/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.ui.screens.settings

import app.xan.music.ui.utils.appTopBarWindowInsets
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import app.xan.music.playback.ExoDownloadService
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.annotation.ExperimentalCoilApi
import coil3.imageLoader
import app.xan.music.LocalDatabase
import app.xan.music.LocalDownloadUtil
import app.xan.music.LocalPlayerAwareWindowInsets
import app.xan.music.LocalPlayerConnection
import app.xan.music.R
import app.xan.music.constants.MaxImageCacheSizeKey
import app.xan.music.constants.MaxSongCacheSizeKey
import app.xan.music.constants.AdaptiveParallelDownloadsKey
import app.xan.music.constants.AutomaticDownloadRetryKey
import app.xan.music.constants.BackgroundDownloadsEnabledKey
import app.xan.music.constants.DownloadSpeedLimitKibKey
import app.xan.music.constants.MaxParallelDownloadsKey
import app.xan.music.constants.PreserveDownloadsAfterUninstallKey
import app.xan.music.constants.ResumeInterruptedDownloadsKey
import app.xan.music.extensions.tryOrNull
import app.xan.music.ui.component.ActionPromptDialog
import app.xan.music.ui.component.IconButton
import app.xan.music.ui.component.Material3SettingsGroup
import app.xan.music.ui.component.Material3SettingsItem
import app.xan.music.ui.utils.backToMain
import app.xan.music.ui.utils.formatFileSize
import app.xan.music.playback.AudioDownloadRecoveryWorker
import app.xan.music.playback.DownloadStorageMigration
import androidx.media3.exoplayer.offline.Download
import app.xan.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.ByteString.Companion.encodeUtf8
import kotlin.math.roundToInt

@OptIn(ExperimentalCoilApi::class, ExperimentalMaterial3Api::class, DelicateCoilApi::class)
@Composable
fun StorageSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val imageDiskCache = context.imageLoader.diskCache ?: return
    val playerCache = LocalPlayerConnection.current?.service?.playerCache ?: return
    val downloadCache = LocalPlayerConnection.current?.service?.downloadCache ?: return

    val coroutineScope = rememberCoroutineScope()
    val songCacheString = stringResource(R.string.song_cache).lowercase()
    val imageCacheString = stringResource(R.string.image_cache).lowercase()
    val (maxImageCacheSize, onMaxImageCacheSizeChange) = rememberPreference(
        key = MaxImageCacheSizeKey,
        defaultValue = 512
    )
    val (maxSongCacheSize, onMaxSongCacheSizeChange) = rememberPreference(
        key = MaxSongCacheSizeKey,
        defaultValue = 1024
    )
    val (adaptiveParallel, onAdaptiveParallelChange) = rememberPreference(AdaptiveParallelDownloadsKey, true)
    val (maxParallelDownloads, onMaxParallelDownloadsChange) = rememberPreference(MaxParallelDownloadsKey, 16)
    val (automaticRetry, onAutomaticRetryChange) = rememberPreference(AutomaticDownloadRetryKey, true)
    val (resumeInterrupted, onResumeInterruptedChange) = rememberPreference(ResumeInterruptedDownloadsKey, true)
    val (backgroundDownloads, onBackgroundDownloadsChange) = rememberPreference(BackgroundDownloadsEnabledKey, true)
    val (preserveAfterUninstall, onPreserveAfterUninstallChange) = rememberPreference(PreserveDownloadsAfterUninstallKey, true)
    val (speedLimitKib, onSpeedLimitKibChange) = rememberPreference(DownloadSpeedLimitKibKey, 0)
    val downloads by downloadUtil.downloads.collectAsState()
    val downloadSpeed by downloadUtil.aggregateDownloadSpeedBytesPerSecond.collectAsState()
    val activeDownloadCount = downloads.values.count { it.state == Download.STATE_DOWNLOADING }
    val queuedDownloadCount = downloads.values.count {
        it.state == Download.STATE_QUEUED || it.state == Download.STATE_RESTARTING
    }
    val failedDownloadCount = downloads.values.count { it.state == Download.STATE_FAILED }

    var clearDownloads by remember { mutableStateOf(false) }
    var clearCacheDialog by remember { mutableStateOf(false) }
    var clearImageCacheDialog by remember { mutableStateOf(false) }
    var storageMigrationConfirmation by remember { mutableStateOf(false) }
    var storageMigrationRunning by remember { mutableStateOf(false) }
    var storageMigrationMessage by remember { mutableStateOf<String?>(null) }

    // State for the confirmation dialog
    var showCacheWarningDialog by remember { mutableStateOf(false) }
    var cacheType by remember { mutableStateOf("") }
    var cacheUsage by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var onConfirmAction by remember { mutableStateOf<() -> Unit>({}) }

    fun startStorageMigration(targetPreserve: Boolean, sharedDeleteConfirmed: Boolean = false) {
        if (storageMigrationRunning) return
        coroutineScope.launch {
            storageMigrationRunning = true
            try {
                val result = DownloadStorageMigration.migrate(
                    context = context,
                    database = database,
                    keepAfterUninstall = targetPreserve,
                    deleteSharedOriginalsConfirmed = sharedDeleteConfirmed,
                )
                onPreserveAfterUninstallChange(targetPreserve)
                if (result.leftInSource > 0) {
                    storageMigrationMessage = context.getString(
                        R.string.download_storage_migration_incomplete,
                        result.moved,
                        result.leftInSource,
                    )
                } else if (result.moved > 0) {
                    storageMigrationMessage = context.getString(
                        R.string.download_storage_migration_complete,
                        result.moved,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                storageMigrationMessage = context.getString(
                    R.string.download_storage_migration_failed,
                    error.localizedMessage ?: error.javaClass.simpleName,
                )
            } finally {
                storageMigrationRunning = false
            }
        }
    }


    var imageCacheSize by remember {
        androidx.compose.runtime.mutableLongStateOf(imageDiskCache.size)
    }
    var playerCacheSize by remember {
        androidx.compose.runtime.mutableLongStateOf(tryOrNull { playerCache.cacheSpace } ?: 0)
    }
    var downloadCacheSize by remember {
        mutableLongStateOf(tryOrNull { downloadCache.cacheSpace } ?: 0)
    }
    val imageCacheProgress by animateFloatAsState(
        targetValue = (imageCacheSize.toFloat() / (maxImageCacheSize * 1024 * 1024L)).coerceIn(
            0f,
            1f
        ),
        label = "imageCacheProgress",
    )
    val playerCacheProgress by animateFloatAsState(
        targetValue = (playerCacheSize.toFloat() / (maxSongCacheSize * 1024 * 1024L)).coerceIn(
            0f,
            1f
        ),
        label = "playerCacheProgress",
    )

    LaunchedEffect(maxImageCacheSize) {
        SingletonImageLoader.reset()
        if (maxImageCacheSize == 0) {
            coroutineScope.launch(Dispatchers.IO) {
                imageDiskCache.clear()
            }
        }
    }
    LaunchedEffect(maxSongCacheSize) {
        if (maxSongCacheSize == 0) {
            coroutineScope.launch(Dispatchers.IO) {
                playerCache.keys.forEach { key ->
                    playerCache.removeResource(key)
                }
            }
        }
    }

    LaunchedEffect(imageDiskCache) {
        while (isActive) {
            delay(500)
            imageCacheSize = imageDiskCache.size
        }
    }
    LaunchedEffect(playerCache) {
        while (isActive) {
            delay(500)
            playerCacheSize = tryOrNull { playerCache.cacheSpace } ?: 0
        }
    }
    LaunchedEffect(downloadCache) {
        while (isActive) {
            delay(500)
            downloadCacheSize = tryOrNull { downloadCache.cacheSpace } ?: 0
        }
    }

    if (clearDownloads) {
        ActionPromptDialog(
            title = stringResource(R.string.clear_all_downloads),
            onDismiss = { clearDownloads = false },
            onConfirm = {
                // Route through DownloadService/DownloadManager, not the cache directly:
                // stomping downloadCache bypasses DownloadManager's own index, which keeps
                // reporting those songs as downloaded (and can re-trigger them) since it
                // never saw the removal.
                DownloadService.sendRemoveAllDownloads(
                    context,
                    ExoDownloadService::class.java,
                    false,
                )
                clearDownloads = false
            },
            onCancel = { clearDownloads = false },
            content = {
                Text(text = stringResource(R.string.clear_downloads_dialog))
            }
        )
    }
    if (clearCacheDialog) {
        ActionPromptDialog(
            title = stringResource(R.string.clear_song_cache),
            onDismiss = { clearCacheDialog = false },
            onConfirm = {
                coroutineScope.launch(Dispatchers.IO) {
                    playerCache.keys.forEach { key ->
                        playerCache.removeResource(key)
                    }
                }
                clearCacheDialog = false
            },
            onCancel = { clearCacheDialog = false },
            content = {
                Text(text = stringResource(R.string.clear_song_cache_dialog))
            }
        )
    }
    if (clearImageCacheDialog) {
        ActionPromptDialog(
            title = stringResource(R.string.clear_image_cache),
            onDismiss = { clearImageCacheDialog = false },
            onConfirm = {
                coroutineScope.launch(Dispatchers.IO) {
                    val urlsToPreserve = mutableSetOf<String>()
                    val downloadedSongs = try {
                        database.downloadedSongsByNameAsc().first()
                    } catch (e: Exception) {
                        emptyList()
                    }
                    downloadedSongs.forEach { song ->
                        song.song.thumbnailUrl?.let { urlsToPreserve.add(it.encodeUtf8().sha256().hex()) }
                        song.album?.thumbnailUrl?.let { urlsToPreserve.add(it.encodeUtf8().sha256().hex()) }
                    }
                    val directory = imageDiskCache.directory.toFile()
                    if (directory.exists() && directory.isDirectory) {
                        directory.listFiles()?.forEach { file ->
                            if (file.isFile && !file.name.startsWith("journal")) {
                                val isPreserved = urlsToPreserve.any { hash -> file.name.startsWith(hash) }
                                if (!isPreserved) {
                                    file.delete()
                                }
                            }
                        }
                    }
                }
                clearImageCacheDialog = false
            },
            onCancel = { clearImageCacheDialog = false },
            content = {
                Text(text = stringResource(R.string.clear_image_cache_dialog))
            }
        )
    }

    if (storageMigrationConfirmation) {
        AlertDialog(
            onDismissRequest = { storageMigrationConfirmation = false },
            title = { Text(stringResource(R.string.download_storage_migration_confirm_title)) },
            text = { Text(stringResource(R.string.download_storage_migration_confirm_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    storageMigrationConfirmation = false
                    startStorageMigration(targetPreserve = false, sharedDeleteConfirmed = true)
                }) { Text(stringResource(R.string.download_storage_migration_move)) }
            },
            dismissButton = {
                TextButton(onClick = { storageMigrationConfirmation = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    storageMigrationMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { storageMigrationMessage = null },
            title = { Text(stringResource(R.string.download_storage_migration_result_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { storageMigrationMessage = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    // Confirmation Dialog
    if (showCacheWarningDialog) {
        AlertDialog(
            onDismissRequest = { showCacheWarningDialog = false },
            title = { Text(stringResource(R.string.cache_size_warning_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.cache_size_warning_message,
                        formatFileSize(cacheUsage),
                        cacheType
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirmAction()
                        showCacheWarningDialog = false
                    }
                ) {
                    Text(
                        stringResource(R.string.cache_size_warning_confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showCacheWarningDialog = false }) {
                    Text(stringResource(id = android.R.string.cancel))
                }
            }
        )
    }

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top
                )
            )
        )
        Material3SettingsGroup(
            title = stringResource(R.string.download_performance),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.xan_icon_download),
                    title = { Text(stringResource(R.string.download_activity)) },
                    description = {
                        Text(
                            stringResource(
                                R.string.download_activity_summary,
                                activeDownloadCount,
                                queuedDownloadCount,
                                formatFileSize(downloadSpeed) + "/s",
                            ),
                        )
                    },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.cached),
                    title = { Text(stringResource(R.string.adaptive_parallel_downloads)) },
                    description = { Text(stringResource(R.string.adaptive_parallel_downloads_desc)) },
                    trailingContent = {
                        Switch(checked = adaptiveParallel, onCheckedChange = onAdaptiveParallelChange)
                    },
                    onClick = { onAdaptiveParallelChange(!adaptiveParallel) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.cached),
                    title = { Text(stringResource(R.string.maximum_parallel_downloads)) },
                    description = {
                        Column {
                            Text("${maxParallelDownloads.coerceIn(2, 16)}")
                            Slider(
                                value = maxParallelDownloads.coerceIn(2, 16).toFloat(),
                                onValueChange = { onMaxParallelDownloadsChange(it.roundToInt().coerceIn(2, 16)) },
                                valueRange = 2f..16f,
                                steps = 13,
                            )
                        }
                    },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.cached),
                    title = { Text(stringResource(R.string.download_speed_limit)) },
                    description = {
                        Column {
                            Text(if (speedLimitKib == 0) stringResource(R.string.download_speed_unlimited) else "$speedLimitKib KiB/s")
                            Slider(
                                value = speedLimitKib.coerceIn(0, 4096).toFloat(),
                                onValueChange = { onSpeedLimitKibChange(it.roundToInt()) },
                                valueRange = 0f..4096f,
                                steps = 31,
                            )
                        }
                    },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.cached),
                    title = { Text(stringResource(R.string.automatic_download_retry)) },
                    description = { Text(stringResource(R.string.automatic_download_retry_desc)) },
                    trailingContent = { Switch(checked = automaticRetry, onCheckedChange = onAutomaticRetryChange) },
                    onClick = { onAutomaticRetryChange(!automaticRetry) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.cached),
                    title = { Text(stringResource(R.string.resume_interrupted_downloads)) },
                    description = { Text(stringResource(R.string.resume_interrupted_downloads_desc)) },
                    trailingContent = { Switch(checked = resumeInterrupted, onCheckedChange = onResumeInterruptedChange) },
                    onClick = { onResumeInterruptedChange(!resumeInterrupted) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.cached),
                    title = { Text(stringResource(R.string.background_downloads)) },
                    description = { Text(stringResource(R.string.background_downloads_desc)) },
                    trailingContent = { Switch(checked = backgroundDownloads, onCheckedChange = onBackgroundDownloadsChange) },
                    onClick = { onBackgroundDownloadsChange(!backgroundDownloads) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.xan_icon_storage),
                    title = { Text(stringResource(R.string.keep_downloads_after_uninstall)) },
                    description = {
                        Text(
                            stringResource(
                                if (storageMigrationRunning) R.string.download_storage_migration_running
                                else R.string.keep_downloads_after_uninstall_desc,
                            ),
                        )
                    },
                    enabled = !storageMigrationRunning,
                    trailingContent = {
                        if (storageMigrationRunning) CircularProgressIndicator(Modifier.size(20.dp))
                        else Switch(
                            checked = preserveAfterUninstall,
                            onCheckedChange = { target ->
                                if (target) startStorageMigration(targetPreserve = true)
                                else storageMigrationConfirmation = true
                            },
                        )
                    },
                    onClick = {
                        if (!storageMigrationRunning) {
                            if (preserveAfterUninstall) storageMigrationConfirmation = true
                            else startStorageMigration(targetPreserve = true)
                        }
                    },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.cached),
                    title = { Text(stringResource(R.string.retry_failed_downloads)) },
                    description = { Text(stringResource(R.string.retry_failed_downloads_desc, failedDownloadCount)) },
                    enabled = failedDownloadCount > 0,
                    onClick = {
                        downloads.values.filter { it.state == Download.STATE_FAILED }.forEach {
                            runCatching { AudioDownloadRecoveryWorker.retryNow(context, it) }
                        }
                    },
                ),
            ),
        )
        Material3SettingsGroup(
            title = stringResource(R.string.storage),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.xan_icon_storage),
                    title = { Text(stringResource(R.string.downloaded_songs)) },
                    description = {
                        Text(text = formatFileSize(downloadCacheSize))
                    }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.clear_all),
                    title = { Text(stringResource(R.string.clear_all_downloads)) },
                    onClick = {
                        clearDownloads = true
                    }
                )
            )
        )

        Material3SettingsGroup(
            title = stringResource(R.string.song_cache),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.cached),
                    title = { Text(stringResource(R.string.max_song_cache_size)) },
                    description = {
                        val songCacheValues =
                            remember { listOf(0, 128, 256, 512, 1024, 2048, 4096, 8192, -1) }
                        Column {
                            Text(
                                text = when (maxSongCacheSize) {
                                    0 -> stringResource(R.string.disable)
                                    -1 -> stringResource(R.string.unlimited)
                                    else -> formatFileSize(maxSongCacheSize * 1024 * 1024L)
                                }
                            )
                            Slider(
                                value = songCacheValues.indexOf(maxSongCacheSize).toFloat(),
                                onValueChange = {
                                    val newValue = songCacheValues[it.roundToInt()]
                                    val newLimitInBytes = if (newValue == -1) {
                                        Long.MAX_VALUE
                                    } else {
                                        newValue * 1024 * 1024L
                                    }

                                    if (newLimitInBytes < playerCacheSize) {
                                        cacheUsage = playerCacheSize
                                        cacheType = songCacheString
                                        onConfirmAction = { onMaxSongCacheSizeChange(newValue) }
                                        showCacheWarningDialog = true
                                    } else {
                                        onMaxSongCacheSizeChange(newValue)
                                    }
                                },
                                steps = songCacheValues.size - 2,
                                valueRange = 0f..(songCacheValues.size - 1).toFloat()
                            )
                            LinearProgressIndicator(
                                progress = { playerCacheProgress },
                                modifier = Modifier.fillMaxWidth(),
                                strokeCap = StrokeCap.Round
                            )
                            Spacer(modifier = Modifier.padding(2.dp))
                            Text(
                                text = if (maxSongCacheSize == -1) {
                                    formatFileSize(playerCacheSize)
                                } else {
                                    "${formatFileSize(playerCacheSize)} / ${
                                        formatFileSize(
                                            maxSongCacheSize * 1024 * 1024L
                                        )
                                    }"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.clear_all),
                    title = { Text(stringResource(R.string.clear_song_cache)) },
                    onClick = {
                        clearCacheDialog = true
                    }
                )
            )
        )

        Material3SettingsGroup(
            title = stringResource(R.string.image_cache),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.manage_search),
                    title = { Text(stringResource(R.string.max_image_cache_size)) },
                    description = {
                        val imageCacheValues =
                            remember { listOf(0, 128, 256, 512, 1024, 2048, 4096, 8192) }
                        Column {
                            Text(
                                text = when (maxImageCacheSize) {
                                    0 -> stringResource(R.string.disable)
                                    else -> formatFileSize(maxImageCacheSize * 1024 * 1024L)
                                }
                            )
                            Slider(
                                value = imageCacheValues.indexOf(maxImageCacheSize).toFloat(),
                                onValueChange = {
                                    val newValue = imageCacheValues[it.roundToInt()]
                                    val newLimitInBytes = newValue * 1024 * 1024L

                                    if (newLimitInBytes < imageCacheSize) {
                                        cacheUsage = imageCacheSize
                                        cacheType = imageCacheString
                                        onConfirmAction = { onMaxImageCacheSizeChange(newValue) }
                                        showCacheWarningDialog = true
                                    } else {
                                        onMaxImageCacheSizeChange(newValue)
                                    }
                                },
                                steps = imageCacheValues.size - 2,
                                valueRange = 0f..(imageCacheValues.size - 1).toFloat()
                            )
                            LinearProgressIndicator(
                                progress = { imageCacheProgress },
                                modifier = Modifier.fillMaxWidth(),
                                strokeCap = StrokeCap.Round
                            )
                            Spacer(modifier = Modifier.padding(2.dp))
                            Text(
                                text = "${formatFileSize(imageCacheSize)} / ${
                                    formatFileSize(
                                        maxImageCacheSize * 1024 * 1024L
                                    )
                                }",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.clear_all),
                    title = { Text(stringResource(R.string.clear_image_cache)) },
                    onClick = {
                        clearImageCacheDialog = true
                    }
                )
            )
        )
        Spacer(Modifier.padding(bottom = 30.dp))
    }

    TopAppBar(
            windowInsets = appTopBarWindowInsets(),
        title = { Text(stringResource(R.string.storage)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.xan_icon_back),
                    contentDescription = null,
                )
            }
        }
    )
}
