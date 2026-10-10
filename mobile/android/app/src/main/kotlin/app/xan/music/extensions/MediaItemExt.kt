/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.extensions

import android.os.Bundle
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import com.music.innertube.models.SongItem
import app.xan.music.BuildConfig
import app.xan.music.R
import app.xan.music.db.entities.Song
import app.xan.music.models.MediaMetadata
import app.xan.music.models.toMediaMetadata
import app.xan.music.ui.utils.resize

private val defaultArtworkUri: Uri =
    Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.xan_default_album_art}")

val MediaItem.metadata: MediaMetadata?
    get() = (localConfiguration?.tag as? MediaMetadata) ?: run {
        val mediaMetadata = mediaMetadata
        if (mediaMetadata.title == null && mediaMetadata.artist == null) return@run null
        
        MediaMetadata(
            id = mediaId,
            title = mediaMetadata.title?.toString().orEmpty(),
            artists = listOf(MediaMetadata.Artist(id = null, name = mediaMetadata.artist?.toString().orEmpty())),
            duration = -1,
            thumbnailUrl = mediaMetadata.artworkUri?.toString(),
            album = mediaMetadata.albumTitle?.let { MediaMetadata.Album(id = "", title = it.toString()) },
            explicit = false,
        )
    }

fun Song.toMediaItem() = MediaItem.Builder()
    .setMediaId(song.id)
    .setUri(song.externalVideoStreamUrl ?: song.id)
    .apply { if (!song.externalVideoAdaptive) setCustomCacheKey(song.id) }
    .setMimeType(song.externalVideoMimeType)
    .setTag(toMediaMetadata())
    .setMediaMetadata(
        androidx.media3.common.MediaMetadata.Builder()
            .setTitle(song.title)
            .setSubtitle(artists.joinToString { it.name })
            .setArtist(artists.joinToString { it.name })
            .setArtworkUri(song.thumbnailUrl?.takeIf(String::isNotBlank)?.toUri() ?: defaultArtworkUri)
            .setAlbumTitle(song.albumName)
            .setAlbumArtist(artists.firstOrNull()?.name)
            .setDisplayTitle(song.title)
            .setMediaType(if (song.isVideo) androidx.media3.common.MediaMetadata.MEDIA_TYPE_VIDEO else MEDIA_TYPE_MUSIC)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setExtras(Bundle().apply {
                putString("artwork_uri", song.thumbnailUrl)
            })
            .build()
    )
    .build()

fun SongItem.toMediaItem() = MediaItem.Builder()
    .setMediaId(id)
    .setUri(id)
    .setCustomCacheKey(id)
    .setTag(toMediaMetadata())
    .setMediaMetadata(
        androidx.media3.common.MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(artists.joinToString { it.name })
            .setArtist(artists.joinToString { it.name })
            .setArtworkUri(thumbnail.resize(1200, 1200).toUri())
            .setAlbumTitle(album?.name)
            .setAlbumArtist(artists.firstOrNull()?.name)
            .setDisplayTitle(title)
            .setMediaType(MEDIA_TYPE_MUSIC)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setExtras(Bundle().apply {
                putString("artwork_uri", thumbnail.resize(1200, 1200))
            })
            .build()
    )
    .build()

fun MediaMetadata.toMediaItem() = MediaItem.Builder()
    .setMediaId(id)
    .setUri(externalVideoStreamUrl ?: id)
    .apply { if (!externalVideoAdaptive) setCustomCacheKey(id) }
    .setMimeType(externalVideoMimeType)
    .setTag(this)
    .setMediaMetadata(
        androidx.media3.common.MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(artists.joinToString { it.name })
            .setArtist(artists.joinToString { it.name })
            .setArtworkUri(thumbnailUrl?.takeIf(String::isNotBlank)?.toUri() ?: defaultArtworkUri)
            .setAlbumTitle(album?.title)
            .setAlbumArtist(artists.firstOrNull()?.name)
            .setDisplayTitle(title)
            .setMediaType(if (isVideoSong) androidx.media3.common.MediaMetadata.MEDIA_TYPE_VIDEO else MEDIA_TYPE_MUSIC)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setExtras(Bundle().apply {
                thumbnailUrl?.let { putString("artwork_uri", it) }
            })
            .build()
    )
    .build()
