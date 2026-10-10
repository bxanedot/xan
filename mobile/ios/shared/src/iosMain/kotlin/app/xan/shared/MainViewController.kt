package app.xan.shared

import androidx.compose.ui.window.ComposeUIViewController
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets
import platform.UIKit.UIViewController

fun MainViewController(sessionStore: RoomSessionStore): UIViewController {
    IosListenTogether.installSessionStore(sessionStore)
    if (sessionStore is YouTubeCredentialStore) IosCredentialStorage.install(sessionStore)
    if (sessionStore is IosLastFmSecretStore) IosLastFm.install(sessionStore)
    if (sessionStore is IosNativeCrypto) IosLyrics.installNativeCrypto(sessionStore)
    if (sessionStore is IosListenBrainzTokenStore) IosListenBrainz.install(sessionStore)
    if (sessionStore is IosLyricsTranslationKeyStore) IosLyricsTranslation.install(sessionStore)
    if (sessionStore is IosLyricsRomanizer) IosLyricsRomanization.install(sessionStore)
    return ComposeUIViewController { XanApp() }
}

actual fun newRoomHttpClient(): HttpClient = HttpClient(Darwin) { install(WebSockets) }
