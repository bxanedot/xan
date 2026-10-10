package app.xan.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

internal enum class Destination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    SEARCH("Search", Icons.Rounded.Search),
    LIBRARY("Library", Icons.Rounded.LibraryMusic),
    RECOGNIZE("Recognize Music", Icons.Rounded.Mic),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

internal enum class DesktopNavigationMode { SIDEBAR, MAGNET, MINI_BAR }

fun main() = application {
    DesktopBackend.firstInstalledAtMillis()
    DesktopBackend.startLocalMusicWatcher()
    val logs = DesktopBackend.diagnosticsDirectory()
    val previousExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        DiagnosticLog.error(logs, "uncaught_exception", "An uncaught application error occurred.", error, mapOf("thread" to thread.name))
        previousExceptionHandler?.uncaughtException(thread, error) ?: error.printStackTrace()
    }
    DiagnosticLog.info(logs, "application_start", "XAN Desktop started.", mapOf("version" to XAN_DESKTOP_VERSION, "os" to System.getProperty("os.name").orEmpty()))
    val windowState = rememberWindowState(position = WindowPosition(Alignment.Center), width = 1280.dp, height = 820.dp)
    var windowVisible by remember { mutableStateOf(true) }
    var appLogo by remember { mutableStateOf(DesktopBackend.appLogo()) }
    val quitApplication = {
        DesktopGlobalMediaKeys.setEnabled(false)
        DesktopSystemTray.shutdown()
        DesktopListenTogether.shutdown()
        DesktopBackend.stopLocalMusicWatcher()
        DesktopCast.shutdown()
        DesktopPlayer.shutdown()
        exitApplication()
    }
    val trayAvailable = remember {
        DesktopSystemTray.install(
            onShowWindow = { windowVisible = true },
            onQuit = quitApplication,
            logo = appLogo,
        )
    }
    val closeWindow = {
        val current = DesktopPlayer.state.value.status
        if (shouldHideToSystemTray(trayAvailable, current)) {
            windowVisible = false
            DesktopSystemTray.notifyBackgroundPlayback()
        } else quitApplication()
    }
    Window(
        visible = windowVisible,
        onCloseRequest = closeWindow,
        title = "XAN",
        icon = painterResource(appLogo.resource),
        state = windowState,
        undecorated = true,
    ) {
        XanTheme { DesktopApp(
            state = windowState,
            appLogo = appLogo,
            onAppLogoChange = {
                appLogo = it
                DesktopBackend.setAppLogo(it)
                DesktopSystemTray.setLogo(it)
            },
            onMinimize = { windowState.isMinimized = true },
            onToggleMaximize = { windowState.placement = if (windowState.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized },
            onClose = closeWindow,
            trayAvailable = trayAvailable,
        ) }
    }
}

@Composable
private fun WindowScope.DesktopApp(state: WindowState, appLogo: AppLogoVariant, onAppLogoChange: (AppLogoVariant) -> Unit, onMinimize: () -> Unit, onToggleMaximize: () -> Unit, onClose: () -> Unit, trayAvailable: Boolean) {
    var destination by remember { mutableStateOf(Destination.HOME) }
    var recentlySelected by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var libraryStart by remember { mutableStateOf("Library") }
    var searchQuery by remember { mutableStateOf("") }
    var magnet by remember { mutableStateOf(DesktopBackend.magnetNavigation()) }
    var sidebar by remember { mutableStateOf(DesktopBackend.sidebarNavigation()) }
    var sidebarCollapsed by remember { mutableStateOf(DesktopBackend.sidebarCollapsed()) }
    var magnetSemicircle by remember { mutableStateOf(DesktopBackend.magnetSemicircle()) }
    var reducedMotion by remember { mutableStateOf(DesktopBackend.reducedMotion()) }
    var reducedTransparency by remember { mutableStateOf(DesktopBackend.reducedTransparency()) }
    var animatedPlayerCanvas by remember { mutableStateOf(DesktopBackend.animatedPlayerCanvas()) }
    var playerExpanded by remember { mutableStateOf(false) }
    val playback by DesktopPlayer.state.collectAsState()
    LaunchedEffect(playback.track) { if (playback.track == null) playerExpanded = false }
    val listenTogether by DesktopListenTogether.state.collectAsState()
    val scope = rememberCoroutineScope()
    val rootFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { DesktopGlobalMediaKeys.setEnabled(DesktopBackend.globalMediaKeysEnabled()); DesktopUpdater.checkOnStartup(); DesktopCast.startDiscovery(); DesktopListenTogether.resumeSavedSession() }
    LaunchedEffect(Unit) { rootFocusRequester.requestFocus() }
    val selectTrack: (MusicTrack) -> Unit = { track ->
        if (listenTogether.roomCode != null && !listenTogether.isHost && listenTogether.controlMode != app.xan.music.listentogether.ControlModes.EVERYONE) {
            DesktopPlayer.setNotice("The room host controls playback.")
        } else {
            val identity: (MusicTrack) -> String = { it.videoId ?: it.localPath ?: "${it.title}:${it.artist}" }
            recentlySelected = (listOf(track) + recentlySelected.filterNot { identity(it) == identity(track) }).take(12)
            scope.launch { DesktopPlayer.play(track) }
        }
    }
    val playingTrack = playback.track
    val trackKey = playingTrack?.let { it.videoId ?: it.localPath ?: "${it.title}:${it.artist}" }
    var lastRoomTrackKey by remember { mutableStateOf<String?>(null) }
    var lastRoomStatus by remember { mutableStateOf<PlaybackStatus?>(null) }
    var lastRoomQueue by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastRoomPosition by remember { mutableStateOf(0L) }
    var lastRoomPositionSampleAt by remember { mutableStateOf(0L) }
    LaunchedEffect(trackKey, playback.status, playback.queue, playback.positionMillis, listenTogether.roomCode) {
        if (listenTogether.roomCode == null || (!listenTogether.isHost && listenTogether.controlMode != app.xan.music.listentogether.ControlModes.EVERYONE)) return@LaunchedEffect
        if (trackKey != lastRoomTrackKey && playingTrack != null) {
            DesktopListenTogether.publishPlayback(app.xan.music.listentogether.PlaybackActions.CHANGE_TRACK, playingTrack, playback.positionMillis)
            lastRoomTrackKey = trackKey
        }
        if (playback.status != lastRoomStatus) {
            val action = if (playback.status == PlaybackStatus.PLAYING) app.xan.music.listentogether.PlaybackActions.PLAY else app.xan.music.listentogether.PlaybackActions.PAUSE
            DesktopListenTogether.publishPlayback(action, playingTrack, playback.positionMillis)
            lastRoomStatus = playback.status
        }
        val queueKeys = playback.queue.map { it.videoId ?: it.localPath ?: "${it.title}:${it.artist}" }
        if (queueKeys != lastRoomQueue && playback.queue.isNotEmpty()) {
            DesktopListenTogether.publishPlayback(app.xan.music.listentogether.PlaybackActions.SYNC_QUEUE)
            lastRoomQueue = queueKeys
        }
        val now = System.currentTimeMillis()
        if (trackKey == lastRoomTrackKey && lastRoomPositionSampleAt > 0 && playback.status == PlaybackStatus.PLAYING) {
            val expectedAdvance = now - lastRoomPositionSampleAt
            val actualAdvance = playback.positionMillis - lastRoomPosition
            if (kotlin.math.abs(actualAdvance - expectedAdvance) > 2_000) {
                DesktopListenTogether.publishPlayback(app.xan.music.listentogether.PlaybackActions.SEEK, positionMillis = playback.positionMillis)
            }
        }
        lastRoomPosition = playback.positionMillis
        lastRoomPositionSampleAt = now
    }
    LaunchedEffect(trackKey, playback.playbackSessionId) {
        val track = playingTrack ?: return@LaunchedEffect
        var historyRecorded = false
        var scrobbleHandled = false
        while (true) {
            val now = DesktopPlayer.state.value
            if (now.track?.let { it.videoId ?: it.localPath ?: "${it.title}:${it.artist}" } != trackKey) return@LaunchedEffect
            val duration = now.durationMillis.takeIf { it > 0 }
                ?: track.durationSeconds?.times(1000L)
                ?: 0L
            val threshold = scrobbleThresholdMillis(duration)
            if (now.status == PlaybackStatus.PLAYING && threshold != null && now.listenedMillis >= threshold) {
                val startedAt = now.startedAtEpochSeconds ?: System.currentTimeMillis() / 1000
                if (!historyRecorded) {
                    if (!DesktopBackend.listeningHistoryEnabled()) historyRecorded = true
                    else runCatching { withContext(Dispatchers.IO) { DesktopBackend.recordListening(track, now.listenedMillis, startedAt) } }
                        .onSuccess { historyRecorded = true }
                        .onFailure {
                            DiagnosticLog.warn(DesktopBackend.diagnosticsDirectory(), "history_save_failed", "Could not save a listening history event.", it)
                            DesktopPlayer.setNotice("Could not save listening history: ${it.message ?: "unknown error"}")
                        }
                }
                if (historyRecorded && !scrobbleHandled && DesktopBackend.hasEnabledScrobblingService()) {
                    scrobbleHandled = true
                    if (!track.metadataVerified) DesktopPlayer.setNotice("Skipped scrobbling: local title and artist tags are incomplete.")
                    else runCatching {
                        withContext(Dispatchers.IO) {
                            val eventId = "${playback.playbackSessionId}:${track.videoId ?: track.localPath ?: "${track.title}:${track.artist}"}:$startedAt"
                            DesktopBackend.scrobbleEnabledServices(track, startedAt, eventId)
                        }
                    }.onSuccess { outcome ->
                        val sent = outcome.delivered.joinToString(" and ")
                        val errors = outcome.failures.joinToString("; ") { (service, error) -> "$service: $error" }
                        DesktopPlayer.setNotice(when {
                            errors.isNotBlank() && sent.isNotBlank() -> "Scrobbled to $sent. $errors"
                            errors.isNotBlank() -> "Scrobbling failed: $errors"
                            sent.isNotBlank() -> "Scrobbled to $sent."
                            else -> null
                        })
                    }.onFailure {
                        DiagnosticLog.warn(DesktopBackend.diagnosticsDirectory(), "scrobbling_failed", "Could not submit a scrobble.", it)
                        DesktopPlayer.setNotice("Scrobbling failed: ${it.message ?: "unknown error"}")
                    }
                }
            }
            delay(500)
        }
    }
    LaunchedEffect(trackKey, playback.playbackSessionId) {
        val track = playingTrack ?: return@LaunchedEffect
        val videoId = track.videoId ?: return@LaunchedEffect
        if (!Regex("^[A-Za-z0-9_-]{11}$").matches(videoId) || track.source?.contains("youtube", ignoreCase = true) != true) return@LaunchedEffect
        var requestSettings: Pair<Boolean, List<String>>? = null
        var segments = emptyList<SponsorSegment>()
        val skipped = mutableSetOf<String>()
        while (true) {
            val desired = DesktopBackend.sponsorBlockEnabled() to DesktopBackend.sponsorBlockCategories()
            if (desired != requestSettings) {
                requestSettings = desired
                skipped.clear()
                segments = if (desired.first && desired.second.isNotEmpty()) {
                    runCatching { withContext(Dispatchers.IO) { SponsorBlockClient.segments(videoId, desired.second) } }
                        .onFailure {
                            DiagnosticLog.warn(DesktopBackend.diagnosticsDirectory(), "sponsorblock_request_failed", "SponsorBlock could not return segments.", it)
                            DesktopPlayer.setNotice("SponsorBlock is unavailable: ${it.message ?: "request failed"}")
                        }
                        .getOrDefault(emptyList())
                } else emptyList()
            }
            val now = DesktopPlayer.state.value
            if (now.playbackSessionId != playback.playbackSessionId) return@LaunchedEffect
            if (now.status == PlaybackStatus.PLAYING) {
                val position = now.positionMillis
                val segment = sponsorSegmentAt(segments, position)
                if (segment != null) {
                    val segmentKey = "${segment.category}:${segment.startMillis}:${segment.endMillis}"
                    if (skipped.add(segmentKey)) {
                        val target = sponsorSegmentTarget(segment, now.durationMillis)
                        if (target > position + 250L) {
                            DesktopPlayer.setNotice("SponsorBlock skipped ${SponsorBlockCategories.labels[segment.category] ?: segment.category}.")
                            DesktopPlayer.seekTo(target)
                        }
                    }
                }
            }
            delay(250)
        }
    }
    LaunchedEffect(trackKey, playback.status) {
        if (DesktopBackend.isDiscordEnabled()) withContext(Dispatchers.IO) {
            DesktopBackend.updateDiscordPresence(playingTrack.takeIf { playback.status == PlaybackStatus.PLAYING })
        }
    }
    val navigate: (Destination) -> Unit = {
        if (it == Destination.LIBRARY) libraryStart = "Library"
        destination = it
    }
    val selectedMagnetId = when {
        destination != Destination.LIBRARY -> destination.name.lowercase()
        libraryStart.equals("Playlists", ignoreCase = true) -> "playlists"
        else -> "library"
    }
    val selectMagnet: (MagnetNavItem) -> Unit = { item ->
        when (item.id) {
            "shuffle" -> scope.launch {
                val queue = playback.queue.takeIf { it.size > 1 } ?: DesktopBackend.catalogTracks()
                DesktopPlayer.shuffle(queue)
            }
            else -> navigate(Destination.valueOf(item.id.uppercase()))
        }
    }
    val navigationMode = when {
        sidebar -> DesktopNavigationMode.SIDEBAR
        magnet -> DesktopNavigationMode.MAGNET
        else -> DesktopNavigationMode.MINI_BAR
    }
    val changeNavigationMode: (DesktopNavigationMode) -> Unit = { mode ->
        sidebar = mode == DesktopNavigationMode.SIDEBAR
        DesktopBackend.setSidebarNavigation(sidebar)
        when (mode) {
            DesktopNavigationMode.SIDEBAR -> Unit
            DesktopNavigationMode.MAGNET -> {
                magnet = true
                DesktopBackend.setMagnetNavigation(true)
            }
            DesktopNavigationMode.MINI_BAR -> {
                magnet = false
                DesktopBackend.setMagnetNavigation(false)
            }
        }
    }

    Box(
        Modifier.fillMaxSize()
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                val action = resolveDesktopShortcut(
                    event.key,
                    primaryModifier = event.isCtrlPressed || event.isMetaPressed,
                    altPressed = event.isAltPressed,
                ) ?: return@onPreviewKeyEvent false
                val canControlPlayback = listenTogether.roomCode == null || listenTogether.isHost ||
                    listenTogether.controlMode == app.xan.music.listentogether.ControlModes.EVERYONE
                if (action in setOf(DesktopShortcutAction.PLAY_PAUSE, DesktopShortcutAction.PREVIOUS_TRACK, DesktopShortcutAction.NEXT_TRACK) && !canControlPlayback) {
                    DesktopPlayer.setNotice("The room host controls playback.")
                } else when (action) {
                    DesktopShortcutAction.PLAY_PAUSE -> {
                        scope.launch { DesktopTransportControls.perform(DesktopTransportAction.PLAY_PAUSE) }
                    }
                    DesktopShortcutAction.PREVIOUS_TRACK -> scope.launch {
                        DesktopTransportControls.perform(DesktopTransportAction.PREVIOUS)
                    }
                    DesktopShortcutAction.NEXT_TRACK -> scope.launch {
                        DesktopTransportControls.perform(DesktopTransportAction.NEXT)
                    }
                    DesktopShortcutAction.HOME -> navigate(Destination.HOME)
                    DesktopShortcutAction.SEARCH -> navigate(Destination.SEARCH)
                    DesktopShortcutAction.LIBRARY -> navigate(Destination.LIBRARY)
                    DesktopShortcutAction.RECOGNIZE -> navigate(Destination.RECOGNIZE)
                    DesktopShortcutAction.SETTINGS -> navigate(Destination.SETTINGS)
                }
                true
            }
            .background(Brush.verticalGradient(listOf(XanColors.Background, XanColors.Selected, XanColors.Background)))
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            TopChrome(
                appLogo = appLogo,
                onSettings = { navigate(Destination.SETTINGS) },
                onMinimize = onMinimize,
                onToggleMaximize = onToggleMaximize,
                onClose = onClose,
                isMaximized = state.placement == WindowPlacement.Maximized,
                trayAvailable = trayAvailable,
            )
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val availableWidth = maxWidth
                val compactSidebar = sidebarCollapsed || availableWidth < 1120.dp
                val sidebarWidth by animateDpAsState(
                    targetValue = if (compactSidebar) 74.dp else 224.dp,
                    animationSpec = tween(if (reducedMotion) 0 else 220),
                    label = "desktop-sidebar-width",
                )
                Row(Modifier.fillMaxSize()) {
                    if (sidebar) {
                        DesktopSidebar(
                            destination = destination,
                            collapsed = compactSidebar,
                            onToggleCollapsed = {
                                sidebarCollapsed = !sidebarCollapsed
                                DesktopBackend.setSidebarCollapsed(sidebarCollapsed)
                            },
                            onNavigate = navigate,
                            reducedMotion = reducedMotion,
                            modifier = Modifier.fillMaxHeight().padding(end = 14.dp).width(sidebarWidth),
                            canToggleCollapse = availableWidth >= 1120.dp,
                        )
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().padding(bottom = when {
                        playback.track != null && navigationMode != DesktopNavigationMode.MINI_BAR -> 88.dp
                        playback.track != null -> 156.dp
                        navigationMode == DesktopNavigationMode.MINI_BAR -> 88.dp
                        else -> 0.dp
                    })) {
                        AnimatedContent(
                            targetState = destination,
                            transitionSpec = {
                                val enterDuration = if (reducedMotion) 0 else 210
                                val exitDuration = if (reducedMotion) 0 else 140
                                (fadeIn(tween(enterDuration)) + slideInHorizontally(
                                    initialOffsetX = { width -> if (reducedMotion) 0 else width / 36 },
                                    animationSpec = tween(enterDuration),
                                )) togetherWith (fadeOut(tween(exitDuration)) + slideOutHorizontally(
                                    targetOffsetX = { width -> if (reducedMotion) 0 else -width / 48 },
                                    animationSpec = tween(exitDuration),
                                ))
                            },
                            label = "desktop-destination-transition",
                        ) { page ->
                            when (page) {
                                Destination.HOME -> HomeScreen(
                                    recentTracks = recentlySelected,
                                    onTrackSelected = selectTrack,
                                    onSearch = { navigate(Destination.SEARCH) },
                                    onLibrary = { navigate(Destination.LIBRARY) },
                                )
                                Destination.SEARCH -> SearchScreen(selectTrack, initialQuery = searchQuery)
                                Destination.LIBRARY -> LibraryScreen(libraryStart, selectTrack)
                                Destination.RECOGNIZE -> RecognitionScreen(
                                    onBack = { navigate(Destination.HOME) },
                                    onSearch = { query -> searchQuery = query; navigate(Destination.SEARCH) },
                                )
                                Destination.SETTINGS -> SettingsScreen(
                                    navigationMode, changeNavigationMode, appLogo, onAppLogoChange,
                                    magnetSemicircle, { magnetSemicircle = it; DesktopBackend.setMagnetSemicircle(it) },
                                    reducedMotion, { reducedMotion = it; DesktopBackend.setReducedMotion(it) },
                                    reducedTransparency, { reducedTransparency = it; DesktopBackend.setReducedTransparency(it) },
                                    animatedPlayerCanvas, { animatedPlayerCanvas = it; DesktopBackend.setAnimatedPlayerCanvas(it) },
                                    onBackupRestored = {
                                        magnet = DesktopBackend.magnetNavigation()
                                        sidebar = DesktopBackend.sidebarNavigation()
                                        sidebarCollapsed = DesktopBackend.sidebarCollapsed()
                                        magnetSemicircle = DesktopBackend.magnetSemicircle()
                                        onAppLogoChange(DesktopBackend.appLogo())
                                        DesktopBackend.setColorTheme(DesktopBackend.colorTheme())
                                        XanColors.selectTheme(DesktopBackend.colorTheme())
                                        XanFonts.refresh()
                                        reducedMotion = DesktopBackend.reducedMotion()
                                        reducedTransparency = DesktopBackend.reducedTransparency()
                                        animatedPlayerCanvas = DesktopBackend.animatedPlayerCanvas()
                                    },
                                    onTrackSelected = selectTrack,
                                )
                            }
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            visible = playback.track != null && !playerExpanded,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(
                                bottom = if (navigationMode == DesktopNavigationMode.MINI_BAR) 86.dp else 20.dp,
                            ),
                            enter = expandVertically(expandFrom = Alignment.CenterVertically, animationSpec = tween(240)) + fadeIn(tween(180)),
                            exit = shrinkVertically(shrinkTowards = Alignment.CenterVertically, animationSpec = tween(240)) + fadeOut(tween(160)),
                            label = "desktop-now-playing-bar",
                        ) {
                            NowPlayingBar(
                                playback,
                                controlsEnabled = listenTogether.roomCode == null || listenTogether.isHost || listenTogether.controlMode == app.xan.music.listentogether.ControlModes.EVERYONE,
                                onExpand = { playerExpanded = true },
                            )
                        }
                        if (navigationMode == DesktopNavigationMode.MINI_BAR) {
                            MiniNavigationBar(
                                destination = destination,
                                onNavigate = navigate,
                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
                            )
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = playerExpanded && playback.track != null,
            modifier = Modifier.fillMaxSize(),
            enter = expandVertically(expandFrom = Alignment.Bottom, animationSpec = tween(320)) + fadeIn(tween(220)),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom, animationSpec = tween(260)) + fadeOut(tween(180)),
            label = "desktop-player-canvas",
        ) {
            Box(
                Modifier.fillMaxSize().padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 72.dp,
                    bottom = if (magnet) 86.dp else 104.dp,
                ),
            ) {
                ExpandedPlayerCanvas(
                    snapshot = playback,
                    controlsEnabled = listenTogether.roomCode == null || listenTogether.isHost || listenTogether.controlMode == app.xan.music.listentogether.ControlModes.EVERYONE,
                    animatedCanvas = animatedPlayerCanvas,
                    modifier = Modifier.fillMaxSize(),
                    onClose = { playerExpanded = false },
                )
            }
        }
        if (navigationMode == DesktopNavigationMode.MAGNET) {
            MagneticNavigation(
                selectedId = selectedMagnetId,
                reducedMotion = reducedMotion,
                semicircle = magnetSemicircle,
                onSelect = selectMagnet,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun MiniNavigationBar(
    destination: Destination,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(28.dp))
                .background(XanColors.Panel)
                .border(1.dp, XanColors.Border.copy(alpha = .65f), RoundedCornerShape(28.dp))
                .padding(horizontal = 7.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(Destination.HOME, Destination.LIBRARY, Destination.SETTINGS).forEach { item ->
                val active = destination == item
                TextButton(
                    onClick = { onNavigate(item) },
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (active) XanColors.Text else XanColors.Muted,
                        containerColor = if (active) XanColors.Selected else Color.Transparent,
                    ),
                    contentPadding = PaddingValues(horizontal = 15.dp, vertical = 10.dp),
                ) {
                    Icon(item.icon, contentDescription = null, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(item.label)
                }
            }
        }
        IconButton(
            onClick = { onNavigate(Destination.SEARCH) },
            modifier = Modifier.size(56.dp).clip(CircleShape)
                .background(XanColors.Panel)
                .border(1.dp, XanColors.Border.copy(alpha = .75f), CircleShape),
        ) {
            Icon(Icons.Rounded.Search, contentDescription = "Search", tint = if (destination == Destination.SEARCH) XanColors.Accent else XanColors.Text)
        }
    }
}

@Composable
private fun WindowScope.TopChrome(
    appLogo: AppLogoVariant,
    onSettings: () -> Unit,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
    isMaximized: Boolean,
    trayAvailable: Boolean,
) {
    WindowDraggableArea {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = 18.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            XanMark(25.dp, appLogo)
            Text("xan", Modifier.padding(start = 9.dp), color = XanColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = XanBrandFont)
            Spacer(Modifier.width(20.dp))
            Text("YOUR MUSIC. YOUR LIBRARY. YOUR WAY.", style = MaterialTheme.typography.labelSmall, color = XanColors.Muted, letterSpacing = 1.1.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSettings) {
                Icon(Icons.Rounded.AccountCircle, contentDescription = "Settings", tint = XanColors.Text, modifier = Modifier.size(24.dp))
            }
            IconButton(onClick = onMinimize) {
                Icon(Icons.Rounded.Remove, contentDescription = "Minimize window", tint = XanColors.Muted)
            }
            IconButton(onClick = onToggleMaximize) {
                Icon(if (isMaximized) Icons.Rounded.FilterNone else Icons.Rounded.CropSquare, contentDescription = if (isMaximized) "Restore window" else "Maximize window", tint = XanColors.Muted)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = if (trayAvailable) "Close XAN; active playback continues in the system tray" else "Close XAN", tint = XanColors.Muted)
            }
        }
    }
}
