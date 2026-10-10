package app.xan.desktop

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/** Optional system-wide play/pause/previous/next key handling. Native media sessions remain OS-specific. */
internal object DesktopGlobalMediaKeys {
    private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastKeyAt = ConcurrentHashMap<Int, Long>()
    @Volatile private var active = false
    @Volatile private var currentStatus = "Off"

    private val listener = object : NativeKeyListener {
        override fun nativeKeyTyped(event: NativeKeyEvent) = Unit
        override fun nativeKeyReleased(event: NativeKeyEvent) = Unit

        override fun nativeKeyPressed(event: NativeKeyEvent) {
            if (!active) return
            val action = when (event.keyCode) {
                NativeKeyEvent.VC_MEDIA_PLAY -> DesktopTransportAction.PLAY_PAUSE
                NativeKeyEvent.VC_MEDIA_PREVIOUS -> DesktopTransportAction.PREVIOUS
                NativeKeyEvent.VC_MEDIA_NEXT -> DesktopTransportAction.NEXT
                NativeKeyEvent.VC_MEDIA_STOP -> DesktopTransportAction.STOP
                else -> return
            }
            val now = System.nanoTime()
            val previous = lastKeyAt.put(event.keyCode, now) ?: 0L
            if (now - previous < 300_000_000L) return
            actionScope.launch { if (active) DesktopTransportControls.perform(action) }
        }
    }

    fun enabled(): Boolean = active
    fun status(): String = currentStatus

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (enabled == active) return
        if (enabled) {
            try {
                Logger.getLogger(GlobalScreen::class.java.packageName).level = Level.OFF
                GlobalScreen.registerNativeHook()
                GlobalScreen.addNativeKeyListener(listener)
                active = true
                currentStatus = "Global media keys are active."
            } catch (failure: NativeHookException) {
                active = false
                currentStatus = "Global media keys could not start: ${failure.message ?: "native hook unavailable"}"
            } catch (failure: UnsatisfiedLinkError) {
                active = false
                currentStatus = "Global media keys are unavailable on this system: ${failure.message ?: "native hook unavailable"}"
            } catch (failure: Exception) {
                runCatching { GlobalScreen.unregisterNativeHook() }
                active = false
                currentStatus = "Global media keys could not start: ${failure.message ?: "permission denied or native hook unavailable"}"
            }
        } else {
            runCatching { GlobalScreen.removeNativeKeyListener(listener) }
            runCatching { GlobalScreen.unregisterNativeHook() }
            active = false
            lastKeyAt.clear()
            currentStatus = "Off"
        }
    }
}
