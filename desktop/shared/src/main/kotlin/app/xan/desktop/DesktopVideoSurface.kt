package app.xan.desktop

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.media.MediaView
import javafx.embed.swing.JFXPanel
import javafx.scene.paint.Color as FxColor
import java.awt.Color as AwtColor

/** Renders the active JavaFX video stream inside Compose's expanded player canvas. */
@Composable
internal fun DesktopVideoSurface(snapshot: PlaybackSnapshot, modifier: Modifier = Modifier) {
    val mediaViewState = remember { mutableStateOf<MediaView?>(null) }
    val mediaView = mediaViewState.value
    SwingPanel(
        modifier = modifier.background(XanColors.Background),
        factory = {
            JFXPanel().apply {
                background = AwtColor.BLACK
                val panel = this
                Platform.runLater {
                    val view = MediaView().apply { isPreserveRatio = true; isSmooth = true }
                    val container = StackPane(view)
                    container.widthProperty().addListener { _, _, width -> view.fitWidth = width.toDouble() }
                    container.heightProperty().addListener { _, _, height -> view.fitHeight = height.toDouble() }
                    scene = Scene(container, FxColor.BLACK)
                    mediaViewState.value = view
                }
            }
        },
        update = {},
    )
    LaunchedEffect(mediaView, snapshot.playbackSessionId, snapshot.status) {
        mediaView?.let { DesktopPlayer.attachVideoView(it, snapshot.playbackSessionId) }
    }
    DisposableEffect(mediaView) {
        onDispose { mediaView?.let(DesktopPlayer::detachVideoView) }
    }
}
