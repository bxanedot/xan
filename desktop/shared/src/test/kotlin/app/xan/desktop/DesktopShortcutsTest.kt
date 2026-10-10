package app.xan.desktop

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopShortcutsTest {
    @Test
    fun mediaAndNavigationShortcutsRequireTheExplicitChord() {
        assertEquals(DesktopShortcutAction.PLAY_PAUSE, resolveDesktopShortcut(Key.Spacebar, primaryModifier = true, altPressed = true))
        assertEquals(DesktopShortcutAction.PREVIOUS_TRACK, resolveDesktopShortcut(Key.DirectionLeft, primaryModifier = true, altPressed = true))
        assertEquals(DesktopShortcutAction.NEXT_TRACK, resolveDesktopShortcut(Key.DirectionRight, primaryModifier = true, altPressed = true))
        assertEquals(DesktopShortcutAction.SEARCH, resolveDesktopShortcut(Key.S, primaryModifier = true, altPressed = true))
        assertNull(resolveDesktopShortcut(Key.Spacebar, primaryModifier = true, altPressed = false))
        assertNull(resolveDesktopShortcut(Key.S, primaryModifier = false, altPressed = true))
    }
}
