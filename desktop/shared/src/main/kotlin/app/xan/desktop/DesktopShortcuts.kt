package app.xan.desktop

import androidx.compose.ui.input.key.Key

internal enum class DesktopShortcutAction {
    PLAY_PAUSE,
    PREVIOUS_TRACK,
    NEXT_TRACK,
    HOME,
    SEARCH,
    LIBRARY,
    RECOGNIZE,
    SETTINGS,
}

/** Require a deliberate platform-primary + Alt chord so text fields retain normal editing keys. */
internal fun resolveDesktopShortcut(key: Key, primaryModifier: Boolean, altPressed: Boolean): DesktopShortcutAction? {
    if (!primaryModifier || !altPressed) return null
    return when (key) {
        Key.Spacebar -> DesktopShortcutAction.PLAY_PAUSE
        Key.DirectionLeft -> DesktopShortcutAction.PREVIOUS_TRACK
        Key.DirectionRight -> DesktopShortcutAction.NEXT_TRACK
        Key.H -> DesktopShortcutAction.HOME
        Key.S -> DesktopShortcutAction.SEARCH
        Key.L -> DesktopShortcutAction.LIBRARY
        Key.R -> DesktopShortcutAction.RECOGNIZE
        Key.O -> DesktopShortcutAction.SETTINGS
        else -> null
    }
}
