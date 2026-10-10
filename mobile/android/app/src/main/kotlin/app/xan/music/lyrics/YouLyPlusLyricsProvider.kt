/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.lyrics

import android.content.Context
import com.music.youlyplus.YouLyPlus
import app.xan.music.constants.EnableYouLyPlusKey
import app.xan.music.utils.dataStore
import app.xan.music.utils.get

object YouLyPlusLyricsProvider : LyricsProvider {
    override val name = "YouLyPlus"

    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableYouLyPlusKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = YouLyPlus.getLyrics(title, artist, duration, album, id)

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
        callback: (String) -> Unit,
    ) {
        YouLyPlus.getAllLyrics(title, artist, duration, album, id, null, callback)
    }
}

