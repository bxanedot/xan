package com.music.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyPlaylistRefParserTest {
    private val id = "37i9dQZF1DXcBWIGoYBM5M"

    @Test
    fun parsesRawId() {
        assertEquals(id, SpotifyPlaylistRefParser.parse(id))
    }

    @Test
    fun parsesSpotifyUri() {
        assertEquals(id, SpotifyPlaylistRefParser.parse("spotify:playlist:$id"))
    }

    @Test
    fun parsesWebUrlWithQuery() {
        assertEquals(
            id,
            SpotifyPlaylistRefParser.parse("https://open.spotify.com/playlist/$id?si=abc123"),
        )
    }

    @Test
    fun parsesLocalizedWebUrl() {
        assertEquals(
            id,
            SpotifyPlaylistRefParser.parse("https://open.spotify.com/intl-de/playlist/$id"),
        )
    }

    @Test
    fun rejectsMalformedAndForeignUrls() {
        assertNull(SpotifyPlaylistRefParser.parse("https://example.com/playlist/$id"))
        assertNull(SpotifyPlaylistRefParser.parse("https://open.spotify.com/track/$id"))
        assertNull(SpotifyPlaylistRefParser.parse("https://open.spotify.com/$id"))
        assertNull(SpotifyPlaylistRefParser.parse("spotify:track:$id"))
        assertNull(SpotifyPlaylistRefParser.parse("not-a-playlist"))
    }
}
