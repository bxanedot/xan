package app.xan.music.playback

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.room.withTransaction
import app.xan.music.R
import app.xan.music.db.MusicDatabase
import app.xan.music.db.entities.ArtistEntity
import app.xan.music.db.entities.SongArtistMap
import app.xan.music.db.entities.SongEntity
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Rebuild XAN audio download records from preserved Music/XAN media after reinstall. */
suspend fun reconcileDownloadedAudioFiles(context: Context, database: MusicDatabase) = withContext(Dispatchers.IO) {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return@withContext

    val scopedStorage = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val projection = buildList {
        add(MediaStore.Audio.Media._ID)
        add(MediaStore.Audio.Media.TITLE)
        add(MediaStore.Audio.Media.DISPLAY_NAME)
        add(MediaStore.Audio.Media.ARTIST)
        add(MediaStore.Audio.Media.DURATION)
        add(MediaStore.Audio.Media.ALBUM)
        add(MediaStore.MediaColumns.SIZE)
        add(MediaStore.MediaColumns.DATE_ADDED)
        if (scopedStorage) add(MediaStore.MediaColumns.RELATIVE_PATH)
        else add(MediaStore.Audio.Media.DATA)
    }.toTypedArray()
    val collections = if (scopedStorage) {
        MediaStore.getExternalVolumeNames(context).map { volume -> MediaStore.Audio.Media.getContentUri(volume) }
    } else {
        listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
    }

    for (collection in collections) {
        val selection = if (scopedStorage) "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        else "${MediaStore.Audio.Media.DATA} LIKE ?"
        val selectionArgs = if (scopedStorage) arrayOf("${Environment.DIRECTORY_MUSIC}/XAN/")
        else arrayOf("%/${Environment.DIRECTORY_MUSIC}/XAN/%")
        try {
            context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val artistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(nameIndex).orEmpty()
                    val encodedId = Regex("\\[xan-id-([A-Za-z0-9_-]+)]").find(displayName)?.groupValues?.getOrNull(1)
                        ?: continue
                    val mediaId = runCatching {
                        String(java.util.Base64.getUrlDecoder().decode(encodedId), Charsets.UTF_8)
                    }.getOrNull()?.takeIf(String::isNotBlank) ?: continue
                    val byteCount = cursor.getLong(sizeIndex)
                    if (byteCount <= 0) continue

                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idIndex))
                    val title = cursor.getString(titleIndex)?.trim().takeUnless { it.isNullOrBlank() }
                        ?: displayName.substringBeforeLast(" [xan-id-").substringBeforeLast('.')
                    val artist = cursor.getString(artistIndex)?.trim()
                        ?.takeUnless { it.isBlank() || it.equals("<unknown>", true) || it.equals("Unknown Artist", true) }
                    val album = cursor.getString(albumIndex)?.trim()?.takeIf(String::isNotBlank)
                    val duration = (cursor.getLong(durationIndex) / 1000L).toInt().takeIf { it > 0 }
                    val dateAdded = cursor.getLong(dateIndex).takeIf { it > 0 }
                        ?.let { runCatching { LocalDateTime.ofInstant(Instant.ofEpochSecond(it), ZoneId.systemDefault()) }.getOrNull() }
                    val existing = database.getSongByIdBlocking(mediaId)

                    database.withTransaction {
                        val current = existing?.song
                        upsert(
                            (current ?: SongEntity(id = mediaId, title = title)).copy(
                                title = current?.title?.takeIf(String::isNotBlank) ?: title,
                                duration = current?.duration?.takeIf { it > 0 } ?: duration ?: -1,
                                albumName = current?.albumName?.takeIf(String::isNotBlank) ?: album,
                                thumbnailUrl = current?.thumbnailUrl?.takeIf(String::isNotBlank)
                                    ?: "android.resource://${context.packageName}/${R.drawable.xan_default_album_art}",
                                isLocal = true,
                                isDownloaded = true,
                                persistentMediaUri = uri.toString(),
                                dateDownload = current?.dateDownload ?: dateAdded ?: LocalDateTime.now(),
                            ),
                        )
                        if (existing?.artists.isNullOrEmpty() && !artist.isNullOrBlank()) {
                            val artistId = artistByName(artist)?.id ?: ArtistEntity.generateArtistId().also { id ->
                                insert(ArtistEntity(id = id, name = artist))
                            }
                            insert(SongArtistMap(songId = mediaId, artistId = artistId, position = 0))
                        }
                    }
                }
            }
        } catch (_: SecurityException) {
            return@withContext
        }
    }
}
