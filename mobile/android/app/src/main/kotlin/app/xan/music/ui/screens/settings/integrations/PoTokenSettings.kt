/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.ui.screens.settings.integrations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.xan.music.LocalPlayerAwareWindowInsets
import app.xan.music.R
import app.xan.music.constants.PoTokenApiKey
import app.xan.music.ui.component.IconButton
import app.xan.music.ui.utils.appTopBarWindowInsets
import app.xan.music.ui.utils.backToMain
import app.xan.music.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoTokenSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    var poTokenApiKey by rememberPreference(PoTokenApiKey, "")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)
            )
        )
        Text(
            text = stringResource(R.string.potoken_api_key_title),
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 16.dp),
        )
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = stringResource(R.string.potoken_api_key_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = poTokenApiKey,
                onValueChange = { poTokenApiKey = it },
                label = { Text(stringResource(R.string.potoken_api_key_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = { poTokenApiKey = "" },
                enabled = poTokenApiKey.isNotBlank(),
            ) {
                Text(stringResource(R.string.potoken_api_key_clear))
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    TopAppBar(
        title = {},
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                androidx.compose.material3.Icon(
                    painter = painterResource(R.drawable.xan_icon_back),
                    contentDescription = null,
                )
            }
        },
        windowInsets = appTopBarWindowInsets(),
        scrollBehavior = scrollBehavior,
    )
}
