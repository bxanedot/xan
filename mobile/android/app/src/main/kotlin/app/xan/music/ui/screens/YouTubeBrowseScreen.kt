/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.ui.screens

import app.xan.music.ui.utils.appTopBarWindowInsets
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import app.xan.music.LocalPlayerAwareWindowInsets
import app.xan.music.LocalPlayerConnection
import app.xan.music.R
import app.xan.music.ui.utils.rememberGridColumns
import app.xan.music.constants.GridItemSize
import app.xan.music.constants.GridItemsSizeKey
import app.xan.music.constants.GridThumbnailHeight
import app.xan.music.models.toMediaMetadata
import app.xan.music.playback.queues.YouTubeQueue
import app.xan.music.ui.component.GlassCircleButton
import app.xan.music.ui.component.HeroBackground
import app.xan.music.ui.utils.rememberHeroZoom
import app.xan.music.ui.utils.heroPullZoom
import app.xan.music.ui.utils.listOverscroll
import app.xan.music.ui.component.GlassComponent
import app.xan.music.ui.component.LocalGlassEffectConfig
import app.xan.music.ui.component.LargeScreenTitle
import app.xan.music.ui.component.LocalMenuState
import app.xan.music.ui.component.YouTubeGridItem
import app.xan.music.ui.component.backdrop.backdrops.rememberLayerBackdrop
import app.xan.music.ui.component.isGlassAllowed
import app.xan.music.ui.component.liquidGlass
import app.xan.music.ui.component.rememberHeroSource
import app.xan.music.ui.component.rememberHeroTint
import app.xan.music.ui.component.shapes.ContinuousRoundedRectangle
import app.xan.music.ui.menu.YouTubeAlbumMenu
import app.xan.music.ui.menu.YouTubeArtistMenu
import app.xan.music.ui.menu.YouTubePlaylistMenu
import app.xan.music.ui.menu.YouTubeSongMenu
import app.xan.music.ui.theme.AppleTokens
import app.xan.music.ui.theme.HeroTintedContent
import app.xan.music.ui.utils.backToMain
import app.xan.music.ui.utils.combinedBounceClick
import app.xan.music.utils.rememberEnumPreference
import app.xan.music.viewmodels.YouTubeBrowseViewModel

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun YouTubeBrowseScreen(
    navController: NavController,
    viewModel: YouTubeBrowseViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val browseResult by viewModel.result.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    val gridItemSize by rememberEnumPreference(GridItemsSizeKey, GridItemSize.BIG)

    // Flattening multiple sections can surface the same item twice (an
    // auto-generated radio mix showing up in more than one section is the
    // common case — confirmed via a crash log: "Key playlist_RDCLAK5uy_...
    // was already used" in this screen's keyed grid below). Dedupe by id,
    // matching the same fix already applied to AccountScreen's playlist grid.
    val allItems = browseResult?.items?.flatMap { it.items }.orEmpty().distinctBy { it.id }

    val heroUrl = allItems.firstOrNull()?.let {
        when (it) {
            is SongItem -> it.thumbnail
            is AlbumItem -> it.thumbnail
            is ArtistItem -> it.thumbnail
            is PlaylistItem -> it.thumbnail
            else -> null
        }
    }
    val heroSource = rememberHeroSource(
        staticArt = heroUrl,
        songs = emptyList()
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
        // Apple-Music-style heavily-blurred artwork behind the playlist grid
        // instead of the sharp top-hero. Fully blurred (no sharp-top split).
        blurArtwork = true,
        fullBlur = true,
        heroScale = heroZoom.scale,
        modifier = Modifier.fillMaxSize(),
    ) {
      HeroTintedContent(tint = tint, backdrop = heroBackdrop) {
        val chromeShape = ContinuousRoundedRectangle(percent = 50)

        Box(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                // No bounce here: the top pull drives the hero zoom instead.
                overscrollEffect = heroZoom.listOverscroll(),
                modifier = Modifier.heroPullZoom(heroZoom).fillMaxSize(),
                columns = rememberGridColumns(),
                contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
            ) {
                browseResult?.let { result ->
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Column {
                            LargeScreenTitle(
                                title = result.title.orEmpty(),
                                color = onTint,
                            )
                        }
                    }

                    items(
                        items = allItems,
                        key = {
                            when (it) {
                                is SongItem -> "song_${it.id}"
                                is AlbumItem -> "album_${it.id}"
                                is ArtistItem -> "artist_${it.id}"
                                is PlaylistItem -> "playlist_${it.id}"
                                else -> it.hashCode()
                            }
                        },
                    ) { item ->
                        YouTubeGridItem(
                            item = item,
                            isActive = when (item) {
                                is SongItem -> mediaMetadata?.id == item.id
                                is AlbumItem -> mediaMetadata?.album?.id == item.id
                                else -> false
                            },
                            isPlaying = isPlaying,
                            fillMaxWidth = true,
                            coroutineScope = coroutineScope,
                            modifier =
                            Modifier
                                .combinedBounceClick(
                                    onClick = {
                                        when (item) {
                                            is SongItem ->
                                                playerConnection.playQueue(
                                                    YouTubeQueue(
                                                        com.music.innertube.models.WatchEndpoint(videoId = item.id),
                                                        item.toMediaMetadata()
                                                    ),
                                                )

                                            is AlbumItem -> navController.navigate("album/${item.id}")
                                            is ArtistItem -> navController.navigate("artist/${item.id}")
                                            is PlaylistItem -> navController.navigate("online_playlist/${item.id}")
                                        }
                                    },
                                    onLongClick = {
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
                                    },
                                ),
                        )
                    }
                }
            }

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
                GlassCircleButton(
                    onClick = { navController.navigateUp() },
                    onLongClick = { navController.backToMain() },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.xan_icon_back),
                        contentDescription = null,
                    )
                }

                Spacer(Modifier.weight(1f))
            }
        }
      }
    }
}
