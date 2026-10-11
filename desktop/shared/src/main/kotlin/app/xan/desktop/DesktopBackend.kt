package app.xan.desktop

import com.microsoft.credentialstorage.SecretStore
import com.microsoft.credentialstorage.StorageProvider
import com.microsoft.credentialstorage.StorageProvider.SecureOption
import com.microsoft.credentialstorage.model.StoredCredential
import com.music.innertube.YouTube
import com.music.innertube.NewPipeExtractor
import com.music.innertube.ExternalVideoFormat
import com.music.innertube.ExternalVideoInfo
import com.music.innertube.ExternalVideoSearchResult
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.YouTubeClient
import com.music.innertube.models.AccountInfo
import com.music.innertube.models.response.PlayerResponse
import com.music.innertube.pages.SearchResult
import com.music.innertube.pages.ArtistPage
import com.music.innertube.pages.ExplorePage
import com.music.innertube.pages.MoodAndGenres
import com.music.innertube.pages.HomePage
import com.music.innertube.models.BrowseEndpoint
import com.music.lastfm.LastFM
import com.music.spotify.SpotifyPublicPlaylist
import com.music.spotify.SpotifyPublicPlaylistClient
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.io.RandomAccessFile
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.Base64
import java.util.Properties
import java.util.prefs.Preferences
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException

internal data class DiscoveryBrowse(val title: String?, val sections: List<Pair<String, List<MusicTrack>>>)
internal data class DesktopHomeFeed(
    val chips: List<HomePage.Chip>,
    val recentTracks: List<MusicTrack>,
    val shelves: List<Pair<String, List<MusicTrack>>>,
)
internal data class YouTubeMusicLibrarySyncResult(
    val songs: Int,
    val albums: Int,
    val artists: Int,
    val playlists: Int,
    val syncedAt: Long,
)
internal data class YouTubePlaylistSyncResult(
    val playlistsCreated: Int,
    val playlistsUpdated: Int,
    val tracksAdded: Int,
    val tracksRemoved: Int,
    val tracksReordered: Int,
    val tracksWithoutYouTubeId: Int,
    val playlistsPartiallySynced: Int,
)

internal object DesktopBackend {
    private const val FALLBACK_KEY = "youtube_video_fallback"
    private const val VIDEO_ONLY_SEARCH = "video_only_search"
    private const val OTHER_VIDEO_SOURCES = "other_video_sources"
    private const val DIRECT_VIDEO_SERVICE_ID = -1
    private const val DOWNLOAD_DIR_KEY = "download_directory"
    private const val YOUTUBE_COOKIE_KEY = "youtube_music_cookie"
    private const val LYRICS_TRANSLATION_API_KEY = "lyrics_translation_openrouter_key"
    private const val YOUTUBE_DATA_SYNC_ID = "youtube_data_sync_id"
    private const val YOUTUBE_CHANNELS = "youtube_saved_channels"
    private const val YOUTUBE_LOGIN_FOR_BROWSE = "youtube_login_for_browse"
    private const val LISTEN_TOGETHER_SERVER = "listen_together_server_url"
    private const val LISTEN_TOGETHER_TOKEN = "listen_together_session_token"
    private const val LISTEN_TOGETHER_ROOM = "listen_together_room_code"
    private const val LISTEN_TOGETHER_USER = "listen_together_user_id"
    private const val LISTEN_TOGETHER_USERNAME = "listen_together_username"
    private const val LASTFM_API_KEY = "lastfm_api_key"
    private const val LASTFM_SECRET = "lastfm_api_secret"
    private const val LASTFM_SESSION = "lastfm_session"
    private const val LISTENBRAINZ_TOKEN = "listenbrainz_user_token"
    private const val LASTFM_SCROBBLING = "lastfm_scrobbling_enabled"
    private const val LISTENBRAINZ_SCROBBLING = "listenbrainz_scrobbling_enabled"
    private const val LISTENBRAINZ_USERNAME = "listenbrainz_username"
    private const val DISCORD_APP_ID = "discord_application_id"
    private const val DISCORD_ENABLED = "discord_presence_enabled"
    private const val MAGNET_NAV = "magnet_navigation"
    private const val MAGNET_SEMICIRCLE = "magnet_semicircle"
    private const val SIDEBAR_NAV = "sidebar_navigation"
    private const val SIDEBAR_COLLAPSED = "sidebar_collapsed"
    private const val MAGNET_X = "magnet_position_x"
    private const val MAGNET_Y = "magnet_position_y"
    private const val GOLD_LOGO = "gold_logo"
    private const val APP_LOGO = "app_logo"
    private const val COLOR_THEME = "color_theme"
    private const val FONT_FAMILY = "font_family"
    private const val PLAYER_VOLUME = "player_volume"
    private const val EQ_ENABLED = "equalizer_enabled"
    private const val EQ_ADVANCED = "equalizer_advanced"
    private const val EQ_GAINS = "equalizer_gains_db"
    private const val SAVED_COLLECTIONS = "saved_collections"
    private const val LISTENING_HISTORY = "listening_history_enabled"
    private const val SEARCH_HISTORY = "search_history_enabled"
    private const val REDUCED_MOTION = "reduced_motion"
    private const val REDUCED_TRANSPARENCY = "reduced_transparency"
    private const val ANIMATED_PLAYER_CANVAS = "animated_player_canvas"
    private const val GLOBAL_MEDIA_KEYS = "global_media_keys_enabled"
    private const val DJ_TRANSITION_SECONDS = "dj_transition_seconds"
    private const val LYRICS_AUTO_SCROLL = "lyrics_auto_scroll"
    private const val LYRICS_ANIMATION = "lyrics_animation"
    private const val LYRICS_BLUR = "lyrics_blur"
    private const val LYRICS_ROMANIZE_JAPANESE = "lyrics_romanize_japanese"
    private const val LYRICS_TRANSLATION_LANGUAGE = "lyrics_translation_language"
    private const val LYRICS_PROVIDER_ORDER = "lyrics_provider_order"
    private const val LIBRARY_SORT = "library_sort"
    private const val LIBRARY_SORT_DESCENDING = "library_sort_descending"
    private const val LIBRARY_GRID = "library_grid"
    private const val SONG_CACHE_LIMIT_MB = "song_cache_limit_mb"
    private const val IMAGE_CACHE_LIMIT_MB = "image_cache_limit_mb"
    private val LIBRARY_SORT_OPTIONS = setOf("Date added", "Name", "Last updated", "Playlist order")
    private val FONT_FAMILY_IDS = setOf("system", "google_sans", "sans_flex", "outfit", "plus_jakarta")
    private val preferences = Preferences.userRoot().node("app/xan/desktop")
    private val audioCacheMutex = Mutex()
    private val adaptiveVideoMutex = Mutex()
    private val localMusicWatcher by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MusicLibraryWatcher(
            roots = ::musicFolders,
            scan = { scanMusicLibrary() },
            onFailure = { DiagnosticLog.warn(diagnosticsDirectory(), "local_library_watch_failed", "The watched music library could not be refreshed.", it) },
        )
    }
    private val backupPreferenceKeys = setOf(FALLBACK_KEY, VIDEO_ONLY_SEARCH, OTHER_VIDEO_SOURCES, DOWNLOAD_DIR_KEY, DISCORD_ENABLED, MAGNET_NAV, MAGNET_SEMICIRCLE, SIDEBAR_NAV, SIDEBAR_COLLAPSED, MAGNET_X, MAGNET_Y, GOLD_LOGO, APP_LOGO, COLOR_THEME, FONT_FAMILY, PLAYER_VOLUME, EQ_ENABLED, EQ_ADVANCED, EQ_GAINS, SAVED_COLLECTIONS, SONG_CACHE_LIMIT_MB, IMAGE_CACHE_LIMIT_MB, ANIMATED_PLAYER_CANVAS, LYRICS_TRANSLATION_LANGUAGE)
    @Volatile private var currentTrack: MusicTrack? = null
    private val catalogue by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CatalogStore(catalogueFile())
    }
    private val recognitionHistoryStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RecognitionHistoryStore(
            readValue = { catalogue.getSetting("recognition_history") },
            writeValue = { catalogue.setSetting("recognition_history", it) },
        )
    }
    private val credentials: SecretStore<StoredCredential>? = runCatching {
        StorageProvider.getCredentialStorage(true, SecureOption.REQUIRED)
    }.getOrNull()
    private val scrobbleLedger = ScrobbleSubmissionLedger()

    fun fallbackEnabled(): Boolean = preferences.getBoolean(FALLBACK_KEY, false)
    fun listenTogetherServerUrl(): String = resolveDesktopRoomServerUrl(preferences.get(LISTEN_TOGETHER_SERVER, ""))
    fun setListenTogetherServerUrl(value: String) {
        if (value.isBlank()) { preferences.remove(LISTEN_TOGETHER_SERVER); return }
        val normalized = normalizeDesktopRoomServerUrl(value)
        require(normalized != null) { "Use a secure wss:// server origin without a path, query, or credentials. Local development servers may use ws://." }
        preferences.put(LISTEN_TOGETHER_SERVER, normalized)
    }
    fun saveListenTogetherSession(token: String, roomCode: String, userId: String, username: String) {
        saveSecret(LISTEN_TOGETHER_TOKEN, token)
        preferences.put(LISTEN_TOGETHER_ROOM, roomCode)
        preferences.put(LISTEN_TOGETHER_USER, userId)
        preferences.put(LISTEN_TOGETHER_USERNAME, username)
    }
    fun listenTogetherSession(): Triple<String, String, String>? {
        val token = readSecret(LISTEN_TOGETHER_TOKEN)?.takeIf(String::isNotBlank) ?: return null
        val room = preferences.get(LISTEN_TOGETHER_ROOM, "").takeIf(String::isNotBlank) ?: return null
        val username = preferences.get(LISTEN_TOGETHER_USERNAME, "XAN listener")
        return Triple(token, room, username)
    }
    fun clearListenTogetherSession() {
        deleteSecret(LISTEN_TOGETHER_TOKEN)
        preferences.remove(LISTEN_TOGETHER_ROOM)
        preferences.remove(LISTEN_TOGETHER_USER)
        preferences.remove(LISTEN_TOGETHER_USERNAME)
    }
    fun magnetNavigation(): Boolean = preferences.getBoolean(MAGNET_NAV, true)
    fun setMagnetNavigation(value: Boolean) = preferences.putBoolean(MAGNET_NAV, value)
    fun magnetSemicircle(): Boolean = preferences.getBoolean(MAGNET_SEMICIRCLE, false)
    fun setMagnetSemicircle(value: Boolean) = preferences.putBoolean(MAGNET_SEMICIRCLE, value)
    fun sidebarNavigation(): Boolean = preferences.getBoolean(SIDEBAR_NAV, false)
    fun setSidebarNavigation(value: Boolean) = preferences.putBoolean(SIDEBAR_NAV, value)
    fun sidebarCollapsed(): Boolean = preferences.getBoolean(SIDEBAR_COLLAPSED, false)
    fun setSidebarCollapsed(value: Boolean) = preferences.putBoolean(SIDEBAR_COLLAPSED, value)
    fun appLogo(): AppLogoVariant = AppLogoVariant.fromPreferences(
        preferences.get(APP_LOGO, null),
        preferences.get(GOLD_LOGO, null)?.toBooleanStrictOrNull(),
    )
    fun setAppLogo(value: AppLogoVariant) {
        preferences.put(APP_LOGO, value.preferenceValue)
        preferences.putBoolean(GOLD_LOGO, value == AppLogoVariant.GOLD)
    }
    /** Compatibility accessors for backup migration and older tests. */
    fun goldLogo(): Boolean = appLogo() == AppLogoVariant.GOLD
    fun setGoldLogo(value: Boolean) = setAppLogo(if (value) AppLogoVariant.GOLD else AppLogoVariant.HORIZON)
    fun colorTheme(): String = preferences.get(COLOR_THEME, "xan").takeIf { it in XanPalette.ids } ?: "xan"
    fun setColorTheme(value: String) = preferences.put(COLOR_THEME, value.takeIf { it in XanPalette.ids } ?: "xan")
    fun fontFamily(): String = preferences.get(FONT_FAMILY, "system").takeIf { it in FONT_FAMILY_IDS } ?: "system"
    fun setFontFamily(value: String) = preferences.put(FONT_FAMILY, value.takeIf { it in FONT_FAMILY_IDS } ?: "system")
    fun magnetPosition(): Pair<Float, Float>? = if (preferences.get(MAGNET_X, null) != null && preferences.get(MAGNET_Y, null) != null) {
        preferences.getFloat(MAGNET_X, 0f).coerceIn(0f, 1f) to preferences.getFloat(MAGNET_Y, 0f).coerceIn(0f, 1f)
    } else null
    fun setMagnetPosition(x: Float, y: Float) {
        preferences.putFloat(MAGNET_X, x.coerceIn(0f, 1f))
        preferences.putFloat(MAGNET_Y, y.coerceIn(0f, 1f))
    }
    fun clearMagnetPosition() {
        preferences.remove(MAGNET_X)
        preferences.remove(MAGNET_Y)
    }
    fun setFallbackEnabled(value: Boolean) = preferences.putBoolean(FALLBACK_KEY, value)
    fun videoOnlySearch(): Boolean = preferences.getBoolean(VIDEO_ONLY_SEARCH, false)
    fun setVideoOnlySearch(value: Boolean) = preferences.putBoolean(VIDEO_ONLY_SEARCH, value)
    fun otherVideoSourcesEnabled(): Boolean = preferences.getBoolean(OTHER_VIDEO_SOURCES, true)
    fun setOtherVideoSourcesEnabled(value: Boolean) = preferences.putBoolean(OTHER_VIDEO_SOURCES, value)
    fun librarySort(): String = preferences.get(LIBRARY_SORT, "Date added").takeIf { it in LIBRARY_SORT_OPTIONS } ?: "Date added"
    fun setLibrarySort(value: String) { if (value in LIBRARY_SORT_OPTIONS) preferences.put(LIBRARY_SORT, value) }
    fun librarySortDescending(): Boolean = preferences.getBoolean(LIBRARY_SORT_DESCENDING, true)
    fun setLibrarySortDescending(value: Boolean) = preferences.putBoolean(LIBRARY_SORT_DESCENDING, value)
    fun libraryGrid(): Boolean = preferences.getBoolean(LIBRARY_GRID, true)
    fun setLibraryGrid(value: Boolean) = preferences.putBoolean(LIBRARY_GRID, value)
    fun listeningHistoryEnabled(): Boolean = catalogue.getSetting(LISTENING_HISTORY)?.toBooleanStrictOrNull() ?: true
    internal fun catalogueSetting(key: String): String? = catalogue.getSetting(key)
    internal fun setCatalogueSetting(key: String, value: String) = catalogue.setSetting(key, value)
    fun setListeningHistoryEnabled(value: Boolean) = catalogue.setSetting(LISTENING_HISTORY, value.toString())
    fun listeningHistoryCount(): Long = catalogue.historyCount()
    fun recentListeningHistory(limit: Int = 100): List<CatalogPlayEvent> = catalogue.recentHistory(limit)
    fun listeningHistoryStats(sinceMillis: Long? = null): CatalogHistoryStats = catalogue.historyStats(sinceMillis)
    fun topListeningHistory(limit: Int = 10, sinceMillis: Long? = null): List<CatalogTrackPlayStats> = catalogue.topPlayedTrackStats(limit, sinceMillis)
    fun clearListeningHistory() = catalogue.clearHistory()
    fun topPlayedTracks(limit: Int = 50): List<MusicTrack> = if (listeningHistoryEnabled()) catalogue.topPlayedTracks(limit).map(::toMusicTrack) else emptyList()
    fun searchHistoryEnabled(): Boolean = catalogue.getSetting(SEARCH_HISTORY)?.toBooleanStrictOrNull() ?: true
    fun setSearchHistoryEnabled(value: Boolean) = catalogue.setSetting(SEARCH_HISTORY, value.toString())
    fun recordSearch(query: String) = catalogue.recordSearch(query)
    fun recentSearches(limit: Int = 12): List<String> = catalogue.recentSearches(limit)
    fun searchHistoryCount(): Long = catalogue.searchHistoryCount()
    fun clearSearchHistory() = catalogue.clearSearchHistory()
    fun reducedMotion(): Boolean = catalogue.getSetting(REDUCED_MOTION)?.toBooleanStrictOrNull() ?: false
    fun setReducedMotion(value: Boolean) = catalogue.setSetting(REDUCED_MOTION, value.toString())
    fun reducedTransparency(): Boolean = catalogue.getSetting(REDUCED_TRANSPARENCY)?.toBooleanStrictOrNull() ?: false
    fun setReducedTransparency(value: Boolean) = catalogue.setSetting(REDUCED_TRANSPARENCY, value.toString())
    fun animatedPlayerCanvas(): Boolean = preferences.getBoolean(ANIMATED_PLAYER_CANVAS, true)
    fun setAnimatedPlayerCanvas(value: Boolean) = preferences.putBoolean(ANIMATED_PLAYER_CANVAS, value)
    fun globalMediaKeysEnabled(): Boolean = preferences.getBoolean(GLOBAL_MEDIA_KEYS, false)
    fun setGlobalMediaKeysEnabled(value: Boolean) {
        preferences.putBoolean(GLOBAL_MEDIA_KEYS, value)
        DesktopGlobalMediaKeys.setEnabled(value)
    }
    fun djTransitionSeconds(): Int = preferences.getInt(DJ_TRANSITION_SECONDS, 0).coerceIn(0, 8)
    fun setDjTransitionSeconds(seconds: Int) = preferences.putInt(DJ_TRANSITION_SECONDS, seconds.coerceIn(0, 8))
    fun lyricsAutoScroll(): Boolean = catalogue.getSetting(LYRICS_AUTO_SCROLL)?.toBooleanStrictOrNull() ?: true
    fun setLyricsAutoScroll(value: Boolean) = catalogue.setSetting(LYRICS_AUTO_SCROLL, value.toString())
    fun lyricsAnimation(): String = catalogue.getSetting(LYRICS_ANIMATION) ?: "XAN"
    fun setLyricsAnimation(value: String) = catalogue.setSetting(LYRICS_ANIMATION, value)
    fun lyricsBlur(): Boolean = catalogue.getSetting(LYRICS_BLUR)?.toBooleanStrictOrNull() ?: false
    fun setLyricsBlur(value: Boolean) = catalogue.setSetting(LYRICS_BLUR, value.toString())
    fun lyricsRomanizationEnabled(): Boolean = catalogue.getSetting(LYRICS_ROMANIZE_JAPANESE)?.toBooleanStrictOrNull() ?: true
    fun setLyricsRomanizationEnabled(value: Boolean) = catalogue.setSetting(LYRICS_ROMANIZE_JAPANESE, value.toString())
    /** Kept for existing callers and older backups. */
    fun lyricsRomanizeJapanese(): Boolean = lyricsRomanizationEnabled()
    fun setLyricsRomanizeJapanese(value: Boolean) = setLyricsRomanizationEnabled(value)
    fun lyricsTranslationLanguage(): String = catalogue.getSetting(LYRICS_TRANSLATION_LANGUAGE)?.takeIf(String::isNotBlank) ?: "English"
    fun setLyricsTranslationLanguage(value: String) = catalogue.setSetting(LYRICS_TRANSLATION_LANGUAGE, value.trim().take(40).ifBlank { "English" })
    fun lyricsTranslationConfigured(): Boolean = !readSecret(LYRICS_TRANSLATION_API_KEY).isNullOrBlank()
    fun saveLyricsTranslationApiKey(value: String) {
        require(value.trim().startsWith("sk-or-")) { "Enter an OpenRouter API key." }
        saveSecret(LYRICS_TRANSLATION_API_KEY, value.trim())
    }
    fun clearLyricsTranslationApiKey() = deleteSecret(LYRICS_TRANSLATION_API_KEY)
    suspend fun translateLyrics(lines: List<String>, targetLanguage: String): List<String> {
        val key = readSecret(LYRICS_TRANSLATION_API_KEY) ?: error("Add an OpenRouter API key in Settings → Lyrics first.")
        return DesktopLyricsTranslator.translate(lines, targetLanguage, key)
    }
    suspend fun romanizeLyricsWithAi(lines: List<String>): List<String> {
        val key = readSecret(LYRICS_TRANSLATION_API_KEY) ?: error("Add an OpenRouter API key in Settings → Lyrics first.")
        return DesktopLyricsTranslator.translate(lines, "", key, romanize = true)
    }
    fun lyricsProviderOrder(): List<String> {
        val known = listOf("BetterLyrics", "YouLyPlus", "SimpMusic", "LRCLIB")
        val saved = catalogue.getSetting(LYRICS_PROVIDER_ORDER).orEmpty().split(',').filter { it in known }.distinct()
        return saved + known.filterNot { it in saved }
    }
    fun setLyricsProviderOrder(value: List<String>) {
        val known = setOf("BetterLyrics", "YouLyPlus", "SimpMusic", "LRCLIB")
        val normalized = value.filter { it in known }.distinct()
        catalogue.setSetting(LYRICS_PROVIDER_ORDER, (normalized + known.filterNot { it in normalized }).joinToString(","))
    }
    fun cachedLyrics(trackKey: String): CachedDesktopLyrics? = catalogue.cachedLyrics(trackKey)
    fun cacheLyrics(trackKey: String, lyrics: String, provider: String) = catalogue.cacheLyrics(trackKey, lyrics, provider)
    fun deleteCachedLyrics(trackKey: String) = catalogue.deleteCachedLyrics(trackKey)
    fun clearLyricsCache() = catalogue.clearLyricsCache()
    fun lyricsCacheSizeBytes(): Long = catalogue.lyricsCacheSizeBytes()
    fun cachedLyricVariant(trackKey: String, kind: String, target: String, sourceLines: List<String>): CachedLyricVariant? =
        catalogue.cachedLyricVariant(trackKey, kind, normalizeLyricsVariantTarget(kind, target), lyricsSourceHash(sourceLines))
    fun cacheLyricVariant(trackKey: String, kind: String, target: String, sourceLines: List<String>, translatedLines: List<String>) =
        catalogue.cacheLyricVariant(trackKey, kind, normalizeLyricsVariantTarget(kind, target), lyricsSourceHash(sourceLines), translatedLines)

    fun createBackup(destination: Path) {
        catalogue.schemaVersion()
        val includedSettings = Properties()
        backupPreferenceKeys.forEach { key -> preferences.get(key, null)?.let { includedSettings.setProperty(key, it) } }
        BackupArchive.create(catalogueFile(), includedSettings, destination)
    }

    @Synchronized
    fun restoreBackup(source: Path) {
        val directory = appDataDirectory()
        Files.createDirectories(directory)
        val database = catalogueFile()
        val staged = Files.createTempFile(directory, ".xan-restore-", ".sqlite3")
        val rollback = Files.createTempFile(directory, ".xan-rollback-", ".sqlite3")
        val hadDatabase = Files.isRegularFile(database)
        val beforeSettings = backupPreferenceKeys.associateWith { preferences.get(it, null) }
        try {
            val importedSettings = BackupArchive.read(source, staged)
            require(importedSettings.stringPropertyNames().all { it in backupPreferenceKeys }) { "The backup contains unsupported or sensitive settings." }
            val importedCatalogue = CatalogStore(staged)
            require(importedCatalogue.verifyIntegrity()) { "The backup catalogue failed SQLite integrity validation." }
            if (hadDatabase) Files.copy(database, rollback, StandardCopyOption.REPLACE_EXISTING)
            try {
                try {
                    Files.move(staged, database, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(staged, database, StandardCopyOption.REPLACE_EXISTING)
                }
                backupPreferenceKeys.forEach(preferences::remove)
                importedSettings.stringPropertyNames().forEach { key -> preferences.put(key, importedSettings.getProperty(key)) }
            } catch (failure: Exception) {
                if (hadDatabase && Files.exists(rollback)) {
                    try { Files.move(rollback, database, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                    catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(rollback, database, StandardCopyOption.REPLACE_EXISTING) }
                } else if (!hadDatabase) Files.deleteIfExists(database)
                beforeSettings.forEach { (key, value) -> if (value == null) preferences.remove(key) else preferences.put(key, value) }
                throw failure
            }
        } finally {
            Files.deleteIfExists(staged)
            Files.deleteIfExists(rollback)
        }
    }
    fun playerVolume(): Float = preferences.getFloat(PLAYER_VOLUME, 0.8f).coerceIn(0f, 1f)
    fun setPlayerVolume(value: Float) = preferences.putFloat(PLAYER_VOLUME, value.coerceIn(0f, 1f))
    fun equalizerSettings(): EqualizerSettings {
        val gains = preferences.get(EQ_GAINS, "").split(',').mapNotNull(String::toDoubleOrNull)
        return runCatching {
            EqualizerSettings(
                enabled = preferences.getBoolean(EQ_ENABLED, false),
                advanced = preferences.getBoolean(EQ_ADVANCED, false),
                gainsDb = if (gains.size == XanEqualizer.bandDefinitions.size) gains else List(5) { 0.0 },
            )
        }.getOrDefault(EqualizerSettings())
    }
    fun setEqualizerSettings(value: EqualizerSettings) {
        preferences.putBoolean(EQ_ENABLED, value.enabled)
        preferences.putBoolean(EQ_ADVANCED, value.advanced)
        preferences.put(EQ_GAINS, value.gainsDb.joinToString(","))
    }
    fun recognitionHistory(): List<RecognitionHistoryEntry> = recognitionHistoryStore.entries()
    fun recordRecognition(entry: RecognitionHistoryEntry) = recognitionHistoryStore.record(entry)
    fun clearRecognitionHistory() = recognitionHistoryStore.clear()
    fun downloadDirectory(): Path = Path.of(preferences.get(DOWNLOAD_DIR_KEY, "").ifBlank {
        Path.of(System.getProperty("user.home"), "Music", "XAN Downloads").toString()
    })
    private fun audioCacheDirectory(): Path = appDataDirectory().resolve("cache/audio")
    private fun imageCacheDirectory(): Path = appDataDirectory().resolve("cache/artwork")
    fun songCacheLimitMb(): Int = preferences.getInt(SONG_CACHE_LIMIT_MB, 1024).let { if (it == -1) -1 else it.coerceIn(64, 16_384) }
    fun setSongCacheLimitMb(value: Int) {
        require(value == -1 || value in 64..16_384) { "Choose a cache limit between 64 MB and 16 GB, or -1 for unlimited." }
        preferences.putInt(SONG_CACHE_LIMIT_MB, value)
        evictAudioCache(0)
    }
    fun imageCacheLimitMb(): Int = preferences.getInt(IMAGE_CACHE_LIMIT_MB, 512).coerceIn(64, 16_384)
    fun setImageCacheLimitMb(value: Int) {
        require(value in 64..16_384) { "Choose an image cache limit between 64 MB and 16 GB." }
        preferences.putInt(IMAGE_CACHE_LIMIT_MB, value)
        ArtworkCache.applyLimit(imageCacheDirectory(), value * 1024L * 1024L)
    }
    fun loadArtwork(url: String): java.awt.image.BufferedImage? = runCatching {
        ArtworkCache.load(url, imageCacheDirectory(), imageCacheLimitMb() * 1024L * 1024L)
    }.getOrNull()
    fun loadLocalArtwork(audioPath: String): java.awt.image.BufferedImage? = runCatching {
        ArtworkCache.loadLocalAudioArtwork(Path.of(audioPath), imageCacheDirectory(), imageCacheLimitMb() * 1024L * 1024L)
    }.getOrNull()
    fun imageCacheBytes(): Long = ArtworkCache.sizeBytes(imageCacheDirectory())
    fun clearImageCache(): Int = ArtworkCache.clear(imageCacheDirectory())
    fun cachedAudioTracks(): List<CachedCatalogTrack> = catalogue.cachedTracks().filter { entry ->
        val valid = runCatching { Files.isRegularFile(Path.of(entry.path)) }.getOrDefault(false)
        if (!valid) runCatching { catalogue.forgetCachedTrack(entry.track.id) }
        valid
    }
    fun isAudioCached(track: MusicTrack): Boolean = catalogue.cachedTracks().any { it.track.id == toCatalogTrack(track).id && Files.isRegularFile(Path.of(it.path)) }
    fun cachedAudioBytes(): Long = cachedAudioTracks().sumOf { it.sizeBytes }
    fun removeCachedAudioTrack(trackId: String): Boolean {
        val cached = catalogue.forgetCachedTrack(trackId) ?: return false
        Files.deleteIfExists(Path.of(cached.path))
        return true
    }
    fun removeCachedAudioTrack(track: MusicTrack): Boolean = removeCachedAudioTrack(toCatalogTrack(track).id)
    fun clearAudioCache(): Int {
        val cached = catalogue.cachedTracks()
        cached.forEach { entry ->
            catalogue.forgetCachedTrack(entry.track.id)
            Files.deleteIfExists(Path.of(entry.path))
        }
        return cached.size
    }
    fun downloadedFiles(): List<Path> {
        val folder = downloadDirectory()
        if (!Files.isDirectory(folder)) return emptyList()
        return Files.list(folder).use { files ->
            files.filter { Files.isRegularFile(it) && it.fileName.toString().substringAfterLast('.', "").lowercase() in setOf("m4a", "mp3", "opus", "ogg", "webm", "wav", "flac") }
                .sorted(compareByDescending<Path> { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) })
                .toList()
        }
    }
    fun downloadedTracks(): List<MusicTrack> = downloadedFiles().mapNotNull { path ->
        runCatching { playableLocalTrack(path) }.getOrNull()
    }
    fun downloadedBytes(): Long = downloadedFiles().sumOf { path -> runCatching { Files.size(path) }.getOrDefault(0L) }
    fun clearDownloadedFiles(): Int {
        val files = downloadedFiles()
        files.forEach { Files.delete(it) }
        return files.size
    }
    fun savedCollections(kind: String? = null): List<MusicTrack> = preferences.get(SAVED_COLLECTIONS, "")
        .lineSequence()
        .mapNotNull { line ->
            runCatching {
                val fields = line.split('|').map { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }
                require(fields.size == 7)
                MusicTrack(
                    title = fields[1],
                    artist = fields[2],
                    album = fields[3],
                    colors = listOf(XanColors.CardLight, XanColors.Card),
                    motif = "♫",
                    source = "YouTube Music",
                    artworkUrl = fields[4].ifBlank { null },
                    browseId = fields[6].ifBlank { null },
                    collectionId = fields[5].ifBlank { null },
                    kind = fields[0],
                )
            }.getOrNull()
        }
        .filter { kind == null || it.kind == kind }
        .toList()

    fun favoriteTracks(): List<MusicTrack> = catalogue.getSetting("favorite_tracks").orEmpty()
        .lineSequence().filter(String::isNotBlank).mapNotNull { row ->
            runCatching {
                val fields = row.split('|').map { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }
                require(fields.size == 10)
                MusicTrack(
                    title = fields[0], artist = fields[1], album = fields[2],
                    colors = listOf(XanColors.CardLight, XanColors.Card), motif = fields[3],
                    videoId = fields[4].ifBlank { null }, source = fields[5].ifBlank { null },
                    artworkUrl = fields[6].ifBlank { null }, durationSeconds = fields[7].toIntOrNull(),
                    localPath = fields[8].ifBlank { null }, metadataVerified = fields[9].toBooleanStrictOrNull() ?: true,
                )
            }.getOrNull()
        }.toList()

    @Synchronized
    fun toggleFavorite(track: MusicTrack): Boolean {
        require(track.videoId != null || track.localPath != null) { "This item cannot be saved to favorites." }
        val (current, added) = updatedDesktopFavorites(favoriteTracks(), track)
        val encode = { value: String -> Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8)) }
        val serialized = current.joinToString("\n") { item ->
            listOf(item.title, item.artist, item.album, item.motif, item.videoId.orEmpty(), item.source.orEmpty(), item.artworkUrl.orEmpty(), item.durationSeconds?.toString().orEmpty(), item.localPath.orEmpty(), item.metadataVerified.toString())
                .joinToString("|") { encode(it) }
        }
        catalogue.setSetting("favorite_tracks", serialized)
        return added
    }

    @Synchronized
    fun setCollectionSaved(item: MusicTrack, saved: Boolean): Boolean {
        require(item.kind in setOf("album", "playlist", "artist"))
        fun identity(track: MusicTrack): String = when {
            !track.collectionId.isNullOrBlank() -> "id:${track.collectionId}"
            !track.browseId.isNullOrBlank() -> "browse:${track.browseId}"
            else -> "name:${track.title.trim().lowercase()}|${track.artist.trim().lowercase()}"
        }
        val current = savedCollections().toMutableList()
        val key = identity(item)
        val wasSaved = current.any { it.kind == item.kind && identity(it) == key }
        current.removeAll { it.kind == item.kind && identity(it) == key }
        if (saved) current.add(0, item)
        val encode = { value: String -> Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8)) }
        val serialized = current.take(100).joinToString("\n") { collection ->
            listOf(collection.kind, collection.title, collection.artist, collection.album, collection.artworkUrl.orEmpty(), collection.collectionId.orEmpty(), collection.browseId.orEmpty())
                .joinToString("|") { encode(it) }
        }
        if (serialized.isBlank()) preferences.remove(SAVED_COLLECTIONS) else preferences.put(SAVED_COLLECTIONS, serialized)
        return if (saved) !wasSaved else wasSaved
    }

    fun isCollectionSaved(item: MusicTrack): Boolean = savedCollections(item.kind).any { saved ->
        when {
            !item.collectionId.isNullOrBlank() -> saved.collectionId == item.collectionId
            !item.browseId.isNullOrBlank() -> saved.browseId == item.browseId
            else -> saved.title.equals(item.title, ignoreCase = true) && saved.artist.equals(item.artist, ignoreCase = true)
        }
    }

    fun rememberDownloadedCollection(item: MusicTrack) { setCollectionSaved(item, true) }

    fun restoreSavedCollections(collections: List<MusicTrack>) {
        preferences.remove(SAVED_COLLECTIONS)
        collections.asReversed().forEach(::rememberDownloadedCollection)
    }
    fun setDownloadDirectory(path: String) = preferences.put(DOWNLOAD_DIR_KEY, path)
    fun youtubeConnected(): Boolean = readSecret(YOUTUBE_COOKIE_KEY) != null
    fun savedYoutubeChannels(): List<SavedYouTubeChannel> = YouTubeAccountCodec.decode(catalogue.getSetting(YOUTUBE_CHANNELS).orEmpty())
    fun selectedYoutubeChannelId(): String? = catalogue.getSetting(YOUTUBE_DATA_SYNC_ID)?.takeIf(String::isNotBlank)
    fun youtubeLoginForBrowse(): Boolean = useAccountForBrowse(catalogue.getSetting(YOUTUBE_LOGIN_FOR_BROWSE))
    fun setYoutubeLoginForBrowse(value: Boolean) {
        catalogue.setSetting(YOUTUBE_LOGIN_FOR_BROWSE, value.toString())
        YouTube.useLoginForBrowse = value
    }
    fun storedLastFmApiKey(): String = preferences.get(LASTFM_API_KEY, "")
    fun lastFmConnected(): Boolean = !readSecret(LASTFM_SESSION).isNullOrBlank()
    fun lastFmScrobblingEnabled(): Boolean = catalogue.getSetting(LASTFM_SCROBBLING)?.toBooleanStrictOrNull() ?: true
    fun setLastFmScrobblingEnabled(value: Boolean) = catalogue.setSetting(LASTFM_SCROBBLING, value.toString())
    fun listenBrainzConnected(): Boolean = !readSecret(LISTENBRAINZ_TOKEN).isNullOrBlank()
    fun listenBrainzUsername(): String = catalogue.getSetting(LISTENBRAINZ_USERNAME).orEmpty()
    fun listenBrainzScrobblingEnabled(): Boolean = catalogue.getSetting(LISTENBRAINZ_SCROBBLING)?.toBooleanStrictOrNull() ?: true
    fun setListenBrainzScrobblingEnabled(value: Boolean) = catalogue.setSetting(LISTENBRAINZ_SCROBBLING, value.toString())
    fun sponsorBlockEnabled(): Boolean = catalogue.getSetting("sponsorblock_enabled")?.toBooleanStrictOrNull() ?: true
    fun setSponsorBlockEnabled(value: Boolean) = catalogue.setSetting("sponsorblock_enabled", value.toString())
    fun sponsorBlockCategories(): List<String> {
        val selected = catalogue.getSetting("sponsorblock_categories")?.split(',')?.filter(String::isNotBlank)?.toSet()
            ?: SponsorBlockCategories.default.toSet()
        return SponsorBlockCategories.all.filter { it in selected }
    }
    fun setSponsorBlockCategories(categories: Collection<String>) {
        val selected = categories.toSet()
        catalogue.setSetting("sponsorblock_categories", SponsorBlockCategories.all.filter { it in selected }.joinToString(","))
    }

    suspend fun connectListenBrainz(token: String): String = withContext(Dispatchers.IO) {
        val username = ListenBrainzClient.validateToken(token.trim())
        deleteSecret(LISTENBRAINZ_TOKEN)
        saveSecret(LISTENBRAINZ_TOKEN, token.trim())
        catalogue.setSetting(LISTENBRAINZ_USERNAME, username)
        username
    }

    fun disconnectListenBrainz() {
        deleteSecret(LISTENBRAINZ_TOKEN)
        catalogue.setSetting(LISTENBRAINZ_USERNAME, "")
    }

    fun disconnectLastFm() {
        deleteSecret(LASTFM_SESSION)
        deleteSecret(LASTFM_SECRET)
        preferences.remove(LASTFM_API_KEY)
        LastFM.sessionKey = null
    }

    fun hasEnabledScrobblingService(): Boolean =
        (lastFmScrobblingEnabled() && lastFmConnected()) ||
            (listenBrainzScrobblingEnabled() && listenBrainzConnected())
    fun saveYoutubeCookie(cookie: String) {
        require(cookie.contains("SAPISID=") || cookie.contains("__Secure-3PAPISID=")) {
            "This cookie does not look like a signed-in YouTube Music session."
        }
        saveSecret(YOUTUBE_COOKIE_KEY, cookie.trim())
        YouTube.cookie = cookie.trim()
        YouTube.dataSyncId = selectedYoutubeChannelId()
    }
    fun forgetYoutubeAccount(clearSyncedContent: Boolean = false) {
        deleteSecret(YOUTUBE_COOKIE_KEY)
        YouTube.cookie = null
        YouTube.dataSyncId = null
        catalogue.setSetting(YOUTUBE_DATA_SYNC_ID, "")
        catalogue.setSetting(YOUTUBE_CHANNELS, "")
        if (clearSyncedContent) catalogue.clearYouTubeLibrarySnapshots()
    }

    suspend fun youtubeAccountState(): YouTubeAccountState = withContext(Dispatchers.IO) {
        restoreYoutubeSession()
        require(!YouTube.cookie.isNullOrBlank()) { "Connect a YouTube Music account first." }
        val channels = YouTube.getAccountChannels().getOrThrow()
            .mapNotNull { account -> account.dataSyncId?.takeIf(String::isNotBlank)?.let { id -> account.toSavedChannel(id) } }
            .distinctBy(SavedYouTubeChannel::dataSyncId)
        if (channels.isNotEmpty()) catalogue.setSetting(YOUTUBE_CHANNELS, YouTubeAccountCodec.encode(channels))
        if (YouTube.dataSyncId != null && channels.none { it.dataSyncId == YouTube.dataSyncId }) {
            YouTube.dataSyncId = null
            catalogue.setSetting(YOUTUBE_DATA_SYNC_ID, "")
        }
        val profile = YouTube.accountInfo().getOrThrow().toDesktopProfile()
        if (YouTube.dataSyncId == null) {
            val activeChannel = channels.singleOrNull { channel ->
                profile.channelHandle != null && channel.channelHandle.equals(profile.channelHandle, ignoreCase = true)
            } ?: channels.singleOrNull { channel -> channel.name.equals(profile.name, ignoreCase = true) }
            if (activeChannel != null) {
                YouTube.dataSyncId = activeChannel.dataSyncId
                catalogue.setSetting(YOUTUBE_DATA_SYNC_ID, activeChannel.dataSyncId)
            }
        }
        YouTubeAccountState(profile, YouTube.dataSyncId, channels.ifEmpty(::savedYoutubeChannels))
    }

    suspend fun switchYoutubeChannel(dataSyncId: String): YouTubeAccountState = withContext(Dispatchers.IO) {
        restoreYoutubeSession()
        require(!YouTube.cookie.isNullOrBlank()) { "Connect a YouTube Music account first." }
        val channels = YouTube.getAccountChannels().getOrThrow()
            .mapNotNull { account -> account.dataSyncId?.takeIf(String::isNotBlank)?.let { id -> account.toSavedChannel(id) } }
            .distinctBy(SavedYouTubeChannel::dataSyncId)
        val target = channels.firstOrNull { it.dataSyncId == dataSyncId }
            ?: error("That channel is no longer available in this YouTube Music session. Refresh the channel list and try again.")
        val previousDataSyncId = YouTube.dataSyncId
        YouTube.dataSyncId = target.dataSyncId
        try {
            val profile = YouTube.accountInfo().getOrThrow().toDesktopProfile()
            catalogue.setSetting(YOUTUBE_DATA_SYNC_ID, target.dataSyncId)
            catalogue.setSetting(YOUTUBE_CHANNELS, YouTubeAccountCodec.encode(channels))
            YouTubeAccountState(profile, target.dataSyncId, channels)
        } catch (failure: Throwable) {
            YouTube.dataSyncId = previousDataSyncId
            throw failure
        }
    }

    private fun restoreYoutubeSession() {
        YouTube.cookie = readSecret(YOUTUBE_COOKIE_KEY)
        YouTube.dataSyncId = selectedYoutubeChannelId()
        YouTube.useLoginForBrowse = youtubeLoginForBrowse()
    }

    private fun AccountInfo.toDesktopProfile() = YouTubeAccountProfile(name, email, channelHandle, thumbnailUrl)

    private fun AccountInfo.toSavedChannel(dataSyncId: String) = SavedYouTubeChannel(dataSyncId, name, channelHandle, thumbnailUrl)

    suspend fun search(query: String): List<MusicTrack> = withContext(Dispatchers.IO) {
        restoreYoutubeSession()
        val songs = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrThrow().items
        val primary = songs.filterIsInstance<SongItem>()
        val matches = if (primary.isNotEmpty() || !fallbackEnabled()) primary else {
            YouTube.searchYouTubeVideos(query).getOrThrow().items.filterIsInstance<SongItem>()
        }
        matches.distinctBy { it.id }.take(30).mapIndexed { index, song -> song.toTrack(index) }
    }

    suspend fun searchCatalog(query: String): List<MusicTrack> = withContext(Dispatchers.IO) {
        restoreYoutubeSession()
        val local = catalogue.search(query).map(::toMusicTrack)
        val remote = runCatching {
            val items = YouTube.searchSummary(query).getOrThrow().summaries.flatMap { it.items }.toMutableList()
            if (fallbackEnabled() && items.none { it is SongItem }) {
                items += YouTube.searchYouTubeVideos(query).getOrThrow().items.filterIsInstance<SongItem>()
            }
            items.distinctBy { it.id }.take(40).mapIndexedNotNull { index, item ->
                when (item) {
                is SongItem -> item.toTrack(index)
                is ArtistItem -> MusicTrack(item.title, "Artist", "YouTube Music", listOf(XanColors.CardLight, XanColors.Card), "♫", source = "YouTube Music", artworkUrl = item.thumbnail, collectionId = item.id, kind = "artist")
                is AlbumItem -> MusicTrack(item.title, item.artists?.joinToString { it.name } ?: "Album", item.year?.toString() ?: "YouTube Music", listOf(XanColors.CardLight, XanColors.Card), "♫", source = "YouTube Music", artworkUrl = item.thumbnail, browseId = item.browseId, collectionId = item.playlistId, kind = "album")
                is PlaylistItem -> MusicTrack(item.title, item.author?.name ?: "Playlist", item.songCountText ?: "YouTube Music", listOf(XanColors.CardLight, XanColors.Card), "♫", source = "YouTube Music", artworkUrl = item.thumbnail, collectionId = item.id, kind = "playlist")
                else -> null
                }
            }
        }.onFailure { DiagnosticLog.warn(diagnosticsDirectory(), "catalogue_search_failed", "InnerTube catalogue search failed.", it) }.getOrElse { failure ->
            if (local.isEmpty()) throw failure
            emptyList()
        }
        catalogue.upsertTracks(remote.map(::toCatalogTrack))
        (remote + local).distinctBy { it.videoId ?: "${it.kind}:${it.collectionId ?: it.browseId ?: it.title.lowercase()}" }.take(60)
    }

    suspend fun searchExternalVideos(query: String): List<MusicTrack> = withContext(Dispatchers.IO) {
        val normalized = query.trim()
        require(normalized.isNotBlank()) { "Enter a video title or supported video URL." }
        NewPipeExtractor.init()
        val directPage = runCatching { NewPipeExtractor.resolveVideoUrl(normalized).getOrThrow() }.getOrNull()
            ?.takeIf { it.serviceId == NewPipeExtractor.YOUTUBE_SERVICE_ID || otherVideoSourcesEnabled() }
        val directFile = directPage ?: directVideoResult(normalized)
        val results = if (directFile != null) {
            listOf(directFile)
        } else {
            val youtube = YouTube.searchExternalYouTubeVideos(normalized).getOrElse { failure ->
                DiagnosticLog.warn(diagnosticsDirectory(), "video_search_youtube_failed", "YouTube video search failed.", failure)
                emptyList()
            }
            val other = if (otherVideoSourcesEnabled()) {
                NewPipeExtractor.searchVideos(normalized, excludedServiceIds = setOf(NewPipeExtractor.YOUTUBE_SERVICE_ID))
            } else emptyList()
            (youtube + other).distinctBy { it.serviceId to (it.id.ifBlank { it.url }) }.take(60)
        }
        val remote = results.map { result ->
            MusicTrack(
                title = result.title,
                artist = result.uploader.ifBlank { result.serviceName },
                album = result.serviceName,
                colors = listOf(XanColors.CardLight, XanColors.Card),
                motif = "▶",
                source = result.serviceName,
                artworkUrl = result.thumbnailUrl,
                durationSeconds = result.durationSeconds.takeIf { it >= 0 && it <= Int.MAX_VALUE }?.toInt(),
                kind = "video",
                externalVideoResult = result,
            )
        }
        val localVideos = catalogue.search(normalized, 40).map(::toMusicTrack)
            .filter { it.kind == "video" && it.localPath != null }
        (localVideos + remote).distinctBy { it.localPath ?: "${it.externalVideoResult?.serviceId}:${it.externalVideoResult?.id}" }.take(60)
    }

    suspend fun externalVideoInfo(result: ExternalVideoSearchResult): ExternalVideoInfo = withContext(Dispatchers.IO) {
        if (result.serviceId == DIRECT_VIDEO_SERVICE_ID) {
            val extension = result.directFileExtension.orEmpty()
            val mime = result.directFileMimeType ?: videoMimeType(extension)
            ExternalVideoInfo(
                title = result.title,
                uploader = result.uploader,
                durationSeconds = -1,
                thumbnailUrl = result.thumbnailUrl,
                formats = listOf(ExternalVideoFormat(
                    id = "direct-file", url = result.url, mimeType = mime, extension = extension,
                    resolution = "Original file", height = 0, bitrate = 0, videoOnly = false,
                    adaptiveManifest = extension in setOf("m3u8", "mpd"),
                )),
            )
        } else NewPipeExtractor.getVideoInfo(result).getOrThrow()
    }

    fun playableExternalVideoFormats(info: ExternalVideoInfo): List<ExternalVideoFormat> =
        info.formats.filter(::isDesktopPlayableVideoFormat)
            .sortedWith(compareByDescending<ExternalVideoFormat> { it.height }.thenByDescending { it.bitrate })

    suspend fun downloadExternalVideo(track: MusicTrack): Path = withContext(Dispatchers.IO) {
        val result = track.externalVideoResult ?: error("This video source is no longer available.")
        val info = track.externalVideoInfo ?: error("Load the video formats before downloading.")
        val format = track.externalVideoFormat ?: error("Select a video format to download.")
        require(isDesktopPlayableVideoFormat(format)) { "Choose an MP4 or supported adaptive format." }
        if (format.videoOnly || format.adaptiveManifest) {
            val merged = downloadAdaptiveExternalVideo(track, downloadDirectory())
            val saved = MusicTrack(
                title = info.title.ifBlank { track.title }, artist = info.uploader.ifBlank { track.artist },
                album = result.serviceName, colors = listOf(XanColors.CardLight, XanColors.Card), motif = "▶",
                source = result.serviceName, artworkUrl = info.thumbnailUrl ?: result.thumbnailUrl,
                durationSeconds = info.durationSeconds.takeIf { it >= 0 && it <= Int.MAX_VALUE }?.toInt(),
                kind = "video", localPath = merged.toString(),
            )
            catalogue.upsertTracks(listOf(toCatalogTrack(saved)))
            return@withContext merged
        }
        val folder = downloadDirectory()
        Files.createDirectories(folder)
        val safeTitle = track.title.replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]"), "_")
            .trim().trimEnd('.', ' ').take(150).ifBlank { "XAN video" }
        val suffix = MessageDigest.getInstance("SHA-256").digest("${result.serviceId}|${result.id}|${format.id}".toByteArray())
            .take(5).joinToString("") { "%02x".format(it) }
        val extension = format.extension.lowercase(Locale.ROOT).takeIf { it in setOf("mp4", "m4v") } ?: "mp4"
        val destination = folder.resolve("$safeTitle [$suffix].$extension")
        val temporary = folder.resolve(".$safeTitle-$suffix.part")
        val connection = URI(format.url).toURL().openConnection().apply {
            connectTimeout = 20_000
            readTimeout = 45_000
            setRequestProperty("User-Agent", YouTubeClient.USER_AGENT_WEB)
            setRequestProperty("Referer", result.url)
        }
        try {
            connection.getInputStream().use { input -> Files.newOutputStream(temporary).use { output -> input.copyTo(output) } }
            require(Files.size(temporary) > 0) { "The video download was empty." }
            try { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING) }
            val saved = MusicTrack(
                title = info.title.ifBlank { track.title }, artist = info.uploader.ifBlank { track.artist },
                album = result.serviceName, colors = listOf(XanColors.CardLight, XanColors.Card), motif = "▶",
                source = result.serviceName, artworkUrl = info.thumbnailUrl ?: result.thumbnailUrl,
                durationSeconds = info.durationSeconds.takeIf { it >= 0 && it <= Int.MAX_VALUE }?.toInt(),
                kind = "video", localPath = destination.toString(),
            )
            catalogue.upsertTracks(listOf(toCatalogTrack(saved)))
            destination
        } catch (failure: Throwable) {
            Files.deleteIfExists(temporary)
            throw failure
        }
    }

    private fun directVideoResult(query: String): ExternalVideoSearchResult? {
        val uri = runCatching { URI(query.trim()) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        val extension = uri.path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in setOf("mp4", "m4v", "m3u8", "mpd", "webm", "mov")) return null
        val name = uri.path.substringAfterLast('/').substringBeforeLast('.').ifBlank { uri.host }
        return ExternalVideoSearchResult(
            serviceId = DIRECT_VIDEO_SERVICE_ID, serviceName = uri.host, id = query.trim(), url = query.trim(),
            title = java.net.URLDecoder.decode(name, Charsets.UTF_8), uploader = uri.host, durationSeconds = -1,
            thumbnailUrl = null, directFileExtension = extension, directFileMimeType = videoMimeType(extension),
        )
    }

    private fun videoMimeType(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
        "mp4", "m4v", "mov" -> "video/mp4"
        "webm" -> "video/webm"
        "m3u8" -> "application/x-mpegURL"
        "mpd" -> "application/dash+xml"
        else -> "application/octet-stream"
    }

    private fun isDesktopPlayableVideoFormat(format: ExternalVideoFormat): Boolean {
        val extension = format.extension.lowercase(Locale.ROOT)
        val supportedCodec = format.codec.isNullOrBlank() || format.codec.contains("avc", true) || format.codec.contains("h264", true)
        val progressiveMp4 = !format.adaptiveManifest && !format.videoOnly &&
            extension in setOf("mp4", "m4v") && format.mimeType?.startsWith("video/mp4", ignoreCase = true) != false && supportedCodec
        val adaptiveVideo = (format.videoOnly || format.adaptiveManifest) &&
            extension in setOf("mp4", "m4v", "webm", "m3u8", "mpd") && supportedCodec
        return progressiveMp4 || adaptiveVideo
    }

    private suspend fun downloadAdaptiveExternalVideo(
        track: MusicTrack,
        outputDirectory: Path = appDataDirectory().resolve("cache/video"),
    ): Path = adaptiveVideoMutex.withLock { withContext(Dispatchers.IO) {
        val result = track.externalVideoResult ?: error("This video source is no longer available.")
        val format = track.externalVideoFormat ?: error("Select an adaptive format first.")
        val folder = outputDirectory
        Files.createDirectories(folder)
        val safeTitle = track.title.replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]"), "_")
            .trim().trimEnd('.', ' ').take(120).ifBlank { "XAN video" }
        val suffix = MessageDigest.getInstance("SHA-256")
            .digest("${result.serviceId}|${result.id}|${format.id}".toByteArray())
            .take(6).joinToString("") { "%02x".format(it) }
        val cachedVideo = folder.resolve("$safeTitle [$suffix].mp4")
        if (Files.isRegularFile(cachedVideo) && Files.size(cachedVideo) > 0) return@withContext cachedVideo
        val ffmpeg = locateFfmpeg() ?: error("Adaptive video needs FFmpeg installed and available on PATH. Install FFmpeg, restart XAN, and retry.")
        val outputTemplate = folder.resolve("$safeTitle [$suffix].%(ext)s").toString()
        val heightCap = format.height.takeIf { it > 0 }?.let { "[height<=$it]" }.orEmpty()
        val video = "bestvideo[ext=mp4][vcodec^=avc1]$heightCap"
        val audio = "bestaudio[ext=m4a]"
        val progressiveFallback = "best[ext=mp4][vcodec^=avc1]$heightCap"
        val downloader = if (result.serviceId != DIRECT_VIDEO_SERVICE_ID) ensureYtDlp() else null
        val jsRuntime = if (result.serviceId != DIRECT_VIDEO_SERVICE_ID) ensureDeno() else null
        val cookieFile = if (result.serviceId != DIRECT_VIDEO_SERVICE_ID) readSecret(YOUTUBE_COOKIE_KEY)?.let(::writeTemporaryCookieFile) else null
        val directOutput = folder.resolve("$safeTitle [$suffix].preparing.mp4")
        val command = if (result.serviceId == DIRECT_VIDEO_SERVICE_ID) {
            // Manifest codecs may be unknown. Let FFmpeg decode the selected manifest
            // and produce JavaFX-compatible H.264/AAC rather than filtering it out.
            listOf(ffmpeg.toString(), "-nostdin", "-y", "-i", format.url,
                "-map", "0:v:0", "-map", "0:a:0?", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-movflags", "+faststart", directOutput.toString())
        } else buildList {
            addAll(listOf(
                downloader.toString(), "--js-runtimes", "deno:$jsRuntime", "--no-playlist", "--no-progress",
                "-f", "$video+$audio/$progressiveFallback", "--merge-output-format", "mp4",
                "--ffmpeg-location", ffmpeg.parent.toString(), "-o", outputTemplate,
                "--print", "after_move:filepath", result.url,
            ))
            if (cookieFile != null) addAll(listOf("--cookies", cookieFile.toString()))
        }
        var prepared = false
        try {
            val response = ExternalProcessRunner.run(
                command = command,
                timeoutMillis = VIDEO_PREPARATION_TIMEOUT_MILLIS,
                timeoutMessage = "Adaptive video preparation timed out. Check the connection and try again.",
            )
            if (response.exitCode != 0) throw IllegalStateException(
                response.outputTail.lastOrNull { it.contains("ERROR", true) }
                    ?: "yt-dlp could not combine this adaptive video with its audio track."
            )
            if (result.serviceId == DIRECT_VIDEO_SERVICE_ID) {
                require(Files.isRegularFile(directOutput) && Files.size(directOutput) > 0) { "FFmpeg produced no playable video." }
                try { Files.move(directOutput, cachedVideo, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(directOutput, cachedVideo, StandardCopyOption.REPLACE_EXISTING) }
            }
            require(Files.isRegularFile(cachedVideo) && Files.size(cachedVideo) > 0) {
                "Adaptive video preparation finished without a complete MP4 file."
            }
            val output = cachedVideo
            prepared = true
            if (folder == appDataDirectory().resolve("cache/video")) evictPlaybackStaging(folder, output)
            output
        } finally {
            cookieFile?.let { Files.deleteIfExists(it) }
            if (!prepared) Files.list(folder).use { paths ->
                paths.filter(Files::isRegularFile)
                    .filter { it.fileName.toString().contains("[$suffix]") }
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }

    }

    private fun locateFfmpeg(): Path? {
        val executable = if (System.getProperty("os.name").contains("win", true)) "ffmpeg.exe" else "ffmpeg"
        return System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator)
            .asSequence().map { Path.of(it.ifBlank { "." }).resolve(executable) }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
    }

    suspend fun searchExplore(): ExplorePage = withContext(Dispatchers.IO) {
        restoreYoutubeSession()
        YouTube.explore().getOrThrow()
    }

    suspend fun searchMoodAndGenres(): List<MoodAndGenres> = withContext(Dispatchers.IO) {
        restoreYoutubeSession()
        YouTube.moodAndGenres().getOrThrow()
    }

    suspend fun homeFeed(request: HomeFeedRequest = HomeFeedRequest(), sessionRecent: List<MusicTrack> = emptyList()): DesktopHomeFeed = withContext(Dispatchers.IO) {
        restoreYoutubeSession()
        val response = YouTube.home(params = request.params, browseId = request.browseId).getOrThrow()
        val recent = if (listeningHistoryEnabled()) {
            catalogue.recentHistory(30).map { toMusicTrack(it.track) }
                .distinctBy { it.videoId ?: it.localPath ?: "${it.title}:${it.artist}" }
                .take(12)
        } else emptyList()
        val shelves = homeFeedShelves(response.sections)
        DesktopHomeFeed(response.chips.orEmpty(), (recent + sessionRecent).distinctBy { it.videoId ?: it.localPath ?: "${it.title}:${it.artist}" }.take(12), shelves)
    }

    internal fun homeFeedShelves(sections: List<HomePage.Section>): List<Pair<String, List<MusicTrack>>> =
        sections.mapNotNull { section ->
            val items = section.items.mapIndexedNotNull { index, item -> item.toCatalogTrack(index) }
            items.takeIf { it.isNotEmpty() }?.let { section.title to it }
        }

    suspend fun browseDiscovery(endpoint: BrowseEndpoint): DiscoveryBrowse = withContext(Dispatchers.IO) {
        restoreYoutubeSession()
        val result = YouTube.browse(endpoint.browseId, endpoint.params).getOrThrow()
        DiscoveryBrowse(
            title = result.title,
            sections = result.items.mapNotNull { section ->
                val tracks = section.items.mapNotNull { item -> (item as? YTItem)?.toCatalogTrack(0) }
                tracks.takeIf { it.isNotEmpty() }?.let { section.title.orEmpty() to it }
            },
        )
    }

    suspend fun searchSuggestion(query: String, kind: String): MusicTrack? {
        val results = searchCatalog(query)
        return results.firstOrNull { it.kind == kind }
    }

    suspend fun uploadedSongs(): List<MusicTrack> = withContext(Dispatchers.IO) {
        val cookie = readSecret(YOUTUBE_COOKIE_KEY) ?: error("Add your YouTube Music account cookie in Settings → Account to view uploaded songs.")
        restoreYoutubeSession()
        val uploaded = mutableListOf<SongItem>()
        val firstPage = YouTube.library("FEmusic_library_privately_owned_tracks", tabIndex = 1).getOrThrow()
        uploaded += firstPage.items.filterIsInstance<SongItem>()
        var continuation = firstPage.continuation
        var pages = 1
        while (continuation != null && pages < 50 && uploaded.size < 1000) {
            val nextPage = YouTube.libraryContinuation(continuation).getOrThrow()
            uploaded += nextPage.items.filterIsInstance<SongItem>()
            continuation = nextPage.continuation
            pages++
        }
        val tracks = uploaded.distinctBy { it.id }.take(1000).mapIndexed { index, song -> song.toTrack(index) }
        catalogue.upsertTracks(tracks.map(::toCatalogTrack))
        tracks
    }

    suspend fun syncYoutubeMusicLibrary(): YouTubeMusicLibrarySyncResult = withContext(Dispatchers.IO) {
        val account = youtubeAccountState()
        val ownerId = account.selectedDataSyncId
            ?: error("Select a YouTube Music channel in Settings → Account before syncing its library.")
        val syncedAt = System.currentTimeMillis()
        val entries = buildList {
            fetchCompleteYouTubeLibrary("FEmusic_liked_videos").filterIsInstance<SongItem>().forEachIndexed { index, item ->
                add(item.toYouTubeLibraryEntry(ownerId, "song", index, syncedAt))
            }
            fetchCompleteYouTubeLibrary("FEmusic_liked_albums").filterIsInstance<AlbumItem>().forEachIndexed { index, item ->
                add(item.toYouTubeLibraryEntry(ownerId, "album", index, syncedAt))
            }
            fetchCompleteYouTubeLibrary("FEmusic_library_corpus_artists").filterIsInstance<ArtistItem>().forEachIndexed { index, item ->
                add(item.toYouTubeLibraryEntry(ownerId, "artist", index, syncedAt))
            }
            fetchCompleteYouTubeLibrary("FEmusic_liked_playlists").filterIsInstance<PlaylistItem>().forEachIndexed { index, item ->
                add(item.toYouTubeLibraryEntry(ownerId, "playlist", index, syncedAt))
            }
        }
        catalogue.replaceYouTubeLibrary(ownerId, entries, syncedAt)
        YouTubeMusicLibrarySyncResult(
            songs = entries.count { it.kind == "song" },
            albums = entries.count { it.kind == "album" },
            artists = entries.count { it.kind == "artist" },
            playlists = entries.count { it.kind == "playlist" },
            syncedAt = syncedAt,
        )
    }

    /** Reconciles linked, editable YouTube Music playlists against the local playlist contents and order. */
    suspend fun syncLocalPlaylistsToYoutube(): YouTubePlaylistSyncResult = withContext(Dispatchers.IO) {
        restoreYoutubeSession()
        val account = youtubeAccountState()
        val ownerId = account.selectedDataSyncId
            ?: error("Select a YouTube Music channel in Settings → Account before syncing playlists.")

        // Process local playlist deletions only while signed into the account that
        // owned the link, and only if its remote contents still match the saved
        // baseline. A remote edit becomes a conflict instead of a silent deletion.
        catalogue.pendingCloudPlaylistDeletions(ownerId).forEach { deletion ->
            val baseline = deletion.baseline
            if (baseline == null) {
                throw CloudPlaylistConflict("${deletion.name} was deleted locally, but its cloud baseline is missing. The remote playlist was kept.")
            }
            val current = readCloudPlaylistSnapshot(deletion.remoteId, baseline.name)
            if (current == null) {
                catalogue.completeCloudPlaylistDeletion(ownerId, deletion.remoteId)
            } else {
                if (current != baseline) {
                    throw CloudPlaylistConflict("${deletion.name} changed remotely after it was deleted locally. The cloud playlist was kept.")
                }
                verifyCloudPlaylistMutation(
                    before = baseline,
                    expected = null,
                    read = { readCloudPlaylistSnapshot(deletion.remoteId, baseline.name) },
                    edit = {
                        cloudPlaylistRequest(readOnly = false) {
                            YouTube.deletePlaylist(deletion.remoteId).getOrThrow()
                        }
                    },
                    record = { deleted ->
                        if (deleted == null) catalogue.completeCloudPlaylistDeletion(ownerId, deletion.remoteId)
                    },
                )
            }
        }

        val localPlaylists = catalogue.playlists()
        require(localPlaylists.size <= 200) { "This device has more than 200 playlists; sync fewer playlists at a time." }
        var created = 0
        var updated = 0
        var added = 0
        var removed = 0
        var reordered = 0
        var unresolved = 0
        var partial = 0

        localPlaylists.forEach playlistLoop@{ playlist ->
            val localTracks = catalogue.playlistTracks(playlist.id)
            require(localTracks.size <= 2_000) { "${playlist.name} has more than 2,000 songs and cannot be synced in one pass." }
            val unresolvedInPlaylist = localTracks.count { it.videoId.isNullOrBlank() }
            unresolved += unresolvedInPlaylist
            if (unresolvedInPlaylist > 0) {
                // A local-only item has no safe YouTube identity. Keep both copies
                // untouched until every local entry can be matched explicitly.
                partial++
                return@playlistLoop
            }
            val targetVideoIds = localTracks.map { it.videoId!! }
            val localSnapshot = CloudPlaylistSnapshot(playlist.name, targetVideoIds)

            val existingRemoteId = catalogue.youtubePlaylistId(playlist.id, ownerId)
            val remoteId = if (existingRemoteId == null) {
                cloudPlaylistRequest(readOnly = false) { YouTube.createPlaylist(playlist.name) }.also { id ->
                    require(id.isNotBlank()) { "YouTube Music created a playlist without returning its ID." }
                    catalogue.linkYoutubePlaylist(playlist.id, ownerId, id)
                    created++
                }
            } else {
                updated++
                existingRemoteId
            }

            var (remoteSnapshot, remoteTracks) = fetchCloudPlaylist(remoteId, playlist.name)
            var baseline = catalogue.youtubePlaylistSnapshot(playlist.id, ownerId)
            if (existingRemoteId == null && baseline == null) {
                // New cloud playlists start from their actual, observed state. If a
                // create response was lost, a later run will find the local link and
                // refuse to overwrite an unrecognized remote edit.
                catalogue.recordYoutubePlaylistSnapshot(playlist.id, ownerId, remoteSnapshot)
                baseline = remoteSnapshot
            }

            when (cloudPlaylistSyncDecision(baseline, localSnapshot, remoteSnapshot)) {
                CloudPlaylistSyncDecision.IN_SYNC -> {
                    catalogue.recordYoutubePlaylistSnapshot(playlist.id, ownerId, remoteSnapshot)
                    return@playlistLoop
                }
                CloudPlaylistSyncDecision.PULL_REMOTE -> {
                    require(remoteSnapshot.videoIds.distinct().size == remoteSnapshot.videoIds.size) {
                        "${playlist.name} contains duplicate cloud songs that this local playlist cannot represent. Local data was kept."
                    }
                    val remoteCatalogTracks = remoteTracks.mapIndexed { index, song ->
                        toCatalogTrack(song.toTrack(index))
                    }
                    if (!catalogue.applyRemotePlaylistIfUnchanged(
                            playlist.id,
                            ownerId,
                            expectedLocal = localSnapshot,
                            remote = remoteSnapshot,
                            tracks = remoteCatalogTracks,
                        )
                    ) {
                        throw CloudPlaylistConflict("${playlist.name} changed locally while its cloud changes were being imported. Both copies were kept.")
                    }
                    return@playlistLoop
                }
                CloudPlaylistSyncDecision.CONFLICT -> throw CloudPlaylistConflict(
                    "${playlist.name} changed both locally and in YouTube Music, or has no saved sync baseline. Neither copy was overwritten; import or resolve the conflict first."
                )
                CloudPlaylistSyncDecision.PUSH_LOCAL -> Unit
            }

            suspend fun applyCloudEdit(expected: CloudPlaylistSnapshot, edit: suspend () -> Unit) {
                var confirmed: CloudPlaylistSnapshot? = null
                verifyCloudPlaylistMutation(
                    before = remoteSnapshot,
                    expected = expected,
                    read = { readCloudPlaylistSnapshot(remoteId, playlist.name) },
                    edit = edit,
                    record = { actual ->
                        if (actual == null) throw CloudPlaylistConflict("${playlist.name} disappeared while syncing; the local copy was kept.")
                        catalogue.recordYoutubePlaylistSnapshot(playlist.id, ownerId, actual)
                        confirmed = actual
                    },
                )
                remoteSnapshot = confirmed
                    ?: throw CloudPlaylistConflict("${playlist.name} changed while syncing; no confirmed cloud state was saved.")
            }

            if (remoteSnapshot.name != localSnapshot.name) {
                val expected = remoteSnapshot.copy(name = localSnapshot.name)
                applyCloudEdit(expected) {
                    cloudPlaylistRequest(readOnly = false) {
                        YouTube.renamePlaylist(remoteId, localSnapshot.name).getOrThrow()
                    }
                }
            }

            val desiredCounts = targetVideoIds.groupingBy { it }.eachCount()

            // Remove excess copies from the end so prior position tokens remain valid.
            while (true) {
                val (liveSnapshot, liveTracks) = fetchCloudPlaylist(remoteId, playlist.name)
                if (liveSnapshot != remoteSnapshot) throw CloudPlaylistConflict("${playlist.name} changed remotely while syncing; remaining edits were stopped.")
                val seen = mutableMapOf<String, Int>()
                val extraIndex = liveTracks.indices.lastOrNull { index ->
                    val id = liveTracks[index].id
                    val occurrence = seen.getOrDefault(id, 0)
                    seen[id] = occurrence + 1
                    occurrence >= desiredCounts.getOrDefault(id, 0)
                } ?: break
                val song = liveTracks[extraIndex]
                val token = song.setVideoId
                    ?: error("${playlist.name} has a cloud song without a removal token; no changes were made to this playlist.")
                val expected = liveSnapshot.copy(videoIds = liveSnapshot.videoIds.toMutableList().apply { removeAt(extraIndex) })
                applyCloudEdit(expected) {
                    cloudPlaylistRequest(readOnly = false) {
                        YouTube.removeFromPlaylist(remoteId, song.id, token).getOrThrow()
                    }
                }
                removed++
                delay(100)
            }

            desiredCounts.forEach { (videoId, desiredCount) ->
                while (true) {
                    val (liveSnapshot, liveTracks) = fetchCloudPlaylist(remoteId, playlist.name)
                    if (liveSnapshot != remoteSnapshot) throw CloudPlaylistConflict("${playlist.name} changed remotely while syncing; remaining edits were stopped.")
                    if (liveTracks.count { it.id == videoId } >= desiredCount) break
                    val expected = liveSnapshot.copy(videoIds = liveSnapshot.videoIds + videoId)
                    applyCloudEdit(expected) {
                        cloudPlaylistRequest(readOnly = false) {
                            YouTube.addToPlaylist(remoteId, videoId).getOrThrow()
                        }
                    }
                    added++
                    delay(100)
                }
            }

            targetVideoIds.forEachIndexed { targetIndex, targetVideoId ->
                val (liveSnapshot, liveTracks) = fetchCloudPlaylist(remoteId, playlist.name)
                if (liveSnapshot != remoteSnapshot) throw CloudPlaylistConflict("${playlist.name} changed remotely while syncing; remaining edits were stopped.")
                if (liveTracks.getOrNull(targetIndex)?.id == targetVideoId) return@forEachIndexed
                val movingIndex = (targetIndex + 1 until liveTracks.size).firstOrNull { liveTracks[it].id == targetVideoId }
                    ?: throw CloudPlaylistConflict("${playlist.name} could not reconcile a duplicate cloud song; its current order was kept.")
                val moving = liveTracks[movingIndex]
                val successor = liveTracks[targetIndex]
                val movingToken = moving.setVideoId
                    ?: error("${playlist.name} has a cloud song without an ordering token.")
                val successorToken = successor.setVideoId
                    ?: error("${playlist.name} has a cloud song without an ordering token.")
                val expected = liveSnapshot.copy(videoIds = liveSnapshot.videoIds.toMutableList().apply {
                    add(targetIndex, removeAt(movingIndex))
                })
                applyCloudEdit(expected) {
                    cloudPlaylistRequest(readOnly = false) {
                        YouTube.moveSongPlaylist(remoteId, movingToken, successorToken).getOrThrow()
                    }
                }
                reordered++
                delay(100)
            }

            if (remoteSnapshot != localSnapshot) {
                throw CloudPlaylistConflict("${playlist.name} changed while syncing; the last confirmed cloud state was saved for a safe retry.")
            }
            catalogue.linkYoutubePlaylist(playlist.id, ownerId, remoteId)
        }
        YouTubePlaylistSyncResult(created, updated, added, removed, reordered, unresolved, partial)
    }

    private suspend fun fetchCloudPlaylist(
        remoteId: String,
        displayName: String,
    ): Pair<CloudPlaylistSnapshot, MutableList<SongItem>> {
        val firstPage = cloudPlaylistRequest { YouTube.playlist(remoteId).getOrThrow() }
        require(firstPage.playlist.isEditable) { "$displayName is linked to a YouTube Music playlist this account cannot edit." }
        val tracks = fetchYoutubePlaylistSongs(remoteId, displayName, firstPage.songs, firstPage.songsContinuation)
        val name = firstPage.playlist.title.ifBlank { displayName }
        return CloudPlaylistSnapshot(name, tracks.map(SongItem::id)) to tracks
    }

    private suspend fun readCloudPlaylistSnapshot(remoteId: String, displayName: String): CloudPlaylistSnapshot? = try {
        fetchCloudPlaylist(remoteId, displayName).first
    } catch (failure: Exception) {
        if (failure.hasCloudNotFoundCause()) null else throw failure
    }

    private fun Throwable.hasCloudNotFoundCause(): Boolean = generateSequence(this) { it.cause }
        .filterIsInstance<io.ktor.client.plugins.ResponseException>()
        .any { it.response.status.value == 404 }

    private suspend fun fetchYoutubePlaylistSongs(
        playlistId: String,
        playlistName: String,
        firstPageSongs: List<SongItem>? = null,
        firstContinuation: String? = null,
    ): MutableList<SongItem> {
        val songs: MutableList<SongItem>
        var continuation: String?
        if (firstPageSongs == null) {
            val firstPage = YouTube.playlist(playlistId).getOrThrow()
            require(firstPage.playlist.isEditable) { "$playlistName is linked to a YouTube Music playlist this account cannot edit." }
            songs = firstPage.songs.toMutableList()
            continuation = firstPage.songsContinuation
        } else {
            songs = firstPageSongs.toMutableList()
            continuation = firstContinuation
        }
        val seen = mutableSetOf<String>()
        var pageCount = 1
        while (continuation != null) {
            val pageToken = continuation
            require(seen.add(pageToken)) { "$playlistName repeated a cloud playlist page token; sync stopped." }
            require(pageCount < 50) { "$playlistName exceeds the 50-page playlist sync limit." }
            val nextPage = cloudPlaylistRequest { YouTube.playlistContinuation(pageToken).getOrThrow() }
            songs += nextPage.songs
            require(songs.size <= 5_000) { "$playlistName exceeds the 5,000-song playlist sync limit." }
            continuation = nextPage.continuation
            pageCount++
        }
        return songs
    }

    /** Imports or refreshes a local copy of an editable playlist from the selected YouTube Music channel. */
    suspend fun importYoutubePlaylist(collection: MusicTrack): CatalogPlaylist = withContext(Dispatchers.IO) {
        require(collection.kind == "playlist") { "Choose a YouTube Music playlist to import." }
        restoreYoutubeSession()
        val account = youtubeAccountState()
        val ownerId = account.selectedDataSyncId
            ?: error("Select a YouTube Music channel in Settings → Account before importing playlists.")
        val remoteId = (collection.collectionId ?: collection.browseId)?.takeIf(String::isNotBlank)
            ?: error("This playlist does not have a YouTube Music playlist ID.")
        val firstPage = cloudPlaylistRequest { YouTube.playlist(remoteId).getOrThrow() }
        val songs = firstPage.songs.toMutableList()
        var continuation = firstPage.songsContinuation
        val seen = mutableSetOf<String>()
        var pageCount = 1
        while (continuation != null) {
            val pageToken = continuation
            require(seen.add(pageToken)) { "YouTube Music repeated a playlist page token; import stopped to avoid duplicate songs." }
            require(pageCount < 50) { "This playlist exceeds the 50-page import limit." }
            val page = cloudPlaylistRequest { YouTube.playlistContinuation(pageToken).getOrThrow() }
            songs += page.songs
            require(songs.size <= 5_000) { "This playlist exceeds the 5,000-song import limit." }
            continuation = page.continuation
            pageCount++
        }

        val existing = if (firstPage.playlist.isEditable) catalogue.playlists().firstOrNull { playlist ->
            catalogue.youtubePlaylistId(playlist.id, ownerId) == remoteId
        } else null
        val local = existing?.also { playlist ->
            if (playlist.name != collection.title) catalogue.updatePlaylistMetadata(playlist.id, collection.title, playlist.description)
        } ?: catalogue.createPlaylist(collection.title, "Imported from YouTube Music", "youtube_music")
        require(songs.all { it.id.isNotBlank() } && songs.map(SongItem::id).distinct().size == songs.size) {
            "This playlist contains duplicate or missing song IDs that the local playlist format cannot preserve."
        }
        val tracks = songs.mapIndexed { index, song -> toCatalogTrack(song.toTrack(index)) }
        catalogue.replacePlaylistTracks(local.id, tracks)
        if (firstPage.playlist.isEditable) {
            catalogue.linkYoutubePlaylist(local.id, ownerId, remoteId)
            catalogue.recordYoutubePlaylistSnapshot(
                local.id,
                ownerId,
                CloudPlaylistSnapshot(firstPage.playlist.title.ifBlank { collection.title }, songs.map(SongItem::id)),
            )
        }
        catalogue.playlists().first { it.id == local.id }
    }

    fun youtubeMusicLibraryItems(): List<MusicTrack> {
        val ownerId = selectedYoutubeChannelId() ?: return emptyList()
        return catalogue.youtubeLibrary(ownerId).map { it.toMusicTrack() }
    }

    fun youtubeMusicLibrarySync(): YouTubeLibrarySync? =
        selectedYoutubeChannelId()?.let(catalogue::youtubeLibrarySync)

    private suspend fun fetchCompleteYouTubeLibrary(browseId: String): List<YTItem> {
        val items = mutableListOf<YTItem>()
        val seenContinuations = mutableSetOf<String>()
        val firstPage = YouTube.library(browseId).getOrThrow()
        items += firstPage.items
        var continuation = firstPage.continuation
        var pageCount = 1
        while (continuation != null) {
            require(seenContinuations.add(continuation)) { "YouTube Music repeated a library page token; sync stopped to avoid duplicating results." }
            require(pageCount < 50) { "This YouTube Music library exceeds the 50-page sync limit. The previous local sync was kept." }
            val nextPage = YouTube.libraryContinuation(continuation).getOrThrow()
            items += nextPage.items
            require(items.size <= 5_000) { "This YouTube Music library exceeds the 5,000-item sync limit. The previous local sync was kept." }
            continuation = nextPage.continuation
            pageCount++
            delay(80)
        }
        return items
    }

    private fun YTItem.toYouTubeLibraryEntry(ownerId: String, kind: String, position: Int, syncedAt: Long): YouTubeLibraryEntry = when (this) {
        is SongItem -> YouTubeLibraryEntry(
            ownerId, id, kind, title, artists.joinToString(", ") { it.name }.ifBlank { "YouTube Music" },
            album?.name ?: "YouTube Music", thumbnail, videoId = id,
            artistId = artists.firstOrNull()?.id, albumId = album?.id, durationSeconds = duration,
            position = position, syncedAt = syncedAt,
        )
        is AlbumItem -> YouTubeLibraryEntry(
            ownerId, id, kind, title, artists.orEmpty().joinToString(", ") { it.name }.ifBlank { "Album" },
            year?.toString() ?: "YouTube Music", thumbnail, browseId = browseId, collectionId = playlistId,
            position = position, syncedAt = syncedAt,
        )
        is ArtistItem -> YouTubeLibraryEntry(
            ownerId, id, kind, title, "Artist", "YouTube Music", thumbnail,
            browseId = id, collectionId = id, position = position, syncedAt = syncedAt,
        )
        is PlaylistItem -> YouTubeLibraryEntry(
            ownerId, id, kind, title, author?.name ?: "Playlist", songCountText ?: "YouTube Music",
            thumbnail, browseId = id, collectionId = id, position = position, syncedAt = syncedAt,
        )
    }

    private fun YouTubeLibraryEntry.toMusicTrack() = MusicTrack(
        title = title, artist = artist, album = album,
        colors = listOf(XanColors.CardLight, XanColors.Card), motif = "♫",
        source = "YouTube Music", artworkUrl = artworkUrl, durationSeconds = durationSeconds,
        videoId = videoId, artistId = artistId, albumId = albumId,
        browseId = browseId, collectionId = collectionId, kind = kind,
    )

    fun catalogTracks(query: String = "", limit: Int = 100): List<MusicTrack> =
        if (query.isBlank()) catalogue.allLocalTracks(limit).map(::toMusicTrack)
        else catalogue.search(query, limit).map(::toMusicTrack)
    fun playableLocalTrack(path: Path): MusicTrack = toMusicTrack(readLocalTrack(path))

    fun addMusicFolder(path: Path) = catalogue.addMusicFolder(path)
    fun removeMusicFolder(path: Path) {
        val root = path.toAbsolutePath().normalize()
        catalogue.removeMusicFolder(root)
        catalogue.removeTracks(catalogue.allLocalTracks(5000).filter { track ->
            track.localPath?.let { Path.of(it).startsWith(root) } == true
        }.map { it.id })
    }
    fun musicFolders(): List<Path> = catalogue.musicFolders()
    fun startLocalMusicWatcher() = localMusicWatcher.start()
    fun refreshLocalMusicWatcher() = localMusicWatcher.refresh()
    fun stopLocalMusicWatcher() = localMusicWatcher.close()
    fun playlists(): List<CatalogPlaylist> = catalogue.playlists()
    fun movePlaylist(playlistId: String, to: Int): Boolean = catalogue.movePlaylist(playlistId, to)
    fun createPlaylist(name: String, description: String? = null): CatalogPlaylist = catalogue.createPlaylist(name, description)
    fun updatePlaylistMetadata(playlistId: String, name: String, description: String?) =
        catalogue.updatePlaylistMetadata(playlistId, name, description)
    fun deletePlaylist(playlistId: String) = catalogue.deletePlaylist(playlistId)
    fun playlistTracks(playlistId: String): List<MusicTrack> = catalogue.playlistTracks(playlistId).map(::toMusicTrack)
    fun addTrackToPlaylist(playlistId: String, track: MusicTrack): Boolean = catalogue.addPlaylistTrack(playlistId, toCatalogTrack(track))
    fun removeTrackFromPlaylist(playlistId: String, track: MusicTrack): Boolean = catalogue.removePlaylistTrack(playlistId, toCatalogTrack(track).id)
    fun movePlaylistTrack(playlistId: String, from: Int, to: Int): Boolean = catalogue.movePlaylistTrack(playlistId, from, to)
    fun importPlaylistFile(path: Path): PlaylistImportReport {
        require(Files.isRegularFile(path)) { "Choose an existing M3U, M3U8, or CSV playlist file." }
        val extension = path.fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(extension in setOf("m3u", "m3u8", "csv")) { "XAN supports M3U, M3U8, and CSV playlist files." }
        val text = Files.readString(path, Charsets.UTF_8)
        val entries = when (extension) {
            "m3u", "m3u8" -> PlaylistImport.parseM3u(text)
            else -> PlaylistImport.parseCsv(text)
        }
        val imported = mutableListOf<MusicTrack>()
        val unresolved = mutableListOf<String>()
        entries.forEach { entry ->
            val localPath = PlaylistImport.resolveLocal(entry, path.toAbsolutePath().parent)
            val track = if (localPath != null) runCatching { playableLocalTrack(localPath) }.getOrNull()
            else matchImportedEntry(entry)
            if (track != null) imported += track
            else unresolved += buildString {
                append("Row ").append(entry.row).append(": ")
                append(entry.title ?: entry.location ?: "unidentified track")
                entry.artist?.let { append(" — ").append(it) }
            }
        }
        val safeName = path.fileName.toString().substringBeforeLast('.').ifBlank { "Imported playlist" }
        val playlist = catalogue.createPlaylist(safeName, "Imported from ${path.fileName}", "import")
        catalogue.replacePlaylistTracks(playlist.id, imported.map(::toCatalogTrack))
        return PlaylistImportReport(playlist.name, imported, unresolved)
    }

    private fun matchImportedEntry(entry: ImportedPlaylistEntry): MusicTrack? {
        val query = (entry.title ?: entry.isrc ?: entry.artist ?: entry.album).orEmpty().trim()
        if (query.isBlank()) return null
        val candidates = catalogue.search(query, 200).filter { it.kind == "song" }
        val title = entry.title?.normalizeImportedText()
        val artist = entry.artist?.normalizeImportedText()
        val album = entry.album?.normalizeImportedText()
        val matched = candidates.firstOrNull { candidate ->
            val titleMatch = title != null && candidate.title.normalizeImportedText() == title
            val artistMatch = artist == null || candidate.artist.normalizeImportedText().split(", ").any { it == artist }
            val albumMatch = album == null || candidate.album.isBlank() || candidate.album.normalizeImportedText() == album
            val durationMatch = entry.durationSeconds == null || candidate.durationSeconds == null ||
                kotlin.math.abs(candidate.durationSeconds - entry.durationSeconds) <= 3
            titleMatch && artistMatch && albumMatch && durationMatch
        }
        return matched?.let(::toMusicTrack)
    }

    fun addLocalLibraryToPlaylist(playlistId: String) {
        val existing = catalogue.playlistTracks(playlistId)
        val additions = catalogue.allLocalTracks(5000)
        catalogue.replacePlaylistTracks(playlistId, (existing + additions).distinctBy { it.id })
    }

    fun scanMusicLibrary(): Int {
        val roots = musicFolders()
        var scanned = 0
        val supported = setOf("mp3", "flac", "ogg", "opus", "m4a", "mp4", "aif", "aiff", "wav", "wma", "dsf", "dsd")
        roots.forEach { root ->
            require(Files.isDirectory(root)) { "Music folder is no longer available: $root" }
            val discovered = Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT) in supported }
                    .toList()
            }
            val tracks = discovered.map { path -> readLocalTrack(path) }
            catalogue.upsertTracks(tracks)
            val present = tracks.mapTo(hashSetOf()) { it.id }
            val removed = catalogue.allLocalTracks(5000).filter { track ->
                val localPath = track.localPath?.let(Path::of) ?: return@filter false
                localPath.startsWith(root) && track.id !in present
            }.map { it.id }
            catalogue.removeTracks(removed)
            scanned += tracks.size
        }
        return scanned
    }

    private fun readLocalTrack(path: Path): CatalogTrack {
        val canonical = path.toAbsolutePath().normalize()
        val fallback = canonical.fileName.toString().substringBeforeLast('.')
        val file = runCatching { AudioFileIO.read(canonical.toFile()) }.getOrNull()
        val tag = file?.tag
        val taggedTitle = tag?.getFirst(FieldKey.TITLE)?.trim().orEmpty()
        val taggedArtist = tag?.getFirst(FieldKey.ARTIST)?.trim().orEmpty()
        val title = taggedTitle.ifBlank { fallback.substringAfter(" - ", fallback).trim() }
        val artist = taggedArtist.ifBlank {
            fallback.substringBefore(" - ").takeIf { " - " in fallback }?.trim().orEmpty()
        }
        val album = tag?.getFirst(FieldKey.ALBUM)?.trim().orEmpty()
        val yearText = tag?.getFirst(FieldKey.YEAR)?.trim().orEmpty()
        val duration = file?.audioHeader?.trackLength?.takeIf { it > 0 }
        val pathKey = canonical.toString()
        return CatalogTrack(
            id = "local:$pathKey", title = title.ifBlank { fallback }, artist = artist,
            album = album, durationSeconds = duration, source = "local", sourceId = pathKey,
            localPath = pathKey, year = yearText.toIntOrNull(),
            metadataVerified = taggedTitle.isNotBlank() && taggedArtist.isNotBlank(),
            addedAt = runCatching { Files.getLastModifiedTime(canonical).toMillis() }.getOrDefault(System.currentTimeMillis()),
        )
    }

    private fun toCatalogTrack(track: MusicTrack): CatalogTrack {
        val source = track.source?.lowercase(Locale.ROOT)?.replace(' ', '_') ?: "youtube_music"
        val sourceId = track.externalVideoResult?.let(::encodeExternalVideoIdentity)
            ?: track.videoId ?: track.localPath ?: track.collectionId ?: track.browseId ?: track.title
        val id = when {
            !track.videoId.isNullOrBlank() -> "$source:song:${track.videoId}"
            !track.localPath.isNullOrBlank() -> "local:${track.localPath}"
            else -> "$source:${track.kind}:${track.collectionId ?: track.browseId ?: track.title.lowercase(Locale.ROOT)}"
        }
        return CatalogTrack(
            id = id, title = track.title, artist = track.artist, album = track.album,
            durationSeconds = track.durationSeconds, artworkUrl = track.artworkUrl, source = source,
            sourceId = sourceId, videoId = track.videoId, artistId = track.artistId,
            albumId = track.albumId, browseId = track.browseId, collectionId = track.collectionId,
            kind = track.kind, localPath = track.localPath, metadataVerified = track.metadataVerified,
        )
    }

    fun toMusicTrack(track: CatalogTrack) = MusicTrack(
        title = track.title, artist = track.artist, album = track.album,
        colors = listOf(XanColors.CardLight, XanColors.Card), motif = "♫",
        videoId = track.videoId, source = track.source.replace('_', ' '), artworkUrl = track.artworkUrl,
        durationSeconds = track.durationSeconds, artistId = track.artistId, albumId = track.albumId,
        browseId = track.browseId, collectionId = track.collectionId, kind = track.kind,
        localPath = track.localPath,
        metadataVerified = track.metadataVerified,
        externalVideoResult = if (track.kind == "video" && track.localPath == null) {
            decodeExternalVideoIdentity(track.sourceId.orEmpty(), track.title, track.artist, track.durationSeconds, track.artworkUrl)
        } else null,
    )

    private fun encodeExternalVideoIdentity(result: ExternalVideoSearchResult): String {
        val encode = { value: String -> Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8)) }
        return "xan_external_video_v1:" + listOf(
            result.serviceId.toString(), result.serviceName, result.id, result.url,
            result.directFileExtension.orEmpty(), result.directFileMimeType.orEmpty(),
        ).joinToString("|") { encode(it) }
    }

    private fun decodeExternalVideoIdentity(
        encoded: String,
        title: String,
        uploader: String,
        durationSeconds: Int?,
        thumbnailUrl: String?,
    ): ExternalVideoSearchResult? = runCatching {
        val payload = encoded.removePrefix("xan_external_video_v1:")
        require(payload != encoded)
        val fields = payload.split('|').map { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }
        require(fields.size == 6)
        ExternalVideoSearchResult(
            serviceId = fields[0].toInt(), serviceName = fields[1], id = fields[2], url = fields[3],
            title = title, uploader = uploader, durationSeconds = durationSeconds?.toLong() ?: -1L,
            thumbnailUrl = thumbnailUrl, directFileExtension = fields[4].ifBlank { null },
            directFileMimeType = fields[5].ifBlank { null },
        )
    }.getOrNull()

    suspend fun resolveTrackUri(track: MusicTrack): URI = withContext(Dispatchers.IO) {
        track.localPath?.let { path ->
            val file = Path.of(path)
            require(Files.isRegularFile(file)) { "The local audio file cannot be found." }
            return@withContext file.toUri()
        }
        if (track.kind == "video") {
            val format = track.externalVideoFormat ?: error("Select a supported video format before playback.")
            require(isDesktopPlayableVideoFormat(format)) { "This video format is not supported by the desktop player." }
            if (format.videoOnly || format.adaptiveManifest) return@withContext downloadAdaptiveExternalVideo(track).toUri()
            return@withContext URI(format.url)
        }
        val videoId = track.videoId ?: error("This item does not have a playable audio source.")
        val cacheId = toCatalogTrack(track).id
        catalogue.touchCachedTrack(cacheId)?.let { cached ->
            val file = Path.of(cached.path)
            if (Files.isRegularFile(file)) return@withContext file.toUri()
            runCatching { catalogue.forgetCachedTrack(cacheId) }
        }
        restoreYoutubeSession()
        var innerTubeFailure: Throwable? = null
        val directStream = try {
            val response = YouTube.player(videoId, client = YouTubeClient.WEB_REMIX).getOrThrow()
            val audio = selectDesktopPlayableAudioFormat(response.streamingData?.adaptiveFormats) {
                YouTube.newPipePlayer(videoId, response)?.streamingData?.adaptiveFormats
            }
                ?: error("InnerTube returned no AAC audio stream that the desktop player can decode.")
            URI(requireNotNull(audio.url))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            innerTubeFailure = failure
            null
        }
        if (directStream != null) return@withContext directStream

        val fallback = try {
            resolveWithYtDlp(videoId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fallbackFailure: Throwable) {
            DiagnosticLog.warn(diagnosticsDirectory(), "stream_resolution_failed", "InnerTube and the configured playback fallback could not resolve audio.", fallbackFailure, mapOf("source" to (track.source ?: "unknown")))
            throw IllegalStateException(
                "XAN could not resolve this song for playback. ${fallbackFailure.message ?: innerTubeFailure?.message.orEmpty()}".trim(),
                fallbackFailure,
            )
        }
        return@withContext fallback
    }

    private suspend fun resolveWithYtDlp(videoId: String): URI {
        val downloader = ensureYtDlp()
        val runtime = ensureDeno()
        val cookie = readSecret(YOUTUBE_COOKIE_KEY)
        val cookieFile = cookie?.let(::writeTemporaryCookieFile)
        try {
            val command = buildList {
                add(downloader.toString())
                addAll(listOf("--js-runtimes", "deno:$runtime", "--no-playlist", "--quiet", "--no-warnings", "--no-progress", "--format", "bestaudio[ext=m4a][acodec^=mp4a]/bestaudio[acodec^=mp4a]", "--get-url"))
                cookieFile?.let { addAll(listOf("--cookies", it.toString())) }
                add("https://www.youtube.com/watch?v=$videoId")
            }
            val result = ExternalProcessRunner.run(
                command = command,
                timeoutMillis = 60_000,
                timeoutMessage = "Playback stream resolution timed out. Check your connection and try again.",
            )
            check(result.exitCode == 0) {
                result.outputTail.lastOrNull().orEmpty().ifBlank { "yt-dlp could not resolve a compatible AAC audio stream." }
            }
            val stream = result.outputTail.firstOrNull { it.startsWith("https://") }
                ?: error("yt-dlp returned no secure AAC audio stream URL.")
            return URI(stream)
        } finally {
            cookieFile?.let { Files.deleteIfExists(it) }
        }
    }

    fun recordListening(track: MusicTrack, listenedMillis: Long, playedAtSeconds: Long = System.currentTimeMillis() / 1000) {
        val record = toCatalogTrack(track)
        catalogue.upsertTracks(listOf(record))
        catalogue.recordPlay(record.id, playedAtSeconds * 1000, listenedMillis.coerceAtLeast(0))
    }

    suspend fun artistDetails(artist: MusicTrack): ArtistDetails = withContext(Dispatchers.IO) {
        val browseId = artist.collectionId ?: error("This artist is missing its YouTube Music ID.")
        restoreYoutubeSession()
        val page = YouTube.artist(browseId).getOrThrow()
        val sections = page.sections.mapNotNull { section ->
            val items = section.items.toMutableList()
            var continuation = section.moreEndpoint?.let { endpoint ->
                runCatching { YouTube.artistItems(endpoint).getOrThrow() }.onSuccess { items += it.items }
                    .getOrNull()?.continuation
            }
            while (continuation != null && items.size < 60) {
                val next = runCatching { YouTube.artistItemsContinuation(continuation!!).getOrThrow() }.getOrNull() ?: break
                items += next.items
                continuation = next.continuation
            }
            val tracks = items.distinctBy { it.id }.mapIndexedNotNull { index, item -> item.toCatalogTrack(index) }
            tracks.takeIf { it.isNotEmpty() }?.let { ArtistOfferSection(section.title, it) }
        }
        ArtistDetails(
            description = page.description?.replace(Regex("\\s+"), " ")?.trim()?.takeIf(String::isNotBlank),
            subscriberCount = page.subscriberCountText,
            monthlyListeners = page.monthlyListenerCount,
            sections = sections,
        )
    }

    suspend fun collectionDetails(item: MusicTrack): CollectionDetails = withContext(Dispatchers.IO) {
        restoreYoutubeSession()
        var description: String? = null
        val tracks = when (item.kind) {
            "album" -> {
                val page = YouTube.album(item.browseId ?: error("This album is missing its YouTube Music browse ID."), withSongs = true).getOrThrow()
                description = page.description?.replace(Regex("\\s+"), " ")?.trim()?.takeIf(String::isNotBlank)
                page.songs.mapIndexed { index, song -> song.toTrack(index) }
            }
            "playlist" -> {
                val id = item.collectionId ?: error("This playlist is missing its YouTube Music ID.")
                val page = YouTube.playlist(id).getOrThrow()
                val songs = page.songs.toMutableList()
                var continuation = page.songsContinuation ?: page.continuation
                while (continuation != null && songs.size < 500) {
                    val next = YouTube.playlistContinuation(continuation).getOrThrow()
                    songs += next.songs
                    continuation = next.continuation
                }
                songs.distinctBy { it.id }.mapIndexed { index, song -> song.toTrack(index) }
            }
            else -> error("Only album and playlist pages have collection details.")
        }
        CollectionDetails(description = description, tracks = tracks)
    }

    suspend fun importSpotifyPlaylist(url: String): SpotifyPublicPlaylist =
        SpotifyPublicPlaylistClient.fetch(url)

    suspend fun migrateSpotifyPlaylist(playlist: SpotifyPublicPlaylist): SpotifyPlaylistImportReport {
        val matches = mutableListOf<Pair<com.music.spotify.SpotifyPublicTrack, MusicTrack>>()
        val unmatched = mutableListOf<com.music.spotify.SpotifyPublicTrack>()
        playlist.tracks.forEach { source ->
            val candidate = runCatching {
                search("${source.title} ${source.artist}").firstOrNull { isSpotifyTrackMatch(source, it) }
            }.getOrNull()
            if (candidate == null) unmatched += source else matches += source to candidate
        }
        val saved = matches.map { it.second }.distinctBy { it.videoId ?: it.title.normalizeImportedText() }
        val catalogPlaylist = catalogue.createPlaylist(playlist.name, "Imported from Spotify public playlist ${playlist.id}", "spotify")
        catalogue.replacePlaylistTracks(catalogPlaylist.id, saved.map(::toCatalogTrack))
        return SpotifyPlaylistImportReport(playlist, catalogPlaylist.id, matches, unmatched)
    }

    suspend fun download(track: MusicTrack): Path = downloadTo(track, downloadDirectory())

    internal suspend fun downloadPlaybackFallback(track: MusicTrack): Path = withContext(Dispatchers.IO) {
        require(!track.videoId.isNullOrBlank()) { "This item does not have a YouTube audio source." }
        val directory = appDataDirectory().resolve("cache/playback")
        Files.createDirectories(directory)
        val downloaded = downloadTo(track, directory)
        require(Files.isRegularFile(downloaded) && Files.size(downloaded) > 0) { "yt-dlp did not produce a playable audio file." }
        evictPlaybackStaging(directory, downloaded)
        downloaded
    }

    internal fun clearPlaybackFallback(videoId: String) {
        val directory = appDataDirectory().resolve("cache/playback")
        if (!Files.isDirectory(directory)) return
        Files.list(directory).use { files ->
            files.filter(Files::isRegularFile)
                .filter { it.fileName.toString().contains("[$videoId]") }
                .forEach { path -> Files.deleteIfExists(path) }
        }
    }

    private fun evictPlaybackStaging(directory: Path, keep: Path) {
        Files.list(directory).use { files ->
            files.filter(Files::isRegularFile)
                .filter { it != keep }
                .sorted(Comparator.comparingLong<Path> { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }.reversed())
                .skip(7)
                .forEach { path -> runCatching { Files.deleteIfExists(path) } }
        }
    }

    suspend fun cacheAudioTrack(track: MusicTrack): Path = audioCacheMutex.withLock { withContext(Dispatchers.IO) {
        require(!track.videoId.isNullOrBlank()) { "Only YouTube Music tracks can be cached for offline playback." }
        val record = toCatalogTrack(track)
        catalogue.touchCachedTrack(record.id)?.let { cached ->
            val existing = Path.of(cached.path)
            if (Files.isRegularFile(existing)) return@withContext existing
            runCatching { catalogue.forgetCachedTrack(record.id) }
        }
        val folder = audioCacheDirectory()
        Files.createDirectories(folder)
        val destination = downloadTo(track, folder)
        val size = Files.size(destination)
        val limit = songCacheLimitMb()
        val limitBytes = if (limit < 0) Long.MAX_VALUE else limit * 1024L * 1024L
        if (size > limitBytes) {
            Files.deleteIfExists(destination)
            error("This track is larger than the current XAN song cache limit.")
        }
        evictAudioCache(size)
        if (limit >= 0 && catalogue.cachedTracks().sumOf { it.sizeBytes } + size > limitBytes) {
            Files.deleteIfExists(destination)
            error("The XAN song cache could not make enough space for this track.")
        }
        catalogue.recordCachedTrack(record, destination.toString(), size)
        evictAudioCache(0)
        if (!Files.isRegularFile(destination) || catalogue.touchCachedTrack(record.id) == null) {
            error("The song cache limit changed before this track could be kept.")
        }
        destination
    } }

    @Synchronized
    private fun evictAudioCache(incomingBytes: Long) {
        val limit = songCacheLimitMb()
        if (limit < 0) return
        val limitBytes = limit * 1024L * 1024L
        val cached = catalogue.cachedTracks()
        cached.forEach { entry ->
            if (!Files.isRegularFile(Path.of(entry.path))) {
                runCatching { catalogue.forgetCachedTrack(entry.track.id) }
            }
        }
        val current = catalogue.cachedTracks().filter { Files.isRegularFile(Path.of(it.path)) }
        audioCacheEvictions(current, limitBytes, incomingBytes).forEach { entry ->
            val removed = catalogue.forgetCachedTrack(entry.track.id) ?: return@forEach
            Files.deleteIfExists(Path.of(removed.path))
        }
    }

    private suspend fun downloadTo(track: MusicTrack, folder: Path): Path = withContext(Dispatchers.IO) {
        val id = track.videoId ?: error("This result does not have a downloadable YouTube track ID.")
        Files.createDirectories(folder)
        val downloader = ensureYtDlp()
        val jsRuntime = ensureDeno()
        val cookieFile = readSecret(YOUTUBE_COOKIE_KEY)?.let(::writeTemporaryCookieFile)
        val url = "https://music.youtube.com/watch?v=$id"
        val output = folder.resolve("%(title).180B [%(id)s].%(ext)s").toString()
        val command = buildList {
            addAll(listOf(
            downloader.toString(), "--js-runtimes", "deno:${jsRuntime}", "--no-playlist", "--newline", "-f", "bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]",
            "-o", output, "--print", "after_move:filepath"))
            if (cookieFile != null) addAll(listOf("--cookies", cookieFile.toString()))
            add(url)
        }
        try {
            val result = ExternalProcessRunner.run(
                command = command,
                timeoutMillis = DOWNLOAD_PROCESS_TIMEOUT_MILLIS,
                timeoutMessage = "The download timed out. Check your connection and try again.",
            )
            if (result.exitCode != 0) throw IllegalStateException(
                result.outputTail.lastOrNull { it.contains("ERROR", true) }
                    ?: "yt-dlp failed with exit code ${result.exitCode}."
            )
            val pathLine = result.outputTail.lastOrNull { it.startsWith(folder.toString()) }
            pathLine?.let(Path::of)?.takeIf(Files::exists)
                ?: Files.list(folder).use { files ->
                    files.filter { it.fileName.toString().contains("[$id]") }
                        .max(java.util.Comparator.comparingLong { Files.getLastModifiedTime(it).toMillis() })
                        .orElseThrow { IllegalStateException("Download finished but the audio file was not found.") }
                }
        } finally {
            cookieFile?.let { Files.deleteIfExists(it) }
        }
    }

    private const val DOWNLOAD_PROCESS_TIMEOUT_MILLIS = 30 * 60 * 1000L
    const val VIDEO_PREPARATION_TIMEOUT_MILLIS = 30 * 60 * 1000L

    suspend fun downloadCollection(item: MusicTrack): Int {
        require(item.kind != "song" && !item.collectionId.isNullOrBlank()) { "Choose an artist, album, or playlist result." }
        val songs = withContext(Dispatchers.IO) {
            restoreYoutubeSession()
            when (item.kind) {
                "album" -> YouTube.albumSongs(item.collectionId).getOrThrow()
                "playlist" -> {
                    val page = YouTube.playlist(item.collectionId).getOrThrow()
                    val all = page.songs.toMutableList()
                    var continuation = page.songsContinuation ?: page.continuation
                    while (continuation != null && all.size < 500) {
                        val next = YouTube.playlistContinuation(continuation).getOrThrow()
                        all += next.songs
                        continuation = next.continuation
                    }
                    all
                }
                "artist" -> {
                    val page = YouTube.artist(item.collectionId).getOrThrow()
                    val all = page.sections.flatMap { it.items }.filterIsInstance<SongItem>().toMutableList()
                    page.sections.mapNotNull { it.moreEndpoint }.forEach { endpoint ->
                        if (all.size >= 500) return@forEach
                        val first = YouTube.artistItems(endpoint).getOrThrow()
                        all += first.items.filterIsInstance<SongItem>()
                        var continuation = first.continuation
                        while (continuation != null && all.size < 500) {
                            val next = YouTube.artistItemsContinuation(continuation).getOrThrow()
                            all += next.items.filterIsInstance<SongItem>()
                            continuation = next.continuation
                        }
                    }
                    all
                }
                else -> emptyList()
            }.distinctBy { it.id }.take(500)
        }
        songs.forEachIndexed { index, song -> download(song.toTrack(index)) }
        if (songs.isNotEmpty()) rememberDownloadedCollection(item)
        return songs.size
    }

    fun openTrack(track: MusicTrack) {
        if (track.videoId == null) return
        runCatching { java.awt.Desktop.getDesktop().browse(URI("https://music.youtube.com/watch?v=${track.videoId}")) }
    }

    private fun writeTemporaryCookieFile(cookie: String): Path {
        val path = Files.createTempFile(appDataDirectory(), "youtube-session-", ".cookies")
        val lines = cookie.split(';').mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val name = part.substring(0, separator).trim()
            val value = part.substring(separator + 1).trim()
            if (name.isBlank()) null else ".youtube.com\tTRUE\t/\tTRUE\t0\t$name\t$value"
        }
        Files.writeString(path, "# Netscape HTTP Cookie File\n${lines.joinToString("\n")}\n")
        runCatching {
            Files.setPosixFilePermissions(path, setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            ))
        }
        return path
    }

    // Multiple playback requests can arrive together while these tools are first
    // installed. Keep each download/extraction complete before another request
    // checks the same destination or its shared temporary archive.
    @Synchronized
    private fun ensureYtDlp(): Path {
        val directory = appDataDirectory().resolve("tools")
        Files.createDirectories(directory)
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
        val (binaryName, asset) = when {
            os.contains("win") && (arch.contains("aarch64") || arch.contains("arm64")) -> "yt-dlp.exe" to "yt-dlp_arm64.exe"
            os.contains("win") -> "yt-dlp.exe" to "yt-dlp.exe"
            os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm64")) -> "yt-dlp_macos" to "yt-dlp_macos"
            os.contains("mac") -> "yt-dlp_macos" to "yt-dlp_macos_legacy"
            (arch.contains("aarch64") || arch.contains("arm64")) -> "yt-dlp_linux" to "yt-dlp_linux_aarch64"
            else -> "yt-dlp_linux" to "yt-dlp_linux"
        }
        val binary = directory.resolve(binaryName)
        if (!Files.isExecutable(binary)) {
            downloadVerified(
                "https://github.com/yt-dlp/yt-dlp/releases/latest/download/$asset",
                "https://github.com/yt-dlp/yt-dlp/releases/latest/download/SHA2-256SUMS",
                asset,
                binary,
            )
            binary.toFile().setExecutable(true)
        }
        return binary
    }

    @Synchronized
    private fun ensureDeno(): Path {
        val directory = appDataDirectory().resolve("tools")
        Files.createDirectories(directory)
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
        val target = when {
            os.contains("win") && (arch.contains("aarch64") || arch.contains("arm64")) -> "aarch64-pc-windows-msvc"
            os.contains("win") -> "x86_64-pc-windows-msvc"
            os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm64")) -> "aarch64-apple-darwin"
            os.contains("mac") -> "x86_64-apple-darwin"
            (arch.contains("aarch64") || arch.contains("arm64")) -> "aarch64-unknown-linux-gnu"
            else -> "x86_64-unknown-linux-gnu"
        }
        val executableName = if (os.contains("win")) "deno.exe" else "deno"
        val executable = directory.resolve(executableName)
        if (!Files.isExecutable(executable)) {
            val asset = "deno-$target.zip"
            val archive = directory.resolve(asset)
            downloadVerified(
                "https://github.com/denoland/deno/releases/latest/download/$asset",
                "https://github.com/denoland/deno/releases/latest/download/$asset.sha256sum",
                asset,
                archive,
            )
            ZipInputStream(Files.newInputStream(archive)).use { zip ->
                val entry = zip.nextEntry ?: error("Deno archive was empty.")
                require(entry.name == executableName || entry.name == "deno") { "Unexpected Deno archive contents." }
                Files.copy(zip, executable, StandardCopyOption.REPLACE_EXISTING)
            }
            Files.deleteIfExists(archive)
            executable.toFile().setExecutable(true)
        }
        return executable
    }

    private fun downloadVerified(url: String, checksumUrl: String, assetName: String, destination: Path) {
        VerifiedToolDownload.download(url, checksumUrl, assetName, destination)
    }

    private fun catalogueFile(): Path = appDataDirectory().resolve("catalogue.sqlite3")

    internal fun diagnosticsDirectory(): Path = appDataDirectory().resolve("logs")
    fun firstInstalledAtMillis(): Long {
        val setting = "desktop_first_launched_at"
        catalogue.getSetting(setting)?.toLongOrNull()?.let { return it }
        val installed = System.currentTimeMillis()
        catalogue.setSetting(setting, installed.toString())
        return installed
    }

    internal fun updateDirectory(): Path = appDataDirectory().resolve("updates")

    private fun appDataDirectory(): Path {
        val home = Path.of(System.getProperty("user.home"))
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        val environment = System.getenv()
        val directory = DesktopDataPaths.resolve(os, home, environment)
        Files.createDirectories(directory)
        if (os.contains("win")) {
            val legacyRoot = Path.of(
                environment["LOCALAPPDATA"] ?: home.resolve("AppData/Local").toString(),
            ).resolve("XAN")
            DesktopDataPaths.importLegacyWindowsData(legacyRoot, directory)
        }
        return directory
    }

    internal fun featurePackDirectory(): Path = appDataDirectory().resolve("features")

    fun downloadDirectoryLabel(): String = downloadDirectory().toString()
    fun isDiscordEnabled() = preferences.getBoolean(DISCORD_ENABLED, false)
    suspend fun setDiscordEnabled(enabled: Boolean) {
        preferences.putBoolean(DISCORD_ENABLED, enabled)
        if (enabled) updateDiscordPresence(currentTrack)
        else if (discordApplicationId().isNotBlank()) withContext(Dispatchers.IO) {
            runCatching { DiscordRpc.publish(discordApplicationId(), null) }
        }
    }
    fun discordApplicationId() = preferences.get(DISCORD_APP_ID, "")
    fun setDiscordApplicationId(id: String) = preferences.put(DISCORD_APP_ID, id.trim())

    suspend fun updateDiscordPresence(track: MusicTrack?): String = withContext(Dispatchers.IO) {
        currentTrack = track
        if (!isDiscordEnabled()) return@withContext "Discord presence is off."
        val appId = discordApplicationId()
        if (appId.isBlank()) return@withContext "Add a Discord Application ID first."
        runCatching { DiscordRpc.publish(appId, currentTrack) }
            .fold({ "Discord listening status updated." }, { "Discord is not running with XAN RPC enabled: ${it.message ?: "connection failed"}" })
    }

    fun prepareLastFm(apiKey: String, secret: String) {
        preferences.put(LASTFM_API_KEY, apiKey.trim())
        saveSecret(LASTFM_SECRET, secret.trim())
        LastFM.initialize(apiKey.trim(), secret.trim())
        LastFM.sessionKey = readSecret(LASTFM_SESSION)
    }

    suspend fun beginLastFmAuth(apiKey: String, secret: String): String = withContext(Dispatchers.IO) {
        prepareLastFm(apiKey, secret)
        val token = LastFM.getToken().getOrThrow().token
        SystemBrowser.openUrl(LastFM.getAuthUrl(token))
        token
    }

    suspend fun finishLastFmAuth(token: String) = withContext(Dispatchers.IO) {
        val key = preferences.get(LASTFM_API_KEY, "")
        val secret = readSecret(LASTFM_SECRET) ?: error("Enter the Last.fm API secret first.")
        LastFM.initialize(key, secret)
        val session = LastFM.getSession(token).getOrThrow().session
        saveSecret(LASTFM_SESSION, session.key)
        LastFM.sessionKey = session.key
        session.name
    }

    suspend fun scrobble(track: MusicTrack, playedAtSeconds: Long = System.currentTimeMillis() / 1000) = withContext(Dispatchers.IO) {
        val key = preferences.get(LASTFM_API_KEY, "")
        val secret = readSecret(LASTFM_SECRET)
        val session = readSecret(LASTFM_SESSION)
        if (!key.isNullOrBlank() && !secret.isNullOrBlank() && !session.isNullOrBlank()) {
            LastFM.initialize(key, secret)
            LastFM.sessionKey = session
            LastFM.scrobble(track.artist, track.title, playedAtSeconds, track.album, duration = track.durationSeconds)
                .getOrThrow()
        }
    }

    suspend fun scrobbleEnabledServices(track: MusicTrack, playedAtSeconds: Long, playbackEventId: String): ScrobbleOutcome = withContext(Dispatchers.IO) {
        val delivered = mutableListOf<String>()
        val failures = mutableListOf<Pair<String, String>>()
        val deduplicated = mutableListOf<String>()
        if (lastFmScrobblingEnabled() && lastFmConnected()) {
            if (!scrobbleLedger.claim(playbackEventId, "Last.fm")) deduplicated += "Last.fm"
            else runCatching { scrobble(track, playedAtSeconds) }
                .onSuccess { scrobbleLedger.finish(playbackEventId, "Last.fm", true); delivered += "Last.fm" }
                .onFailure { scrobbleLedger.finish(playbackEventId, "Last.fm", false); failures += "Last.fm" to (it.message ?: "submission failed") }
        }
        val listenBrainzToken = if (listenBrainzScrobblingEnabled()) readSecret(LISTENBRAINZ_TOKEN) else null
        if (!listenBrainzToken.isNullOrBlank()) {
            if (!scrobbleLedger.claim(playbackEventId, "ListenBrainz")) deduplicated += "ListenBrainz"
            else runCatching { ListenBrainzClient.submitListen(listenBrainzToken, track, playedAtSeconds, XAN_DESKTOP_VERSION) }
                .onSuccess { scrobbleLedger.finish(playbackEventId, "ListenBrainz", true); delivered += "ListenBrainz" }
                .onFailure { scrobbleLedger.finish(playbackEventId, "ListenBrainz", false); failures += "ListenBrainz" to (it.message ?: "submission failed") }
        }
        ScrobbleOutcome(delivered, failures, deduplicated)
    }

    private fun saveSecret(key: String, value: String) {
        val store = credentials ?: error("No OS credential store is available. Enable Windows Credential Manager, macOS Keychain, or a Linux Secret Service keyring.")
        val credential = StoredCredential("XAN Desktop", value.toCharArray())
        try {
            check(store.add(key, credential)) { "The operating system did not save this credential." }
        } finally {
            credential.clear()
        }
    }

    private fun readSecret(key: String): String? = runCatching {
        val credential = credentials?.get(key) ?: return null
        try { String(credential.password) } finally { credential.clear() }
    }.getOrNull()

    private fun deleteSecret(key: String) {
        runCatching { credentials?.delete(key) }
    }

    private fun YTItem.toTrack(index: Int): MusicTrack {
        val song = this as SongItem
        val artist = song.artists.joinToString(", ") { it.name }.ifBlank { "YouTube Music" }
        return MusicTrack(
            title = song.title,
            artist = artist,
            album = song.album?.name ?: "YouTube Music",
            colors = listOf(XanColors.CardLight, XanColors.Card),
            motif = "♪",
            videoId = song.id,
            source = if (song.musicVideoType == null) "YouTube Music" else "YouTube",
            artworkUrl = song.thumbnail,
            durationSeconds = song.duration,
            artistId = song.artists.firstOrNull()?.id,
            albumId = song.album?.id,
        )
    }

    private fun YTItem.toCatalogTrack(index: Int): MusicTrack? = when (this) {
        is SongItem -> toTrack(index)
        is AlbumItem -> MusicTrack(
            title, artists?.joinToString { it.name } ?: "Album", year?.toString() ?: "YouTube Music",
            listOf(XanColors.CardLight, XanColors.Card), "♫", source = "YouTube Music", artworkUrl = thumbnail,
            browseId = browseId, collectionId = playlistId, kind = "album",
        )
        is PlaylistItem -> MusicTrack(
            title, author?.name ?: "Playlist", songCountText ?: "YouTube Music",
            listOf(XanColors.CardLight, XanColors.Card), "♫", source = "YouTube Music", artworkUrl = thumbnail,
            collectionId = id, kind = "playlist",
        )
        is ArtistItem -> MusicTrack(
            title, "Artist", "YouTube Music", listOf(XanColors.CardLight, XanColors.Card), "♫",
            source = "YouTube Music", artworkUrl = thumbnail, collectionId = id, kind = "artist",
        )
        else -> null
    }
}

private fun normalizeLyricsVariantTarget(kind: String, target: String): String =
    if (kind == "romanization") "latin" else target.trim().lowercase(Locale.ROOT)

private fun lyricsSourceHash(lines: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    lines.forEach { line ->
        val bytes = line.toByteArray(Charsets.UTF_8)
        val length = bytes.size
        digest.update(byteArrayOf(
            (length ushr 24).toByte(),
            (length ushr 16).toByte(),
            (length ushr 8).toByte(),
            length.toByte(),
        ))
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** Use InnerTube's direct AAC URL immediately; page extraction is only needed for cipher-only responses. */
internal suspend fun selectDesktopPlayableAudioFormat(
    formats: List<PlayerResponse.StreamingData.Format>?,
    resolveFallback: suspend () -> List<PlayerResponse.StreamingData.Format>?,
): PlayerResponse.StreamingData.Format? {
    fun bestPlayable(candidates: List<PlayerResponse.StreamingData.Format>?): PlayerResponse.StreamingData.Format? =
        candidates.orEmpty()
            .asSequence()
            .filter { isDesktopPlayableAudioMimeType(it.mimeType) && !it.url.isNullOrBlank() }
            .sortedByDescending { it.averageBitrate ?: it.bitrate }
            .firstOrNull()

    return bestPlayable(formats) ?: bestPlayable(resolveFallback())
}

private object DiscordRpc {
    private var appId: String? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var closeConnection: (() -> Unit)? = null

    @Synchronized
    fun publish(applicationId: String, track: MusicTrack?) {
        if (appId != applicationId || output == null) connect(applicationId)
        val activity = if (track == null) "null" else """
            {"details":"${track.title.jsonEscape()}","state":"${track.artist.jsonEscape()}","assets":{"large_text":"XAN"}}
        """.trimIndent()
        writePacket("""{"cmd":"SET_ACTIVITY","args":{"pid":${ProcessHandle.current().pid()},"activity":$activity},"nonce":"${System.nanoTime()}"}""")
    }

    private fun connect(applicationId: String) {
        runCatching { closeConnection?.invoke() }
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        if (os.contains("win")) {
            val pipe = (0..9).firstNotNullOfOrNull { index ->
                runCatching {
                    val file = RandomAccessFile("\\\\.\\pipe\\discord-ipc-$index", "rw")
                    Triple(FileInputStream(file.fd), FileOutputStream(file.fd)) { file.close() }
                }.getOrNull()
            } ?: error("Discord IPC pipe not found")
            input = pipe.first
            output = pipe.second
            closeConnection = pipe.third
        } else {
            val home = Path.of(System.getProperty("user.home"))
            val runtime = System.getenv("XDG_RUNTIME_DIR")?.let(Path::of)
            val roots = listOfNotNull(
                runtime,
                System.getenv("TMPDIR")?.let(Path::of),
                Path.of(System.getProperty("java.io.tmpdir")),
                home.resolve(".config/discord"),
                home.resolve(".discord"),
                home.resolve("Library/Application Support/discord"),
            ).distinct()
            var connected: SocketChannel? = null
            for (root in roots) {
                for (index in 0..9) {
                    val pipe = root.resolve("discord-ipc-$index")
                    if (!Files.exists(pipe)) continue
                    connected = runCatching {
                        SocketChannel.open(StandardProtocolFamily.UNIX).apply { connect(UnixDomainSocketAddress.of(pipe)) }
                    }.getOrNull()
                    if (connected != null) break
                }
                if (connected != null) break
            }
            val channel = connected ?: error("Discord IPC socket not found")
            input = Channels.newInputStream(channel)
            output = Channels.newOutputStream(channel)
            closeConnection = { channel.close() }
        }
        appId = applicationId
        writePacket("""{"v":1,"client_id":"${applicationId.jsonEscape()}"}""", opcode = 0)
        readPacket()
    }

    private fun writePacket(json: String, opcode: Int = 1) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val stream = output ?: error("Discord RPC is disconnected")
        stream.write(byteArrayOf(opcode.toByte(), 0, 0, 0, bytes.size.toByte(), (bytes.size shr 8).toByte(), (bytes.size shr 16).toByte(), (bytes.size shr 24).toByte()))
        stream.write(bytes)
        stream.flush()
    }

    private fun readPacket(): String {
        val stream = input ?: error("Discord RPC is disconnected")
        val header = stream.readNBytes(8)
        if (header.size != 8) error("Discord closed the RPC connection")
        val length = (header[4].toInt() and 255) or ((header[5].toInt() and 255) shl 8) or ((header[6].toInt() and 255) shl 16) or ((header[7].toInt() and 255) shl 24)
        return stream.readNBytes(length).toString(Charsets.UTF_8)
    }

    private fun String.jsonEscape() = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
}

internal object SystemBrowser {
    fun openUrl(url: String) {
        if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().browse(URI(url))
    }
}
