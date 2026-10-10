package app.xan.music.ui.screens
import app.xan.music.ui.utils.xanLogoPainter

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import app.xan.music.ui.utils.bounceClick
import app.xan.music.ui.utils.combinedBounceClick
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

import coil3.compose.AsyncImage
import com.music.innertube.utils.parseCookieString
import app.xan.music.R
import app.xan.music.constants.AccountEmailKey
import app.xan.music.constants.InnerTubeCookieKey
import app.xan.music.constants.UseLoginForBrowse
import app.xan.music.constants.YtmSyncKey
import app.xan.music.BuildConfig
import app.xan.music.utils.rememberPreference
import app.xan.music.viewmodels.HomeViewModel
import kotlinx.coroutines.flow.map

@Composable
fun SettingDialoge(
    onDismissRequest: () -> Unit,
    onNavigate: (String) -> Unit,
    homeViewModel: HomeViewModel
) {
    val uriHandler = LocalUriHandler.current
    val (innerTubeCookie, _) = rememberPreference(InnerTubeCookieKey, "")
    val isLoggedIn = remember(innerTubeCookie) {
        innerTubeCookie.isNotEmpty() && "SAPISID" in parseCookieString(innerTubeCookie)
    }

    val (accountEmail, _) = rememberPreference(AccountEmailKey, "")
    val accountName by homeViewModel.accountName.collectAsState()
    val accountImageUrl by homeViewModel.accountImageUrl.collectAsState()

    val (useLoginForBrowse, onUseLoginForBrowseChange) = rememberPreference(UseLoginForBrowse, true)
    val (ytmSync, onYtmSyncChange) = rememberPreference(YtmSyncKey, true)

    val likedSongsFlow = remember(homeViewModel.database) {
        homeViewModel.database.likedSongsCount()
    }
    val likedSongsCount by likedSongsFlow.collectAsState(initial = 0)

    val playlistCountFlow = remember(homeViewModel.database) {
        homeViewModel.database.playlistsByNameAsc().map { it.size }
    }
    val playlistCount by playlistCountFlow.collectAsState(initial = 0)

    val albumCountFlow = remember(homeViewModel.database) {
        homeViewModel.database.albumsByNameAsc().map { it.size }
    }
    val albumCount by albumCountFlow.collectAsState(initial = 0)

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val primaryColor = MaterialTheme.colorScheme.surface
        val onPrimaryColor = MaterialTheme.colorScheme.onSurface
        val secondaryColor = MaterialTheme.colorScheme.secondaryContainer
        val onSecondaryColor = MaterialTheme.colorScheme.onSecondaryContainer

        Surface(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = primaryColor,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
            ) {
                // AppBar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, top = 10.dp, end = 4.dp, bottom = 10.dp)
                ) {
                    Image(
                        painter = xanLogoPainter(),
                        contentDescription = stringResource(R.string.app_icon_description),
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                    )
                    
                    Text(
                        modifier = Modifier.wrapContentWidth(),
                        text = "xan",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        color = onPrimaryColor,
                        textAlign = TextAlign.Center
                    )

                    Image(
                        painter = painterResource(id = R.drawable.xan_icon_close),
                        contentDescription = "Cancel",
                        modifier = Modifier
                            .size(24.dp)
                            .bounceClick { onDismissRequest() },
                        colorFilter = ColorFilter.tint(onPrimaryColor)
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(
                            RoundedCornerShape(
                                topStart = 25.dp,
                                topEnd = 25.dp,
                                bottomStart = 2.dp,
                                bottomEnd = 2.dp
                            )
                        )
                        .background(color = secondaryColor)
                        .bounceClick(enabled = isLoggedIn) {
                            onNavigate("account")
                        }
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(15.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(15.dp, Alignment.Start)
                    ) {
                        if (isLoggedIn && !accountImageUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = accountImageUrl,
                                contentDescription = stringResource(R.string.profile_photo_description),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.xan_icon_account),
                                contentDescription = stringResource(R.string.account_manager_description),
                                tint = onSecondaryColor,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                            )
                        }
                        Column(
                            verticalArrangement = Arrangement.Top,
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = if (isLoggedIn) accountName else stringResource(R.string.anonymous),
                                fontWeight = FontWeight.Normal,
                                color = onSecondaryColor,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (isLoggedIn) {
                                    accountEmail.ifEmpty { stringResource(R.string.logged_in) }
                                } else {
                                    stringResource(R.string.not_signed_in)
                                },
                                fontWeight = FontWeight.Light,
                                color = onSecondaryColor,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    
                    OutlinedButton(
                        onClick = {
                            if (isLoggedIn) {
                                onNavigate("settings/account")
                            } else {
                                onNavigate("login")
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, onSecondaryColor.copy(alpha = 0.4f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = onSecondaryColor
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (isLoggedIn) stringResource(R.string.manage_account) else stringResource(R.string.login),
                            color = onSecondaryColor,
                            fontWeight = FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Options List
                Column(
                    modifier = Modifier
                        .clip(
                            RoundedCornerShape(
                                topStart = 2.dp,
                                topEnd = 2.dp,
                                bottomStart = 25.dp,
                                bottomEnd = 25.dp
                            )
                        )
                        .background(color = secondaryColor)
                ) {
                    OptionItem(
                        option = Option(stringResource(R.string.playlists), R.drawable.library_music_outlined),
                        tintColor = onPrimaryColor,
                        textColor = onSecondaryColor,
                        trailingText = playlistCount.toString()
                    )
                    OptionItem(
                        option = Option(stringResource(R.string.albums), R.drawable.album),
                        tintColor = onPrimaryColor,
                        textColor = onSecondaryColor,
                        trailingText = albumCount.toString()
                    )
                    OptionItem(
                        option = Option(stringResource(R.string.liked_songs), R.drawable.favorite_border),
                        tintColor = onPrimaryColor,
                        textColor = onSecondaryColor,
                        trailingText = likedSongsCount.toString()
                    )

                    if (isLoggedIn) {
                        SwitchOptionItem(
                            title = stringResource(R.string.use_account_for_browsing),
                            icon = R.drawable.add_circle,
                            checked = useLoginForBrowse,
                            onCheckedChange = {
                                com.music.innertube.YouTube.useLoginForBrowse = it
                                onUseLoginForBrowseChange(it)
                            },
                            tintColor = onPrimaryColor,
                            textColor = onSecondaryColor
                        )
                        SwitchOptionItem(
                            title = stringResource(R.string.youtube_music_sync),
                            icon = R.drawable.cached,
                            checked = ytmSync,
                            onCheckedChange = onYtmSyncChange,
                            tintColor = onPrimaryColor,
                            textColor = onSecondaryColor
                        )
                    }
                }

                // Extra Options List
                val extraOptions = listOf(
                    Option(stringResource(R.string.settings), R.drawable.xan_icon_settings) to "settings",
                    Option(stringResource(R.string.about), R.drawable.xan_icon_about) to "settings/about"
                )
                extraOptions.forEach { (option, destination) ->
                    OptionItem(
                        option = option,
                        tintColor = onPrimaryColor,
                        textColor = onPrimaryColor,
                        trailingText = if (destination == "settings/about") BuildConfig.VERSION_NAME else null,
                        onClick = {
                            onNavigate(destination)
                        }
                    )
                }

                // Footer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally)
                ) {
                    TextButton(onClick = { uriHandler.openUri("https://guns.lol/bxane") }) {
                        Text(
                            text = stringResource(R.string.privacy_policy),
                            fontWeight = FontWeight.Light,
                            color = onPrimaryColor,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                
                    Text(text = "•", color = onPrimaryColor)
                    TextButton(onClick = { uriHandler.openUri("https://guns.lol/bxane") }) {
                        Text(
                            text = stringResource(R.string.terms_of_service),
                            fontWeight = FontWeight.Light,
                            color = onPrimaryColor,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private data class Option(
    val title: String,
    val icon: Int
)

@Composable
private fun OptionItem(
    option: Option,
    tintColor: Color,
    textColor: Color,
    trailingText: String? = null,
    onClick: (() -> Unit)? = null
) {
    val modifier = Modifier
        .fillMaxWidth()
        .then(if (onClick != null) Modifier.bounceClick { onClick() } else Modifier)
        .padding(12.dp)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start)
    ) {
        Icon(
            painter = painterResource(id = option.icon),
            contentDescription = option.title,
            tint = tintColor,
            modifier = Modifier
                .padding(horizontal = 10.dp)
                .size(23.dp)
        )
        Text(
            modifier = Modifier.weight(1f),
            text = option.title,
            color = textColor,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (trailingText != null) {
            Text(
                text = trailingText,
                color = textColor.copy(alpha = 0.6f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(end = 10.dp)
            )
        }
    }
}

@Composable
private fun SwitchOptionItem(
    title: String,
    icon: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tintColor: Color,
    textColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .bounceClick { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start)
    ) {
        Icon(
            painter = painterResource(id = icon),
            contentDescription = title,
            tint = tintColor,
            modifier = Modifier
                .padding(horizontal = 10.dp)
                .size(23.dp)
        )
        Text(
            modifier = Modifier.weight(1f),
            text = title,
            color = textColor,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            thumbContent = {
                Icon(
                    painter = painterResource(
                        id = if (checked) R.drawable.check else R.drawable.xan_icon_close
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize)
                )
            },
            modifier = Modifier
                .padding(end = 2.dp)
                .scale(0.8f)
        )
    }
}


