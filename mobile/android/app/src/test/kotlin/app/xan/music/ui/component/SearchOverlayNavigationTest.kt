package app.xan.music.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchOverlayNavigationTest {
    private val roots = setOf("home", "library", "settings")

    @Test fun `artist album and playlist selections immediately reveal the selected page`() {
        for (route in listOf("artist/{artistId}", "album/{albumId}", "online_playlist/{playlistId}")) {
            assertFalse(route, searchOverlayAfterNavigation(true, route, roots))
        }
    }
    @Test fun `search can stay open over a root tab and during startup`() {
        assertTrue(searchOverlayAfterNavigation(true, "home", roots))
        assertTrue(searchOverlayAfterNavigation(true, null, roots))
    }
    @Test fun `closed search is never reopened by navigation`() {
        assertFalse(searchOverlayAfterNavigation(false, "home", roots))
        assertFalse(searchOverlayAfterNavigation(false, "artist/{artistId}", roots))
    }
}
