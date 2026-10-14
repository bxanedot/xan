package app.xan.music.ui.component

import app.xan.music.ui.screens.Screens
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationMagnetTest {
    @Test
    fun `magnet contains primary destinations without settings downloads or player actions`() {
        assertEquals(
            listOf(
                Screens.Home,
                Screens.Search,
                Screens.Library,
                Screens.Recognition,
            ),
            MagnetNavigationScreens,
        )
        assertEquals(
            listOf("home", "search_input", "library", "recognition"),
            MagnetNavigationScreens.map(Screens::route),
        )
        assertEquals(
            setOf("home", "search_input", "library", "songs"),
            Screens.MainRoutes,
        )
    }

    @Test
    fun `popup remains anchored above the chosen bottom corner`() {
        val window = IntSize(400, 800)
        val popup = IntSize(180, 220)
        assertEquals(
            IntOffset(204, 492),
            magnetPopupPosition(IntRect(328, 728, 384, 784), window, popup, onLeft = false, verticalOffset = -72),
        )
        assertEquals(
            IntOffset(16, 492),
            magnetPopupPosition(IntRect(16, 728, 72, 784), window, popup, onLeft = true, verticalOffset = -72),
        )
        assertEquals(
            IntOffset(204, 564),
            magnetPopupPosition(IntRect(328, 728, 384, 784), window, popup, onLeft = false, verticalOffset = 0),
        )
    }

    @Test
    fun `popup clamps to a small window without changing to a centered layout`() {
        assertEquals(
            IntOffset(0, 0),
            magnetPopupPosition(
                IntRect(144, 124, 200, 180), IntSize(200, 180), IntSize(240, 260),
                onLeft = false, verticalOffset = -72,
            ),
        )
    }
}
