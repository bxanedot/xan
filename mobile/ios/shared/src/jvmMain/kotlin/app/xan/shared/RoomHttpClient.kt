package app.xan.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets

actual fun newRoomHttpClient(): HttpClient = HttpClient(CIO) { install(WebSockets) }

private val roomPreferences = java.util.prefs.Preferences.userNodeForPackage(IosListenTogether::class.java)

actual fun readIosPreference(key: String): String? = roomPreferences.get(key, null)

actual fun writeIosPreference(key: String, value: String) {
    roomPreferences.put(key, value)
}
