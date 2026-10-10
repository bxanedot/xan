package app.xan.music.ui.component

import app.xan.music.ui.screens.Screens
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationMagnetTest {
    @Test
    fun `magnet contains only the five required navigation destinations`() {
        assertEquals(
            listOf(
                Screens.Home,
                Screens.Search,
                Screens.Library,
                Screens.Downloads,
                Screens.Settings,
            ),
            MagnetNavigationScreens,
        )
        assertEquals(
            listOf("home", "search_input", "library", "auto_playlist/downloaded", "settings"),
            MagnetNavigationScreens.map(Screens::route),
        )
        assertEquals(
            setOf("home", "search_input", "library", "songs"),
            Screens.MainRoutes,
        )
    }
}
