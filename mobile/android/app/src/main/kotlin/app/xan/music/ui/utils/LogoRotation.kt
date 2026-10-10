package app.xan.music.ui.utils

const val STARTUP_LOGO_POWER = 50f
const val BOOSTED_LOGO_POWER = 80f

/** Power is degrees per second; cap a stalled frame so the logo never jumps. */
fun advanceLogoRotation(angle: Float, power: Float, elapsedSeconds: Float): Float =
    (angle + power * elapsedSeconds.coerceIn(0f, 0.1f)) % 360f
