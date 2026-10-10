/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.music.innertube.models.WatchEndpoint
import com.music.innertube.utils.YouTubeUrlParser
import app.xan.music.LocalDatabase
import app.xan.music.LocalIsPlayerExpanded
import app.xan.music.LocalPlayerAwareWindowInsets
import app.xan.music.LocalPlayerConnection
import app.xan.music.R
import app.xan.music.constants.PauseSearchHistoryKey
import app.xan.music.constants.LocalOnlyModeKey
import app.xan.music.constants.VideoSearchFallbackKey
import app.xan.music.constants.SearchSource
import app.xan.music.db.entities.SearchHistory
import app.xan.music.playback.queues.YouTubeQueue
import app.xan.music.ui.component.NavigationTitle
import app.xan.music.ui.utils.bounceClick
import app.xan.music.utils.rememberPreference
import app.xan.music.viewmodels.MoodAndGenresViewModel
import app.xan.music.ui.screens.search.suggestions.SuggestionsTabContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URLEncoder
import androidx.compose.runtime.collectAsState
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.foundation.layout.PaddingValues

import app.xan.music.ui.component.LargeScreenTitle
import app.xan.music.ui.component.rememberAppBackgroundColor
import app.xan.music.ui.theme.AppleTokens
import app.xan.music.ui.theme.HeroTintedContent

import app.xan.music.ui.component.LocalNavSearchState
import app.xan.music.ui.component.shapes.ContinuousRoundedRectangle
import app.xan.music.ui.component.backdrop.backdrops.layerBackdropWhenGlassEnabled
import app.xan.music.ui.component.backdrop.backdrops.rememberBackdropFreeze
import app.xan.music.ui.component.backdrop.backdrops.rememberLayerBackdrop
import app.xan.music.ui.component.backdrop.catalog.components.LiquidBottomTab
import app.xan.music.ui.component.backdrop.catalog.components.LiquidBottomTabs
import androidx.compose.ui.text.font.FontWeight
import app.xan.music.ui.component.GlassCircleButton
import app.xan.music.ui.component.LocalGlassEffectConfig
import app.xan.music.ui.component.isGlassAllowed
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.offset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    navController: NavController,
    pureBlack: Boolean
) {
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val isPlayerExpanded = LocalIsPlayerExpanded.current
    val playerConnection = LocalPlayerConnection.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // The nav bar owns the actual search text field now (see NavBarSearchInputBar
    // in FloatingNavBar.kt) — this screen just reads the live query to filter results.
    val navSearch = LocalNavSearchState.current
    val pauseSearchHistory by rememberPreference(PauseSearchHistoryKey, defaultValue = false)
    val (videoOnlySearch) = rememberPreference(VideoSearchFallbackKey, defaultValue = false)

    var selectedTabIndex by rememberSaveable { mutableStateOf(0) }
    // Keep the visible browse tabs to Explore and Suggestions. Clamp the saved
    // index so installs upgrading from the old third tab land on Explore.
    val visibleTabIndex = selectedTabIndex.coerceIn(0, 1)
    val (localOnly) = rememberPreference(LocalOnlyModeKey, false)
    DisposableEffect(lifecycleOwner, isPlayerExpanded) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (isPlayerExpanded) {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    navSearch.onCloseKeyboard()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    navSearch.onCloseKeyboard()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (isPlayerExpanded) {
            keyboardController?.hide()
            focusManager.clearFocus()
            navSearch.onCloseKeyboard()
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (!localOnly && navSearch.searchSource == SearchSource.ONLINE && navSearch.query.text.isNotBlank()) {
        OnlineSearchResult(
            navController = navController,
            pureBlack = pureBlack,
            queryOverride = navSearch.query.text,
        )
        return
    }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var showSearchContent by remember { mutableStateOf(false) }

    val onSearchFromSuggestion: (String) -> Unit = remember {
        { searchQuery ->
            if (searchQuery.isNotEmpty()) {
                focusManager.clearFocus()
                when (val parsedUrl = YouTubeUrlParser.parse(searchQuery)) {
                    is YouTubeUrlParser.ParsedUrl.Video -> {
                        playerConnection?.playQueue(
                            YouTubeQueue(WatchEndpoint(videoId = parsedUrl.id)),
                        )
                    }
                    is YouTubeUrlParser.ParsedUrl.Artist -> {
                        navController.navigate("artist/${parsedUrl.id}")
                    }
                    null -> {
                        navSearch.onQueryChange(
                            androidx.compose.ui.text.input.TextFieldValue(
                                searchQuery,
                                androidx.compose.ui.text.TextRange(searchQuery.length),
                            )
                        )
                        navSearch.onCloseKeyboard()
                    }
                }
                if (!pauseSearchHistory) {
                    coroutineScope.launch(Dispatchers.IO) {
                        database.query {
                            insert(SearchHistory(query = searchQuery))
                        }
                    }
                }
            }
        }
    }
    val onVideoSearchSuggestion: (String) -> Unit = { searchQuery ->
        if (navSearch.searchSource != SearchSource.ONLINE && navSearch.canToggleSource) {
            navSearch.onToggleSource()
        }
        navSearch.onQueryChange(
            androidx.compose.ui.text.input.TextFieldValue(
                searchQuery,
                androidx.compose.ui.text.TextRange(searchQuery.length),
            ),
        )
        navSearch.onCloseKeyboard()
    }

    // Fixed elevated token, not derived from any specific result's thumbnail —
    // safe to override with the user's own picked theme color.
    val tint = rememberAppBackgroundColor(AppleTokens.BgElevated)
    val onTint = AppleTokens.onColor(tint)
    val heroBackdrop = rememberLayerBackdrop {
        drawRect(tint)
        drawContent()
    }
    val backdropFreeze = rememberBackdropFreeze()
    // Solid mode must not record the discovery screen into a backdrop layer.
    // The Search tabs only sample this layer for Liquid Glass rendering.
    val captureHeroBackdrop = LocalGlassEffectConfig.current.globalEnabled && isGlassAllowed()

    Box(modifier = Modifier.fillMaxSize().background(tint)) {
      HeroTintedContent(tint = tint, backdrop = heroBackdrop) {
        val chromeShape = ContinuousRoundedRectangle(percent = 50)
        val accent = app.xan.music.ui.theme.LocalAccentColor.current

        // Space for the floating side bar in tab view (0 otherwise). The hero
        // background stays full-bleed behind the Scaffold; only the foreground —
        // title, search pill, tabs and results — shifts clear of the panel. The
        // result lists inset bottom-only, so nothing double-pads.
        val sideInset = LocalPlayerAwareWindowInsets.current
            .asPaddingValues()
            .calculateStartPadding(LocalLayoutDirection.current)

        Scaffold(
            modifier = Modifier.padding(start = sideInset),
            topBar = {
                Column(
                    modifier = Modifier.background(Color.Transparent)
                ) {
                    LargeScreenTitle(
                        title = stringResource(R.string.search),
                        color = onTint,
                    )

                    AnimatedVisibility(
                        visible = navSearch.query.text.isEmpty() && !localOnly,
                        enter = expandVertically(animationSpec = tween(durationMillis = 245, easing = FastOutSlowInEasing)) + fadeIn(),
                        exit = shrinkVertically(animationSpec = tween(durationMillis = 245, easing = FastOutSlowInEasing)) + fadeOut()
                    ) {
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            // A compact Explore/Suggestions selector with clear
                            // labels and a fixed per-tab selected surface.
                            LiquidBottomTabs(
                                selectedTabIndex = { visibleTabIndex },
                                onTabSelected = { selectedTabIndex = it },
                                backdrop = heroBackdrop,
                                tabsCount = 2,
                                // Blend into this screen's own tint instead of the
                                // component's default near-black glass fill.
                                containerColor = onTint.copy(alpha = 0.12f),
                                height = 44.dp,
                                // Keep this control's labels clear; the movable
                                // puck belongs only to primary navigation.
                                showSelectionPuck = false,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 30.dp , vertical = 16.dp)
                            ) {
                                LiquidBottomTab(
                                    onClick = { selectedTabIndex = 0 },
                                    isSelected = visibleTabIndex == 0,
                                    modifier = Modifier.background(
                                        if (visibleTabIndex == 0) accent.copy(alpha = 0.16f) else Color.Transparent,
                                        chromeShape,
                                    ),
                                ) {
                                    Text(
                                        text = stringResource(R.string.tab_explore),
                                        // The selected label was nearly unreadable in
                                        // the reference screenshot when the accent hue
                                        // was dark. Keep selection in the surface and
                                        // use the contrast-safe foreground instead.
                                        color = if (visibleTabIndex == 0) onTint else onTint.copy(alpha = 0.6f),
                                    )
                                }
                                LiquidBottomTab(
                                    onClick = { selectedTabIndex = 1 },
                                    isSelected = visibleTabIndex == 1,
                                    modifier = Modifier.background(
                                        if (visibleTabIndex == 1) accent.copy(alpha = 0.16f) else Color.Transparent,
                                        chromeShape,
                                    ),
                                ) {
                                    Text(
                                        text = stringResource(R.string.tab_Suggestions),
                                        color = if (visibleTabIndex == 1) onTint else onTint.copy(alpha = 0.6f),
                                    )
                                }
                            }
                    }
                }
                }
            },
            // The bottom search pill used to live here — now it's part of the
            // floating nav bar itself (NavBarSearchInputBar in FloatingNavBar.kt),
            // which stays mounted across this whole screen instead of being owned
            // by it. See PLAN_search_navbar.md.
            containerColor = Color.Transparent
        ) { paddingValues ->
            val bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
            
            Box(
                modifier = Modifier
                    .nestedScroll(backdropFreeze.connection)

                    .then(
                        if (captureHeroBackdrop) {
                            // OUTER layer encloses the capture node so sibling redraws
                            // don't re-record this list. The inner layer gives the capture
                            // one cached RenderNode instead of re-issuing its draw operations.
                            Modifier
                                .graphicsLayer()
                                .layerBackdropWhenGlassEnabled(heroBackdrop, frozen = backdropFreeze.frozen)
                                .graphicsLayer()
                        } else {
                            Modifier
                        },
                    )
                    .padding(
                        top = paddingValues.calculateTopPadding(),
                    )
                    .fillMaxSize()
            ) {
                if (navSearch.query.text.isEmpty() && !localOnly) {
                    val tabPadding = PaddingValues(bottom = bottomPadding + 50.dp)
                    when (visibleTabIndex) {
                        0 -> ExploreTabContent(
                            navController = navController,
                            contentPadding = tabPadding,
                            videoOnly = videoOnlySearch,
                            onVideoSearch = onVideoSearchSuggestion,
                        )
                        1 -> SuggestionsTabContent(
                            navController = navController,
                            contentPadding = tabPadding,
                            videoOnly = videoOnlySearch,
                            onVideoSearch = onVideoSearchSuggestion,
                        )
                    }
                } else {
                    when (navSearch.searchSource) {
                        SearchSource.LOCAL -> LocalSearchScreen(
                            query = navSearch.query.text,
                            navController = navController,
                            onDismiss = { },
                            pureBlack = pureBlack
                        )
                        SearchSource.ONLINE -> OnlineSearchScreen(
                            query = navSearch.query.text,
                            onQueryChange = navSearch.onQueryChange,
                            navController = navController,
                            onSearch = { onSearchFromSuggestion(it) },
                            onDismiss = { },
                            pureBlack = pureBlack,
                            contentColor = onTint,
                        )
                    }
                }
            }
        }
      }
    }

}

@Composable
fun ExploreTabContent(
    navController: NavController,
    viewModel: MoodAndGenresViewModel = hiltViewModel(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    videoOnly: Boolean = false,
    onVideoSearch: (String) -> Unit = {},
) {
    if (videoOnly) {
        VideoSearchExploreContent(contentPadding = contentPadding, onVideoSearch = onVideoSearch)
        return
    }

    val moodAndGenresList by viewModel.moodAndGenres.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding
    ) {
        moodAndGenresList?.forEach { section ->
            item {
                NavigationTitle(title = section.title)
            }
            val rows = section.items.chunked(2)
            items(rows) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp)
                ) {
                    row.forEach { item ->
                        Box(
                            contentAlignment = Alignment.CenterStart,
                            modifier = Modifier
                                .weight(1f)
                                .padding(6.dp)
                                .height(64.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(LocalContentColor.current.copy(alpha = 0.1f))
                                .bounceClick {
                                    navController.navigate(
                                        "youtube_browse/${item.endpoint.browseId}?params=${item.endpoint.params}"
                                    )
                                }
                                .padding(horizontal = 14.dp)
                        ) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    repeat(2 - row.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        if (moodAndGenresList == null) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularWavyProgressIndicator()
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun VideoSearchExploreContent(
    contentPadding: PaddingValues,
    onVideoSearch: (String) -> Unit,
) {
    val videoSearches = listOf(
        R.string.video_idea_trending,
        R.string.video_idea_trailers,
        R.string.video_idea_gaming,
        R.string.video_idea_live,
        R.string.video_idea_documentaries,
        R.string.video_idea_science,
        R.string.video_idea_sports,
        R.string.video_idea_cooking,
        R.string.video_idea_travel,
        R.string.video_idea_technology,
        R.string.video_idea_comedy,
        R.string.video_idea_music,
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item { NavigationTitle(title = stringResource(R.string.video_search_ideas)) }
        items(videoSearches.chunked(2)) { row ->
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                row.forEach { labelId ->
                    val query = stringResource(labelId)
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier
                            .weight(1f)
                            .padding(6.dp)
                            .height(64.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(LocalContentColor.current.copy(alpha = 0.1f))
                            .bounceClick { onVideoSearch(query) }
                            .padding(horizontal = 14.dp),
                    ) {
                        Text(
                            text = query,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                repeat(2 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
        item {
            Text(
                text = stringResource(R.string.video_search_ideas_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
fun DynamicSearchPlaceholder(searchSource: SearchSource, style: TextStyle) {
    Text(
        text = stringResource(
            when (searchSource) {
                SearchSource.ONLINE -> R.string.search_xan
                SearchSource.LOCAL -> R.string.search_library
            }
        ),
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}
