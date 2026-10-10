/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.innertube.YouTube
import com.music.innertube.models.filterExplicit
import com.music.innertube.models.filterVideoSongs
import com.music.innertube.models.filterYoutubeShorts
import com.music.innertube.models.SongItem
import com.music.innertube.pages.ArtistPage
import app.xan.music.constants.HideExplicitKey
import app.xan.music.constants.HideVideoSongsKey
import app.xan.music.constants.DataSaverEnabledKey
import app.xan.music.constants.HideYoutubeShortsKey
import app.xan.music.db.MusicDatabase
import app.xan.music.extensions.filterExplicit
import app.xan.music.extensions.filterExplicitAlbums
import app.xan.music.utils.dataStore
import app.xan.music.utils.get
import app.xan.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import app.xan.music.extensions.filterVideoSongs as filterVideoSongsLocal
import app.xan.music.artistvideo.ArtistVideoCanvasProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ArtistViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val artistId = savedStateHandle.get<String>("artistId")!!
    var artistPage by mutableStateOf<ArtistPage?>(null)
    
    private val _artistVideoUrl = MutableStateFlow<String?>(null)
    val artistVideoUrl: StateFlow<String?> = _artistVideoUrl

    private val _artistVideoSong = MutableStateFlow<com.music.innertube.models.SongItem?>(null)
    val artistVideoSong: StateFlow<com.music.innertube.models.SongItem?> = _artistVideoSong
    
    val libraryArtist = database.artist(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    val librarySongs = context.dataStore.data
        .map { (it[HideExplicitKey] ?: false) to ((it[HideVideoSongsKey] ?: false) || (it[DataSaverEnabledKey] ?: false)) }
        .distinctUntilChanged()
        .flatMapLatest { (hideExplicit, hideVideoSongs) ->
            database.artistSongsPreview(artistId).map { it.filterExplicit(hideExplicit).filterVideoSongsLocal(hideVideoSongs) }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val libraryAlbums = context.dataStore.data
        .map { it[HideExplicitKey] ?: false }
        .distinctUntilChanged()
        .flatMapLatest { hideExplicit ->
            database.artistAlbumsPreview(artistId).map { it.filterExplicitAlbums(hideExplicit) }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        // Load artist page and reload when hide explicit setting changes
        viewModelScope.launch {
            context.dataStore.data
                .map {
                    Triple(
                        it[HideExplicitKey] ?: false,
                        (it[HideVideoSongsKey] ?: false) || (it[DataSaverEnabledKey] ?: false),
                        it[HideYoutubeShortsKey] ?: false
                    )
                }
                .distinctUntilChanged()
                .collect {
                    fetchArtistsFromYTM()
                }
        }
    }


    /** Collect song/video shelves and album/single tracks, without silent partial success. */
    suspend fun allArtistSongsForDownload(): List<SongItem> {
        val page = artistPage ?: error("Artist page has not loaded")
        val songs = app.xan.music.playback.resolveArtistDownloadSongs(
            page = page,
            artistId = artistId,
            fetchItems = { YouTube.artistItems(it).getOrThrow() },
            fetchContinuation = { YouTube.artistItemsContinuation(it).getOrThrow() },
            fetchAlbum = { YouTube.album(it).getOrThrow() },
        )
        val hideExplicit = context.dataStore.get(HideExplicitKey, false)
        val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false) ||
            context.dataStore.get(DataSaverEnabledKey, false)
        return songs.filterExplicit(hideExplicit).filterVideoSongs(hideVideoSongs)
    }
    fun fetchArtistsFromYTM() {
        viewModelScope.launch {
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
            val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false) || context.dataStore.get(DataSaverEnabledKey, false)
            val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
            YouTube.artist(artistId)
                .onSuccess { page ->
                    val filteredSections = page.sections
                        .map { section ->
                            section.copy(items = section.items.filterExplicit(hideExplicit).filterVideoSongs(hideVideoSongs).filterYoutubeShorts(hideYoutubeShorts))
                        }
                        .filter { section -> section.items.isNotEmpty() }

                    artistPage = page.copy(sections = filteredSections)
                    
                    // Try to fetch artist video canvas from top songs
                    val topSongsSection = page.sections.find { it.items.firstOrNull() is com.music.innertube.models.SongItem }
                    topSongsSection?.items?.forEach { item ->
                        if (item is com.music.innertube.models.SongItem) {
                            val canvas = ArtistVideoCanvasProvider.getBySongArtist(
                                song = item.title,
                                artist = page.artist?.title ?: ""
                            )
                            if (canvas?.preferredAnimationUrl != null) {
                                _artistVideoUrl.value = canvas.preferredAnimationUrl
                                _artistVideoSong.value = item
                                return@forEach
                            }
                        }
                    }
                }.onFailure {
                    reportException(it)
                }
        }
    }
}
