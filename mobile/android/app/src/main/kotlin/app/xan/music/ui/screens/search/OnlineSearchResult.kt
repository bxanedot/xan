/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.ui.screens.search

import app.xan.music.ui.utils.appTopBarWindowInsets
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.music.innertube.YouTube.SearchFilter.Companion.FILTER_ALBUM
import com.music.innertube.YouTube.SearchFilter.Companion.FILTER_ARTIST
import com.music.innertube.YouTube.SearchFilter.Companion.FILTER_COMMUNITY_PLAYLIST
import com.music.innertube.YouTube.SearchFilter.Companion.FILTER_FEATURED_PLAYLIST
import com.music.innertube.YouTube.SearchFilter.Companion.FILTER_SONG
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.WatchEndpoint
import com.music.innertube.models.YTItem
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import app.xan.music.ui.utils.bounceClick
import app.xan.music.ui.utils.combinedBounceClick
import app.xan.music.LocalPlayerConnection
import app.xan.music.LocalExpandPlayer
import app.xan.music.R
import app.xan.music.constants.MiniPlayerBottomSpacing
import app.xan.music.constants.MiniPlayerHeight
import app.xan.music.constants.NavigationBarHeight
import app.xan.music.constants.VideoSearchFallbackKey
import app.xan.music.models.toMediaMetadata
import app.xan.music.playback.queues.YouTubeQueue
import app.xan.music.ui.component.ChipsRow
import app.xan.music.ui.component.EmptyPlaceholder
import app.xan.music.ui.component.LocalMenuState
import app.xan.music.ui.component.LocalNavSearchState
import app.xan.music.ui.component.NavigationTitle
import app.xan.music.ui.component.YouTubeListItem
import app.xan.music.ui.component.HomeImageBackground
import app.xan.music.ui.component.hasCustomHomeBackground
import app.xan.music.ui.component.rememberAppBackgroundColor
import app.xan.music.ui.component.rememberHeroTint
import app.xan.music.ui.theme.AppleTokens
import app.xan.music.ui.theme.HeroTintedContent
import app.xan.music.ui.component.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.xan.music.ui.component.shimmer.ListItemPlaceHolder
import app.xan.music.ui.component.shimmer.ShimmerHost
import app.xan.music.ui.menu.YouTubeAlbumMenu
import app.xan.music.ui.menu.YouTubeArtistMenu
import app.xan.music.ui.menu.YouTubePlaylistMenu
import app.xan.music.ui.menu.YouTubeSongMenu
import app.xan.music.utils.listItemShape
import app.xan.music.utils.rememberPreference
import app.xan.music.viewmodels.OnlineSearchViewModel
import app.xan.music.video.buildExternalVideoQueue
import com.music.innertube.ExternalVideoSearchResult
import app.xan.music.video.autoPlaybackFormat
import kotlinx.coroutines.launch
import java.net.URLDecoder
import app.xan.music.ui.utils.heroPullZoom
import app.xan.music.ui.utils.listOverscroll
import app.xan.music.ui.utils.rememberHeroZoom
import android.widget.Toast

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OnlineSearchResult(
    navController: NavController,
    viewModel: OnlineSearchViewModel = hiltViewModel(),
    pureBlack: Boolean = false,
    queryOverride: String? = null,
) {
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val expandPlayer = LocalExpandPlayer.current
    val (videoOnlySearch) = rememberPreference(VideoSearchFallbackKey, false)
    val haptic = LocalHapticFeedback.current
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    // Pull-to-refresh only: no hero artwork here, so heroZoom.scale goes unread
    // and the modifier contributes just the rubber-band stretch.
    val heroZoom = rememberHeroZoom()

    // The nav bar owns the actual search text field/keyboard now (see
    // NavBarSearchInputBar in FloatingNavBar.kt) — this screen just reads the
    // live query to filter/display results.
    val navSearch = LocalNavSearchState.current

    // Deep-link safety net: if this screen was reached without going through
    // the nav bar's own field first (e.g. a direct navigate to search/{query}
    // from search history elsewhere), seed the shared query from the nav arg.
    val encodedQuery = navController.currentBackStackEntry?.arguments?.getString("query") ?: ""
    val decodedQuery = remember(encodedQuery) {
        try {
            URLDecoder.decode(encodedQuery, "UTF-8")
        } catch (e: Exception) {
            encodedQuery
        }
    }
    LaunchedEffect(queryOverride, decodedQuery) {
        if (queryOverride == null && navSearch.query.text != decodedQuery) {
            navSearch.onQueryChange(TextFieldValue(decodedQuery, TextRange(decodedQuery.length)))
        }
    }
    LaunchedEffect(queryOverride, navSearch.query.text) {
        val currentQuery = queryOverride ?: navSearch.query.text
        if (currentQuery.isNotBlank()) viewModel.updateQuery(currentQuery)
    }

    val searchFilter by viewModel.filter.collectAsState()
    val searchSummary = viewModel.summaryPage
    val itemsPage by remember(searchFilter) {
        derivedStateOf {
            searchFilter?.value?.let {
                viewModel.viewStateMap[it]
            }
        }
    }
    val isSearchLoading = if (searchFilter == null) {
        viewModel.summaryLoading
    } else {
        searchFilter?.value?.let { viewModel.loadingFilters[it] } ?: true
    }
    val searchFailed = if (searchFilter == null) {
        viewModel.summaryError
    } else {
        searchFilter?.value?.let { viewModel.errorFilters[it] } == true
    }
    
    // Suggestion states


    LaunchedEffect(lazyListState) {
        snapshotFlow {
            lazyListState.layoutInfo.visibleItemsInfo.any { it.key == "loading" }
        }.collect { shouldLoadMore ->
            if (!shouldLoadMore) return@collect
            viewModel.loadMore()
        }
    }

    val ytItemContent: @Composable LazyItemScope.(YTItem, Int, Int) -> Unit = { item: YTItem, index: Int, size: Int ->
        val longClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            menuState.show {
                when (item) {
                    is SongItem ->
                        YouTubeSongMenu(
                            song = item,
                            navController = navController,
                            onDismiss = menuState::dismiss,
                        )

                    is AlbumItem ->
                        YouTubeAlbumMenu(
                            albumItem = item,
                            navController = navController,
                            onDismiss = menuState::dismiss,
                        )

                    is ArtistItem ->
                        YouTubeArtistMenu(
                            artist = item,
                            onDismiss = menuState::dismiss,
                        )

                    is PlaylistItem ->
                        YouTubePlaylistMenu(
                            playlist = item,
                            coroutineScope = coroutineScope,
                            onDismiss = menuState::dismiss,
                        )
                }
            }
        }
        YouTubeListItem(
            item = item,
            isActive =
            when (item) {
                is SongItem -> mediaMetadata?.id == item.id
                is AlbumItem -> mediaMetadata?.album?.id == item.id
                else -> false
            },
            isPlaying = isPlaying,
            flat = true,
            shape = listItemShape(index, size),
            trailingContent = {
                IconButton(
                    onClick = longClick,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.more_vert),
                        contentDescription = null,
                    )
                }
            },
            modifier =
            Modifier
                .combinedBounceClick(
                    onClick = {
                        when (item) {
                            is SongItem -> {
                                if (item.id == mediaMetadata?.id) {
                                    playerConnection.togglePlayPause()
                                } else {
                                    playerConnection.playQueue(
                                        YouTubeQueue(
                                            WatchEndpoint(videoId = item.id),
                                            item.toMediaMetadata()
                                        )
                                    )
                                }
                            }

                            is AlbumItem -> navController.navigate("album/${item.id}")
                            is ArtistItem -> navController.navigate("artist/${item.id}")
                            is PlaylistItem -> navController.navigate("online_playlist/${item.id}")
                        }
                    },
                    onLongClick = longClick,
                )
                .animateItem(),
        )
    }

    val heroUrl = remember(searchSummary, itemsPage) {
        if (searchFilter == null) {
            searchSummary?.summaries?.firstOrNull()?.items?.firstOrNull()?.thumbnail
        } else {
            itemsPage?.items?.firstOrNull()?.thumbnail
        }
    }
    val tint = rememberHeroTint(heroUrl)
    val onTint = AppleTokens.onColor(tint)

    val heroBackdrop = rememberLayerBackdrop()

    val context = LocalContext.current

    val selectedExternalVideo = viewModel.selectedExternalVideo
    val externalVideoInfo = viewModel.externalVideoInfo
    val externalVideoInfoLoading = viewModel.externalVideoInfoLoading
    val externalVideoInfoFailed = viewModel.externalVideoInfoFailed
    var lastStartedExternalVideoUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedExternalVideo?.url, externalVideoInfo, externalVideoInfoLoading, externalVideoInfoFailed) {
        val selected = selectedExternalVideo
        if (selected == null) {
            lastStartedExternalVideoUrl = null
            return@LaunchedEffect
        }
        if (externalVideoInfoLoading) return@LaunchedEffect
        if (externalVideoInfoFailed || externalVideoInfo == null || externalVideoInfo.formats.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.external_video_load_failed), Toast.LENGTH_LONG).show()
            viewModel.closeExternalVideo()
            return@LaunchedEffect
        }
        if (lastStartedExternalVideoUrl == selected.url) return@LaunchedEffect

        val info = externalVideoInfo
        val initialFormat = info.autoPlaybackFormat()
            ?: run {
                Toast.makeText(context, context.getString(R.string.external_video_no_formats), Toast.LENGTH_LONG).show()
                viewModel.closeExternalVideo()
                return@LaunchedEffect
            }

        lastStartedExternalVideoUrl = selected.url
        playerConnection.playQueue(buildExternalVideoQueue(selected, info, initialFormat))
        viewModel.closeExternalVideo()
        expandPlayer()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tint)
    ) {
        if (hasCustomHomeBackground()) {
            // Matches Home/Library: the user's own picture, not this screen's
            // auto-generated hero blur, once a custom background is set.
            HomeImageBackground(withGradient = true)
        } else {
            // Blurred hero artwork background
            if (heroUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(heroUrl)
                        .size(100, 100)
                        .allowHardware(false)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(150.dp)
                )
            }
            // Dark overlay for readability
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
            )
            // Primary-color wash at the bottom
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.55f to Color.Transparent,
                            1f to MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                        )
                    )
            )
        }

      HeroTintedContent(tint = tint, backdrop = heroBackdrop) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(appTopBarWindowInsets())
        ) {
            // The search bar used to live here — now it's part of the floating nav
            // bar itself (NavBarSearchInputBar in FloatingNavBar.kt), which stays
            // mounted across this whole screen. See PLAN_search_navbar.md.

            // Main content area
            Box(modifier = Modifier.weight(1f)) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // No opaque wrapper, no Material chip fills — just the row over
                    // the screen's own blurred hero backdrop, tinted to match.
                    if (!videoOnlySearch) ChipsRow(
                        chips = listOf(
                            null to stringResource(R.string.filter_all),
                            FILTER_SONG to stringResource(R.string.filter_songs),
                            FILTER_ALBUM to stringResource(R.string.filter_albums),
                            FILTER_ARTIST to stringResource(R.string.filter_artists),
                            FILTER_COMMUNITY_PLAYLIST to stringResource(R.string.filter_community_playlists),
                            FILTER_FEATURED_PLAYLIST to stringResource(R.string.filter_featured_playlists),
                        ),
                        currentValue = searchFilter,
                        onValueUpdate = {
                            if (viewModel.filter.value != it) {
                                viewModel.filter.value = it
                            }
                            coroutineScope.launch {
                                lazyListState.animateScrollToItem(0)
                            }
                        },
                        containerColor = Color.Transparent,
                        selectedContainerColor = onTint.copy(alpha = 0.2f),
                        labelColor = onTint.copy(alpha = 0.8f),
                        selectedLabelColor = onTint,
                        modifier = Modifier.fillMaxWidth()
                    )

                    LazyColumn(
                        state = lazyListState,
                        overscrollEffect = heroZoom.listOverscroll(),
                        contentPadding = WindowInsets.systemBars.only(WindowInsetsSides.Bottom).asPaddingValues(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heroPullZoom(heroZoom)
                    ) {
                        if (searchFailed) {
                            item(key = "search_error") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 72.dp, start = 24.dp, end = 24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        text = stringResource(R.string.search_failed),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = onTint,
                                    )
                                    TextButton(onClick = viewModel::refresh) {
                                        Text(stringResource(R.string.retry))
                                    }
                                }
                            }
                        }

                        if (searchFilter == null) {
                            searchSummary?.summaries?.forEach { summary ->
                                item {
                                    NavigationTitle(
                                        title = summary.title
                                    )
                                }

                                itemsIndexed(
                                    items = summary.items,
                                    key = { index, item -> "${summary.title}/${item.id}/$index" },
                                    contentType = { _, _ -> "yt_item_row" },
                                ) { index, item ->
                                    ytItemContent(item, index, summary.items.size)
                                }
                            }

                            if (searchSummary?.summaries?.isEmpty() == true) {
                                if (viewModel.externalVideoResults.isEmpty() && !viewModel.externalVideoLoading) {
                                    item {
                                        EmptyPlaceholder(
                                            icon = R.drawable.xan_icon_search,
                                            text = stringResource(
                                                if (videoOnlySearch) R.string.no_video_results_found
                                                else R.string.no_music_results_found,
                                            ),
                                            modifier = Modifier.padding(top = 100.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            // Was recomputed inside every row for the count argument,
                            // i.e. a full distinctBy pass per row.
                            val distinctItems = itemsPage?.items.orEmpty().distinctBy { it.id }
                            itemsIndexed(
                                items = distinctItems,
                                key = { _, it -> "filtered_${it.id}" },
                                contentType = { _, _ -> "yt_item_row" },
                            ) { index, item ->
                                ytItemContent(item, index, distinctItems.size)
                            }

                            if (itemsPage?.continuation != null) {
                                item(key = "loading") {
                                    ShimmerHost {
                                        repeat(3) {
                                            ListItemPlaceHolder()
                                        }
                                    }
                                }
                            }

                            if (itemsPage?.items?.isEmpty() == true && viewModel.externalVideoResults.isEmpty()) {
                                item {
                                    EmptyPlaceholder(
                                        icon = R.drawable.xan_icon_search,
                                        text = stringResource(
                                            if (videoOnlySearch) R.string.no_video_results_found
                                            else R.string.no_music_results_found,
                                        ),
                                        modifier = Modifier.padding(top = 100.dp)
                                    )
                                }
                            }
                        }

                        if (viewModel.externalVideoResults.isNotEmpty()) {
                            item(key = "external_video_results_title") {
                                NavigationTitle(title = stringResource(R.string.external_video_results))
                            }
                            itemsIndexed(
                                items = viewModel.externalVideoResults,
                                key = { _, item -> "external_video_${item.serviceId}_${item.url}" },
                                contentType = { _, _ -> "external_video_row" },
                            ) { index, item ->
                                ExternalVideoResultRow(
                                    result = item,
                                    index = index,
                                    count = viewModel.externalVideoResults.size,
                                    large = videoOnlySearch,
                                    onClick = { viewModel.openExternalVideo(item) },
                                )
                            }
                        }

                        if (viewModel.externalVideoLoading) {
                            item(key = "external_video_loading") {
                                ShimmerHost {
                                    repeat(3) { ListItemPlaceHolder() }
                                }
                            }
                        }

                        if (isSearchLoading &&
                            (if (searchFilter == null) searchSummary == null else itemsPage == null)
                        ) {
                            item {
                                ShimmerHost {
                                    repeat(8) {
                                        ListItemPlaceHolder()
                                    }
                                }
                            }
                        }

                        item(key = "bottom_spacer") {
                            Spacer(modifier = Modifier.height(MiniPlayerHeight + MiniPlayerBottomSpacing + NavigationBarHeight + 50.dp))
                        }
                    }
                }
            }
        }
      }
    }
}

@Composable
fun ExternalVideoResultRow(
    result: ExternalVideoSearchResult,
    index: Int,
    count: Int,
    large: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = if (large) 8.dp else 5.dp)
            .clip(listItemShape(index, count))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
            .combinedBounceClick(onClick = onClick)
            .padding(if (large) 10.dp else 12.dp),
    ) {
        Box {
            if (large) {
                if (!result.thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = result.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(196.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(196.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.xan_icon_search),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            if (large && result.durationSeconds >= 0) {
                Text(
                    text = formatExternalDuration(result.durationSeconds),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.Black.copy(alpha = 0.76f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
        Row(
            modifier = Modifier.padding(top = if (large) 10.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!large) {
                if (!result.thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = result.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 104.dp, height = 64.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(width = 104.dp, height = 64.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.xan_icon_search),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f).padding(start = if (large) 2.dp else 12.dp),
            ) {
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOf(result.uploader, result.serviceName)
                        .filter(String::isNotBlank)
                        .distinct()
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val extraMetadata = listOfNotNull(
                    result.viewCountText,
                    result.publishedTimeText,
                ).joinToString(" · ")
                if (extraMetadata.isNotBlank()) {
                    Text(
                        text = extraMetadata,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                result.description?.takeIf { large && it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!large && result.durationSeconds >= 0) {
                    Text(
                        text = formatExternalDuration(result.durationSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun formatExternalDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainingSeconds = seconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
    } else {
        "%d:%02d".format(minutes, remainingSeconds)
    }
}

