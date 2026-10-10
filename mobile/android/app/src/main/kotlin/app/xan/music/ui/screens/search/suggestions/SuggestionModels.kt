/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.ui.screens.search.suggestions

data class SuggestionTrack(
    val rank: Int,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val appleMusicUrl: String? = null
)

data class SuggestionArtist(
    val rank: Int,
    val name: String,
    val thumbnailUrl: String?
)

data class SuggestionAlbum(
    val rank: Int,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val appleMusicUrl: String? = null
)
