/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.models

import androidx.compose.runtime.Immutable
import com.music.innertube.models.SongItem
import com.music.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import app.xan.music.db.entities.Song
import app.xan.music.db.entities.SongEntity
import app.xan.music.ui.utils.resize
import java.io.Serializable
import java.time.LocalDateTime

@Immutable
data class MediaMetadata(
    val id: String,
    val title: String,
    val artists: List<Artist>,
    val duration: Int,
    val thumbnailUrl: String? = null,
    val album: Album? = null,
    val setVideoId: String? = null,
    val musicVideoType: String? = null,
    val explicit: Boolean = false,
    val liked: Boolean = false,
    val likedDate: LocalDateTime? = null,
    val inLibrary: LocalDateTime? = null,
    val libraryAddToken: String? = null,
    val libraryRemoveToken: String? = null,
    val suggestedBy: String? = null,
    val isrc: String? = null,
    /** External video search results bypass the YouTube music-video hide filter. */
    val isExternalVideo: Boolean = false,
    val externalVideoDescription: String? = null,
    val externalVideoViewCountText: String? = null,
    val externalVideoPublishedTimeText: String? = null,
    val externalVideoSource: String? = null,
    val externalVideoServiceId: Int? = null,
    val externalVideoPageId: String? = null,
    val externalVideoFormatId: String? = null,
    val externalVideoPageUrl: String? = null,
    val externalVideoUploader: String? = null,
    val externalVideoResolution: String? = null,
    val externalVideoExtension: String? = null,
    val externalVideoMimeType: String? = null,
    val externalVideoCodec: String? = null,
    val externalVideoAudioCodec: String? = null,
    val externalVideoStreamUrl: String? = null,
    val externalVideoAdaptive: Boolean = false,
    val externalVideoAudioUrl: String? = null,
    val externalVideoAudioMimeType: String? = null,
    val externalVideoVideoOnly: Boolean = false,
    val externalVideoFormats: List<ExternalVideoPlaybackFormat> = emptyList(),
) : Serializable {
    val isVideoSong: Boolean
        get() = musicVideoType != null && musicVideoType != MUSIC_VIDEO_TYPE_ATV

    data class Artist(
        val id: String?,
        val name: String,
    ) : Serializable

    data class Album(
        val id: String,
        val title: String,
    ) : Serializable

    data class ExternalVideoPlaybackFormat(
        val id: String,
        val url: String,
        val mimeType: String?,
        val extension: String,
        val resolution: String,
        val height: Int,
        val bitrate: Int,
        val videoOnly: Boolean,
        val adaptiveManifest: Boolean,
        val audioUrl: String?,
        val audioMimeType: String?,
        val audioExtension: String?,
        val codec: String? = null,
        val audioCodec: String? = null,
    ) : Serializable

    fun toSongEntity() =
        SongEntity(
            id = id,
            title = title,
            duration = duration,
            thumbnailUrl = thumbnailUrl,
            albumId = album?.id,
            albumName = album?.title,
            explicit = explicit,
            liked = liked,
            likedDate = likedDate,
            inLibrary = inLibrary,
            libraryAddToken = libraryAddToken,
            libraryRemoveToken = libraryRemoveToken,
            isVideo = isVideoSong,
            externalVideoSource = externalVideoSource,
            externalVideoServiceId = externalVideoServiceId,
            externalVideoPageId = externalVideoPageId,
            externalVideoFormatId = externalVideoFormatId,
            externalVideoPageUrl = externalVideoPageUrl,
            externalVideoStreamUrl = externalVideoStreamUrl,
            externalVideoMimeType = externalVideoMimeType,
            externalVideoCodec = externalVideoCodec,
            externalVideoAudioCodec = externalVideoAudioCodec,
            externalVideoExtension = externalVideoExtension,
            externalVideoResolution = externalVideoResolution,
            externalVideoAdaptive = externalVideoAdaptive,
        )
}

fun Song.toMediaMetadata() =
    MediaMetadata(
        id = song.id,
        title = song.title,
        artists =
        artists.map {
            MediaMetadata.Artist(
                id = it.id,
                name = it.name,
            )
        },
        duration = song.duration,
        thumbnailUrl = song.thumbnailUrl,
        album =
        album?.let {
            MediaMetadata.Album(
                id = it.id,
                title = it.title,
            )
        } ?: song.albumId?.let { albumId ->
            MediaMetadata.Album(
                id = albumId,
                title = song.albumName.orEmpty(),
            )
        },
        explicit = song.explicit,
        // Use a non-ATV type if isVideo is true to indicate it's a video song
        musicVideoType = if (song.isVideo) "MUSIC_VIDEO_TYPE_OMV" else null,
        suggestedBy = null,
        isExternalVideo = song.externalVideoSource != null || song.externalVideoStreamUrl != null,
        externalVideoSource = song.externalVideoSource,
        externalVideoServiceId = song.externalVideoServiceId,
        externalVideoPageId = song.externalVideoPageId,
        externalVideoFormatId = song.externalVideoFormatId,
        externalVideoPageUrl = song.externalVideoPageUrl,
        externalVideoStreamUrl = song.externalVideoStreamUrl,
        externalVideoMimeType = song.externalVideoMimeType,
        externalVideoCodec = song.externalVideoCodec,
        externalVideoAudioCodec = song.externalVideoAudioCodec,
        externalVideoExtension = song.externalVideoExtension,
        externalVideoResolution = song.externalVideoResolution,
        externalVideoAdaptive = song.externalVideoAdaptive,
        externalVideoFormats = if (song.externalVideoStreamUrl != null) listOf(
            MediaMetadata.ExternalVideoPlaybackFormat(
                id = song.externalVideoFormatId ?: "saved-video",
                url = song.externalVideoStreamUrl,
                mimeType = song.externalVideoMimeType,
                extension = song.externalVideoExtension.orEmpty(),
                resolution = song.externalVideoResolution.orEmpty(),
                height = 0,
                bitrate = 0,
                videoOnly = false,
                adaptiveManifest = song.externalVideoAdaptive,
                audioUrl = null,
                audioMimeType = null,
                audioExtension = null,
                codec = song.externalVideoCodec,
                audioCodec = song.externalVideoAudioCodec,
            )
        ) else emptyList(),
    )

fun SongItem.toMediaMetadata() =
    MediaMetadata(
        id = id,
        title = title,
        artists =
        artists.map {
            MediaMetadata.Artist(
                id = it.id,
                name = it.name,
            )
        },
        duration = duration ?: -1,
        thumbnailUrl = thumbnail.resize(1200, 1200),
        album =
        album?.let {
            MediaMetadata.Album(
                id = it.id,
                title = it.name,
            )
        },
        explicit = explicit,
        setVideoId = setVideoId,
        musicVideoType = musicVideoType,
        libraryAddToken = libraryAddToken,
        libraryRemoveToken = libraryRemoveToken,
        suggestedBy = null
    )
