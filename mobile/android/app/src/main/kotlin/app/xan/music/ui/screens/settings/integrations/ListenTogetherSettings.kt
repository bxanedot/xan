/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.ui.screens.settings.integrations

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import app.xan.music.ui.screens.ListenTogetherScreen

@Composable
fun ListenTogetherSettings(navController: NavController) {
    ListenTogetherScreen(navController, showTopBar = true, showSettings = true)
}
