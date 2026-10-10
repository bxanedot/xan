package com.music.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPublicPlaylistClientTest {
    private val playlistId = "37i9dQZF1DXcBWIGoYBM5M"

    @Test
    fun parsesPublicEmbedDataWithoutAuthentication() {
        val html = """
            <html><script id="__NEXT_DATA__" type="application/json">
            {"props":{"pageProps":{"state":{"data":{"entity":{
              "type":"playlist","id":"$playlistId","name":"Morning Mix",
              "coverArt":{"sources":[{"url":"https://i.scdn.co/image/cover"}]},
              "trackList":[
                {"entityType":"track","uri":"spotify:track:11hcBLPtbMp4aQI6zGQLub","title":"Patient Zero","subtitle":"Taylor Swift","duration":225868},
                {"entityType":"episode","uri":"spotify:episode:abcdefghijabcdefghij","title":"Podcast","subtitle":"Host","duration":1000},
                {"entityType":"track","uri":"spotify:track:70cHKK8bHAfJrOGVnfRG9J","title":"Nicole Kidman","subtitle":"ADÉLA","duration":181270}
              ]
            }}}}}}
            </script></html>
        """.trimIndent()

        val playlist = SpotifyPublicPlaylistClient.parseHtml(playlistId, html)

        assertEquals("Morning Mix", playlist?.name)
        assertEquals("https://i.scdn.co/image/cover", playlist?.thumbnailUrl)
        assertEquals(2, playlist?.tracks?.size)
        assertEquals("Patient Zero", playlist?.tracks?.first()?.title)
        assertEquals("Taylor Swift", playlist?.tracks?.first()?.artist)
        assertEquals(225868, playlist?.tracks?.first()?.durationMs)
        assertFalse(playlist?.mayBeTruncated ?: true)
    }

    @Test
    fun rejectsUnexpectedPlaylistAndMissingData() {
        val otherId = "0vvXsWCC9xrXsKd4FyS8kM"
        val html = """<script type='application/json' id='__NEXT_DATA__'>{"props":{"pageProps":{"state":{"data":{"entity":{"type":"playlist","id":"$otherId","name":"Other","trackList":[]}}}}}}</script>"""

        assertNull(SpotifyPublicPlaylistClient.parseHtml(playlistId, html))
        assertNull(SpotifyPublicPlaylistClient.parseHtml(playlistId, "<html>Unavailable</html>"))
    }

    @Test
    fun flagsFiftyVisibleTracksAsPotentiallyIncomplete() {
        val tracks = (1..50).joinToString(",") { index ->
            """{"entityType":"track","uri":"spotify:track:${index.toString().padStart(22, '0')}","title":"Song $index","subtitle":"Artist","duration":120000}"""
        }
        val html = """<script id="__NEXT_DATA__">{"props":{"pageProps":{"state":{"data":{"entity":{"type":"playlist","id":"$playlistId","name":"Long Playlist","trackList":[$tracks]}}}}}}</script>"""

        val playlist = SpotifyPublicPlaylistClient.parseHtml(playlistId, html)

        assertEquals(50, playlist?.tracks?.size)
        assertTrue(playlist?.mayBeTruncated == true)
    }
}
