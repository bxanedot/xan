package app.xan.music.ui.screens.search.suggestions

import androidx.compose.animation.core.animateDpAsState
import app.xan.music.ui.utils.bounceClick
import app.xan.music.ui.utils.combinedBounceClick
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.xan.music.R
import app.xan.music.constants.SuggestionRegionSlugToName
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun localizedSuggestionRegionName(slug: String): String {
    if (slug == "system") return stringResource(R.string.system_default)
    if (slug == "us") return stringResource(R.string.suggestions_global_usa)
    if (slug !in SuggestionRegionSlugToName) return stringResource(R.string.suggestions_global_charts)

    val locale = LocalConfiguration.current.locales[0]
    val country = Locale.Builder()
        .setRegion(slug.uppercase(Locale.ROOT))
        .build()
        .getDisplayCountry(locale)
    return country.takeIf { it.isNotBlank() }
        ?: SuggestionRegionSlugToName[slug]
        ?: stringResource(R.string.suggestions_global_charts)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionRegionSheet(
    currentRegionSlug: String,
    onRegionSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    
    val regions = mutableListOf<Pair<String, String>>()
    for (slug in SuggestionRegionSlugToName.keys) {
        if (slug != "system") regions.add(slug to localizedSuggestionRegionName(slug))
    }
    val filteredRegions = remember(searchQuery, regions) {
        regions
            .filter { it.second.contains(searchQuery, ignoreCase = true) }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val bottomSheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = bottomSheetState
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.choose_suggestions_region),
                style = MaterialTheme.typography.labelLarge
            )
            
            DockedSearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = {},
                        expanded = false,
                        onExpandedChange = {},
                        placeholder = { Text(stringResource(R.string.search_regions)) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = stringResource(R.string.search)
                            )
                        },
                    )
                },
                expanded = false,
                onExpandedChange = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {}
            
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(24.dp))
            ) {
                // System Default Section
                item {
                    Text(
                        text = stringResource(R.string.suggestions_system_section),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    )
                }
                item {
                    val isSelected = currentRegionSlug == "system"
                    RegionListItem(
                        headlineContent = { Text(stringResource(R.string.system_default)) },
                        selected = isSelected,
                        items = 1,
                        index = 0,
                        onClick = {
                            onRegionSelected("system")
                            scope.launch { bottomSheetState.hide() }.invokeOnCompletion {
                                if (!bottomSheetState.isVisible) {
                                    onDismiss()
                                }
                            }
                        }
                    )
                }
                
                item {
                    Spacer(Modifier.height(12.dp))
                }

                item {
                    Text(
                        text = stringResource(R.string.suggestions_countries_regions),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(
                            top = 14.dp,
                            bottom = 16.dp,
                            start = 16.dp,
                            end = 16.dp
                        )
                    )
                }

                itemsIndexed(filteredRegions, key = { _, pair -> pair.first }) { index, (slug, name) ->
                    val isSelected = slug == currentRegionSlug
                    RegionListItem(
                        headlineContent = { Text(name) },
                        selected = isSelected,
                        items = filteredRegions.size,
                        index = index,
                        onClick = {
                            onRegionSelected(slug)
                            scope.launch { bottomSheetState.hide() }.invokeOnCompletion {
                                if (!bottomSheetState.isVisible) {
                                    onDismiss()
                                }
                            }
                        }
                    )
                    Spacer(Modifier.height(2.dp))
                }
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
    
    LaunchedEffect(searchQuery) {
        if (filteredRegions.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }
}

@Composable
fun RegionListItem(
    headlineContent: @Composable (() -> Unit),
    selected: Boolean,
    items: Int,
    index: Int,
    supportingContent: @Composable (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val top by animateDpAsState(
        if (isPressed) 36.dp
        else {
            if (items == 1 || index == 0) 20.dp
            else 4.dp
        },
        label = "top"
    )
    val bottom by animateDpAsState(
        if (isPressed) 36.dp
        else {
            if (items == 1 || index == items - 1) 20.dp
            else 4.dp
        },
        label = "bottom"
    )

    ListItem(
        headlineContent = headlineContent,
        supportingContent = supportingContent,
        leadingContent = if (selected) {
            {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.suggestions_selected)
                )
            }
        } else null,
        colors =
            if (selected) ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(
                    alpha = 0.3f
                ), leadingIconColor = MaterialTheme.colorScheme.primary
            )
            else ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier
            .clip(
                if (selected) CircleShape
                else RoundedCornerShape(
                    topStart = top,
                    topEnd = top,
                    bottomStart = bottom,
                    bottomEnd = bottom
                )
            )
            .bounceClick(
                onClick = onClick,
                interactionSource = interactionSource,
                indication = null
            )
    )
}
