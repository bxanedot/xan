package app.xan.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosSpotifyPlaylistImportTest {
    private val playlistId = "37i9dQZF1DXcBWIGoYBM5M"

    @Test
    fun parsesSpotifyIdsUrisAndLocalizedHttpsLinks() {
        assertEquals(playlistId, parseIosSpotifyPlaylistId(playlistId))
        assertEquals(playlistId, parseIosSpotifyPlaylistId("spotify:playlist:$playlistId"))
        assertEquals(playlistId, parseIosSpotifyPlaylistId("https://open.spotify.com/playlist/$playlistId?si=abc"))
        assertEquals(playlistId, parseIosSpotifyPlaylistId("https://open.spotify.com/intl-de/playlist/$playlistId"))
        assertEquals(playlistId, parseIosSpotifyPlaylistId("https://www.spotify.com/playlist/$playlistId"))
        assertNull(parseIosSpotifyPlaylistId("http://open.spotify.com/playlist/$playlistId"))
        assertNull(parseIosSpotifyPlaylistId("https://example.com/playlist/$playlistId"))
        assertNull(parseIosSpotifyPlaylistId("spotify:track:$playlistId"))
    }

    @Test
    fun parsesOnlyTheRequestedPublicPlaylistAndItsTrackPreview() {
        val html = """
            <html><script id="__NEXT_DATA__" type="application/json">
            {"props":{"pageProps":{"state":{"data":{"entity":{
              "type":"playlist","id":"$playlistId","name":"Morning Mix",
              "trackList":[
                {"entityType":"track","uri":"spotify:track:11hcBLPtbMp4aQI6zGQLub","title":"Patient Zero","subtitle":"Taylor Swift","duration":225868},
                {"entityType":"episode","uri":"spotify:episode:abcdefghijabcdefghij","title":"Podcast","subtitle":"Host","duration":1000},
                {"entityType":"track","uri":"spotify:track:70cHKK8bHAfJrOGVnfRG9J","title":"Nicole Kidman","subtitle":"ADÉLA","duration":181270}
              ]
            }}}}}}
            </script></html>
        """.trimIndent()
        val playlist = parseIosSpotifyPlaylistHtml(playlistId, html)

        assertEquals("Morning Mix", playlist?.name)
        assertEquals(2, playlist?.tracks?.size)
        assertEquals("Patient Zero", playlist?.tracks?.first()?.title)
        assertEquals("Taylor Swift", playlist?.tracks?.first()?.artist)
        assertEquals(225868, playlist?.tracks?.first()?.durationMillis)
        assertFalse(playlist?.mayBeTruncated ?: true)
        assertNull(parseIosSpotifyPlaylistHtml("0vvXsWCC9xrXsKd4FyS8kM", html))
        assertNull(parseIosSpotifyPlaylistHtml(playlistId, "<html>Unavailable</html>"))
    }

    @Test
    fun reportsPotentiallyTruncatedFiftyTrackPreviews() {
        val tracks = (1..50).joinToString(",") { index ->
            """{"entityType":"track","uri":"spotify:track:${index.toString().padStart(22, '0')}","title":"Song $index","subtitle":"Artist","duration":120000}"""
        }
        val html = """<script id="__NEXT_DATA__">{"props":{"pageProps":{"state":{"data":{"entity":{"type":"playlist","id":"$playlistId","name":"Long Playlist","trackList":[$tracks]}}}}}}</script>"""

        val playlist = parseIosSpotifyPlaylistHtml(playlistId, html)

        assertEquals(50, playlist?.tracks?.size)
        assertTrue(playlist?.mayBeTruncated == true)
    }

    @Test
    fun matchingRequiresSimilarTitleAndArtistAndRewardsDurationAgreement() {
        val source = IosSpotifyPlaylistTrack("11hcBLPtbMp4aQI6zGQLub", "Patient Zero", "Taylor Swift", 225_000)
        val exact = IosMusicSearchTrack("abcdefghijk", "Patient Zero", "Taylor Swift", null, 225, null)
        val wrongArtist = IosMusicSearchTrack("abcdefghijl", "Patient Zero", "Other Artist", null, 225, null)
        val wrongSong = IosMusicSearchTrack("abcdefghijm", "Another Song", "Taylor Swift", null, 225, null)

        assertTrue(scoreIosSpotifyMatch(source, exact) > 0.99)
        assertEquals(0.0, scoreIosSpotifyMatch(source, wrongArtist))
        assertEquals(0.0, scoreIosSpotifyMatch(source, wrongSong))
    }
}
