package app.xan.shared

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import org.jetbrains.compose.resources.painterResource
import app.xan.shared.resources.Res
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import app.xan.music.listentogether.ControlModes
import app.xan.music.listentogether.PlaybackActions
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import androidx.compose.animation.core.animateFloatAsState
import coil3.compose.AsyncImage
import kotlin.math.roundToInt

private data class IosMagnetItem(val title: String, val symbol: String)

private val IosMagnetItems = listOf(
    IosMagnetItem("Home", "⌂"),
    IosMagnetItem("Search", "⌕"),
    IosMagnetItem("Library", "▤"),
    IosMagnetItem("Queue", "≡"),
    IosMagnetItem("Listen Together", "♫"),
    IosMagnetItem("Settings", "⚙"),
    IosMagnetItem("About", "ⓘ"),
)

private val IosSearchCategories = listOf("All", "Songs", "Videos", "Artists", "Albums", "Community playlists", "Featured playlists")

private sealed interface IosMusicCatalogPage {
    data class Artist(val page: IosMusicArtistPage) : IosMusicCatalogPage
    data class Album(val page: IosMusicAlbumPage) : IosMusicCatalogPage
    data class Playlist(val page: IosMusicPlaylistPage) : IosMusicCatalogPage
}

private data class IosMusicSearchOutcomes(
    val songs: Result<List<IosMusicSearchTrack>>,
    val videos: Result<List<IosMusicSearchTrack>>,
    val artists: Result<List<IosMusicArtistResult>>,
    val albums: Result<List<IosMusicAlbumResult>>,
    val communityPlaylists: Result<List<IosMusicHomeItem.Playlist>>,
    val featuredPlaylists: Result<List<IosMusicHomeItem.Playlist>>,
)

private data class XanPalette(
    val background: Color,
    val panel: Color,
    val accent: Color,
    val text: Color,
    val muted: Color,
    val isLight: Boolean = false,
)

private fun xanPalette(name: String): XanPalette = when (name) {
    "Midnight" -> XanPalette(Color(0xFF10131B), Color(0xFF1C2130), Color(0xFF9EAEFF), Color.White, Color(0xFFB9C0D3))
    "Plum" -> XanPalette(Color(0xFF19121E), Color(0xFF281B30), Color(0xFFE0A9FF), Color.White, Color(0xFFD0C0D8))
    "Forest" -> XanPalette(Color(0xFF101813), Color(0xFF1A281F), Color(0xFF9BE0AD), Color.White, Color(0xFFBED1C2))
    "Light" -> XanPalette(Color(0xFFF4F3F6), Color.White, Color(0xFF9A7900), Color(0xFF17171A), Color(0xFF66636B), isLight = true)
    else -> XanPalette(Color(0xFF121212), Color(0xFF1D1D1D), Color(0xFFFFD500), Color.White, Color.LightGray)
}

/** iOS/iPadOS surface backed by the shared XAN room protocol. */
@Composable
fun XanApp() {
    val room by IosListenTogether.state.collectAsState()
    val playback by IosMusicPlayback.state.collectAsState()
    val playbackQueue by IosMusicPlayback.queueState.collectAsState()
    val sleepTimer by IosMusicPlayback.sleepTimerState.collectAsState()
    val lyrics by IosLyrics.state.collectAsState()
    val lyricTranslation by IosLyricsTranslation.state.collectAsState()
    val download by IosMusicPlayback.downloadState.collectAsState()
    val library by IosMusicLibrary.state.collectAsState()
    val lastFm by IosLastFm.state.collectAsState()
    val canControlRoom = room.roomCode == null || (room.status == "In room" && (room.isHost || room.controlMode == ControlModes.EVERYONE))
    var server by remember { mutableStateOf(IosListenTogether.savedServer()) }
    var name by remember { mutableStateOf(IosListenTogether.savedUsername()) }
    var roomCode by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf("Home") }
    var useMagnet by remember { mutableStateOf(readIosPreference("navigation.useMagnet")?.toBooleanStrictOrNull() ?: true) }
    var reducedMotion by remember { mutableStateOf(readIosPreference("appearance.reducedMotion")?.toBooleanStrictOrNull() ?: false) }
    var magnetOpen by remember { mutableStateOf(false) }
    var magnetShowing by remember { mutableStateOf(false) }
    var magnetDragging by remember { mutableStateOf(false) }
    var magnetX by remember { mutableFloatStateOf(readIosPreference("navigation.magnet.x")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f) }
    var magnetY by remember { mutableFloatStateOf(readIosPreference("navigation.magnet.y")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f) }
    var selectedPalette by remember { mutableStateOf(readIosPreference("appearance.palette") ?: "XAN") }
    var selectedFont by remember { mutableStateOf(IosTypeface.fromPreference(readIosPreference("selected_font"))) }
    var brandFontEnabled by remember {
        mutableStateOf(readIosPreference("brandFontEnabled")?.toBooleanStrictOrNull() ?: true)
    }
    var fontMenuOpen by remember { mutableStateOf(false) }
    var appLogo by remember {
        mutableStateOf(
            IosAppLogo.fromPreferences(
                readIosPreference("brand.appLogo"),
                readIosPreference("brand.androidLogo")?.toBooleanStrictOrNull(),
            ),
        )
    }
    var youtubeCookie by remember { mutableStateOf("") }
    var hasYoutubeCookie by remember { mutableStateOf(IosCredentialStorage.hasYouTubeCookie()) }
    var youtubeAccount by remember { mutableStateOf<IosMusicAccountState?>(null) }
    var youtubeAccountLoading by remember { mutableStateOf(false) }
    var youtubeAccountMessage by remember { mutableStateOf<String?>(null) }
    var youtubeAccountError by remember { mutableStateOf(false) }
    var accountDataSyncId by remember { mutableStateOf(normalizeIosDataSyncId(readIosPreference("account.dataSyncId"))) }
    var useAccountForBrowse by remember {
        mutableStateOf(readIosPreference("useLoginForBrowse")?.toBooleanStrictOrNull() ?: true)
    }
    var youtubeAccountLibrary by remember { mutableStateOf<IosMusicAccountLibrary?>(null) }
    var youtubeLibraryLoading by remember { mutableStateOf(false) }
    var youtubeLibraryMessage by remember { mutableStateOf<String?>(null) }
    var youtubeLibraryError by remember { mutableStateOf(false) }
    var youtubeLibraryRequestSequence by remember { mutableIntStateOf(0) }
    var credentialMessage by remember { mutableStateOf<String?>(null) }
    var credentialMessageIsError by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf(emptyList<IosMusicSearchTrack>()) }
    var videoResults by remember { mutableStateOf(emptyList<IosMusicSearchTrack>()) }
    var artistResults by remember { mutableStateOf(emptyList<IosMusicArtistResult>()) }
    var albumResults by remember { mutableStateOf(emptyList<IosMusicAlbumResult>()) }
    var communityPlaylistResults by remember { mutableStateOf(emptyList<IosMusicHomeItem.Playlist>()) }
    var featuredPlaylistResults by remember { mutableStateOf(emptyList<IosMusicHomeItem.Playlist>()) }
    var searchCategory by remember { mutableStateOf("All") }
    var selectedCatalogPage by remember { mutableStateOf<IosMusicCatalogPage?>(null) }
    var catalogReturnTab by remember { mutableStateOf("Search") }
    var isLoadingCatalogPage by remember { mutableStateOf(false) }
    var catalogRequestSequence by remember { mutableIntStateOf(0) }
    var catalogPageError by remember { mutableStateOf<String?>(null) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var spotifyPlaylistUrl by remember { mutableStateOf("") }
    var spotifyImportLoading by remember { mutableStateOf(false) }
    var spotifyImportMessage by remember { mutableStateOf<String?>(null) }
    var spotifyImportError by remember { mutableStateOf(false) }
    var playlistPendingDelete by remember { mutableStateOf<IosLibraryPlaylist?>(null) }
    var libraryMessage by remember { mutableStateOf<String?>(null) }
    var homeFeed by remember { mutableStateOf<IosMusicHomePage?>(null) }
    var unfilteredHomeFeed by remember { mutableStateOf<IosMusicHomePage?>(null) }
    var homeMood by remember { mutableStateOf("All") }
    var homeLoading by remember { mutableStateOf(true) }
    var homeError by remember { mutableStateOf<String?>(null) }
    var homeRequestSequence by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    suspend fun refreshYoutubeAccount() {
        if (!hasYoutubeCookie) return
        youtubeAccountLoading = true
        youtubeAccountMessage = null
        try {
            youtubeAccount = withTimeout(25_000) { IosMusicAccount.refresh() }
            youtubeAccountMessage = "YouTube Music account refreshed."
            youtubeAccountError = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            youtubeAccountMessage = failure.message ?: "Could not load the YouTube Music account. Check the saved cookie and try again."
            youtubeAccountError = true
        } finally {
            youtubeAccountLoading = false
        }
    }
    suspend fun refreshYoutubeLibrary() {
        val sequence = ++youtubeLibraryRequestSequence
        if (!hasYoutubeCookie) {
            youtubeAccountLibrary = null
            youtubeLibraryMessage = "Connect a YouTube Music account in Settings to load its saved library."
            youtubeLibraryError = false
            youtubeLibraryLoading = false
            return
        }
        val selectedChannelId = normalizeIosDataSyncId(accountDataSyncId)
        val cachedLibrary = IosMusicAccountLibraryCache.load(selectedChannelId)
        youtubeAccountLibrary = cachedLibrary
        youtubeLibraryLoading = true
        youtubeLibraryMessage = if (cachedLibrary == null) null else "Showing the last saved library while refreshing…"
        youtubeLibraryError = false
        try {
            val loaded = withTimeout(45_000) { IosMusicCatalog.accountLibrary() }
            if (sequence == youtubeLibraryRequestSequence) {
                youtubeAccountLibrary = loaded
                runCatching { IosMusicAccountLibraryCache.save(selectedChannelId, loaded) }
                youtubeLibraryMessage = "YouTube Music library refreshed."
                youtubeLibraryError = false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (sequence == youtubeLibraryRequestSequence) {
                youtubeLibraryMessage = if (cachedLibrary != null) {
                    "Could not refresh. Showing your last saved YouTube Music library."
                } else {
                    failure.message ?: "Could not load your YouTube Music library. Check your connection and try again."
                }
                youtubeLibraryError = true
            }
        } finally {
            if (sequence == youtubeLibraryRequestSequence) youtubeLibraryLoading = false
        }
    }
    LaunchedEffect(hasYoutubeCookie, accountDataSyncId) {
        if (hasYoutubeCookie) {
            refreshYoutubeAccount()
        } else {
            youtubeAccount = null
            youtubeAccountMessage = "Connect a YouTube Music account to load its profile and channels."
            youtubeAccountError = false
        }
    }
    LaunchedEffect(selectedTab, hasYoutubeCookie, accountDataSyncId) {
        if (selectedTab == "Library") {
            refreshYoutubeLibrary()
        } else {
            youtubeLibraryRequestSequence += 1
            youtubeLibraryLoading = false
        }
    }
    val runSearch = {
        val query = searchQuery
        scope.launch {
            isSearching = true
            searchError = null
            selectedCatalogPage = null
            catalogPageError = null
            try {
                val results = withTimeout(30_000) {
                    coroutineScope {
                        val songs = async { captureSearchResult { IosMusicSearch.search(query) } }
                        val videos = async { captureSearchResult { IosMusicSearch.searchVideos(query) } }
                        val artists = async { captureSearchResult { IosMusicSearch.searchArtists(query) } }
                        val albums = async { captureSearchResult { IosMusicSearch.searchAlbums(query) } }
                        val communityPlaylists = async { captureSearchResult { IosMusicSearch.searchCommunityPlaylists(query) } }
                        val featuredPlaylists = async { captureSearchResult { IosMusicSearch.searchFeaturedPlaylists(query) } }
                        IosMusicSearchOutcomes(
                            songs = songs.await(),
                            videos = videos.await(),
                            artists = artists.await(),
                            albums = albums.await(),
                            communityPlaylists = communityPlaylists.await(),
                            featuredPlaylists = featuredPlaylists.await(),
                        )
                    }
                }
                val outcomes = listOf(
                    results.songs.isFailure,
                    results.videos.isFailure,
                    results.artists.isFailure,
                    results.albums.isFailure,
                    results.communityPlaylists.isFailure,
                    results.featuredPlaylists.isFailure,
                )
                if (outcomes.all { it }) {
                    throw listOfNotNull(
                        results.songs.exceptionOrNull(),
                        results.videos.exceptionOrNull(),
                        results.artists.exceptionOrNull(),
                        results.albums.exceptionOrNull(),
                        results.communityPlaylists.exceptionOrNull(),
                        results.featuredPlaylists.exceptionOrNull(),
                    ).firstOrNull()
                        ?: IllegalStateException("YouTube Music search failed. Check your connection and try again.")
                }
                searchResults = results.songs.getOrDefault(emptyList())
                videoResults = results.videos.getOrDefault(emptyList())
                artistResults = results.artists.getOrDefault(emptyList())
                albumResults = results.albums.getOrDefault(emptyList())
                communityPlaylistResults = results.communityPlaylists.getOrDefault(emptyList())
                featuredPlaylistResults = results.featuredPlaylists.getOrDefault(emptyList())
                val partialFailures = outcomes.count { it }
                searchError = if (partialFailures > 0) "Some result categories could not be loaded. Try again if you do not see the item." else null
                hasSearched = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                searchResults = emptyList()
                videoResults = emptyList()
                artistResults = emptyList()
                albumResults = emptyList()
                communityPlaylistResults = emptyList()
                featuredPlaylistResults = emptyList()
                hasSearched = true
                searchError = failure.message ?: "YouTube Music search failed. Check your connection and try again."
            } finally {
                isSearching = false
            }
        }
    }
    val openArtist: (IosMusicArtistResult) -> Unit = { artist ->
        val sequence = ++catalogRequestSequence
        scope.launch {
            isLoadingCatalogPage = true
            catalogPageError = null
            selectedCatalogPage = null
            try {
                val page = withTimeout(30_000) { IosMusicCatalog.artist(artist.browseId) }
                if (sequence == catalogRequestSequence) selectedCatalogPage = IosMusicCatalogPage.Artist(page)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (sequence == catalogRequestSequence) catalogPageError = failure.message ?: "Could not load this artist. Check your connection and try again."
            } finally {
                if (sequence == catalogRequestSequence) isLoadingCatalogPage = false
            }
        }
    }
    val openAlbum: (IosMusicAlbumResult) -> Unit = { album ->
        val sequence = ++catalogRequestSequence
        scope.launch {
            isLoadingCatalogPage = true
            catalogPageError = null
            selectedCatalogPage = null
            try {
                val page = withTimeout(30_000) { IosMusicCatalog.album(album.browseId) }
                if (sequence == catalogRequestSequence) selectedCatalogPage = IosMusicCatalogPage.Album(page)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (sequence == catalogRequestSequence) catalogPageError = failure.message ?: "Could not load this album. Check your connection and try again."
            } finally {
                if (sequence == catalogRequestSequence) isLoadingCatalogPage = false
            }
        }
    }
    val openPlaylist: (IosMusicHomeItem.Playlist) -> Unit = { playlist ->
        val sequence = ++catalogRequestSequence
        selectedTab = "Search"
        scope.launch {
            isLoadingCatalogPage = true
            catalogPageError = null
            selectedCatalogPage = null
            try {
                val page = withTimeout(30_000) { IosMusicCatalog.playlist(playlist.browseId) }
                if (sequence == catalogRequestSequence) selectedCatalogPage = IosMusicCatalogPage.Playlist(page)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (sequence == catalogRequestSequence) catalogPageError = failure.message ?: "Could not load this playlist. Check your connection and try again."
            } finally {
                if (sequence == catalogRequestSequence) isLoadingCatalogPage = false
            }
        }
    }
    fun loadHome(chip: IosMusicHomeChip? = null, rollbackMood: String = homeMood) {
        val sequence = ++homeRequestSequence
        scope.launch {
            homeLoading = true
            homeError = null
            try {
                val loaded = withTimeout(30_000) {
                    IosMusicCatalog.home(chip?.browseId ?: "FEmusic_home", chip?.params)
                }
                if (sequence == homeRequestSequence) {
                    val next = loaded.copy(chips = homeFeed?.chips?.takeIf { it.isNotEmpty() } ?: loaded.chips)
                    homeFeed = next
                    if (chip == null) unfilteredHomeFeed = next
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (sequence == homeRequestSequence) {
                    homeMood = rollbackMood
                    homeError = failure.message ?: "Could not load your YouTube Music Home feed."
                }
            } finally {
                if (sequence == homeRequestSequence) homeLoading = false
            }
        }
    }
    fun chooseHomeChip(chip: IosMusicHomeChip?) {
        if (chip == null || homeMood == chip.title) {
            homeMood = "All"
            homeError = null
            unfilteredHomeFeed?.let { homeFeed = it; homeLoading = false } ?: loadHome()
        } else {
            val previousMood = homeMood
            homeMood = chip.title
            loadHome(chip, rollbackMood = previousMood)
        }
    }
    LaunchedEffect(Unit) { loadHome() }
    LaunchedEffect(magnetOpen) {
        if (magnetOpen) {
            magnetShowing = true
        } else {
            delay(360)
            magnetShowing = false
        }
    }
    LaunchedEffect(room.roomCode, room.currentTrack, room.playing, room.position, room.positionReceivedAtMillis) {
        if (room.roomCode == null) {
            IosMusicPlayback.clearRoomSync(stopRoomPlayback = true)
        } else {
            room.currentTrack?.let { track ->
                val position = advanceIosRoomPosition(room.position, room.playing, room.positionReceivedAtMillis, iosRoomElapsedMillis(), track.duration)
                IosMusicPlayback.syncRoomState(track, room.playing, position)
            } ?: IosMusicPlayback.clearRoomSync(stopRoomPlayback = true)
        }
    }
    val playbackAlbum = playback.videoId?.let { id ->
        playbackQueue.currentTrack?.takeIf { it.videoId == id }?.album
            ?: (library.importedTracks + library.downloadedTracks + library.likedSongs + library.playlists.flatMap { it.tracks })
                .firstOrNull { it.id == id }?.album
    }
    LaunchedEffect(playback.videoId, playback.title, playback.artist, playback.durationMillis, playbackAlbum) {
        val trackId = playback.videoId
        val title = playback.title
        val artist = playback.artist
        if (trackId != null && title != null && artist != null) {
            IosLyrics.load(trackId, title, artist, playback.durationMillis.takeIf { it > 0 }?.div(1_000L)?.toInt(), playbackAlbum)
        } else {
            IosLyrics.clear()
        }
    }
    LaunchedEffect(Unit) { IosLastFm.refreshState() }
    val listenBrainzAlbum = playbackAlbum
    val currentListenBrainzAlbum by rememberUpdatedState(listenBrainzAlbum)
    LaunchedEffect(Unit) {
        IosListenBrainz.refreshState()
        var previous: IosMusicPlaybackState? = null
        IosMusicPlayback.state.collect { current ->
            IosListenBrainz.onPlaybackStateChanged(previous, current, currentListenBrainzAlbum)
            previous = current
        }
    }
    LaunchedEffect(
        playback.videoId,
        playback.title,
        playback.artist,
        playback.durationMillis,
        playback.isPlaying,
        playbackQueue.currentTrack?.album,
        library.importedTracks,
        library.downloadedTracks,
        library.likedSongs,
        lastFm.connected,
        lastFm.scrobblingEnabled,
        lastFm.sendNowPlaying,
    ) {
        val trackId = playback.videoId
        val title = playback.title
        val artist = playback.artist
        if (trackId == null || title.isNullOrBlank() || artist.isNullOrBlank() || !playback.isPlaying || !lastFm.connected) return@LaunchedEffect
        val durationSeconds = (playback.durationMillis / 1_000L).takeIf { it > 0L }?.coerceAtMost(86_400L)?.toInt()
        val album = playbackQueue.currentTrack?.takeIf { it.videoId == trackId }?.album
            ?: (library.importedTracks + library.downloadedTracks + library.likedSongs + library.playlists.flatMap { it.tracks })
                .firstOrNull { it.id == trackId }?.album
        if (lastFm.sendNowPlaying) {
            runCatching { IosLastFm.updateNowPlaying(title, artist, album, durationSeconds) }
                .onFailure { failure -> if (failure is CancellationException) throw failure else IosLastFm.reportFailure(failure.message ?: "Last.fm now-playing update failed.") }
        }
        val durationMillis = playback.durationMillis
        val threshold = if (lastFm.scrobblingEnabled) lastFmScrobbleThresholdMillis(durationMillis) else null
        if (threshold == null || IosLastFm.hasScrobbled(trackId)) return@LaunchedEffect
        val startedAt = (IosLastFm.currentUnixTimeSeconds() - playback.positionMillis.coerceAtLeast(0L) / 1_000L).coerceAtLeast(0L)
        val playbackAtThreshold = IosMusicPlayback.state.first { state ->
            state.videoId != trackId || !state.isPlaying || state.positionMillis >= threshold
        }
        if (playbackAtThreshold.videoId == trackId && playbackAtThreshold.isPlaying && playbackAtThreshold.positionMillis >= threshold) {
            runCatching { IosLastFm.scrobble(trackId, title, artist, album, durationSeconds, startedAt) }
                .onFailure { failure -> if (failure is CancellationException) throw failure else IosLastFm.reportFailure(failure.message ?: "Last.fm scrobble failed.") }
        }
    }
    val palette = xanPalette(selectedPalette)
    val appFontFamily = IosTypeface.appFontFamily(selectedFont)
    val brandFontFamily = IosTypeface.brandFontFamily(brandFontEnabled, appFontFamily)
    val magnetRotation by animateFloatAsState(
        targetValue = if (magnetOpen) 45f else 0f,
        animationSpec = if (reducedMotion) snap() else spring(),
        label = "iosNavigationMagnetRotation",
    )
    val colorScheme = if (palette.isLight) {
        lightColorScheme(
            primary = palette.accent,
            background = palette.background,
            surface = palette.panel,
            onPrimary = Color.White,
            onBackground = palette.text,
            onSurface = palette.text,
        )
    } else {
        darkColorScheme(
            primary = palette.accent,
            background = palette.background,
            surface = palette.panel,
            onPrimary = Color.Black,
            onBackground = palette.text,
            onSurface = palette.text,
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = IosTypeface.typography(androidx.compose.material3.Typography(), appFontFamily),
    ) {
        Surface(Modifier.fillMaxSize(), color = palette.background) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(Modifier.fillMaxWidth().widthIn(max = 760.dp).align(Alignment.TopCenter).verticalScroll(rememberScrollState()).padding(horizontal = if (maxWidth >= 600.dp) 32.dp else 20.dp).padding(top = 22.dp, bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(painterResource(appLogo.drawable), contentDescription = "XAN ${appLogo.label} logo", modifier = Modifier.width(42.dp).height(42.dp))
                    Column {
                        Text("XAN", color = palette.text, fontWeight = FontWeight.Bold, fontSize = 24.sp, fontFamily = brandFontFamily)
                        Text("Listen together across your devices.", color = palette.muted, fontSize = 13.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    if (!playback.title.isNullOrBlank() && playback.videoId != null) {
                        TextButton(onClick = { selectedTab = "Lyrics" }) { Text("Lyrics") }
                    }
                    Text(selectedTab, color = palette.accent, fontSize = 14.sp)
                }
                if (selectedTab == "Search") {
                    Column(Modifier.fillMaxWidth().background(palette.panel, RoundedCornerShape(22.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Search YouTube Music", color = palette.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        Text("Find songs, artists, albums, and playlists.", color = palette.muted, fontSize = 14.sp)
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Song, artist, album, or playlist") },
                            singleLine = true,
                        )
                        Button(
                            onClick = { runSearch() },
                            enabled = searchQuery.isNotBlank() && !isSearching,
                            colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = if (palette.isLight) Color.White else Color.Black),
                        ) { Text(if (isSearching) "Searching…" else "Search") }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IosSearchCategories.forEach { category ->
                                FilterChip(
                                    selected = searchCategory == category,
                                    onClick = { searchCategory = category },
                                    label = { Text(category) },
                                )
                            }
                        }
                        if (selectedCatalogPage == null && catalogPageError != null && catalogReturnTab != "Search") {
                            OutlinedButton(onClick = { catalogPageError = null; selectedTab = catalogReturnTab }) {
                                Text("Back to ${catalogReturnTab.lowercase()}")
                            }
                        }
                        playback.message?.let { message ->
                            Text(message, color = if (playback.error) Color(0xFFFF8A80) else palette.accent, fontSize = 13.sp)
                        }
                        download.message?.let { message ->
                            Text(message, color = if (download.error) Color(0xFFFF8A80) else palette.accent, fontSize = 13.sp)
                        }
                        searchError?.let { Text(it, color = Color(0xFFFF8A80)) }
                        catalogPageError?.let { Text(it, color = Color(0xFFFF8A80)) }
                        if (selectedCatalogPage == null) {
                            val visibleSongs = searchCategory == "All" || searchCategory == "Songs"
                            val visibleVideos = searchCategory == "All" || searchCategory == "Videos"
                            val visibleArtists = searchCategory == "All" || searchCategory == "Artists"
                            val visibleAlbums = searchCategory == "All" || searchCategory == "Albums"
                            val visibleCommunityPlaylists = searchCategory == "All" || searchCategory == "Community playlists"
                            val visibleFeaturedPlaylists = searchCategory == "All" || searchCategory == "Featured playlists"
                            val hasVisibleResults = (visibleSongs && searchResults.isNotEmpty()) ||
                                (visibleVideos && videoResults.isNotEmpty()) ||
                                (visibleArtists && artistResults.isNotEmpty()) ||
                                (visibleAlbums && albumResults.isNotEmpty()) ||
                                (visibleCommunityPlaylists && communityPlaylistResults.isNotEmpty()) ||
                                (visibleFeaturedPlaylists && featuredPlaylistResults.isNotEmpty())
                            if (hasSearched && !isSearching && !hasVisibleResults && searchError == null) {
                                Text("No results found. Try another search.", color = palette.muted)
                            }
                            if (visibleSongs) searchResults.forEach { track ->
                            val libraryTrack = track.toLibraryTrack()
                            val isLiked = library.likedSongs.any { it.id == track.videoId }
                            val isDownloaded = library.downloadedTracks.any { it.id == track.videoId }
                            val isDownloadQueued = track.videoId in download.queuedTrackIds
                            val isDownloading = download.isDownloading && download.trackId == track.videoId
                            var playlistMenuExpanded by remember(track.videoId) { mutableStateOf(false) }
                            Column(Modifier.fillMaxWidth().background(palette.background, RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CatalogArtwork(track.artworkUrl, track.title, Modifier.size(52.dp), palette)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(track.title, color = palette.text, fontWeight = FontWeight.Medium)
                                        Text(
                                            listOfNotNull(track.artist, track.album, track.durationSeconds?.let { duration -> "${duration / 60}:${(duration % 60).toString().padStart(2, '0')}" })
                                                .filter(String::isNotBlank).joinToString(" · "),
                                            color = palette.muted,
                                            fontSize = 13.sp,
                                        )
                                    }
                                }
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            runCatching { IosMusicLibrary.toggleLiked(libraryTrack) }
                                                .onSuccess { saved -> libraryMessage = if (saved) "Added to Liked Songs." else "Removed from Liked Songs." }
                                                .onFailure { failure -> libraryMessage = failure.message ?: "Could not update Liked Songs." }
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.text),
                                    ) { Text(if (isLiked) "Saved" else "Like") }
                                    OutlinedButton(
                                        onClick = {
                                            if (library.playlists.isEmpty()) {
                                                libraryMessage = "Create a playlist in Library first."
                                            } else {
                                                playlistMenuExpanded = true
                                            }
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.text),
                                    ) { Text("Playlist") }
                                    OutlinedButton(
                                        onClick = {
                                            IosMusicPlayback.enqueueNext(track)
                                            selectedTab = "Queue"
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.text),
                                    ) { Text("Play next") }
                                    OutlinedButton(
                                        onClick = { IosMusicPlayback.download(track) },
                                        enabled = !isDownloaded && !isDownloadQueued && !isDownloading,
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.text),
                                    ) {
                                        Text(when {
                                            isDownloaded -> "Downloaded"
                                            isDownloading -> "Downloading…"
                                            isDownloadQueued -> "Queued"
                                            else -> "Download"
                                        })
                                    }
                                    DropdownMenu(
                                        expanded = playlistMenuExpanded,
                                        onDismissRequest = { playlistMenuExpanded = false },
                                    ) {
                                        library.playlists.forEach { playlist ->
                                            DropdownMenuItem(
                                                text = { Text(playlist.name) },
                                                onClick = {
                                                    runCatching { IosMusicLibrary.addToPlaylist(playlist.id, libraryTrack) }
                                                        .onSuccess { added -> libraryMessage = if (added) "Added to ${playlist.name}." else "Already in ${playlist.name}." }
                                                        .onFailure { failure -> libraryMessage = failure.message ?: "Could not update the playlist." }
                                                    playlistMenuExpanded = false
                                                },
                                            )
                                        }
                                    }
                                    Button(
                                        onClick = {
                                            if (room.roomCode != null) {
                                                IosListenTogether.sendPlayback(PlaybackActions.CHANGE_TRACK, track.toRoomTrackInfo())
                                            } else {
                                                IosMusicPlayback.play(track.videoId, track.title, track.artist, track.artworkUrl)
                                            }
                                        },
                                        enabled = canControlRoom && (playback.videoId != track.videoId || !playback.isResolving),
                                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = if (palette.isLight) Color.White else Color.Black),
                                    ) {
                                        Text(when {
                                            room.roomCode != null -> "Play for room"
                                            playback.videoId == track.videoId && playback.isResolving -> "Preparing…"
                                            else -> "Play in XAN"
                                        })
                                    }
                                }
                            }
                            }
                            if (visibleVideos && videoResults.isNotEmpty()) {
                                Text("Videos", color = palette.accent, fontWeight = FontWeight.Medium)
                                videoResults.forEach { track ->
                                    CatalogTrackRow(
                                        track, palette, canControlRoom, library, download,
                                        onQueueNext = { IosMusicPlayback.enqueueNext(track); selectedTab = "Queue" },
                                    ) {
                                        if (room.roomCode != null) IosListenTogether.sendPlayback(PlaybackActions.CHANGE_TRACK, track.toRoomTrackInfo())
                                        else IosMusicPlayback.play(track.videoId, track.title, track.artist, track.artworkUrl)
                                    }
                                }
                            }
                            if (visibleArtists && artistResults.isNotEmpty()) {
                                Text("Artists", color = palette.accent, fontWeight = FontWeight.Medium)
                                artistResults.forEach { artist ->
                                    Row(
                                        Modifier.fillMaxWidth().background(palette.background, RoundedCornerShape(14.dp)).padding(12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CatalogArtwork(artist.artworkUrl, artist.name, Modifier.size(48.dp), palette, CircleShape)
                                        Column(Modifier.weight(1f)) {
                                            Text(artist.name, color = palette.text, fontWeight = FontWeight.Medium, fontFamily = brandFontFamily)
                                            artist.subtitle?.let { Text(it, color = palette.muted, fontSize = 12.sp) }
                                        }
                                        OutlinedButton(onClick = { catalogReturnTab = "Search"; openArtist(artist) }, enabled = !isLoadingCatalogPage) {
                                            Text(if (isLoadingCatalogPage) "Loading…" else "Open")
                                        }
                                    }
                                }
                            }
                            if (visibleAlbums && albumResults.isNotEmpty()) {
                                Text("Albums", color = palette.accent, fontWeight = FontWeight.Medium)
                                albumResults.forEach { album ->
                                    Row(
                                        Modifier.fillMaxWidth().background(palette.background, RoundedCornerShape(14.dp)).padding(12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CatalogArtwork(album.artworkUrl, album.name, Modifier.size(52.dp), palette)
                                        Column(Modifier.weight(1f)) {
                                            Text(album.name, color = palette.text, fontWeight = FontWeight.Medium)
                                            Text(listOfNotNull(album.artist, album.year?.toString()).joinToString(" · "), color = palette.muted, fontSize = 12.sp)
                                        }
                                        OutlinedButton(onClick = { catalogReturnTab = "Search"; openAlbum(album) }, enabled = !isLoadingCatalogPage) {
                                            Text(if (isLoadingCatalogPage) "Loading…" else "Open")
                                        }
                                    }
                                }
                            }
                            if (visibleCommunityPlaylists && communityPlaylistResults.isNotEmpty()) {
                                Text("Community playlists", color = palette.accent, fontWeight = FontWeight.Medium)
                                communityPlaylistResults.forEach { playlist ->
                                    YoutubeLibraryItemRow(playlist.title, playlist.subtitle ?: "Community playlist", palette, !isLoadingCatalogPage, playlist.artworkUrl) {
                                        catalogReturnTab = "Search"
                                        openPlaylist(playlist)
                                    }
                                }
                            }
                            if (visibleFeaturedPlaylists && featuredPlaylistResults.isNotEmpty()) {
                                Text("Featured playlists", color = palette.accent, fontWeight = FontWeight.Medium)
                                featuredPlaylistResults.forEach { playlist ->
                                    YoutubeLibraryItemRow(playlist.title, playlist.subtitle ?: "Featured playlist", palette, !isLoadingCatalogPage, playlist.artworkUrl) {
                                        catalogReturnTab = "Search"
                                        openPlaylist(playlist)
                                    }
                                }
                            }
                        } else {
                            OutlinedButton(onClick = {
                                selectedCatalogPage = null
                                catalogPageError = null
                                selectedTab = catalogReturnTab
                            }) { Text(if (catalogReturnTab == "Search") "Back to results" else "Back to ${catalogReturnTab.lowercase()}") }
                            when (val page = selectedCatalogPage) {
                                is IosMusicCatalogPage.Artist -> {
                                    Text(page.page.name, color = palette.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, fontFamily = brandFontFamily)
                                    page.page.description?.let { Text(it, color = palette.muted, fontSize = 14.sp) }
                                    listOfNotNull(page.page.subscriberCount, page.page.monthlyListeners).takeIf { it.isNotEmpty() }
                                        ?.let { Text(it.joinToString(" · "), color = palette.muted, fontSize = 12.sp) }
                                    Text("Songs", color = palette.accent, fontWeight = FontWeight.Medium)
                                    if (page.page.songs.isEmpty()) Text("No songs were listed for this artist.", color = palette.muted)
                                    OutlinedButton(
                                        onClick = { IosMusicPlayback.playQueue(page.page.songs); selectedTab = "Queue" },
                                        enabled = page.page.songs.isNotEmpty() && canControlRoom,
                                    ) { Text("Play artist songs") }
                                    OutlinedButton(
                                        onClick = { IosMusicPlayback.downloadAll(page.page.songs) },
                                        enabled = page.page.songs.isNotEmpty(),
                                    ) { Text("Download artist songs") }
                                    page.page.songs.forEach { track ->
                                        CatalogTrackRow(
                                            track, palette, canControlRoom, library, download,
                                            onQueueNext = { IosMusicPlayback.enqueueNext(track); selectedTab = "Queue" },
                                        ) {
                                            if (room.roomCode != null) IosListenTogether.sendPlayback(PlaybackActions.CHANGE_TRACK, track.toRoomTrackInfo())
                                            else IosMusicPlayback.play(track.videoId, track.title, track.artist, track.artworkUrl)
                                        }
                                    }
                                }
                                is IosMusicCatalogPage.Album -> {
                                    Text(page.page.name, color = palette.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                                    Text(listOfNotNull(page.page.artist, page.page.year).joinToString(" · "), color = palette.muted, fontSize = 14.sp)
                                    page.page.description?.let { Text(it, color = palette.muted, fontSize = 14.sp) }
                                    Text("Tracks", color = palette.accent, fontWeight = FontWeight.Medium)
                                    if (page.page.songs.isEmpty()) Text("No tracks were listed for this album.", color = palette.muted)
                                    OutlinedButton(
                                        onClick = { IosMusicPlayback.playQueue(page.page.songs); selectedTab = "Queue" },
                                        enabled = page.page.songs.isNotEmpty() && canControlRoom,
                                    ) { Text("Play album") }
                                    OutlinedButton(
                                        onClick = { IosMusicPlayback.downloadAll(page.page.songs) },
                                        enabled = page.page.songs.isNotEmpty(),
                                    ) { Text("Download album") }
                                    page.page.songs.forEach { track ->
                                        CatalogTrackRow(
                                            track, palette, canControlRoom, library, download,
                                            onQueueNext = { IosMusicPlayback.enqueueNext(track); selectedTab = "Queue" },
                                        ) {
                                            if (room.roomCode != null) IosListenTogether.sendPlayback(PlaybackActions.CHANGE_TRACK, track.toRoomTrackInfo())
                                            else IosMusicPlayback.play(track.videoId, track.title, track.artist, track.artworkUrl)
                                        }
                                    }
                                }
                                is IosMusicCatalogPage.Playlist -> {
                                    Text(page.page.name, color = palette.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                                    page.page.description?.let { Text(it, color = palette.muted, fontSize = 14.sp) }
                                    Text("Tracks", color = palette.accent, fontWeight = FontWeight.Medium)
                                    if (page.page.songs.isEmpty()) Text("No tracks were listed for this playlist.", color = palette.muted)
                                    OutlinedButton(
                                        onClick = { IosMusicPlayback.playQueue(page.page.songs); selectedTab = "Queue" },
                                        enabled = page.page.songs.isNotEmpty() && canControlRoom,
                                    ) { Text("Play playlist") }
                                    OutlinedButton(
                                        onClick = { IosMusicPlayback.downloadAll(page.page.songs) },
                                        enabled = page.page.songs.isNotEmpty(),
                                    ) { Text("Download playlist") }
                                    page.page.songs.forEach { track ->
                                        CatalogTrackRow(
                                            track, palette, canControlRoom, library, download,
                                            onQueueNext = { IosMusicPlayback.enqueueNext(track); selectedTab = "Queue" },
                                        ) {
                                            if (room.roomCode != null) IosListenTogether.sendPlayback(PlaybackActions.CHANGE_TRACK, track.toRoomTrackInfo())
                                            else IosMusicPlayback.play(track.videoId, track.title, track.artist, track.artworkUrl)
                                        }
                                    }
                                }
                                null -> Unit
                            }
                        }
                        libraryMessage?.let { Text(it, color = palette.muted, fontSize = 13.sp) }
                    }
                } else if (selectedTab == "Library") {
                    Column(Modifier.fillMaxWidth().background(palette.panel, RoundedCornerShape(22.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Your Library", color = palette.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        Text("Your saved YouTube Music collections and on-device playlists.", color = palette.muted, fontSize = 13.sp)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("YouTube Music", color = palette.accent, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            OutlinedButton(
                                onClick = { scope.launch { refreshYoutubeLibrary() } },
                                enabled = hasYoutubeCookie && !youtubeLibraryLoading,
                            ) { Text(if (youtubeLibraryLoading) "Refreshing…" else "Refresh") }
                        }
                        youtubeLibraryMessage?.let {
                            Text(it, color = if (youtubeLibraryError) Color(0xFFFF8A80) else palette.muted, fontSize = 13.sp)
                        }
                        if (!hasYoutubeCookie) {
                            OutlinedButton(onClick = { selectedTab = "Settings" }) { Text("Connect account in Settings") }
                        } else if (youtubeLibraryLoading && youtubeAccountLibrary == null) {
                            Text("Loading saved playlists, albums, and artists…", color = palette.muted, fontSize = 13.sp)
                        }
                        youtubeAccountLibrary?.let { accountLibrary ->
                            if (accountLibrary.playlists.isEmpty() && accountLibrary.albums.isEmpty() && accountLibrary.artists.isEmpty()) {
                                Text("No saved playlists, albums, or artists were found.", color = palette.muted, fontSize = 13.sp)
                            }
                            if (accountLibrary.playlists.isNotEmpty()) {
                                Text("Saved playlists", color = palette.text, fontWeight = FontWeight.Medium)
                                accountLibrary.playlists.forEach { playlist ->
                                    YoutubeLibraryItemRow(playlist.title, playlist.subtitle ?: "YouTube Music playlist", palette, !isLoadingCatalogPage, playlist.artworkUrl) {
                                        catalogReturnTab = "Library"
                                        openPlaylist(playlist)
                                    }
                                }
                            }
                            if (accountLibrary.albums.isNotEmpty()) {
                                Text("Saved albums", color = palette.text, fontWeight = FontWeight.Medium)
                                accountLibrary.albums.forEach { album ->
                                    YoutubeLibraryItemRow(
                                        album.name,
                                        listOfNotNull(album.artist, album.year?.toString()).joinToString(" · ").ifBlank { "YouTube Music album" },
                                        palette,
                                        !isLoadingCatalogPage,
                                        album.artworkUrl,
                                    ) {
                                        catalogReturnTab = "Library"
                                        selectedTab = "Search"
                                        openAlbum(album)
                                    }
                                }
                            }
                            if (accountLibrary.artists.isNotEmpty()) {
                                Text("Followed artists", color = palette.text, fontWeight = FontWeight.Medium)
                                accountLibrary.artists.forEach { artist ->
                                    YoutubeLibraryItemRow(artist.name, artist.subtitle ?: "YouTube Music artist", palette, !isLoadingCatalogPage, artist.artworkUrl, CircleShape) {
                                        catalogReturnTab = "Library"
                                        selectedTab = "Search"
                                        openArtist(artist)
                                    }
                                }
                            }
                        }
                        Text("On this device", color = palette.accent, fontWeight = FontWeight.Medium)
                        Text("Imported audio", color = palette.text, fontWeight = FontWeight.Medium)
                        if (library.importedTracks.isEmpty()) {
                            Text("Choose files from the player bar to add local audio to your library.", color = palette.muted, fontSize = 13.sp)
                        }
                        library.importedTracks.forEach { track ->
                            LibraryTrackRow(
                                track = track,
                                palette = palette,
                                playEnabled = room.roomCode == null && track.localFilePath != null,
                                onPlay = {
                                    runCatching { IosMusicPlayback.playImportedAudio(track) }
                                        .onFailure { failure -> libraryMessage = failure.message ?: "Could not play the imported audio." }
                                },
                                onRemove = {
                                    runCatching { IosMusicPlayback.removeImportedTrack(track.id) }
                                        .onSuccess { removed -> if (removed) libraryMessage = "Removed ${track.title} from this device." }
                                        .onFailure { failure -> libraryMessage = failure.message ?: "Could not remove the imported audio." }
                                },
                            )
                        }
                        Text("Downloads", color = palette.text, fontWeight = FontWeight.Medium)
                        if (library.downloadedTracks.isEmpty()) {
                            Text("Tracks you download from Search will be available offline here.", color = palette.muted, fontSize = 13.sp)
                        }
                        library.downloadedTracks.forEach { track ->
                            LibraryTrackRow(
                                track = track,
                                palette = palette,
                                playEnabled = room.roomCode == null && track.localFilePath != null,
                                onPlay = {
                                    runCatching { IosMusicPlayback.playDownloaded(track) }
                                        .onFailure { failure -> libraryMessage = failure.message ?: "Could not play the downloaded track." }
                                },
                                onRemove = {
                                    runCatching { IosMusicPlayback.removeDownload(track.id) }
                                        .onSuccess { removed -> if (removed) libraryMessage = "Removed ${track.title} from Downloads." }
                                        .onFailure { failure -> libraryMessage = failure.message ?: "Could not remove the downloaded track." }
                                },
                            )
                        }
                        Text("Playlists", color = palette.accent, fontWeight = FontWeight.Medium)
                        Text("Import a Spotify public playlist", color = palette.text, fontWeight = FontWeight.Medium)
                        Text(
                            "XAN matches the public Spotify preview to YouTube Music. Spotify may expose only the first 50 tracks without account access.",
                            color = palette.muted,
                            fontSize = 12.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = spotifyPlaylistUrl,
                                onValueChange = { spotifyPlaylistUrl = it.take(2_048) },
                                modifier = Modifier.weight(1f),
                                label = { Text("Spotify playlist link") },
                                singleLine = true,
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        spotifyImportLoading = true
                                        spotifyImportMessage = null
                                        spotifyImportError = false
                                        try {
                                            val report = withTimeout(180_000L) { IosSpotifyPlaylistImporter.import(spotifyPlaylistUrl) }
                                            spotifyImportMessage = report.summary()
                                            spotifyPlaylistUrl = ""
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (failure: Throwable) {
                                            spotifyImportMessage = failure.message ?: "Could not import this Spotify playlist."
                                            spotifyImportError = true
                                        } finally {
                                            spotifyImportLoading = false
                                        }
                                    }
                                },
                                enabled = spotifyPlaylistUrl.isNotBlank() && !spotifyImportLoading,
                                colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = if (palette.isLight) Color.White else Color.Black),
                            ) { Text(if (spotifyImportLoading) "Matching…" else "Import") }
                        }
                        spotifyImportMessage?.let {
                            Text(it, color = if (spotifyImportError) Color(0xFFFF8A80) else palette.muted, fontSize = 13.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newPlaylistName,
                                onValueChange = { newPlaylistName = it.take(80) },
                                modifier = Modifier.weight(1f),
                                label = { Text("New playlist") },
                                singleLine = true,
                            )
                            Button(
                                onClick = {
                                    runCatching { IosMusicLibrary.createPlaylist(newPlaylistName) }
                                        .onSuccess { playlist ->
                                            newPlaylistName = ""
                                            libraryMessage = "Created ${playlist.name}."
                                        }
                                        .onFailure { failure -> libraryMessage = failure.message ?: "Could not create the playlist." }
                                },
                                enabled = newPlaylistName.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = if (palette.isLight) Color.White else Color.Black),
                            ) { Text("Create") }
                        }
                        libraryMessage?.let { Text(it, color = palette.muted, fontSize = 13.sp) }
                        if (library.playlists.isEmpty()) Text("Your playlists will appear here.", color = palette.muted)
                        library.playlists.forEach { playlist ->
                            var editedName by remember(playlist.id, playlist.name) { mutableStateOf(playlist.name) }
                            Column(Modifier.fillMaxWidth().background(palette.background, RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = editedName,
                                        onValueChange = { editedName = it.take(80) },
                                        modifier = Modifier.weight(1f),
                                        label = { Text("Playlist name") },
                                        singleLine = true,
                                    )
                                    Button(onClick = {
                                        runCatching { IosMusicLibrary.renamePlaylist(playlist.id, editedName) }
                                            .onSuccess { libraryMessage = "Playlist renamed." }
                                            .onFailure { failure -> libraryMessage = failure.message ?: "Could not rename the playlist." }
                                    }) { Text("Save") }
                                    OutlinedButton(onClick = { playlistPendingDelete = playlist }) { Text("Delete") }
                                }
                                if (playlist.tracks.isEmpty()) Text("No songs yet. Add songs from Search.", color = palette.muted, fontSize = 13.sp)
                                playlist.tracks.forEach { track ->
                                    LibraryTrackRow(
                                        track = track,
                                        palette = palette,
                                        playEnabled = canControlRoom,
                                        onPlay = {
                                            IosMusicPlayback.playQueue(playlist.tracks.map(IosLibraryTrack::toSearchTrack), playlist.tracks.indexOf(track))
                                        },
                                        onRemove = {
                                            runCatching { IosMusicLibrary.removeFromPlaylist(playlist.id, track.id) }
                                                .onSuccess { libraryMessage = "Removed from ${playlist.name}." }
                                                .onFailure { failure -> libraryMessage = failure.message ?: "Could not remove the song." }
                                        },
                                    )
                                }
                            }
                        }
                        Text("Liked Songs", color = palette.accent, fontWeight = FontWeight.Medium)
                        if (library.likedSongs.isEmpty()) Text("Like a song in Search and it will be saved here.", color = palette.muted)
                        library.likedSongs.forEach { track ->
                            LibraryTrackRow(
                                track = track,
                                palette = palette,
                                playEnabled = canControlRoom,
                                onPlay = {
                                    IosMusicPlayback.playQueue(library.likedSongs.map(IosLibraryTrack::toSearchTrack), library.likedSongs.indexOf(track))
                                },
                                onRemove = {
                                    runCatching { IosMusicLibrary.toggleLiked(track) }
                                        .onSuccess { libraryMessage = "Removed from Liked Songs." }
                                        .onFailure { failure -> libraryMessage = failure.message ?: "Could not update Liked Songs." }
                                },
                            )
                        }
                    }
                } else if (selectedTab == "Home") {
                    Column(Modifier.fillMaxWidth().background(palette.panel, RoundedCornerShape(22.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Listen Now", color = palette.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            playback.title?.let { "Now playing · $it" } ?: "Find music and build your library.",
                            color = palette.muted,
                            fontSize = 14.sp,
                        )
                        playback.message?.let { Text(it, color = if (playback.error) Color(0xFFFF8A80) else palette.accent, fontSize = 13.sp) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { selectedTab = "Search" },
                                colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = if (palette.isLight) Color.White else Color.Black),
                            ) { Text("Search music") }
                            OutlinedButton(onClick = { selectedTab = "Listen Together" }) { Text("Listen Together") }
                        }
                        libraryMessage?.let { Text(it, color = palette.muted, fontSize = 13.sp) }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = homeMood == "All", onClick = { chooseHomeChip(null) }, label = { Text("All") })
                        homeFeed?.chips.orEmpty().forEach { chip ->
                            FilterChip(
                                selected = homeMood == chip.title,
                                onClick = { chooseHomeChip(chip) },
                                label = { Text(chip.title) },
                            )
                        }
                    }
                    if (homeLoading) {
                        Text(if (homeMood == "All") "Loading your music…" else "Loading ${homeMood}…", color = palette.muted, fontSize = 13.sp)
                    }
                    homeError?.let { error ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(error, modifier = Modifier.weight(1f), color = Color(0xFFFF8A80), fontSize = 13.sp)
                            OutlinedButton(onClick = { loadHome(homeFeed?.chips?.firstOrNull { it.title == homeMood }) }) { Text("Retry") }
                        }
                    }
                    homeFeed?.sections.orEmpty().forEach { section ->
                        Column(Modifier.fillMaxWidth().background(palette.panel, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text(section.title, color = palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            section.items.forEach { item ->
                                when (item) {
                                    is IosMusicHomeItem.Song -> CatalogTrackRow(
                                        item.track, palette, canControlRoom, library, download,
                                        onQueueNext = { IosMusicPlayback.enqueueNext(item.track); selectedTab = "Queue" },
                                    ) {
                                        if (room.roomCode != null) IosListenTogether.sendPlayback(PlaybackActions.CHANGE_TRACK, item.track.toRoomTrackInfo())
                                        else IosMusicPlayback.play(item.track.videoId, item.track.title, item.track.artist, item.track.artworkUrl)
                                    }
                                    is IosMusicHomeItem.Artist -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        CatalogArtwork(item.artist.artworkUrl, item.artist.name, Modifier.size(48.dp), palette, CircleShape)
                                        Column(Modifier.weight(1f)) {
                                            Text(item.artist.name, color = palette.text, fontWeight = FontWeight.Medium, fontFamily = brandFontFamily)
                                            item.artist.subtitle?.let { Text(it, color = palette.muted, fontSize = 12.sp) }
                                        }
                                        OutlinedButton(onClick = { catalogReturnTab = "Home"; selectedTab = "Search"; openArtist(item.artist) }, enabled = !isLoadingCatalogPage) { Text("Open artist") }
                                    }
                                    is IosMusicHomeItem.Album -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        CatalogArtwork(item.album.artworkUrl, item.album.name, Modifier.size(48.dp), palette)
                                        Column(Modifier.weight(1f)) {
                                            Text(item.album.name, color = palette.text, fontWeight = FontWeight.Medium)
                                            Text(listOfNotNull(item.album.artist, item.album.year?.toString()).joinToString(" · "), color = palette.muted, fontSize = 12.sp)
                                        }
                                        OutlinedButton(onClick = { catalogReturnTab = "Home"; selectedTab = "Search"; openAlbum(item.album) }, enabled = !isLoadingCatalogPage) { Text("Open album") }
                                    }
                                    is IosMusicHomeItem.Playlist -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        CatalogArtwork(item.artworkUrl, item.title, Modifier.size(48.dp), palette)
                                        Column(Modifier.weight(1f)) {
                                            Text(item.title, color = palette.text, fontWeight = FontWeight.Medium)
                                            item.subtitle?.let { Text(it, color = palette.muted, fontSize = 12.sp) }
                                        }
                                        OutlinedButton(onClick = { catalogReturnTab = "Home"; openPlaylist(item) }, enabled = !isLoadingCatalogPage) { Text("Open playlist") }
                                    }
                                }
                            }
                        }
                    }
                    if (homeFeed == null && !homeLoading && homeError == null) {
                        Text("No Home shelves were returned. Search YouTube Music to find something to play.", color = palette.muted, fontSize = 13.sp)
                    }
                    Column(Modifier.fillMaxWidth().background(palette.panel, RoundedCornerShape(22.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Liked Songs", color = palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        if (library.likedSongs.isEmpty()) Text("Songs you like will appear here.", color = palette.muted)
                        library.likedSongs.take(5).forEach { track ->
                            LibraryTrackRow(
                                track = track,
                                palette = palette,
                                playEnabled = canControlRoom,
                                onPlay = {
                                    val visibleLikedSongs = library.likedSongs.take(5)
                                    IosMusicPlayback.playQueue(visibleLikedSongs.map(IosLibraryTrack::toSearchTrack), visibleLikedSongs.indexOf(track))
                                },
                                onRemove = {
                                    runCatching { IosMusicLibrary.toggleLiked(track) }
                                        .onSuccess { libraryMessage = "Removed from Liked Songs." }
                                        .onFailure { failure -> libraryMessage = failure.message ?: "Could not update Liked Songs." }
                                },
                            )
                        }
                    }
                } else if (selectedTab == "Listen Together") {
                    Column(Modifier.fillMaxWidth().background(palette.panel, RoundedCornerShape(22.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Listen Together", color = palette.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        Text("Join the same rooms as Android and XAN Desktop.", color = palette.muted, fontSize = 14.sp)
                        RoomServerProviderChooser(server, enabled = room.roomCode == null) { server = it; IosListenTogether.savePreferences(it, name) }
                        OutlinedTextField(server, { server = it; IosListenTogether.savePreferences(it, name) }, modifier = Modifier.fillMaxWidth(), label = { Text("Secure room server") }, singleLine = true)
                        OutlinedTextField(name, { name = it; IosListenTogether.savePreferences(server, it) }, modifier = Modifier.fillMaxWidth(), label = { Text("Display name") }, singleLine = true)
                        OutlinedTextField(roomCode, { roomCode = it.uppercase().take(12) }, modifier = Modifier.fillMaxWidth(), label = { Text("Room code to join") }, singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { IosListenTogether.createRoom(server, name) }, enabled = room.roomCode == null && room.status != "Connecting…", colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = if (palette.isLight) Color.White else Color.Black)) { Text("Create room") }
                            Button(onClick = { IosListenTogether.joinRoom(server, roomCode, name) }, enabled = room.roomCode == null && room.status != "Connecting…") { Text("Join room") }
                            if (room.roomCode != null) Button(onClick = { IosListenTogether.leave() }) { Text("Leave") }
                        }
                        Text("${room.status}${room.roomCode?.let { " · $it" } ?: ""}", color = palette.accent)
                        room.message?.let { Text(it, color = palette.muted) }
                        room.error?.let { Text(it, color = Color(0xFFFF8A80)) }
                        if (room.isHost && room.status == "In room") {
                            room.joinRequests.forEach { request ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("${request.username} wants to join", color = palette.text)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { IosListenTogether.approveJoin(request.userId) }) { Text("Accept") }
                                        OutlinedButton(onClick = { IosListenTogether.rejectJoin(request.userId) }) { Text("Decline") }
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Everyone can control playback", color = palette.text, modifier = Modifier.weight(1f))
                                Switch(checked = room.controlMode == ControlModes.EVERYONE, onCheckedChange = { IosListenTogether.setControlMode(if (it) ControlModes.EVERYONE else ControlModes.OWNER) })
                            }
                        }
                        room.currentTrack?.let { track ->
                            Text("${track.title} · ${track.artist}", color = palette.text, fontWeight = FontWeight.Medium)
                            playback.message?.takeIf { playback.videoId == track.id }?.let { message ->
                                Text(message, color = if (playback.error) Color(0xFFFF8A80) else palette.muted, fontSize = 13.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(enabled = room.isHost || room.controlMode == ControlModes.EVERYONE, onClick = { IosListenTogether.sendPlayback(PlaybackActions.SKIP_PREV) }) { Text("Previous") }
                                Button(enabled = room.isHost || room.controlMode == ControlModes.EVERYONE, onClick = { IosListenTogether.sendPlayback(if (room.playing) PlaybackActions.PAUSE else PlaybackActions.PLAY) }) { Text(if (room.playing) "Pause" else "Play") }
                                Button(enabled = room.isHost || room.controlMode == ControlModes.EVERYONE, onClick = { IosListenTogether.sendPlayback(PlaybackActions.SKIP_NEXT) }) { Text("Next") }
                            }
                        }
                        if (room.users.isNotEmpty()) Text("${room.users.size} listeners · ${room.users.joinToString { it.username }}", color = palette.muted)
                    }
                } else if (selectedTab == "Lyrics") {
                    val lyricDocument = lyrics.document
                    val romanization by IosLyricsRomanization.state.collectAsState()
                    val romanizedLines = remember(lyricDocument, romanization.enabled) {
                        lyricDocument?.lines?.map { IosLyricsRomanization.romanize(it.text) }.orEmpty()
                    }
                    val activeLine = lyricDocument?.let { activeIosLyricsLine(it.lines, playback.positionMillis) } ?: -1
                    val lyricListState = rememberLazyListState()
                    LaunchedEffect(lyrics.trackId, lyricDocument) {
                        val trackId = lyrics.trackId
                        val title = lyrics.title
                        val artist = lyrics.artist
                        val document = lyricDocument
                        if (trackId != null && title != null && artist != null && document != null) {
                            IosLyricsTranslation.restoreCached(trackId, title, artist, document.lines.map(IosLyricsLine::text))
                        }
                    }
                    LaunchedEffect(lyrics.trackId, activeLine) {
                        if (activeLine >= 0) lyricListState.animateScrollToItem((activeLine - 2).coerceAtLeast(0))
                    }
                    Column(Modifier.fillMaxWidth().background(palette.panel, RoundedCornerShape(22.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Lyrics", color = palette.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            listOfNotNull(lyrics.title, lyrics.artist).joinToString(" · ").ifBlank { "Play a YouTube Music track to find lyrics." },
                            color = palette.muted,
                            fontSize = 13.sp,
                        )
                        if (lyrics.isLoading) Text("Finding lyrics…", color = palette.accent)
                        lyrics.error?.let { lyricsError ->
                            Text(lyricsError, color = if (palette.isLight) Color(0xFFB3261E) else Color(0xFFFF8A80))
                            OutlinedButton(
                                enabled = lyrics.trackId != null && !lyrics.isLoading,
                                onClick = {
                                    val id = lyrics.trackId
                                    val title = lyrics.title
                                    val artist = lyrics.artist
                                    if (id != null && title != null && artist != null) {
                                        scope.launch { IosLyrics.load(id, title, artist, playback.durationMillis.takeIf { it > 0 }?.div(1_000L)?.toInt(), playbackAlbum) }
                                    }
                                },
                            ) { Text("Retry") }
                        }
                        if (lyricDocument != null) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (lyricDocument.isSynced) "Synced lyrics" else "Plain lyrics", color = palette.accent, fontSize = 12.sp)
                                Spacer(Modifier.weight(1f))
                                Text(lyricDocument.provider, color = palette.muted, fontSize = 12.sp)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = lyricTranslation.trackId != lyrics.trackId || !lyricTranslation.isLoading,
                                    onClick = {
                                        val id = lyrics.trackId
                                        val title = lyrics.title
                                        val artist = lyrics.artist
                                        if (id != null && title != null && artist != null) {
                                            scope.launch {
                                                IosLyricsTranslation.translate(id, title, artist, lyricDocument.lines.map(IosLyricsLine::text))
                                            }
                                        }
                                    },
                                ) { Text(if (lyricTranslation.trackId == lyrics.trackId && lyricTranslation.lines.isNotEmpty()) "Refresh translation" else "Translate") }
                                if (lyricTranslation.trackId == lyrics.trackId && lyricTranslation.lines.isNotEmpty()) {
                                    OutlinedButton(onClick = IosLyricsTranslation::clear) { Text("Hide translation") }
                                }
                            }
                            if (lyricTranslation.trackId == lyrics.trackId) {
                                if (lyricTranslation.isLoading) Text("Translating lyrics…", color = palette.accent)
                                lyricTranslation.error?.let { Text(it, color = if (palette.isLight) Color(0xFFB3261E) else Color(0xFFFF8A80), fontSize = 12.sp) }
                            }
                            LazyColumn(
                                state = lyricListState,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 520.dp),
                                contentPadding = PaddingValues(vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                itemsIndexed(lyricDocument.lines) { index, line ->
                                    val romanized = romanizedLines.getOrNull(index)
                                    val romanizedIsMain = romanization.useAsMain && romanized != null
                                    val displayedText = if (romanizedIsMain) romanized else line.text
                                    val activeWord = if (index == activeLine) activeIosLyricsWord(line.words, playback.positionMillis) else -1
                                    val lyricText = buildAnnotatedString {
                                        if (line.words.isEmpty() || romanizedIsMain) {
                                            append(displayedText)
                                        } else {
                                            line.words.forEachIndexed { wordIndex, word ->
                                                withStyle(
                                                    SpanStyle(
                                                        color = if (wordIndex == activeWord) palette.accent else palette.text,
                                                        fontWeight = if (wordIndex == activeWord) FontWeight.Bold else FontWeight.Normal,
                                                    ),
                                                ) { append(word.text) }
                                            }
                                        }
                                    }
                                    Text(
                                        lyricText,
                                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(
                                            if (index == activeLine) palette.accent.copy(alpha = 0.14f) else Color.Transparent,
                                        ).padding(horizontal = 12.dp, vertical = 7.dp),
                                        color = if (index == activeLine) palette.accent else palette.text,
                                        fontSize = if (index == activeLine) 18.sp else 16.sp,
                                        fontWeight = if (index == activeLine) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                    val secondaryText = when {
                                        romanized == null -> null
                                        romanizedIsMain -> line.text
                                        else -> romanized
                                    }
                                    secondaryText?.takeIf(String::isNotBlank)?.let { text ->
                                        Text(
                                            text,
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                            color = palette.muted,
                                            fontSize = 14.sp,
                                        )
                                    }
                                    lyricTranslation.lines.getOrNull(index)
                                        ?.takeIf { lyricTranslation.trackId == lyrics.trackId && it.isNotBlank() }
                                        ?.let { translatedLine ->
                                            Text(
                                                translatedLine,
                                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                                color = palette.muted,
                                                fontSize = 14.sp,
                                            )
                                        }
                                }
                            }
                            if (lyricDocument.provider == "Unison") {
                                Text("Lyrics from Unison (unison.boidu.dev)", color = palette.muted, fontSize = 11.sp)
                            }
                        } else if (!lyrics.isLoading && lyrics.error == null) {
                            Text("Lyrics will appear here when a matching version is available.", color = palette.muted)
                        }
                    }
                } else if (selectedTab == "Queue") {
                    Column(Modifier.fillMaxWidth().background(palette.panel, RoundedCornerShape(22.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Playback queue", color = palette.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        IosSleepTimerControls(sleepTimer, palette)
                        if (room.roomCode != null) {
                            Text("Shared with room ${room.roomCode}", color = palette.muted, fontSize = 13.sp)
                            room.currentTrack?.let { track ->
                                Text("Now playing", color = palette.accent, fontWeight = FontWeight.Medium)
                                Text("${track.title} · ${track.artist}", color = palette.text)
                            }
                            if (room.queue.isEmpty()) Text("There are no upcoming tracks in the room.", color = palette.muted)
                            room.queue.forEachIndexed { index, track ->
                                Row(Modifier.fillMaxWidth().background(palette.background, RoundedCornerShape(12.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${index + 1}. ${track.title}", color = palette.text, fontWeight = FontWeight.Medium)
                                        Text(track.artist, color = palette.muted, fontSize = 12.sp)
                                    }
                                    OutlinedButton(
                                        onClick = { IosMusicPlayback.playQueueItem(track.id) },
                                        enabled = canControlRoom,
                                    ) { Text("Play") }
                                    if (canControlRoom) OutlinedButton(onClick = { IosMusicPlayback.removeFromQueue(track.id) }) { Text("Remove") }
                                }
                            }
                            if (room.queue.isNotEmpty() && canControlRoom) {
                                OutlinedButton(onClick = { IosMusicPlayback.clearUpcomingQueue() }) { Text("Clear upcoming") }
                            }
                        } else {
                            playbackQueue.message?.let { Text(it, color = palette.accent, fontSize = 13.sp) }
                            IosPlaybackQueueModeControls(playbackQueue.shuffleEnabled, playbackQueue.repeatMode.label)
                            val current = playbackQueue.currentTrack
                            if (current != null) {
                                Text("Now playing", color = palette.accent, fontWeight = FontWeight.Medium)
                                Text("${current.title} · ${current.artist}", color = palette.text)
                            }
                            if (playbackQueue.tracks.isEmpty()) Text("Your queue is empty. Add songs with Play next or play an album or playlist.", color = palette.muted)
                            playbackQueue.tracks.forEachIndexed { index, track ->
                                IosPlaybackQueueTrackRow(
                                    track = track,
                                    orderPosition = playbackQueue.playOrder.indexOf(index),
                                    isCurrent = track.videoId == current?.videoId,
                                    isUpcoming = playbackQueue.playOrder.indexOf(index) > playbackQueue.currentIndex,
                                    palette = palette,
                                    onPlay = { IosMusicPlayback.playQueueItem(track.videoId) },
                                    onRemove = { IosMusicPlayback.removeFromQueue(track.videoId) },
                                )
                            }
                            if (playbackQueue.upcomingCount > 0) {
                                OutlinedButton(onClick = { IosMusicPlayback.clearUpcomingQueue() }) { Text("Clear upcoming") }
                            }
                        }
                    }
                } else if (selectedTab == "Settings") {
                    Column(Modifier.fillMaxWidth().background(palette.panel, RoundedCornerShape(22.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Settings", color = palette.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        IosPlaybackPersistenceSetting(palette)
                        Text("Appearance", color = palette.accent, fontWeight = FontWeight.Medium)
                        Text("App font", color = palette.text, fontWeight = FontWeight.Medium)
                        Text("This follows the selected font on Android.", color = palette.muted, fontSize = 12.sp)
                        Box {
                            OutlinedButton(onClick = { fontMenuOpen = true }) {
                                Text(IosTypeface.label(selectedFont))
                            }
                            DropdownMenu(expanded = fontMenuOpen, onDismissRequest = { fontMenuOpen = false }) {
                                IosTypeface.options.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label, fontFamily = IosTypeface.appFontFamily(option.id)) },
                                        onClick = {
                                            selectedFont = option.id
                                            writeIosPreference("selected_font", option.id)
                                            fontMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Chillax brand font", color = palette.text, fontWeight = FontWeight.Medium)
                                Text("Use the Android wordmark and artist display face.", color = palette.muted, fontSize = 12.sp)
                            }
                            Switch(
                                checked = brandFontEnabled,
                                onCheckedChange = { enabled ->
                                    brandFontEnabled = enabled
                                    writeIosPreference("brandFontEnabled", enabled.toString())
                                },
                            )
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Navigation Magnet", color = palette.text, fontWeight = FontWeight.Medium)
                                Text(if (useMagnet) "Floating Magnet with animated attachments" else "Compact navigation bar", color = palette.muted, fontSize = 12.sp)
                            }
                            Switch(
                                checked = useMagnet,
                                onCheckedChange = { enabled ->
                                    useMagnet = enabled
                                    magnetOpen = false
                                    writeIosPreference("navigation.useMagnet", enabled.toString())
                                },
                            )
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Reduced motion", color = palette.text, fontWeight = FontWeight.Medium)
                                Text("Use instant Magnet transitions.", color = palette.muted, fontSize = 12.sp)
                            }
                            Switch(
                                checked = reducedMotion,
                                onCheckedChange = { enabled ->
                                    reducedMotion = enabled
                                    writeIosPreference("appearance.reducedMotion", enabled.toString())
                                },
                            )
                        }
                        Text("XAN logo", color = palette.text, fontWeight = FontWeight.Medium)
                        Text("Choose the mark shown across XAN.", color = palette.muted, fontSize = 12.sp)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IosAppLogo.entries.forEach { logo ->
                                Button(
                                    onClick = {
                                        appLogo = logo
                                        writeIosPreference("brand.appLogo", logo.preferenceValue)
                                        writeIosPreference("brand.androidLogo", (logo == IosAppLogo.HORIZON).toString())
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (appLogo == logo) palette.accent else palette.background,
                                        contentColor = if (appLogo == logo && !palette.isLight) Color.Black else palette.text,
                                    ),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Image(painterResource(logo.drawable), contentDescription = null, modifier = Modifier.width(22.dp).height(22.dp))
                                        Text(logo.label)
                                    }
                                }
                            }
                        }
                        Text("Color palette", color = palette.text, fontWeight = FontWeight.Medium)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("XAN", "Midnight", "Plum", "Forest", "Light").forEach { option ->
                                val optionPalette = xanPalette(option)
                                Button(
                                    onClick = {
                                        selectedPalette = option
                                        writeIosPreference("appearance.palette", option)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (selectedPalette == option) optionPalette.accent else optionPalette.panel,
                                        contentColor = if (selectedPalette == option && !optionPalette.isLight) Color.Black else optionPalette.text,
                                    ),
                                ) { Text(option) }
                            }
                        }
                        Text("Account", color = palette.accent, fontWeight = FontWeight.Medium)
                        Text(
                            "Sign in to YouTube Music here or paste a session cookie. XAN stores the session in iOS Keychain.",
                            color = palette.muted,
                            fontSize = 13.sp,
                        )
                        OutlinedButton(
                            onClick = {
                                IosYouTubeLogin.open(object : IosYouTubeLoginCallback {
                                    override fun onLoginCookie(cookie: String, dataSyncId: String?) {
                                        val saved = IosCredentialStorage.saveYouTubeCookie(cookie)
                                        hasYoutubeCookie = IosCredentialStorage.hasYouTubeCookie()
                                        credentialMessageIsError = !saved
                                        if (saved) {
                                            youtubeCookie = ""
                                            val selectedDataSyncId = normalizeIosDataSyncId(dataSyncId).orEmpty()
                                            accountDataSyncId = selectedDataSyncId
                                            writeIosPreference("account.dataSyncId", selectedDataSyncId)
                                            credentialMessage = "YouTube Music sign-in saved securely."
                                            scope.launch { refreshYoutubeAccount() }
                                        } else {
                                            credentialMessage = "The YouTube Music session could not be saved to Keychain. Try again or paste the cookie."
                                        }
                                    }

                                    override fun onLoginCancelled() {
                                        credentialMessage = "YouTube Music sign-in was cancelled."
                                        credentialMessageIsError = false
                                    }

                                    override fun onLoginError(message: String) {
                                        credentialMessage = message
                                        credentialMessageIsError = true
                                    }
                                })
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Sign in with YouTube Music") }
                        OutlinedTextField(
                            value = youtubeCookie,
                            onValueChange = { youtubeCookie = it; credentialMessage = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("YouTube Music cookie (includes SAPISID)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = {
                                    val valid = IosCredentialStorage.isYouTubeCookieValid(youtubeCookie)
                                    val saved = valid && IosCredentialStorage.saveYouTubeCookie(youtubeCookie)
                                    hasYoutubeCookie = IosCredentialStorage.hasYouTubeCookie()
                                    credentialMessageIsError = !saved
                                    credentialMessage = when {
                                        !valid -> "Paste the full cookie string containing a non-empty SAPISID value."
                                        saved -> "YouTube Music cookie saved securely."
                                        else -> "Keychain could not save the cookie. Try again."
                                    }
                                    if (saved) {
                                        youtubeCookie = ""
                                        accountDataSyncId = ""
                                        writeIosPreference("account.dataSyncId", "")
                                    }
                                },
                                enabled = youtubeCookie.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = if (palette.isLight) Color.White else Color.Black),
                            ) { Text("Save to Keychain") }
                            if (hasYoutubeCookie) {
                                OutlinedButton(onClick = {
                                    val cleared = IosCredentialStorage.clearYouTubeCookie()
                                    hasYoutubeCookie = IosCredentialStorage.hasYouTubeCookie()
                                    accountDataSyncId = ""
                                    writeIosPreference("account.dataSyncId", "")
                                    credentialMessageIsError = !cleared
                                    credentialMessage = if (cleared) "YouTube Music cookie removed." else "Keychain could not remove the cookie. Try again."
                                }) { Text("Remove") }
                            }
                        }
                        Text(if (hasYoutubeCookie) "YouTube Music cookie is saved on this device." else "No YouTube Music cookie is saved.", color = palette.muted, fontSize = 12.sp)
                        credentialMessage?.let { Text(it, color = if (credentialMessageIsError) Color(0xFFFF8A80) else palette.accent, fontSize = 13.sp) }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Use account for browsing", color = palette.text, fontWeight = FontWeight.Medium)
                                Text("Load personalized Home and search results.", color = palette.muted, fontSize = 12.sp)
                            }
                            Switch(
                                checked = useAccountForBrowse,
                                onCheckedChange = { enabled ->
                                    useAccountForBrowse = enabled
                                    writeIosPreference("useLoginForBrowse", enabled.toString())
                                },
                            )
                        }
                        youtubeAccountMessage?.let {
                            Text(it, color = if (youtubeAccountError) Color(0xFFFF8A80) else palette.muted, fontSize = 13.sp)
                        }
                        youtubeAccount?.let { account ->
                            Text(account.profile.name, color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                            account.profile.email?.let { Text(it, color = palette.muted, fontSize = 13.sp) }
                            account.profile.channelHandle?.let { Text(it, color = palette.muted, fontSize = 13.sp) }
                            if (account.channels.isNotEmpty()) {
                                Text("YouTube channels", color = palette.accent, fontWeight = FontWeight.Medium)
                                account.channels.forEach { channel ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(channel.name, color = palette.text, fontWeight = FontWeight.Medium)
                                            channel.channelHandle?.let { Text(it, color = palette.muted, fontSize = 12.sp) }
                                        }
                                        if (channel.dataSyncId == accountDataSyncId) {
                                            Text("Selected", color = palette.accent, fontSize = 12.sp)
                                        } else {
                                            OutlinedButton(
                                                onClick = {
                                                    accountDataSyncId = channel.dataSyncId
                                                    writeIosPreference("account.dataSyncId", channel.dataSyncId)
                                                },
                                                enabled = !youtubeAccountLoading,
                                            ) { Text("Switch") }
                                        }
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { scope.launch { refreshYoutubeAccount() } }, enabled = hasYoutubeCookie && !youtubeAccountLoading) {
                                Text(if (youtubeAccountLoading) "Refreshing…" else "Refresh account")
                            }
                        }
                        IosLastFmSettings(palette.accent, palette.text, palette.muted, palette.isLight)
                        IosListenBrainzSettings(palette.accent, palette.text, palette.muted, palette.isLight)
                        IosLyricsSettings(palette.text, palette.muted)
                        Text("Listen Together", color = palette.accent, fontWeight = FontWeight.Medium)
                        RoomServerProviderChooser(server, enabled = room.roomCode == null) { server = it; IosListenTogether.savePreferences(it, name) }
                        if (XAN_ROOM_HUGGING_FACE_URL.isBlank()) {
                            Text("Hugging Face needs a deployed Space URL. Select it above, then enter the Space's wss:// URL below.", color = palette.muted, fontSize = 12.sp)
                        }
                        Text("Everyone in a room must use the same server. Hugging Face Spaces may pause after idle.", color = palette.muted, fontSize = 12.sp)
                        OutlinedTextField(server, { server = it; IosListenTogether.savePreferences(it, name) }, modifier = Modifier.fillMaxWidth(), label = { Text("Secure room server") }, singleLine = true)
                        OutlinedTextField(name, { name = it; IosListenTogether.savePreferences(server, it) }, modifier = Modifier.fillMaxWidth(), label = { Text("Display name") }, singleLine = true)
                        Text("Appearance and room preferences are saved on this device. Room session tokens are stored in iOS Keychain.", color = palette.muted, fontSize = 13.sp)
                    }
                } else {
                    Column(Modifier.fillMaxWidth().background(palette.panel, RoundedCornerShape(22.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("About XAN", color = palette.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        Text("Version $XAN_IOS_VERSION", color = palette.accent, fontWeight = FontWeight.Medium)
                        Text("Developer, author, and producer: bxane", color = palette.text)
                        Text("XAN brings your YouTube Music discovery, personal library, and shared listening rooms together.", color = palette.muted, fontSize = 14.sp)
                        Text("Your Home, Search, library, logo, and appearance preferences stay on this device. Listen Together rooms use the same server and protocol as Android and desktop.", color = palette.muted, fontSize = 13.sp)
                    }
                }
                Text("XAN · bxane", modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp), color = palette.muted, fontSize = 12.sp)
            }
            val magnetSize = 56.dp
            val attachmentWidth = minOf(190.dp, (maxWidth - 24.dp).coerceAtLeast(0.dp))
            val attachmentHeight = 48.dp
            val attachmentGap = 8.dp
            val density = LocalDensity.current
            val edgeInset = with(density) { 16.dp.toPx() }
            val magnetMaxX = with(density) { (maxWidth - magnetSize - 32.dp).coerceAtLeast(0.dp).toPx() }
            val magnetMaxY = with(density) { (maxHeight - magnetSize - 32.dp).coerceAtLeast(0.dp).toPx() }
            val dockAnimation: FiniteAnimationSpec<Float> = if (reducedMotion || magnetDragging) snap() else spring(dampingRatio = .78f, stiffness = 520f)
            val magnetLeft by animateFloatAsState(edgeInset + magnetX * magnetMaxX, dockAnimation, label = "iosMagnetDockX")
            val magnetTop by animateFloatAsState(edgeInset + magnetY * magnetMaxY, dockAnimation, label = "iosMagnetDockY")
            val isDockedLeft = magnetX < .5f
            val menuLeft = if (isDockedLeft) edgeInset else
                (with(density) { maxWidth.toPx() } - with(density) { attachmentWidth.toPx() } - edgeInset)
                    .coerceAtLeast(0f)
            val magnetSizePx = with(density) { magnetSize.toPx() }
            val attachmentWidthPx = with(density) { attachmentWidth.toPx() }
            val openUpward = magnetTop + with(density) { magnetSize.toPx() / 2f } > with(density) { maxHeight.toPx() / 2f }
            if (useMagnet) {
                if (magnetShowing) {
                    IosMagnetItems.forEachIndexed { index, item ->
                        val itemHeightPx = with(density) { attachmentHeight.toPx() }
                        val itemGapPx = with(density) { attachmentGap.toPx() }
                        val itemTop = if (openUpward) {
                            magnetTop - itemHeightPx - index * (itemHeightPx + itemGapPx)
                        } else {
                            magnetTop + with(density) { magnetSize.toPx() } + index * (itemHeightPx + itemGapPx)
                        }.coerceIn(0f, (with(density) { maxHeight.toPx() } - itemHeightPx).coerceAtLeast(0f))
                        IosMagnetAttachment(
                            item = item,
                            selected = selectedTab == item.title,
                            expanded = magnetOpen,
                            left = menuLeft,
                            top = itemTop,
                            width = attachmentWidth,
                            hubCenter = Offset(magnetLeft + magnetSizePx / 2f, magnetTop + magnetSizePx / 2f),
                            attachmentWidthPx = attachmentWidthPx,
                            attachmentHeightPx = itemHeightPx,
                            reducedMotion = reducedMotion,
                            palette = palette,
                            onClick = { selectedTab = item.title; magnetOpen = false },
                        )
                    }
                }
                Box(
                    Modifier.offset { IntOffset(magnetLeft.roundToInt(), magnetTop.roundToInt()) }
                        .size(magnetSize)
                        .semantics {
                            role = Role.Button
                            contentDescription = if (magnetOpen) "Close navigation Magnet" else "Open navigation Magnet"
                        }
                        .clickable { magnetOpen = !magnetOpen }
                        .pointerInput(magnetMaxX, magnetMaxY) {
                            detectDragGestures(
                                onDragStart = { magnetDragging = true },
                                onDragCancel = { magnetDragging = false },
                                onDrag = { change, delta ->
                                    change.consume()
                                    magnetX = if (magnetMaxX == 0f) 1f else ((magnetX * magnetMaxX + delta.x) / magnetMaxX).coerceIn(0f, 1f)
                                    magnetY = if (magnetMaxY == 0f) 1f else ((magnetY * magnetMaxY + delta.y) / magnetMaxY).coerceIn(0f, 1f)
                                },
                                onDragEnd = {
                                    val docked = dockIosMagnet(magnetX, magnetY, magnetMaxX, magnetMaxY)
                                    magnetX = docked.x
                                    magnetY = docked.y
                                    writeIosPreference("navigation.magnet.x", magnetX.toString())
                                    writeIosPreference("navigation.magnet.y", magnetY.toString())
                                    magnetDragging = false
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(28.dp).rotate(magnetRotation)) {
                        val gap = size.minDimension * .11f
                        val cell = (size.minDimension - gap) / 2f
                        val stroke = size.minDimension * .075f
                        for (row in 0..1) for (column in 0..1) {
                            drawRoundRect(
                                color = palette.text,
                                topLeft = Offset(column * (cell + gap), row * (cell + gap)),
                                size = Size(cell, cell),
                                cornerRadius = CornerRadius(stroke * 1.5f),
                                style = Stroke(stroke),
                            )
                        }
                    }
                }
            } else {
                Row(
                    Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                        .clip(CircleShape)
                        .background(palette.panel)
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IosMagnetItems.forEach { item ->
                        val selected = selectedTab == item.title
                        Box(
                            Modifier.size(48.dp).clip(CircleShape)
                                .background(if (selected) palette.accent else Color.Transparent)
                                .clickable { selectedTab = item.title }
                                .semantics {
                                    role = Role.Button
                                    contentDescription = item.title
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(item.symbol, color = if (selected && !palette.isLight) Color.Black else palette.text, fontSize = 19.sp)
                        }
                    }
                }
            }
            }
        }
        playlistPendingDelete?.let { playlist ->
            AlertDialog(
                onDismissRequest = { playlistPendingDelete = null },
                title = { Text("Delete playlist?") },
                text = { Text("Delete “${playlist.name}” and its ${playlist.tracks.size} saved songs? This cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        runCatching { IosMusicLibrary.deletePlaylist(playlist.id) }
                            .onSuccess { libraryMessage = "Playlist deleted." }
                            .onFailure { failure -> libraryMessage = failure.message ?: "Could not delete the playlist." }
                        playlistPendingDelete = null
                    }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { playlistPendingDelete = null }) { Text("Cancel") }
                },
            )
        }
    }
}

@Composable
private fun IosPlaybackQueueModeControls(shuffleEnabled: Boolean, repeatModeLabel: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { IosMusicPlayback.toggleShuffle() }) {
            Text(if (shuffleEnabled) "Shuffle on" else "Shuffle off")
        }
        OutlinedButton(onClick = { IosMusicPlayback.cycleRepeatMode() }) {
            Text("Repeat ${repeatModeLabel.replaceFirstChar(Char::uppercase)}")
        }
    }
}

@Composable
private fun IosSleepTimerControls(timer: IosSleepTimerState, palette: XanPalette) {
    var dialogOpen by remember { mutableStateOf(false) }
    var minutes by remember { mutableIntStateOf(30) }
    Column(
        Modifier.fillMaxWidth().background(palette.background, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Sleep timer", color = palette.text, fontWeight = FontWeight.Medium)
                Text(
                    when {
                        timer.pauseAtTrackEnd && timer.remainingMillis != null -> "Pauses at the end of this track · ${formatIosTimer(timer.remainingMillis)} left"
                        timer.pauseAtTrackEnd -> "Pauses at the end of this track"
                        timer.deadlineEpochMillis != null -> "Pauses in ${formatIosTimer(timer.remainingMillis ?: 0L)}"
                        else -> "Off"
                    },
                    color = palette.muted,
                    fontSize = 12.sp,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { dialogOpen = true }) { Text(if (timer.isActive) "Change" else "Set") }
            if (timer.isActive) {
                TextButton(onClick = IosMusicPlayback::cancelSleepTimer) { Text("Cancel") }
            }
        }
    }

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text("Sleep timer") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Pause after $minutes minutes", color = palette.text)
                    Slider(
                        value = minutes.toFloat(),
                        onValueChange = { minutes = ((it.roundToInt() / 5) * 5).coerceIn(5, 120) },
                        valueRange = 5f..120f,
                        steps = 22,
                    )
                    OutlinedButton(
                        onClick = {
                            IosMusicPlayback.startSleepTimerAtTrackEnd()
                            dialogOpen = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("At the end of this track") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    IosMusicPlayback.startSleepTimer(minutes)
                    dialogOpen = false
                }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { dialogOpen = false }) { Text("Cancel") } },
        )
    }
}

private fun formatIosTimer(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) + 999L) / 1_000L
    val seconds = totalSeconds % 60L
    val totalMinutes = totalSeconds / 60L
    val minutes = totalMinutes % 60L
    val hours = totalMinutes / 60L
    return if (hours > 0L) {
        "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${totalMinutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}

@Composable
private fun IosPlaybackQueueTrackRow(
    track: IosMusicSearchTrack,
    orderPosition: Int,
    isCurrent: Boolean,
    isUpcoming: Boolean,
    palette: XanPalette,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(palette.background, RoundedCornerShape(12.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("${(orderPosition + 1).coerceAtLeast(1)}. ${track.title}", color = palette.text, fontWeight = FontWeight.Medium)
            Text(track.artist, color = palette.muted, fontSize = 12.sp)
            Text(
                when {
                    isCurrent -> "Now playing"
                    isUpcoming -> "Up next"
                    orderPosition >= 0 -> "Played"
                    else -> "Not queued"
                },
                color = if (isCurrent) palette.accent else palette.muted,
                fontSize = 11.sp,
            )
        }
        OutlinedButton(onClick = onPlay) { Text("Play") }
        OutlinedButton(onClick = onRemove) { Text("Remove") }
    }
}

@Composable
private fun IosPlaybackPersistenceSetting(palette: XanPalette) {
    var rememberModes by remember {
        mutableStateOf(readIosPreference("rememberShuffleAndRepeat")?.toBooleanStrictOrNull() ?: true)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Player", color = palette.accent, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Remember shuffle and repeat", color = palette.text, fontWeight = FontWeight.Medium)
                Text("Restore these playback modes when XAN starts, like Android.", color = palette.muted, fontSize = 12.sp)
            }
            Switch(
                checked = rememberModes,
                onCheckedChange = { enabled ->
                    rememberModes = enabled
                    IosMusicPlayback.setRememberShuffleAndRepeat(enabled)
                },
            )
        }
    }
}

@Composable
private fun IosMagnetAttachment(
    item: IosMagnetItem,
    selected: Boolean,
    expanded: Boolean,
    left: Float,
    top: Float,
    width: androidx.compose.ui.unit.Dp,
    hubCenter: Offset,
    attachmentWidthPx: Float,
    attachmentHeightPx: Float,
    reducedMotion: Boolean,
    palette: XanPalette,
    onClick: () -> Unit,
) {
    val arrival = remember(item.title) { Animatable(0f) }
    LaunchedEffect(expanded, reducedMotion) {
        if (reducedMotion) {
            arrival.snapTo(if (expanded) 1f else 0f)
        } else if (expanded) {
            arrival.snapTo(0f)
            delay((IosMagnetItems.lastIndex - IosMagnetItems.indexOf(item)).coerceAtLeast(0) * 34L)
            arrival.animateTo(1f, spring(dampingRatio = .62f, stiffness = 520f))
        } else {
            arrival.animateTo(0f, spring(dampingRatio = .84f, stiffness = 560f))
        }
    }
    val progress = arrival.value
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .width(width)
            .height(48.dp)
            .graphicsLayer {
                alpha = progress
                translationX = (1f - progress) * (hubCenter.x - (left + attachmentWidthPx / 2f))
                translationY = (1f - progress) * (hubCenter.y - (top + attachmentHeightPx / 2f))
                scaleX = .86f + .14f * progress
                scaleY = .86f + .14f * progress
            }
            .clip(shape)
            .background(if (selected) palette.accent else palette.panel)
            .clickable(enabled = expanded, onClick = onClick)
            .semantics { role = Role.Button },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(item.symbol, color = if (selected && !palette.isLight) Color.Black else palette.text, fontSize = 19.sp)
            Text(
                item.title,
                color = if (selected && !palette.isLight) Color.Black else palette.text,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun RoomServerProviderChooser(server: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        iosRoomServerProviders.forEach { provider ->
            FilterChip(selected = server == provider.url, onClick = { onSelect(provider.url) }, enabled = enabled, label = { Text(provider.name) })
        }
    }
}

@Composable
private fun YoutubeLibraryItemRow(
    title: String,
    subtitle: String,
    palette: XanPalette,
    enabled: Boolean,
    artworkUrl: String? = null,
    artworkShape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(10.dp),
    onOpen: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(palette.background, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CatalogArtwork(artworkUrl, title, Modifier.size(48.dp), palette, artworkShape)
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.text, fontWeight = FontWeight.Medium)
            Text(subtitle, color = palette.muted, fontSize = 12.sp)
        }
        OutlinedButton(
            onClick = onOpen,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "Open $title" },
        ) { Text("Open") }
    }
}

@Composable
private fun CatalogArtwork(
    artworkUrl: String?,
    description: String,
    modifier: Modifier,
    palette: XanPalette,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(10.dp),
) {
    val trustedUrl = safeIosArtworkUrl(artworkUrl)
    var hasLoadFailed by remember(trustedUrl) { mutableStateOf(false) }
    Box(modifier.clip(shape).background(palette.background), contentAlignment = Alignment.Center) {
        if (trustedUrl != null && !hasLoadFailed) {
            AsyncImage(
                model = trustedUrl,
                contentDescription = "$description artwork",
                modifier = Modifier.fillMaxSize().clip(shape),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                onError = { hasLoadFailed = true },
            )
        } else {
            Text("♪", color = palette.accent, fontSize = 22.sp)
        }
    }
}

@Composable
private fun LibraryTrackRow(
    track: IosLibraryTrack,
    palette: XanPalette,
    playEnabled: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(palette.background, RoundedCornerShape(12.dp)).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CatalogArtwork(track.artworkUrl, track.title, Modifier.size(42.dp), palette)
        Column(Modifier.weight(1f)) {
            Text(track.title, color = palette.text, fontWeight = FontWeight.Medium)
            Text(listOfNotNull(track.artist, track.album).joinToString(" · "), color = palette.muted, fontSize = 12.sp)
        }
        Button(
            onClick = onPlay,
            enabled = playEnabled,
            colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = if (palette.isLight) Color.White else Color.Black),
        ) { Text("Play") }
        OutlinedButton(onClick = onRemove) { Text("Remove") }
    }
}

@Composable
private fun CatalogTrackRow(
    track: IosMusicSearchTrack,
    palette: XanPalette,
    playEnabled: Boolean,
    library: IosMusicLibrarySnapshot,
    download: IosMusicDownloadState,
    onQueueNext: (() -> Unit)? = null,
    onPlay: () -> Unit,
) {
    val isDownloaded = library.downloadedTracks.any { it.id == track.videoId }
    val isQueued = track.videoId in download.queuedTrackIds
    val isDownloading = download.isDownloading && download.trackId == track.videoId
    Column(
        Modifier.fillMaxWidth().background(palette.background, RoundedCornerShape(12.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CatalogArtwork(track.artworkUrl, track.title, Modifier.size(48.dp), palette)
            Column(Modifier.weight(1f)) {
                Text(track.title, color = palette.text, fontWeight = FontWeight.Medium)
                Text(track.artist, color = palette.muted, fontSize = 12.sp)
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onPlay,
                enabled = playEnabled,
                colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = if (palette.isLight) Color.White else Color.Black),
            ) { Text("Play") }
            OutlinedButton(
                onClick = { IosMusicPlayback.download(track) },
                enabled = !isDownloaded && !isQueued && !isDownloading,
            ) {
                Text(when {
                    isDownloaded -> "Downloaded"
                    isDownloading -> "Downloading…"
                    isQueued -> "Queued"
                    else -> "Download"
                })
            }
            onQueueNext?.let { queueNext ->
                OutlinedButton(onClick = queueNext, enabled = playEnabled) { Text("Play next") }
            }
        }
    }
}

private suspend fun <T> captureSearchResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    Result.failure(failure)
}
