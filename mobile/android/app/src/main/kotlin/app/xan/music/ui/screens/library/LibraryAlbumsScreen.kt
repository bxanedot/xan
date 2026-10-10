/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.ui.screens.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import app.xan.music.LocalPlayerAwareWindowInsets
import app.xan.music.LocalPlayerConnection
import app.xan.music.R
import app.xan.music.ui.utils.rememberGridColumns
import app.xan.music.constants.AlbumFilter
import app.xan.music.constants.AlbumFilterKey
import app.xan.music.constants.LocalOnlyModeKey
import app.xan.music.constants.AlbumSortDescendingKey
import app.xan.music.constants.AlbumSortType
import app.xan.music.constants.AlbumSortTypeKey
import app.xan.music.constants.AlbumViewTypeKey
import app.xan.music.constants.CONTENT_TYPE_ALBUM
import app.xan.music.constants.CONTENT_TYPE_HEADER
import app.xan.music.constants.GridItemSize
import app.xan.music.constants.GridItemsSizeKey
import app.xan.music.constants.GridThumbnailHeight
import app.xan.music.constants.HideExplicitKey
import app.xan.music.constants.LibraryIconsOnlyKey
import app.xan.music.constants.LibraryViewType
import app.xan.music.constants.YtmSyncKey
import app.xan.music.ui.component.buildAlphabetSectionIndex
import app.xan.music.ui.component.ListScrollRail
import app.xan.music.ui.component.ChipsRow
import app.xan.music.ui.component.LargeScreenTitle
import app.xan.music.ui.component.EmptyPlaceholder
import app.xan.music.ui.component.LibraryAlbumGridItem
import app.xan.music.ui.component.LibraryAlbumListItem
import app.xan.music.ui.component.LocalMenuState
import app.xan.music.ui.component.SortHeader
import app.xan.music.utils.rememberEnumPreference
import app.xan.music.utils.rememberPreference
import app.xan.music.viewmodels.LibraryAlbumsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.xan.music.ui.theme.AppleTokens
import app.xan.music.ui.utils.heroPullZoom
import app.xan.music.ui.utils.listOverscroll
import app.xan.music.ui.utils.rememberHeroZoom

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibraryAlbumsScreen(
    navController: NavController,
    onDeselect: () -> Unit,
    viewModel: LibraryAlbumsViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    var viewType by rememberEnumPreference(AlbumViewTypeKey, LibraryViewType.GRID)
    var storedFilter by rememberEnumPreference(AlbumFilterKey, AlbumFilter.LIKED)
    val (localOnly) = rememberPreference(LocalOnlyModeKey, false)
    // Mirrors what the view model actually queries while local-only mode is on.
    val filter = if (localOnly) AlbumFilter.LOCAL else storedFilter
    val (sortType, onSortTypeChange) = rememberEnumPreference(
        AlbumSortTypeKey,
        AlbumSortType.CREATE_DATE
    )
    val (sortDescending, onSortDescendingChange) = rememberPreference(AlbumSortDescendingKey, true)
    val gridItemSize by rememberEnumPreference(GridItemsSizeKey, GridItemSize.BIG)
    
    val (libraryIconsOnly) = rememberPreference(LibraryIconsOnlyKey, defaultValue = true)

    val (ytmSync) = rememberPreference(YtmSyncKey, true)
    val hideExplicit by rememberPreference(key = HideExplicitKey, defaultValue = false)

    val filterContent = @Composable {
        Row {
            Spacer(Modifier.width(12.dp))
            FilterChip(
                label = { Text(stringResource(R.string.albums)) },
                selected = true,
                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
                onClick = onDeselect,
                shape = RoundedCornerShape(16.dp),
                leadingIcon = {
                    Icon(painter = painterResource(R.drawable.xan_icon_close), contentDescription = "")
                },
            )
            if (!localOnly) {
                ChipsRow(
                    chips =
                    listOf(
                        AlbumFilter.LIKED to stringResource(R.string.filter_liked),
                        AlbumFilter.LIBRARY to stringResource(R.string.filter_library),
                        AlbumFilter.UPLOADED to stringResource(R.string.filter_uploaded),
                        AlbumFilter.LOCAL to stringResource(R.string.filter_local),
                    ),
                    currentValue = filter,
                    onValueUpdate = {
                        storedFilter = it
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        if (ytmSync) {
            withContext(Dispatchers.IO) {
                viewModel.sync()
            }
        }
    }

    val albums by viewModel.allAlbums.collectAsState()

    val lazyListState = rememberLazyListState()
    val lazyGridState = rememberLazyGridState()
    // Pull-to-refresh only: no hero artwork on this screen, so heroZoom.scale
    // goes unread and the modifier contributes just the rubber-band stretch.
    val heroZoom = rememberHeroZoom()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val scrollToTop =
        backStackEntry?.savedStateHandle?.getStateFlow("scrollToTop", false)?.collectAsState()

    LaunchedEffect(scrollToTop?.value) {
        if (scrollToTop?.value == true) {
            when (viewType) {
                LibraryViewType.LIST -> lazyListState.animateScrollToItem(0)
                LibraryViewType.GRID -> lazyGridState.animateScrollToItem(0)
            }
            backStackEntry?.savedStateHandle?.set("scrollToTop", false)
        }
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
                        AlbumSortType.CREATE_DATE -> R.string.sort_by_create_date
                        AlbumSortType.NAME -> R.string.sort_by_name
                        AlbumSortType.ARTIST -> R.string.sort_by_artist
                        AlbumSortType.YEAR -> R.string.sort_by_year
                        AlbumSortType.SONG_COUNT -> R.string.sort_by_song_count
                        AlbumSortType.LENGTH -> R.string.sort_by_length
                        AlbumSortType.PLAY_TIME -> R.string.sort_by_play_time
                    }
                },
            )

            Spacer(Modifier.weight(1f))

            Text(
                text = pluralStringResource(R.plurals.n_album, albums.size, albums.size),
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

    // Hoisted: both view types filtered and de-duped the same list separately, and the
    // scroll rail needs the resulting count too.
    val visibleAlbums = remember(albums, hideExplicit) {
        (if (hideExplicit) albums.filter { !it.album.explicit } else albums)
            .distinctBy { it.id }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        when (viewType) {
            LibraryViewType.LIST ->
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
                        LargeScreenTitle(title = stringResource(R.string.albums))
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

                    albums.let { albums ->
                        if (albums.isEmpty()) {
                            item(key = "empty_placeholder") {
                                EmptyPlaceholder(
                                    icon = R.drawable.album,
                                    text = stringResource(R.string.library_album_empty),
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }

                        items(
                            items = visibleAlbums,
                            key = { it.id },
                            contentType = { CONTENT_TYPE_ALBUM },
                        ) { album ->
                            LibraryAlbumListItem(
                                navController = navController,
                                menuState = menuState,
                                album = album,
                                isActive = album.id == mediaMetadata?.album?.id,
                                isPlaying = isPlaying,
                                showIconOnly = libraryIconsOnly,
                                modifier = Modifier
                                    .animateItem()
                            )
                        }
                    }
                }

            LibraryViewType.GRID ->
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
                        LargeScreenTitle(title = stringResource(R.string.albums))
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

                    albums.let { albums ->
                        if (albums.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                EmptyPlaceholder(
                                    icon = R.drawable.album,
                                    text = stringResource(R.string.library_album_empty),
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }

                        items(
                            items = visibleAlbums,
                            key = { it.id },
                            contentType = { CONTENT_TYPE_ALBUM },
                        ) { album ->
                            LibraryAlbumGridItem(
                                navController = navController,
                                menuState = menuState,
                                coroutineScope = coroutineScope,
                                album = album,
                                isActive = album.id == mediaMetadata?.album?.id,
                                isPlaying = isPlaying,
                                showIconOnly = libraryIconsOnly,
                                modifier = Modifier
                                    .animateItem()
                            )
                        }
                    }
                }
        }

        ListScrollRail(
            lazyListState = lazyListState,
            lazyGridState = lazyGridState,
            isGrid = viewType == LibraryViewType.GRID,
            itemCount = visibleAlbums.size,
            sectionIndexMap = when (sortType) {
                AlbumSortType.NAME ->
                    remember(visibleAlbums) {
                        buildAlphabetSectionIndex(visibleAlbums) { it.album.title }
                    }

                AlbumSortType.ARTIST ->
                    remember(visibleAlbums) {
                        buildAlphabetSectionIndex(visibleAlbums) { album ->
                            album.artists.firstOrNull()?.name.orEmpty()
                        }
                    }

                else -> null
            },
        )
    }
}
