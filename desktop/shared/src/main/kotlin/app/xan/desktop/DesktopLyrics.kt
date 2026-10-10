package app.xan.desktop

import androidx.compose.animation.core.RepeatMode as AnimationRepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.xan.music.betterlyrics.BetterLyrics
import com.music.innertube.YouTube
import com.music.innertube.models.comment.CommentThreadRenderer
import com.music.lrclib.LrcLib
import com.music.simpmusic.SimpMusicLyrics
import com.music.youlyplus.YouLyPlus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class DesktopLyricWord(val text: String, val startsAtMillis: Long)
internal data class DesktopLyricLine(val startsAtMillis: Long?, val text: String, val words: List<DesktopLyricWord> = emptyList())
internal data class DesktopLyricsResult(val lines: List<DesktopLyricLine>, val provider: String, val cached: Boolean = false)

internal object DesktopLyricsRepository {
    private const val CACHE_MAX_AGE_MILLIS = 90L * 24 * 60 * 60 * 1000

    fun trackKey(track: MusicTrack): String = track.videoId?.let { "video:$it" }
        ?: track.localPath?.let { "file:$it" }
        ?: "metadata:${track.title.trim().lowercase()}|${track.artist.trim().lowercase()}|${track.durationSeconds ?: -1}"

    suspend fun load(track: MusicTrack): DesktopLyricsResult {
        val key = trackKey(track)
        val cached = withContext(Dispatchers.IO) { DesktopBackend.cachedLyrics(key) }
        val cacheIsFresh = cached != null && System.currentTimeMillis() - cached.updatedAt <= CACHE_MAX_AGE_MILLIS
        if (cacheIsFresh) return DesktopLyricsResult(parseDesktopLyrics(cached!!.lyrics), cached.provider, cached = true)

        val duration = track.durationSeconds ?: -1
        val providers: Map<String, suspend () -> String> = mapOf(
            "BetterLyrics" to { BetterLyrics.getLyrics(track.title, track.artist, duration, track.album.takeIf { it.isNotBlank() }).getOrThrow() },
            "YouLyPlus" to { YouLyPlus.getLyrics(track.title, track.artist, duration, track.album.takeIf { it.isNotBlank() }, track.videoId).getOrThrow() },
            "SimpMusic" to {
                val videoId = track.videoId ?: error("SimpMusic lyrics need a YouTube Music track ID.")
                SimpMusicLyrics.getLyrics(videoId, duration).getOrThrow()
            },
            "LRCLIB" to { LrcLib.getLyrics(track.title, track.artist, duration, track.album.takeIf { it.isNotBlank() }).getOrThrow() },
        )
        var lastError: Throwable? = null
        for (provider in DesktopBackend.lyricsProviderOrder()) {
            val request = providers[provider] ?: continue
            val lyrics = try {
                request().trim().also { require(it.isNotBlank()) { "No lyrics returned." } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                lastError = failure
                continue
            }
            val lines = parseDesktopLyrics(lyrics)
            if (lines.isEmpty()) {
                lastError = IllegalStateException("$provider returned lyrics in an unsupported format.")
                continue
            }
            withContext(Dispatchers.IO) { DesktopBackend.cacheLyrics(key, lyrics, provider) }
            return DesktopLyricsResult(lines, provider)
        }
        if (cached != null) return DesktopLyricsResult(parseDesktopLyrics(cached.lyrics), "${cached.provider} · offline cache", cached = true)
        throw IllegalStateException(lastError?.message ?: "No lyrics were found for this track.", lastError)
    }
}

internal fun parseDesktopLyrics(source: String): List<DesktopLyricLine> {
    val timestamp = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    val wordTimestamp = Regex("<(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?>")
    fun millis(match: MatchResult): Long {
        val fraction = match.groupValues.getOrNull(3).orEmpty()
        val ms = fraction.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return (match.groupValues[1].toLongOrNull() ?: 0) * 60_000 + (match.groupValues[2].toLongOrNull() ?: 0) * 1_000 + ms
    }

    val lines = mutableListOf<DesktopLyricLine>()
    source.replace("\\r\\n", "\n").replace("\\n", "\n").lines().forEach { raw ->
        val line = raw.trim().replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        if (line.isBlank() || Regex("^\\[[a-zA-Z]+:.*]$").matches(line)) return@forEach
        val betterLyricsWords = parseBetterLyricsWords(line)
        if (betterLyricsWords != null) {
            val previousIndex = lines.lastIndex
            if (previousIndex >= 0) lines[previousIndex] = lines[previousIndex].copy(words = betterLyricsWords)
            return@forEach
        }
        val stamps = timestamp.findAll(line).toList()
        val words = wordTimestamp.findAll(line).mapNotNull { match ->
            val end = wordTimestamp.find(line, match.range.last + 1)?.range?.first ?: line.length
            val text = line.substring(match.range.last + 1, end).trim()
            text.takeIf { it.isNotBlank() }?.let { DesktopLyricWord(it, millis(match)) }
        }.toList()
        val clean = line.replace(timestamp, "").replace(wordTimestamp, "").trim()
        if (clean.isBlank()) return@forEach
        if (stamps.isEmpty()) lines += DesktopLyricLine(words.firstOrNull()?.startsAtMillis, clean, words)
        else stamps.forEach { stamp -> lines += DesktopLyricLine(millis(stamp), clean, words) }
    }
    return lines.sortedWith(compareBy(nullsLast()) { it.startsAtMillis })
}

private fun parseBetterLyricsWords(line: String): List<DesktopLyricWord>? {
    if (!line.startsWith('<') || !line.endsWith('>')) return null
    val words = line.substring(1, line.length - 1).split('|').mapNotNull { entry ->
        val endSeparator = entry.lastIndexOf(':')
        val startSeparator = entry.lastIndexOf(':', endSeparator - 1)
        if (startSeparator <= 0 || endSeparator <= startSeparator) return@mapNotNull null
        val text = entry.substring(0, startSeparator).trim()
        val startSeconds = entry.substring(startSeparator + 1, endSeparator).toDoubleOrNull() ?: return@mapNotNull null
        text.takeIf(String::isNotBlank)?.let { DesktopLyricWord(it, (startSeconds * 1000).toLong().coerceAtLeast(0)) }
    }
    return words.takeIf { it.isNotEmpty() }
}

@Composable
private fun ExpandedPlayerControls(
    snapshot: PlaybackSnapshot,
    cast: CastSnapshot,
    controlsEnabled: Boolean,
    modifier: Modifier = Modifier,
    artworkSize: Dp = 260.dp,
) {
    val track = snapshot.track ?: return
    val scope = rememberCoroutineScope()
    val isCasting = cast.connectedName != null
    val position = if (isCasting) cast.positionMillis else snapshot.positionMillis
    val duration = if (isCasting) cast.durationMillis else snapshot.durationMillis
    val trackKey = track.videoId ?: track.localPath ?: track.title
    var seekDraft by remember(trackKey) { mutableFloatStateOf(position.toFloat()) }
    var seeking by remember(trackKey) { mutableStateOf(false) }
    LaunchedEffect(position, seeking, trackKey) { if (!seeking) seekDraft = position.toFloat() }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("NOW PLAYING", Modifier.fillMaxWidth(), color = XanColors.Accent, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(14.dp))
        if (track.kind == "video") {
            DesktopVideoSurface(snapshot, Modifier.size(artworkSize).clip(RoundedCornerShape(22.dp)))
        } else Artwork(track, artworkSize, corner = 22.dp)
        Text(track.title, Modifier.fillMaxWidth().padding(top = 14.dp), color = XanColors.Text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center)
        Text(track.artist, Modifier.fillMaxWidth().padding(top = 5.dp), color = XanColors.Muted, maxLines = 1, textAlign = TextAlign.Center)
        Text(track.album, Modifier.fillMaxWidth().padding(top = 3.dp), color = XanColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, textAlign = TextAlign.Center)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    scope.launch {
                        val current = DesktopPlayer.state.value
                        runCatching {
                            if (isCasting) {
                                if (position > 5_000) DesktopCast.seek(0)
                                else current.queue.getOrNull(current.queueIndex - 1)?.let { previous ->
                                    DesktopCast.playOnCurrentReceiver(previous, withContext(Dispatchers.IO) { DesktopBackend.resolveTrackUri(previous) })
                                    DesktopPlayer.setExternalTrack(previous)
                                }
                            } else DesktopPlayer.previous()
                        }.onFailure { DesktopPlayer.setNotice(it.message ?: "Could not play the previous track.") }
                    }
                },
                enabled = controlsEnabled && (position > 0 || snapshot.queueIndex > 0),
            ) { Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous track", tint = XanColors.Text, modifier = Modifier.size(27.dp)) }
            FilledIconButton(
                onClick = {
                    if (isCasting) scope.launch { runCatching { DesktopCast.togglePlayback() }.onFailure { DesktopPlayer.setNotice(it.message ?: "Cast playback control failed.") } }
                    else DesktopPlayer.togglePlayPause()
                },
                enabled = controlsEnabled && (isCasting || snapshot.status !in setOf(PlaybackStatus.RESOLVING, PlaybackStatus.ERROR, PlaybackStatus.IDLE)),
                modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = XanColors.Accent, contentColor = XanColors.Background),
            ) {
                Icon(if (if (isCasting) cast.isPlaying else snapshot.status == PlaybackStatus.PLAYING || snapshot.status == PlaybackStatus.BUFFERING) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (if (isCasting) cast.isPlaying else snapshot.status == PlaybackStatus.PLAYING) "Pause" else "Play", modifier = Modifier.size(28.dp))
            }
            IconButton(
                onClick = {
                    scope.launch {
                        runCatching {
                            if (isCasting) currentNextTrack(snapshot)?.let { next ->
                                DesktopCast.playOnCurrentReceiver(next, withContext(Dispatchers.IO) { DesktopBackend.resolveTrackUri(next) })
                                DesktopPlayer.setExternalTrack(next)
                            } else DesktopPlayer.next()
                        }.onFailure { DesktopPlayer.setNotice(it.message ?: "Could not play the next track.") }
                    }
                },
                enabled = controlsEnabled && (snapshot.queueIndex + 1 < snapshot.queue.size || (!isCasting && snapshot.repeatMode == RepeatMode.ALL)),
            ) { Icon(Icons.Rounded.SkipNext, contentDescription = "Next track", tint = XanColors.Text, modifier = Modifier.size(27.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(formatPlayerTime(position), color = XanColors.Muted, style = MaterialTheme.typography.labelSmall)
            Slider(
                value = seekDraft,
                onValueChange = { seeking = true; seekDraft = it },
                onValueChangeFinished = {
                    if (isCasting) scope.launch { runCatching { DesktopCast.seek(seekDraft.toLong()) }.onFailure { DesktopPlayer.setNotice(it.message ?: "Cast seek failed.") } }
                    else DesktopPlayer.seekTo(seekDraft.toLong())
                    seeking = false
                },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                enabled = controlsEnabled && duration > 0,
                modifier = Modifier.weight(1f).padding(horizontal = 7.dp),
            )
            Text(formatPlayerTime(duration), color = XanColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.VolumeUp, contentDescription = "Volume", tint = XanColors.Muted, modifier = Modifier.size(18.dp))
            Slider(
                value = if (isCasting) cast.volume else snapshot.volume,
                onValueChange = { value ->
                    if (isCasting) scope.launch { runCatching { DesktopCast.setVolume(value) }.onFailure { DesktopPlayer.setNotice(it.message ?: "Could not change Cast volume.") } }
                    else DesktopPlayer.setVolume(value)
                },
                enabled = controlsEnabled,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
        }
        if (!controlsEnabled) Text("The room host controls playback.", Modifier.padding(top = 4.dp), color = XanColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

private fun currentNextTrack(snapshot: PlaybackSnapshot): MusicTrack? = snapshot.queue.getOrNull(snapshot.queueIndex + 1)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ExpandedPlayerCanvas(
    snapshot: PlaybackSnapshot,
    controlsEnabled: Boolean,
    animatedCanvas: Boolean,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
) {
    val track = snapshot.track ?: return
    val cast by DesktopCast.state.collectAsState()
    val isCasting = cast.connectedName != null
    val positionMillis = if (isCasting) cast.positionMillis else snapshot.positionMillis
    val durationMillis = if (isCasting) cast.durationMillis else snapshot.durationMillis
    val key = DesktopLyricsRepository.trackKey(track)
    var result by remember(key) { mutableStateOf<DesktopLyricsResult?>(null) }
    var loading by remember(key) { mutableStateOf(true) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var autoScroll by remember(key) { mutableStateOf(DesktopBackend.lyricsAutoScroll()) }
    var blur by remember(key) { mutableStateOf(DesktopBackend.lyricsBlur()) }
    var romanizationEnabled by remember(key) { mutableStateOf(DesktopBackend.lyricsRomanizationEnabled()) }
    var style by remember(key) { mutableStateOf(DesktopBackend.lyricsAnimation()) }
    val romanizedLines = remember(key) { mutableStateMapOf<Int, String>() }
    val aiRomanizedLines = remember(key) { mutableStateMapOf<Int, String>() }
    val translatedLines = remember(key) { mutableStateMapOf<Int, String>() }
    var translationLanguage by remember(key) { mutableStateOf(DesktopBackend.lyricsTranslationLanguage()) }
    var translating by remember(key) { mutableStateOf(false) }
    var translationError by remember(key) { mutableStateOf<String?>(null) }
    var showComments by remember(key) { mutableStateOf(false) }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var suspendedUntil by remember(key) { mutableLongStateOf(0L) }
    val reducedMotion = DesktopBackend.reducedMotion()
    val reducedTransparency = DesktopBackend.reducedTransparency()
    val nestedConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source == NestedScrollSource.UserInput) suspendedUntil = System.currentTimeMillis() + 5_000
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    LaunchedEffect(key) {
        loading = true
        error = null
        try {
            result = DesktopLyricsRepository.load(track)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Could not load lyrics."
        }
        loading = false
    }
    val lines = result?.lines.orEmpty()
    LaunchedEffect(key, lines, translationLanguage) {
        translatedLines.clear()
        aiRomanizedLines.clear()
        val sourceLines = lines.map(DesktopLyricLine::text)
        if (sourceLines.isNotEmpty()) {
            val savedTranslation = withContext(Dispatchers.IO) {
                DesktopBackend.cachedLyricVariant(key, "translation", translationLanguage, sourceLines)
            }
            savedTranslation?.lines?.forEachIndexed { index, line -> if (line.isNotBlank()) translatedLines[index] = line }
            val savedRomanization = withContext(Dispatchers.IO) {
                DesktopBackend.cachedLyricVariant(key, "romanization", "Latin", sourceLines)
            }
            savedRomanization?.lines?.forEachIndexed { index, line -> if (line.isNotBlank()) aiRomanizedLines[index] = line }
        }
    }
    val featurePackRevision by DesktopOptionalFeaturePacks.revision.collectAsState()
    LaunchedEffect(key, result, romanizationEnabled, featurePackRevision) {
        romanizedLines.clear()
        if (romanizationEnabled) {
            val japaneseContext = lines.any { DesktopOptionalFeaturePacks.containsJapanese(it.text) }
            val translated = withContext(Dispatchers.Default) {
                lines.mapIndexedNotNull { index, line ->
                    runCatching { DesktopLyricsRomanizer.romanize(line.text, japaneseContext) }
                        .getOrNull()
                        ?.takeIf(String::isNotBlank)
                        ?.let { index to it }
                }
            }
            romanizedLines.putAll(translated)
        }
    }
    val currentLine = lines.indexOfLast { it.startsAtMillis != null && it.startsAtMillis <= positionMillis }.coerceAtLeast(0)
    LaunchedEffect(currentLine, autoScroll, suspendedUntil, key) {
        if (autoScroll && lines.isNotEmpty()) {
            val wait = suspendedUntil - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            if (suspendedUntil <= System.currentTimeMillis()) runCatching { state.animateScrollToItem(currentLine, scrollOffset = -120) }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val artworkSize = minOf(260.dp, maxHeight * .42f, maxWidth * .26f)
        val controlsWidth = minOf(300.dp, maxWidth * .32f).coerceAtLeast(180.dp)
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(26.dp))
                .background(XanColors.Background)
                .border(1.dp, XanColors.Border.copy(alpha = .72f), RoundedCornerShape(26.dp)),
        ) {
            ExpandedPlayerBackdrop(track, snapshot.status == PlaybackStatus.PLAYING, animatedCanvas, reducedMotion, reducedTransparency, snapshot.audioSpectrum)
            Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                ExpandedPlayerControls(
                    snapshot,
                    cast,
                    controlsEnabled,
                    Modifier.width(controlsWidth).fillMaxHeight(),
                    artworkSize,
                )
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (showComments) "Comments" else "Lyrics", style = MaterialTheme.typography.headlineLarge, color = XanColors.Text)
                            Text(if (showComments) "Public YouTube comments · ${track.title}" else "${track.title} · ${track.artist}", color = XanColors.Muted, maxLines = 1)
                            if (!showComments) result?.let { Text("${it.provider}${if (it.cached) " · cached" else ""}", color = XanColors.Accent, style = MaterialTheme.typography.labelSmall) }
                        }
                        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close lyrics") }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterPill("Lyrics", !showComments) { showComments = false }
                        FilterPill("Comments", showComments) { showComments = true }
                    }
                    if (!showComments) {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterPill("Auto-scroll", autoScroll) {
                                autoScroll = !autoScroll
                                DesktopBackend.setLyricsAutoScroll(autoScroll)
                            }
                            FilterPill("Blur inactive", blur) {
                                blur = !blur
                                DesktopBackend.setLyricsBlur(blur)
                            }
                            FilterPill("Romanize lyrics", romanizationEnabled) {
                                romanizationEnabled = !romanizationEnabled
                                DesktopBackend.setLyricsRomanizationEnabled(romanizationEnabled)
                            }
                            TextButton(
                                enabled = !translating && lines.isNotEmpty() && (DesktopBackend.lyricsTranslationConfigured() || translatedLines.isNotEmpty()),
                                onClick = {
                                    translating = true
                                    translationError = null
                                    scope.launch {
                                        try {
                                            val sourceLines = lines.map(DesktopLyricLine::text)
                                            val translations = withContext(Dispatchers.IO) {
                                                DesktopBackend.cachedLyricVariant(key, "translation", translationLanguage, sourceLines)
                                            }?.lines ?: DesktopBackend.translateLyrics(sourceLines, translationLanguage).also { translated ->
                                                withContext(Dispatchers.IO) {
                                                    DesktopBackend.cacheLyricVariant(key, "translation", translationLanguage, sourceLines, translated)
                                                }
                                            }
                                            translatedLines.clear()
                                            translations.forEachIndexed { index, line -> if (line.isNotBlank()) translatedLines[index] = line }
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (failure: Exception) {
                                            translationError = failure.message ?: "Could not translate lyrics."
                                        } finally {
                                            translating = false
                                        }
                                    }
                                },
                            ) { Text(if (translating) "Translating…" else "Translate to $translationLanguage") }
                            TextButton(
                                enabled = !translating && lines.isNotEmpty() && (DesktopBackend.lyricsTranslationConfigured() || aiRomanizedLines.isNotEmpty()),
                                onClick = {
                                    translating = true
                                    translationError = null
                                    scope.launch {
                                        try {
                                            val sourceLines = lines.map(DesktopLyricLine::text)
                                            val romanized = withContext(Dispatchers.IO) {
                                                DesktopBackend.cachedLyricVariant(key, "romanization", "Latin", sourceLines)
                                            }?.lines ?: DesktopBackend.romanizeLyricsWithAi(sourceLines).also { converted ->
                                                withContext(Dispatchers.IO) {
                                                    DesktopBackend.cacheLyricVariant(key, "romanization", "Latin", sourceLines, converted)
                                                }
                                            }
                                            aiRomanizedLines.clear()
                                            romanized.forEachIndexed { index, line -> if (line.isNotBlank()) aiRomanizedLines[index] = line }
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (failure: Exception) {
                                            translationError = failure.message ?: "Could not romanize lyrics."
                                        } finally {
                                            translating = false
                                        }
                                    }
                                },
                            ) { Text(if (translating) "Working…" else "AI romanize") }
                        }
                    }
                    if (translationError != null) Text(translationError.orEmpty(), Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (!DesktopBackend.lyricsTranslationConfigured()) Text("Add an OpenRouter API key in Settings → Lyrics to enable translation.", Modifier.padding(top = 4.dp), color = XanColors.Muted, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("XAN", "Fade", "Glow", "Slide", "Karaoke", "Apple", "Apple V2", "Lyrics V2", "Metro Lyrics", "None").forEach { option ->
                            FilterPill(option, style == option) { style = option; DesktopBackend.setLyricsAnimation(option) }
                        }
                    }
                    }
                    when {
                        showComments -> DesktopCommentsPane(track, Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp))
                        loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = XanColors.Accent)
                                Text("Finding synchronized lyrics…", Modifier.padding(top = 12.dp), color = XanColors.Muted)
                            }
                        }
                        error != null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = XanColors.Accent, modifier = Modifier.size(40.dp))
                                Text(error!!, Modifier.padding(top = 10.dp), color = XanColors.Muted, textAlign = TextAlign.Center)
                                TextButton(onClick = {
                                    scope.launch {
                                        loading = true
                                        error = null
                                        try { result = DesktopLyricsRepository.load(track) }
                                        catch (cancelled: CancellationException) { throw cancelled }
                                        catch (failure: Exception) { error = failure.message ?: "Could not load lyrics." }
                                        loading = false
                                    }
                                }) { Text("Try again") }
                            }
                        }
                        else -> LazyColumn(
                            state = state,
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp)
                                .nestedScroll(nestedConnection),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                            contentPadding = PaddingValues(vertical = 90.dp),
                        ) {
                            itemsIndexed(lines) { index, line ->
                                val active = index == currentLine && line.startsAtMillis != null
                                val color by animateColorAsState(
                                    if (active) XanColors.Text else XanColors.Muted,
                                    if (reducedMotion) androidx.compose.animation.core.snap() else tween(250),
                                    label = "lyrics-line-$index",
                                )
                                val offset by animateDpAsState(if (style == "Slide" && active && !reducedMotion) 0.dp else 4.dp, if (reducedMotion) androidx.compose.animation.core.snap() else tween(250), label = "lyrics-slide-$index")
                                val alpha = when (style) {
                                    "Fade" -> if (active) 1f else .62f
                                    "Glow" -> if (active) 1f else .55f
                                    "Lyrics V2" -> if (active) 1f else .35f
                                    "Apple", "Apple V2" -> if (active) 1f else .52f
                                    "Slide" -> if (active) 1f else .72f
                                    "None" -> 1f
                                    else -> if (active) 1f else .68f
                                }
                                val activeWord = line.words.indexOfLast { positionMillis >= it.startsAtMillis }
                                val lyricText = buildAnnotatedString {
                                    if (style in setOf("XAN", "Karaoke") && line.words.isNotEmpty()) {
                                        line.words.forEachIndexed { wordIndex, word ->
                                            pushStyle(SpanStyle(color = if (active && wordIndex == activeWord) XanColors.Accent else color, fontWeight = if (active && wordIndex == activeWord) FontWeight.Bold else FontWeight.Medium))
                                            append(word.text)
                                            append(' ')
                                            pop()
                                        }
                                    } else {
                                        pushStyle(
                                            SpanStyle(
                                                color = color,
                                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                                shadow = if (style == "Glow" && active) Shadow(XanColors.Accent.copy(alpha = .72f), Offset.Zero, 14f) else null,
                                            ),
                                        )
                                        append(line.text)
                                        pop()
                                    }
                                }
                                Column(
                                    Modifier.fillMaxWidth().offset(y = if (style == "Slide") offset else 0.dp).alpha(alpha)
                                        .then(if (blur && !reducedTransparency && !active) Modifier.blur(3.dp) else Modifier)
                                        .padding(vertical = 2.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        lyricText,
                                        Modifier.fillMaxWidth(),
                                        fontSize = when (style) {
                                            "Apple", "Apple V2" -> if (active) 30.sp else 18.sp
                                            "Lyrics V2" -> if (active) 28.sp else 17.sp
                                            "Metro Lyrics" -> if (active) 25.sp else 21.sp
                                            else -> if (active) 26.sp else 21.sp
                                        },
                                        lineHeight = 34.sp,
                                        textAlign = TextAlign.Center,
                                    )
                                    translatedLines[index]?.let { translated ->
                                        Text(
                                            translated,
                                            Modifier.fillMaxWidth().padding(top = 3.dp),
                                            color = if (active) XanColors.Text.copy(alpha = .88f) else XanColors.Muted,
                                            fontSize = if (active) 15.sp else 13.sp,
                                            lineHeight = 20.sp,
                                            textAlign = TextAlign.Center,
                                        )
                                    }
                                    romanizedLines[index]?.let { romanized ->
                                        Text(
                                            romanized,
                                            Modifier.fillMaxWidth().padding(top = if (translatedLines[index] == null) 2.dp else 0.dp),
                                            color = if (active) XanColors.Accent.copy(alpha = .9f) else XanColors.Muted,
                                            fontSize = if (active) 15.sp else 13.sp,
                                            lineHeight = 20.sp,
                                            textAlign = TextAlign.Center,
                                        )
                                    }
                                    aiRomanizedLines[index]?.let { romanized ->
                                        Text(
                                            romanized,
                                            Modifier.fillMaxWidth().padding(top = 2.dp),
                                            color = if (active) XanColors.Accent else XanColors.Muted,
                                            fontSize = if (active) 15.sp else 13.sp,
                                            lineHeight = 20.sp,
                                            textAlign = TextAlign.Center,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopCommentsPane(track: MusicTrack, modifier: Modifier = Modifier) {
    val videoId = track.videoId
    var comments by remember(videoId) { mutableStateOf<List<CommentThreadRenderer>>(emptyList()) }
    var continuation by remember(videoId) { mutableStateOf<String?>(null) }
    var loading by remember(videoId) { mutableStateOf(videoId != null) }
    var error by remember(videoId) { mutableStateOf<String?>(null) }
    var loadingMore by remember(videoId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun loadFirstPage() {
        if (videoId == null) return
        loading = true
        error = null
        try {
            val (page, next) = YouTube.comments(videoId).getOrThrow()
            comments = page
            continuation = next
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Could not load YouTube comments."
        } finally {
            loading = false
        }
    }

    suspend fun loadMore() {
        val token = continuation ?: return
        loadingMore = true
        error = null
        try {
            val (nextComments, nextToken) = YouTube.commentContinuation(token).getOrThrow()
            comments = comments + nextComments
            continuation = nextToken
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Could not load more comments."
        } finally {
            loadingMore = false
        }
    }

    LaunchedEffect(videoId) { loadFirstPage() }
    when {
        videoId == null -> Box(modifier, contentAlignment = Alignment.Center) {
            Text("This track does not have a YouTube video ID for comments.", color = XanColors.Muted, textAlign = TextAlign.Center)
        }
        loading && comments.isEmpty() -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(color = XanColors.Accent) }
        error != null && comments.isEmpty() -> Box(modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error.orEmpty(), color = XanColors.Muted, textAlign = TextAlign.Center)
                TextButton(onClick = { scope.launch { loadFirstPage() } }) { Text("Try again") }
            }
        }
        else -> LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            itemsIndexed(comments) { index, thread ->
                val comment = thread.comment?.commentRenderer ?: return@itemsIndexed
                Column(Modifier.fillMaxWidth().background(XanColors.Card, RoundedCornerShape(14.dp)).padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(comment.authorText?.runs?.joinToString("") { it.text }.orEmpty().ifBlank { "YouTube user" }, Modifier.weight(1f), color = XanColors.Text, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(comment.publishedTimeText?.runs?.joinToString("") { it.text }.orEmpty(), color = XanColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    Text(comment.contentText?.runs?.joinToString("") { it.text }.orEmpty(), Modifier.padding(top = 8.dp), color = XanColors.Text, lineHeight = 21.sp)
                    val likes = comment.voteCount?.runs?.joinToString("") { it.text }.orEmpty()
                    val replies = comment.replyCount ?: 0
                    if (likes.isNotBlank() || replies > 0) {
                        Text(buildString {
                            if (likes.isNotBlank()) append("$likes likes")
                            if (likes.isNotBlank() && replies > 0) append(" · ")
                            if (replies > 0) append("$replies replies")
                        }, Modifier.padding(top = 8.dp), color = XanColors.Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item {
                if (error != null && comments.isNotEmpty()) {
                    Text(error.orEmpty(), Modifier.fillMaxWidth().padding(8.dp), color = MaterialTheme.colorScheme.error)
                }
                when {
                    continuation != null -> OutlinedButton(
                        onClick = {
                            scope.launch { loadMore() }
                        },
                        enabled = !loadingMore,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (loadingMore) "Loading comments…" else "Load more comments") }
                    comments.isEmpty() -> Text("No public comments were found.", Modifier.fillMaxWidth().padding(24.dp), color = XanColors.Muted, textAlign = TextAlign.Center)
                    error != null -> Text(error.orEmpty(), Modifier.fillMaxWidth().padding(8.dp), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ExpandedPlayerBackdrop(
    track: MusicTrack,
    playing: Boolean,
    enabled: Boolean,
    reducedMotion: Boolean,
    reducedTransparency: Boolean,
    spectrum: List<Float>,
) {
    var artwork by remember(track.artworkUrl, track.localPath, enabled) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(track.artworkUrl, track.localPath, enabled) {
        artwork = if (!enabled) null else withContext(Dispatchers.IO) {
            track.artworkUrl?.let(DesktopBackend::loadArtwork)?.toComposeImageBitmap()
                ?: track.localPath?.let(DesktopBackend::loadLocalArtwork)?.toComposeImageBitmap()
        }
    }
    val moving = enabled && playing && !reducedMotion
    if (enabled && artwork != null) {
        if (moving) {
            val transition = rememberInfiniteTransition(label = "expanded-player-artwork")
            val scale by transition.animateFloat(
                initialValue = 1.04f,
                targetValue = 1.12f,
                animationSpec = infiniteRepeatable(tween(18_000), repeatMode = AnimationRepeatMode.Reverse),
                label = "expanded-player-artwork-scale",
            )
            val drift by transition.animateFloat(
                initialValue = -0.018f,
                targetValue = 0.018f,
                animationSpec = infiniteRepeatable(tween(22_000), repeatMode = AnimationRepeatMode.Reverse),
                label = "expanded-player-artwork-drift",
            )
            PlayerBackdropImage(artwork!!, scale, drift, reducedTransparency)
        } else {
            PlayerBackdropImage(artwork!!, 1.04f, 0f, reducedTransparency)
        }
    }
    if (enabled) {
        Box(
            Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(XanColors.Background.copy(alpha = if (reducedTransparency) .94f else .66f), XanColors.Background.copy(alpha = .86f)),
                ),
            ),
        )
    }
    if (enabled && playing && !reducedMotion && spectrum.isNotEmpty()) {
        Canvas(Modifier.fillMaxSize().alpha(if (reducedTransparency) .18f else .4f)) {
            val energy = spectrum.average().toFloat().coerceIn(0f, 1f)
            val radius = size.minDimension * (.18f + .12f * energy)
            drawCircle(XanColors.Accent.copy(alpha = .10f + energy * .10f), radius, Offset(size.width * .82f, size.height * .18f))
            drawCircle(Color(0xFFAF52DE).copy(alpha = .08f + energy * .08f), radius * .78f, Offset(size.width * .16f, size.height * .72f))
            val bands = spectrum.take(36)
            val step = size.width / (bands.size + 1)
            bands.forEachIndexed { index, value ->
                val height = size.height * (.025f + value.coerceIn(0f, 1f) * .13f)
                val x = step * (index + 1)
                drawLine(
                    color = if (index % 2 == 0) XanColors.Accent.copy(alpha = .48f) else Color(0xFFAF52DE).copy(alpha = .42f),
                    start = Offset(x, size.height * .92f),
                    end = Offset(x, size.height * .92f - height),
                    strokeWidth = step * .48f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun PlayerBackdropImage(artwork: ImageBitmap, scale: Float, drift: Float, reducedTransparency: Boolean) {
    Image(
        artwork,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = size.width * drift
        }.then(if (reducedTransparency) Modifier else Modifier.blur(38.dp))
            .alpha(if (reducedTransparency) 0.12f else 0.28f),
    )
}
