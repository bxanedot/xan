package app.xan.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun IosListenBrainzSettings(accent: Color, text: Color, muted: Color, isLight: Boolean) {
    val state by IosListenBrainz.state.collectAsState()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var token by remember { mutableStateOf("") }

    Text("ListenBrainz", color = accent, fontWeight = FontWeight.Medium)
    Text("Scrobble listening activity to ListenBrainz. Your user token is stored in iOS Keychain.", color = muted, fontSize = 13.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { runCatching { uriHandler.openUri("https://listenbrainz.org/settings/") } }) {
            Text("Open ListenBrainz settings")
        }
        Text(if (state.connected) "Connected as ${state.username ?: "ListenBrainz user"}." else "Not connected.", color = muted, fontSize = 12.sp)
    }
    OutlinedTextField(
        value = token,
        onValueChange = { token = it.take(512) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("ListenBrainz user token") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = {
                scope.launch {
                    try {
                        IosListenBrainz.connect(token)
                        token = ""
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        IosListenBrainz.reportFailure(failure.message ?: "ListenBrainz connection failed.")
                    }
                }
            },
            enabled = token.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = if (isLight) Color.White else Color.Black),
        ) { Text("Connect") }
        if (state.connected) {
            OutlinedButton(onClick = {
                runCatching { IosListenBrainz.disconnect() }
                    .onFailure { IosListenBrainz.reportFailure(it.message ?: "ListenBrainz could not be disconnected.") }
            }) { Text("Disconnect") }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Scrobble to ListenBrainz", color = text, fontWeight = FontWeight.Medium)
            Text("Send now-playing when a track starts and save listens after half the track or four minutes.", color = muted, fontSize = 12.sp)
        }
        Switch(checked = state.enabled, enabled = state.connected, onCheckedChange = IosListenBrainz::setEnabled)
    }
    state.message?.let { Text(it, color = if (state.error) Color(0xFFFF8A80) else muted, fontSize = 13.sp) }
}
