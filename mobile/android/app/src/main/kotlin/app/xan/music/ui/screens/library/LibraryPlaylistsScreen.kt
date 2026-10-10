/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.ui.screens.library

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.xan.music.LocalDatabase
import app.xan.music.ui.utils.rememberGridColumns
import app.xan.music.constants.SongSortType
import app.xan.music.ui.utils.bounceClick
import app.xan.music.ui.utils.combinedBounceClick
import kotlinx.coroutines.flow.first

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.music.innertube.utils.parseCookieString
import app.xan.music.LocalPlayerAwareWindowInsets
import app.xan.music.R
import app.xan.music.constants.CONTENT_TYPE_HEADER
import app.xan.music.constants.CONTENT_TYPE_PLAYLIST
import app.xan.music.constants.GridItemSize
import app.xan.music.constants.GridItemsSizeKey
import app.xan.music.constants.GridThumbnailHeight
import app.xan.music.constants.InnerTubeCookieKey
import app.xan.music.constants.LibraryIconsOnlyKey
import app.xan.music.constants.LibraryViewType
import app.xan.music.constants.PlaylistSortDescendingKey
import app.xan.music.constants.PlaylistSortType
import app.xan.music.constants.PlaylistSortTypeKey
import app.xan.music.constants.PlaylistViewTypeKey
import app.xan.music.constants.ShowCachedPlaylistKey
import app.xan.music.constants.ShowDownloadedPlaylistKey
import app.xan.music.constants.ShowLikedPlaylistKey
import app.xan.music.constants.ShowTopPlaylistKey
import app.xan.music.constants.ShowUploadedPlaylistKey
import app.xan.music.constants.YtmSyncKey
import app.xan.music.db.entities.Playlist
import app.xan.music.db.entities.PlaylistEntity
import app.xan.music.ui.component.buildAlphabetSectionIndex
import app.xan.music.ui.component.ListScrollRail
import app.xan.music.ui.component.LargeScreenTitle
import app.xan.music.ui.component.CreatePlaylistDialog
import app.xan.music.ui.component.HideOnScrollFAB
import app.xan.music.ui.component.LibraryPlaylistGridItem
import app.xan.music.ui.component.LibraryPlaylistListItem
import app.xan.music.ui.component.LocalMenuState
import app.xan.music.ui.component.PlaylistGridItem
import app.xan.music.ui.component.PlaylistListItem
import app.xan.music.ui.component.SortHeader
import app.xan.music.utils.rememberEnumPreference
import app.xan.music.utils.rememberPreference
import app.xan.music.viewmodels.LibraryPlaylistsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.xan.music.ui.theme.AppleTokens
import app.xan.music.ui.utils.heroPullZoom
import app.xan.music.ui.utils.listOverscroll
import app.xan.music.ui.utils.rememberHeroZoom

private const val AUTO_LIKED_PLAYLIST_ID = "auto_liked"
private const val AUTO_DOWNLOADED_PLAYLIST_ID = "auto_downloaded"
private const val AUTO_TOP_PLAYLIST_ID = "auto_top"
private const val AUTO_CACHED_PLAYLIST_ID = "auto_cached"
private const val AUTO_UPLOADED_PLAYLIST_ID = "auto_uploaded"

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibraryPlaylistsScreen(
    navController: NavController,
    filterContent: @Composable () -> Unit,
    viewModel: LibraryPlaylistsViewModel = hiltViewModel(),
    initialTextFieldValue: String? = null,
    allowSyncing: Boolean = true,
) {
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current

    val coroutineScope = rememberCoroutineScope()

    var viewType by rememberEnumPreference(PlaylistViewTypeKey, LibraryViewType.GRID)
    val (sortType, onSortTypeChange) = rememberEnumPreference(
        PlaylistSortTypeKey,
        PlaylistSortType.CREATE_DATE
    )
    val (sortDescending, onSortDescendingChange) = rememberPreference(
        PlaylistSortDescendingKey,
        true
    )
    val gridItemSize by rememberEnumPreference(GridItemsSizeKey, GridItemSize.BIG)
    
    val (libraryIconsOnly) = rememberPreference(LibraryIconsOnlyKey, defaultValue = false)

    val playlists by viewModel.allPlaylists.collectAsState()

    val topSize by viewModel.topValue.collectAsState(initial = 50)
    val likedThumbnails by viewModel.likedPlaylistThumbnails.collectAsState()
    val downloadedThumbnails by viewModel.downloadedPlaylistThumbnails.collectAsState()
    val uploadedThumbnails by viewModel.uploadedPlaylistThumbnails.collectAsState()
    val topThumbnails by viewModel.topPlaylistThumbnails.collectAsState()
    val cachedThumbnails by viewModel.cachedPlaylistThumbnails.collectAsState()

    val likedPlaylist =
        Playlist(
            playlist = PlaylistEntity(
                id = AUTO_LIKED_PLAYLIST_ID,
                name = stringResource(R.string.liked)
            ),
            songCount = 0,
            songThumbnails = likedThumbnails,
        )

    val downloadPlaylist =
        Playlist(
            playlist = PlaylistEntity(
                id = AUTO_DOWNLOADED_PLAYLIST_ID,
                name = stringResource(R.string.offline)
            ),
            songCount = 0,
            songThumbnails = downloadedThumbnails,
        )

    val topPlaylist =
        Playlist(
            playlist = PlaylistEntity(
                id = AUTO_TOP_PLAYLIST_ID,
                name = stringResource(R.string.my_top) + " $topSize"
            ),
            songCount = 0,
            songThumbnails = topThumbnails,
        )

    val cachePlaylist =
        Playlist(
            playlist = PlaylistEntity(
                id = AUTO_CACHED_PLAYLIST_ID,
                name = stringResource(R.string.cached_playlist)
            ),
            songCount = 0,
            songThumbnails = cachedThumbnails,
        )
        
    val uploadedPlaylist =
        Playlist(
            playlist = PlaylistEntity(
                id = AUTO_UPLOADED_PLAYLIST_ID,
                name = stringResource(R.string.uploaded_playlist)
            ),
            songCount = 0,
            songThumbnails = uploadedThumbnails,
        )

    val (showLiked) = rememberPreference(ShowLikedPlaylistKey, true)
    val (showDownloaded) = rememberPreference(ShowDownloadedPlaylistKey, true)
    val (showTop) = rememberPreference(ShowTopPlaylistKey, true)
    val (showCached) = rememberPreference(ShowCachedPlaylistKey, true)
    val (showUploaded) = rememberPreference(ShowUploadedPlaylistKey, true)

    val lazyListState = rememberLazyListState()
    val lazyGridState = rememberLazyGridState()
    // Pull-to-refresh only: no hero artwork on this screen, so heroZoom.scale
    // goes unread and the modifier contributes just the rubber-band stretch.
    val heroZoom = rememberHeroZoom()


    val backStackEntry by navController.currentBackStackEntryAsState()
    val scrollToTop =
        backStackEntry?.savedStateHandle?.getStateFlow("scrollToTop", false)?.collectAsState()

    val (innerTubeCookie) = rememberPreference(InnerTubeCookieKey, "")
    val isLoggedIn = remember(innerTubeCookie) {
        "SAPISID" in parseCookieString(innerTubeCookie)
    }

    val (ytmSync) = rememberPreference(YtmSyncKey, true)

    LaunchedEffect(Unit) {
        if (ytmSync) {
            withContext(Dispatchers.IO) {
                viewModel.sync()
            }
        }
    }

    LaunchedEffect(scrollToTop?.value) {
        if (scrollToTop?.value == true) {
            when (viewType) {
                LibraryViewType.LIST -> lazyListState.animateScrollToItem(0)
                LibraryViewType.GRID -> lazyGridState.animateScrollToItem(0)
            }
            backStackEntry?.savedStateHandle?.set("scrollToTop", false)
        }
    }

    var showCreatePlaylistDialog by rememberSaveable { mutableStateOf(false) }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            initialTextFieldValue = initialTextFieldValue,
            allowSyncing = allowSyncing,
            onPlaylistCreated = { playlistId ->
                showCreatePlaylistDialog = false
                navController.navigate("local_playlist/$playlistId")
            }
        )
    }

    val headerContent = @Composable {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = AppleTokens.Gutter),
        ) {
            SortHeader(
                sortType = sortType,
                sortDescending = sortDescending,
                onSortTypeChange = onSortTypeChange,
                onSortDescendingChange = onSortDescendingChange,
                sortTypeText = { sortType ->
                    when (sortType) {
                        PlaylistSortType.CREATE_DATE -> R.string.sort_by_create_date
                        PlaylistSortType.NAME -> R.string.sort_by_name
                        PlaylistSortType.SONG_COUNT -> R.string.sort_by_song_count
                        PlaylistSortType.LAST_UPDATED -> R.string.sort_by_last_updated
                    }
                },
            )

            Spacer(Modifier.weight(1f))

            Text(
                text = pluralStringResource(
                    R.plurals.n_playlist,
                    playlists.size,
                    playlists.size
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
            )

            FlowRow(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                LibraryViewType.entries.forEachIndexed { index, type ->
                    ToggleButton(
                        checked = viewType == type,
                        onCheckedChange = { viewType = type },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            LibraryViewType.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        modifier = Modifier.semantics { role = Role.RadioButton },
                    ) {
                        Icon(
                            painter = painterResource(
                                when (type) {
                                    LibraryViewType.LIST -> R.drawable.list
                                    LibraryViewType.GRID -> R.drawable.grid_view
                                }
                            ),
                            contentDescription = null,
                        )
                    }
                }
            }
        }
    }

    // Hoisted: both view types de-duped the same list separately, and the scroll rail
    // needs the resulting count too.
    val visiblePlaylists = remember(playlists) { playlists.distinctBy { it.id } }

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        when (viewType) {
            LibraryViewType.LIST -> {
                LazyColumn(
                    state = lazyListState,
                    overscrollEffect = heroZoom.listOverscroll(),
                    modifier = Modifier.heroPullZoom(heroZoom),
                    contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
                ) {
                    item(
                        key = "title",
                        contentType = CONTENT_TYPE_HEADER,
                    ) {
                        LargeScreenTitle(title = stringResource(R.string.playlists))
                    }

                    item(
                        key = "filter",
                        contentType = CONTENT_TYPE_HEADER,
                    ) {
                        filterContent()
                    }

                    item(
                        key = "header",
                        contentType = CONTENT_TYPE_HEADER,
                    ) {
                        headerContent()
                    }

                    if (showLiked) {
                        item(
                            key = "likedPlaylist",
                            contentType = { CONTENT_TYPE_PLAYLIST },
                        ) {
                            AutoPlaylistCard(
                                playlist = likedPlaylist,
                                grid = false,
                                onClick = { navController.navigate("auto_playlist/liked") },
                                showIconOnly = libraryIconsOnly,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }

                    if (showDownloaded) {
                        item(
                            key = "downloadedPlaylist",
                            contentType = { CONTENT_TYPE_PLAYLIST },
                        ) {
                            AutoPlaylistCard(
                                playlist = downloadPlaylist,
                                grid = false,
                                onClick = { navController.navigate("auto_playlist/downloaded") },
                                showIconOnly = libraryIconsOnly,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }

                    if (showTop) {
                        item(
                            key = "TopPlaylist",
                            contentType = { CONTENT_TYPE_PLAYLIST },
                        ) {
                            AutoPlaylistCard(
                                playlist = topPlaylist,
                                grid = false,
                                onClick = { navController.navigate("top_playlist/$topSize") },
                                showIconOnly = libraryIconsOnly,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }

                    if (showCached) {
                        item(
                            key = "cachePlaylist",
                            contentType = { CONTENT_TYPE_PLAYLIST },
                        ) {
                            AutoPlaylistCard(
                                playlist = cachePlaylist,
                                grid = false,
                                onClick = { navController.navigate("cache_playlist/cached") },
                                showIconOnly = libraryIconsOnly,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                    
                    if (showUploaded) {
                        item(
                            key = "uploadedPlaylist",
                            contentType = { CONTENT_TYPE_PLAYLIST },
                        ) {
                            AutoPlaylistCard(
                                playlist = uploadedPlaylist,
                                grid = false,
                                onClick = { navController.navigate("auto_playlist/uploaded") },
                                showIconOnly = libraryIconsOnly,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }

                    playlists.let { playlists ->
                        if (playlists.isEmpty()) {
                            item(key = "empty_placeholder") {
                            }
                        }

                        items(
                            items = visiblePlaylists,
                            key = { it.id },
                            contentType = { CONTENT_TYPE_PLAYLIST },
                        ) { playlist ->
                            LibraryPlaylistListItem(
                                navController = navController,
                                menuState = menuState,
                                coroutineScope = coroutineScope,
                                playlist = playlist,
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }

                HideOnScrollFAB(
                    lazyListState = lazyListState,
                    icon = R.drawable.add,
                    onClick = {
                        showCreatePlaylistDialog = true
                    },
                )
            }

            LibraryViewType.GRID -> {
                LazyVerticalGrid(
                    state = lazyGridState,
                    overscrollEffect = heroZoom.listOverscroll(),
                    modifier = Modifier.heroPullZoom(heroZoom),
                    columns = rememberGridColumns(),
                    contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
                ) {
                    item(
                        key = "title",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = CONTENT_TYPE_HEADER,
                    ) {
                        LargeScreenTitle(title = stringResource(R.string.playlists))
                    }

                    item(
                        key = "filter",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = CONTENT_TYPE_HEADER,
                    ) {
                        filterContent()
                    }

                    item(
                        key = "header",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = CONTENT_TYPE_HEADER,
                    ) {
                        headerContent()
                    }

                    if (showLiked) {
                        item(
                            key = "likedPlaylist",
                            contentType = { CONTENT_TYPE_PLAYLIST },
                        ) {
                            AutoPlaylistCard(
                                playlist = likedPlaylist,
                                grid = true,
                                onClick = { navController.navigate("auto_playlist/liked") },
                                showIconOnly = libraryIconsOnly,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }

                    if (showDownloaded) {
                        item(
                            key = "downloadedPlaylist",
                            contentType = { CONTENT_TYPE_PLAYLIST },
                        ) {
                            AutoPlaylistCard(
                                playlist = downloadPlaylist,
                                grid = true,
                                onClick = { navController.navigate("auto_playlist/downloaded") },
                                showIconOnly = libraryIconsOnly,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }

                    if (showTop) {
                        item(
                            key = "TopPlaylist",
                            contentType = { CONTENT_TYPE_PLAYLIST },
                        ) {
                            AutoPlaylistCard(
                                playlist = topPlaylist,
                                grid = true,
                                onClick = { navController.navigate("top_playlist/$topSize") },
                                showIconOnly = libraryIconsOnly,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }

                    if (showCached) {
                        item(
                            key = "cachePlaylist",
                            contentType = { CONTENT_TYPE_PLAYLIST },
                        ) {
                            AutoPlaylistCard(
                                playlist = cachePlaylist,
                                grid = true,
                                onClick = { navController.navigate("cache_playlist/cached") },
                                showIconOnly = libraryIconsOnly,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }

                    if (showUploaded) {
                        item(
                            key = "uploadedPlaylist",
                            contentType = { CONTENT_TYPE_PLAYLIST },
                        ) {
                            AutoPlaylistCard(
                                playlist = uploadedPlaylist,
                                grid = true,
                                onClick = { navController.navigate("auto_playlist/uploaded") },
                                showIconOnly = libraryIconsOnly,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }

                    playlists.let { playlists ->
                        if (playlists.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                            }
                        }

                        items(
                            items = visiblePlaylists,
                            key = { it.id },
                            contentType = { CONTENT_TYPE_PLAYLIST },
                        ) { playlist ->
                            LibraryPlaylistGridItem(
                                navController = navController,
                                menuState = menuState,
                                coroutineScope = coroutineScope,
                                playlist = playlist,
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }

                HideOnScrollFAB(
                    lazyListState = lazyGridState,
                    icon = R.drawable.add,
                    onClick = {
                        showCreatePlaylistDialog = true
                    },
                )
            }
        }

        ListScrollRail(
            lazyListState = lazyListState,
            lazyGridState = lazyGridState,
            isGrid = viewType == LibraryViewType.GRID,
            itemCount = visiblePlaylists.size,
            sectionIndexMap = if (sortType == PlaylistSortType.NAME) {
                remember(visiblePlaylists) {
                    buildAlphabetSectionIndex(visiblePlaylists) { it.playlist.name }
                }
            } else {
                null
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AutoPlaylistCard(
    playlist: Playlist,
    grid: Boolean,
    onClick: () -> Unit,
    showIconOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var customUri by rememberPreference(stringPreferencesKey("thumbnail_${playlist.id}"), "")

    // Let PlaylistGridItem/PlaylistListItem build the same multi-artwork cover
    // style used by YouTube Music from the collection's actual track thumbnails.
    // Only a user-picked custom image overrides that generated cover.
    val override = customUri.takeIf { it.isNotBlank() }

    var menuOpen by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            customUri = uri.toString()
        }
    }

    val clickMod = Modifier
        .fillMaxWidth()
        .combinedBounceClick(
            onClick = onClick,
            onLongClick = { menuOpen = true },
        )

    Box(modifier) {
        if (grid) {
            PlaylistGridItem(
                playlist = playlist,
                fillMaxWidth = true,
                autoPlaylist = true,
                showIconOnly = showIconOnly,
                thumbnailOverrideUrl = override,
                modifier = clickMod,
            )
        } else {
            PlaylistListItem(
                playlist = playlist,
                autoPlaylist = true,
                showIconOnly = showIconOnly,
                thumbnailOverrideUrl = override,
                modifier = clickMod,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.change_card_image)) },
                onClick = {
                    menuOpen = false
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
            )
            if (override != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.remove_custom_image)) },
                    onClick = {
                        menuOpen = false
                        customUri = ""
                    },
                )
            }
        }
    }
}
