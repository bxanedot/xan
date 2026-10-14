/*
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.playback

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import app.xan.music.db.MusicDatabase
import app.xan.music.db.entities.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

data class DownloadStorageMigrationResult(
    val moved: Int,
    val leftInSource: Int,
)

/** Moves completed XAN copies only after verifying the new copy against its source. */
object DownloadStorageMigration {
    private val mediaToken = Regex("\\[xan-id-([A-Za-z0-9_-]+)]")

    suspend fun migrate(
        context: Context,
        database: MusicDatabase,
        keepAfterUninstall: Boolean,
        deleteSharedOriginalsConfirmed: Boolean = false,
    ): DownloadStorageMigrationResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!keepAfterUninstall && !deleteSharedOriginalsConfirmed) {
            throw IOException("Confirm before moving shared downloads into app-only storage.")
        }
        val songs = database.downloadedSongsByNameAsc().first().associateBy { it.song.id }
        if (keepAfterUninstall) migratePrivateToPublic(app, database, songs.values)
        else migratePublicToPrivate(app, database, songs)
    }

    internal fun mediaIdFromFileName(fileName: String): String? = runCatching {
        mediaToken.find(fileName)?.groupValues?.getOrNull(1)
            ?.let { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }
            ?.takeIf(String::isNotBlank)
    }.getOrNull()

    private fun migratePublicToPrivate(
        context: Context,
        database: MusicDatabase,
        songs: Map<String, Song>,
    ): DownloadStorageMigrationResult {
        val entries = publicEntries(context)
        var moved = 0
        var left = 0
        for (entry in entries) {
            val mediaId = mediaIdFromFileName(entry.displayName)
            val song = mediaId?.let(songs::get)
            if (song == null || song.song.isVideo != entry.isVideo) {
                left++
                continue
            }
            val targetDirectory = privateDirectory(context, entry.isVideo).apply {
                if (!mkdirs() && !isDirectory) throw IOException("Could not create private XAN download storage.")
            }
            val destination = File(targetDirectory, safeFileName(entry.displayName))
            val expectedSize = entry.size.takeIf { it > 0 } ?: sourceSize(context, entry.uri)
            val expectedHash = streamHash(context.contentResolver.openInputStream(entry.uri)
                ?: throw IOException("Could not read ${entry.displayName} for migration."))
            ensureSpace(targetDirectory, expectedSize)
            copyAtomically(context.contentResolver.openInputStream(entry.uri)
                ?: throw IOException("Could not reopen ${entry.displayName} for migration."), destination, expectedHash, expectedSize)
            updateSongLocation(database, song, Uri.fromFile(destination), entry.isVideo)
            if (!removeSource(context, entry)) left++
            moved++
        }
        return DownloadStorageMigrationResult(moved, left)
    }

    private fun migratePrivateToPublic(
        context: Context,
        database: MusicDatabase,
        songs: Collection<Song>,
    ): DownloadStorageMigrationResult {
        var moved = 0
        var left = 0
        for (song in songs) {
            val isVideo = song.song.isVideo
            val currentUri = if (isVideo) song.song.externalVideoStreamUrl else song.song.persistentMediaUri
            val source = currentUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ?.takeIf { it.scheme.equals("file", true) }
                ?.path?.let(::File)
                ?: continue
            if (!isWithin(source, privateDirectory(context, isVideo))) continue
            if (!source.isFile || source.length() <= 0) {
                left++
                continue
            }
            val expectedHash = hashFile(source)
            val publicUri = publishPublicCopy(context, song, source, isVideo, expectedHash)
            updateSongLocation(database, song, publicUri, isVideo)
            if (!source.delete()) left++
            moved++
        }
        return DownloadStorageMigrationResult(moved, left)
    }

    private fun publicEntries(context: Context): List<PublicEntry> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return buildList {
                addAll(queryMediaStore(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, false))
                addAll(queryMediaStore(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true))
            }
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            throw IOException("Allow storage access so XAN can move shared downloads safely.")
        }
        return buildList {
            for ((kind, isVideo) in listOf(Environment.DIRECTORY_MUSIC to false, Environment.DIRECTORY_MOVIES to true)) {
                File(Environment.getExternalStoragePublicDirectory(kind), "XAN").listFiles()
                    .orEmpty().filter(File::isFile).forEach { file ->
                        add(PublicEntry(Uri.fromFile(file), file.name, file.length(), isVideo))
                    }
            }
        }
    }

    private fun queryMediaStore(context: Context, collection: Uri, isVideo: Boolean): List<PublicEntry> = buildList {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
        )
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf("${if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC}/XAN/"),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)) ?: continue
                val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
                add(PublicEntry(ContentUris.withAppendedId(collection, id), name, size, isVideo))
            }
        }
    }

    private fun publishPublicCopy(context: Context, song: Song, source: File, isVideo: Boolean, expectedHash: String): Uri {
        val displayName = safeFileName(source.name)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                throw IOException("Allow storage access so XAN can save shared downloads.")
            }
            val kind = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC
            val folder = File(Environment.getExternalStoragePublicDirectory(kind), "XAN").apply {
                if (!mkdirs() && !isDirectory) throw IOException("Could not create the public XAN folder.")
            }
            ensureSpace(folder, source.length())
            val expected = File(folder, displayName)
            if (expected.isFile && expected.length() == source.length() && hashFile(expected) == expectedHash) {
                return Uri.fromFile(expected)
            }
            val target = uniqueFile(folder, displayName)
            copyAtomically(source.inputStream(), target, expectedHash, source.length())
            return Uri.fromFile(target)
        }

        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        ensureSpace(context.getExternalFilesDir(null) ?: context.filesDir, source.length())
        findPublicCopy(context, collection, displayName, isVideo)?.let { existing ->
            val hash = context.contentResolver.openInputStream(existing)?.use(::streamHash)
            if (hash == expectedHash) return existing
        }
        val kind = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType(displayName, isVideo))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$kind/XAN")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (isVideo) {
                put(MediaStore.MediaColumns.TITLE, song.song.title)
            } else {
                put(MediaStore.Audio.Media.TITLE, song.song.title)
                put(MediaStore.Audio.Media.ARTIST, song.artists.joinToString(", ") { it.name })
                put(MediaStore.Audio.Media.ALBUM, song.album?.title.orEmpty())
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: throw IOException("Could not create a public XAN media copy.")
        try {
            val output = resolver.openFileDescriptor(uri, "w")?.use { descriptor ->
                FileOutputStream(descriptor.fileDescriptor).use { stream ->
                    source.inputStream().use { input -> input.copyTo(stream, 64 * 1024) }
                    stream.fd.sync()
                }
            } ?: throw IOException("Could not write the public XAN media copy.")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) <= 0) throw IOException("Could not finalize the public XAN media copy.")
            val savedHash = resolver.openInputStream(uri)?.use(::streamHash)
                ?: throw IOException("Could not verify the public XAN media copy.")
            if (savedHash != expectedHash) throw IOException("The public XAN media copy failed its integrity check.")
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun findPublicCopy(context: Context, collection: Uri, name: String, isVideo: Boolean): Uri? {
        val kind = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(name, "$kind/XAN/"),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return ContentUris.withAppendedId(collection, cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)))
            }
        }
        return null
    }

    private fun privateDirectory(context: Context, isVideo: Boolean): File {
        val kind = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC
        return File(context.getExternalFilesDir(kind) ?: context.filesDir, "XAN").canonicalFile
    }

    private fun updateSongLocation(database: MusicDatabase, song: Song, uri: Uri, isVideo: Boolean) {
        database.update(
            if (isVideo) song.song.copy(externalVideoStreamUrl = uri.toString())
            else song.song.copy(persistentMediaUri = uri.toString()),
        )
    }

    private fun removeSource(context: Context, entry: PublicEntry): Boolean =
        if (entry.uri.scheme.equals("file", true)) entry.uri.path?.let(::File)?.delete() == true
        else context.contentResolver.delete(entry.uri, null, null) > 0

    private fun sourceSize(context: Context, uri: Uri): Long =
        if (uri.scheme.equals("file", true)) uri.path?.let(::File)?.length() ?: 0L else 0L

    private fun ensureSpace(directory: File, bytes: Long) {
        if (bytes <= 0) throw IOException("The source download has no readable data.")
        val available = runCatching { StatFs(directory.absolutePath).availableBytes }.getOrDefault(0L)
        if (available < bytes + MIN_FREE_SPACE_BYTES) throw IOException("There is not enough free space to copy this download safely.")
    }

    private fun isWithin(file: File, directory: File): Boolean = runCatching {
        file.canonicalPath.startsWith(directory.canonicalPath.trimEnd(File.separatorChar) + File.separator)
    }.getOrDefault(false)

    private fun safeFileName(name: String): String = File(name.replace('\u0000', '_')).name
        .replace('/', '_').replace('\\', '_')
        .ifBlank { "XAN-download-${System.currentTimeMillis()}" }

    private fun uniqueFile(directory: File, name: String): File {
        val original = File(directory, name)
        if (!original.exists()) return original
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var suffix = 2
        while (true) {
            val candidate = File(directory, "$base ($suffix)$extension")
            if (!candidate.exists()) return candidate
            suffix++
        }
    }

    private fun mimeType(name: String, isVideo: Boolean): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: if (isVideo) "video/mp4" else "audio/mpeg"
    }

    private fun copyAtomically(input: InputStream, destination: File, expectedHash: String, expectedSize: Long) {
        val folder = destination.parentFile ?: throw IOException("The download destination is invalid.")
        if (!folder.exists() && !folder.mkdirs()) throw IOException("Could not create the download destination.")
        if (destination.isFile && destination.length() == expectedSize && hashFile(destination) == expectedHash) return
        val temporary = File(folder, ".${destination.name}.migration-partial")
        temporary.delete()
        try {
            val actualHash = input.use { source ->
                FileOutputStream(temporary).use { output ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        total += count
                    }
                    output.fd.sync()
                    if (expectedSize > 0 && total != expectedSize) throw IOException("The copied download has the wrong size.")
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
            }
            if (actualHash != expectedHash) throw IOException("The copied download failed its integrity check.")
            if (destination.exists() && !destination.delete()) throw IOException("Could not replace an old XAN copy.")
            if (!temporary.renameTo(destination)) throw IOException("Could not finalize the migrated XAN copy.")
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private fun hashFile(file: File): String = file.inputStream().use(::streamHash)

    private fun streamHash(input: InputStream): String = input.use { source ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = source.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class PublicEntry(val uri: Uri, val displayName: String, val size: Long, val isVideo: Boolean)

    private const val MIN_FREE_SPACE_BYTES = 4L * 1024L * 1024L
}
