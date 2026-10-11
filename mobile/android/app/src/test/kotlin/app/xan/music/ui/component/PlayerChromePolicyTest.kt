package app.xan.music.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerChromePolicyTest {
    @Test
    fun `active player remains on playlist and media detail destinations`() {
        listOf(
            "home", "library", "songs", "search_input", "search/{query}",
            "local_playlist/{playlistId}", "online_playlist/{playlistId}",
            "auto_playlist/{playlist}", "auto_playlist/downloaded", "cache_playlist/{playlist}",
            "top_playlist/{top}", "album/{albumId}", "artist/{artistId}",
            "artist/{artistId}/songs", "local_folder/{path}", "browse/{browseId}",
        ).forEach { route ->
            assertTrue(route, shouldShowPlayerAccessory(route, true, false, false))
        }
    }

    @Test
    fun `settings and focused flows have no global player chrome`() {
        listOf(
            null, "settings", "settings/about", "settings/appearance", "login", "account",
            "recognition", "recognition_history", "equalizer", "wrapped", "update", "ambient_mode",
        ).forEach { route ->
            assertFalse(route, shouldShowPlayerAccessory(route, true, false, false))
        }
    }

    @Test
    fun `absent media dismissal and query keyboard each hide the accessory`() {
        assertFalse(shouldShowPlayerAccessory("local_playlist/{playlistId}", false, false, false))
        assertFalse(shouldShowPlayerAccessory("local_playlist/{playlistId}", true, true, false))
        assertFalse(shouldShowPlayerAccessory("search_input", true, false, true))
    }
}
