package app.xan.music.platform.changelog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.xan.music.R
import app.xan.music.platform.github.GitHubRelease
import app.xan.music.platform.github.GitHubReleases
import app.xan.music.ui.component.IconButton
import app.xan.music.ui.utils.appTopBarWindowInsets
import app.xan.music.ui.utils.backToMain

private sealed interface ChangelogState {
    data object Loading : ChangelogState
    data class Loaded(val releases: List<GitHubRelease>) : ChangelogState
    data class Error(val message: String) : ChangelogState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    var state by remember { mutableStateOf<ChangelogState>(ChangelogState.Loading) }

    LaunchedEffect(Unit) {
        state = ChangelogState.Loading
        state = GitHubReleases.fetch(includePrereleases = true).fold(
            onSuccess = { ChangelogState.Loaded(it) },
            onFailure = { ChangelogState.Error(it.message ?: "") },
        )
    }

    when (val current = state) {
        ChangelogState.Loading -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(R.string.loading_github_releases),
                    modifier = Modifier.padding(top = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is ChangelogState.Error -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = current.message.ifBlank { stringResource(R.string.unable_to_load_github_releases) },
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        is ChangelogState.Loaded -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 88.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(current.releases, key = { it.tagName }) { release ->
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = release.tagName,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            if (release.title.isNotBlank() && release.title != release.tagName) {
                                Text(
                                    text = release.title,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Text(
                                text = release.displayDate,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = release.body.ifBlank { stringResource(R.string.no_release_notes_provided) },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }

    TopAppBar(
        windowInsets = appTopBarWindowInsets(),
        title = { Text(stringResource(R.string.changelog)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painter = painterResource(R.drawable.xan_icon_back),
                    contentDescription = null,
                )
            }
        },
        scrollBehavior = scrollBehavior,
    )
}
