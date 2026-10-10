package app.xan.music.ui.utils

import androidx.compose.runtime.mutableFloatStateOf

/** Rotation shared by the startup and About logos for the lifetime of the app process. */
object XanLogoMotion {
    val startupRotation = mutableFloatStateOf(0f)
}
