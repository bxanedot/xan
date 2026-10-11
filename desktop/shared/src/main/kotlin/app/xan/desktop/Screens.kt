package app.xan.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import javax.swing.JFileChooser
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun HomeScreen(
    recentTracks: List<MusicTrack>,
    onTrackSelected: (MusicTrack) -> Unit,
    onSearch: () -> Unit,
    onLibrary: () -> Unit,
) {
    var feed by remember { mutableStateOf(DesktopHomeFeed(emptyList(), recentTracks, emptyList())) }
    var openedCollection by remember { mutableStateOf<MusicTrack?>(null) }
    var openedArtist by remember { mutableStateOf<MusicTrack?>(null) }
    var selectedMood by remember { mutableStateOf("All") }
    var loading by remember { mutableStateOf(true) }
    var feedError by remember { mutableStateOf<String?>(null) }
    var unfilteredFeed by remember { mutableStateOf<DesktopHomeFeed?>(null) }
    var requestSequence by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    fun loadFeed(chip: com.music.innertube.pages.HomePage.Chip? = null, rollbackMood: String = selectedMood) {
        val sequence = ++requestSequence
        scope.launch {
            loading = true
            feedError = null
            val request = chip?.toHomeFeedRequest()
            if (chip != null && request == null) {
                feedError = "This mood filter is missing its YouTube Music endpoint."
                selectedMood = rollbackMood
                loading = false
                return@launch
            }
            runCatching { DesktopBackend.homeFeed(request ?: HomeFeedRequest(), recentTracks) }
                .onSuccess { loaded ->
                    if (sequence == requestSequence) {
                        val next = loaded.copy(chips = feed.chips.ifEmpty { loaded.chips })
                        feed = next
                        if (chip == null) unfilteredFeed = next
                    }
                }
                .onFailure { failure ->
                    if (sequence == requestSequence) {
                        feedError = failure.message ?: "Could not load your YouTube Music home feed."
                        selectedMood = rollbackMood
                        DiagnosticLog.warn(
                            DesktopBackend.diagnosticsDirectory(),
                            "home_mood_filter_failed",
                            "Could not load the selected YouTube Music Home filter.",
                            failure,
                            mapOf("chip" to (chip?.title ?: "All")),
                        )
                    }
                }
            if (sequence == requestSequence) loading = false
        }
    }
    LaunchedEffect(recentTracks) { loadFeed() }
    openedCollection?.let { collection ->
        CollectionDetailScreen(collection, { openedCollection = null }, onTrackSelected, backLabel = "Listen Now")
        return
    }
    openedArtist?.let { artist ->
        ArtistDetailScreen(
            artist = artist,
            onBack = { openedArtist = null },
            onTrackSelected = onTrackSelected,
            onCollectionSelected = { openedCollection = it },
        )
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 32.dp, end = 32.dp, bottom = 32.dp)) {
        Text("xan", Modifier.padding(top = 12.dp), color = XanColors.Accent, fontFamily = XanBrandFont, fontWeight = FontWeight.SemiBold, fontSize = 27.sp)
        Text("Listen Now", Modifier.padding(top = 7.dp, bottom = 16.dp), color = XanColors.Text, fontFamily = XanBrandFont, fontWeight = FontWeight.Bold, fontSize = 42.sp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val filters = listOf<Pair<String, com.music.innertube.pages.HomePage.Chip?>>("All" to null) + feed.chips
                .filter { it.endpoint != null }
                .map { it.title to it }
            filters.distinctBy { it.first }.forEach { (title, chip) ->
                FilterPill(title, selectedMood == title) {
                    if (chip == null || selectedMood == title) {
                        selectedMood = "All"
                        feedError = null
                        unfilteredFeed?.let { feed = it; loading = false } ?: loadFeed()
                    } else {
                        val previousMood = selectedMood
                        selectedMood = title
                        loadFeed(chip, rollbackMood = previousMood)
                    }
                }
            }
        }
        if (loading && selectedMood != "All") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = XanColors.Accent)
                Text("Loading $selectedMood…", color = XanColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        feedError?.let { error ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(error, Modifier.weight(1f), color = XanColors.Muted)
                TextButton(onClick = { loadFeed(feed.chips.firstOrNull { it.title == selectedMood }) }) { Text("Retry") }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp)
                .clip(RoundedCornerShape(25.dp))
                .background(Brush.horizontalGradient(listOf(XanColors.Accent.copy(alpha = .24f), XanColors.Selected, XanColors.Card)))
                .clickable(onClick = onSearch).padding(horizontal = 24.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("YOUR MUSIC", style = MaterialTheme.typography.labelSmall, color = XanColors.Muted, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(7.dp))
                Text("What do you want to hear?", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
                Spacer(Modifier.height(6.dp))
                Text("Search songs, artists, albums, and playlists.", color = XanColors.Muted)
            }
            Icon(Icons.Rounded.Search, contentDescription = null, tint = XanColors.Accent.copy(alpha = .86f), modifier = Modifier.size(44.dp))
        }
        SectionTitle("Recently Played", action = "See All", onAction = onLibrary)
        if (feed.recentTracks.isEmpty()) {
            EmptyPanel(Icons.Rounded.History, if (loading) "Loading listening history…" else "Nothing played yet", "Tracks you listen to will appear here.")
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                feed.recentTracks.forEach { track ->
                    TrackCard(track, onClick = { onTrackSelected(track) }, modifier = Modifier.width(190.dp))
                }
            }
        }
        feed.shelves.forEach { (title, items) ->
            SectionTitle(title.ifBlank { "Recommended for you" }, action = "See All", onAction = onLibrary)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items.forEach { item ->
                    TrackCard(item, onClick = {
                        when (item.kind) {
                            "song" -> onTrackSelected(item)
                            "artist" -> openedArtist = item
                            "album", "playlist" -> openedCollection = item
                        }
                    }, modifier = Modifier.width(190.dp))
                }
            }
        }
        if (feed.shelves.isEmpty() && !loading && feedError == null) {
            SectionTitle("Recommended for you", action = "Search", onAction = onSearch)
            Text("YouTube Music has no recommendations to show yet. Search for an artist, album, playlist, or song to get started.", color = XanColors.Muted)
        }
    }
}

@Composable
internal fun SearchScreen(onTrackSelected: (MusicTrack) -> Unit, initialQuery: String = "") {
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    var discoveryTab by remember { mutableStateOf("Explore") }
    var discoveryReload by remember { mutableIntStateOf(0) }
    var explorePage by remember { mutableStateOf<com.music.innertube.pages.ExplorePage?>(null) }
    var moodSections by remember { mutableStateOf<List<com.music.innertube.pages.MoodAndGenres>?>(null) }
    var charts by remember { mutableStateOf<AppleCharts?>(null) }
    var discoveryLoading by remember { mutableStateOf(false) }
    var discoveryError by remember { mutableStateOf<String?>(null) }
    var selectedBrowse by remember { mutableStateOf<DiscoveryBrowse?>(null) }
    var selectedBrowseTitle by remember { mutableStateOf<String?>(null) }
    var recentSearches by remember { mutableStateOf(DesktopBackend.recentSearches()) }
    var fallback by remember { mutableStateOf(DesktopBackend.fallbackEnabled()) }
    var videoOnly by remember { mutableStateOf(DesktopBackend.videoOnlySearch()) }
    var onlineResults by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var onlineError by remember { mutableStateOf<String?>(null) }
    var downloadMessage by remember { mutableStateOf<String?>(null) }
    var selectedArtist by remember { mutableStateOf<MusicTrack?>(null) }
    var selectedCollection by remember { mutableStateOf<MusicTrack?>(null) }
    var selectedVideo by remember { mutableStateOf<MusicTrack?>(null) }
    var videoInfo by remember { mutableStateOf<com.music.innertube.ExternalVideoInfo?>(null) }
    var videoInfoLoading by remember { mutableStateOf(false) }
    var videoInfoError by remember { mutableStateOf<String?>(null) }
    var videoDownloadMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(discoveryTab, query, discoveryReload) {
        if (query.isNotBlank() || discoveryTab == "") return@LaunchedEffect
        if ((discoveryTab == "Explore" && moodSections == null) || (discoveryTab == "Album" && explorePage == null)) {
            discoveryLoading = true
            discoveryError = null
            if (discoveryTab == "Explore") {
                runCatching { DesktopBackend.searchMoodAndGenres() }
                    .onSuccess { moodSections = it }
                    .onFailure { discoveryError = it.message ?: "Could not load YouTube Music discovery." }
            } else {
                runCatching { DesktopBackend.searchExplore() }
                    .onSuccess { explorePage = it }
                    .onFailure { discoveryError = it.message ?: "Could not load YouTube Music discovery." }
            }
            discoveryLoading = false
        } else if (discoveryTab == "Suggestions" && charts == null) {
            discoveryLoading = true
            discoveryError = null
            runCatching { AppleChartsClient.fetch() }
                .onSuccess { charts = it }
                .onFailure { discoveryError = it.message ?: "Could not load music charts." }
            discoveryLoading = false
        }
    }
    selectedCollection?.let { collection ->
        CollectionDetailScreen(
            collection = collection,
            onBack = { selectedCollection = null },
            onTrackSelected = onTrackSelected,
        )
        return
    }
    selectedArtist?.let { artist ->
        ArtistDetailScreen(
            artist = artist,
            onBack = { selectedArtist = null },
            onTrackSelected = onTrackSelected,
            onCollectionSelected = { selectedCollection = it },
        )
        return
    }
    selectedBrowse?.let { browse ->
        DiscoveryBrowseScreen(
            title = selectedBrowseTitle ?: browse.title ?: "Explore",
            browse = browse,
            onBack = { selectedBrowse = null; selectedBrowseTitle = null },
            onItemSelected = { item ->
                when (item.kind) {
                    "song" -> onTrackSelected(item)
                    "artist" -> selectedArtist = item
                    else -> selectedCollection = item
                }
            },
        )
        return
    }
    LaunchedEffect(query, fallback, videoOnly) {
        if (query.isBlank()) {
            onlineResults = emptyList()
            searching = false
            onlineError = null
        } else {
            delay(350)
            searching = true
            onlineError = null
            runCatching {
                if (videoOnly) DesktopBackend.searchExternalVideos(query.trim())
                else DesktopBackend.searchCatalog(query.trim())
            }
                .onSuccess {
                    onlineResults = it
                    if (DesktopBackend.searchHistoryEnabled()) {
                        withContext(Dispatchers.IO) { DesktopBackend.recordSearch(query.trim()) }
                        recentSearches = withContext(Dispatchers.IO) { DesktopBackend.recentSearches() }
                    }
                }
                .onFailure { onlineError = it.message ?: "Search failed." }
            searching = false
        }
    }
    LaunchedEffect(selectedVideo?.externalVideoResult?.url) {
        val result = selectedVideo?.externalVideoResult ?: return@LaunchedEffect
        videoInfo = null
        videoInfoError = null
        videoInfoLoading = true
        runCatching { DesktopBackend.externalVideoInfo(result) }
            .onSuccess { videoInfo = it }
            .onFailure { videoInfoError = it.message ?: "Could not load video formats." }
        videoInfoLoading = false
    }
    fun download(track: MusicTrack) {
        scope.launch {
            downloadMessage = "Downloading ${track.title}…"
            runCatching {
                if (track.kind == "song") DesktopBackend.download(track).toString()
                else "${DesktopBackend.downloadCollection(track)} tracks"
            }
                .onSuccess { result -> downloadMessage = "Saved $result" }
                .onFailure { downloadMessage = it.message ?: "Download failed." }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 32.dp, end = 32.dp, bottom = 32.dp)) {
        ScreenTitle("Search", "Discover something worth playing.")
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            placeholder = { Text(if (videoOnly) "Search videos or paste a video URL" else "Songs, artists, albums, playlists") },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = XanColors.Border,
                unfocusedBorderColor = XanColors.Border,
                focusedContainerColor = XanColors.Card,
                unfocusedContainerColor = XanColors.Card,
            ),
        )
        if (query.isBlank() && DesktopBackend.searchHistoryEnabled() && recentSearches.isNotEmpty()) {
            SectionTitle("Recent searches", action = "Clear", onAction = {
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { DesktopBackend.clearSearchHistory() } }
                        .onSuccess { recentSearches = emptyList() }
                        .onFailure { onlineError = it.message ?: "Could not clear search history." }
                }
            })
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                recentSearches.forEach { term -> FilterPill(term, selected = false) { query = term } }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            FilterPill("Videos only", videoOnly) {
                videoOnly = !videoOnly
                DesktopBackend.setVideoOnlySearch(videoOnly)
            }
            if (!videoOnly) FilterPill("YouTube video fallback", fallback) { fallback = !fallback; DesktopBackend.setFallbackEnabled(fallback) }
        }
        if (query.isBlank()) {
            SearchDiscoveryTabs(discoveryTab, onSelected = {
                discoveryTab = it
                discoveryError = null
            })
            when {
                discoveryLoading -> EmptyPanel(Icons.Rounded.Public, "Loading $discoveryTab…", "Fetching current music discovery from its source.")
                discoveryError != null -> Column {
                    EmptyPanel(Icons.Rounded.ErrorOutline, "Could not load $discoveryTab", discoveryError!!)
                    TextButton(onClick = {
                        when (discoveryTab) {
                            "Suggestions" -> charts = null
                            "Explore" -> moodSections = null
                            else -> explorePage = null
                        }
                        discoveryReload++
                    }) { Text("Try again") }
                }
                discoveryTab == "Explore" -> ExploreDiscoveryContent(
                    sections = moodSections.orEmpty(),
                    onMoodSelected = { item ->
                        scope.launch {
                            discoveryLoading = true
                            discoveryError = null
                            runCatching { DesktopBackend.browseDiscovery(item.endpoint) }
                                .onSuccess { selectedBrowseTitle = item.title; selectedBrowse = it }
                                .onFailure { discoveryError = it.message ?: "Could not open ${item.title}." }
                            discoveryLoading = false
                        }
                    },
                )
                discoveryTab == "Album" -> AlbumDiscoveryContent(
                    albums = explorePage?.newReleaseAlbums.orEmpty().mapIndexed { index, album ->
                        MusicTrack(album.title, album.artists?.joinToString { it.name } ?: "Album", album.year?.toString() ?: "New release", listOf(XanColors.CardLight, XanColors.Card), "♫", source = "YouTube Music", artworkUrl = album.thumbnail, browseId = album.browseId, collectionId = album.playlistId, kind = "album")
                    },
                    onAlbumSelected = { selectedCollection = it },
                )
                discoveryTab == "Suggestions" -> SuggestionsDiscoveryContent(
                    charts = charts,
                    onSongSelected = { item ->
                        scope.launch {
                            discoveryLoading = true
                            discoveryError = null
                            runCatching { DesktopBackend.searchSuggestion("${item.title} ${item.artist}", "song") }
                                .onSuccess { match -> if (match != null) onTrackSelected(match) else discoveryError = "No YouTube Music match found for ${item.title}." }
                                .onFailure { discoveryError = it.message ?: "Could not find ${item.title} on YouTube Music." }
                            discoveryLoading = false
                        }
                    },
                    onArtistSelected = { item ->
                        scope.launch {
                            discoveryLoading = true
                            runCatching { DesktopBackend.searchSuggestion(item.title, "artist") }
                                .onSuccess { match -> if (match != null) selectedArtist = match else discoveryError = "No YouTube Music artist match found." }
                                .onFailure { discoveryError = it.message ?: "Could not find this artist." }
                            discoveryLoading = false
                        }
                    },
                    onAlbumSelected = { item ->
                        scope.launch {
                            discoveryLoading = true
                            runCatching { DesktopBackend.searchSuggestion("${item.title} ${item.artist}", "album") }
                                .onSuccess { match -> if (match != null) selectedCollection = match else discoveryError = "No YouTube Music album match found." }
                                .onFailure { discoveryError = it.message ?: "Could not find this album." }
                            discoveryLoading = false
                        }
                    },
                )
            }
        }
        when {
                searching -> EmptyPanel(Icons.Rounded.Public, if (videoOnly) "Searching video sources…" else "Searching YouTube Music…", if (videoOnly) "Looking across YouTube and supported video services." else "Looking across songs, artists, albums, and playlists.")
                onlineError != null -> EmptyPanel(Icons.Rounded.ErrorOutline, "Search failed", onlineError!!)
                query.isBlank() -> Unit
                onlineResults.isEmpty() -> EmptyPanel(Icons.Rounded.Search, "No results", if (videoOnly) "Try another video title or supported video URL." else "Try another song, artist, or album.")
                else -> {
                    SectionTitle(if (videoOnly) "Video results" else "YouTube Music results")
                    Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(22.dp)).padding(6.dp)) {
                        onlineResults.forEachIndexed { index, track ->
                            TrackRow(track, index, onDownload = if (track.kind == "video") null else ({
                                scope.launch {
                                    downloadMessage = if (track.kind == "song") "Downloading ${track.title}…" else "Downloading ${track.title} collection…"
                                    runCatching {
                                        if (track.kind == "song") { DesktopBackend.download(track); 1 }
                                        else DesktopBackend.downloadCollection(track)
                                    }.onSuccess { downloadMessage = "Downloaded $it track${if (it == 1) "" else "s"} from ${track.title}." }
                                        .onFailure { downloadMessage = it.message ?: "Download failed." }
                                }
                            }), onQueue = if (track.kind == "song") ({ DesktopPlayer.enqueueNext(track) }) else null) {
                                when (track.kind) {
                                    "song" -> onTrackSelected(track)
                                    "video" -> if (track.localPath != null) onTrackSelected(track) else {
                                        selectedVideo = track
                                        videoInfo = null
                                        videoDownloadMessage = null
                                    }
                                    "artist" -> selectedArtist = track
                                    else -> selectedCollection = track
                                }
                            }
                        }
                    }
                    downloadMessage?.let { Text(it, Modifier.padding(12.dp), color = XanColors.Muted) }
                }
        }
    }
    selectedVideo?.let { video ->
        ExternalVideoFormatsDialog(
            video = video,
            info = videoInfo,
            loading = videoInfoLoading,
            error = videoInfoError,
            downloadMessage = videoDownloadMessage,
            onDismiss = { selectedVideo = null },
            onPlay = { format ->
                val info = videoInfo ?: return@ExternalVideoFormatsDialog
                onTrackSelected(video.copy(externalVideoInfo = info, externalVideoFormat = format))
                selectedVideo = null
            },
            onDownload = { format ->
                val info = videoInfo ?: return@ExternalVideoFormatsDialog
                scope.launch {
                    videoDownloadMessage = "Downloading ${video.title}…"
                    runCatching { DesktopBackend.downloadExternalVideo(video.copy(externalVideoInfo = info, externalVideoFormat = format)) }
                        .onSuccess { videoDownloadMessage = "Saved video to $it" }
                        .onFailure { videoDownloadMessage = it.message ?: "Video download failed." }
                }
            },
        )
    }
}

@Composable
private fun ExternalVideoFormatsDialog(
    video: MusicTrack,
    info: com.music.innertube.ExternalVideoInfo?,
    loading: Boolean,
    error: String?,
    downloadMessage: String?,
    onDismiss: () -> Unit,
    onPlay: (com.music.innertube.ExternalVideoFormat) -> Unit,
    onDownload: (com.music.innertube.ExternalVideoFormat) -> Unit,
) {
    val formats = info?.let(DesktopBackend::playableExternalVideoFormats).orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.widthIn(max = 560.dp).heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("${video.artist} · ${video.album}", color = XanColors.Muted)
                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = XanColors.Accent)
                        Text("Loading supported formats…", color = XanColors.Muted)
                    }
                    error != null -> Text(error, color = XanColors.Muted)
                    formats.isEmpty() -> Text("This source has no H.264 MP4 or adaptive format that the desktop player can prepare.", color = XanColors.Muted)
                    else -> formats.forEach { format ->
                        Row(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(12.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                val adaptive = format.videoOnly || format.adaptiveManifest
                                Text(format.resolution.ifBlank { format.extension.uppercase() } + if (adaptive) " · adaptive" else "", color = XanColors.Text, fontWeight = FontWeight.SemiBold)
                                Text("${format.extension.uppercase()}${format.codec?.let { " · $it" }.orEmpty()}${if (adaptive) " · combines audio/video before playback" else ""}", color = XanColors.Muted, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { onPlay(format) }) { Text("Play") }
                            TextButton(onClick = { onDownload(format) }) { Text("Download") }
                        }
                    }
                }
                downloadMessage?.let { Text(it, color = XanColors.Muted) }
                Text("Adaptive video is combined before playback with yt-dlp and FFmpeg. Install FFmpeg and add it to PATH to use adaptive formats.", color = XanColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SearchDiscoveryTabs(selected: String, onSelected: (String) -> Unit) {
    val tabs = listOf("Explore", "Suggestions", "Album")
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 16.dp)
            .clip(RoundedCornerShape(22.dp)).background(XanColors.Card).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEach { tab ->
            val active = selected == tab
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                    .background(if (active) Color(0xFF3A82F6) else Color.Transparent)
                    .clickable { onSelected(tab) }.padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(tab, color = if (active) Color.White else XanColors.Muted, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ExploreDiscoveryContent(
    sections: List<com.music.innertube.pages.MoodAndGenres>,
    onMoodSelected: (com.music.innertube.pages.MoodAndGenres.Item) -> Unit,
) {
    if (sections.isEmpty()) {
        EmptyPanel(Icons.Rounded.Explore, "Nothing to explore yet", "YouTube Music did not return any moods or genres.")
        return
    }
    sections.forEach { section ->
        SectionTitle(section.title)
        section.items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { item ->
                    Box(
                        Modifier.weight(1f).padding(bottom = 10.dp).heightIn(min = 64.dp)
                            .clip(RoundedCornerShape(14.dp)).background(XanColors.CardLight)
                            .clickable { onMoodSelected(item) }.padding(horizontal = 16.dp, vertical = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(item.title, color = XanColors.Text, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AlbumDiscoveryContent(albums: List<MusicTrack>, onAlbumSelected: (MusicTrack) -> Unit) {
    SectionTitle("New releases")
    if (albums.isEmpty()) {
        EmptyPanel(Icons.Rounded.Album, "No new releases", "YouTube Music did not return new albums right now.")
        return
    }
    albums.chunked(4).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            row.forEach { album ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).clickable { onAlbumSelected(album) }.padding(8.dp)) {
                    Artwork(album, 150.dp, corner = 14.dp, modifier = Modifier.fillMaxWidth())
                    Text(album.title, Modifier.padding(top = 8.dp), color = XanColors.Text, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(album.artist, color = XanColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun SuggestionsDiscoveryContent(
    charts: AppleCharts?,
    onSongSelected: (AppleChartItem) -> Unit,
    onArtistSelected: (AppleChartItem) -> Unit,
    onAlbumSelected: (AppleChartItem) -> Unit,
) {
    if (charts == null) return
    Text("System Default", Modifier.padding(top = 18.dp), color = XanColors.Muted, style = MaterialTheme.typography.labelLarge)
    Text("Apple Music Top 100", Modifier.padding(top = 5.dp, bottom = 10.dp), color = XanColors.Text, fontFamily = XanBrandFont, fontWeight = FontWeight.Bold, fontSize = 31.sp)
    charts.songs.forEach { item -> ChartSuggestionRow(item, "song", onClick = { onSongSelected(item) }) }
    SectionTitle("Trending artists")
    charts.artists.forEach { item -> ChartSuggestionRow(item, "artist", onClick = { onArtistSelected(item) }) }
    SectionTitle("Top albums · Apple Music")
    charts.albums.forEach { item -> ChartSuggestionRow(item, "album", onClick = { onAlbumSelected(item) }) }
}

@Composable
private fun ChartSuggestionRow(item: AppleChartItem, kind: String, onClick: () -> Unit) {
    val track = remember(item) {
        MusicTrack(item.title, item.artist, "Apple Music chart #${item.rank}", listOf(XanColors.Accent, Color(0xFF6B2941)), "♫", source = "Apple Music", artworkUrl = item.artworkUrl, kind = kind)
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 18.dp)) {
            Text(item.title, color = XanColors.Text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.artist, color = XanColors.Accent, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("#${item.rank} · Apple Music Top 100", Modifier.padding(top = 4.dp), color = XanColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
        Artwork(track, 82.dp, corner = 15.dp)
    }
}

@Composable
private fun DiscoveryBrowseScreen(
    title: String,
    browse: DiscoveryBrowse,
    onBack: () -> Unit,
    onItemSelected: (MusicTrack) -> Unit,
) {
    var downloadStatus by remember(title) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 32.dp, end = 32.dp, bottom = 32.dp)) {
        TextButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Explore")
        }
        ScreenTitle(title)
        if (browse.sections.isEmpty()) EmptyPanel(Icons.Rounded.Explore, "No items found", "This YouTube Music page did not return any browseable items.")
        browse.sections.forEach { (sectionTitle, tracks) ->
            SectionTitle(sectionTitle.ifBlank { title })
            Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(22.dp)).padding(6.dp)) {
                tracks.forEachIndexed { index, track ->
                    TrackRow(track, index, onDownload = {
                        scope.launch {
                            downloadStatus = "Downloading ${track.title}…"
                            runCatching {
                                if (track.kind == "song") { DesktopBackend.download(track); 1 }
                                else DesktopBackend.downloadCollection(track)
                            }.onSuccess { count ->
                                downloadStatus = if (count == 0) "No downloadable songs were found for ${track.title}."
                                else "Downloaded $count track${if (count == 1) "" else "s"} from ${track.title}."
                            }.onFailure { downloadStatus = it.message ?: "Download failed." }
                        }
                    }) { onItemSelected(track) }
                }
            }
        }
        downloadStatus?.let { Text(it, Modifier.padding(12.dp), color = XanColors.Muted) }
    }
}

@Composable
private fun ArtistDetailScreen(
    artist: MusicTrack,
    onBack: () -> Unit,
    onTrackSelected: (MusicTrack) -> Unit,
    onCollectionSelected: (MusicTrack) -> Unit,
) {
    var details by remember(artist.collectionId) { mutableStateOf<ArtistDetails?>(null) }
    var loading by remember(artist.collectionId) { mutableStateOf(true) }
    var error by remember(artist.collectionId) { mutableStateOf<String?>(null) }
    var reload by remember(artist.collectionId) { mutableIntStateOf(0) }
    var downloading by remember(artist.collectionId) { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var saved by remember(artist.collectionId, artist.browseId) { mutableStateOf(DesktopBackend.isCollectionSaved(artist)) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(artist.collectionId, reload) {
        loading = true
        error = null
        runCatching { DesktopBackend.artistDetails(artist) }
            .onSuccess { details = it }
            .onFailure { error = it.message ?: "Could not load this artist." }
        loading = false
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 32.dp, end = 32.dp, bottom = 32.dp)) {
        TextButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Search results")
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Artwork(artist, 176.dp, corner = 88.dp)
            Column(Modifier.weight(1f)) {
                Text(artist.title, color = XanColors.Text, fontFamily = XanBrandFont, fontSize = 38.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                details?.let { info ->
                    val stats = listOfNotNull(info.subscriberCount, info.monthlyListeners).distinct()
                    if (stats.isNotEmpty()) Text(stats.joinToString(" · "), color = XanColors.Muted, modifier = Modifier.padding(top = 6.dp))
                    info.description?.let { description ->
                        Text(description.take(520).let { if (description.length > 520) "$it…" else it }, color = XanColors.Text.copy(alpha = .82f), modifier = Modifier.padding(top = 12.dp), maxLines = 5, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 14.dp), color = XanColors.Accent)
                error?.let { Text(it, color = XanColors.Accent, modifier = Modifier.padding(top = 10.dp)) }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        saved = DesktopBackend.setCollectionSaved(artist, !saved)
                        message = if (saved) "${artist.title} saved to your library." else "${artist.title} removed from your library."
                    }) { Text(if (saved) "Saved to library" else "Save artist") }
                    OutlinedButton(enabled = !loading && !downloading, onClick = {
                        scope.launch {
                            downloading = true
                            message = "Downloading ${artist.title}…"
                            runCatching { DesktopBackend.downloadCollection(artist) }
                                .onSuccess { count -> message = if (count == 0) "No downloadable songs were found for ${artist.title}." else "Downloaded $count tracks from ${artist.title}." }
                                .onFailure { message = it.message ?: "Could not download this artist." }
                            downloading = false
                        }
                    }) { Text(if (downloading) "Downloading artist…" else "Download artist") }
                    if (error != null) TextButton(onClick = { reload++ }) { Text("Retry") }
                }
            }
        }
        details?.sections?.forEach { section ->
            SectionTitle(section.title.ifBlank { "More from ${artist.title}" })
            Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(22.dp)).padding(6.dp)) {
                section.items.forEachIndexed { index, item ->
                    TrackRow(item, index, onDownload = {
                        scope.launch {
                            message = "Downloading ${item.title}…"
                            runCatching {
                                if (item.kind == "song") { DesktopBackend.download(item); 1 }
                                else DesktopBackend.downloadCollection(item)
                            }.onSuccess { count -> message = "Downloaded $count track${if (count == 1) "" else "s"} from ${item.title}." }
                                .onFailure { message = it.message ?: "Download failed." }
                        }
                    }, onQueue = if (item.kind == "song") ({ DesktopPlayer.enqueueNext(item) }) else null) {
                        when (item.kind) {
                            "song" -> onTrackSelected(item)
                            "album", "playlist" -> onCollectionSelected(item)
                        }
                    }
                }
            }
        }
        if (!loading && error == null && details?.sections.isNullOrEmpty()) {
            EmptyPanel(Icons.Rounded.MusicNote, "No artist releases found", "YouTube Music did not return songs, albums, or playlists for this artist.")
        }
        message?.let { Text(it, Modifier.padding(12.dp), color = XanColors.Muted) }
    }
}

@Composable
private fun CollectionDetailScreen(
    collection: MusicTrack,
    onBack: () -> Unit,
    onTrackSelected: (MusicTrack) -> Unit,
    backLabel: String = "Search results",
    onPlaylistImported: (CatalogPlaylist) -> Unit = {},
) {
    var details by remember(collection.collectionId, collection.browseId) { mutableStateOf<CollectionDetails?>(null) }
    var loading by remember(collection.collectionId, collection.browseId) { mutableStateOf(true) }
    var error by remember(collection.collectionId, collection.browseId) { mutableStateOf<String?>(null) }
    var reload by remember(collection.collectionId, collection.browseId) { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var saved by remember(collection.collectionId, collection.browseId) { mutableStateOf(DesktopBackend.isCollectionSaved(collection)) }
    var importingPlaylist by remember(collection.collectionId, collection.browseId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(collection.collectionId, collection.browseId, reload) {
        loading = true
        error = null
        runCatching { DesktopBackend.collectionDetails(collection) }
            .onSuccess { details = it }
            .onFailure { error = it.message ?: "Could not load this ${collection.kind}." }
        loading = false
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 32.dp, end = 32.dp, bottom = 32.dp)) {
        TextButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(backLabel)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Artwork(collection, 176.dp, corner = 24.dp)
            Column(Modifier.weight(1f)) {
                Text(collection.kind.replaceFirstChar(Char::uppercase), color = XanColors.Accent, style = MaterialTheme.typography.labelLarge)
                Text(collection.title, color = XanColors.Text, fontFamily = XanBrandFont, fontSize = 38.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(collection.artist, color = XanColors.Muted, modifier = Modifier.padding(top = 6.dp))
                Text(collection.album, color = XanColors.Muted, style = MaterialTheme.typography.bodyMedium)
                details?.description?.let { description ->
                    Text(description, color = XanColors.Text.copy(alpha = .82f), modifier = Modifier.padding(top = 12.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 14.dp), color = XanColors.Accent)
                OutlinedButton(onClick = {
                    saved = DesktopBackend.setCollectionSaved(collection, !saved)
                    message = if (saved) "${collection.title} saved to your library." else "${collection.title} removed from your library."
                }, modifier = Modifier.padding(top = 12.dp)) { Text(if (saved) "Saved to library" else "Save to library") }
                if (collection.kind == "playlist" && collection.source?.contains("YouTube Music", ignoreCase = true) == true && DesktopBackend.youtubeConnected()) {
                    OutlinedButton(
                        enabled = !loading && !importingPlaylist,
                        onClick = {
                            importingPlaylist = true
                            scope.launch {
                                runCatching { DesktopBackend.importYoutubePlaylist(collection) }
                                    .onSuccess { playlist ->
                                        onPlaylistImported(playlist)
                                        message = "Imported ${playlist.trackCount} songs from ${playlist.name} into local playlists."
                                    }
                                    .onFailure { message = it.message ?: "Could not import this YouTube Music playlist." }
                                importingPlaylist = false
                            }
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text(if (importingPlaylist) "Importing playlist…" else "Import or refresh local copy") }
                }
                Button(
                    enabled = !loading,
                    onClick = {
                        scope.launch {
                            message = "Downloading ${collection.title}…"
                            runCatching { DesktopBackend.downloadCollection(collection) }
                                .onSuccess { count -> message = "Downloaded $count track${if (count == 1) "" else "s"} from ${collection.title}." }
                                .onFailure { message = it.message ?: "Could not download this ${collection.kind}." }
                        }
                    },
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text("Download ${collection.kind}") }
                error?.let { Text(it, color = XanColors.Accent, modifier = Modifier.padding(top = 10.dp)) }
                if (error != null) TextButton(onClick = { reload++ }) { Text("Retry loading") }
            }
        }
        details?.tracks?.let { tracks ->
            SectionTitle("${tracks.size} songs")
            Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(22.dp)).padding(6.dp)) {
                tracks.forEachIndexed { index, track ->
                    TrackRow(track, index, onDownload = {
                        scope.launch {
                            message = "Downloading ${track.title}…"
                            runCatching { DesktopBackend.download(track) }
                                .onSuccess { message = "Saved ${it.fileName}." }
                                .onFailure { message = it.message ?: "Download failed." }
                        }
                    }, onQueue = { DesktopPlayer.enqueueNext(track) }) { onTrackSelected(track) }
                }
            }
            if (tracks.isEmpty() && !loading) EmptyPanel(Icons.Rounded.MusicNote, "No songs found", "YouTube Music did not return songs for this ${collection.kind}.")
        }
        message?.let { Text(it, Modifier.padding(12.dp), color = XanColors.Muted) }
    }
}

@Composable
internal fun LibraryScreen(initialView: String, onTrackSelected: (MusicTrack) -> Unit) {
    var filter by remember(initialView) { mutableStateOf(if (initialView == "Downloads") "Library" else initialView) }
    var sort by remember { mutableStateOf(DesktopBackend.librarySort()) }
    var descending by remember { mutableStateOf(DesktopBackend.librarySortDescending()) }
    var gridView by remember { mutableStateOf(DesktopBackend.libraryGrid()) }
    var openedCollection by remember { mutableStateOf<MusicTrack?>(null) }
    var openedArtist by remember { mutableStateOf<MusicTrack?>(null) }
    var showDownloads by remember(initialView) { mutableStateOf(initialView == "Downloads") }
    var collections by remember { mutableStateOf(DesktopBackend.savedCollections()) }
    var favoriteTracks by remember { mutableStateOf(DesktopBackend.favoriteTracks()) }
    var topTracks by remember { mutableStateOf(DesktopBackend.topPlayedTracks()) }
    var cachedTracks by remember { mutableStateOf(DesktopBackend.cachedAudioTracks()) }
    var localTracks by remember { mutableStateOf(DesktopBackend.catalogTracks()) }
    var libraryStatus by remember { mutableStateOf("") }
    var playlists by remember { mutableStateOf(DesktopBackend.playlists()) }
    var openedPlaylist by remember { mutableStateOf<CatalogPlaylist?>(null) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var newPlaylistDescription by remember { mutableStateOf("") }
    var showEditPlaylist by remember { mutableStateOf(false) }
    var editPlaylistName by remember { mutableStateOf("") }
    var editPlaylistDescription by remember { mutableStateOf("") }
    var importReport by remember { mutableStateOf<PlaylistImportReport?>(null) }
    var uploadedTracks by remember { mutableStateOf<List<MusicTrack>?>(null) }
    var uploadedLoading by remember { mutableStateOf(false) }
    var uploadedError by remember { mutableStateOf<String?>(null) }
    var uploadedReload by remember { mutableIntStateOf(0) }
    var youtubeLibraryTracks by remember { mutableStateOf(DesktopBackend.youtubeMusicLibraryItems()) }
    var youtubeLibrarySync by remember { mutableStateOf(DesktopBackend.youtubeMusicLibrarySync()) }
    var youtubeLibrarySyncing by remember { mutableStateOf(false) }
    var youtubeLibraryError by remember { mutableStateOf<String?>(null) }
    var youtubeLibraryMessage by remember { mutableStateOf<String?>(null) }
    var youtubeAccountConnected by remember { mutableStateOf(DesktopBackend.youtubeConnected()) }
    val scope = rememberCoroutineScope()
    val sortedPlaylists = remember(playlists, sort, descending) {
        val ordered = when (sort) {
            "Name" -> playlists.sortedBy { it.name.lowercase() }
            "Last updated" -> playlists.sortedBy { it.updatedAt }
            "Playlist order" -> playlists.sortedBy { it.position }
            else -> playlists.sortedBy { it.createdAt }
        }
        if (descending) ordered.reversed() else ordered
    }
    fun sortTracks(items: List<MusicTrack>): List<MusicTrack> = when (sort) {
        "Name" -> if (descending) items.sortedByDescending { it.title.lowercase() } else items.sortedBy { it.title.lowercase() }
        "Playlist order" -> items
        else -> if (descending) items else items.reversed()
    }
    LaunchedEffect(initialView) {
        collections = DesktopBackend.savedCollections()
        favoriteTracks = DesktopBackend.favoriteTracks()
        topTracks = DesktopBackend.topPlayedTracks()
        youtubeLibraryTracks = DesktopBackend.youtubeMusicLibraryItems()
        youtubeLibrarySync = DesktopBackend.youtubeMusicLibrarySync()
        youtubeAccountConnected = DesktopBackend.youtubeConnected()
    }
    LaunchedEffect(filter) { if (filter == "Cached") cachedTracks = DesktopBackend.cachedAudioTracks() }
    LaunchedEffect(filter, uploadedReload) {
        if (filter == "Uploaded" && uploadedTracks == null) {
            uploadedLoading = true
            uploadedError = null
            runCatching { DesktopBackend.uploadedSongs() }
                .onSuccess { uploadedTracks = it }
                .onFailure { uploadedError = it.message ?: "Could not load uploaded songs." }
            uploadedLoading = false
        }
    }
    openedPlaylist?.let { playlist ->
        val tracks = remember(playlist, playlists) { DesktopBackend.playlistTracks(playlist.id) }
        val playlistPosition = playlists.sortedBy { it.position }.indexOfFirst { it.id == playlist.id }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 32.dp, end = 32.dp, bottom = 32.dp)) {
            TextButton(onClick = { openedPlaylist = null; playlists = DesktopBackend.playlists() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Playlists")
            }
            ScreenTitle(playlist.name, "${tracks.size} songs")
            playlist.description?.takeIf(String::isNotBlank)?.let {
                Text(it, Modifier.padding(bottom = 8.dp), color = XanColors.Muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    editPlaylistName = playlist.name
                    editPlaylistDescription = playlist.description.orEmpty()
                    showEditPlaylist = true
                }) { Text("Edit details") }
                OutlinedButton(enabled = playlistPosition > 0, onClick = {
                    runCatching { DesktopBackend.movePlaylist(playlist.id, playlistPosition - 1) }
                        .onSuccess {
                            playlists = DesktopBackend.playlists()
                            openedPlaylist = playlists.firstOrNull { it.id == playlist.id }
                            sort = "Playlist order"
                            DesktopBackend.setLibrarySort(sort)
                        }
                        .onFailure { libraryStatus = it.message ?: "Could not reorder playlists." }
                }) { Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move playlist up") }
                OutlinedButton(enabled = playlistPosition >= 0 && playlistPosition < playlists.size - 1, onClick = {
                    runCatching { DesktopBackend.movePlaylist(playlist.id, playlistPosition + 1) }
                        .onSuccess {
                            playlists = DesktopBackend.playlists()
                            openedPlaylist = playlists.firstOrNull { it.id == playlist.id }
                            sort = "Playlist order"
                            DesktopBackend.setLibrarySort(sort)
                        }
                        .onFailure { libraryStatus = it.message ?: "Could not reorder playlists." }
                }) { Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move playlist down") }
                OutlinedButton(onClick = {
                    runCatching { DesktopBackend.addLocalLibraryToPlaylist(playlist.id); playlists = DesktopBackend.playlists() }
                        .onSuccess { libraryStatus = "Added indexed local songs to ${playlist.name}." }
                        .onFailure { libraryStatus = it.message ?: "Could not add songs." }
                }) { Text("Add local songs") }
                OutlinedButton(enabled = DesktopPlayer.state.value.track != null, onClick = {
                    val current = DesktopPlayer.state.value.track ?: return@OutlinedButton
                    runCatching { DesktopBackend.addTrackToPlaylist(playlist.id, current).also { playlists = DesktopBackend.playlists() } }
                        .onSuccess { added -> libraryStatus = if (added) "Added ${current.title} to ${playlist.name}." else "${current.title} is already in ${playlist.name}." }
                        .onFailure { libraryStatus = it.message ?: "Could not add the current song." }
                }) { Text("Add current song") }
                OutlinedButton(onClick = {
                    runCatching { DesktopBackend.deletePlaylist(playlist.id); playlists = DesktopBackend.playlists(); openedPlaylist = null }
                        .onFailure { libraryStatus = it.message ?: "Could not delete playlist." }
                }) { Text("Delete playlist") }
            }
            if (tracks.isEmpty()) EmptyPanel(Icons.Rounded.QueueMusic, "This playlist is empty", "Add local songs or the current song to start your playlist.")
            else Column(Modifier.fillMaxWidth().padding(top = 12.dp).background(XanColors.Card, RoundedCornerShape(22.dp)).padding(6.dp)) {
                tracks.forEachIndexed { index, track ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            TrackRow(track, index, onQueue = { DesktopPlayer.enqueueNext(track) }) { onTrackSelected(track) }
                        }
                        IconButton(enabled = index > 0, onClick = {
                            runCatching { DesktopBackend.movePlaylistTrack(playlist.id, index, index - 1); playlists = DesktopBackend.playlists() }
                                .onFailure { libraryStatus = it.message ?: "Could not reorder the playlist." }
                        }) { Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move ${track.title} up", tint = XanColors.Muted) }
                        IconButton(enabled = index < tracks.lastIndex, onClick = {
                            runCatching { DesktopBackend.movePlaylistTrack(playlist.id, index, index + 1); playlists = DesktopBackend.playlists() }
                                .onFailure { libraryStatus = it.message ?: "Could not reorder the playlist." }
                        }) { Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move ${track.title} down", tint = XanColors.Muted) }
                        IconButton(onClick = {
                            runCatching { DesktopBackend.removeTrackFromPlaylist(playlist.id, track); playlists = DesktopBackend.playlists() }
                                .onSuccess { libraryStatus = "Removed ${track.title} from ${playlist.name}." }
                                .onFailure { libraryStatus = it.message ?: "Could not remove this song." }
                        }) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove ${track.title} from ${playlist.name}", tint = XanColors.Muted) }
                    }
                }
            }
            if (libraryStatus.isNotBlank()) Text(libraryStatus, Modifier.padding(top = 8.dp), color = XanColors.Muted)
        }
        if (showEditPlaylist) AlertDialog(
            onDismissRequest = { showEditPlaylist = false },
            title = { Text("Edit playlist") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(editPlaylistName, { editPlaylistName = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(editPlaylistDescription, { editPlaylistDescription = it }, label = { Text("Description (optional)") }, minLines = 2, maxLines = 4)
                }
            },
            confirmButton = { TextButton(enabled = editPlaylistName.isNotBlank(), onClick = {
                runCatching { DesktopBackend.updatePlaylistMetadata(playlist.id, editPlaylistName, editPlaylistDescription) }
                    .onSuccess {
                        playlists = DesktopBackend.playlists()
                        openedPlaylist = playlists.firstOrNull { it.id == playlist.id }
                        showEditPlaylist = false
                        libraryStatus = "Playlist details saved."
                    }
                    .onFailure { libraryStatus = it.message ?: "Could not update playlist details." }
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showEditPlaylist = false }) { Text("Cancel") } },
        )
        return
    }
    openedCollection?.let { collection ->
        CollectionDetailScreen(collection, {
            collections = DesktopBackend.savedCollections()
            openedCollection = null
        }, onTrackSelected, backLabel = "Library", onPlaylistImported = { playlists = DesktopBackend.playlists() })
        return
    }
    openedArtist?.let { artist ->
        ArtistDetailScreen(artist, {
            collections = DesktopBackend.savedCollections()
            openedArtist = null
        }, onTrackSelected) { openedCollection = it }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 32.dp, end = 32.dp, bottom = 32.dp)) {
        if (showDownloads) {
            TextButton(onClick = { showDownloads = false }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Library")
            }
            ScreenTitle("Downloads", "Audio saved on this device")
            DownloadedAudioList(onTrackSelected)
            return@Column
        }
        Text("Library", Modifier.padding(top = 10.dp, bottom = 12.dp), color = XanColors.Accent, fontFamily = XanBrandFont, fontWeight = FontWeight.Bold, fontSize = 42.sp)
        TextButton(onClick = { newPlaylistName = ""; newPlaylistDescription = ""; showCreatePlaylist = true }) {
            Icon(Icons.Rounded.QueueMusic, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Artist playlist")
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            listOf("Playlists", "Songs", "Albums", "Artists", "Online playlists", "YouTube Music").forEach { label ->
                FilterPill(label, filter == label) { filter = if (filter == label) "Library" else label }
            }
        }
        LibraryToolbar(
            sort = sort,
            descending = descending,
            gridView = gridView,
            onSort = { sort = it; DesktopBackend.setLibrarySort(it) },
            onDirection = { descending = !descending; DesktopBackend.setLibrarySortDescending(descending) },
            onView = { gridView = it; DesktopBackend.setLibraryGrid(it) },
        )
        when (filter) {
            "Songs" -> {
                SectionTitle("Your songs")
                if (localTracks.isNotEmpty()) Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(22.dp)).padding(6.dp)) {
                    sortTracks(localTracks).forEachIndexed { index, track -> TrackRow(track, index, onQueue = { DesktopPlayer.enqueueNext(track) }) { onTrackSelected(track) } }
                } else EmptyPanel(Icons.Rounded.MusicNote, "No local songs indexed", "Add a music folder from Local Music to scan your collection.")
            }
            "Liked" -> {
                SectionTitle("Favorites", "${favoriteTracks.size} tracks")
                if (favoriteTracks.isEmpty()) EmptyPanel(Icons.Rounded.Star, "No favorites yet", "Use the heart beside the player controls to save the current track here.")
                else Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(22.dp)).padding(6.dp)) {
                    sortTracks(favoriteTracks).forEachIndexed { index, track ->
                        TrackRow(track, index, onQueue = { DesktopPlayer.enqueueNext(track) }) { onTrackSelected(track) }
                    }
                }
            }
            "My top 50" -> {
                SectionTitle("My top 50", "${topTracks.size} tracks")
                if (topTracks.isEmpty()) EmptyPanel(Icons.Rounded.Leaderboard, "Your top songs will appear here", "Play music for long enough to add it to your listening history.")
                else Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(22.dp)).padding(6.dp)) {
                    sortTracks(topTracks).forEachIndexed { index, track ->
                        TrackRow(track, index, onQueue = { DesktopPlayer.enqueueNext(track) }) { onTrackSelected(track) }
                    }
                }
            }
            "Cached" -> {
                SectionTitle("Cached songs", "${cachedTracks.size} tracks · ${formatStorageSize(DesktopBackend.cachedAudioBytes())}")
                if (cachedTracks.isEmpty()) EmptyPanel(Icons.Rounded.Cached, "No cached songs", "Cache a playing YouTube Music track from the player to keep it available offline.")
                else Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(22.dp)).padding(6.dp)) {
                    cachedTracks.forEachIndexed { index, entry ->
                        val track = DesktopBackend.toMusicTrack(entry.track)
                        TrackRow(track, index, onQueue = { DesktopPlayer.enqueueNext(track) }, onRemoveCache = {
                            runCatching { DesktopBackend.removeCachedAudioTrack(entry.track.id) }
                                .onSuccess { cachedTracks = DesktopBackend.cachedAudioTracks() }
                                .onFailure { libraryStatus = it.message ?: "Could not remove this cached track." }
                        }) { onTrackSelected(track) }
                    }
                }
            }
            "Local Music" -> {
                SectionTitle("Local Music")
                Text("Scan folders for audio files and read their embedded tags.", color = XanColors.Muted)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; isMultiSelectionEnabled = false }
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            runCatching {
                                DesktopBackend.addMusicFolder(chooser.selectedFile.toPath())
                                DesktopBackend.refreshLocalMusicWatcher()
                                val count = DesktopBackend.scanMusicLibrary()
                                localTracks = DesktopBackend.catalogTracks()
                                libraryStatus = "Indexed $count audio files."
                            }.onFailure { libraryStatus = it.message ?: "Folder scan failed." }
                        }
                    }) { Text("Add music folder") }
                    OutlinedButton(onClick = {
                        runCatching { val count = DesktopBackend.scanMusicLibrary(); localTracks = DesktopBackend.catalogTracks(); libraryStatus = "Indexed $count audio files." }
                            .onFailure { libraryStatus = it.message ?: "Folder scan failed." }
                    }) { Text("Scan folders") }
                }
                DesktopBackend.musicFolders().forEach { root ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(root.toString(), Modifier.weight(1f), color = XanColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = {
                            DesktopBackend.removeMusicFolder(root)
                            DesktopBackend.refreshLocalMusicWatcher()
                            runCatching { DesktopBackend.scanMusicLibrary() }
                            localTracks = DesktopBackend.catalogTracks()
                        }) { Text("Remove") }
                    }
                }
                if (libraryStatus.isNotBlank()) Text(libraryStatus, Modifier.padding(top = 8.dp), color = XanColors.Muted)
                if (localTracks.isNotEmpty()) {
                    SectionTitle("Indexed songs · ${localTracks.size}")
                    Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(22.dp)).padding(6.dp)) {
                        localTracks.forEachIndexed { index, track -> TrackRow(track, index, onQueue = { DesktopPlayer.enqueueNext(track) }) { onTrackSelected(track) } }
                    }
                }
            }
            "Albums" -> {
                    val albums = collections.filter { it.kind == "album" }
                    val orderedAlbums = when (sort) {
                        "Name" -> if (descending) albums.sortedByDescending { it.title.lowercase() } else albums.sortedBy { it.title.lowercase() }
                        else -> if (descending) albums else albums.reversed()
                    }
                    SavedCollectionList(orderedAlbums, "Albums", "No saved albums", "Download an album from search or an artist page to keep it here.") { openedCollection = it }
            }
            "Artists" -> {
                val artists = collections.filter { it.kind == "artist" }
                SectionTitle("Saved artists", "${artists.size} artists")
                SavedCollectionList(artists, "Artists", "No saved artists", "Save an artist from its page to keep it in your library.") { openedArtist = it }
            }
            "Online playlists" -> {
                val onlinePlaylists = collections.filter { it.kind == "playlist" }
                SectionTitle("Saved online playlists", "${onlinePlaylists.size} playlists")
                SavedCollectionList(onlinePlaylists, "Online playlists", "No saved online playlists", "Save a YouTube Music playlist from its page to keep it in your library.") { openedCollection = it }
            }
            "YouTube Music" -> {
                val songs = youtubeLibraryTracks.filter { it.kind == "song" }
                val savedItems = youtubeLibraryTracks.filter { it.kind != "song" }
                val itemCount = youtubeLibrarySync?.let { it.songCount + it.albumCount + it.artistCount + it.playlistCount } ?: 0
                SectionTitle("YouTube Music library", youtubeLibrarySync?.let { "$itemCount synced items" } ?: "Account library")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = !youtubeLibrarySyncing && youtubeAccountConnected,
                        onClick = {
                            scope.launch {
                                youtubeLibrarySyncing = true
                                youtubeLibraryError = null
                                youtubeLibraryMessage = null
                                try {
                                    val result = DesktopBackend.syncYoutubeMusicLibrary()
                                    youtubeLibraryTracks = DesktopBackend.youtubeMusicLibraryItems()
                                    youtubeLibrarySync = DesktopBackend.youtubeMusicLibrarySync()
                                    youtubeAccountConnected = DesktopBackend.youtubeConnected()
                                    youtubeLibraryMessage = "Synced ${result.songs} songs, ${result.albums} albums, ${result.artists} artists, and ${result.playlists} playlists."
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Throwable) {
                                    youtubeLibraryError = failure.message ?: "Could not sync the YouTube Music library."
                                } finally {
                                    youtubeLibrarySyncing = false
                                }
                            }
                        },
                    ) { Text(if (youtubeLibrarySyncing) "Syncing…" else "Sync account library") }
                    youtubeLibrarySync?.let { sync ->
                        Text("Last synced ${java.time.Instant.ofEpochMilli(sync.syncedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString().replace('T', ' ')}", color = XanColors.Muted)
                    }
                }
                if (!youtubeAccountConnected) {
                    Text("Connect YouTube Music in Settings → Account to sync liked songs, albums, artists, and playlists.", color = XanColors.Muted)
                }
                youtubeLibraryMessage?.let { Text(it, color = XanColors.Muted) }
                youtubeLibraryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (songs.isEmpty()) {
                    EmptyPanel(
                        Icons.Rounded.MusicNote,
                        if (youtubeLibrarySync != null) "No liked songs" else "No synced songs",
                        if (youtubeLibrarySync != null) "This YouTube Music channel has no liked songs." else "Sync your YouTube Music account to load its liked songs here.",
                    )
                } else {
                    SectionTitle("Liked songs", "${songs.size} songs")
                    Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(22.dp)).padding(6.dp)) {
                        sortTracks(songs).forEachIndexed { index, track ->
                            TrackRow(track, index, onQueue = { DesktopPlayer.enqueueNext(track) }) { onTrackSelected(track) }
                        }
                    }
                }
                if (savedItems.isNotEmpty()) {
                    SavedCollectionList(savedItems, "Liked albums, followed artists, and playlists", "", "") { item ->
                        if (item.kind == "artist") openedArtist = item else openedCollection = item
                    }
                }
            }
            "Uploaded" -> {
                SectionTitle("Uploaded songs", uploadedTracks?.size?.let { "$it tracks" })
                when {
                    uploadedLoading -> EmptyPanel(Icons.Rounded.CloudUpload, "Loading uploaded songs…", "Syncing your YouTube Music uploads.")
                    uploadedError != null -> Column {
                        EmptyPanel(Icons.Rounded.ErrorOutline, "Could not load uploads", uploadedError!!)
                        TextButton(onClick = { uploadedTracks = null; uploadedReload++ }) { Text("Try again") }
                    }
                    uploadedTracks.isNullOrEmpty() -> EmptyPanel(Icons.Rounded.CloudUpload, "No uploaded songs", "Your YouTube Music account has no uploaded songs.")
                    else -> Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(22.dp)).padding(6.dp)) {
                            sortTracks(uploadedTracks.orEmpty()).forEachIndexed { index, track ->
                            TrackRow(track, index, onQueue = { DesktopPlayer.enqueueNext(track) }) { onTrackSelected(track) }
                        }
                    }
                }
            }
            "Playlists" -> Unit
            else -> LibraryLandingContent(
                playlists = sortedPlaylists,
                favoriteCount = favoriteTracks.size,
                downloadCount = DesktopBackend.downloadedFiles().size,
                topCount = topTracks.size,
                cachedCount = cachedTracks.size,
                localCount = localTracks.size,
                gridView = gridView,
                onLiked = { filter = "Liked" },
                onDownloads = { showDownloads = true },
                onTop = { filter = "My top 50" },
                onCached = { filter = "Cached" },
                onUploaded = { filter = "Uploaded" },
                onLocal = { filter = "Local Music" },
                onPlaylist = { openedPlaylist = it },
                onCreatePlaylist = { newPlaylistName = ""; newPlaylistDescription = ""; showCreatePlaylist = true },
            )
        }
        if (filter == "Playlists") {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Your playlists", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
                OutlinedButton(onClick = {
                    val chooser = JFileChooser().apply {
                        dialogTitle = "Import a playlist"
                        isMultiSelectionEnabled = false
                        fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Playlist files (.m3u, .m3u8, .csv)", "m3u", "m3u8", "csv")
                    }
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        runCatching { DesktopBackend.importPlaylistFile(chooser.selectedFile.toPath()) }
                            .onSuccess { importReport = it; playlists = DesktopBackend.playlists() }
                            .onFailure { libraryStatus = it.message ?: "Playlist import failed." }
                    }
                }) { Text("Import playlist") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { newPlaylistName = ""; newPlaylistDescription = ""; showCreatePlaylist = true }) { Text("New playlist") }
            }
            importReport?.let { report ->
                Column(Modifier.fillMaxWidth().padding(top = 10.dp).background(XanColors.Card, RoundedCornerShape(16.dp)).padding(14.dp)) {
                    Text("${report.playlistName} · ${report.summary}", color = XanColors.Text)
                    if (report.unresolved.isNotEmpty()) {
                        Text("Unresolved entries", Modifier.padding(top = 8.dp), color = XanColors.Muted)
                        report.unresolved.take(12).forEach { Text(it, color = XanColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        if (report.unresolved.size > 12) Text("And ${report.unresolved.size - 12} more…", color = XanColors.Muted)
                    }
                }
            }
            if (playlists.isEmpty()) EmptyPanel(Icons.Rounded.QueueMusic, "No playlists yet", "Create a playlist to organize songs from your local library.")
            else LibraryUserPlaylists(sortedPlaylists, gridView) { openedPlaylist = it }
        }
    }
    if (showCreatePlaylist) AlertDialog(
        onDismissRequest = { showCreatePlaylist = false },
        title = { Text("New playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(newPlaylistName, { newPlaylistName = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(newPlaylistDescription, { newPlaylistDescription = it }, label = { Text("Description (optional)") }, minLines = 2, maxLines = 4)
            }
        },
        confirmButton = { TextButton(onClick = {
            runCatching { DesktopBackend.createPlaylist(newPlaylistName, newPlaylistDescription) }
                .onSuccess { playlists = DesktopBackend.playlists(); showCreatePlaylist = false }
                .onFailure { libraryStatus = it.message ?: "Could not create playlist." }
        }, enabled = newPlaylistName.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = { showCreatePlaylist = false }) { Text("Cancel") } },
    )
}

@Composable
private fun LibraryToolbar(
    sort: String,
    descending: Boolean,
    gridView: Boolean,
    onSort: (String) -> Unit,
    onDirection: () -> Unit,
    onView: (Boolean) -> Unit,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 13.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Button(onClick = { sortMenuOpen = true }, colors = ButtonDefaults.buttonColors(containerColor = XanColors.Accent, contentColor = XanColors.Text)) {
                Text(sort)
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                listOf("Date added", "Name", "Last updated", "Playlist order").forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = { onSort(option); sortMenuOpen = false })
                }
            }
        }
        IconButton(onClick = onDirection) {
            Icon(if (descending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward, contentDescription = if (descending) "Sort descending" else "Sort ascending", tint = XanColors.Text)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { onView(false) }) {
            Icon(Icons.Rounded.ViewList, contentDescription = "List view", tint = if (!gridView) XanColors.Text else XanColors.Muted)
        }
        IconButton(onClick = { onView(true) }) {
            Icon(Icons.Rounded.GridView, contentDescription = "Grid view", tint = if (gridView) XanColors.Text else XanColors.Muted)
        }
    }
}

@Composable
private fun LibraryLandingContent(
    playlists: List<CatalogPlaylist>,
    favoriteCount: Int,
    downloadCount: Int,
    topCount: Int,
    cachedCount: Int,
    localCount: Int,
    gridView: Boolean,
    onLiked: () -> Unit,
    onDownloads: () -> Unit,
    onTop: () -> Unit,
    onCached: () -> Unit,
    onUploaded: () -> Unit,
    onLocal: () -> Unit,
    onPlaylist: (CatalogPlaylist) -> Unit,
    onCreatePlaylist: () -> Unit,
) {
    if (gridView) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = (maxWidth / 218.dp).toInt().coerceAtLeast(2)
            val gap = 14.dp
            val cardWidth = (maxWidth - gap * (columns - 1)) / columns
            val cards = listOf(
                LibraryCardData("Liked", "$favoriteCount songs", Icons.Rounded.FavoriteBorder, onLiked),
                LibraryCardData("Downloaded", "$downloadCount songs", Icons.Rounded.Download, onDownloads),
                LibraryCardData("My top 50", "$topCount songs", Icons.Rounded.Leaderboard, onTop),
                LibraryCardData("Cached", "$cachedCount songs", Icons.Rounded.Cached, onCached),
                LibraryCardData("Uploaded", "YouTube Music", Icons.Rounded.CloudUpload, onUploaded),
                LibraryCardData("Local", "$localCount songs", Icons.Rounded.FolderOpen, onLocal),
            )
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                cards.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { card -> LibraryCollectionCard(card, Modifier.width(cardWidth)) }
                        repeat(columns - row.size) { Spacer(Modifier.width(cardWidth)) }
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(22.dp)).padding(6.dp)) {
            listOf(
                LibraryCardData("Liked", "$favoriteCount songs", Icons.Rounded.FavoriteBorder, onLiked),
                LibraryCardData("Downloaded", "$downloadCount songs", Icons.Rounded.Download, onDownloads),
                LibraryCardData("My top 50", "$topCount songs", Icons.Rounded.Leaderboard, onTop),
                LibraryCardData("Cached", "$cachedCount songs", Icons.Rounded.Cached, onCached),
                LibraryCardData("Uploaded", "YouTube Music", Icons.Rounded.CloudUpload, onUploaded),
                LibraryCardData("Local", "$localCount songs", Icons.Rounded.FolderOpen, onLocal),
            ).forEach { card ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = card.onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(card.icon, contentDescription = null, modifier = Modifier.size(28.dp), tint = XanColors.Muted)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(card.title, color = XanColors.Text, fontWeight = FontWeight.SemiBold)
                        Text(card.subtitle, color = XanColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = XanColors.Muted)
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
        FloatingActionButton(onClick = onCreatePlaylist, containerColor = XanColors.Accent, contentColor = Color.White) {
            Icon(Icons.Rounded.Add, contentDescription = "Create playlist")
        }
    }
    SectionTitle("Your playlists", action = "New playlist", onAction = onCreatePlaylist)
    if (playlists.isEmpty()) EmptyPanel(Icons.Rounded.QueueMusic, "No playlists yet", "Create a playlist to organize your music.")
    else LibraryUserPlaylists(playlists, gridView, onPlaylist)
}

@Composable
private fun LibraryUserPlaylists(playlists: List<CatalogPlaylist>, gridView: Boolean, onPlaylist: (CatalogPlaylist) -> Unit) {
    if (gridView) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = (maxWidth / 218.dp).toInt().coerceAtLeast(2)
            val gap = 14.dp
            val cardWidth = (maxWidth - gap * (columns - 1)) / columns
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                playlists.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { playlist ->
                            Column(Modifier.width(cardWidth).clip(RoundedCornerShape(18.dp)).clickable { onPlaylist(playlist) }.padding(10.dp)) {
                                EmptyArtwork(Icons.Rounded.QueueMusic, Modifier.fillMaxWidth().aspectRatio(1f), XanColors.CardLight)
                                Text(playlist.name, Modifier.padding(top = 8.dp), color = XanColors.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${playlist.trackCount} songs", color = XanColors.Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.width(cardWidth)) }
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(22.dp)).padding(6.dp)) {
            playlists.forEach { playlist ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onPlaylist(playlist) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    EmptyArtwork(Icons.Rounded.QueueMusic, Modifier.size(48.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(playlist.name, color = XanColors.Text)
                        Text("${playlist.trackCount} songs", color = XanColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private data class LibraryCardData(val title: String, val subtitle: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
private fun LibraryCollectionCard(card: LibraryCardData, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = card.onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(XanColors.Card), contentAlignment = Alignment.Center) {
            Icon(card.icon, contentDescription = null, modifier = Modifier.size(54.dp), tint = XanColors.Muted)
        }
        Text(card.title, Modifier.padding(top = 7.dp), color = XanColors.Text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(card.subtitle, color = XanColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SavedCollectionList(
    items: List<MusicTrack>,
    title: String,
    emptyTitle: String,
    emptyBody: String,
    onOpen: (MusicTrack) -> Unit,
) {
    SectionTitle(title)
    if (items.isEmpty()) {
        if (emptyTitle.isNotEmpty()) EmptyPanel(Icons.Rounded.LibraryMusic, emptyTitle, emptyBody)
        return
    }
    Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(22.dp)).padding(6.dp)) {
        items.forEachIndexed { index, item ->
            TrackRow(item, index) { onOpen(item) }
        }
    }
}

@Composable
private fun DownloadedAudioList(onTrackSelected: (MusicTrack) -> Unit) {
    val downloads = remember { DesktopBackend.downloadedTracks() }
    var openError by remember { mutableStateOf<String?>(null) }
        Text("${downloads.size} downloaded audio files · ${DesktopBackend.downloadDirectoryLabel()}", color = XanColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (downloads.isEmpty()) {
        EmptyPanel(Icons.Rounded.Download, "No downloads yet", "Use the download action beside a song, artist, album, or playlist result.")
        return
    }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).background(XanColors.Card, RoundedCornerShape(22.dp)).padding(6.dp)) {
        downloads.forEach { track ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .clickable { runCatching { onTrackSelected(track) }
                        .onFailure { openError = it.message ?: "Could not open this audio file." } }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Artwork(track, 48.dp, corner = 10.dp)
                Column(Modifier.weight(1f)) {
                    Text(track.title, color = XanColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(track.artist, track.album).filter(String::isNotBlank).joinToString(" · ").ifBlank { "Downloaded audio" }, color = XanColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("Play", color = XanColors.Accent)
            }
        }
    }
    openError?.let { Text(it, Modifier.padding(12.dp), color = XanColors.Accent) }
}

@Composable
internal fun NowPlayingBar(snapshot: PlaybackSnapshot, modifier: Modifier = Modifier, controlsEnabled: Boolean = true, onExpand: () -> Unit) {
    val track = snapshot.track ?: return
    val scope = rememberCoroutineScope()
    val cast by DesktopCast.state.collectAsState()
    var castMenuOpen by remember { mutableStateOf(false) }
    val isCasting = cast.connectedName != null
    val shownPosition = if (isCasting) cast.positionMillis else snapshot.positionMillis
    val shownDuration = if (isCasting) cast.durationMillis else snapshot.durationMillis
    LaunchedEffect(cast.connectedName) {
        while (cast.connectedName != null) {
            runCatching { DesktopCast.refreshStatus() }
            delay(1_000)
        }
    }
    var queueOpen by remember { mutableStateOf(false) }
    var sleepTimerDialogOpen by remember { mutableStateOf(false) }
    var sleepTimerMinutes by remember { mutableIntStateOf(30) }
    val sleepTimerActive = snapshot.sleepTimerDeadlineEpochMillis != null || snapshot.sleepTimerPauseAtTrackEnd
    var playlistMenuOpen by remember { mutableStateOf(false) }
    var createPlaylistDialogOpen by remember { mutableStateOf(false) }
    var availablePlaylists by remember { mutableStateOf(emptyList<CatalogPlaylist>()) }
    val trackKey = track.videoId ?: track.localPath ?: track.title
    var cachedOffline by remember(trackKey) { mutableStateOf(false) }
    var cacheBusy by remember(trackKey) { mutableStateOf(false) }
    var favorited by remember(trackKey) {
        mutableStateOf(DesktopBackend.favoriteTracks().any { (it.videoId ?: it.localPath) == (track.videoId ?: track.localPath) })
    }
    LaunchedEffect(trackKey) {
        cachedOffline = withContext(Dispatchers.IO) { DesktopBackend.isAudioCached(track) }
    }
    var seekDraft by remember(trackKey) { mutableFloatStateOf(snapshot.positionMillis.toFloat()) }
    var seeking by remember(trackKey) { mutableStateOf(false) }
    LaunchedEffect(snapshot.positionMillis, seeking) {
        if (!seeking) seekDraft = snapshot.positionMillis.toFloat()
    }
    Column(
        modifier.widthIn(max = 760.dp).fillMaxWidth()
            .clip(RoundedCornerShape(24.dp)).background(XanColors.Panel)
            .border(1.dp, XanColors.Border.copy(alpha = .75f), RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Artwork(track, 52.dp, Modifier.clickable(onClick = onExpand), corner = 12.dp)
            Column(Modifier.weight(1f).clickable(onClick = onExpand)) {
                Text(track.title, color = XanColors.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (!controlsEnabled) "Room host controls playback" else if (isCasting) "${if (cast.isPlaying) "Casting" else "Cast paused"} · ${cast.connectedName}" else snapshot.error ?: snapshot.notice ?: when (snapshot.status) {
                        PlaybackStatus.RESOLVING -> "Finding audio stream…"
                        PlaybackStatus.BUFFERING -> "Buffering…"
                        PlaybackStatus.PAUSED -> "Paused · ${track.artist}"
                        PlaybackStatus.ERROR -> "Playback error · ${track.artist}"
                        PlaybackStatus.IDLE -> "Stopped · ${track.artist}"
                        PlaybackStatus.PLAYING -> track.artist
                    },
                    color = if (snapshot.status == PlaybackStatus.ERROR) XanColors.Accent else XanColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sleepTimerActive) {
                    Text(
                        if (snapshot.sleepTimerPauseAtTrackEnd) "Sleep timer · At end of track"
                        else "Sleep timer · ${formatPlayerTime(snapshot.sleepTimerRemainingMillis ?: 0L)}",
                        color = XanColors.Accent,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            IconButton(onClick = onExpand) {
                Icon(Icons.Rounded.Subtitles, contentDescription = "Open expanded player and lyrics", tint = XanColors.Text)
            }
            Box {
                IconButton(onClick = { DesktopCast.refreshDiscovery(); castMenuOpen = true }) {
                    Icon(
                        if (cast.connectedName != null) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                        contentDescription = if (cast.connectedName != null) "Cast to ${cast.connectedName}" else "Cast to a device",
                        tint = if (cast.connectedName != null) XanColors.Accent else XanColors.Text,
                    )
                }
                DropdownMenu(expanded = castMenuOpen, onDismissRequest = { castMenuOpen = false }) {
                    Text("Google Cast", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = XanColors.Muted, style = MaterialTheme.typography.labelMedium)
                    cast.status?.let { Text(it, Modifier.widthIn(max = 300.dp).padding(horizontal = 16.dp, vertical = 4.dp), color = XanColors.Muted, style = MaterialTheme.typography.bodySmall) }
                    if (cast.busy) {
                        DropdownMenuItem(text = { Text("Connecting…") }, enabled = false, onClick = {})
                    } else if (cast.connectedName != null) {
                        DropdownMenuItem(text = { Text("Pause / resume on ${cast.connectedName}") }, onClick = {
                            castMenuOpen = false
                            scope.launch { runCatching { DesktopCast.togglePlayback() }.onFailure { DesktopPlayer.setNotice(it.message ?: "Cast playback control failed.") } }
                        })
                        DropdownMenuItem(text = { Text("Disconnect") }, onClick = {
                            castMenuOpen = false
                            scope.launch { runCatching { DesktopCast.disconnect() }.onFailure { DesktopPlayer.setNotice(it.message ?: "Could not disconnect from Cast.") } }
                        })
                    } else if (cast.devices.isEmpty()) {
                        DropdownMenuItem(text = { Text(if (cast.status?.startsWith("Searching") == true) "Searching for devices…" else "No Cast devices found") }, enabled = false, onClick = {})
                        DropdownMenuItem(text = { Text("Search again") }, onClick = { DesktopCast.refreshDiscovery() })
                    } else cast.devices.forEach { device ->
                        DropdownMenuItem(text = { Text(device.name) }, enabled = !cast.busy, onClick = {
                            castMenuOpen = false
                            scope.launch {
                                runCatching {
                                    val uri = withContext(Dispatchers.IO) { DesktopBackend.resolveTrackUri(track) }
                                    DesktopCast.cast(device, track, uri)
                                    DesktopPlayer.pauseLocalForCast()
                                    DesktopPlayer.setExternalTrack(track)
                                }.onFailure { DesktopPlayer.setNotice(it.message ?: "Could not cast this track.") }
                            }
                        })
                    }
                }
            }
            Box {
                IconButton(onClick = { availablePlaylists = DesktopBackend.playlists(); playlistMenuOpen = true }) {
                    Icon(Icons.Rounded.PlaylistAdd, contentDescription = "Add ${track.title} to a playlist", tint = XanColors.Text)
                }
                DropdownMenu(expanded = playlistMenuOpen, onDismissRequest = { playlistMenuOpen = false }) {
                    Text("Add to playlist", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = XanColors.Muted, style = MaterialTheme.typography.labelMedium)
                    if (availablePlaylists.isEmpty()) {
                        Text("No playlists yet", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = XanColors.Muted, style = MaterialTheme.typography.bodySmall)
                    } else availablePlaylists.forEach { playlist ->
                        DropdownMenuItem(text = { Text("${playlist.name} � ${playlist.trackCount}") }, onClick = {
                            playlistMenuOpen = false
                            runCatching { DesktopBackend.addTrackToPlaylist(playlist.id, track) }
                                .onSuccess { added -> DesktopPlayer.setNotice(if (added) "Added ${track.title} to ${playlist.name}." else "${track.title} is already in ${playlist.name}.") }
                                .onFailure { DesktopPlayer.setNotice(it.message ?: "Could not add this song to the playlist.") }
                        })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Create playlist and add song…") }, onClick = {
                        playlistMenuOpen = false
                        createPlaylistDialogOpen = true
                    })
                }
            }
            IconButton(
                enabled = track.videoId != null || track.localPath != null,
                onClick = {
                    runCatching { DesktopBackend.toggleFavorite(track) }
                        .onSuccess { added -> favorited = added; DesktopPlayer.setNotice(if (added) "Added ${track.title} to Favorites." else "Removed ${track.title} from Favorites.") }
                        .onFailure { DesktopPlayer.setNotice(it.message ?: "Could not update Favorites.") }
                },
            ) {
                Icon(if (favorited) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, contentDescription = if (favorited) "Remove from Favorites" else "Add to Favorites", tint = if (favorited) XanColors.Accent else XanColors.Text)
            }
            if (track.videoId != null) IconButton(enabled = !cacheBusy, onClick = {
                if (cachedOffline) {
                    runCatching { DesktopBackend.removeCachedAudioTrack(track) }
                        .onSuccess { cachedOffline = false; DesktopPlayer.setNotice("Removed ${track.title} from Cached songs.") }
                        .onFailure { DesktopPlayer.setNotice(it.message ?: "Could not remove this cached track.") }
                } else scope.launch {
                    cacheBusy = true
                    runCatching { DesktopBackend.cacheAudioTrack(track) }
                        .onSuccess { cachedOffline = true; DesktopPlayer.setNotice("Saved ${track.title} for offline playback.") }
                        .onFailure { DesktopPlayer.setNotice(it.message ?: "Could not cache this song.") }
                    cacheBusy = false
                }
            }) {
                Icon(Icons.Rounded.Cached, contentDescription = if (cacheBusy) "Caching track" else if (cachedOffline) "Remove cached audio" else "Cache for offline playback", tint = if (cachedOffline) XanColors.Accent else XanColors.Text)
            }
            IconButton(onClick = { scope.launch {
                DesktopTransportControls.perform(DesktopTransportAction.PREVIOUS)
            } }, enabled = controlsEnabled && (if (isCasting) snapshot.queueIndex > 0 || shownPosition > 0 else snapshot.queueIndex > 0 || snapshot.positionMillis > 0)) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous track", tint = XanColors.Text)
            }
            IconButton(onClick = { scope.launch {
                DesktopTransportControls.perform(DesktopTransportAction.PLAY_PAUSE)
            } }, enabled = controlsEnabled && (isCasting || snapshot.status !in setOf(PlaybackStatus.RESOLVING, PlaybackStatus.ERROR, PlaybackStatus.IDLE))) {
                Icon(
                    if (if (isCasting) cast.isPlaying else snapshot.status == PlaybackStatus.PLAYING || snapshot.status == PlaybackStatus.BUFFERING) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (if (isCasting) cast.isPlaying else snapshot.status == PlaybackStatus.PLAYING) "Pause" else "Play",
                    tint = XanColors.Accent,
                    modifier = Modifier.size(28.dp),
                )
            }
            IconButton(onClick = { scope.launch {
                DesktopTransportControls.perform(DesktopTransportAction.NEXT)
            } }, enabled = controlsEnabled && (snapshot.queueIndex + 1 < snapshot.queue.size || snapshot.repeatMode == RepeatMode.ALL)) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next track", tint = XanColors.Text)
            }
            IconButton(onClick = { DesktopPlayer.cycleRepeatMode() }, enabled = controlsEnabled) {
                Icon(
                    if (snapshot.repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    contentDescription = when (snapshot.repeatMode) {
                        RepeatMode.OFF -> "Repeat off; select to repeat all"
                        RepeatMode.ALL -> "Repeat all; select to repeat one"
                        RepeatMode.ONE -> "Repeat one; select to turn repeat off"
                    },
                    tint = if (snapshot.repeatMode == RepeatMode.OFF) XanColors.Muted else XanColors.Accent,
                )
            }
            IconButton(onClick = { sleepTimerDialogOpen = true }, enabled = controlsEnabled) {
                Icon(
                    Icons.Rounded.Alarm,
                    contentDescription = if (sleepTimerActive) "Change sleep timer" else "Set sleep timer",
                    tint = if (sleepTimerActive) XanColors.Accent else XanColors.Text,
                )
            }
            Box {
                IconButton(onClick = { queueOpen = true }, enabled = controlsEnabled) {
                    Icon(Icons.Rounded.QueueMusic, contentDescription = "Playback queue", tint = XanColors.Text)
                }
            }
            Icon(Icons.Rounded.VolumeUp, contentDescription = "Volume", tint = XanColors.Muted, modifier = Modifier.size(19.dp))
            Slider(value = if (isCasting) cast.volume else snapshot.volume, onValueChange = { value ->
                if (isCasting) scope.launch { runCatching { DesktopCast.setVolume(value) }.onFailure { DesktopPlayer.setNotice(it.message ?: "Could not change Cast volume.") } }
                else DesktopPlayer.setVolume(value)
            }, enabled = controlsEnabled, modifier = Modifier.width(92.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(formatPlayerTime(shownPosition), color = XanColors.Muted, style = MaterialTheme.typography.labelSmall)
            Slider(
                value = if (isCasting) shownPosition.toFloat() else seekDraft,
                onValueChange = { seeking = true; seekDraft = it },
                onValueChangeFinished = {
                    if (isCasting) scope.launch { runCatching { DesktopCast.seek(seekDraft.toLong()) }.onFailure { DesktopPlayer.setNotice(it.message ?: "Cast seek failed.") } }
                    else DesktopPlayer.seekTo(seekDraft.toLong())
                    seeking = false
                },
                valueRange = 0f..shownDuration.coerceAtLeast(1).toFloat(),
                enabled = controlsEnabled && shownDuration > 0 && (isCasting || snapshot.status != PlaybackStatus.RESOLVING),
                modifier = Modifier.weight(1f).height(25.dp),
            )
            Text(formatPlayerTime(shownDuration), color = XanColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
    if (createPlaylistDialogOpen) CreatePlaylistAndAddTrackDialog(track) { createPlaylistDialogOpen = false }
    if (queueOpen) {
        AlertDialog(
            onDismissRequest = { queueOpen = false },
            title = { Text("Up next · ${snapshot.queue.size} songs") },
            text = {
                Column(Modifier.widthIn(min = 380.dp, max = 620.dp).heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    snapshot.queue.forEachIndexed { index, queued ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(enabled = controlsEnabled, onClick = { queueOpen = false; scope.launch { DesktopPlayer.playQueuePosition(index) } }, modifier = Modifier.weight(1f)) {
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                    Text("${if (index == snapshot.queueIndex) "▶ " else ""}${queued.title}", color = XanColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(queued.artist, color = XanColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            IconButton(enabled = controlsEnabled && index > 0, onClick = { DesktopPlayer.moveQueueItem(index, index - 1) }) { Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move ${queued.title} up") }
                            IconButton(enabled = controlsEnabled && index < snapshot.queue.lastIndex, onClick = { DesktopPlayer.moveQueueItem(index, index + 1) }) { Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move ${queued.title} down") }
                            IconButton(enabled = controlsEnabled, onClick = { scope.launch { DesktopPlayer.removeQueueItem(index) } }) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove ${queued.title} from queue", tint = XanColors.Muted) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { queueOpen = false }) { Text("Done") } },
        )
    }
    if (sleepTimerDialogOpen) {
        AlertDialog(
            onDismissRequest = { sleepTimerDialogOpen = false },
            title = { Text("Sleep timer") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Pause in $sleepTimerMinutes minutes", color = XanColors.Text)
                    Slider(
                        value = sleepTimerMinutes.toFloat(),
                        onValueChange = { sleepTimerMinutes = ((it.toInt() / 5) * 5).coerceIn(5, 120) },
                        valueRange = 5f..120f,
                        steps = 22,
                    )
                    OutlinedButton(
                        onClick = {
                            DesktopPlayer.startSleepTimerAtTrackEnd()
                            sleepTimerDialogOpen = false
                        },
                        enabled = !isCasting,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (isCasting) "End-of-track timer unavailable while casting" else "At the end of this track") }
                    if (sleepTimerActive) {
                        TextButton(onClick = {
                            DesktopPlayer.cancelSleepTimer()
                            sleepTimerDialogOpen = false
                        }) { Text("Turn off timer") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    DesktopPlayer.startSleepTimer(sleepTimerMinutes)
                    sleepTimerDialogOpen = false
                }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { sleepTimerDialogOpen = false }) { Text("Cancel") } },
        )
    }
}

internal fun formatPlayerTime(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private data class SettingLink(val group: String, val title: String, val icon: ImageVector, val tint: Color)

private val SettingLinks = listOf(
    SettingLink("GENERAL", "System update", Icons.Rounded.SystemUpdate, Color(0xFF007AFF)),
    SettingLink("GENERAL", "Appearance", Icons.Rounded.Palette, Color(0xFFAF52DE)),
    SettingLink("GENERAL", "Keyboard shortcuts", Icons.Rounded.Keyboard, Color(0xFF5856D6)),
    SettingLink("ACCOUNT", "Account", Icons.Rounded.AccountCircle, Color(0xFF34C759)),
    SettingLink("ACCOUNT", "Scrobbling", Icons.Rounded.History, Color(0xFFFF2D55)),
    SettingLink("SOCIAL", "Listen Together", Icons.Rounded.People, Color(0xFF00C7BE)),
    SettingLink("ACTIVITY", "History", Icons.Rounded.History, Color(0xFF007AFF)),
    SettingLink("ACTIVITY", "Stats", Icons.Rounded.BarChart, Color(0xFFFF9F0A)),
    SettingLink("CONTENT", "Content", Icons.Rounded.Tune, Color(0xFF007AFF)),
    SettingLink("PLAYER", "Lyrics", Icons.Rounded.Subtitles, Color(0xFFFA2D48)),
    SettingLink("PLAYER", "Media Controls", Icons.Rounded.Headphones, Color(0xFF5E5CE6)),
    SettingLink("PLAYER", "Video Search", Icons.Rounded.VideoLibrary, Color(0xFF007AFF)),
    SettingLink("PLAYER", "Equalizer", Icons.Rounded.GraphicEq, Color(0xFF30D158)),
    SettingLink("PLAYER", "SponsorBlock", Icons.Rounded.FastForward, Color(0xFFFF9F0A)),
    SettingLink("INTEGRATIONS", "Integrations", Icons.Rounded.Extension, Color(0xFF5856D6)),
    SettingLink("DATA & PRIVACY", "Storage", Icons.Rounded.Storage, Color(0xFF8E8E93)),
    SettingLink("DATA & PRIVACY", "Privacy", Icons.Rounded.Lock, Color(0xFF8E8E93)),
    SettingLink("DATA & PRIVACY", "Diagnostics", Icons.Rounded.BugReport, Color(0xFF007AFF)),
    SettingLink("DATA & PRIVACY", "Backup & Restore", Icons.Rounded.Backup, Color(0xFF8E8E93)),
    SettingLink("ABOUT", "About XAN", Icons.Rounded.Info, Color(0xFF8E8E93)),
)

@Composable
internal fun SettingsScreen(navigationMode: DesktopNavigationMode, onNavigationModeChange: (DesktopNavigationMode) -> Unit, appLogo: AppLogoVariant, onAppLogoChange: (AppLogoVariant) -> Unit, magnetSemicircle: Boolean, onMagnetSemicircleChange: (Boolean) -> Unit, reducedMotion: Boolean, onReducedMotionChange: (Boolean) -> Unit, reducedTransparency: Boolean, onReducedTransparencyChange: (Boolean) -> Unit, animatedPlayerCanvas: Boolean, onAnimatedPlayerCanvasChange: (Boolean) -> Unit, onBackupRestored: () -> Unit, onTrackSelected: (MusicTrack) -> Unit) {
    var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<SettingLink?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 32.dp, end = 32.dp, bottom = 32.dp)) {
        if (detail != null) {
            TextButton(onClick = { detail = null }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Settings")
            }
            ScreenTitle(detail!!.title)
            SettingDetail(detail!!, navigationMode, onNavigationModeChange, appLogo, onAppLogoChange, magnetSemicircle, onMagnetSemicircleChange, reducedMotion, onReducedMotionChange, reducedTransparency, onReducedTransparencyChange, animatedPlayerCanvas, onAnimatedPlayerCanvasChange, onBackupRestored, onTrackSelected)
        } else {
            ScreenTitle("Settings")
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                placeholder = { Text("Search settings") },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = XanColors.Border,
                    unfocusedBorderColor = XanColors.Border,
                    focusedContainerColor = XanColors.Card,
                    unfocusedContainerColor = XanColors.Card,
                ),
            )
            val filtered = SettingLinks.filter { it.title.contains(query, ignoreCase = true) || it.group.contains(query, ignoreCase = true) }
            filtered.groupBy { it.group }.forEach { (group, links) ->
                Text(group, Modifier.padding(top = 25.dp, bottom = 9.dp, start = 8.dp), style = MaterialTheme.typography.labelSmall, color = XanColors.Muted, letterSpacing = 1.sp)
                Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(20.dp)).padding(4.dp)) {
                    links.forEach { link ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).clickable { detail = link }
                                .height(54.dp).padding(horizontal = 15.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(link.icon, contentDescription = null, tint = link.tint, modifier = Modifier.size(23.dp))
                            Text(link.title, Modifier.weight(1f).padding(start = 14.dp), color = XanColors.Text)
                            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = XanColors.Muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingDetail(link: SettingLink, navigationMode: DesktopNavigationMode, onNavigationModeChange: (DesktopNavigationMode) -> Unit, appLogo: AppLogoVariant, onAppLogoChange: (AppLogoVariant) -> Unit, magnetSemicircle: Boolean, onMagnetSemicircleChange: (Boolean) -> Unit, reducedMotion: Boolean, onReducedMotionChange: (Boolean) -> Unit, reducedTransparency: Boolean, onReducedTransparencyChange: (Boolean) -> Unit, animatedPlayerCanvas: Boolean, onAnimatedPlayerCanvasChange: (Boolean) -> Unit, onBackupRestored: () -> Unit, onTrackSelected: (MusicTrack) -> Unit) {
    when (link.title) {
        "System update" -> { SystemUpdateDetail(); return }
        "Appearance" -> { AppearanceDetail(navigationMode, onNavigationModeChange, appLogo, onAppLogoChange, magnetSemicircle, onMagnetSemicircleChange, reducedMotion, onReducedMotionChange, reducedTransparency, onReducedTransparencyChange, animatedPlayerCanvas, onAnimatedPlayerCanvasChange); return }
        "Keyboard shortcuts" -> { KeyboardShortcutsDetail(); return }
        "Account" -> { YouTubeAccountDetail(); return }
        "Scrobbling" -> { ScrobblingDetail(); return }
        "Listen Together" -> { ListenTogetherSettingsDetail(); return }
        "History" -> { ListeningHistoryDetail(onTrackSelected); return }
        "Stats" -> { ListeningStatsDetail(); return }
        "SponsorBlock" -> { SponsorBlockSettingsDetail(); return }
        "Integrations" -> { IntegrationsDetail(); return }
        "Content" -> { OnlineContentDetail(); return }
        "Storage" -> { DownloadStorageDetail(); return }
        "Privacy" -> { PrivacyDetail(); return }
        "Diagnostics" -> { DiagnosticsDetail(); return }
        "Lyrics" -> { LyricsSettingsDetail(); return }
        "Media Controls" -> { MediaControlsSettingsDetail(); return }
        "Video Search" -> { VideoSearchSettingsDetail(); return }
        "Equalizer" -> { EqualizerSettingsDetail(); return }
        "Backup & Restore" -> { BackupRestoreDetail(onBackupRestored); return }
        "About XAN" -> {
            AboutDetail()
            return
        }
    }
}

@Composable
private fun MediaControlsSettingsDetail() {
    var globalMediaKeys by remember { mutableStateOf(DesktopBackend.globalMediaKeysEnabled()) }
    var djTransitionSeconds by remember { mutableIntStateOf(DesktopBackend.djTransitionSeconds()) }
    var status by remember { mutableStateOf(DesktopGlobalMediaKeys.status()) }
    Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(20.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Desktop media controls", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        SettingSwitch(
            "Global media keys",
            "Listen for your keyboard’s play/pause, previous, next, and stop keys while XAN is in the background. On macOS, grant Accessibility access if asked.",
            globalMediaKeys,
        ) {
            globalMediaKeys = it
            DesktopBackend.setGlobalMediaKeysEnabled(it)
            status = DesktopGlobalMediaKeys.status()
        }
        Text(status, color = XanColors.Muted)
        HorizontalDivider(color = XanColors.Border)
        Text("DJ transitions", style = MaterialTheme.typography.titleMedium, color = XanColors.Text)
        Text(if (djTransitionSeconds == 0) "Off" else "${djTransitionSeconds}s fade-out and fade-in at track changes.", color = XanColors.Muted)
        Slider(
            value = djTransitionSeconds.toFloat(),
            onValueChange = { value ->
                djTransitionSeconds = value.toInt()
                DesktopBackend.setDjTransitionSeconds(djTransitionSeconds)
            },
            valueRange = 0f..8f,
            steps = 7,
        )
        HorizontalDivider(color = XanColors.Border)
        Text("Audio output", style = MaterialTheme.typography.titleMedium, color = XanColors.Text)
        Text("XAN follows the operating system’s default output device. Open system sound settings to switch speakers or headphones; direct per-device routing and native lock-screen media sessions still need OS-specific integration.", color = XanColors.Muted)
        OutlinedButton(onClick = { status = DesktopSystemAudioSettings.open() }) { Text("Open system sound settings") }
    }
}

@Composable
private fun KeyboardShortcutsDetail() {
    val shortcuts = listOf(
        "Play or pause" to "Ctrl+Alt+Space · ⌘+Option+Space",
        "Previous track" to "Ctrl+Alt+← · ⌘+Option+←",
        "Next track" to "Ctrl+Alt+→ · ⌘+Option+→",
        "Home" to "Ctrl+Alt+H · ⌘+Option+H",
        "Search" to "Ctrl+Alt+S · ⌘+Option+S",
        "Library" to "Ctrl+Alt+L · ⌘+Option+L",
        "Recognize Music" to "Ctrl+Alt+R · ⌘+Option+R",
        "Settings" to "Ctrl+Alt+O · ⌘+Option+O",
    )
    Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(24.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Keyboard shortcuts", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        Text("Shortcuts work while the XAN window is focused. Use Ctrl+Alt on Windows/Linux or Command+Option on macOS.", color = XanColors.Muted)
        shortcuts.forEach { (action, keys) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(action, Modifier.weight(1f), color = XanColors.Text)
                Text(keys, color = XanColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AboutDetail() {
    val scope = rememberCoroutineScope()
    val update by DesktopUpdater.state.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var licenseOpen by remember { mutableStateOf(false) }
    val license = remember { DesktopBackend::class.java.getResourceAsStream("/legal/LICENSE.txt")?.bufferedReader()?.use { it.readText() }.orEmpty() }
    val installed = remember {
        java.time.Instant.ofEpochMilli(DesktopBackend.firstInstalledAtMillis()).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    }
    Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(28.dp)).padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            XanMark(112.dp)
            Text("xan", Modifier.padding(top = 10.dp), color = XanColors.Text, fontFamily = XanBrandFont, fontSize = 48.sp, fontWeight = FontWeight.SemiBold)
            Text("XAN Desktop $XAN_DESKTOP_VERSION · bxane", Modifier.padding(top = 10.dp).clip(RoundedCornerShape(50)).background(XanColors.CardLight).padding(horizontal = 18.dp, vertical = 8.dp), color = XanColors.Muted)
        }
        Text("Your music. Your library. Your way.", color = XanColors.Text, style = MaterialTheme.typography.titleLarge)
        Text("Developer, author, and producer: bxane · GPL-3.0-only · First launched $installed", color = XanColors.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("Source" to "https://github.com/bxanedot/xan", "Releases" to "https://github.com/bxanedot/xan/releases", "Issues" to "https://github.com/bxanedot/xan/issues").forEach { (label, url) ->
                OutlinedButton(onClick = { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }.onFailure { status = it.message ?: "Could not open $label." } }) { Text(label) }
            }
            Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    val result = DesktopUpdater.checkForUpdates()
                    status = result.error ?: "Project information refreshed."
                    busy = false
                }
            }) { Text(if (busy) "Refreshing…" else "Refresh") }
            OutlinedButton(onClick = { licenseOpen = true }) { Text("View GPL License") }
        }
        update.check?.latestRelease?.let { release ->
            HorizontalDivider(color = XanColors.Border)
            Text("Latest release · ${release.tag}", color = XanColors.Text, style = MaterialTheme.typography.titleLarge)
            Text(release.publishedAt.take(10), color = XanColors.Muted)
            if (release.changelog.isNotBlank()) Text(release.changelog.take(4_000), color = XanColors.Text)
            TextButton(onClick = { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(release.releaseUrl)) }.onFailure { status = it.message ?: "Could not open release notes." } }) { Text("Open full release notes") }
        }
        if (update.recentCommits.isNotEmpty()) {
            HorizontalDivider(color = XanColors.Border)
            Text("Recent changes", color = XanColors.Text, style = MaterialTheme.typography.titleLarge)
            update.recentCommits.take(8).forEach { commit ->
                TextButton(onClick = { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(commit.url)) }.onFailure { status = it.message ?: "Could not open this change." } }) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                        Text(commit.message, color = XanColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${commit.sha.take(7)} · ${commit.committedAt.take(10)}", color = XanColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (status.isNotBlank()) Text(status, color = XanColors.Muted)
    }
    if (licenseOpen) AlertDialog(
        onDismissRequest = { licenseOpen = false },
        title = { Text("GPL-3.0-only License") },
        text = { Column(Modifier.widthIn(max = 720.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState())) { Text(license.ifBlank { "The license text could not be loaded." }, color = XanColors.Text) } },
        confirmButton = { TextButton(onClick = { licenseOpen = false }) { Text("Close") } },
    )
}

@Composable
private fun SystemUpdateDetail() {
    val scope = rememberCoroutineScope()
    val update by DesktopUpdater.state.collectAsState()
    var automatic by remember { mutableStateOf(DesktopUpdater.automaticUpdatesEnabled()) }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val platform = remember { runCatching { DesktopPlatform.current() }.getOrNull() }
    val updateAvailable = update.check?.compatibleUpdate
    val currentDownloaded = update.downloadedInstaller
    val latestInstallerDownloaded = currentDownloaded?.fileName?.toString() == updateAvailable?.name
    var notifications by remember { mutableStateOf(DesktopUpdater.notificationsEnabled()) }

    Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(24.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("App Updates", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
            Text("Check XAN releases and install updates for this computer.", color = XanColors.Muted)
        }

        Column(Modifier.fillMaxWidth().background(XanColors.Background, RoundedCornerShape(18.dp)).padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Current version", color = XanColors.Muted, style = MaterialTheme.typography.labelSmall)
            Text("XAN Desktop $XAN_DESKTOP_VERSION", color = XanColors.Text, style = MaterialTheme.typography.titleLarge)
            Text(platform?.label ?: "This operating system or architecture is not supported for desktop updates.", color = XanColors.Muted)
            Text("Stable channel", color = XanColors.Muted)
            HorizontalDivider(color = XanColors.Border.copy(alpha = 0.5f))
            Text("Latest desktop version", color = XanColors.Muted, style = MaterialTheme.typography.labelSmall)
            Text(
                updateAvailable?.let { "${it.version} · ${it.name}" }
                    ?: update.check?.latestDesktopAsset?.let { it.version }
                    ?: "No compatible desktop installer found",
                color = XanColors.Text,
                style = MaterialTheme.typography.titleMedium,
            )
            update.notice?.let { Text(it, color = XanColors.Muted) }
            update.check?.latestRelease?.let { release ->
                Text("Latest GitHub release: ${release.tag} · ${release.publishedAt.take(10)}", color = XanColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
            update.checkedAt?.let { checkedAt ->
                val formatted = runCatching {
                    java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm")
                        .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(checkedAt))
                }.getOrDefault("Previously checked")
                Text("Last checked $formatted", color = XanColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(enabled = !working && platform != null, onClick = {
                    working = true
                    status = "Checking GitHub releases…"
                    scope.launch {
                        DesktopUpdater.checkForUpdates()
                        status = ""
                        working = false
                    }
                }) { Text(if (working) "Working…" else "Check for Updates") }
                if (updateAvailable != null && !latestInstallerDownloaded) {
                    OutlinedButton(enabled = !working, onClick = {
                        working = true
                        status = "Downloading and verifying the installer…"
                        scope.launch {
                            runCatching { DesktopUpdater.downloadUpdate() }
                                .onSuccess { status = "Installer downloaded and verified." }
                                .onFailure { status = it.message ?: "Could not download the installer." }
                            working = false
                        }
                    }) { Text("Download Update") }
                }
            }
            val progress = update.downloadingBytes
            if (progress != null) {
                val total = update.downloadSizeBytes?.coerceAtLeast(1) ?: 1
                LinearProgressIndicator(progress = { (progress.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("${formatUpdateBytes(progress)} of ${formatUpdateBytes(total)}", color = XanColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
            if (status.isNotBlank()) Text(status, color = XanColors.Muted)
            update.error?.let { Text(it, color = XanColors.Accent) }
            currentDownloaded?.let { path ->
                HorizontalDivider(color = XanColors.Border.copy(alpha = 0.5f))
                Text("Verified installer downloaded", color = XanColors.Text, style = MaterialTheme.typography.titleMedium)
                Text("${path.fileName} · ${formatUpdateBytes(runCatching { java.nio.file.Files.size(path) }.getOrDefault(0L))}", color = XanColors.Muted)
                Text("XAN hands the installer to your operating system. Follow its prompts, then restart XAN.", color = XanColors.Muted)
                OutlinedButton(enabled = !working, onClick = {
                    status = runCatching { DesktopUpdater.openInstaller() }.getOrElse { it.message ?: "Could not open the installer." }
                }) { Text("Install Update") }
            }
        }

        Column(Modifier.fillMaxWidth().background(XanColors.Background, RoundedCornerShape(18.dp)).padding(17.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            SettingSwitch(
                "Automatic Updates",
                "Check when XAN opens and download verified installers. Installation still uses your system installer.",
                automatic,
            ) { enabled -> automatic = enabled; DesktopUpdater.setAutomaticUpdatesEnabled(enabled) }
            HorizontalDivider(color = XanColors.Border.copy(alpha = 0.5f))
            SettingSwitch(
                "Update Notifications",
                "Show a system notification when a new desktop installer is available.",
                notifications,
            ) { enabled -> notifications = enabled; DesktopUpdater.setNotificationsEnabled(enabled) }
            HorizontalDivider(color = XanColors.Border.copy(alpha = 0.5f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Clear Update Downloads", color = XanColors.Text)
                    Text("Remove verified installers and incomplete update files from XAN's update folder.", color = XanColors.Muted)
                }
                TextButton(onClick = { confirmClear = true }, enabled = currentDownloaded != null || update.downloadingBytes != null) { Text("Clear") }
            }
        }

        val release = update.check?.latestRelease
        Column(Modifier.fillMaxWidth().background(XanColors.Background, RoundedCornerShape(18.dp)).padding(17.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Changelog", color = XanColors.Text, style = MaterialTheme.typography.titleMedium)
            if (release == null) {
                Text("Release notes will appear after XAN checks GitHub.", color = XanColors.Muted)
            } else {
                Text("${release.name} · ${release.publishedAt.take(10)}", color = XanColors.Muted)
                Text(
                    release.changelog.ifBlank { "This release does not include release notes." }.take(4_000),
                    color = XanColors.Text,
                    maxLines = 18,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(enabled = release.releaseUrl.isNotBlank(), onClick = { runCatching { SystemBrowser.openUrl(release.releaseUrl) }.onFailure { status = it.message ?: "Could not open release notes." } }) { Text("View Release") }
                    TextButton(onClick = { runCatching { SystemBrowser.openUrl("https://github.com/bxanedot/xan/commits/main/") }.onFailure { status = it.message ?: "Could not open commits." } }) { Text("Recent Commits") }
                }
            }
            HorizontalDivider(color = XanColors.Border.copy(alpha = 0.5f))
            Text("Recent commits", color = XanColors.Text, style = MaterialTheme.typography.titleMedium)
            if (update.recentCommits.isEmpty()) {
                Text("Commit history will appear after XAN checks GitHub.", color = XanColors.Muted)
            } else update.recentCommits.forEach { commit ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                        runCatching { SystemBrowser.openUrl(commit.url) }.onFailure { status = it.message ?: "Could not open this commit." }
                    }.padding(vertical = 8.dp),
                ) {
                    Text(commit.message, color = XanColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${commit.sha.take(7)} · ${commit.committedAt.take(10)}", color = XanColors.Muted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear downloaded updates?") },
        text = { Text("This removes XAN update installer files and incomplete downloads. It does not uninstall XAN or change your current version.") },
        confirmButton = {
            TextButton(onClick = {
                runCatching { DesktopUpdater.clearDownloads() }
                    .onSuccess { removed -> status = if (removed == 0) "There are no downloaded update files to clear." else "Removed $removed update file${if (removed == 1) "" else "s"}." }
                    .onFailure { status = it.message ?: "Could not clear downloaded updates." }
                confirmClear = false
            }) { Text("Clear") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

private fun formatUpdateBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes.toDouble() / (1024 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
    bytes >= 1024 -> "%.0f KB".format(bytes.toDouble() / 1024)
    else -> "$bytes B"
}

@Composable
private fun ListeningHistoryDetail(onTrackSelected: (MusicTrack) -> Unit) {
    var events by remember { mutableStateOf(DesktopBackend.recentListeningHistory(100)) }
    var confirmClear by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(20.dp)).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Listening history", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
                Text("${DesktopBackend.listeningHistoryCount()} plays saved", color = XanColors.Muted)
            }
            TextButton(onClick = { confirmClear = true }, enabled = events.isNotEmpty()) { Text("Clear history") }
        }
        if (!DesktopBackend.listeningHistoryEnabled()) {
            Text("Listening history is paused in Settings → Privacy. Existing entries are still shown.", Modifier.padding(top = 10.dp), color = XanColors.Muted)
        }
        if (events.isEmpty()) {
            EmptyPanel(Icons.Rounded.History, "No listening history", "Tracks you play in XAN will appear here when history is enabled.")
        } else {
            events.forEachIndexed { index, event ->
                val track = DesktopBackend.toMusicTrack(event.track)
                TrackRow(track, index, onQueue = { DesktopPlayer.enqueueNext(track) }, onClick = { onTrackSelected(track) })
                Text(
                    java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm")
                        .withZone(java.time.ZoneId.systemDefault())
                        .format(java.time.Instant.ofEpochMilli(event.playedAt)),
                    Modifier.padding(start = 74.dp, bottom = 5.dp),
                    color = XanColors.Muted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (message.isNotBlank()) Text(message, color = XanColors.Muted)
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear listening history?") },
        text = { Text("This permanently removes saved play events and last-played timestamps from your local catalogue.") },
        confirmButton = {
            TextButton(onClick = {
                runCatching { DesktopBackend.clearListeningHistory() }
                    .onSuccess { events = emptyList(); message = "Listening history cleared." }
                    .onFailure { message = it.message ?: "Could not clear listening history." }
                confirmClear = false
            }) { Text("Clear history") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ListeningStatsDetail() {
    var period by remember { mutableStateOf("All time") }
    val since = remember(period) {
        val days = when (period) { "7 days" -> 7L; "30 days" -> 30L; "12 months" -> 365L; else -> null }
        days?.let { System.currentTimeMillis() - it * 24 * 60 * 60 * 1000 }
    }
    val stats = remember(period) { DesktopBackend.listeningHistoryStats(since) }
    val topTracks = remember(period) { DesktopBackend.topListeningHistory(10, since) }
    Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(20.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Your listening stats", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("7 days", "30 days", "12 months", "All time").forEach { option ->
                FilterPill(option, period == option) { period = option }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HistoryStatCard("Listening time", formatListenedTime(stats.listenedMillis), Modifier.weight(1f))
            HistoryStatCard("Plays", stats.playCount.toString(), Modifier.weight(1f))
            HistoryStatCard("Tracks", stats.uniqueTracks.toString(), Modifier.weight(1f))
        }
        if (stats.firstPlayedAt != null && stats.lastPlayedAt != null) {
            Text(
                "First play ${formatHistoryDate(stats.firstPlayedAt)} · Latest ${formatHistoryDate(stats.lastPlayedAt)}",
                color = XanColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SectionTitle("Most played", if (topTracks.isEmpty()) "No plays in this period" else "Ranked by listening time")
        topTracks.forEachIndexed { index, item ->
            val track = DesktopBackend.toMusicTrack(item.track)
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${index + 1}", color = XanColors.Muted, modifier = Modifier.width(22.dp))
                Artwork(track, 42.dp, corner = 9.dp)
                Column(Modifier.weight(1f)) {
                    Text(track.title, maxLines = 1, color = XanColors.Text, fontWeight = FontWeight.SemiBold)
                    Text(track.artist, maxLines = 1, color = XanColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${item.playCount} plays", color = XanColors.Text)
                    Text(formatListenedTime(item.listenedMillis), color = XanColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (topTracks.isEmpty()) EmptyPanel(Icons.Rounded.BarChart, "No stats yet", "Play music with listening history enabled to build your stats.")
    }
}

@Composable
private fun HistoryStatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(XanColors.CardLight, RoundedCornerShape(16.dp)).padding(14.dp)) {
        Text(label, color = XanColors.Muted, style = MaterialTheme.typography.labelMedium)
        Text(value, Modifier.padding(top = 5.dp), color = XanColors.Text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

private fun formatListenedTime(milliseconds: Long): String {
    val totalMinutes = milliseconds.coerceAtLeast(0) / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

private fun formatHistoryDate(epochMillis: Long): String = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")
    .withZone(java.time.ZoneId.systemDefault())
    .format(java.time.Instant.ofEpochMilli(epochMillis))

@Composable
private fun DiagnosticsDetail() {
    var status by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val logDirectory = remember { DesktopBackend.diagnosticsDirectory() }
    Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(20.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Diagnostic logs", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        Text("XAN keeps a small rotating log on this device to help diagnose playback and application errors. Logs avoid track titles, search terms, account credentials, and full URLs. Export creates a ZIP you can review before sharing.", color = XanColors.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Export XAN diagnostic logs"
                    selectedFile = java.io.File("xan-diagnostics.zip")
                    isAcceptAllFileFilterUsed = false
                    addChoosableFileFilter(javax.swing.filechooser.FileNameExtensionFilter("ZIP archive", "zip"))
                }
                if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                    val destination = chooser.selectedFile.toPath().let { if (it.fileName.toString().endsWith(".zip", true)) it else it.resolveSibling("${it.fileName}.zip") }
                    runCatching { DiagnosticLog.export(logDirectory, destination) }
                        .onSuccess { status = "Diagnostic logs exported to ${it.fileName}. Review the archive before sharing it." }
                        .onFailure { status = it.message ?: "Could not export diagnostic logs." }
                }
            }) { Text("Export logs") }
            OutlinedButton(onClick = { confirmClear = true }) { Text("Clear logs") }
        }
        if (status.isNotBlank()) Text(status, color = XanColors.Muted)
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear diagnostic logs?") },
        text = { Text("This permanently deletes the locally stored diagnostic log files.") },
        confirmButton = {
            TextButton(onClick = {
                runCatching { DiagnosticLog.clear(logDirectory) }
                    .onSuccess { status = "Diagnostic logs cleared." }
                    .onFailure { status = it.message ?: "Could not clear diagnostic logs." }
                confirmClear = false
            }) { Text("Clear logs") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun LyricsSettingsDetail() {
    var autoScroll by remember { mutableStateOf(DesktopBackend.lyricsAutoScroll()) }
    var blur by remember { mutableStateOf(DesktopBackend.lyricsBlur()) }
    var romanizationEnabled by remember { mutableStateOf(DesktopBackend.lyricsRomanizationEnabled()) }
    var animation by remember { mutableStateOf(DesktopBackend.lyricsAnimation()) }
    var providerOrder by remember { mutableStateOf(DesktopBackend.lyricsProviderOrder()) }
    var translationLanguage by remember { mutableStateOf(DesktopBackend.lyricsTranslationLanguage()) }
    var translationApiKey by remember { mutableStateOf("") }
    var translationKeyConfigured by remember { mutableStateOf(DesktopBackend.lyricsTranslationConfigured()) }
    var confirmClear by remember { mutableStateOf(false) }
    var cacheBytes by remember { mutableStateOf(DesktopBackend.lyricsCacheSizeBytes()) }
    var japanesePackInstalled by remember { mutableStateOf(DesktopOptionalFeaturePacks.japaneseInstalled()) }
    var updatingFeaturePack by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(20.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Synchronized lyrics", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        SettingSwitch("Auto-scroll", "Follow the current line. Manual scrolling pauses it for five seconds.", autoScroll) {
            autoScroll = it
            DesktopBackend.setLyricsAutoScroll(it)
        }
        SettingSwitch("Blur inactive lines", "De-emphasize lines away from the current lyric. Reduced transparency disables blur and uses opacity instead.", blur) {
            blur = it
            DesktopBackend.setLyricsBlur(it)
        }
        SettingSwitch("Romanize non-Latin lyrics", "Show an offline Latin-script reading for Korean, Cyrillic, Hindi, and Punjabi; Japanese dictionary readings use the optional language pack below.", romanizationEnabled) {
            romanizationEnabled = it
            DesktopBackend.setLyricsRomanizationEnabled(it)
        }
        Text("Optional language packs", style = MaterialTheme.typography.titleMedium, color = XanColors.Text)
        Text("Japanese dictionary romanization is downloaded separately and stored outside the app. Remove it here to reclaim its storage.", color = XanColors.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !updatingFeaturePack && !japanesePackInstalled,
                onClick = {
                    updatingFeaturePack = true
                    scope.launch {
                        runCatching { DesktopOptionalFeaturePacks.installJapanesePack() }
                            .onSuccess { japanesePackInstalled = true; status = "Japanese romanization pack installed." }
                            .onFailure { status = it.message ?: "Could not install the Japanese romanization pack." }
                        updatingFeaturePack = false
                    }
                },
            ) { Text(if (updatingFeaturePack) "Installing…" else if (japanesePackInstalled) "Japanese pack installed" else "Install Japanese pack") }
            OutlinedButton(enabled = !updatingFeaturePack, onClick = {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Install XAN Japanese romanization pack"
                    isMultiSelectionEnabled = false
                    fileFilter = javax.swing.filechooser.FileNameExtensionFilter("XAN feature pack", "jar")
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    val source = chooser.selectedFile.toPath()
                    updatingFeaturePack = true
                    scope.launch {
                        try {
                            DesktopOptionalFeaturePacks.installJapanesePack(source)
                            japanesePackInstalled = true
                            status = "Japanese romanization pack installed."
                        } catch (failure: Exception) {
                            status = failure.message ?: "Could not install the Japanese pack."
                        } finally { updatingFeaturePack = false }
                    }
                }
            }) { Text("Install from file…") }
            if (japanesePackInstalled) OutlinedButton(
                enabled = !updatingFeaturePack,
                onClick = {
                    runCatching { DesktopOptionalFeaturePacks.uninstallJapanesePack() }
                        .onSuccess { japanesePackInstalled = false; status = "Japanese romanization pack removed from this device." }
                        .onFailure { status = it.message ?: "Could not remove the Japanese romanization pack." }
                },
            ) { Text("Remove pack") }
        }
        HorizontalDivider(color = XanColors.Border)
        Text("AI translation", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        Text("When you choose Translate or AI romanize in the lyrics panel, XAN sends the lyric lines to OpenRouter using your API key. Results are saved in the local lyrics cache and reused for the same track, lyrics, and language.", color = XanColors.Muted)
        OutlinedTextField(
            value = translationLanguage,
            onValueChange = { value -> translationLanguage = value.take(40); DesktopBackend.setLyricsTranslationLanguage(value) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Target language") },
        )
        OutlinedTextField(
            value = translationApiKey,
            onValueChange = { translationApiKey = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(if (translationKeyConfigured) "Replace OpenRouter API key" else "OpenRouter API key") },
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                runCatching { DesktopBackend.saveLyricsTranslationApiKey(translationApiKey) }
                    .onSuccess { translationApiKey = ""; translationKeyConfigured = true; status = "Translation key saved in the operating system credential store." }
                    .onFailure { status = it.message ?: "Could not save the translation key." }
            }, enabled = translationApiKey.isNotBlank()) { Text("Save key") }
            if (translationKeyConfigured) OutlinedButton(onClick = {
                DesktopBackend.clearLyricsTranslationApiKey()
                translationKeyConfigured = false
                translationApiKey = ""
                status = "Translation key removed."
            }) { Text("Remove key") }
        }
        Text(if (translationKeyConfigured) "A key is stored securely on this device." else "No API key saved.", color = XanColors.Muted, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(color = XanColors.Border)
        Text("Provider priority", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        Text("XAN checks these lyrics providers in order and keeps the result in the local cache.", color = XanColors.Muted)
        providerOrder.forEachIndexed { index, provider ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}. $provider", Modifier.weight(1f), color = XanColors.Text)
                TextButton(enabled = index > 0, onClick = {
                    providerOrder = providerOrder.toMutableList().apply { add(index - 1, removeAt(index)) }
                    DesktopBackend.setLyricsProviderOrder(providerOrder)
                }) { Text("Move up") }
                TextButton(enabled = index < providerOrder.lastIndex, onClick = {
                    providerOrder = providerOrder.toMutableList().apply { add(index + 1, removeAt(index)) }
                    DesktopBackend.setLyricsProviderOrder(providerOrder)
                }) { Text("Move down") }
            }
        }
        HorizontalDivider(color = XanColors.Border)
        Text("Word animation", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        Text("XAN word highlighting is the default when word timestamps are available.", color = XanColors.Muted)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("XAN", "Fade", "Glow", "Slide", "None").forEach { style ->
                FilterPill(style, animation == style) { animation = style; DesktopBackend.setLyricsAnimation(style) }
            }
        }
        HorizontalDivider(color = XanColors.Border)
        Text("Cached lyrics · ${formatStorageSize(cacheBytes)}", color = XanColors.Muted)
        OutlinedButton(onClick = { confirmClear = true }, enabled = cacheBytes > 0) { Text("Clear lyrics cache") }
        if (status.isNotBlank()) Text(status, color = XanColors.Muted)
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear cached lyrics?") },
        text = { Text("Downloaded lyrics will be fetched again when available online.") },
        confirmButton = {
            TextButton(onClick = {
                runCatching { DesktopBackend.clearLyricsCache() }
                    .onSuccess { cacheBytes = DesktopBackend.lyricsCacheSizeBytes(); status = "Lyrics cache cleared." }
                    .onFailure { status = it.message ?: "Could not clear lyrics cache." }
                confirmClear = false
            }) { Text("Clear") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun VideoSearchSettingsDetail() {
    var videoOnly by remember { mutableStateOf(DesktopBackend.videoOnlySearch()) }
    var fallback by remember { mutableStateOf(DesktopBackend.fallbackEnabled()) }
    var otherSources by remember { mutableStateOf(DesktopBackend.otherVideoSourcesEnabled()) }
    Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(20.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Video Search", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        SettingSwitch("Search videos only", "Use the Videos only filter as the default when you open Search.", videoOnly) {
            videoOnly = it
            DesktopBackend.setVideoOnlySearch(it)
        }
        SettingSwitch("YouTube video fallback", "Include regular YouTube videos when YouTube Music has no song results.", fallback) {
            fallback = it
            DesktopBackend.setFallbackEnabled(it)
        }
        SettingSwitch("Other video sites", "Search video sites supported by NewPipe alongside YouTube.", otherSources) {
            otherSources = it
            DesktopBackend.setOtherVideoSourcesEnabled(it)
        }
        HorizontalDivider(color = XanColors.Border)
        Text("Playback and offline saving currently use progressive MP4 formats supported by the desktop media engine. Adaptive streams and separate audio/video muxing are not available yet.", color = XanColors.Muted)
    }
}

@Composable
internal fun SettingSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = XanColors.Text)
            Text(description, color = XanColors.Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun BackupRestoreDetail(onBackupRestored: () -> Unit) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var backupToRestore by remember { mutableStateOf<Path?>(null) }
    Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(20.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Backup & Restore", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        Text("Backups include the local XAN catalogue, playlists, favorites, history, and supported settings. YouTube Music and Last.fm credentials are excluded.", color = XanColors.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(enabled = !working, onClick = {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Create XAN backup"
                    dialogType = JFileChooser.SAVE_DIALOG
                    selectedFile = java.io.File("xan-backup-${java.time.LocalDate.now()}.xanbackup")
                }
                if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                    var target = chooser.selectedFile.toPath()
                    if (!target.fileName.toString().contains('.')) target = target.resolveSibling("${target.fileName}.xanbackup")
                    val path = target
                    scope.launch {
                        working = true
                        runCatching { withContext(Dispatchers.IO) { DesktopBackend.createBackup(path) } }
                            .onSuccess { status = "Backup created: $path" }
                            .onFailure { status = it.message ?: "Could not create backup." }
                        working = false
                    }
                }
            }) { Text("Create Backup") }
            OutlinedButton(enabled = !working, onClick = {
                val chooser = JFileChooser().apply { dialogTitle = "Restore XAN backup"; dialogType = JFileChooser.OPEN_DIALOG; isMultiSelectionEnabled = false }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) backupToRestore = chooser.selectedFile.toPath()
            }) { Text("Restore Backup") }
        }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotBlank()) Text(status, color = XanColors.Muted)
    }
    backupToRestore?.let { backup -> AlertDialog(
        onDismissRequest = { backupToRestore = null },
        title = { Text("Restore this backup?") },
        text = { Text("Restoring replaces the current XAN catalogue and supported preferences. Account credentials remain in the operating system credential store and are not changed.") },
        confirmButton = {
            TextButton(enabled = !working, onClick = {
                backupToRestore = null
                scope.launch {
                    working = true
                    runCatching { withContext(Dispatchers.IO) { DesktopBackend.restoreBackup(backup) } }
                        .onSuccess { status = "Backup restored. The restored XAN settings are active."; onBackupRestored() }
                        .onFailure { status = it.message ?: "Could not restore this backup." }
                    working = false
                }
            }) { Text("Restore") }
        },
        dismissButton = { TextButton(onClick = { backupToRestore = null }) { Text("Cancel") } },
    ) }
}

@Composable
private fun PrivacyDetail() {
    var historyEnabled by remember { mutableStateOf(DesktopBackend.listeningHistoryEnabled()) }
    var historyCount by remember { mutableStateOf(DesktopBackend.listeningHistoryCount()) }
    var searchHistoryEnabled by remember { mutableStateOf(DesktopBackend.searchHistoryEnabled()) }
    var searchHistoryCount by remember { mutableStateOf(DesktopBackend.searchHistoryCount()) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmClearSearch by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(20.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Listening history", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Save listening activity", color = XanColors.Text)
                Text("Store completed listens on this device. Last.fm scrobbling can still run when history is paused.", color = XanColors.Muted)
            }
            Switch(checked = historyEnabled, onCheckedChange = { enabled ->
                historyEnabled = enabled
                runCatching { DesktopBackend.setListeningHistoryEnabled(enabled) }
                    .onFailure { status = it.message ?: "Could not update this setting." }
            })
        }
        Text("$historyCount saved listening events", color = XanColors.Muted)
        OutlinedButton(onClick = { confirmClear = true }, enabled = historyCount > 0) { Text("Clear listening history") }
        if (status.isNotBlank()) Text(status, color = XanColors.Muted)
        HorizontalDivider(color = XanColors.Border)
        Text("Search history", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Save recent searches", color = XanColors.Text)
                Text("Keep recent search terms on this device for quick access.", color = XanColors.Muted)
            }
            Switch(checked = searchHistoryEnabled, onCheckedChange = { enabled ->
                searchHistoryEnabled = enabled
                runCatching { DesktopBackend.setSearchHistoryEnabled(enabled) }
                    .onFailure { status = it.message ?: "Could not update this setting." }
            })
        }
        Text("$searchHistoryCount saved searches", color = XanColors.Muted)
        OutlinedButton(onClick = { confirmClearSearch = true }, enabled = searchHistoryCount > 0) { Text("Clear search history") }
        HorizontalDivider(color = XanColors.Border)
        Text("Screenshots", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        Text("XAN does not capture screenshots. This desktop app does not block screenshots taken with operating system tools.", color = XanColors.Muted)
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear listening history?") },
        text = { Text("This removes saved listening events and the last-played timestamps from your local catalogue.") },
        confirmButton = {
            TextButton(onClick = {
                runCatching { DesktopBackend.clearListeningHistory() }
                    .onSuccess { historyCount = DesktopBackend.listeningHistoryCount(); status = "Listening history cleared." }
                    .onFailure { status = it.message ?: "Could not clear listening history." }
                confirmClear = false
            }) { Text("Clear") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
    if (confirmClearSearch) AlertDialog(
        onDismissRequest = { confirmClearSearch = false },
        title = { Text("Clear search history?") },
        text = { Text("This removes recent search terms stored on this device.") },
        confirmButton = {
            TextButton(onClick = {
                runCatching { DesktopBackend.clearSearchHistory() }
                    .onSuccess { searchHistoryCount = DesktopBackend.searchHistoryCount(); status = "Search history cleared." }
                    .onFailure { status = it.message ?: "Could not clear search history." }
                confirmClearSearch = false
            }) { Text("Clear") }
        },
        dismissButton = { TextButton(onClick = { confirmClearSearch = false }) { Text("Cancel") } },
    )
}

@Composable
private fun AppearanceDetail(navigationMode: DesktopNavigationMode, onNavigationModeChange: (DesktopNavigationMode) -> Unit, appLogo: AppLogoVariant, onAppLogoChange: (AppLogoVariant) -> Unit, magnetSemicircle: Boolean, onMagnetSemicircleChange: (Boolean) -> Unit, reducedMotion: Boolean, onReducedMotionChange: (Boolean) -> Unit, reducedTransparency: Boolean, onReducedTransparencyChange: (Boolean) -> Unit, animatedPlayerCanvas: Boolean, onAnimatedPlayerCanvasChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(24.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Navigation", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        Text("Choose a floating Magnet, a compact bottom bar, or a persistent sidebar. The sidebar can collapse to icons and does so automatically in narrower windows.", color = XanColors.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            FilterPill("Sidebar", navigationMode == DesktopNavigationMode.SIDEBAR) { onNavigationModeChange(DesktopNavigationMode.SIDEBAR) }
            FilterPill("Magnet", navigationMode == DesktopNavigationMode.MAGNET) { onNavigationModeChange(DesktopNavigationMode.MAGNET) }
            FilterPill("Mini bar", navigationMode == DesktopNavigationMode.MINI_BAR) { onNavigationModeChange(DesktopNavigationMode.MINI_BAR) }
        }
        if (navigationMode == DesktopNavigationMode.MAGNET) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Semicircle layout", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
                    Text(if (magnetSemicircle) "Fan the six actions around the Magnet." else "Stack the six actions in a compact pill menu, like Android.", color = XanColors.Muted)
                }
                Switch(checked = magnetSemicircle, onCheckedChange = onMagnetSemicircleChange)
            }
        }
        HorizontalDivider(color = XanColors.Border)
        Text("Brand", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        Text("Choose the XAN icon used across the app.", color = XanColors.Muted)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppLogoVariant.entries.forEach { logo ->
                BrandChoice(
                    label = logo.label,
                    logo = logo,
                    selected = appLogo == logo,
                    modifier = Modifier.width(164.dp),
                    onClick = { onAppLogoChange(logo) },
                )
            }
        }
        HorizontalDivider(color = XanColors.Border)
        Text("Color theme", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        Text("Apply a palette across pages, settings, and the Magnet.", color = XanColors.Muted)
        val currentTheme = XanPalette.find(DesktopBackend.colorTheme()).id
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            XanPalette.all.forEach { palette ->
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                        .background(palette.card)
                        .border(1.dp, if (palette.id == currentTheme) palette.accent else palette.border, RoundedCornerShape(14.dp))
                        .clickable { DesktopBackend.setColorTheme(palette.id); XanColors.selectTheme(palette.id) }
                        .padding(9.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(palette.background, palette.card, palette.accent).forEach { color ->
                            Box(Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(5.dp)).background(color))
                        }
                    }
                    Text(palette.label, Modifier.padding(top = 7.dp), color = palette.text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
        HorizontalDivider(color = XanColors.Border)
        Text("Typeface", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        Text("Use the same built-in typefaces offered by XAN for Android.", color = XanColors.Muted)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            XanFonts.options.forEach { option ->
                FilterPill(option.label, XanFonts.selected == option.id) { XanFonts.select(option.id) }
            }
        }
        HorizontalDivider(color = XanColors.Border)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Animated player canvas", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
                Text("Show softly moving album artwork behind the expanded player and lyrics.", color = XanColors.Muted)
            }
            Switch(checked = animatedPlayerCanvas, onCheckedChange = onAnimatedPlayerCanvasChange)
        }
        HorizontalDivider(color = XanColors.Border)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Reduced motion", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
                Text("Turn off the Magnet's movement and scale animations.", color = XanColors.Muted)
            }
            Switch(checked = reducedMotion, onCheckedChange = onReducedMotionChange)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Reduced transparency", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
                Text("Use clear, opaque lyric styling instead of blur effects.", color = XanColors.Muted)
            }
            Switch(checked = reducedTransparency, onCheckedChange = onReducedTransparencyChange)
        }
    }
}

@Composable
private fun BrandChoice(
    label: String,
    logo: AppLogoVariant,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier.clip(RoundedCornerShape(18.dp))
            .background(if (selected) XanColors.Accent.copy(alpha = .12f) else XanColors.Background)
            .border(1.dp, if (selected) XanColors.Accent else XanColors.Border, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(painterResource(logo.resource), contentDescription = null, modifier = Modifier.size(42.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = XanColors.Text, fontWeight = FontWeight.SemiBold)
            if (selected) Text("Selected", color = XanColors.Accent, style = MaterialTheme.typography.labelSmall)
        }
        Icon(
            if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = if (selected) "Selected logo" else "Select $label",
            tint = if (selected) XanColors.Accent else XanColors.Muted,
            modifier = Modifier.size(21.dp),
        )
    }
}

@Composable
private fun YouTubeAccountDetail() {
    var cookie by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(if (DesktopBackend.youtubeConnected()) "YouTube Music account connected." else "Not connected") }
    var account by remember { mutableStateOf<YouTubeAccountState?>(null) }
    var isLoadingAccount by remember { mutableStateOf(false) }
    var isSyncingPlaylists by remember { mutableStateOf(false) }
    var useAccountForBrowse by remember { mutableStateOf(DesktopBackend.youtubeLoginForBrowse()) }
    val scope = rememberCoroutineScope()

    suspend fun refreshAccount() {
        isLoadingAccount = true
        runCatching { DesktopBackend.youtubeAccountState() }
            .onSuccess {
                account = it
                status = "Account and channel list refreshed."
            }
            .onFailure { status = it.message ?: "Could not load this YouTube Music account." }
        isLoadingAccount = false
    }

    LaunchedEffect(Unit) {
        if (DesktopBackend.youtubeConnected()) refreshAccount()
    }

    Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(20.dp)).padding(20.dp)) {
        Text("YouTube Music account", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        Text("Paste the Cookie header from your signed-in YouTube Music session. XAN stores it in the operating system credential store and loads the channel identities available to that session. Google does not allow sign-in inside an embedded desktop webview; a system-browser OAuth flow still needs XAN’s registered Desktop OAuth client.", Modifier.padding(top = 8.dp, bottom = 16.dp), color = XanColors.Muted)
        OutlinedTextField(
            value = cookie,
            onValueChange = { cookie = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Cookie header") },
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
            Button(onClick = {
                runCatching { DesktopBackend.saveYoutubeCookie(cookie) }
                    .onSuccess {
                        cookie = ""
                        status = "Session saved. Loading account and channels…"
                        account = null
                        scope.launch { refreshAccount() }
                    }
                    .onFailure { status = it.message ?: "Could not save this session." }
            }, enabled = cookie.isNotBlank()) { Text("Connect") }
            OutlinedButton(onClick = {
                DesktopBackend.forgetYoutubeAccount()
                cookie = ""
                account = null
                status = "Account disconnected."
            }) { Text("Disconnect") }
            if (DesktopBackend.youtubeConnected()) {
                OutlinedButton(onClick = { scope.launch { refreshAccount() } }, enabled = !isLoadingAccount) {
                    Text(if (isLoadingAccount) "Refreshing…" else "Refresh channels")
                }
            }
        }
        if (DesktopBackend.youtubeConnected()) {
            OutlinedButton(
                onClick = {
                    DesktopBackend.forgetYoutubeAccount(clearSyncedContent = true)
                    cookie = ""
                    account = null
                    status = "Account disconnected and synced YouTube Music content removed from this device."
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Disconnect and clear synced content") }
        }
        Text(status, Modifier.padding(top = 12.dp), color = XanColors.Muted)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Use account for browsing", color = XanColors.Text)
                Text("Personalize search, home, and discovery with the selected channel.", color = XanColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = useAccountForBrowse, onCheckedChange = { enabled ->
                useAccountForBrowse = enabled
                DesktopBackend.setYoutubeLoginForBrowse(enabled)
            })
        }
        HorizontalDivider(Modifier.padding(vertical = 14.dp), color = XanColors.Border)
        Text("Cloud playlists", style = MaterialTheme.typography.titleMedium, color = XanColors.Text)
        Text("Sync this device’s local playlists with the selected YouTube Music channel. XAN creates matching playlists, renames linked ones, and syncs track additions, removals, duplicates, and order. If a local track has no YouTube Music ID, XAN keeps the cloud playlist intact and adds resolvable tracks until that item can be matched. To bring a cloud playlist onto another device, open it from Library → YouTube Music and import a local copy.", Modifier.padding(top = 5.dp, bottom = 10.dp), color = XanColors.Muted)
        OutlinedButton(
            onClick = {
                isSyncingPlaylists = true
                scope.launch {
                    runCatching { DesktopBackend.syncLocalPlaylistsToYoutube() }
                        .onSuccess { result ->
                            status = "Synced ${result.playlistsCreated + result.playlistsUpdated} playlists; added ${result.tracksAdded}, removed ${result.tracksRemoved}, and reordered ${result.tracksReordered} songs. ${result.tracksWithoutYouTubeId} local tracks had no YouTube Music ID; ${result.playlistsPartiallySynced} playlists were left in safe partial-sync mode."
                        }
                        .onFailure { status = it.message ?: "Could not sync local playlists." }
                    isSyncingPlaylists = false
                }
            },
            enabled = DesktopBackend.youtubeConnected() && account?.selectedDataSyncId != null && DesktopBackend.playlists().isNotEmpty() && !isLoadingAccount && !isSyncingPlaylists,
        ) { Text(if (isSyncingPlaylists) "Syncing playlists…" else "Sync local playlists to YouTube Music") }
        account?.let { accountState ->
            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = XanColors.Border)
            Text(accountState.profile.name, style = MaterialTheme.typography.titleMedium, color = XanColors.Text)
            accountState.profile.email?.takeIf(String::isNotBlank)?.let { Text(it, color = XanColors.Muted) }
            accountState.profile.channelHandle?.takeIf(String::isNotBlank)?.let { Text(it, color = XanColors.Muted) }
            if (accountState.channels.isNotEmpty()) {
                Text("YouTube channels", Modifier.padding(top = 12.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall, color = XanColors.Text)
                accountState.channels.forEach { channel ->
                    val selected = channel.dataSyncId == accountState.selectedDataSyncId
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (selected) XanColors.Selected else XanColors.Background)
                            .clickable(enabled = !selected && !isLoadingAccount) {
                                scope.launch {
                                    isLoadingAccount = true
                                    runCatching { DesktopBackend.switchYoutubeChannel(channel.dataSyncId) }
                                        .onSuccess { account = it; status = "Switched to ${it.profile.name}." }
                                        .onFailure { status = it.message ?: "Could not switch YouTube channel." }
                                    isLoadingAccount = false
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null, enabled = !isLoadingAccount)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(channel.name, color = XanColors.Text, fontWeight = FontWeight.Medium)
                            channel.channelHandle?.takeIf(String::isNotBlank)?.let { Text(it, color = XanColors.Muted, style = MaterialTheme.typography.bodySmall) }
                        }
                        if (selected) Text("Selected", color = XanColors.Accent, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlineContentDetail() {
    var fallback by remember { mutableStateOf(DesktopBackend.fallbackEnabled()) }
    Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(20.dp)).padding(20.dp)) {
        Text("Online search", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("YouTube video fallback", color = XanColors.Text)
                Text("Show regular YouTube videos when YouTube Music has no song matches.", color = XanColors.Muted)
            }
            Switch(checked = fallback, onCheckedChange = { fallback = it; DesktopBackend.setFallbackEnabled(it) })
        }
        Text("Off by default", Modifier.padding(top = 8.dp), color = XanColors.Muted)
    }
}

@Composable
private fun SponsorBlockSettingsDetail() {
    var enabled by remember { mutableStateOf(DesktopBackend.sponsorBlockEnabled()) }
    var categories by remember { mutableStateOf(DesktopBackend.sponsorBlockCategories().toSet()) }
    var status by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(20.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("SponsorBlock", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Skip sponsor, self-promo and non-music segments automatically", color = XanColors.Text)
                Text("Uses community-submitted YouTube segment times while YouTube-derived audio is playing.", color = XanColors.Muted)
            }
            Switch(checked = enabled, onCheckedChange = { value ->
                enabled = value
                runCatching { DesktopBackend.setSponsorBlockEnabled(value) }
                    .onFailure { status = it.message ?: "Could not save SponsorBlock settings." }
            })
        }
        HorizontalDivider(color = XanColors.Border)
        Text("Categories", style = MaterialTheme.typography.titleMedium, color = XanColors.Text)
        SponsorBlockCategories.all.forEach { category ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = category in categories, onCheckedChange = { checked ->
                    categories = if (checked) categories + category else categories - category
                    runCatching { DesktopBackend.setSponsorBlockCategories(categories) }
                        .onFailure { status = it.message ?: "Could not save category settings." }
                })
                Text(SponsorBlockCategories.labels.getValue(category), Modifier.weight(1f), color = XanColors.Text)
                if (category == "filler") Text("Aggressive", color = XanColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
        Text("Segments are requested with SponsorBlock’s hash-prefix endpoint. XAN only reads skip segments; it does not submit or vote on segments.", color = XanColors.Muted)
        if (status.isNotBlank()) Text(status, color = XanColors.Muted)
    }
}

@Composable
private fun DownloadStorageDetail() {
    var folder by remember { mutableStateOf(DesktopBackend.downloadDirectoryLabel()) }
    var message by remember { mutableStateOf("") }
    var downloadCount by remember { mutableStateOf(DesktopBackend.downloadedFiles().size) }
    var downloadBytes by remember { mutableStateOf(DesktopBackend.downloadedBytes()) }
    var confirmClear by remember { mutableStateOf(false) }
    var cacheLimitMb by remember { mutableIntStateOf(DesktopBackend.songCacheLimitMb()) }
    var cachedBytes by remember { mutableStateOf(DesktopBackend.cachedAudioBytes()) }
    var cacheMenu by remember { mutableStateOf(false) }
    var confirmClearCache by remember { mutableStateOf(false) }
    var imageLimitMb by remember { mutableIntStateOf(DesktopBackend.imageCacheLimitMb()) }
    var imageBytes by remember { mutableStateOf(DesktopBackend.imageCacheBytes()) }
    var imageCacheMenu by remember { mutableStateOf(false) }
    var confirmClearImages by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(20.dp)).padding(20.dp)) {
        Text("Downloads", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        Text("${formatStorageSize(downloadBytes)} used · $downloadCount audio files", Modifier.padding(top = 8.dp), color = XanColors.Muted)
        Text("Audio files are saved here:", Modifier.padding(top = 8.dp), color = XanColors.Muted)
        OutlinedTextField(folder, { folder = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true)
        Button(onClick = {
            runCatching { java.nio.file.Files.createDirectories(java.nio.file.Path.of(folder)); DesktopBackend.setDownloadDirectory(folder); message = "Download folder saved." }
                .onFailure { message = it.message ?: "Invalid folder." }
        }, Modifier.padding(top = 12.dp)) { Text("Save folder") }
        OutlinedButton(onClick = { confirmClear = true }, Modifier.padding(top = 8.dp), enabled = downloadCount > 0) { Text("Clear all downloads") }
        HorizontalDivider(Modifier.padding(vertical = 14.dp), color = XanColors.Border)
        Text("Song cache", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        Text("${formatStorageSize(cachedBytes)} cached · ${DesktopBackend.cachedAudioTracks().size} songs", Modifier.padding(top = 8.dp), color = XanColors.Muted)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Maximum size", Modifier.weight(1f), color = XanColors.Text)
            Box {
                OutlinedButton(onClick = { cacheMenu = true }) { Text(if (cacheLimitMb < 0) "Unlimited" else if (cacheLimitMb >= 1024) "${cacheLimitMb / 1024} GB" else "$cacheLimitMb MB") }
                DropdownMenu(expanded = cacheMenu, onDismissRequest = { cacheMenu = false }) {
                    listOf(256, 512, 1024, 2048, 4096, -1).forEach { option ->
                        val label = if (option < 0) "Unlimited" else if (option >= 1024) "${option / 1024} GB" else "$option MB"
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            runCatching { DesktopBackend.setSongCacheLimitMb(option) }
                                .onSuccess { cacheLimitMb = option; cachedBytes = DesktopBackend.cachedAudioBytes(); message = "Song cache limit updated." }
                                .onFailure { message = it.message ?: "Could not change the song cache limit." }
                            cacheMenu = false
                        })
                    }
                }
            }
        }
        OutlinedButton(onClick = { confirmClearCache = true }, Modifier.padding(top = 8.dp), enabled = cachedBytes > 0) { Text("Clear song cache") }
        HorizontalDivider(Modifier.padding(vertical = 14.dp), color = XanColors.Border)
        Text("Artwork cache", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        Text("${formatStorageSize(imageBytes)} used", Modifier.padding(top = 8.dp), color = XanColors.Muted)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Maximum size", Modifier.weight(1f), color = XanColors.Text)
            Box {
                OutlinedButton(onClick = { imageCacheMenu = true }) { Text(if (imageLimitMb >= 1024) "${imageLimitMb / 1024} GB" else "$imageLimitMb MB") }
                DropdownMenu(expanded = imageCacheMenu, onDismissRequest = { imageCacheMenu = false }) {
                    listOf(128, 256, 512, 1024, 2048, 4096).forEach { option ->
                        val label = if (option >= 1024) "${option / 1024} GB" else "$option MB"
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            runCatching { DesktopBackend.setImageCacheLimitMb(option) }
                                .onSuccess { imageLimitMb = option; imageBytes = DesktopBackend.imageCacheBytes(); message = "Artwork cache limit updated." }
                                .onFailure { message = it.message ?: "Could not change the artwork cache limit." }
                            imageCacheMenu = false
                        })
                    }
                }
            }
        }
        OutlinedButton(onClick = { confirmClearImages = true }, Modifier.padding(top = 8.dp), enabled = imageBytes > 0) { Text("Clear artwork cache") }
        if (message.isNotBlank()) Text(message, Modifier.padding(top = 8.dp), color = XanColors.Muted)
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Delete all downloaded audio?") },
        text = { Text("This permanently deletes $downloadCount audio files from the configured XAN Downloads folder.") },
        confirmButton = {
            TextButton(onClick = {
                runCatching { DesktopBackend.clearDownloadedFiles() }
                    .onSuccess { count ->
                        downloadCount = DesktopBackend.downloadedFiles().size
                        downloadBytes = DesktopBackend.downloadedBytes()
                        message = "Deleted $count downloaded audio file${if (count == 1) "" else "s"}."
                    }
                    .onFailure { message = it.message ?: "Could not clear downloads." }
                confirmClear = false
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
    if (confirmClearCache) AlertDialog(
        onDismissRequest = { confirmClearCache = false },
        title = { Text("Clear cached songs?") },
        text = { Text("This removes ${formatStorageSize(cachedBytes)} of temporary offline audio. Downloaded songs are kept.") },
        confirmButton = {
            TextButton(onClick = {
                runCatching { DesktopBackend.clearAudioCache() }
                    .onSuccess { count -> cachedBytes = DesktopBackend.cachedAudioBytes(); message = "Removed $count cached song${if (count == 1) "" else "s"}." }
                    .onFailure { message = it.message ?: "Could not clear the song cache." }
                confirmClearCache = false
            }) { Text("Clear cache") }
        },
        dismissButton = { TextButton(onClick = { confirmClearCache = false }) { Text("Cancel") } },
    )
    if (confirmClearImages) AlertDialog(
        onDismissRequest = { confirmClearImages = false },
        title = { Text("Clear artwork cache?") },
        text = { Text("Cached cover art will load again when it is next displayed.") },
        confirmButton = {
            TextButton(onClick = {
                runCatching { DesktopBackend.clearImageCache() }
                    .onSuccess { count -> imageBytes = DesktopBackend.imageCacheBytes(); message = "Removed $count cached artwork files." }
                    .onFailure { message = it.message ?: "Could not clear the artwork cache." }
                confirmClearImages = false
            }) { Text("Clear cache") }
        },
        dismissButton = { TextButton(onClick = { confirmClearImages = false }) { Text("Cancel") } },
    )
}

private fun formatStorageSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return "%.1f %s".format(value, units[unitIndex])
}

@Composable
private fun ScrobblingDetail() {
    val scope = rememberCoroutineScope()
    var lastFmEnabled by remember { mutableStateOf(DesktopBackend.lastFmScrobblingEnabled()) }
    var listenBrainzEnabled by remember { mutableStateOf(DesktopBackend.listenBrainzScrobblingEnabled()) }
    var lastFmConnected by remember { mutableStateOf(DesktopBackend.lastFmConnected()) }
    var listenBrainzConnected by remember { mutableStateOf(DesktopBackend.listenBrainzConnected()) }
    var apiKey by remember { mutableStateOf(DesktopBackend.storedLastFmApiKey()) }
    var apiSecret by remember { mutableStateOf("") }
    var lastFmToken by remember { mutableStateOf("") }
    var listenBrainzToken by remember { mutableStateOf("") }
    var lastFmStatus by remember { mutableStateOf(if (lastFmConnected) "Last.fm connected." else "Not connected") }
    var listenBrainzStatus by remember {
        mutableStateOf(if (listenBrainzConnected) "Connected as ${DesktopBackend.listenBrainzUsername()}" else "Not connected")
    }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(20.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Last.fm", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (lastFmConnected) "Connected" else "Not connected", color = XanColors.Text)
                Text("Scrobble after the listening threshold is reached.", color = XanColors.Muted)
            }
            Switch(checked = lastFmEnabled, onCheckedChange = { enabled ->
                lastFmEnabled = enabled
                runCatching { DesktopBackend.setLastFmScrobblingEnabled(enabled) }
                    .onFailure { lastFmStatus = it.message ?: "Could not update Last.fm settings." }
            })
        }
        OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Last.fm API key") })
        OutlinedTextField(apiSecret, { apiSecret = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Last.fm API shared secret") }, visualTransformation = PasswordVisualTransformation())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(enabled = apiKey.isNotBlank() && apiSecret.isNotBlank() && !busy, onClick = {
                scope.launch {
                    busy = true
                    runCatching { DesktopBackend.beginLastFmAuth(apiKey, apiSecret) }
                        .onSuccess { lastFmToken = it; lastFmStatus = "Authorize XAN in your browser, then finish connecting here." }
                        .onFailure { lastFmStatus = it.message ?: "Last.fm authorization failed." }
                    busy = false
                }
            }) { Text("Connect Last.fm") }
            if (lastFmToken.isNotBlank()) OutlinedButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    runCatching { DesktopBackend.finishLastFmAuth(lastFmToken) }
                        .onSuccess { lastFmConnected = true; lastFmStatus = "Connected as $it"; lastFmToken = ""; apiSecret = "" }
                        .onFailure { lastFmStatus = it.message ?: "Could not finish Last.fm authorization." }
                    busy = false
                }
            }) { Text("Finish connection") }
            if (lastFmConnected) OutlinedButton(onClick = {
                DesktopBackend.disconnectLastFm()
                lastFmConnected = false
                lastFmStatus = "Last.fm disconnected."
            }) { Text("Disconnect") }
        }
        Text(lastFmStatus, color = XanColors.Muted)

        HorizontalDivider(color = XanColors.Border)
        Text("ListenBrainz", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (listenBrainzConnected) "Connected as ${DesktopBackend.listenBrainzUsername()}" else "Not connected", color = XanColors.Text)
                Text("Scrobble each qualifying play to ListenBrainz.", color = XanColors.Muted)
            }
            Switch(checked = listenBrainzEnabled, onCheckedChange = { enabled ->
                listenBrainzEnabled = enabled
                runCatching { DesktopBackend.setListenBrainzScrobblingEnabled(enabled) }
                    .onFailure { listenBrainzStatus = it.message ?: "Could not update ListenBrainz settings." }
            })
        }
        OutlinedTextField(
            value = listenBrainzToken,
            onValueChange = { listenBrainzToken = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("ListenBrainz user token") },
            visualTransformation = PasswordVisualTransformation(),
        )
        Text("Create a user token in your ListenBrainz account settings. XAN validates it and stores it in the operating system credential store.", color = XanColors.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(enabled = listenBrainzToken.isNotBlank() && !busy, onClick = {
                scope.launch {
                    busy = true
                    runCatching { DesktopBackend.connectListenBrainz(listenBrainzToken) }
                        .onSuccess { username ->
                            listenBrainzConnected = true
                            listenBrainzStatus = "Connected as $username"
                            listenBrainzToken = ""
                        }
                        .onFailure { listenBrainzStatus = it.message ?: "ListenBrainz connection failed." }
                    busy = false
                }
            }) { Text(if (busy) "Connecting…" else "Connect ListenBrainz") }
            if (listenBrainzConnected) OutlinedButton(onClick = {
                DesktopBackend.disconnectListenBrainz()
                listenBrainzConnected = false
                listenBrainzStatus = "ListenBrainz disconnected."
            }) { Text("Disconnect") }
        }
        Text(listenBrainzStatus, color = XanColors.Muted)
    }
}

@Composable
private fun IntegrationsDetail() {
    val scope = rememberCoroutineScope()
    var discordEnabled by remember { mutableStateOf(DesktopBackend.isDiscordEnabled()) }
    var discordAppId by remember { mutableStateOf(DesktopBackend.discordApplicationId()) }
    var discordStatus by remember { mutableStateOf("") }
    var playlistUrl by remember { mutableStateOf("") }
    var playlist by remember { mutableStateOf<SpotifyPlaylistImportReport?>(null) }
    var playlistStatus by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(20.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Discord Rich Presence", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        Text("Shows the current song in your Discord profile while the desktop client is running.", color = XanColors.Muted)
        OutlinedTextField(discordAppId, { discordAppId = it; DesktopBackend.setDiscordApplicationId(it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Discord Application ID") })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enable listening status", Modifier.weight(1f), color = XanColors.Text)
            Switch(discordEnabled, { enabled ->
                discordEnabled = enabled
                scope.launch { discordStatus = runCatching { DesktopBackend.setDiscordEnabled(enabled) }.fold({ if (enabled) "Presence enabled." else "Presence cleared." }, { it.message ?: "Discord update failed." }) }
            })
        }
        if (discordStatus.isNotBlank()) Text(discordStatus, color = XanColors.Muted)
        HorizontalDivider(color = XanColors.Border)
        Text("Spotify playlist import", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        Text("Import a public playlist preview, match tracks in XAN, and save the matched songs as a XAN playlist.", color = XanColors.Muted)
        OutlinedTextField(playlistUrl, { playlistUrl = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Spotify playlist URL") })
        Button(enabled = playlistUrl.isNotBlank() && !busy, onClick = {
            scope.launch {
                busy = true
                runCatching {
                    val source = DesktopBackend.importSpotifyPlaylist(playlistUrl)
                    DesktopBackend.migrateSpotifyPlaylist(source)
                }
                    .onSuccess {
                        playlist = it
                        playlistStatus = "Saved ${it.matches.size} matched tracks to XAN; ${it.unmatched.size} unmatched.${if (it.playlist.mayBeTruncated) " Spotify only supplied a partial public preview." else ""}"
                    }
                    .onFailure { playlistStatus = it.message ?: "Could not import playlist." }
                busy = false
            }
        }) { Text(if (busy) "Working…" else "Import playlist") }
        if (playlistStatus.isNotBlank()) Text(playlistStatus, color = XanColors.Muted)
        playlist?.let { report ->
            Text("${report.playlist.name} · ${report.matches.size} matched · ${report.unmatched.size} unmatched", color = XanColors.Text, fontWeight = FontWeight.SemiBold)
            Text("Saved playlist: ${DesktopBackend.playlists().firstOrNull { it.id == report.catalogPlaylistId }?.name ?: report.playlist.name}", color = XanColors.Muted)
            if (report.matches.isNotEmpty()) {
                Text("Matched tracks", Modifier.padding(top = 8.dp), color = XanColors.Text)
                report.matches.take(30).forEachIndexed { index, (sourceTrack, matchedTrack) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${sourceTrack.title} · ${sourceTrack.artist}", Modifier.weight(1f), color = XanColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("→ ${matchedTrack.title} · ${matchedTrack.artist}", Modifier.weight(1f), color = XanColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (report.matches.size > 30) Text("And ${report.matches.size - 30} more matched tracks", color = XanColors.Muted)
            }
            if (report.unmatched.isNotEmpty()) {
                Text("Unmatched tracks", Modifier.padding(top = 8.dp), color = XanColors.Text)
                report.unmatched.take(30).forEach { track -> Text("${track.title} · ${track.artist}", color = XanColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (report.unmatched.size > 30) Text("And ${report.unmatched.size - 30} more unmatched tracks", color = XanColors.Muted)
            }
        }
    }
}

@Composable
private fun EmptyPanel(icon: ImageVector, title: String, subtitle: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 28.dp)
            .background(XanColors.Card, RoundedCornerShape(22.dp)).padding(30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = XanColors.Accent, modifier = Modifier.size(35.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        Text(subtitle, color = XanColors.Muted)
    }
}
