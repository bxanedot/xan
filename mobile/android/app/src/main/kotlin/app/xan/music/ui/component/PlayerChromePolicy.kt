package app.xan.music.ui.component

/** Browse and media-detail pages keep the global player; focused flows own their chrome. */
internal fun isPlayerChromeRoute(route: String?): Boolean =
    route != null && route != "settings" && !route.startsWith("settings/") &&
        route !in FocusedPlayerRoutes

internal fun shouldShowPlayerAccessory(
    route: String?,
    hasActiveMedia: Boolean,
    dismissed: Boolean,
    keyboardActive: Boolean,
): Boolean = hasActiveMedia && !dismissed && !keyboardActive && isPlayerChromeRoute(route)

private val FocusedPlayerRoutes = setOf(
    "login", "channel_picker", "switch_channel", "account", "equalizer", "wrapped",
    "update", "ambient_mode", "recognition", "recognition_history",
)
