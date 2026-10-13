/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.xan.music.BuildConfig
import app.xan.music.LocalPlayerAwareWindowInsets
import app.xan.music.R
import app.xan.music.platform.ProjectLinks
import app.xan.music.constants.GitHubStarsCacheKey
import app.xan.music.constants.GitHubStarsCachedAtKey
import app.xan.music.utils.rememberPreference
import app.xan.music.ui.component.IconButton
import app.xan.music.ui.component.Material3SettingsGroup
import app.xan.music.ui.component.Material3SettingsItem
import app.xan.music.ui.utils.XanLogoMotion
import app.xan.music.ui.utils.appTopBarWindowInsets
import app.xan.music.ui.utils.backToMain
import app.xan.music.ui.utils.xanLogoPainter
import app.xan.music.ui.utils.safeOpenUri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    onBack: (() -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val unknownString = stringResource(R.string.unknown)
    val playerAwareInsets = LocalPlayerAwareWindowInsets.current
    val bottomContentInset = with(LocalDensity.current) {
        playerAwareInsets.getBottom(this).toDp()
    }
    var discordUrl by remember { mutableStateOf(ProjectLinks.DISCORD_FALLBACK) }
    var githubStars by remember { mutableStateOf<Int?>(null) }
    val (cachedGithubStars, onCachedGithubStarsChange) = rememberPreference(GitHubStarsCacheKey, -1)
    val (cachedGithubStarsAt, onCachedGithubStarsAtChange) = rememberPreference(GitHubStarsCachedAtKey, 0L)
    var starsRefresh by remember { mutableStateOf(0) }
    var starsLoading by remember { mutableStateOf(true) }
    var starsRefreshFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { discordUrl = ProjectLinks.discordUrl() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { starsRefresh++ }
    LaunchedEffect(starsRefresh) {
        if (starsRefresh == 0) return@LaunchedEffect
        starsLoading = true
        starsRefreshFailed = false
        val refreshedStars = ProjectLinks.githubStars()
        if (refreshedStars != null) {
            githubStars = refreshedStars
            onCachedGithubStarsChange(refreshedStars)
            onCachedGithubStarsAtChange(System.currentTimeMillis())
        } else {
            starsRefreshFailed = true
        }
        starsLoading = false
    }
    val displayedGithubStars = githubStars ?: cachedGithubStars.takeIf { it >= 0 }
    val animatedGithubStars by animateIntAsState(
        targetValue = displayedGithubStars ?: 0,
        animationSpec = tween(durationMillis = 1_100),
        label = "githubStarCount",
    )
    val logoRotation by rememberInfiniteTransition(label = "aboutLogoRotation").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 90_000, easing = LinearEasing),
        ),
        label = "aboutLogoRotationAngle",
    )

    val installedDate = remember {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                .format(Date(packageInfo.firstInstallTime))
        } catch (_: Exception) {
            unknownString
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            // Keep the content clear of the top and side chrome. The bottom safe
            // area is included in the scroll content so the last row can move
            // completely above the floating player and navigation overlays.
            .windowInsetsPadding(
                playerAwareInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = xanLogoPainter(),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier
                    .size(138.dp)
                    .graphicsLayer {
                        rotationZ = XanLogoMotion.startupRotation.value + logoRotation
                    },
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.XAN_title),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 48.sp,
                    letterSpacing = 2.sp,
                ),
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        shape = CircleShape,
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Material3SettingsGroup(
            title = stringResource(R.string.developer_section),
            items = listOf(
                Material3SettingsItem(
                    leadingContent = {
                        Image(
                            painter = painterResource(R.drawable.bxane_profile),
                            contentDescription = "bxane GitHub profile photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape),
                        )
                    },
                    title = { Text("bxane") },
                    description = { Text(stringResource(R.string.lead_developer), color = MaterialTheme.colorScheme.primary) },
                    onClick = { uriHandler.safeOpenUri(context, ProjectLinks.LEAD_DEVELOPER) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.changelog_paper),
                    title = { Text(stringResource(R.string.changelog)) },
                    description = { Text(stringResource(R.string.github_release_notes_desc)) },
                    onClick = { navController.navigate("settings/changelog") },
                ),
            ),
        )

        Spacer(Modifier.height(24.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.community_section),
            items = listOf(
                Material3SettingsItem(
                    leadingContent = {
                        Icon(
                            painter = painterResource(R.drawable.star_filled_liked),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(36.dp),
                        )
                    },
                    title = {
                        Text(
                            stringResource(R.string.github_stars_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    description = if (starsRefreshFailed) {
                        {
                            androidx.compose.material3.TextButton(
                                onClick = { starsRefresh++ },
                            ) {
                                Text(
                                    if (displayedGithubStars != null && cachedGithubStarsAt > 0) {
                                        stringResource(
                                            R.string.github_stars_cached_retry,
                                            SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                                                .format(Date(cachedGithubStarsAt)),
                                        )
                                    } else {
                                        stringResource(R.string.github_stars_unavailable_retry)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    } else null,
                    trailingContent = {
                        if (displayedGithubStars != null) {
                            Text(
                                NumberFormat.getIntegerInstance().format(animatedGithubStars),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else if (starsLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("—", style = MaterialTheme.typography.titleMedium)
                        }
                    },
                    onClick = { uriHandler.safeOpenUri(context, ProjectLinks.STAR_REPOSITORY) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.newsletter),
                    title = { Text(stringResource(R.string.website)) },
                    description = { Text(ProjectLinks.WEBSITE.removePrefix("https://")) },
                    onClick = { uriHandler.safeOpenUri(context, ProjectLinks.WEBSITE) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.discord),
                    title = { Text(stringResource(R.string.discord_channel)) },
                    description = { Text(stringResource(R.string.join_discord)) },
                    onClick = { uriHandler.safeOpenUri(context, discordUrl) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.telegram),
                    title = { Text(stringResource(R.string.telegram_channel)) },
                    description = { Text(stringResource(R.string.join_telegram)) },
                    onClick = { uriHandler.safeOpenUri(context, ProjectLinks.TELEGRAM) },
                ),
            ),
        )

        Spacer(Modifier.height(24.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.support_section),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.patreon),
                    title = { Text(stringResource(R.string.support_patreon)) },
                    description = { Text(stringResource(R.string.support_patreon_desc)) },
                    onClick = { uriHandler.safeOpenUri(context, ProjectLinks.PATREON) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.ko_fi),
                    title = { Text(stringResource(R.string.support_kofi)) },
                    description = { Text(stringResource(R.string.support_kofi_desc)) },
                    onClick = { uriHandler.safeOpenUri(context, ProjectLinks.KO_FI) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.guns_lol_mark),
                    title = { Text(stringResource(R.string.support_guns)) },
                    description = { Text(stringResource(R.string.support_guns_desc)) },
                    onClick = { uriHandler.safeOpenUri(context, ProjectLinks.GUNS_PROFILE) },
                ),
            ),
        )

        Spacer(Modifier.height(24.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.app_info_section),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.xan_icon_update),
                    title = { Text(stringResource(R.string.installed_date_title)) },
                    description = { Text(installedDate) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.xan_icon_about),
                    title = { Text(stringResource(R.string.release_label)) },
                    description = { Text(BuildConfig.VERSION_NAME) },
                    onClick = { uriHandler.safeOpenUri(context, ProjectLinks.RELEASES) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.info),
                    title = { Text(stringResource(R.string.version_code)) },
                    description = { Text(BuildConfig.VERSION_CODE.toString()) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.github),
                    title = { Text(stringResource(R.string.github_repository)) },
                    description = { Text(stringResource(R.string.view_source_code)) },
                    onClick = { uriHandler.safeOpenUri(context, ProjectLinks.REPOSITORY) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.license_xan),
                    title = { Text(stringResource(R.string.license)) },
                    description = { Text(stringResource(R.string.open_source_license_desc)) },
                    onClick = {
                        uriHandler.safeOpenUri(
                            context,
                            "https://www.gnu.org/licenses/gpl-3.0.html",
                        )
                    },
                ),
            ),
        )

        Spacer(Modifier.height(bottomContentInset + 24.dp))
    }

    TopAppBar(
        colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
        windowInsets = appTopBarWindowInsets(),
        title = { Text(stringResource(R.string.about)) },
        navigationIcon = {
            IconButton(
                onClick = { onBack?.invoke() ?: navController.navigateUp() },
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.xan_icon_back),
                    contentDescription = null,
                )
            }
        },
        scrollBehavior = scrollBehavior,
    )
}
