package com.music.spotify

import java.net.URI

/** Normalizes Spotify playlist IDs, URIs, and share URLs to a canonical playlist ID. */
object SpotifyPlaylistRefParser {
    private val playlistIdRegex = Regex("^[A-Za-z0-9]{22}$")

    fun parse(value: String?): String? {
        val input = value?.trim().orEmpty()
        if (input.isEmpty()) return null

        if (playlistIdRegex.matches(input)) return input

        if (input.startsWith("spotify:", ignoreCase = true)) {
            val parts = input.split(':')
            return parts
                .takeIf { it.size == 3 && it[1].equals("playlist", ignoreCase = true) }
                ?.get(2)
                ?.takeIf(playlistIdRegex::matches)
        }

        val uri = runCatching { URI(input) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        val host = uri.host?.lowercase() ?: return null
        if (host !in setOf("open.spotify.com", "spotify.com", "www.spotify.com")) return null

        val segments = uri.path
            .orEmpty()
            .split('/')
            .filter { it.isNotBlank() }
        val playlistIndex = segments.indexOfFirst { it.equals("playlist", ignoreCase = true) }
        if (playlistIndex < 0) return null
        return segments
            .getOrNull(playlistIndex + 1)
            ?.takeIf(playlistIdRegex::matches)
    }
}
