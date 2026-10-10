/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.lyrics

import android.content.Context

interface LyricsProvider {
    val name: String

    fun isEnabled(context: Context): Boolean

    suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
    ): Result<String>

    /** Rich provider context without forcing older providers to adopt new fields. */
    suspend fun getLyrics(track: LyricsTrackMetadata): Result<String> =
        getLyrics(track.id, track.title, track.artist, track.duration, track.album)

    suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
        callback: (String) -> Unit,
    ) {
        getLyrics(id, title, artist, duration, album).onSuccess(callback)
    }
}

data class LyricsTrackMetadata(
    val id: String,
    val title: String,
    val artist: String,
    val duration: Int,
    val album: String? = null,
    val isrc: String? = null,
)
