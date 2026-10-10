/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.ui.screens

import app.xan.music.ui.utils.appTopBarWindowInsets
import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.xan.music.ui.utils.rememberHeroZoom
import app.xan.music.ui.utils.heroPullZoom
import app.xan.music.ui.utils.listOverscroll
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEachReversed
import androidx.activity.compose.LocalActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.navigation.NavController
import com.music.innertube.utils.parseCookieString
import app.xan.music.ui.utils.bounceClick
import app.xan.music.ui.utils.combinedBounceClick
import app.xan.music.LocalDatabase
import app.xan.music.LocalPlayerAwareWindowInsets
import app.xan.music.LocalPlayerConnection
import app.xan.music.R
import app.xan.music.constants.HistorySource
import app.xan.music.constants.InnerTubeCookieKey
import app.xan.music.extensions.metadata
import app.xan.music.extensions.toMediaItem
import app.xan.music.models.toMediaMetadata
import app.xan.music.playback.queues.ListQueue
import app.xan.music.playback.queues.YouTubeQueue
import app.xan.music.ui.component.ChipsRow
import app.xan.music.ui.component.HideOnScrollFAB
import app.xan.music.ui.component.IconButton
import app.xan.music.ui.component.LargeScreenTitle
import app.xan.music.ui.component.LocalMenuState
import app.xan.music.ui.component.NavigationTitle
import app.xan.music.ui.component.SongListItem
import app.xan.music.ui.component.YouTubeListItem
import app.xan.music.ui.component.GlassCircleButton
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import app.xan.music.ui.component.HeroBackground
import app.xan.music.ui.component.rememberHeroSource
import app.xan.music.ui.component.rememberHeroTint
import app.xan.music.ui.theme.AppleTokens
import app.xan.music.ui.theme.HeroTintedContent
import app.xan.music.ui.component.GlassComponent
import app.xan.music.ui.component.LocalGlassEffectConfig
import app.xan.music.ui.component.isGlassAllowed
import app.xan.music.ui.component.liquidGlass
import app.xan.music.ui.component.shapes.ContinuousRoundedRectangle
import app.xan.music.ui.component.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import app.xan.music.ui.menu.SelectionMediaMetadataMenu
import app.xan.music.ui.menu.SongMenu
import app.xan.music.ui.menu.YouTubeSongMenu
import app.xan.music.ui.utils.backToMain
import app.xan.music.utils.listItemShape
import app.xan.music.utils.rememberPreference
import app.xan.music.viewmodels.DateAgo
import app.xan.music.viewmodels.HistoryViewModel
import java.time.format.DateTimeFormatter

@SuppressLint("LocalContextGetResourceValueCall")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    navController: NavController,
    viewModel: HistoryViewModel = hiltViewModel(LocalActivity.current as ViewModelStoreOwner),
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    var inSelectMode by rememberSaveable { mutableStateOf(false) }
    val selection = rememberSaveable(
        saver = listSaver<MutableList<Long>, Long>(
            save = { it.toList() },
            restore = { it.toMutableStateList() }
        )
    ) { mutableStateListOf() }
    val onExitSelectionMode = {
        inSelectMode = false
        selection.clear()
    }

    val query by viewModel.searchQuery.collectAsState()
    if (query.isNotEmpty() && !inSelectMode) {
        BackHandler { viewModel.searchQuery.value = "" }
    } else if (inSelectMode) {
        BackHandler(onBack = onExitSelectionMode)
    }

    val historySource by viewModel.historySource.collectAsState()

    val filteredRemoteContent by viewModel.filteredRemoteContent.collectAsState()
    val filteredEvents by viewModel.filteredEvents.collectAsState()
    val allEvents by viewModel.filteredFlatEvents.collectAsState()

    val innerTubeCookie by rememberPreference(InnerTubeCookieKey, "")
    val isLoggedIn = remember(innerTubeCookie) {
        "SAPISID" in parseCookieString(innerTubeCookie)
    }

    @SuppressLint("LocalContextGetResourceValueCall")
    fun dateAgoToString(dateAgo: DateAgo): String {
        return when (dateAgo) {
            DateAgo.Today -> context.getString(R.string.today)
            DateAgo.Yesterday -> context.getString(R.string.yesterday)
            DateAgo.ThisWeek -> context.getString(R.string.this_week)
            DateAgo.LastWeek -> context.getString(R.string.last_week)
            is DateAgo.Other -> dateAgo.date.format(DateTimeFormatter.ofPattern("yyyy/MM"))
        }
    }

    LaunchedEffect(allEvents) {
        selection.fastForEachReversed { eventId ->
            if (allEvents.find { it.event.id == eventId } == null) {
                selection.remove(eventId)
            }
        }
    }

    val lazyListState = rememberLazyListState()

    val heroUrl = remember(filteredRemoteContent, filteredEvents) {
        if (historySource == HistorySource.REMOTE) {
            filteredRemoteContent?.firstOrNull()?.songs?.firstOrNull()?.thumbnail
        } else {
            filteredEvents.values.firstOrNull()?.firstOrNull()?.song?.song?.thumbnailUrl
        }
    }
    val heroSource = rememberHeroSource(
        staticArt = heroUrl,
        songs = if (historySource == HistorySource.LOCAL) {
            allEvents.map { it.song.song.thumbnailUrl to false }
        } else emptyList()
    )
    val tint = rememberHeroTint(heroUrl)
    val onTint = AppleTokens.onColor(tint)

    val glassConfig = LocalGlassEffectConfig.current
    val useGlass = glassConfig.isEnabledFor(GlassComponent.NAV_BAR) && isGlassAllowed()
    val heroBackdrop = rememberLayerBackdrop()

    val heroZoom = rememberHeroZoom()

    HeroBackground(
        tint = tint,
        heroSource = heroSource,
        blurArtwork = true,
        fullBlur = true,
        bottomGradient = true,
        heroScale = heroZoom.scale,
        modifier = Modifier.fillMaxSize(),
    ) {
      HeroTintedContent(tint = tint, backdrop = heroBackdrop) {
        val chromeShape = ContinuousRoundedRectangle(percent = 50)
        
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = lazyListState,
                // No bounce here: the top pull drives the hero zoom instead.
                overscrollEffect = heroZoom.listOverscroll(),
                modifier = Modifier.heroPullZoom(heroZoom),
                contentPadding = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                    .asPaddingValues(),
            ) {
                item(key = "history_header") {
                    LargeScreenTitle(
                        title = stringResource(R.string.history),
                        color = onTint,
                    )
                }

                item(key = "chips_row") {
                    ChipsRow(
                        chips = if (isLoggedIn) listOf(
                            HistorySource.LOCAL to stringResource(R.string.local_history),
                            HistorySource.REMOTE to stringResource(R.string.remote_history),
                        ) else {
                            listOf(HistorySource.LOCAL to stringResource(R.string.local_history))
                        },
                        currentValue = historySource,
                        onValueUpdate = {
                            viewModel.historySource.value = it
                            if (it == HistorySource.REMOTE){
                                viewModel.fetchRemoteHistory()
                            }
                        }
                    )
                }

                if (historySource == HistorySource.REMOTE && isLoggedIn) {
                    filteredRemoteContent?.forEach { section ->
                        stickyHeader(contentType = "date_header") {
                            NavigationTitle(
                                title = section.title,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(tint.copy(alpha = 0.8f))
                            )
                        }

                        // Keyed by identity, not position. The old key embedded the row
                        // index, so every keystroke in the search box shifted the indices
                        // and re-keyed the entire list — Compose threw away and rebuilt
                        // every row's state on each character typed. The occurrence
                        // counter only disambiguates a song that genuinely appears twice
                        // in one section, which is what keeps the key unique without
                        // making it move when the list is filtered.
                        // Plain val, not remember: this runs in LazyListScope, which is not
                        // a composable context. One pass over the section when the item
                        // provider is built.
                        val songKeys = buildList(section.songs.size) {
                            val seen = HashMap<String, Int>()
                            section.songs.forEach { song ->
                                val occurrence = seen.merge(song.id, 1, Int::plus)!! - 1
                                add("${section.title}_${song.id}_$occurrence")
                            }
                        }
                        itemsIndexed(
                            items = section.songs,
                            key = { index, _ -> songKeys[index] },
                            contentType = { _, _ -> "history_song" },
                        ) { index, song ->
                            YouTubeListItem(
                                item = song,
                                isActive = song.id == mediaMetadata?.id,
                                isPlaying = isPlaying,
                                shape = listItemShape(index, section.songs.size),
                                flat = true,
                                trailingContent = {
                                    androidx.compose.material3.IconButton(
                                        onClick = {
                                            menuState.show {
                                                YouTubeSongMenu(
                                                    song = song,
                                                    navController = navController,
                                                    onDismiss = menuState::dismiss,
                                                    onHistoryRemoved = {
                                                        viewModel.fetchRemoteHistory()
                                                    }
                                                )
                                            }
                                        }
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.more_vert),
                                            contentDescription = null
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedBounceClick(
                                        onClick = {
                                            if (song.id == mediaMetadata?.id) {
                                                playerConnection.togglePlayPause()
                                            } else {
                                                playerConnection.playQueue(
                                                    YouTubeQueue.radio(song.toMediaMetadata())
                                                )
                                            }
                                        },
                                        onLongClick = {
                                            menuState.show {
                                                YouTubeSongMenu(
                                                    song = song,
                                                    navController = navController,
                                                    onDismiss = menuState::dismiss,
                                                    onHistoryRemoved = {
                                                        viewModel.fetchRemoteHistory()
                                                    }
                                                )
                                            }
                                        }
                                    )
                            )
                        }
                    }
                } else {
                    filteredEvents.forEach { (dateAgo, dateEvents) ->
                        stickyHeader(contentType = "date_header") {
                            NavigationTitle(
                                title = dateAgoToString(dateAgo),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(tint.copy(alpha = 0.8f))
                            )
                        }

                        itemsIndexed(
                            items = dateEvents,
                            key = { _, event -> event.event.id },
                            contentType = { _, _ -> "history_event" },
                        ) { index, event ->
                            val onCheckedChange: (Boolean) -> Unit = remember(event.event.id) {
                                { checked ->
                                    if (checked) {
                                        selection.add(event.event.id)
                                    } else {
                                        selection.remove(event.event.id)
                                    }
                                }
                            }

                            SongListItem(
                                song = event.song,
                                isActive = event.song.id == mediaMetadata?.id,
                                isPlaying = isPlaying,
                                showInLibraryIcon = true,
                                showDownloadIcon = false,
                                shape = listItemShape(index, dateEvents.size),
                                flat = true,
                                trailingContent = {
                                    if (inSelectMode) {
                                        Checkbox(
                                            checked = event.event.id in selection,
                                            onCheckedChange = onCheckedChange
                                        )
                                    } else {
                                        androidx.compose.material3.IconButton(
                                            onClick = {
                                                menuState.show {
                                                    SongMenu(
                                                        originalSong = event.song,
                                                        event = event.event,
                                                        navController = navController,
                                                        onDismiss = menuState::dismiss
                                                    )
                                                }
                                            }
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.more_vert),
                                                contentDescription = null
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedBounceClick(
                                        onClick = {
                                            if (inSelectMode) {
                                                onCheckedChange(event.event.id !in selection)
                                            } else if (event.song.id == mediaMetadata?.id) {
                                                playerConnection.togglePlayPause()
                                            } else {
                                                playerConnection.playQueue(
                                                    ListQueue(
                                                        title = dateAgoToString(dateAgo),
                                                        items = dateEvents.map { it.song.toMediaItem() },
                                                        startIndex = index
                                                    )
                                                )
                                            }
                                        },
                                        onLongClick = {
                                            if (!inSelectMode) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                inSelectMode = true
                                                onCheckedChange(true)
                                            }
                                        }
                                    )
                            )
                        }
                    }
                }

                item(key = "bottom_spacer_history") {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            HideOnScrollFAB(
                visible = if (historySource == HistorySource.REMOTE) {
                    filteredRemoteContent?.any { it.songs.isNotEmpty() } == true
                } else {
                    allEvents.isNotEmpty()
                },
                lazyListState = lazyListState,
                icon = R.drawable.xan_icon_shuffle,
                onClick = {
                    if (historySource == HistorySource.REMOTE && filteredRemoteContent != null) {
                        val songs = filteredRemoteContent?.flatMap { it.songs } ?: emptyList()
                        if (songs.isNotEmpty()) {
                            playerConnection.playQueue(
                                ListQueue(
                                    title = context.getString(R.string.history),
                                    items = songs.map { it.toMediaItem() }.shuffled()
                                )
                            )
                        }
                    } else {
                        playerConnection.playQueue(
                            ListQueue(
                                title = context.getString(R.string.history),
                                items = allEvents.map { it.song.toMediaItem() }.shuffled()
                            )
                        )
                    }
                }
            )

            // Top bar logic
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(appTopBarWindowInsets())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (inSelectMode) {
                    GlassCircleButton(onClick = onExitSelectionMode) {
                        Icon(
                            painter = painterResource(R.drawable.xan_icon_close),
                            contentDescription = null,
                        )
                    }
                    
                    Text(
                        text = pluralStringResource(R.plurals.n_selected, selection.size, selection.size),
                        style = MaterialTheme.typography.titleMedium,
                        color = onTint,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )

                    Row(
                        modifier = Modifier
                            .height(48.dp)
                            .clip(chromeShape)
                            .background(onTint.copy(alpha = 0.15f), chromeShape)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Checkbox(
                            checked = selection.size == allEvents.size && selection.isNotEmpty(),
                            onCheckedChange = {
                                if (selection.size == allEvents.size) {
                                    selection.clear()
                                } else {
                                    selection.clear()
                                    selection.addAll(allEvents.map { it.event.id })
                                }
                            }
                        )
                        androidx.compose.material3.IconButton(
                            enabled = selection.isNotEmpty(),
                            onClick = {
                                menuState.show {
                                    SelectionMediaMetadataMenu(
                                        songSelection = selection.mapNotNull { eventId ->
                                            allEvents.find { it.event.id == eventId }?.song?.toMediaItem()?.metadata
                                        },
                                        onDismiss = menuState::dismiss,
                                        clearAction = onExitSelectionMode,
                                        currentItems = emptyList()
                                    )
                                }
                            }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.more_vert),
                                contentDescription = null
                            )
                        }
                    }
                } else {
                    GlassCircleButton(
                        onClick = { navController.navigateUp() },
                        onLongClick = { navController.backToMain() },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.xan_icon_back),
                            contentDescription = null
                        )
                    }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(chromeShape)
                            .background(onTint.copy(alpha = 0.15f), chromeShape)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextField(
                            value = query,
                            onValueChange = { viewModel.searchQuery.value = it },
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.search),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = onTint.copy(alpha = 0.6f)
                                )
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleMedium.copy(color = onTint),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                                cursorColor = onTint,
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
      }
    }
}
