package app.xan.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.xan.music.LocalPlayerAwareWindowInsets
import app.xan.music.R
import app.xan.music.constants.MagnetOnLeftKey
import app.xan.music.constants.MagnetSemicircleKey
import app.xan.music.constants.MagnetShowIconNamesKey
import app.xan.music.constants.MagnetSpringAnimationsKey
import app.xan.music.ui.component.GlassSwitchCompat as Switch
import app.xan.music.ui.component.IconButton
import app.xan.music.ui.component.Material3SettingsGroup
import app.xan.music.ui.component.Material3SettingsItem
import app.xan.music.ui.utils.appTopBarWindowInsets
import app.xan.music.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MagnetSettingsScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (onLeft, onOnLeftChange) = rememberPreference(MagnetOnLeftKey, defaultValue = false)
    val (semicircle, onSemicircleChange) = rememberPreference(MagnetSemicircleKey, defaultValue = true)
    val (showNames, onShowNamesChange) = rememberPreference(MagnetShowIconNamesKey, defaultValue = false)
    val (springAnimations, onSpringAnimationsChange) = rememberPreference(MagnetSpringAnimationsKey, defaultValue = true)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets = appTopBarWindowInsets(),
            title = { Text(stringResource(R.string.magnet_settings)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }, onLongClick = {}) {
                    Icon(
                        painter = painterResource(R.drawable.xan_icon_back),
                        contentDescription = stringResource(R.string.cd_back),
                    )
                }
            },
            scrollBehavior = scrollBehavior,
        )

        Column(
            Modifier
                .weight(1f)
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Material3SettingsGroup(
                title = stringResource(R.string.navigation_style),
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.xan_icon_modules),
                        title = { Text(stringResource(R.string.magnet_position)) },
                        description = {
                            Text(
                                stringResource(if (onLeft) R.string.magnet_bottom_left else R.string.magnet_bottom_right) +
                                    " · " + stringResource(R.string.magnet_opens_toward_center),
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = onLeft,
                                onCheckedChange = onOnLeftChange,
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(if (onLeft) R.drawable.check else R.drawable.xan_icon_close),
                                        contentDescription = null,
                                        modifier = Modifier.padding(3.dp),
                                    )
                                },
                            )
                        },
                        onClick = { onOnLeftChange(!onLeft) },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.xan_icon_modules),
                        title = { Text(stringResource(R.string.magnet_straight_layout)) },
                        description = { Text(stringResource(R.string.magnet_straight_layout_desc)) },
                        trailingContent = {
                            Switch(
                                checked = !semicircle,
                                onCheckedChange = { onSemicircleChange(!it) },
                            )
                        },
                        onClick = { onSemicircleChange(!semicircle) },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.xan_icon_modules),
                        title = { Text(stringResource(R.string.magnet_show_icon_names)) },
                        description = { Text(stringResource(R.string.magnet_show_icon_names_desc)) },
                        trailingContent = {
                            Switch(
                                checked = showNames,
                                onCheckedChange = onShowNamesChange,
                            )
                        },
                        onClick = { onShowNamesChange(!showNames) },
                    ),
                ),
            )

            Spacer(Modifier.height(24.dp))
            Material3SettingsGroup(
                title = stringResource(R.string.magnet_spring_animations),
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.xan_icon_appearance),
                        title = { Text(stringResource(R.string.magnet_spring_animations)) },
                        description = {
                            Text(
                                stringResource(
                                    if (springAnimations) R.string.magnet_spring_animations_desc
                                    else R.string.magnet_animation_disabled,
                                ),
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = springAnimations,
                                onCheckedChange = onSpringAnimationsChange,
                            )
                        },
                        onClick = { onSpringAnimationsChange(!springAnimations) },
                    ),
                ),
            )
            Spacer(Modifier.height(48.dp))
        }
    }
}
