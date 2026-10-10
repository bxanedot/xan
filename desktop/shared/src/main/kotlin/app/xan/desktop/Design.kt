package app.xan.desktop

import com.music.innertube.ExternalVideoFormat
import com.music.innertube.ExternalVideoInfo
import com.music.innertube.ExternalVideoSearchResult

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class XanPalette(
    val id: String,
    val label: String,
    val background: Color,
    val panel: Color,
    val card: Color,
    val cardLight: Color,
    val selected: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val border: Color,
) {
    companion object {
        val all = listOf(
            XanPalette("xan", "XAN", Color(0xFF121212), Color(0xF0242427), Color(0xFF1C1C1E), Color(0xFF2C2C2E), Color(0xFF353137), Color(0xFFF8F6F7), Color(0xFFAAA5AA), Color(0xFFFA2D48), Color(0xFF49444A)),
            XanPalette("midnight", "Midnight", Color(0xFF0D1320), Color(0xF0192335), Color(0xFF172236), Color(0xFF24334A), Color(0xFF2E405B), Color(0xFFF3F6FC), Color(0xFFA8B5C9), Color(0xFF66B8FF), Color(0xFF40516A)),
            XanPalette("plum", "Plum", Color(0xFF19121C), Color(0xF0271B2D), Color(0xFF241A29), Color(0xFF36263D), Color(0xFF47324F), Color(0xFFF9F2FA), Color(0xFFBBAABD), Color(0xFFD58AE8), Color(0xFF594260)),
            XanPalette("forest", "Forest", Color(0xFF101813), Color(0xF01D2A21), Color(0xFF19241C), Color(0xFF29382D), Color(0xFF354B3B), Color(0xFFF2F7F2), Color(0xFFAAB9AC), Color(0xFF76D39B), Color(0xFF42594A)),
            XanPalette("light", "Light", Color(0xFFF4F2F4), Color(0xF0FFFFFF), Color(0xFFFFFFFF), Color(0xFFE8E5E9), Color(0xFFE1DCE2), Color(0xFF211D22), Color(0xFF706970), Color(0xFFD92743), Color(0xFFD0CAD2)),
        )
        val ids = all.map { it.id }.toSet()
        fun find(id: String): XanPalette = all.firstOrNull { it.id == id } ?: all.first()
    }
}

internal object XanColors {
    private var selectedTheme by mutableStateOf(DesktopBackend.colorTheme())
    private val palette get() = XanPalette.find(selectedTheme)
    fun selectTheme(id: String) { selectedTheme = id.takeIf { it in XanPalette.ids } ?: "xan" }
    val Background get() = palette.background
    val Panel get() = palette.panel
    val Card get() = palette.card
    val CardLight get() = palette.cardLight
    val Selected get() = palette.selected
    val Text get() = palette.text
    val Muted get() = palette.muted
    val Accent get() = palette.accent
    val Border get() = palette.border
}

private val XanDisplay = FontFamily(Font("fonts/outfit.ttf"))
internal val XanBrandFont = FontFamily(Font("fonts/chillax_semibold.otf"))

internal data class XanFontOption(val id: String, val label: String)

internal object XanFonts {
    val options = listOf(
        XanFontOption("system", "System"),
        XanFontOption("google_sans", "Google Sans"),
        XanFontOption("sans_flex", "Sans Flex"),
        XanFontOption("outfit", "Outfit"),
        XanFontOption("plus_jakarta", "Plus Jakarta Sans"),
    )
    private val googleSans = FontFamily(Font("fonts/google_sans_flex.ttf"))
    private val sansFlex = FontFamily(Font("fonts/sans_flex.ttf"))
    private val outfit = FontFamily(Font("fonts/outfit.ttf"))
    private val plusJakarta = FontFamily(Font("fonts/plus_jakarta_sans.ttf"))
    private var selectedId by mutableStateOf(DesktopBackend.fontFamily())

    val selected: String get() = selectedId
    fun refresh() { selectedId = DesktopBackend.fontFamily() }
    fun select(id: String) {
        DesktopBackend.setFontFamily(id)
        selectedId = DesktopBackend.fontFamily()
    }
    fun family(): FontFamily = when (selectedId) {
        "google_sans" -> googleSans
        "sans_flex" -> sansFlex
        "outfit" -> outfit
        "plus_jakarta" -> plusJakarta
        else -> FontFamily.Default
    }
}

@Composable
internal fun XanTheme(content: @Composable () -> Unit) {
    val appFont = XanFonts.family()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = XanColors.Accent,
            background = XanColors.Background,
            surface = XanColors.Card,
            surfaceVariant = XanColors.CardLight,
            onBackground = XanColors.Text,
            onSurface = XanColors.Text,
        ),
        typography = Typography(
            bodyLarge = androidx.compose.ui.text.TextStyle(fontFamily = appFont, fontSize = 15.sp),
            bodyMedium = androidx.compose.ui.text.TextStyle(fontFamily = appFont, fontSize = 14.sp),
            labelLarge = androidx.compose.ui.text.TextStyle(fontFamily = appFont, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            labelSmall = androidx.compose.ui.text.TextStyle(fontFamily = appFont, fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            titleLarge = androidx.compose.ui.text.TextStyle(fontFamily = appFont, fontSize = 25.sp, fontWeight = FontWeight.SemiBold),
            headlineLarge = androidx.compose.ui.text.TextStyle(fontFamily = appFont, fontSize = 38.sp, fontWeight = FontWeight.Bold),
        ),
        content = content,
    )
}

@Composable
internal fun XanMark(size: Dp, logo: AppLogoVariant = DesktopBackend.appLogo()) {
    Image(painterResource(logo.resource), contentDescription = "XAN ${logo.label} logo", modifier = Modifier.size(size))
}

@Composable
internal fun ScreenTitle(title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 22.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, color = XanColors.Text)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = XanColors.Muted)
            }
        }
        trailing?.invoke()
    }
}

@Composable
internal fun SectionTitle(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = XanColors.Text)
        if (action != null && onAction != null) Text(action, Modifier.clickable(onClick = onAction), color = XanColors.Muted, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) XanColors.Text else XanColors.CardLight)
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
        color = if (selected) XanColors.Background else XanColors.Text,
        style = MaterialTheme.typography.labelLarge,
    )
}

internal data class MusicTrack(
    val title: String,
    val artist: String,
    val album: String,
    val colors: List<Color>,
    val motif: String,
    val videoId: String? = null,
    val source: String? = null,
    val artworkUrl: String? = null,
    val durationSeconds: Int? = null,
    val artistId: String? = null,
    val albumId: String? = null,
    val browseId: String? = null,
    val collectionId: String? = null,
    val kind: String = "song",
    val localPath: String? = null,
    val metadataVerified: Boolean = true,
    val externalVideoResult: ExternalVideoSearchResult? = null,
    val externalVideoInfo: ExternalVideoInfo? = null,
    val externalVideoFormat: ExternalVideoFormat? = null,
)

internal data class ArtistOfferSection(val title: String, val items: List<MusicTrack>)

internal data class ArtistDetails(
    val description: String?,
    val subscriberCount: String?,
    val monthlyListeners: String?,
    val sections: List<ArtistOfferSection>,
)

internal data class CollectionDetails(val description: String?, val tracks: List<MusicTrack>)

@Composable
internal fun Artwork(track: MusicTrack, size: Dp, modifier: Modifier = Modifier, corner: Dp = 18.dp) {
    var bitmap by remember(track.artworkUrl, track.localPath) { mutableStateOf<ImageBitmap?>(null) }
    val artwork = bitmap
    LaunchedEffect(track.artworkUrl, track.localPath) {
        bitmap = withContext(Dispatchers.IO) {
            track.artworkUrl?.let(DesktopBackend::loadArtwork)
                ?.toComposeImageBitmap()
                ?: track.localPath?.let(DesktopBackend::loadLocalArtwork)?.toComposeImageBitmap()
        }
    }
    Box(
        modifier.size(size).clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(track.colors))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(corner)),
        contentAlignment = Alignment.Center,
    ) {
        if (artwork != null) Image(artwork, contentDescription = "${track.title} artwork", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(track.motif, color = Color.White.copy(alpha = 0.72f), fontSize = (size.value * 0.45f).sp, fontFamily = XanDisplay)
    }
}

@Composable
internal fun TrackCard(track: MusicTrack, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clickable(onClick = onClick)) {
        Artwork(track, 190.dp, Modifier.fillMaxWidth())
        Spacer(Modifier.height(9.dp))
        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = XanColors.Text, fontWeight = FontWeight.SemiBold)
        Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = XanColors.Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun TrackRow(
    track: MusicTrack,
    index: Int,
    onDownload: (() -> Unit)? = null,
    onQueue: (() -> Unit)? = null,
    onRemoveCache: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    var playlistMenuOpen by remember(track.videoId, track.localPath, track.title) { mutableStateOf(false) }
    var createPlaylistDialogOpen by remember(track.videoId, track.localPath, track.title) { mutableStateOf(false) }
    var playlistOptions by remember { mutableStateOf(emptyList<CatalogPlaylist>()) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Artwork(track, 52.dp, corner = 11.dp)
        Column(Modifier.weight(1f)) {
            Text(track.title, color = XanColors.Text, fontWeight = FontWeight.SemiBold)
            Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = XanColors.Muted)
        }
        Text(track.album, color = XanColors.Muted, style = MaterialTheme.typography.bodyMedium)
        track.durationSeconds?.let { seconds -> Text("%d:%02d".format(seconds / 60, seconds % 60), Modifier.padding(horizontal = 12.dp), color = XanColors.Muted) }
        if (onQueue != null) IconButton(onClick = onQueue) {
            Icon(Icons.Rounded.QueueMusic, contentDescription = "Add ${track.title} to queue", tint = XanColors.Muted)
        }
        if (track.kind == "song") Box {
            IconButton(onClick = { playlistOptions = DesktopBackend.playlists(); playlistMenuOpen = true }) {
                Icon(Icons.Rounded.PlaylistAdd, contentDescription = "Add ${track.title} to a playlist", tint = XanColors.Muted)
            }
            DropdownMenu(expanded = playlistMenuOpen, onDismissRequest = { playlistMenuOpen = false }) {
                Text("Add to playlist", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = XanColors.Muted, style = MaterialTheme.typography.labelMedium)
                if (playlistOptions.isEmpty()) {
                    Text("No playlists yet", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = XanColors.Muted, style = MaterialTheme.typography.bodySmall)
                } else playlistOptions.forEach { playlist ->
                    DropdownMenuItem(text = { Text("${playlist.name} · ${playlist.trackCount}") }, onClick = {
                        playlistMenuOpen = false
                        runCatching { DesktopBackend.addTrackToPlaylist(playlist.id, track) }
                            .onSuccess { added -> DesktopPlayer.setNotice(if (added) "Added ${track.title} to ${playlist.name}." else "${track.title} is already in ${playlist.name}.") }
                            .onFailure { DesktopPlayer.setNotice(it.message ?: "Could not add this song to the playlist.") }
                    })
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Create playlist and add song…") }, onClick = {
                    playlistMenuOpen = false
                    createPlaylistDialogOpen = true
                })
            }
        }
        if (onDownload != null) {
            IconButton(onClick = onDownload) { Icon(Icons.Rounded.Download, contentDescription = "Download ${track.kind} ${track.title}", tint = XanColors.Accent) }
        }
        if (onRemoveCache != null) {
            IconButton(onClick = onRemoveCache) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove ${track.title} from cached songs", tint = XanColors.Muted) }
        }
    }
    if (createPlaylistDialogOpen) CreatePlaylistAndAddTrackDialog(track) { createPlaylistDialogOpen = false }
}

@Composable
internal fun CreatePlaylistAndAddTrackDialog(track: MusicTrack, onDismiss: () -> Unit) {
    var name by remember(track.videoId, track.localPath, track.title) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Playlist name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    runCatching { DesktopBackend.createPlaylist(name) }
                        .onSuccess { playlist ->
                            onDismiss()
                            runCatching { DesktopBackend.addTrackToPlaylist(playlist.id, track) }
                                .onSuccess { added ->
                                    DesktopPlayer.setNotice(
                                        if (added) "Created ${playlist.name} and added ${track.title}."
                                        else "Created ${playlist.name}; ${track.title} is already in it.",
                                    )
                                }
                                .onFailure { error ->
                                    DesktopPlayer.setNotice("Created ${playlist.name}, but could not add ${track.title}: ${error.message ?: "unknown error"}")
                                }
                        }
                        .onFailure { error -> DesktopPlayer.setNotice(error.message ?: "Could not create playlist.") }
                },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun EmptyArtwork(icon: ImageVector, modifier: Modifier = Modifier, color: Color = XanColors.CardLight) {
    Box(modifier.clip(RoundedCornerShape(15.dp)).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = XanColors.Muted, modifier = Modifier.size(30.dp))
    }
}
