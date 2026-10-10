/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.xan.music.constants.HideExplicitKey
import app.xan.music.constants.HideVideoSongsKey
import app.xan.music.constants.DataSaverEnabledKey
import app.xan.music.constants.SongSortDescendingKey
import app.xan.music.constants.SongSortType
import app.xan.music.constants.SongSortTypeKey
import app.xan.music.db.MusicDatabase
import app.xan.music.extensions.filterExplicit
import app.xan.music.extensions.filterVideoSongs
import app.xan.music.extensions.toEnum
import app.xan.music.utils.SyncUtils
import com.music.innertube.YouTube
import app.xan.music.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AutoPlaylistViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    private val database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    val playlist = savedStateHandle.get<String>("playlist")!!

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private val _youtubeMusicCover = MutableStateFlow<String?>(null)
    val youtubeMusicCover = _youtubeMusicCover.asStateFlow()

    init {
        // Use YouTube Music's own auto-playlist ordering for the Liked Music cover.
        // The LM header is sometimes cover-less, so its first YTM song artwork is
        // the authoritative fallback rather than whichever local sort is active.
        if (playlist == "liked") {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    YouTube.playlist("LM").getOrNull()
                }.getOrNull()?.let { page ->
                    _youtubeMusicCover.value =
                        page.playlist.thumbnail?.takeIf { it.isNotBlank() }
                            ?: page.songs.firstOrNull()?.thumbnail?.takeIf { it.isNotBlank() }
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val likedSongs =
        context.dataStore.data
            .map {
                Triple(
                    it[SongSortTypeKey].toEnum(SongSortType.CREATE_DATE) to (it[SongSortDescendingKey]
                        ?: true),
                    it[HideExplicitKey] ?: false,
                    (it[HideVideoSongsKey] ?: false) || (it[DataSaverEnabledKey] ?: false)
                )
            }
            .distinctUntilChanged()
            .flatMapLatest { (sortDesc, hideExplicit, hideVideoSongs) ->
                val (sortType, descending) = sortDesc
                when (playlist) {
                    "liked" -> database.likedSongs(sortType, descending)
                        .map { it.filterExplicit(hideExplicit).filterVideoSongs(hideVideoSongs) }

                    "downloaded" -> database.downloadedSongs(sortType, descending)
                        .map { it.filterExplicit(hideExplicit) }

                    "uploaded" -> database.uploadedSongs(sortType, descending)
                        .map { it.filterExplicit(hideExplicit).filterVideoSongs(hideVideoSongs) }

                    "local" -> database.localSongs(sortType, descending)
                        .map { it.filterExplicit(hideExplicit).filterVideoSongs(hideVideoSongs) }

                    else -> kotlinx.coroutines.flow.flowOf(emptyList())
                }
            }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, emptyList())

    fun syncLikedSongs() {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.syncLikedSongs() }
    }

    fun syncUploadedSongs() {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.syncUploadedSongs() }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _isRefreshing.value = true
            when (playlist) {
                "liked" -> syncUtils.syncLikedSongsSuspend()
                "uploaded" -> syncUtils.syncUploadedSongsSuspend()
            }
            _isRefreshing.value = false
        }
    }

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    /** Scans device audio into the library (isLocal songs). Used by the "local" auto playlist. */
    fun scanLocal(context: Context) {
        if (_isScanning.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isScanning.value = true
            try {
                app.xan.music.utils.LocalAudioScanner.scanAndInsert(context, database)
            } catch (e: Exception) {
                Timber.tag("AutoPlaylistViewModel").e(e, "local scan failed")
            } finally {
                _isScanning.value = false
            }
        }
    }
}
