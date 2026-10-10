package app.xan.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import com.music.shazamkit.models.RecognitionStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun RecognitionScreen(onBack: () -> Unit, onSearch: (String) -> Unit) {
    val status by DesktopRecognitionService.status.collectAsState()
    var showingHistory by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(DesktopBackend.recognitionHistory()) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(status) {
        val success = status as? RecognitionStatus.Success ?: return@LaunchedEffect
        runCatching { DesktopBackend.recordRecognition(success.result.toHistoryEntry()) }
            .onSuccess { history = DesktopBackend.recognitionHistory() }
            .onFailure { message = "Recognized the track, but couldn't save history: ${it.message ?: "storage error"}" }
    }
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 32.dp, vertical = 18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Back")
            }
            Text("Recognize Music", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
            IconButton(onClick = { history = DesktopBackend.recognitionHistory(); showingHistory = !showingHistory }) {
                Icon(Icons.Rounded.History, contentDescription = if (showingHistory) "Close recognition history" else "Open recognition history", tint = XanColors.Accent)
            }
        }
        if (showingHistory) {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recognition history", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = XanColors.Text)
                TextButton(enabled = history.isNotEmpty(), onClick = {
                    runCatching { DesktopBackend.clearRecognitionHistory(); history = emptyList() }
                        .onFailure { message = it.message ?: "Could not clear recognition history." }
                }) { Text("Clear") }
            }
            if (history.isEmpty()) Column(
                Modifier.fillMaxWidth().padding(top = 20.dp).background(XanColors.Card, RoundedCornerShape(22.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.History, contentDescription = null, tint = XanColors.Muted, modifier = Modifier.size(34.dp))
                Text("No recognized tracks yet", Modifier.padding(top = 10.dp), color = XanColors.Text, style = MaterialTheme.typography.titleMedium)
                Text("Tracks you recognize will appear here. Audio recordings are never stored.", Modifier.padding(top = 4.dp), color = XanColors.Muted, textAlign = TextAlign.Center)
            }
            else Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(22.dp)).padding(6.dp)) {
                history.forEach { entry ->
                    val time = remember(entry.recognizedAtEpochMillis) {
                        DateTimeFormatter.ofPattern("MMM d · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(entry.recognizedAtEpochMillis))
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).clickable { entry.shazamUrl?.let { openRecognitionLink(it, onError = { message = it }) } }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(entry.toMusicTrack(), 48.dp, corner = 11.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(entry.title, color = XanColors.Text, maxLines = 1)
                            Text("${entry.artist} · $time", color = XanColors.Muted, maxLines = 1)
                        }
                        Icon(Icons.Rounded.OpenInNew, contentDescription = "Open ${entry.title} in Shazam", tint = XanColors.Muted)
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(1f, fill = true))
            val active = status is RecognitionStatus.Listening || status is RecognitionStatus.Processing
            val pulseTransition = rememberInfiniteTransition(label = "recognition-pulse")
            val pulseScale by pulseTransition.animateFloat(
                initialValue = 1f,
                targetValue = if (status is RecognitionStatus.Listening) 1.12f else 1f,
                animationSpec = infiniteRepeatable(tween(850), repeatMode = RepeatMode.Reverse),
                label = "recognition-pulse-scale",
            )
            val pulseAlpha by pulseTransition.animateFloat(
                initialValue = .76f,
                targetValue = if (status is RecognitionStatus.Listening) .18f else .76f,
                animationSpec = infiniteRepeatable(tween(850), repeatMode = RepeatMode.Reverse),
                label = "recognition-pulse-alpha",
            )
            Box(Modifier.fillMaxWidth().padding(top = 34.dp, bottom = 26.dp), contentAlignment = Alignment.Center) {
                if (status is RecognitionStatus.Listening) Box(
                    Modifier.size(266.dp).graphicsLayer {
                        alpha = pulseAlpha
                        scaleX = pulseScale
                        scaleY = pulseScale
                    }.border(2.dp, XanColors.Accent, CircleShape),
                )
                Box(
                    Modifier.size(230.dp).clip(CircleShape)
                        .background(Brush.radialGradient(listOf(XanColors.Accent, XanColors.Accent.copy(alpha = .85f), Color(0xFF8F2F58)))),
                    contentAlignment = Alignment.Center,
                ) {
                    FilledIconButton(
                        onClick = {
                            when (status) {
                                RecognitionStatus.Ready, is RecognitionStatus.NoMatch, is RecognitionStatus.Error, is RecognitionStatus.Success -> {
                                    message = ""
                                    DesktopRecognitionService.reset()
                                    scope.launch {
                                        val result = DesktopRecognitionService.recognize()
                                        if (result is RecognitionStatus.Error) message = result.message
                                    }
                                }
                                else -> Unit
                            }
                        },
                        enabled = !active,
                        modifier = Modifier.size(160.dp).clip(CircleShape),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Transparent, contentColor = Color(0xFF171318), disabledContainerColor = Color.Transparent),
                    ) {
                        if (status is RecognitionStatus.Processing) CircularProgressIndicator(color = Color(0xFF171318), modifier = Modifier.size(54.dp), strokeWidth = 5.dp)
                        else Icon(if (status is RecognitionStatus.Listening) Icons.Rounded.Mic else Icons.Rounded.MicNone, contentDescription = "Start music recognition", modifier = Modifier.size(72.dp))
                    }
                }
            }
            val title = when (status) {
                RecognitionStatus.Ready -> "Tap to recognize"
                RecognitionStatus.Listening -> "Listening…"
                RecognitionStatus.Processing -> "Analyzing audio…"
                is RecognitionStatus.NoMatch -> "No match found"
                is RecognitionStatus.Error -> "Couldn't recognize this audio"
                is RecognitionStatus.Success -> "Track recognized"
            }
            Text(title, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = XanColors.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            if (status == RecognitionStatus.Ready) Text(
                "XAN records 10 seconds in memory, creates an audio fingerprint, and sends that fingerprint to Shazam. The recording is not saved.",
                Modifier.fillMaxWidth().padding(top = 9.dp), color = XanColors.Muted, textAlign = TextAlign.Center,
            )
            when (val current = status) {
                is RecognitionStatus.Success -> {
                    val result = current.result
                    Column(Modifier.fillMaxWidth().padding(top = 24.dp).background(XanColors.Card, RoundedCornerShape(24.dp)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Artwork(result.toMusicTrack(), 160.dp, corner = 22.dp)
                        Text(result.title, Modifier.padding(top = 14.dp), color = XanColors.Text, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Text(result.artist, Modifier.padding(top = 6.dp), color = XanColors.Muted, style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { onSearch("${result.title} ${result.artist}") }) { Text("Find in XAN") }
                            if (!result.shazamUrl.isNullOrBlank()) OutlinedButton(onClick = { openRecognitionLink(result.shazamUrl) { message = it } }) { Text("Open Shazam") }
                        }
                    }
                }
                is RecognitionStatus.NoMatch -> Text(current.message, Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center, color = XanColors.Muted)
                is RecognitionStatus.Error -> Text(current.message, Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center, color = XanColors.Muted)
                else -> Unit
            }
            if (active) Text("Keep XAN open and play the music nearby for about 10 seconds.", Modifier.fillMaxWidth().padding(top = 10.dp), color = XanColors.Muted, textAlign = TextAlign.Center)
            Spacer(Modifier.weight(1f, fill = true))
        }
        if (message.isNotBlank()) Text(message, Modifier.fillMaxWidth().padding(vertical = 12.dp), color = XanColors.Accent, textAlign = TextAlign.Center)
    }
}

private fun openRecognitionLink(value: String, onError: (String) -> Unit) {
    runCatching {
        val uri = URI(value)
        require(uri.scheme == "https" && uri.host?.let { it == "shazam.com" || it.endsWith(".shazam.com") } == true) { "This recognition link is not a valid Shazam URL." }
        Desktop.getDesktop().browse(uri)
    }.onFailure { onError(it.message ?: "Could not open the Shazam link.") }
}

private fun RecognitionHistoryEntry.toMusicTrack() = MusicTrack(
    title = title,
    artist = artist,
    album = album.orEmpty(),
    colors = listOf(XanColors.CardLight, XanColors.Card),
    motif = "♫",
    source = "Shazam",
    artworkUrl = artworkUrl,
)

private fun com.music.shazamkit.models.RecognitionResult.toMusicTrack() = MusicTrack(
    title = title,
    artist = artist,
    album = album.orEmpty(),
    colors = listOf(XanColors.CardLight, XanColors.Card),
    motif = "♫",
    source = "Shazam",
    artworkUrl = coverArtHqUrl ?: coverArtUrl,
)
