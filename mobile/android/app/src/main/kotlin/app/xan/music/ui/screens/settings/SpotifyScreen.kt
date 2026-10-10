package app.xan.music.ui.screens.settings

import app.xan.music.ui.utils.appTopBarWindowInsets

import android.content.Intent
import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.music.spotify.SpotifyAuth
import com.music.spotify.SpotifyMapper
import com.music.spotify.models.SpotifyPlaylist
import app.xan.music.LocalPlayerAwareWindowInsets
import app.xan.music.R
import app.xan.music.ui.component.DefaultDialog
import app.xan.music.ui.component.IconButton
import app.xan.music.ui.component.Material3SettingsGroup
import app.xan.music.ui.component.Material3SettingsItem
import app.xan.music.ui.menu.LoadingScreen
import app.xan.music.ui.utils.backToMain
import app.xan.music.utils.rememberPreference
import app.xan.music.viewmodels.SpotifyImportViewModel
import kotlinx.coroutines.launch

private fun openSpotifyWeb(context: Context) {
    val uri = Uri.parse("https://open.spotify.com/")
    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
}

private const val SPOTIFY_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; SM-S921U; Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"

private const val SPOTIFY_PAGE_HAS_VISIBLE_CONTENT_JS = """
    (() => {
        const visible = (element) => {
            const rect = element.getBoundingClientRect();
            const style = getComputedStyle(element);
            return rect.width > 2 && rect.height > 2 && style.display !== 'none' &&
                style.visibility !== 'hidden' && Number(style.opacity || 1) > 0.01;
        };
        const text = [...document.querySelectorAll('body *')].some(element =>
            element.children.length === 0 && element.textContent.trim().length > 0 && visible(element)
        );
        const controls = [...document.querySelectorAll(
            'input, button, a, [role="button"], img, svg, canvas, video'
        )].some(visible);
        return text || controls ? 1 : 0;
    })()
"""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    autoStartPublicPlaylist: Boolean = false,
    viewModel: SpotifyImportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var showSpotifyLogin by remember { mutableStateOf(false) }
    var showPlaylistsSheet by remember { mutableStateOf(false) }
    var showPublicPlaylistDialog by remember(autoStartPublicPlaylist) {
        mutableStateOf(autoStartPublicPlaylist)
    }
    var publicPlaylistLink by rememberSaveable { mutableStateOf("") }
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()

    val refreshEnabled = state.isAuthenticated && !state.isLoading
    val rotationAngle by if (state.isLoading) {
        val transition = rememberInfiniteTransition(label = "rotation")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "rotationAngle"
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    Column(
        modifier = Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)
            )
        )

        Text(
            text = stringResource(R.string.spotify),
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 16.dp)
        )

        // Connection Card/Group
        Material3SettingsGroup(
            title = stringResource(R.string.spotify_account),
            items = if (state.isAuthenticated) {
                listOf(
                    Material3SettingsItem(
                        leadingContent = if (!state.accountAvatarUrl.isNullOrBlank()) {
                            {
                                AsyncImage(
                                    model = state.accountAvatarUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        } else null,
                        icon = if (state.accountAvatarUrl.isNullOrBlank()) painterResource(R.drawable.xan_icon_spotify) else null,
                        title = {
                            Text(
                                text = if (state.accountName.isNotBlank()) state.accountName
                                else stringResource(R.string.spotify_account),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        },
                        trailingContent = {
                            OutlinedButton(
                                onClick = { viewModel.logout() },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                Text(stringResource(R.string.action_logout))
                            }
                        },
                        onClick = {}
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.spotify_web_title)) },
                        description = { Text(stringResource(R.string.spotify_open_in_browser)) },
                        icon = painterResource(R.drawable.xan_icon_spotify),
                        onClick = { openSpotifyWeb(context) }
                    )
                )
                } else {
                listOf(
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.spotify_web_title)) },
                        description = { Text(stringResource(R.string.spotify_open_in_browser)) },
                        icon = painterResource(R.drawable.xan_icon_spotify),
                        onClick = { openSpotifyWeb(context) }
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.spotify_connect_for_import)) },
                        description = { Text(stringResource(R.string.spotify_web_sign_in)) },
                        icon = painterResource(R.drawable.xan_icon_spotify),
                        onClick = { showSpotifyLogin = true }
                    )
                )
                }
        )

        Spacer(modifier = Modifier.height(24.dp))

        val totalPlaylists = state.playlists.size + if (state.likedSongsCount > 0) 1 else 0
        Material3SettingsGroup(
            title = stringResource(R.string.playlists),
            items = listOf(
                Material3SettingsItem(
                    title = { Text(stringResource(R.string.spotify_public_link_title)) },
                    description = { Text(stringResource(R.string.spotify_public_link_desc)) },
                    icon = painterResource(R.drawable.xan_icon_spotify),
                    onClick = { showPublicPlaylistDialog = true },
                ),
                Material3SettingsItem(
                    title = { Text(stringResource(R.string.spotify_select_sources)) },
                    description = {
                        Text(
                            if (state.isAuthenticated) {
                                if (totalPlaylists > 0) stringResource(R.string.spotify_available_count, totalPlaylists)
                                else stringResource(R.string.spotify_no_sources)
                            } else {
                                stringResource(R.string.spotify_not_connected)
                            }
                        )
                    },
                    icon = painterResource(R.drawable.bookmark_star_library),
                    enabled = state.isAuthenticated && totalPlaylists > 0 && !state.isLoading,
                    onClick = { showPlaylistsSheet = true }
                ),
                Material3SettingsItem(
                    title = { Text(stringResource(R.string.spotify_refresh)) },
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.sync),
                                contentDescription = null,
                                tint = if (!refreshEnabled) {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                                },
                                modifier = Modifier
                                    .size(24.dp)
                                    .graphicsLayer {
                                        rotationZ = rotationAngle
                                    }
                            )
                        }
                    },
                    enabled = refreshEnabled,
                    onClick = { viewModel.loadSources() }
                )
            )
        )

        // Info block
        Row(
            modifier = Modifier.padding(top = 24.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                painter = painterResource(R.drawable.xan_icon_about),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                text = stringResource(R.string.spotify_public_import_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(36.dp))
    }

    TopAppBar(
            windowInsets = appTopBarWindowInsets(),
        title = {},
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
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent
        )
    )

    if (showSpotifyLogin) {
        SpotifyLoginSheet(
            onDismiss = { showSpotifyLogin = false },
            onCookiesCaptured = { spDc, spKey ->
                showSpotifyLogin = false
                viewModel.connectWithCookies(spDc, spKey)
            }
        )
    }

    if (showPublicPlaylistDialog) {
        DefaultDialog(
            onDismiss = { showPublicPlaylistDialog = false },
            title = { Text(stringResource(R.string.spotify_public_link_title)) },
            buttons = {
                TextButton(onClick = { showPublicPlaylistDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
                Button(
                    onClick = {
                        showPublicPlaylistDialog = false
                        viewModel.startPublicPlaylistImport()
                    },
                    enabled = state.publicPlaylist?.tracks?.isNotEmpty() == true &&
                        !state.isLoadingPublicPlaylist,
                ) {
                    Text(stringResource(R.string.spotify_import_shown_tracks))
                }
            },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = publicPlaylistLink,
                    onValueChange = { value ->
                        publicPlaylistLink = value
                        viewModel.clearPublicPlaylistPreview()
                    },
                    label = { Text(stringResource(R.string.spotify_public_link_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { viewModel.previewPublicPlaylist(publicPlaylistLink) },
                    enabled = publicPlaylistLink.isNotBlank() && !state.isLoadingPublicPlaylist,
                ) {
                    Text(stringResource(R.string.spotify_preview_playlist))
                }
                if (state.isLoadingPublicPlaylist) {
                    CircularProgressIndicator()
                }
                state.publicPlaylist?.let { playlist ->
                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(stringResource(R.string.spotify_public_tracks_found, playlist.tracks.size))
                    if (playlist.mayBeTruncated) {
                        Text(
                            text = stringResource(R.string.spotify_public_preview_limit),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }

    importProgress?.let { progress ->
        val isFinished = progress.isFinished
        DefaultDialog(
            onDismiss = {
                if (isFinished) {
                    viewModel.dismissImportProgress()
                } else {
                    viewModel.cancelImport()
                }
            },
            title = {
                Text(
                    text = if (isFinished) {
                        stringResource(R.string.spotify_import_complete)
                    } else {
                        stringResource(R.string.spotify_import_in_progress)
                    }
                )
            },
            buttons = {
                if (isFinished) {
                    Button(
                        onClick = {
                            viewModel.dismissImportProgress()
                            progress.playlistId?.let { navController.navigate("local_playlist/$it") }
                        },
                        shape = CircleShape
                    ) {
                        Text(stringResource(R.string.spotify_open_imported_playlist))
                    }
                } else {
                    TextButton(onClick = { viewModel.cancelImport() }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.playlists) + ": " + progress.playlistName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (isFinished) {
                        stringResource(R.string.spotify_imported_songs_count, progress.currentSongIndex, progress.totalSongs)
                    } else {
                        stringResource(R.string.spotify_importing_songs_count, progress.currentSongIndex, progress.totalSongs)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinearProgressIndicator(
                    progress = { progress.percent },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
    state.errorMessage?.let { error ->
        DefaultDialog(
            onDismiss = { viewModel.dismissError() },
            title = { Text(stringResource(R.string.error_title)) },
            buttons = {
                TextButton(onClick = { viewModel.dismissError() }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        ) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showPlaylistsSheet) {
        SpotifyPlaylistBottomSheet(
            onDismiss = { showPlaylistsSheet = false },
            viewModel = viewModel
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpotifyLoginSheet(
    onDismiss: () -> Unit,
    onCookiesCaptured: (spDc: String, spKey: String) -> Unit,
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var captured by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var fallbackTried by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            webView?.destroy()
            webView = null
        }
    }

    // A real window-style login surface instead of a bottom sheet. This keeps the
    // Spotify web session visible and usable like the dedicated login windows in
    // other music clients, especially on tablets and landscape devices.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = stringResource(R.string.spotify_login_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.cancel), maxLines = 1)
                    }
                }

                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(R.string.spotify_waiting_for_login),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(modifier = Modifier.weight(1f), onClick = {
                        pageError = null
                        isLoading = true
                        fallbackTried = false
                        webView?.loadUrl(SpotifyAuth.LOGIN_URL)
                    }) {
                        Text(
                            stringResource(R.string.spotify_reload),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(modifier = Modifier.weight(1f), onClick = { openSpotifyWeb(context) }) {
                        Text(
                            stringResource(R.string.spotify_open_browser_button),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp)),
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            WebView(context).apply {
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                val cookieManager = CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)
                                cookieManager.setAcceptThirdPartyCookies(this, true)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
                                }
                                settings.setSupportZoom(true)
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                settings.javaScriptCanOpenWindowsAutomatically = true
                                settings.setSupportMultipleWindows(false)
                                settings.mediaPlaybackRequiresUserGesture = false
                                settings.userAgentString = SPOTIFY_USER_AGENT
                                if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                                    WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
                                }
                                webChromeClient = WebChromeClient()
                                webViewClient = object : WebViewClient() {
                                    private var loadId = 0

                                    private fun captureCookies(url: String?): Boolean {
                                        if (captured) return true
                                        if (url?.startsWith("https://open.spotify.com") != true) return false
                                        cookieManager.flush()
                                        val cookiesStr =
                                            cookieManager.getCookie("https://open.spotify.com") ?: ""
                                        val cookies = cookiesStr.split(";").mapNotNull { cookie ->
                                            val parts = cookie.trim().split("=", limit = 2)
                                            if (parts.size != 2) null else parts[0] to parts[1]
                                        }.toMap()
                                        val spDc = cookies["sp_dc"].orEmpty()
                                        if (spDc.isBlank()) return false
                                        captured = true
                                        onCookiesCaptured(spDc, cookies["sp_key"].orEmpty())
                                        return true
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): Boolean {
                                        val uri = request.url
                                        if (uri.scheme !in listOf("http", "https")) {
                                            runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                                                .onFailure { pageError = context.getString(R.string.spotify_could_not_load) }
                                            return true
                                        }
                                        return false
                                    }

                                    override fun onPageStarted(
                                        view: WebView,
                                        url: String?,
                                        favicon: android.graphics.Bitmap?,
                                    ) {
                                        val currentLoad = ++loadId
                                        isLoading = true
                                        pageError = null
                                        captureCookies(url)
                                        view.postDelayed({
                                            if (webView === view && currentLoad == loadId && isLoading && !captured) {
                                                if (!fallbackTried) {
                                                    fallbackTried = true
                                                    view.loadUrl(SpotifyAuth.LOGIN_FALLBACK_URL)
                                                } else {
                                                    isLoading = false
                                                    pageError = context.getString(R.string.spotify_could_not_load)
                                                }
                                            }
                                        }, 20_000)
                                    }

                                    override fun onPageFinished(view: WebView, url: String?) {
                                        isLoading = false
                                        if (captureCookies(url)) return
                                        // Spotify sometimes completes a navigation but leaves
                                        // an empty document in Android WebView. Try the other
                                        // login URL once, then show a useful error.
                                        view.postDelayed({
                                            if (webView !== view || captured || pageError != null || view.url != url) return@postDelayed
                                            view.evaluateJavascript(
                                                SPOTIFY_PAGE_HAS_VISIBLE_CONTENT_JS,
                                            ) { hasVisibleContent ->
                                                if (hasVisibleContent?.toIntOrNull() == 0 && webView === view && !captured && pageError == null) {
                                                    if (!fallbackTried) {
                                                        fallbackTried = true
                                                        isLoading = true
                                                        view.loadUrl(SpotifyAuth.LOGIN_FALLBACK_URL)
                                                    } else {
                                                        pageError = context.getString(R.string.spotify_blank_page)
                                                    }
                                                }
                                            }
                                        }, 12_000)
                                    }

                                    override fun onReceivedHttpError(
                                        view: WebView,
                                        request: WebResourceRequest,
                                        errorResponse: WebResourceResponse,
                                    ) {
                                        if (request.isForMainFrame) {
                                            isLoading = false
                                            pageError = context.getString(
                                                R.string.spotify_http_error,
                                                errorResponse.statusCode,
                                            )
                                        }
                                    }

                                    override fun onReceivedError(
                                        view: WebView,
                                        request: WebResourceRequest,
                                        error: WebResourceError,
                                    ) {
                                        if (request.isForMainFrame) {
                                            isLoading = false
                                            pageError = error.description?.toString()
                                                ?: context.getString(R.string.spotify_could_not_load)
                                        }
                                    }
                                }
                                webView = this
                                loadUrl(SpotifyAuth.LOGIN_URL)
                            }
                        },
                        update = { view -> webView = view },
                    )

                    if (isLoading && pageError == null) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
                        )
                    }
                    pageError?.let { message ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = {
                                pageError = null
                                isLoading = true
                                fallbackTried = false
                                webView?.loadUrl(SpotifyAuth.LOGIN_URL)
                            }) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }
            }
        }
    }
}
