/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.ui.screens.artist

import app.xan.music.ui.utils.appTopBarWindowInsets
import androidx.compose.foundation.ExperimentalFoundationApi

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import app.xan.music.LocalPlayerAwareWindowInsets
import app.xan.music.LocalPlayerConnection
import app.xan.music.R
import app.xan.music.constants.ArtistSongSortDescendingKey
import app.xan.music.constants.ArtistSongSortType
import app.xan.music.constants.ArtistSongSortTypeKey
import app.xan.music.constants.CONTENT_TYPE_HEADER
import app.xan.music.constants.HideExplicitKey
import app.xan.music.extensions.toMediaItem
import app.xan.music.playback.queues.ListQueue
import app.xan.music.ui.component.buildAlphabetSectionIndex
import app.xan.music.ui.component.ListScrollRail
import app.xan.music.ui.component.HideOnScrollFAB
import app.xan.music.ui.component.IconButton
import app.xan.music.ui.component.LocalMenuState
import app.xan.music.ui.component.SongListItem
import app.xan.music.ui.component.SortHeader
import app.xan.music.ui.menu.SongMenu
import app.xan.music.ui.utils.backToMain
import app.xan.music.ui.utils.bounceClick
import app.xan.music.ui.utils.combinedBounceClick
import app.xan.music.utils.listItemShape
import app.xan.music.utils.rememberEnumPreference
import app.xan.music.utils.rememberPreference
import app.xan.music.viewmodels.ArtistSongsViewModel

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ArtistSongsScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: ArtistSongsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val (sortType, onSortTypeChange) = rememberEnumPreference(
        ArtistSongSortTypeKey,
        ArtistSongSortType.CREATE_DATE
    )
    val (sortDescending, onSortDescendingChange) = rememberPreference(
        ArtistSongSortDescendingKey,
        true
    )
    val hideExplicit by rememberPreference(key = HideExplicitKey, defaultValue = false)
    val artist by viewModel.artist.collectAsState()
    val songs by viewModel.songs.collectAsState()
    val lazyListState = rememberLazyListState()

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        ) {
            item(
                key = "header",
                contentType = CONTENT_TYPE_HEADER,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    SortHeader(
                        sortType = sortType,
                        sortDescending = sortDescending,
                        onSortTypeChange = onSortTypeChange,
                        onSortDescendingChange = onSortDescendingChange,
                        sortTypeText = { sortType ->
                            when (sortType) {
                                ArtistSongSortType.CREATE_DATE -> R.string.sort_by_create_date
                                ArtistSongSortType.NAME -> R.string.sort_by_name
                                ArtistSongSortType.PLAY_TIME -> R.string.sort_by_play_time
                            }
                        },
                    )

                    Spacer(Modifier.weight(1f))

                    Text(
                        text = pluralStringResource(R.plurals.n_song, songs.size, songs.size),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            itemsIndexed(
                items = songs,
                key = { _, item -> item.id },
            ) { index, song ->
                SongListItem(
                    song = song,
                    showInLibraryIcon = true,
                    isActive = song.id == mediaMetadata?.id,
                    isPlaying = isPlaying,
                    shape = listItemShape(index, songs.size),
                    trailingContent = {
                        IconButton(
                            onClick = {
                                menuState.show {
                                    SongMenu(
                                        originalSong = song,
                                        navController = navController,
                                        onDismiss = menuState::dismiss,
                                    )
                                }
                            },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.more_vert),
                                contentDescription = null,
                            )
                        }
                    },
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .combinedBounceClick(
                            onClick = {
                                if (song.id == mediaMetadata?.id) {
                                    playerConnection.togglePlayPause()
                                } else {
                                    playerConnection.playQueue(
                                        ListQueue(
                                            title = context.getString(R.string.queue_all_songs),
                                            items = songs.map { it.toMediaItem() },
                                            startIndex = index,
                                        ),
                                    )
                                }
                            },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuState.show {
                                    SongMenu(
                                        originalSong = song,
                                        navController = navController,
                                        onDismiss = menuState::dismiss,
                                    )
                                }
                            },
                        )
                        .animateItem(),
                )
            }
        }

        TopAppBar(
            windowInsets = appTopBarWindowInsets(),
            title = { Text(artist?.artist?.name.orEmpty()) },
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
        )

        ListScrollRail(
            lazyListState = lazyListState,
            itemCount = songs.size,
            sectionIndexMap = if (sortType == ArtistSongSortType.NAME) {
                remember(songs) { buildAlphabetSectionIndex(songs) { it.title } }
            } else {
                null
            },
        )

        HideOnScrollFAB(
            lazyListState = lazyListState,
            icon = R.drawable.xan_icon_shuffle,
            onClick = {
                playerConnection.playQueue(
                    ListQueue(
                        title = artist?.artist?.name,
                        items = songs.shuffled().map { it.toMediaItem() },
                    ),
                )
            },
        )
    }
}
