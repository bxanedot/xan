package app.xan.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import javax.imageio.ImageIO

/** Native system-tray transport controls and a background-running entry point. */
internal object DesktopSystemTray {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var icon: TrayIcon? = null
    @Volatile private var observing: Job? = null
    @Volatile private var showWindow: (() -> Unit)? = null
    private lateinit var playPause: MenuItem
    private lateinit var previous: MenuItem
    private lateinit var next: MenuItem

    fun install(onShowWindow: () -> Unit, onQuit: () -> Unit, logo: AppLogoVariant = DesktopBackend.appLogo()): Boolean {
        if (!SystemTray.isSupported() || icon != null) return icon != null
        return runCatching {
            val image = loadLogo(logo)
                ?: error("The XAN tray icon could not be loaded.")
            val menu = PopupMenu()
            playPause = MenuItem("Play")
            previous = MenuItem("Previous")
            next = MenuItem("Next")
            val show = MenuItem("Show XAN")
            val quit = MenuItem("Quit XAN")
            menu.add(previous)
            menu.add(playPause)
            menu.add(next)
            menu.addSeparator()
            menu.add(show)
            menu.add(quit)

            previous.addActionListener { scope.launch { DesktopTransportControls.perform(DesktopTransportAction.PREVIOUS) } }
            next.addActionListener { scope.launch { DesktopTransportControls.perform(DesktopTransportAction.NEXT) } }
            playPause.addActionListener { scope.launch { DesktopTransportControls.perform(DesktopTransportAction.PLAY_PAUSE) } }
            show.addActionListener { showWindow?.invoke() }
            quit.addActionListener { onQuit() }

            val trayIcon = TrayIcon(image, "XAN", menu).apply {
                isImageAutoSize = true
                addActionListener { showWindow?.invoke() }
            }
            this.showWindow = onShowWindow
            SystemTray.getSystemTray().add(trayIcon)
            icon = trayIcon
            observing = scope.launch {
                DesktopPlayer.state.collect { snapshot -> update(snapshot) }
            }
            update(DesktopPlayer.state.value)
            true
        }.onFailure {
            DiagnosticLog.warn(DesktopBackend.diagnosticsDirectory(), "system_tray_unavailable", "The system tray could not be initialized.", it)
        }.getOrDefault(false)
    }

    fun setLogo(logo: AppLogoVariant) {
        val image = runCatching { loadLogo(logo) }.getOrNull() ?: return
        EventQueue.invokeLater { icon?.image = image }
    }

    fun notifyBackgroundPlayback() {
        icon?.displayMessage("XAN is still playing", "Use the XAN tray menu to control playback or quit.", TrayIcon.MessageType.INFO)
    }

    fun shutdown() {
        observing?.cancel()
        observing = null
        icon?.let { trayIcon -> runCatching { SystemTray.getSystemTray().remove(trayIcon) } }
        icon = null
        showWindow = null
        scope.cancel()
    }

    private fun update(snapshot: PlaybackSnapshot) {
        val trayIcon = icon ?: return
        EventQueue.invokeLater {
            if (icon !== trayIcon) return@invokeLater
            val playing = snapshot.status == PlaybackStatus.PLAYING || snapshot.status == PlaybackStatus.BUFFERING
            playPause.label = if (playing) "Pause" else "Play"
            val hasTrack = snapshot.track != null
            playPause.isEnabled = hasTrack
            previous.isEnabled = hasTrack
            next.isEnabled = hasTrack && (snapshot.queueIndex + 1 in snapshot.queue.indices || snapshot.repeatMode == RepeatMode.ALL)
            val title = snapshot.track?.let { "${it.title} · ${it.artist}" } ?: "XAN"
            trayIcon.toolTip = title.take(63)
        }
    }

    private fun loadLogo(logo: AppLogoVariant) =
        DesktopSystemTray::class.java.getResourceAsStream("/${logo.resource}")?.use(ImageIO::read)

}

internal fun shouldHideToSystemTray(trayAvailable: Boolean, status: PlaybackStatus): Boolean =
    trayAvailable && status in setOf(PlaybackStatus.PLAYING, PlaybackStatus.BUFFERING, PlaybackStatus.RESOLVING)
