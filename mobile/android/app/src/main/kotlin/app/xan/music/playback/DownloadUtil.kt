/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.playback
import timber.log.Timber
import app.xan.music.R
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.database.DatabaseProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.music.innertube.YouTube
import app.xan.music.constants.AudioQuality
import app.xan.music.constants.AudioQualityKey
import app.xan.music.constants.DataSaverEnabledKey
import app.xan.music.constants.IpVersionKey
import com.music.innertube.models.IpVersion
import okhttp3.Dns
import java.net.InetAddress
import java.net.Inet4Address
import java.net.Inet6Address
import app.xan.music.db.MusicDatabase
import app.xan.music.db.entities.FormatEntity
import app.xan.music.db.entities.ArtistEntity
import app.xan.music.db.entities.SongEntity
import app.xan.music.db.entities.SongArtistMap
import app.xan.music.models.MediaMetadata
import app.xan.music.di.DownloadCache
import app.xan.music.ui.utils.resize
import app.xan.music.constants.AutoDownloadOnLikeKey
import app.xan.music.video.EXTERNAL_VIDEO_MEDIA_ID_PREFIX
import app.xan.music.utils.YTPlayerUtils
import app.xan.music.utils.enumPreference
import app.xan.music.utils.get
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import app.xan.music.applecanvas.AppleMusicCanvasProvider
import app.xan.music.canvas.AppleMusicArtistBackgroundProvider
import app.xan.music.constants.CanvasSource
import app.xan.music.constants.CanvasSourceKey
import app.xan.music.ui.player.normalizeCanvasArtistName
import app.xan.music.ui.player.normalizeCanvasSongTitle
import app.xan.music.utils.dataStore
import app.xan.music.xancanvas.EchoMusicCanvasProvider
import app.xan.music.xancanvas.XanCanvasProvider
import app.xan.music.canvas.TidalCanvasProvider
import java.util.Locale
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

@UnstableApi
@Singleton
class DownloadUtil
@Inject
constructor(
    @ApplicationContext context: Context,
    val database: MusicDatabase,
    val databaseProvider: DatabaseProvider,
    @DownloadCache val downloadCache: SimpleCache,
) {
    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
    private val ipVersion by enumPreference(context, IpVersionKey, IpVersion.AUTO)
    private val songUrlCache = ConcurrentHashMap<String, CachedResolvedStream>()
    private val appContext: Context = context

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    init {
        // Auto-download-on-like watches the `liked` column instead of hooking the
        // like action: the hook used to live in MusicService.toggleLike(), which
        // only the player screen's like button and the media-session action ever
        // reach. Liking from a song/queue/selection menu, a swipe, or the YouTube
        // sync writes the same column and never triggered a download.
        scope.launch {
            var known: Set<String>? = null
            database.likedSongIds().collect { ids ->
                val current = ids.toSet()
                val previous = known
                known = current
                // The first emission is the existing library, not a batch of new
                // likes — seeding it stops a fresh start from queueing everything.
                if (previous == null) return@collect
                if (!appContext.dataStore.get(AutoDownloadOnLikeKey, true)) return@collect

                for (songId in current - previous) {
                    if (downloads.value[songId] != null) continue
                    runCatching {
                        val storedSong = database.getSongById(songId)
                        val target =
                            storedSong?.toDownloadTarget()
                                ?: DownloadTarget(
                                    id = songId,
                                    title = database.songTitle(songId).orEmpty().ifBlank { "Unknown" },
                                )
                        DownloadService.sendAddDownload(
                            appContext,
                            ExoDownloadService::class.java,
                            buildDownloadRequest(target),
                            false,
                        )
                    }.onFailure {
                        // Backgrounded apps can be blocked from starting the download
                        // service; losing one auto-download must not kill the collector.
                        Timber.e(it, "Auto-download on like failed for $songId")
                    }
                }
            }
        }
    }

    private val resolvingDataSourceFactory =
        ResolvingDataSource.Factory(
            StreamRangeDataSource.Factory(
                    OkHttpDataSource.Factory(
                        OkHttpClient.Builder()
                            .dns(object : Dns {
                                override fun lookup(hostname: String): List<InetAddress> {
                                    val addresses = Dns.SYSTEM.lookup(hostname)
                                    return when (this@DownloadUtil.ipVersion) {
                                        IpVersion.IPV4 -> addresses.filter { it is Inet4Address }.ifEmpty { addresses }
                                        IpVersion.IPV6 -> addresses.filter { it is Inet6Address }.ifEmpty { addresses }
                                        IpVersion.AUTO -> addresses
                                    }
                                }
                            })
                            .proxy(YouTube.proxy)
                            .proxyAuthenticator { _, response ->
                                YouTube.proxyAuth?.let { auth ->
                                    response.request.newBuilder()
                                        .header("Proxy-Authorization", auth)
                                        .build()
                                } ?: response.request
                            }
                            .build(),
                    ),
                ),
        ) { dataSpec ->
            val mediaId = dataSpec.key ?: return@Factory dataSpec
            // Extractor-backed video manifests and their segment URLs are already
            // concrete network resources. They must not be resolved as YouTube IDs.
            if (
                mediaId.startsWith(EXTERNAL_VIDEO_MEDIA_ID_PREFIX) ||
                mediaId.startsWith("http://", ignoreCase = true) ||
                mediaId.startsWith("https://", ignoreCase = true)
            ) {
                if (mediaId.startsWith(EXTERNAL_VIDEO_MEDIA_ID_PREFIX)) {
                    val referer = database.getSongByIdBlocking(mediaId)?.song?.externalVideoPageUrl
                    return@Factory dataSpec.buildUpon()
                        .setHttpRequestHeaders(buildMap {
                            putAll(dataSpec.httpRequestHeaders)
                            put("User-Agent", app.xan.music.video.EXTERNAL_VIDEO_USER_AGENT)
                            referer?.takeIf(String::isNotBlank)?.let { put("Referer", it) }
                        })
                        .build()
                }
                return@Factory dataSpec
            }
            // ">" — the entry is usable while its expiry is still in the FUTURE. This
            // was "<", which paired with the expiry being stored as a bare duration
            // (see below) meant a cached URL was reused forever, long past the point
            // where YouTube stopped serving it, and never re-resolved.
            songUrlCache[mediaId]?.takeIf { it.expiresAtMillis > System.currentTimeMillis() }?.let { cached ->
                return@Factory cached.resolve(dataSpec)
            }

            val playbackData = runBlocking(Dispatchers.IO) {
                val downloadQuality =
                    when {
                        appContext.dataStore.get(DataSaverEnabledKey, false) -> AudioQuality.LOW
                        audioQuality == AudioQuality.AUTO -> AudioQuality.HIGH
                        else -> audioQuality
                    }
                YTPlayerUtils.playerResponseForPlayback(
                    mediaId,
                    audioQuality = downloadQuality,
                    connectivityManager = connectivityManager,
                    context = appContext,
                    // Lossless is streaming-only for now: downloads stay YouTube so the
                    // offline cache (keyed by videoId) never mixes FLAC and Opus bytes.
                    allowLossless = false,
                )
            }.getOrThrow()
            val format = playbackData.format

            // Persist metadata synchronously with URL resolution. The old database.query
            // scheduled this work and returned immediately, allowing a fast download to
            // complete before its Song row/artist relations existed.
            runBlocking(Dispatchers.IO) {
                database.withTransaction {
                upsert(
                    FormatEntity(
                        id = mediaId,
                        itag = format.itag,
                        mimeType = format.mimeType.split(";")[0],
                        codecs = format.mimeType.split("codecs=").getOrNull(1)?.removeSurrounding("\"") ?: "mp4a.40.2",
                        bitrate = format.bitrate,
                        sampleRate = format.audioSampleRate,
                        contentLength = format.contentLength ?: 0L,
                        loudnessDb = playbackData.audioConfig?.loudnessDb,
                        perceptualLoudnessDb = playbackData.audioConfig?.perceptualLoudnessDb,
                        playbackUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                    ),
                )

                val now = LocalDateTime.now()
                val existingSong = getSongByIdBlocking(mediaId)
                val existing = existingSong?.song
                val resolvedTitle = playbackData.videoDetails?.title?.trim()?.takeIf { it.isNotEmpty() }
                val resolvedDuration = playbackData.videoDetails?.lengthSeconds?.toIntOrNull() ?: 0
                val defaultArtwork =
                    "android.resource://${appContext.packageName}/${R.drawable.xan_default_album_art}"
                val resolvedThumbnail =
                    playbackData.videoDetails?.thumbnail?.thumbnails?.lastOrNull()?.url?.resize(1200, 1200)
                        ?: defaultArtwork
                val resolvedArtist = playbackData.videoDetails?.author?.trim()?.takeIf { it.isNotEmpty() }

                val updatedSong = if (existing != null) {
                    existing.copy(
                        dateDownload = existing.dateDownload ?: now,
                        title = if (existing.title.isBlank() || existing.title.equals("Unknown", ignoreCase = true)) {
                            resolvedTitle ?: existing.title
                        } else {
                            existing.title
                        },
                        duration = if (existing.duration <= 0) resolvedDuration else existing.duration,
                        thumbnailUrl = existing.thumbnailUrl?.takeIf { it.isNotBlank() } ?: resolvedThumbnail,
                    )
                } else {
                    SongEntity(
                        id = mediaId,
                        title = resolvedTitle ?: "Unknown",
                        duration = resolvedDuration,
                        thumbnailUrl = resolvedThumbnail,
                        dateDownload = now,
                        isDownloaded = false
                    )
                }

                upsert(updatedSong)

                if (existingSong?.artists.isNullOrEmpty()) {
                    resolvedArtist?.let { artistName ->
                        val artistId = artistByName(artistName)?.id ?: ArtistEntity.generateArtistId()
                        insert(ArtistEntity(id = artistId, name = artistName))
                        insert(SongArtistMap(songId = mediaId, artistId = artistId, position = 0))
                    }
                }

                // Pre-cache the high-res thumbnail immediately when download starts.
                // Keyed on the raw (un-resized) URL, not the default (the actual
                // request data, which is this same string): every UI thumbnail
                // request instead resizes this URL to its own target decode size
                // first, so without a shared stable key this entry sits under a
                // URL nothing else ever asks for and is invisible offline despite
                // being cached. ItemThumbnail/LocalThumbnail below key their
                // requests the same way, off the same DB-stored thumbnailUrl.
                updatedSong.thumbnailUrl?.let { url ->
                    val request = ImageRequest.Builder(context)
                        .data(url)
                        .diskCacheKey(url)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build()
                    SingletonImageLoader.get(context).enqueue(request)
                }

                // --- CANVAS CACHING ---
                scope.launch {
                    val canvasSource = context.dataStore.data.map { it[CanvasSourceKey] ?: CanvasSource.AUTO.name }.first().let { name -> CanvasSource.entries.find { it.name == name } ?: CanvasSource.AUTO }

                    val storefront = Locale.getDefault().country.lowercase(Locale.ROOT).takeIf { it.length == 2 } ?: "us"
                    val requestedTitle = playbackData.videoDetails?.title.orEmpty()
                    val requestedArtist = playbackData.videoDetails?.author.orEmpty()
                    
                    val s = normalizeCanvasSongTitle(requestedTitle)
                    val a = normalizeCanvasArtistName(requestedArtist)

                    val canvas = when (canvasSource) {
                        CanvasSource.AUTO -> {
                            EchoMusicCanvasProvider.getBySongArtist(s, a)?.preferredAnimationUrl
                                ?: AppleMusicCanvasProvider.getBySongArtist(s, a, "", storefront)?.preferredAnimationUrl
                                ?: XanCanvasProvider.getBySongArtist(s, a)?.preferredAnimationUrl
                                ?: TidalCanvasProvider.getBySongArtist(s, a, "")?.preferredAnimationUrl
                        }
                        CanvasSource.ECHO_MUSIC -> EchoMusicCanvasProvider.getBySongArtist(s, a)?.preferredAnimationUrl
                        CanvasSource.APPLE_MUSIC -> AppleMusicCanvasProvider.getBySongArtist(s, a, "", storefront)?.preferredAnimationUrl
                        CanvasSource.XAN -> XanCanvasProvider.getBySongArtist(s, a)?.preferredAnimationUrl
                        CanvasSource.TIDAL -> TidalCanvasProvider.getBySongArtist(s, a, "")?.preferredAnimationUrl
                    }

                    canvas?.let { url ->
                        val dataSpec = DataSpec.Builder()
                            .setUri(url.toUri())
                            .setKey("$mediaId#canvas")
                            .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                            .build()
                            
                        val dataSource = CacheDataSource.Factory()
                            .setCache(downloadCache)
                            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context))
                            .setCacheWriteDataSinkFactory(null)
                            .createDataSource()
                        
                        kotlin.runCatching {
                            val writer = CacheWriter(
                                dataSource,
                                dataSpec,
                                null,
                                null
                            )
                            writer.cache()
                            Timber.tag("CanvasDownload").d("Successfully cached canvas for $mediaId")
                        }.onFailure { e ->
                            Timber.tag("CanvasDownload").e(e, "Failed to cache canvas for $mediaId")
                        }
                    }
                }
                }
            }

            // Absolute deadline, not a bare duration — the read above compares this
            // against System.currentTimeMillis(). MusicService's resolver already
            // stored it this way; this one was ~6h past the epoch, i.e. always stale.
            val resolved = CachedResolvedStream(
                url = playbackData.streamUrl,
                expiresAtMillis = System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L),
                requestHeaders = playbackData.streamHeaders,
                rangeLengthBytes = playbackData.rangeChunkSizeBytes.takeIf {
                    it > 0 && (playbackData.requireBoundedRange || playbackData.useRangeChunks)
                },
                isWholeFileStream = playbackData.isTidalStream,
                contentLengthBytes = format.contentLength?.takeIf { it > 0 },
            )
            songUrlCache[mediaId] = resolved
            resolved.resolve(dataSpec)
        }

    /**
     * Rehydrate the Song row from the metadata embedded in DownloadRequest.data.
     * This makes title/artist/cover survive offline and also repairs completed
     * downloads made by older builds when enough metadata is still available.
     */
    suspend fun persistQueuedTarget(target: DownloadTarget) {
        withContext(Dispatchers.IO) { persistDownloadTarget(target.id, target) }
    }

    private suspend fun persistRequestMetadata(download: Download) {
        persistDownloadTarget(
            download.request.id,
            decodeDownloadTarget(download.request.id, download.request.data),
        )
    }

    private suspend fun persistDownloadTarget(id: String, target: DownloadTarget) {
        val existing = database.getSongById(id)
        val defaultArtwork =
            "android.resource://${appContext.packageName}/${R.drawable.xan_default_album_art}"

        val artistNames =
            target.artists.ifEmpty {
                existing?.artists?.map { it.name }.orEmpty()
            }

        val albumId = target.albumId ?: existing?.song?.albumId
        val albumTitle = target.albumTitle ?: existing?.song?.albumName

        val metadata =
            MediaMetadata(
                id = id,
                title =
                    target.title
                        .takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
                        ?: existing?.song?.title?.takeIf { it.isNotBlank() }
                        ?: "Unknown",
                artists =
                    artistNames.map {
                        MediaMetadata.Artist(id = null, name = it)
                    },
                duration =
                    target.duration.takeIf { it > 0 }
                        ?: existing?.song?.duration?.takeIf { it > 0 }
                        ?: -1,
                thumbnailUrl =
                    target.thumbnailUrl?.takeIf { it.isNotBlank() }
                        ?: existing?.song?.thumbnailUrl?.takeIf { it.isNotBlank() }
                        ?: defaultArtwork,
                album =
                    albumId?.let {
                        MediaMetadata.Album(
                            id = it,
                            title = albumTitle.orEmpty(),
                        )
                    },
                liked = existing?.song?.liked ?: false,
                likedDate = existing?.song?.likedDate,
                inLibrary = existing?.song?.inLibrary,
                libraryAddToken = existing?.song?.libraryAddToken,
                libraryRemoveToken = existing?.song?.libraryRemoveToken,
                explicit = existing?.song?.explicit ?: false,
                musicVideoType = if (
                    target.externalVideoSource != null || existing?.song?.isVideo == true
                ) "MUSIC_VIDEO_TYPE_OMV" else null,
                isExternalVideo = target.externalVideoSource != null || existing?.song?.externalVideoSource != null,
                externalVideoSource = target.externalVideoSource ?: existing?.song?.externalVideoSource,
                externalVideoServiceId = target.externalVideoServiceId ?: existing?.song?.externalVideoServiceId,
                externalVideoPageId = target.externalVideoPageId ?: existing?.song?.externalVideoPageId,
                externalVideoFormatId = target.externalVideoFormatId ?: existing?.song?.externalVideoFormatId,
                externalVideoPageUrl = target.externalVideoPageUrl ?: existing?.song?.externalVideoPageUrl,
                externalVideoStreamUrl = target.externalVideoStreamUrl ?: existing?.song?.externalVideoStreamUrl,
                externalVideoMimeType = target.externalVideoMimeType ?: existing?.song?.externalVideoMimeType,
                externalVideoCodec = target.externalVideoCodec ?: existing?.song?.externalVideoCodec,
                externalVideoAudioCodec = target.externalVideoAudioCodec ?: existing?.song?.externalVideoAudioCodec,
                externalVideoExtension = target.externalVideoExtension ?: existing?.song?.externalVideoExtension,
                externalVideoResolution = target.externalVideoResolution ?: existing?.song?.externalVideoResolution,
                externalVideoAdaptive = target.externalVideoAdaptive,
            )

        database.withTransaction {
            val current = getSongByIdBlocking(id)
            if (current == null) {
                insert(metadata)
            } else {
                update(current, metadata)
            }
        }

        metadata.thumbnailUrl
            ?.takeIf { it.isNotBlank() && !it.startsWith("android.resource://") }
            ?.let { url ->
                runCatching {
                    val request =
                        ImageRequest.Builder(appContext)
                            .data(url)
                            .diskCacheKey(url)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .build()
                    SingletonImageLoader.get(appContext).execute(request)
                }.onFailure {
                    Timber.w(it, "Failed to cache download artwork for %s", id)
                }
            }
    }

    val downloadNotificationHelper =
        DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

    @OptIn(DelicateCoroutinesApi::class)
    val downloadManager: DownloadManager =
        DownloadManager(
            context,
            databaseProvider,
            downloadCache,
            // DownloadManager already wraps this in downloadCache. Playback cache
            // bytes may use a different quality/itag and must never be spliced in.
            resolvingDataSourceFactory,
            Executor(Runnable::run)
        ).apply {
            maxParallelDownloads = 3
            // Built to post a notification on a failed download but never actually
            // registered anywhere — a failure produced no system notification, no
            // in-app error state (see the STATE_FAILED handling below/in the menus),
            // nothing. It just silently looked like the download had never happened.
            addListener(
                ExoDownloadService.TerminalStateNotificationHelper(
                    context,
                    downloadNotificationHelper,
                    ExoDownloadService.NOTIFICATION_ID + 1,
                )
            )
            addListener(
                object : DownloadManager.Listener {
                    override fun onDownloadChanged(
                        downloadManager: DownloadManager,
                        download: Download,
                        finalException: Exception?,
                    ) {
                        downloads.update { map ->
                            map.toMutableMap().apply {
                                set(download.request.id, download)
                            }
                        }

                        // finalException was being dropped on the floor, so a download
                        // that failed left no trace of WHY anywhere — which is exactly
                        // the situation in the "only half my songs download, and the
                        // Hindi ones never do" reports: no way to tell a region block
                        // from a dead stream URL from a network drop. Logged through
                        // Timber so it lands in the in-app log viewer (Settings ->
                        // Content -> Logs) and can be read off a user's device.
                        if (download.state == Download.STATE_FAILED) {
                            // A retry must obtain a new URL after a CDN rejects an otherwise
                            // unexpired cached stream (for example after a session change).
                            songUrlCache.remove(download.request.id)
                            Timber.e(
                                finalException,
                                "Download failed: id=%s title=%s reason=%d",
                                download.request.id,
                                downloadDisplayTitle(download),
                                download.failureReason,
                            )
                        }

                        scope.launch {
                            when (download.state) {
                                Download.STATE_QUEUED,
                                Download.STATE_RESTARTING -> {
                                    // Populate title/artist/artwork immediately, before bytes finish.
                                    persistRequestMetadata(download)
                                }
                                Download.STATE_COMPLETED -> {
                                    persistRequestMetadata(download)
                                    database.updateDownloadedInfo(download.request.id, true, LocalDateTime.now())
                                    enqueueMusicCopy(appContext, download.request.id)
                                }
                                Download.STATE_FAILED,
                                Download.STATE_STOPPED,
                                Download.STATE_REMOVING -> {
                                    database.updateDownloadedInfo(download.request.id, false, null)
                                }
                                else -> {
                                }
                            }
                        }
                    }
                }
            )
        }

    init {
        val result = mutableMapOf<String, Download>()
        val cursor = downloadManager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            result[cursor.download.request.id] = cursor.download
        }
        downloads.value = result

        // Repair any completed downloads whose metadata/update raced in older builds.
        result.values
            .filter { it.state == Download.STATE_COMPLETED }
            .forEach { download ->
                scope.launch {
                    persistRequestMetadata(download)
                    database.updateDownloadedInfo(download.request.id, true, LocalDateTime.now())
                }
            }
        // Recover copies missed by process death with one batch, rather than a job per restored song.
        if (result.values.any { it.state == Download.STATE_COMPLETED }) {
            scope.launch { enqueueMusicCopy(appContext, automatic = true) }
        }
    }

    fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }

    fun release() {
        scope.cancel()
    }
}
