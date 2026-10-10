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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun IosLastFmSettings(accent: Color, text: Color, muted: Color, isLight: Boolean) {
    val state by IosLastFm.state.collectAsState()
    var apiKey by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Text("Last.fm", color = accent, fontWeight = FontWeight.Medium)
    Text(
        "Connect with your Last.fm API key and shared secret. XAN stores the credentials and session in iOS Keychain.",
        color = muted,
        fontSize = 13.sp,
    )
    OutlinedTextField(
        value = apiKey,
        onValueChange = { apiKey = it.take(128) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Last.fm API key") },
        singleLine = true,
    )
    OutlinedTextField(
        value = secret,
        onValueChange = { secret = it.take(256) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Last.fm shared secret") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = {
                scope.launch {
                    try {
                        IosLastFm.beginAuthorization(apiKey, secret)
                        apiKey = ""
                        secret = ""
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        IosLastFm.reportFailure(failure.message ?: "Last.fm authorization could not start.")
                    }
                }
            },
            enabled = apiKey.isNotBlank() && secret.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = if (isLight) Color.White else Color.Black),
        ) { Text("Authorize") }
        if (IosLastFm.hasPendingAuthorization()) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        try {
                            IosLastFm.finishAuthorization()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            IosLastFm.reportFailure(failure.message ?: "Last.fm could not finish authorization.")
                        }
                    }
                },
            ) { Text("Finish connecting") }
        }
        if (state.connected) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        try {
                            IosLastFm.disconnect()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            IosLastFm.reportFailure(failure.message ?: "Last.fm could not be disconnected.")
                        }
                    }
                },
            ) { Text("Disconnect") }
        }
    }
    Text(
        if (state.connected) "Connected as ${state.username ?: "Last.fm user"}." else "Last.fm is not connected.",
        color = muted,
        fontSize = 12.sp,
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Scrobble tracks", color = text, fontWeight = FontWeight.Medium)
            Text("Submit a track after the Android-aligned listening threshold.", color = muted, fontSize = 12.sp)
        }
        Switch(
            checked = state.scrobblingEnabled,
            enabled = state.connected,
            onCheckedChange = IosLastFm::setScrobblingEnabled,
        )
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Send now playing", color = text, fontWeight = FontWeight.Medium)
            Text("Show the current track in your Last.fm profile.", color = muted, fontSize = 12.sp)
        }
        Switch(
            checked = state.sendNowPlaying,
            enabled = state.connected,
            onCheckedChange = IosLastFm::setSendNowPlaying,
        )
    }
    state.message?.let {
        Text(it, color = if (state.error) Color(0xFFFF8A80) else muted, fontSize = 13.sp)
    }
}
