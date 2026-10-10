/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.xan.music.ui.screens.search.suggestions

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import app.xan.music.ui.utils.bounceClick
import app.xan.music.ui.utils.combinedBounceClick
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import app.xan.music.R
import app.xan.music.constants.SuggestionRegionKey
import app.xan.music.constants.SuggestionRegionSlugToName
import app.xan.music.ui.component.NavigationTitle
import app.xan.music.ui.theme.AppleTokens
import app.xan.music.utils.rememberPreference
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SuggestionsTabContent(
    navController: NavController,
    viewModel: SuggestionsViewModel = hiltViewModel(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    videoOnly: Boolean = false,
    onVideoSearch: (String) -> Unit = {},
) {
    val suggestionTracks by viewModel.suggestionTracks.collectAsState()
    val suggestionArtists by viewModel.suggestionArtists.collectAsState()
    val suggestionAlbums by viewModel.suggestionAlbums.collectAsState()
    val suggestionVideos by viewModel.suggestionVideos.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isManualLoading by viewModel.isManualLoading.collectAsState()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val playerConnection = app.xan.music.LocalPlayerConnection.current
    val context = LocalContext.current
    val (regionCode, _) = rememberPreference(
        key = SuggestionRegionKey,
        defaultValue = "system"
    )

    androidx.compose.runtime.LaunchedEffect(regionCode, videoOnly) {
        viewModel.refresh(regionCode, videoOnly = videoOnly)
    }

    val pullToRefreshState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()

    PullToRefreshBox(
        isRefreshing = isManualLoading,
        onRefresh = {
            viewModel.refresh(regionCode, force = true, videoOnly = videoOnly)
        },
        state = pullToRefreshState,
        indicator = {
            // Only while visible — see HomeScreen: the M3 LoadingIndicator
            // animates forever once composed and pins the app at full frame
            // rate at idle.
            if (isManualLoading || pullToRefreshState.distanceFraction > 0f) {
                PullToRefreshDefaults.LoadingIndicator(
                    state = pullToRefreshState,
                    isRefreshing = isManualLoading,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding
        ) {
        if (isLoading && !isManualLoading &&
            (if (videoOnly) suggestionVideos == null else
                suggestionTracks == null && suggestionArtists == null && suggestionAlbums == null && suggestionVideos == null)
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularWavyProgressIndicator()
                }
            }
        }

        if (!videoOnly) suggestionTracks?.let { tracks ->
                item {
                    TrendingAppleMusicSection(
                        tracks = suggestionTracks!!,
                        countryCode = regionCode,
                        onTrackClick = { track ->
                            android.widget.Toast.makeText(context, "Loading ${track.title}...", android.widget.Toast.LENGTH_SHORT).show()
                            viewModel.playTrack(track, playerConnection)
                        },
                        onMoreClick = {
                            val code = if (regionCode == "system") java.util.Locale.getDefault().country.lowercase() else regionCode.lowercase()
                            uriHandler.openUri("https://music.apple.com/$code/charts")
                        }
                    )
                }
            }

            if (!videoOnly) suggestionArtists?.let { artists ->
                item {
                    TopArtistsSection(
                        artists = artists,
                        onArtistClick = { artist ->
                            android.widget.Toast.makeText(context, "Loading ${artist.name}...", android.widget.Toast.LENGTH_SHORT).show()
                            viewModel.navigateToArtist(artist, navController)
                        }
                    )
                }
            }

            if (!videoOnly) suggestionAlbums?.let { albums ->
                item {
                    TrendingAlbumsSection(
                        albums = albums,
                        onAlbumClick = { album ->
                            android.widget.Toast.makeText(context, "Loading ${album.title}...", android.widget.Toast.LENGTH_SHORT).show()
                            viewModel.navigateToAlbum(album, navController)
                        },
                        onMoreClick = {
                            val code = if (regionCode == "system") java.util.Locale.getDefault().country.lowercase() else regionCode.lowercase()
                            uriHandler.openUri("https://music.apple.com/$code/charts/albums")
                        }
                    )
                }
            }

            suggestionVideos?.let { videos ->
                item {
                    TrendingVideosSection(
                        videos = videos,
                        titleRes = if (videoOnly) R.string.video_suggestions else R.string.trending_music_videos,
                        onVideoClick = { video ->
                            if (videoOnly) {
                                onVideoSearch("${video.title} ${video.artist}")
                            } else {
                                android.widget.Toast.makeText(context, "Loading video ${video.title}...", android.widget.Toast.LENGTH_SHORT).show()
                                viewModel.playVideo(video, playerConnection)
                            }
                        }
                    )
                }
            }

            if ((if (videoOnly) suggestionVideos == null else
                    suggestionTracks == null && suggestionArtists == null && suggestionAlbums == null && suggestionVideos == null) && !isLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                            text = stringResource(if (videoOnly) R.string.no_video_suggestions_available else R.string.no_suggestions_available),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedButton(onClick = { viewModel.refresh(regionCode, force = true) }) {
                                Text(stringResource(R.string.refresh))
                            }
                        }
                    }
                }
            }

            // Footer at the very bottom
            if (!videoOnly && (suggestionTracks != null || suggestionArtists != null || suggestionAlbums != null || suggestionVideos != null)) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.data_from_apple_music),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = "XAN",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TrendingAppleMusicSection(
    tracks: List<SuggestionTrack>,
    countryCode: String,
    onTrackClick: (SuggestionTrack) -> Unit,
    onMoreClick: () -> Unit
) {
    if (tracks.isEmpty()) return
    val displayTracks = tracks.take(29)
    val totalItems = displayTracks.size + 1
    val pagerState = rememberPagerState(pageCount = { (totalItems + 4) / 5 })
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxWidth()) {
        NavigationTitle(
            title = stringResource(R.string.apple_music_top_100),
            label = SuggestionRegionSlugToName[countryCode] ?: stringResource(R.string.global_charts),
            modifier = Modifier.padding(top = AppleTokens.Gutter),
        )
        HorizontalPager(
            state = pagerState,
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth().animateContentSize(tween(300, easing = FastOutSlowInEasing))
        ) { page ->
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                val startIdx = page * 5
                val endIdx = minOf(startIdx + 5, totalItems)
                for (i in startIdx until endIdx) {
                    val isMoreCard = i == 29
                    val isTop = i == startIdx
                    val isBottom = i == endIdx - 1
                    val shape = when {
                        isTop && isBottom -> RoundedCornerShape(24.dp)
                        isTop -> RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
                        isBottom -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
                        else -> RoundedCornerShape(4.dp)
                    }
                    if (isMoreCard) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh).bounceClick { onMoreClick() }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(painterResource(R.drawable.globe_search), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.view_more_on_apple_music), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    } else if (i < displayTracks.size) {
                        val track = displayTracks[i]
                        Row(modifier = Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer).bounceClick { onTrackClick(track) }) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(track.title, style = MaterialTheme.typography.titleMedium)
                                Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                    Text("#${track.rank}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(8.dp))
                                    // Mock play count
                                    val playCountBase = remember(track.rank) { 2_500_000 / (track.rank + 2) }
                                    val playCount = if (playCountBase >= 1_000_000) {
                                        stringResource(R.string.play_count_millions, playCountBase / 1_000_000f)
                                    } else {
                                        stringResource(R.string.play_count_thousands, playCountBase / 1_000)
                                    }
                                    Text(playCount, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                                }
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainer)) {
                IconButton(onClick = { coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } }, enabled = pagerState.currentPage > 0) {
                    Icon(painterResource(R.drawable.chevron_leftpx), stringResource(R.string.previous))
                }
                Text(stringResource(R.string.page_indicator, pagerState.currentPage + 1, pagerState.pageCount), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } }, enabled = pagerState.currentPage < pagerState.pageCount - 1) {
                    Icon(painterResource(R.drawable.chevron_right_px), stringResource(R.string.next))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TopArtistsSection(
    artists: List<SuggestionArtist>,
    onArtistClick: (SuggestionArtist) -> Unit
) {
    if (artists.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        NavigationTitle(title = stringResource(R.string.trending_artists))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            items(artists) { artist ->
                val rankCount = "#${artist.rank}"
                val playCountBase = remember(artist.rank) { 15_000_000 / (artist.rank + 8) }
                val playCount = if (playCountBase >= 1_000_000) {
                    stringResource(R.string.play_count_millions, playCountBase / 1_000_000f)
                } else {
                    stringResource(R.string.play_count_thousands, playCountBase / 1_000)
                }
                Column(
                    modifier = Modifier
                        .width(164.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .bounceClick { onArtistClick(artist) }
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(rankCount, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(artist.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(playCount, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun TrendingAlbumsSection(
    albums: List<SuggestionAlbum>,
    onAlbumClick: (SuggestionAlbum) -> Unit,
    onMoreClick: () -> Unit
) {
    if (albums.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        NavigationTitle(
            title = stringResource(R.string.trending_albums),
            onClick = onMoreClick,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp)
        ) {
            items(albums) { album ->
                Column(
                    modifier = Modifier
                        .width(176.dp)
                        .height(116.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .bounceClick { onAlbumClick(album) }
                        .padding(14.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("#${album.rank}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(album.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(album.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(144.dp)
                        .height(116.dp)
                        .padding(bottom = 20.dp)
                        .bounceClick { onMoreClick() }
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.globe_search),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.more_label),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendingVideosSection(
    videos: List<SuggestionTrack>,
    onVideoClick: (SuggestionTrack) -> Unit,
    titleRes: Int = R.string.trending_music_videos,
) {
    if (videos.isEmpty()) return
    
    val carouselState = rememberCarouselState(itemCount = { videos.size })
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        NavigationTitle(
            title = stringResource(titleRes),
        )

        HorizontalMultiBrowseCarousel(
            state = carouselState,
            preferredItemWidth = 320.dp,
            itemSpacing = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(176.dp),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) { i ->
            val video = videos[i]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .bounceClick { onVideoClick(video) }
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.xan_icon_music),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = video.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = video.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
