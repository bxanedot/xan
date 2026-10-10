package app.xan.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun IosLyricsSettings(text: Color, muted: Color) {
    var order by remember { mutableStateOf(IosLyrics.providerOrder()) }
    val descriptions = mapOf(
        "BetterLyrics" to "Word-timed lyrics",
        "BiniLyrics" to "Community TTML lyrics",
        "Unison" to "Timed lyrics and YouTube video lookup",
        "Musixmatch" to "Timed lyrics from the Musixmatch catalog",
        "YouLyPlus" to "Word and syllable timing from community servers",
        "Paxsenix" to "Apple Music search and word-timed lyrics",
        "LrcLib" to "Synced and plain lyrics from LRCLIB",
        "Kugou" to "Timed lyrics from the KuGou catalog",
        "SimpMusic" to "Timed lyrics using the YouTube video ID",
        "YouTubeSubtitle" to "Transcript captions from the YouTube video",
        "YouTubeMusic" to "Lyrics attached to the YouTube Music track",
    )
    order.forEachIndexed { index, provider ->
        LyricsProviderSwitch(
            provider = provider,
            description = descriptions.getValue(provider),
            position = index + 1,
            text = text,
            muted = muted,
            canMoveUp = index > 0,
            canMoveDown = index < order.lastIndex,
            onMove = { direction -> order = IosLyrics.moveProvider(provider, direction) },
        )
    }
    LyricsRomanizationSettings(text, muted)
    IosLyricsTranslationSettings(text, muted)
}

@Composable
private fun LyricsRomanizationSettings(text: Color, muted: Color) {
    val settings by IosLyricsRomanization.state.collectAsState()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Offline lyrics romanization", color = text, fontWeight = FontWeight.SemiBold)
        Text("Uses built-in iOS script transliteration to show lyrics in Latin letters. Pronunciation varies by language; it works offline without an API key.", color = muted, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Show romanized lines", Modifier.weight(1f), color = text)
            Switch(settings.enabled, IosLyricsRomanization::setEnabled)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Use romanization as the main line", Modifier.weight(1f), color = text)
            Switch(settings.useAsMain, IosLyricsRomanization::setUseAsMain, enabled = settings.enabled)
        }
    }
}

@Composable
private fun LyricsProviderSwitch(
    provider: String,
    description: String,
    position: Int,
    text: Color,
    muted: Color,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
) {
    var enabled by remember(provider) { mutableStateOf(IosLyrics.providerEnabled(provider)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("$position. $provider lyrics", color = text, fontWeight = FontWeight.Medium)
            Text(description, color = muted, fontSize = 12.sp)
        }
        TextButton(onClick = { onMove(-1) }, enabled = canMoveUp) { Text("↑") }
        TextButton(onClick = { onMove(1) }, enabled = canMoveDown) { Text("↓") }
        Switch(
            checked = enabled,
            onCheckedChange = { selected ->
                enabled = selected
                IosLyrics.setProviderEnabled(provider, selected)
            },
        )
    }
}

@Composable
private fun IosLyricsTranslationSettings(text: Color, muted: Color) {
    val initialConfig = remember { IosLyricsTranslation.config() }
    var apiKey by remember { mutableStateOf("") }
    var hasApiKey by remember { mutableStateOf(IosLyricsTranslation.hasApiKey()) }
    var endpoint by remember { mutableStateOf(initialConfig.endpoint) }
    var model by remember { mutableStateOf(initialConfig.model) }
    var language by remember { mutableStateOf(initialConfig.targetLanguage) }
    var mode by remember { mutableStateOf(initialConfig.mode) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AI translation and transcription", color = text, fontWeight = FontWeight.SemiBold)
        Text(
            "Uses an OpenAI-compatible service when you tap Translate on the lyrics page. The API key is stored in Keychain; translated lines are cached on this device.",
            color = muted,
            fontSize = 12.sp,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it.take(512) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(if (hasApiKey) "Replace saved API key" else "API key") },
            placeholder = { if (hasApiKey) Text("A key is saved securely") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = apiKey.trim().length >= 8,
                onClick = {
                    if (IosLyricsTranslation.saveApiKey(apiKey)) {
                        hasApiKey = true
                        apiKey = ""
                    }
                },
            ) { Text("Save key") }
            OutlinedButton(
                enabled = hasApiKey,
                onClick = {
                    if (IosLyricsTranslation.clearApiKey()) {
                        hasApiKey = false
                        apiKey = ""
                    }
                },
            ) { Text("Remove key") }
        }
        OutlinedButton(
            enabled = IosLyricsTranslation.cachedTranslationCount() > 0,
            onClick = IosLyricsTranslation::clearSavedTranslations,
        ) { Text("Clear saved translations") }
        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it.take(2_000); IosLyricsTranslation.setEndpoint(it) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("HTTPS chat completions endpoint") },
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it.take(200); IosLyricsTranslation.setModel(it) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Model") },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = language,
                onValueChange = { language = it.take(40); IosLyricsTranslation.setTargetLanguage(it) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Target language") },
            )
            OutlinedButton(onClick = {
                mode = IosLyricsTranslationMode.entries[(mode.ordinal + 1) % IosLyricsTranslationMode.entries.size]
                IosLyricsTranslation.setMode(mode)
            }) {
                Text(when (mode) {
                    IosLyricsTranslationMode.TRANSLATE -> "Translate"
                    IosLyricsTranslationMode.ROMANIZED -> "Romanized"
                    IosLyricsTranslationMode.TRANSCRIBED -> "Transcribed"
                })
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
    }
}
