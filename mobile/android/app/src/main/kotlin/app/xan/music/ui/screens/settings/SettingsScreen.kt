/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.ui.screens.settings

import app.xan.music.R
import app.xan.music.ui.utils.xanLogoPainter
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.xan.music.BuildConfig
import app.xan.music.LocalPlayerAwareWindowInsets
import app.xan.music.ui.component.IconButton
import app.xan.music.ui.component.Material3SettingsGroup
import app.xan.music.ui.component.Material3SettingsItem
import app.xan.music.ui.theme.AppleTokens
import app.xan.music.ui.utils.appTopBarWindowInsets
import app.xan.music.ui.utils.backToMain
import app.xan.music.platform.updater.getUpdateAvailableState
import app.xan.music.ui.screens.Screens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isUpdateAvailable = getUpdateAvailableState(context) &&
        app.xan.music.platform.updater.getAutoUpdateCheckSetting(context)

    var searchQuery by rememberSaveable { mutableStateOf("") }
    val searchLower = searchQuery.trim().lowercase()

    androidx.compose.foundation.lazy.LazyColumn(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
    ) {
        item(key = "top_spacer") {
            Spacer(
                Modifier.windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)
                )
            )
        }

        // Large title (iOS style)
        item(key = "title") {
            Text(
                text = stringResource(R.string.settings),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp)
            )
        }

        // Search field — filters the settings catalog and jumps to the matching screen.
        item(key = "search_field") {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.search)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.xan_icon_search),
                        contentDescription = stringResource(R.string.search)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                painter = painterResource(R.drawable.xan_icon_close),
                                contentDescription = null
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
            )
        }

        if (searchLower.isEmpty()) {
        // Section: General
        item(key = "general_header") { SettingsSectionHeader(stringResource(R.string.general).uppercase()) }
        item(key = "general_section") {
            SettingsSection {
                SettingsNavItem(
                    icon = if (isUpdateAvailable) xanLogoPainter() else painterResource(R.drawable.xan_icon_update),
                    iconTint = if (isUpdateAvailable) Color.Unspecified else Color(0xFF007AFF),
                    title = stringResource(R.string.system_update),
                    badge = if (isUpdateAvailable) stringResource(R.string.update_available) else null,
                    onClick = { navController.navigate("settings/update") },
                )
                SettingsDivider()
                SettingsNavItem(
                    icon = painterResource(R.drawable.xan_icon_appearance),
                    iconTint = Color(0xFFAF52DE),
                    title = stringResource(R.string.appearance),
                    onClick = { navController.navigate("settings/appearance") },
                )
                SettingsDivider()
                SettingsNavItem(
                    icon = painterResource(R.drawable.xan_icon_play),
                    iconTint = Color(0xFFFF375F),
                    title = stringResource(R.string.player_and_audio),
                    onClick = { navController.navigate("settings/player") },
                )
            }
        }

        item(key = "spacer_1") { Spacer(Modifier.height(24.dp)) }

        // Section: Account
        item(key = "account_header") { SettingsSectionHeader(stringResource(R.string.account).uppercase()) }
        item(key = "account_section") {
            SettingsSection {
                SettingsNavItem(
                    icon = painterResource(R.drawable.xan_icon_account),
                    iconTint = Color(0xFF34C759),
                    title = stringResource(R.string.account),
                    onClick = { navController.navigate("settings/account") },
                )
            }
        }

        item(key = "listening_header") {
            SettingsSectionHeader(stringResource(R.string.settings_listening).uppercase())
        }
        item(key = "listening_section") {
            SettingsSection {
                SettingsNavItem(
                    icon = painterResource(R.drawable.diversity_listen_together),
                    iconTint = Color(0xFFAF52DE),
                    title = stringResource(R.string.listen_together),
                    onClick = { navController.navigate("settings/listen_together") },
                )
            }
        }

        item(key = "spacer_activity") { Spacer(Modifier.height(24.dp)) }

        // Section: Activity — history/stats moved here from the top bar, which now
        // only shows the logo and this settings/profile pill.
        item(key = "activity_header") { SettingsSectionHeader(stringResource(R.string.settings_activity).uppercase()) }
        item(key = "activity_section") {
            SettingsSection {
                SettingsNavItem(
                    icon = painterResource(R.drawable.xan_icon_history),
                    iconTint = Color(0xFF32ADE6),
                    title = stringResource(R.string.history),
                    onClick = { navController.navigate("history") },
                )
                SettingsDivider()
                SettingsNavItem(
                    icon = painterResource(R.drawable.xan_icon_stats),
                    iconTint = Color(0xFFFFCC00),
                    title = stringResource(R.string.stats),
                    onClick = { navController.navigate("stats") },
                )
            }
        }

        item(key = "spacer_2") { Spacer(Modifier.height(24.dp)) }

        // Section: Content
        item(key = "content_header") { SettingsSectionHeader(stringResource(R.string.content).uppercase()) }
        item(key = "content_section") {
            SettingsSection {
                SettingsNavItem(
                    icon = painterResource(R.drawable.xan_icon_content),
                    iconTint = Color(0xFF007AFF),
                    title = stringResource(R.string.content),
                    onClick = { navController.navigate("settings/content") },
                )
            }
        }

        item(key = "spacer_3") { Spacer(Modifier.height(24.dp)) }

        // Section: Data & Privacy
        item(key = "privacy_header") { SettingsSectionHeader(stringResource(R.string.settings_data_privacy).uppercase()) }
        item(key = "privacy_section") {
            SettingsSection {
                SettingsNavItem(
                    icon = painterResource(R.drawable.xan_icon_privacy),
                    iconTint = Color(0xFF007AFF),
                    title = stringResource(R.string.privacy),
                    onClick = { navController.navigate("settings/privacy") },
                )
                SettingsDivider()
                SettingsNavItem(
                    icon = painterResource(R.drawable.xan_icon_storage),
                    iconTint = Color(0xFF8E8E93),
                    title = stringResource(R.string.storage),
                    onClick = { navController.navigate("settings/storage") },
                )
                SettingsDivider()
                SettingsNavItem(
                    icon = painterResource(R.drawable.xan_icon_backup),
                    iconTint = Color(0xFF34C759),
                    title = stringResource(R.string.backup_restore),
                    onClick = { navController.navigate("settings/backup_restore") },
                )
            }
        }

        item(key = "spacer_4") { Spacer(Modifier.height(24.dp)) }

        // Section: About
        item(key = "about_section") {
            SettingsSection {
                SettingsNavItem(
                    icon = painterResource(R.drawable.xan_icon_about),
                    iconTint = MaterialTheme.colorScheme.onSurface,
                    title = stringResource(R.string.about),
                    onClick = { navController.navigate("settings/about") },
                )
            }
        }

        item(key = "bottom_spacer") {
            Spacer(
                Modifier
                    .height(50.dp)
                    .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom))
            )
        }
        } else {
            item(key = "search_results") {
                val results = getAllSearchableSettings()
                    .filter {
                        it.title.lowercase().contains(searchLower) ||
                            it.description?.lowercase()?.contains(searchLower) == true
                    }
                if (results.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_no_results, searchQuery),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 32.dp)
                    )
                } else {
                    Material3SettingsGroup(
                        items = results.map { setting ->
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.xan_icon_search),
                                title = { Text(setting.title) },
                                description = { Text(localizedSettingsCategory(setting.category)) },
                                onClick = { navController.navigate(setting.route) }
                            )
                        }
                    )
                }
            }
        }
    }

    TopAppBar(
        title = {},
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    painterResource(R.drawable.xan_icon_back),
                    contentDescription = null
                )
            }
        },
        windowInsets = appTopBarWindowInsets(),
    )
}

@Composable
private fun localizedSettingsCategory(category: String): String = when (category) {
    "About" -> stringResource(R.string.about)
    "Account" -> stringResource(R.string.account)
    "General" -> stringResource(R.string.general)
    "Appearance" -> stringResource(R.string.appearance)
    "Player and audio" -> stringResource(R.string.player_and_audio)
    "Content" -> stringResource(R.string.content)
    "Privacy" -> stringResource(R.string.privacy)
    "Storage" -> stringResource(R.string.storage)
    "Backup & Restore" -> stringResource(R.string.backup_restore)
    "Integrations" -> stringResource(R.string.integrations)
    "Listening" -> stringResource(R.string.settings_listening)
    "Activity" -> stringResource(R.string.settings_activity)
    else -> category
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = FontWeight.SemiBold,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = 20.dp,
            top = 8.dp,
            bottom = 8.dp,
        ),
    )
}

@Composable
private fun SettingsSection(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(AppleTokens.CardCorner))
            // Row content (title/icon) already reads MaterialTheme.colorScheme —
            // a fixed-dark AppleTokens.Card background went black-on-black in
            // light theme (same root cause as Material3SettingsGroup).
            .background(MaterialTheme.colorScheme.surfaceContainer),
        content = content,
    )
}

@Composable
private fun SettingsNavItem(
    icon: Painter,
    iconTint: Color,
    title: String,
    badge: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon in rounded square
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(iconTint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp),
            )
        }

        Spacer(Modifier.width(16.dp))

        // Title
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )

        // Badge or chevron
        if (badge != null) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(8.dp))
        }

        Icon(
            painter = painterResource(R.drawable.chevron_right_px),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 64.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = 0.5.dp,
    )
}
