/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.xan.music.db.MusicDatabase
import app.xan.music.db.entities.ArtistEntity
import app.xan.music.ui.component.GlassComponent
import app.xan.music.ui.component.LocalGlassEffectConfig
import app.xan.music.ui.component.isGlassAllowed
import app.xan.music.ui.component.liquidGlass
import app.xan.music.utils.artistFollowErrorMessage
import app.xan.music.utils.toggleArtistFollowing
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.music.innertube.YouTube
import com.music.innertube.models.ArtistItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ArtistSelectionOnboardingScreen(
    database: MusicDatabase,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val glassConfig = LocalGlassEffectConfig.current
    val useGlass = glassConfig.isEnabledFor(GlassComponent.NAV_BAR) && isGlassAllowed()
    val followedArtists by database.artistsBookmarkedByNameAsc().collectAsState(initial = emptyList())
    var suggestions by remember { mutableStateOf<List<ArtistItem>>(emptyList()) }
    var searchResults by remember { mutableStateOf<List<ArtistItem>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loadingSuggestions by remember { mutableStateOf(true) }
    var searching by remember { mutableStateOf(false) }
    var pendingArtistIds by remember { mutableStateOf(emptySet<String>()) }

    LaunchedEffect(Unit) {
        loadingSuggestions = true
        // Home recommendations can contain just one artist (or no artist rows).
        // Supplement them with several independent artist searches so onboarding
        // offers a useful range of choices rather than a single featured artist.
        val homeArtists = YouTube.publicHome().getOrNull()
            ?.sections
            ?.flatMap { it.items }
            ?.filterIsInstance<ArtistItem>()
            .orEmpty()
        val discoveryQueries = listOf(
            "popular artists",
            "pop artists",
            "hip hop artists",
            "rock artists",
            "R&B artists",
            "latin music artists",
            "indie artists",
            "electronic music artists",
        )
        val discovered = mutableListOf<ArtistItem>()
        discovered.addAll(homeArtists)
        for (discoveryQuery in discoveryQueries) {
            if (discovered.distinctBy(ArtistItem::id).size >= 40) break
            val artists = YouTube.search(discoveryQuery, YouTube.SearchFilter.FILTER_ARTIST)
                .getOrNull()
                ?.items
                ?.filterIsInstance<ArtistItem>()
                .orEmpty()
            discovered.addAll(artists)
        }
        suggestions = discovered.distinctBy(ArtistItem::id).take(40)
        loadingSuggestions = false
    }

    LaunchedEffect(query) {
        val searchQuery = query.trim()
        if (searchQuery.isEmpty()) {
            searchResults = emptyList()
            searching = false
            return@LaunchedEffect
        }

        delay(350)
        searching = true
        val results = YouTube.search(searchQuery, YouTube.SearchFilter.FILTER_ARTIST)
            .getOrNull()
            ?.items
            ?.filterIsInstance<ArtistItem>()
            .orEmpty()
            .distinctBy(ArtistItem::id)
            .take(40)
        if (query.trim() == searchQuery) searchResults = results
        searching = false
    }

    val visibleArtists = if (query.isBlank()) suggestions else searchResults

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.artist_onboarding_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = GoogleSansFlex,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.artist_onboarding_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onSkip,
                modifier = if (useGlass) Modifier.liquidGlass(glassConfig, shape = RoundedCornerShape(50)) else Modifier,
                colors = if (useGlass) ButtonDefaults.textButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = glassConfig.textColor,
                ) else ButtonDefaults.textButtonColors(),
            ) {
                Text(stringResource(R.string.artist_onboarding_skip))
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.artist_onboarding_search)) },
            leadingIcon = {
                Icon(Icons.Rounded.Search, contentDescription = null)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { query = "" },
                        modifier = if (useGlass) Modifier.liquidGlass(glassConfig, shape = CircleShape) else Modifier,
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = null)
                    }
                }
            },
        )

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    if (query.isBlank()) R.string.artist_onboarding_recommended
                    else R.string.artist_onboarding_results
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.artist_onboarding_selected_count, followedArtists.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (loadingSuggestions || searching) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }

        if (visibleArtists.isEmpty() && !loadingSuggestions && !searching) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        if (suggestions.isEmpty() && query.isBlank()) R.string.artist_onboarding_search_error
                        else R.string.artist_onboarding_no_results
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(top = 10.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visibleArtists, key = ArtistItem::id) { artist ->
                    val isFollowing = followedArtists.any { it.id == artist.id }
                    val isPending = artist.id in pendingArtistIds
                    ArtistFollowChoice(
                        context = context,
                        artist = artist,
                        isFollowing = isFollowing,
                        isPending = isPending,
                        useGlass = useGlass,
                        glassConfig = glassConfig,
                        onClick = {
                            if (isPending) return@ArtistFollowChoice
                            pendingArtistIds = pendingArtistIds + artist.id
                            scope.launch {
                                try {
                                    toggleArtistFollowing(
                                        context = context,
                                        database = database,
                                        artist = ArtistEntity(
                                            id = artist.id,
                                            name = artist.title,
                                            channelId = artist.channelId,
                                            thumbnailUrl = artist.thumbnail,
                                        ),
                                    )
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    Toast.makeText(
                                        context,
                                        context.artistFollowErrorMessage(error),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                } finally {
                                    pendingArtistIds = pendingArtistIds - artist.id
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistFollowChoice(
    context: Context,
    artist: ArtistItem,
    isFollowing: Boolean,
    isPending: Boolean,
    useGlass: Boolean,
    glassConfig: app.xan.music.ui.component.GlassEffectConfig,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(artist.thumbnail)
                    .crossfade(true)
                    .build(),
                contentDescription = artist.title,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = artist.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        if (isFollowing) R.string.artist_onboarding_following
                        else R.string.artist_onboarding_follow
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            FilledTonalButton(
                onClick = onClick,
                enabled = !isPending,
                shape = CircleShape,
                colors = if (useGlass) ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = glassConfig.textColor,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = glassConfig.textColor.copy(alpha = 0.6f),
                ) else ButtonDefaults.filledTonalButtonColors(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = if (useGlass) Modifier.liquidGlass(glassConfig, shape = CircleShape) else Modifier,
            ) {
                if (isPending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = if (isFollowing) Icons.Rounded.Check else Icons.Rounded.Add,
                        contentDescription = null,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(
                        if (isFollowing) R.string.artist_onboarding_following
                        else R.string.artist_onboarding_follow
                    ),
                    fontSize = 13.sp,
                )
            }
        }
    }
}
