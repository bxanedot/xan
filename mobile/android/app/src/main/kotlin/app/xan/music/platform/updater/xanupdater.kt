/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.platform.updater


import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import timber.log.Timber
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Observer
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.xan.music.BuildConfig
import app.xan.music.platform.github.GitHubReleases
import app.xan.music.platform.github.GitHubRelease
import app.xan.music.platform.github.GitHubReleaseAsset
import app.xan.music.R
import coil3.compose.AsyncImage
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import app.xan.music.platform.updater.downloadmanager.UpdateDownloadWorker
import app.xan.music.platform.updater.downloadmanager.DownloadNotificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern
import app.xan.music.ui.component.ChangelogItem
import app.xan.music.ui.component.leadingItemShape
import app.xan.music.ui.component.middleItemShape
import app.xan.music.ui.component.endItemShape
import app.xan.music.ui.component.detachedItemShape
import app.xan.music.ui.component.AnimatedActionButton
import app.xan.music.ui.component.ExpressiveIconButton
import app.xan.music.ui.component.ErrorSnackbar
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.style.TextDecoration

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
private const val DOWNLOADS_ROOT_URI = "content://com.android.providers.downloads.documents/root/downloads"

data class ChangelogSection(val title: String, val items: List<String>)

sealed class XanUpdateStatus {
    object Idle : XanUpdateStatus()
    object Checking : XanUpdateStatus()
    data class Available(
        val version: String,
        val changelog: List<ChangelogSection>,
        val size: String,
        val releaseDate: String,
        val description: String?,
        val imageUrl: String?,
        val apkUrl: String?
    ) : XanUpdateStatus()

    data class NoUpdate(val version: String) : XanUpdateStatus()
    data class Error(val message: String) : XanUpdateStatus()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateScreen(navController: NavHostController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<XanUpdateStatus>(XanUpdateStatus.NoUpdate(BuildConfig.VERSION_NAME)) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var isDownloadComplete by remember { mutableStateOf(false) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val apkFilePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (uri != null) {
            val canInstallPackages = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || runCatching {
                context.packageManager.canRequestPackageInstalls()
            }.getOrDefault(false)

            if (!canInstallPackages) {
                runCatching {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    )
                }
                scope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.apk_install_permission_required))
                }
            } else {
                runCatching {
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, APK_MIME_TYPE)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ContextCompat.startActivity(context, installIntent, null)
                }.onFailure { error ->
                    Timber.e(error, "Could not open selected update APK")
                    scope.launch {
                        snackbarHostState.showSnackbar(context.getString(R.string.apk_open_failed))
                    }
                }
            }
        }
    }

    fun chooseApkFromDownloads() {
        val pickerIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = APK_MIME_TYPE
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(DOWNLOADS_ROOT_URI))
            }
        }
        runCatching { apkFilePickerLauncher.launch(pickerIntent) }.onFailure { error ->
            Timber.e(error, "Could not open Downloads to select an update APK")
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.apk_open_failed))
            }
        }
    }

    val currentVersion = BuildConfig.VERSION_NAME
    val autoUpdateCheckEnabled = getAutoUpdateCheckSetting(context)

    LaunchedEffect(Unit) {
        DownloadNotificationManager.initialize(context)
    }

    // Observe WorkManager for download progress
    val updateWorkInfos = remember(context) {
        WorkManager.getInstance(context).getWorkInfosByTagLiveData(UPDATE_DOWNLOAD_WORK_NAME)
    }
    DisposableEffect(updateWorkInfos, lifecycleOwner) {
        val observer = Observer<List<WorkInfo>> { workInfos ->
                val targetVersion = (status as? XanUpdateStatus.Available)?.version
                val matchingWorkInfos = workInfos.orEmpty().filter {
                    targetVersion == null || "$UPDATE_DOWNLOAD_WORK_NAME:$targetVersion" in it.tags
                }
                val workInfo = matchingWorkInfos.firstOrNull {
                    it.state == WorkInfo.State.RUNNING ||
                        it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.BLOCKED
                } ?: matchingWorkInfos.lastOrNull() ?: return@Observer

                when (workInfo.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                        isDownloading = true
                        downloadProgress = workInfo.progress.getFloat("progress", 0f)
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        isDownloading = false
                        isDownloadComplete = true
                        val filePath = workInfo.outputData.getString("file_path")
                        if (filePath != null) {
                            downloadedFile = File(filePath)
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        isDownloading = false
                        scope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.download_failed))
                        }
                    }
                    WorkInfo.State.CANCELLED -> {
                        isDownloading = false
                        downloadProgress = 0f
                    }
                    else -> {}
                }
            }
        updateWorkInfos.observe(lifecycleOwner, observer)
        onDispose { updateWorkInfos.removeObserver(observer) }
    }

    // Check if downloaded file still exists
    LaunchedEffect(isDownloadComplete, downloadedFile) {
        if (isDownloadComplete && downloadedFile != null) {
            if (!downloadedFile!!.exists()) {
                isDownloadComplete = false
                downloadedFile = null
                downloadProgress = 0f
            }
        }
    }

    fun triggerUpdateCheck() {
        status = XanUpdateStatus.Checking
        scope.launch {
            // Add a small delay for visual feedback as in Med
            delay(1000L)
            checkForUpdate(
                context = context,
                onSuccess = { tag, isAvailable, changelog, size, date, description, imageUrl, apkUrl ->
                    saveLastCheckedTime(context, LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy, h:mm a")))
                    saveUpdateAvailableState(context, isAvailable)
                    val existingApk = if (isAvailable) {
                        getUpdateApkFile(context, tag).takeIf { it.isFile && it.length() > 0L }
                    } else {
                        null
                    }
                    downloadedFile = existingApk
                    isDownloadComplete = existingApk != null
                    status = if (isAvailable) {
                        XanUpdateStatus.Available(
                            version = tag,
                            changelog = changelog,
                            size = size,
                            releaseDate = date,
                            description = description,
                            imageUrl = imageUrl,
                            apkUrl = apkUrl
                        )
                    } else {
                        XanUpdateStatus.NoUpdate(tag)
                    }
                },
                onError = {
                    status = XanUpdateStatus.Error(context.getString(R.string.cant_check_updates))
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        if (autoUpdateCheckEnabled) {
            triggerUpdateCheck()
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    val titleText = if (status is XanUpdateStatus.Available) {
                        buildAnnotatedString {
                            append(stringResource(R.string.new_update) + " ")
                            withStyle(
                                SpanStyle(
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            ) {
                                append((status as XanUpdateStatus.Available).version)
                            }
                        }
                    } else {
                        AnnotatedString(stringResource(R.string.settings_check_updates_title))
                    }
                    Text(text = titleText, maxLines = 1)
                },
                navigationIcon = {
                    Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp)) {
                        ExpressiveIconButton(
                            onClick = { navController.navigateUp() },
                            painter = painterResource(R.drawable.xan_icon_back),
                            contentDescription = stringResource(R.string.cancel),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(WindowInsets.navigationBars.asPaddingValues())
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (val currentStatus = status) {
                        is XanUpdateStatus.Idle, is XanUpdateStatus.Checking, is XanUpdateStatus.NoUpdate, is XanUpdateStatus.Error -> {
                            AnimatedActionButton(
                                text = stringResource(R.string.check_for_update),
                                onClick = { triggerUpdateCheck() },
                                enabled = currentStatus !is XanUpdateStatus.Checking && !isDownloading,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        is XanUpdateStatus.Available -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                AnimatedActionButton(
                                    text = stringResource(R.string.later),
                                    onClick = { navController.navigateUp() },
                                    modifier = Modifier.weight(1f),
                                    isOutlined = true,
                                    enabled = !isDownloading
                                )
                                AnimatedActionButton(
                                    text = if (isDownloading) "${(downloadProgress * 100).toInt()}%" else if (isDownloadComplete) stringResource(R.string.install) else stringResource(R.string.update_available),
                                    onClick = {
                                        if (isDownloadComplete) {
                                            val file = downloadedFile
                                            if (file == null || !file.exists()) {
                                                isDownloadComplete = false
                                                downloadedFile = null
                                                downloadProgress = 0f
                                                return@AnimatedActionButton
                                            }
                                            file.let { f ->
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                    val canRequestInstalls = try {
                                                        context.packageManager.canRequestPackageInstalls()
                                                    } catch (error: SecurityException) {
                                                        Timber.e(error, "Updater build is missing REQUEST_INSTALL_PACKAGES")
                                                        scope.launch {
                                                            snackbarHostState.showSnackbar(
                                                                context.getString(R.string.updater_install_unavailable)
                                                            )
                                                        }
                                                        return@let
                                                    }
                                                    if (!canRequestInstalls) {
                                                        val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                                            data = Uri.parse("package:${context.packageName}")
                                                        }
                                                        context.startActivity(intent)
                                                        return@let
                                                    }
                                                }
                                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file)
                                                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, "application/vnd.android.package-archive")
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                ContextCompat.startActivity(context, installIntent, null)
                                            }
                                        } else {
                                            val urlToDownload = currentStatus.apkUrl
                                            if (urlToDownload != null) {
                                                enqueueUpdateDownload(
                                                    context = context,
                                                    apkUrl = urlToDownload,
                                                    version = currentStatus.version,
                                                    fileSize = currentStatus.size,
                                                )
                                                isDownloading = true
                                            } else {
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(context.getString(R.string.download_failed))
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = !isDownloading || isDownloadComplete
                                )
                            }
                        }
                    }
                    TextButton(onClick = { chooseApkFromDownloads() }) {
                        Text(stringResource(R.string.install_apk_from_downloads))
                    }
                }
            }
        },
        snackbarHost = { ErrorSnackbar(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .widthIn(max = 700.dp)
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    val contentModifier = if (status is XanUpdateStatus.Available) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier.fillParentMaxSize()
                    }

                    Box(
                        modifier = contentModifier,
                        contentAlignment = Alignment.Center
                    ) {
                        when (val currentStatus = status) {
                            is XanUpdateStatus.Checking -> {
                                androidx.compose.material3.ContainedLoadingIndicator(
                                    modifier = Modifier.size(64.dp)
                                )
                            }

                            is XanUpdateStatus.NoUpdate -> {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.xan_icon_update),
                                        contentDescription = null,
                                        modifier = Modifier.size(120.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(24.dp))
                                    Text(
                                        text = stringResource(R.string.on_latest_version),
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(R.string.current_version_v, currentStatus.version),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            is XanUpdateStatus.Error -> {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.error),
                                        contentDescription = null,
                                        modifier = Modifier.size(120.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.height(24.dp))
                                    Text(
                                        text = currentStatus.message,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.error,
                                        textAlign = TextAlign.Center,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            is XanUpdateStatus.Available -> {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = stringResource(R.string.release_date_v, currentStatus.releaseDate),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (currentStatus.size.isNotBlank()) {
                                        Text(
                                            text = stringResource(R.string.update_size_v, currentStatus.size),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(24.dp))
                                    if (!currentStatus.imageUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = currentStatus.imageUrl,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(200.dp)
                                                .clip(RoundedCornerShape(24.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                        Spacer(modifier = Modifier.height(24.dp))
                                    }
                                    if (!currentStatus.description.isNullOrBlank()) {
                                        val urls = currentStatus.description.extractUrls()
                                        val annotatedText = buildAnnotatedString {
                                            append(currentStatus.description.trim())
                                            urls.forEach { (range, url) ->
                                                addStringAnnotation("URL", url, range.first, range.last + 1)
                                                addStyle(
                                                    SpanStyle(
                                                        color = MaterialTheme.colorScheme.primary,
                                                        textDecoration = TextDecoration.Underline
                                                    ),
                                                    range.first,
                                                    range.last + 1
                                                )
                                            }
                                        }

                                        ClickableText(
                                            text = annotatedText,
                                            onClick = { offset ->
                                                annotatedText.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                                                    ContextCompat.startActivity(context, Intent(Intent.ACTION_VIEW, Uri.parse(it.item)), null)
                                                }
                                            },
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                lineHeight = 20.sp
                                            ),
                                            modifier = Modifier.padding(bottom = 24.dp)
                                        )
                                    }
                                    if (isDownloading) {
                                        if (downloadProgress > 0f) {
                                            androidx.compose.material3.LinearProgressIndicator(
                                                progress = downloadProgress,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(8.dp)
                                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
                                                color = MaterialTheme.colorScheme.primary,
                                                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                            )
                                        } else {
                                            androidx.compose.material3.LinearProgressIndicator(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(8.dp)
                                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
                                                color = MaterialTheme.colorScheme.primary,
                                                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(24.dp))
                                    }
                                    currentStatus.changelog.forEach { section ->
                                        if (section.title.isNotBlank()) {
                                            Text(
                                                text = section.title,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
                                            )
                                        }
                                        section.items.forEachIndexed { index, item ->
                                            val shape = when {
                                                section.items.size == 1 -> app.xan.music.ui.component.detachedItemShape()
                                                index == 0 -> app.xan.music.ui.component.leadingItemShape()
                                                index == section.items.size - 1 -> app.xan.music.ui.component.endItemShape()
                                                else -> app.xan.music.ui.component.middleItemShape()
                                            }
                                            app.xan.music.ui.component.ChangelogItem(
                                                text = item,
                                                shape = shape,
                                                modifier = Modifier.padding(vertical = 1.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(16.dp))
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }
}

const val UPDATE_DOWNLOAD_WORK_NAME = "update_download"

fun enqueueUpdateDownload(
    context: Context,
    apkUrl: String,
    version: String,
    fileSize: String,
) {
    val downloadedApk = getUpdateApkFile(context, version)
    if (downloadedApk.isFile && downloadedApk.length() > 0L) return

    val downloadRequest = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build(),
        )
        .setInputData(workDataOf("apk_url" to apkUrl, "version" to version, "file_size" to fileSize))
        .addTag(UPDATE_DOWNLOAD_WORK_NAME)
        .addTag("$UPDATE_DOWNLOAD_WORK_NAME:$version")
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        "$UPDATE_DOWNLOAD_WORK_NAME-${downloadedApk.nameWithoutExtension}",
        ExistingWorkPolicy.KEEP,
        downloadRequest,
    )
}


// Utility functions for SharedPreferences uses now view model
const val PREFS_NAME = "settings"
const val KEY_AUTO_UPDATE_CHECK = "auto_update_check"
const val KEY_LAST_CHECKED_TIME = "last_checked_time"
const val KEY_UPDATE_AVAILABLE = "update_available"

fun getUpdateAvailableState(context: Context): Boolean {
    val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return sharedPrefs.getBoolean(KEY_UPDATE_AVAILABLE, false)
}

fun saveUpdateAvailableState(context: Context, available: Boolean) {
    val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    sharedPrefs.edit().putBoolean(KEY_UPDATE_AVAILABLE, available).apply()
}

fun getAutoUpdateCheckSetting(context: Context): Boolean {
    val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return sharedPrefs.getBoolean(KEY_AUTO_UPDATE_CHECK, true)
}

fun saveAutoUpdateCheckSetting(context: Context, enabled: Boolean) {
    val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    sharedPrefs.edit().putBoolean(KEY_AUTO_UPDATE_CHECK, enabled).apply()
}

const val KEY_UPDATE_NOTIFICATIONS = "update_notifications"

fun getUpdateNotificationsSetting(context: Context): Boolean {
    val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return sharedPrefs.getBoolean(KEY_UPDATE_NOTIFICATIONS, true)
}

fun saveUpdateNotificationsSetting(context: Context, enabled: Boolean) {
    val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    sharedPrefs.edit().putBoolean(KEY_UPDATE_NOTIFICATIONS, enabled).apply()
}

fun saveLastCheckedTime(context: Context, timestamp: String) {
    val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    sharedPrefs.edit().putString(KEY_LAST_CHECKED_TIME, timestamp).apply()
}

fun getLastCheckedTime(context: Context): String {
    val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return sharedPrefs.getString(KEY_LAST_CHECKED_TIME, "") ?: ""
}

private fun formatGitHubDate(githubDate: String): String = try {
    val githubFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
    val displayFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy, h:mm a")
    val dateTime = LocalDateTime.parse(githubDate, githubFormatter)
    dateTime.format(displayFormatter)
} catch (e: Exception) {
    githubDate
}

// Robust version comparison: returns true if latestVersion > currentVersion
fun isNewerVersion(latestVersion: String, currentVersion: String): Boolean {
    val latestVersionClean = latestVersion.removePrefix("b").removePrefix("v")
    val currentVersionClean = currentVersion.removePrefix("b").removePrefix("v")

    val latestParts = latestVersionClean.split(".").map { it.toIntOrNull() ?: 0 }
    val currentParts = currentVersionClean.split(".").map { it.toIntOrNull() ?: 0 }
    
    // Compare version numbers
    for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
        val latest = latestParts.getOrElse(i) { 0 }
        val current = currentParts.getOrElse(i) { 0 }
        when {
            latest > current -> return true
            latest < current -> return false
        }
    }
    
    // If numbers are equal, check if one is beta and the other is not
    if (latestVersionClean == currentVersionClean) {
        val latestIsBeta = latestVersion.startsWith("b")
        val currentIsBeta = currentVersion.startsWith("b")
        // Stable is "newer" (better) than beta of the same version
        if (currentIsBeta && !latestIsBeta) return true
    }
    
    return false
}

private fun releaseBodyToChangelog(
    context: Context,
    body: String,
): List<ChangelogSection> {
    if (body.isBlank()) {
        return listOf(
            ChangelogSection(
                context.getString(R.string.changelog),
                listOf(context.getString(R.string.no_changelog_available)),
            )
        )
    }

    val sections = mutableListOf<ChangelogSection>()
    var currentTitle = context.getString(R.string.changelog)
    val currentItems = mutableListOf<String>()

    fun flush() {
        if (currentItems.isNotEmpty()) {
            sections += ChangelogSection(currentTitle, currentItems.toList())
            currentItems.clear()
        }
    }

    body.lineSequence().forEach { raw ->
        val line = raw.trim()
        when {
            line.startsWith("### ") || line.startsWith("## ") -> {
                flush()
                currentTitle = line.trimStart('#').trim().ifBlank {
                    context.getString(R.string.changelog)
                }
            }
            line.isBlank() -> Unit
            else -> currentItems += line
                .removePrefix("- ")
                .removePrefix("* ")
                .removePrefix("• ")
                .trim()
        }
    }
    flush()

    return sections.ifEmpty {
        listOf(ChangelogSection(context.getString(R.string.changelog), listOf(body.trim())))
    }
}

private val releaseVersionPattern = Regex("^v?[0-9]+(?:\\.[0-9]+){1,3}$")

// GitHub Releases is the only source used for version/update metadata.
suspend fun checkForUpdate(
    context: Context,
    onSuccess: (tag: String, isAvailable: Boolean, changelog: List<ChangelogSection>, size: String, date: String, description: String?, imageUrl: String?, apkUrl: String?) -> Unit,
    onError: () -> Unit,
) {
    withContext(Dispatchers.IO) {
        GitHubReleases.fetch(includePrereleases = false)
            .onSuccess { releases ->
                val target = releases.asSequence()
                    .filter { releaseVersionPattern.matches(it.tagName) }
                    .mapNotNull { release ->
                        compatibleUpdateApk(release, Build.SUPPORTED_ABIS.toList())?.let { apk ->
                            release to apk
                        }
                    }
                    .reduceOrNull { best, candidate ->
                        if (isNewerVersion(candidate.first.tagName, best.first.tagName)) candidate else best
                    }
                if (target == null) {
                    withContext(Dispatchers.Main) {
                        onSuccess(BuildConfig.VERSION_NAME, false, emptyList(), "", "", null, null, null)
                    }
                    return@onSuccess
                }
                val (targetRelease, apk) = target

                val currentVersion = BuildConfig.VERSION_NAME
                val shouldOfferUpdate = currentVersion == "dev" ||
                    isNewerVersion(targetRelease.tagName, currentVersion)

                val changelog = releaseBodyToChangelog(context, targetRelease.body)
                val sizeMb = apk.sizeBytes
                    .takeIf { it > 0L }
                    ?.let { String.format("%.1f", it / (1024.0 * 1024.0)) }
                    .orEmpty()

                withContext(Dispatchers.Main) {
                    if (shouldOfferUpdate) {
                        onSuccess(
                            targetRelease.tagName,
                            true,
                            changelog,
                            sizeMb,
                            targetRelease.displayDate,
                            targetRelease.title.takeIf { it != targetRelease.tagName },
                            null,
                            apk.downloadUrl,
                        )
                    } else {
                        onSuccess(
                            currentVersion,
                            false,
                            emptyList(),
                            "",
                            "",
                            null,
                            null,
                            null,
                        )
                    }
                }
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                Timber.tag("UpdateCheck").e(error, "Error checking GitHub Releases")
                withContext(Dispatchers.Main) { onError() }
            }
    }
}

fun String.extractUrls(): List<Pair<IntRange, String>> {
    val urlPattern = Pattern.compile(
        "(?:^|[\\s])((https?://|www\\.|pic\\.)[\\w-]+(\\.[\\w-]+)+([/?].*)?)"
    )
    val matcher = urlPattern.matcher(this)
    val urlList = mutableListOf<Pair<IntRange, String>>()

    while (matcher.find()) {
        val url = matcher.group(1)?.trim() ?: continue
        val range = IntRange(matcher.start(1), matcher.end(1) - 1)
        // Ensure URL has proper scheme
        val fullUrl = if (url.startsWith("http")) url else "https://$url"
        urlList.add(range to fullUrl)
    }

    return urlList
}
