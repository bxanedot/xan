package app.xan.desktop

import com.music.spotify.SpotifyPublicPlaylist
import com.music.spotify.SpotifyPublicTrack
import java.nio.file.Path

internal fun String.normalizeImportedText(): String = lowercase().filter(Char::isLetterOrDigit)

internal data class ImportedPlaylistEntry(
    val row: Int,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val location: String? = null,
    val durationSeconds: Int? = null,
    val isrc: String? = null,
)

internal data class PlaylistImportReport(
    val playlistName: String,
    val imported: List<MusicTrack>,
    val unresolved: List<String>,
) {
    val summary: String get() = "Imported ${imported.size} tracks; ${unresolved.size} unresolved."
}

internal data class SpotifyPlaylistImportReport(
    val playlist: SpotifyPublicPlaylist,
    val catalogPlaylistId: String,
    val matches: List<Pair<SpotifyPublicTrack, MusicTrack>>,
    val unmatched: List<SpotifyPublicTrack>,
)

internal fun isSpotifyTrackMatch(source: SpotifyPublicTrack, candidate: MusicTrack): Boolean {
    val title = source.title.normalizeImportedText()
    val artist = source.artist.normalizeImportedText()
    val candidateArtist = candidate.artist.split(',', '&', '/').map(String::normalizeImportedText)
    val durationMatches = candidate.durationSeconds == null || kotlin.math.abs(candidate.durationSeconds * 1000L - source.durationMs) <= 8000L
    return title.isNotEmpty() && title == candidate.title.normalizeImportedText() &&
        (artist.isEmpty() || candidateArtist.any { it == artist || it.contains(artist) || artist.contains(it) }) && durationMatches
}

/** Parsers for common exported playlist formats. Row numbers are retained for actionable reports. */
internal object PlaylistImport {
    fun parseM3u(text: String): List<ImportedPlaylistEntry> {
        val result = mutableListOf<ImportedPlaylistEntry>()
        var pendingTitle: String? = null
        var pendingDuration: Int? = null
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val value = line.substringAfter(':')
                    pendingDuration = value.substringBefore(',').toIntOrNull()?.takeIf { it >= 0 }
                    pendingTitle = value.substringAfter(',', "").trim().takeIf(String::isNotEmpty)
                }
                line.startsWith('#') -> Unit
                else -> {
                    val (artist, title) = splitArtistTitle(pendingTitle)
                    result += ImportedPlaylistEntry(index + 1, title, artist, location = line, durationSeconds = pendingDuration)
                    pendingTitle = null
                    pendingDuration = null
                }
            }
        }
        return result
    }

    fun parseCsv(text: String): List<ImportedPlaylistEntry> {
        val rows = parseCsvRows(text).filter { row -> row.any(String::isNotBlank) }
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map(::normalizeHeader)
        val recognized = header.any { it in HEADER_ALIASES.keys }
        val columns = if (recognized) header else emptyList()
        val data = if (recognized) rows.drop(1).withIndex().map { it.index + 2 to it.value }
            else rows.withIndex().map { it.index + 1 to it.value }
        return data.mapNotNull { (rowNumber, values) ->
            fun value(vararg keys: String): String? {
                val index = columns.indexOfFirst { it in keys }
                return values.getOrNull(index)?.trim()?.takeIf(String::isNotEmpty)
            }
            val title = value("title", "track", "trackname", "name") ?: values.getOrNull(0)?.trim()?.takeIf(String::isNotEmpty)
            val artist = value("artist", "artists", "artistname") ?: values.getOrNull(1)?.trim()?.takeIf(String::isNotEmpty)
            val album = value("album", "albumname")
            val location = value("uri", "url", "location", "path", "file")
                ?: values.firstOrNull { it.trim().startsWith("http", true) || it.trim().startsWith("file:", true) }
            val duration = value("durationseconds", "seconds", "duration", "length")?.let(::parseDuration)
            val isrc = value("isrc", "recordingcode")
            if (title == null && artist == null && location == null && isrc == null) null
            else ImportedPlaylistEntry(rowNumber, title, artist, album, location, duration, isrc)
        }
    }

    fun resolveLocal(entry: ImportedPlaylistEntry, playlistDirectory: Path): Path? {
        val raw = entry.location ?: return null
        val candidate = runCatching {
            when {
                raw.startsWith("file:", true) -> Path.of(java.net.URI(raw))
                raw.startsWith("http://", true) || raw.startsWith("https://", true) -> return null
                else -> Path.of(raw).let { if (it.isAbsolute) it else playlistDirectory.resolve(it) }
            }.toAbsolutePath().normalize()
        }.getOrNull() ?: return null
        if (!java.nio.file.Files.isRegularFile(candidate)) return null
        val extension = candidate.fileName.toString().substringAfterLast('.', "").lowercase()
        return candidate.takeIf { extension in AUDIO_EXTENSIONS }
    }

    private fun parseCsvRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val char = text[i]
            when {
                char == '"' && quoted && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                char == '"' -> quoted = !quoted
                (char == ',' || char == '\t') && !quoted -> { row += cell.toString(); cell.setLength(0) }
                (char == '\n' || char == '\r') && !quoted -> {
                    if (char == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += cell.toString(); cell.setLength(0); rows += row; row = mutableListOf()
                }
                else -> cell.append(char)
            }
            i++
        }
        row += cell.toString()
        if (row.any(String::isNotEmpty)) rows += row
        require(!quoted) { "CSV contains an unclosed quoted field." }
        return rows
    }

    private fun normalizeHeader(value: String) = value.trim().removePrefix("\uFEFF")
        .lowercase().filter(Char::isLetterOrDigit)

    private fun parseDuration(value: String): Int? {
        value.toIntOrNull()?.let { return it.takeIf { seconds -> seconds >= 0 } }
        val parts = value.split(':').map(String::toLongOrNull)
        if (parts.any { it == null } || parts.isEmpty() || parts.size > 3) return null
        val nums = parts.filterNotNull()
        val seconds = nums.fold(0L) { total, part -> total * 60 + part }
        return seconds.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
    }

    private fun splitArtistTitle(value: String?): Pair<String?, String?> {
        if (value == null) return null to null
        val separator = value.indexOf(" - ")
        return if (separator > 0) value.substring(0, separator).trim() to value.substring(separator + 3).trim()
        else null to value
    }

    private val HEADER_ALIASES = mapOf(
        "title" to "title", "track" to "title", "trackname" to "title", "name" to "title",
        "artist" to "artist", "artists" to "artist", "artistname" to "artist",
        "album" to "album", "albumname" to "album", "uri" to "uri", "url" to "url",
        "location" to "location", "path" to "path", "file" to "file",
        "durationseconds" to "durationseconds", "seconds" to "seconds", "duration" to "duration", "length" to "length",
        "isrc" to "isrc", "recordingcode" to "recordingcode",
    )
    private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "opus", "m4a", "mp4", "aif", "aiff", "wav", "wma", "dsf", "dsd")
}
