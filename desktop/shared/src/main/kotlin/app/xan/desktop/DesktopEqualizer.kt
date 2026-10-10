package app.xan.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun EqualizerSettingsDetail() {
    var settings by remember { mutableStateOf(DesktopBackend.equalizerSettings()) }
    var status by remember { mutableStateOf("") }
    fun update(next: EqualizerSettings) {
        settings = next
        runCatching { DesktopPlayer.setEqualizerSettings(next) }
            .onFailure { status = it.message ?: "Could not save equalizer settings." }
            .onSuccess { status = if (DesktopPlayer.state.value.track == null) "Saved. It will apply to the next track." else "Applied to the current player." }
    }
    Column(
        Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(24.dp)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(84.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(XanColors.Accent, Color(0xFF9B65D6), Color(0xFF292235)))),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(68.dp).clip(CircleShape).background(Color(0xFF19171D)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = XanColors.Accent, modifier = Modifier.size(38.dp))
                }
            }
            Column(Modifier.padding(start = 18.dp)) {
                Text("Equalizer", style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
                Text("Shape the sound of every track.", color = XanColors.Muted)
            }
        }
        SettingSwitch("Enable equalizer", "Applies five live frequency filters to music playback.", settings.enabled) {
            update(settings.copy(enabled = it))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill("Simple", !settings.advanced) { update(settings.copy(advanced = false)) }
            FilterPill("Advanced", settings.advanced) { update(settings.copy(advanced = true)) }
        }
        if (settings.advanced) {
            XanEqualizer.bandDefinitions.forEachIndexed { index, band ->
                EqualizerGainSlider(
                    label = band.label,
                    valueDb = settings.gainsDb[index],
                    onValueChange = { gain -> update(settings.copy(gainsDb = settings.gainsDb.toMutableList().also { it[index] = gain })) },
                )
            }
        } else {
            val simpleValues = listOf(settings.gainsDb[0], settings.gainsDb[2], settings.gainsDb[4])
            listOf("Bass", "Mid", "Treble").forEachIndexed { index, label ->
                EqualizerGainSlider(label, simpleValues[index]) { gain ->
                    val values = simpleValues.toMutableList().also { it[index] = gain }
                    update(settings.copy(gainsDb = XanEqualizer.simpleGainsDb(values[0], values[1], values[2])))
                }
            }
        }
        Text("Boosts reduce the player level automatically to help prevent clipping.", color = XanColors.Muted, style = MaterialTheme.typography.bodySmall)
        if (status.isNotBlank()) Text(status, color = XanColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EqualizerGainSlider(label: String, valueDb: Double, onValueChange: (Double) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), color = XanColors.Text, fontWeight = FontWeight.Medium)
            Text("${if (valueDb > 0) "+" else ""}${"%.1f".format(valueDb)} dB", color = XanColors.Muted, fontSize = 12.sp)
        }
        Slider(
            value = valueDb.toFloat(),
            onValueChange = { onValueChange(it.toDouble()) },
            valueRange = XanEqualizer.MIN_GAIN_DB.toFloat()..XanEqualizer.MAX_GAIN_DB.toFloat(),
        )
    }
}
