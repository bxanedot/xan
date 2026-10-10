/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.ui.utils

import androidx.navigation.NavController
import app.xan.music.ui.screens.Screens

fun NavController.backToMain() {
    while (previousBackStackEntry != null &&
        currentBackStackEntry?.destination?.route !in Screens.MainRoutes
    ) {
        popBackStack()
    }
}
