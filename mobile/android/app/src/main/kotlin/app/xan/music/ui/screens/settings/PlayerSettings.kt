/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.ui.screens.settings

import app.xan.music.ui.utils.appTopBarWindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import app.xan.music.ui.component.GlassSwitchCompat as Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.xan.music.BuildConfig
import app.xan.music.LocalPlayerAwareWindowInsets
import app.xan.music.R
import app.xan.music.constants.AudioNormalizationKey
import app.xan.music.constants.AudioOffload
import app.xan.music.constants.AudioQuality
import app.xan.music.constants.AudioQualityKey
import app.xan.music.constants.VideoSearchFallbackKey
import app.xan.music.constants.AutoDownloadOnLikeKey
import app.xan.music.constants.CrossfadeDurationKey
import app.xan.music.constants.CanvasWifiOnlyKey
import app.xan.music.constants.AutoDjMixingEnabledKey
import app.xan.music.constants.CreativeTransitionsEnabledKey
import app.xan.music.constants.CompactPlayerInTabViewKey
import app.xan.music.constants.HideVolumeBarKey
import app.xan.music.constants.LocalAlbumsByYearKey
import app.xan.music.constants.LocalOnlyModeKey
import app.xan.music.constants.CrossfadeEnabledKey
import app.xan.music.constants.CrossfadeGaplessKey
import app.xan.music.constants.AutoLoadMoreKey
import app.xan.music.constants.AutoSkipNextOnErrorKey
import app.xan.music.constants.DisableLoadMoreWhenRepeatAllKey
import app.xan.music.constants.EnableGoogleCastKey
import app.xan.music.constants.HistoryDuration
import app.xan.music.constants.KeepScreenOn
import app.xan.music.constants.PauseOnMute
import app.xan.music.constants.PersistentQueueKey
import app.xan.music.constants.PersistentShuffleAcrossQueuesKey
import app.xan.music.constants.PreventDuplicateTracksInQueueKey
import app.xan.music.constants.RememberShuffleAndRepeatKey
import app.xan.music.constants.ResumeOnBluetoothConnectKey
import app.xan.music.constants.SeekExtraSeconds
import app.xan.music.constants.ShufflePlaylistFirstKey
import app.xan.music.constants.SimilarContent
import app.xan.music.constants.SponsorBlockEnabledKey
import app.xan.music.constants.SkipSilenceInstantKey
import app.xan.music.constants.SkipSilenceKey
import app.xan.music.ui.component.DefaultDialog
import app.xan.music.ui.component.EnumDialog
import app.xan.music.ui.component.IconButton
import app.xan.music.ui.component.Material3SettingsGroup
import app.xan.music.ui.component.Material3SettingsItem
import app.xan.music.ui.utils.backToMain
import app.xan.music.utils.rememberEnumPreference
import app.xan.music.utils.rememberPreference
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (audioQuality, onAudioQualityChange) = rememberEnumPreference(
        AudioQualityKey,
        defaultValue = AudioQuality.AUTO
    )
    val (canvasWifiOnly, onCanvasWifiOnlyChange) = rememberPreference(
        CanvasWifiOnlyKey,
        defaultValue = false
    )
    val (videoSearchFallback, onVideoSearchFallbackChange) = rememberPreference(
        VideoSearchFallbackKey,
        defaultValue = false
    )
    val (crossfadeEnabled, onCrossfadeEnabledChange) = rememberPreference(
        CrossfadeEnabledKey,
        defaultValue = false
    )
    val (crossfadeDuration, onCrossfadeDurationChange) = rememberPreference(
        CrossfadeDurationKey,
        defaultValue = 5f
    )
    val (crossfadeGapless, onCrossfadeGaplessChange) = rememberPreference(
        CrossfadeGaplessKey,
        defaultValue = true
    )
    val (creativeTransitionsEnabled, onCreativeTransitionsEnabledChange) = rememberPreference(
        CreativeTransitionsEnabledKey,
        defaultValue = false
    )
    val (autoDjMixingEnabled, onAutoDjMixingEnabledChange) = rememberPreference(
        AutoDjMixingEnabledKey,
        defaultValue = false
    )
    val (hideVolumeBar, onHideVolumeBarChange) = rememberPreference(
        HideVolumeBarKey,
        defaultValue = false
    )
    val (compactPlayerInTabView, onCompactPlayerInTabViewChange) = rememberPreference(
        CompactPlayerInTabViewKey,
        defaultValue = false
    )
    val (localOnlyMode, onLocalOnlyModeChange) = rememberPreference(
        LocalOnlyModeKey,
        defaultValue = false
    )
    val (localAlbumsByYear, onLocalAlbumsByYearChange) = rememberPreference(
        LocalAlbumsByYearKey,
        defaultValue = true
    )
    val (persistentQueue, onPersistentQueueChange) = rememberPreference(
        PersistentQueueKey,
        defaultValue = true
    )
    val (skipSilence, onSkipSilenceChange) = rememberPreference(
        SkipSilenceKey,
        defaultValue = false
    )
    val (skipSilenceInstant, onSkipSilenceInstantChange) = rememberPreference(
        SkipSilenceInstantKey,
        defaultValue = false
    )
    val (audioNormalization, onAudioNormalizationChange) = rememberPreference(
        AudioNormalizationKey,
        defaultValue = true
    )

    val (audioOffload, onAudioOffloadChange) = rememberPreference(
        key = AudioOffload,
        defaultValue = false
    )

    val (enableGoogleCast, onEnableGoogleCastChange) = rememberPreference(
        key = EnableGoogleCastKey,
        defaultValue = true
    )

    val (seekExtraSeconds, onSeekExtraSeconds) = rememberPreference(
        SeekExtraSeconds,
        defaultValue = false
    )

    val (autoLoadMore, onAutoLoadMoreChange) = rememberPreference(
        AutoLoadMoreKey,
        defaultValue = true
    )
    val (disableLoadMoreWhenRepeatAll, onDisableLoadMoreWhenRepeatAllChange) = rememberPreference(
        DisableLoadMoreWhenRepeatAllKey,
        defaultValue = false
    )
    val (autoDownloadOnLike, onAutoDownloadOnLikeChange) = rememberPreference(
        AutoDownloadOnLikeKey,
        defaultValue = true
    )
    val (similarContentEnabled, similarContentEnabledChange) = rememberPreference(
        key = SimilarContent,
        defaultValue = true
    )
    val (autoSkipNextOnError, onAutoSkipNextOnErrorChange) = rememberPreference(
        AutoSkipNextOnErrorKey,
        defaultValue = true
    )
    val (sponsorBlockEnabled, onSponsorBlockEnabledChange) = rememberPreference(
        SponsorBlockEnabledKey,
        defaultValue = true
    )
    val (persistentShuffleAcrossQueues, onPersistentShuffleAcrossQueuesChange) = rememberPreference(
        PersistentShuffleAcrossQueuesKey,
        defaultValue = false
    )
    val (rememberShuffleAndRepeat, onRememberShuffleAndRepeatChange) = rememberPreference(
        RememberShuffleAndRepeatKey,
        defaultValue = true
    )
    val (shufflePlaylistFirst, onShufflePlaylistFirstChange) = rememberPreference(
        ShufflePlaylistFirstKey,
        defaultValue = false
    )
    val (preventDuplicateTracksInQueue, onPreventDuplicateTracksInQueueChange) = rememberPreference(
        PreventDuplicateTracksInQueueKey,
        defaultValue = false
    )
    val (pauseOnMute, onPauseOnMuteChange) = rememberPreference(
        PauseOnMute,
        defaultValue = false
    )
    val (resumeOnBluetoothConnect, onResumeOnBluetoothConnectChange) = rememberPreference(
        ResumeOnBluetoothConnectKey,
        defaultValue = true
    )
    val (keepScreenOn, onKeepScreenOnChange) = rememberPreference(
        KeepScreenOn,
        defaultValue = false
    )
    val (historyDuration, onHistoryDurationChange) = rememberPreference(
        HistoryDuration,
        defaultValue = 30f
    )
    var showAudioQualityDialog by remember {
        mutableStateOf(false)
    }

    if (showAudioQualityDialog) {
        EnumDialog(
            onDismiss = { showAudioQualityDialog = false },
            onSelect = {
                onAudioQualityChange(it)
                showAudioQualityDialog = false
            },
            title = stringResource(R.string.audio_quality),
            current = audioQuality,
            values = AudioQuality.values().toList(),
            valueText = {
                when (it) {
                    AudioQuality.AUTO -> stringResource(R.string.audio_quality_auto)
                    AudioQuality.HIGH -> stringResource(R.string.audio_quality_high)
                    AudioQuality.LOW -> stringResource(R.string.audio_quality_low)
                }
            }
        )
    }



    androidx.compose.foundation.lazy.LazyColumn(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
            )
            .padding(horizontal = 16.dp)
    ) {
        item(key = "player_settings_content") {
            Column {
        var showCrossfadeBetaDialog by remember { mutableStateOf(false) }

        if (showCrossfadeBetaDialog) {
            DefaultDialog(
                onDismiss = { showCrossfadeBetaDialog = false },
                title = { Text(stringResource(R.string.crossfade_beta_title)) },
                buttons = {
                    TextButton(onClick = { showCrossfadeBetaDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(onClick = {
                        showCrossfadeBetaDialog = false
                        onCrossfadeEnabledChange(true)
                    }) {
                        Text(stringResource(R.string.enable))
                    }
                }
            ) {
                Text(stringResource(R.string.crossfade_beta_message))
            }
        }

        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top
                )
            )
        )

        Material3SettingsGroup(
            title = stringResource(R.string.player),
            items = buildList {
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.graphic_eq),
                    title = { Text(stringResource(R.string.audio_quality)) },
                    description = {
                        Text(
                            when (audioQuality) {
                                AudioQuality.AUTO -> stringResource(R.string.audio_quality_auto)
                                AudioQuality.HIGH -> stringResource(R.string.audio_quality_high)
                                AudioQuality.LOW -> stringResource(R.string.audio_quality_low)
                            }
                        )
                    },
                    onClick = { showAudioQualityDialog = true }
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.xan_icon_search),
                    title = { Text(stringResource(R.string.video_search_fallback)) },
                    description = { Text(stringResource(R.string.video_search_fallback_desc)) },
                    trailingContent = {
                        Switch(
                            checked = videoSearchFallback,
                            onCheckedChange = onVideoSearchFallbackChange,
                        )
                    },
                    onClick = { onVideoSearchFallbackChange(!videoSearchFallback) }
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.canvas_art),
                    title = { Text(stringResource(R.string.canvas_wifi_only)) },
                    description = { Text(stringResource(R.string.canvas_wifi_only_desc)) },
                    trailingContent = {
                        Switch(checked = canvasWifiOnly, onCheckedChange = onCanvasWifiOnlyChange)
                    },
                    onClick = { onCanvasWifiOnlyChange(!canvasWifiOnly) }
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.fast_forward),
                    title = { Text("SponsorBlock") },
                    description = { Text(stringResource(R.string.sponsorblock_desc)) },
                    trailingContent = {
                        Switch(
                            checked = sponsorBlockEnabled,
                            onCheckedChange = onSponsorBlockEnabledChange,
                        )
                    },
                    onClick = { onSponsorBlockEnabledChange(!sponsorBlockEnabled) }
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.linear_scale),
                    title = { Text(stringResource(R.string.crossfade)) },
                    description = { Text(stringResource(R.string.crossfade_desc)) },
                    showBadge = true,
                    trailingContent = {
                        Switch(
                            checked = crossfadeEnabled,
                            onCheckedChange = {
                                if (!crossfadeEnabled) {
                                    showCrossfadeBetaDialog = true
                                } else {
                                    onCrossfadeEnabledChange(false)
                                }
                            },
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (crossfadeEnabled) R.drawable.check else R.drawable.xan_icon_close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = {
                        if (!crossfadeEnabled) {
                            showCrossfadeBetaDialog = true
                        } else {
                            onCrossfadeEnabledChange(false)
                        }
                    }
                ))
                if (crossfadeEnabled) {
                    add(Material3SettingsItem(
                        icon = painterResource(R.drawable.timer),
                        title = { Text(stringResource(R.string.crossfade_duration)) },
                        description = {
                            Column {
                                Text(pluralStringResource(R.plurals.seconds, crossfadeDuration.toInt(), crossfadeDuration.toInt()))
                                Slider(
                                    value = crossfadeDuration,
                                    onValueChange = onCrossfadeDurationChange,
                                    valueRange = 1f..15f,
                                    steps = 14
                                )
                            }
                        }
                    ))
                    add(Material3SettingsItem(
                        icon = painterResource(R.drawable.album),
                        title = { Text(stringResource(R.string.crossfade_gapless)) },
                        description = { Text(stringResource(R.string.crossfade_gapless_desc)) },
                        trailingContent = {
                            Switch(
                                checked = crossfadeGapless,
                                onCheckedChange = onCrossfadeGaplessChange,
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (crossfadeGapless) R.drawable.check else R.drawable.xan_icon_close
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            )
                        },
                        onClick = { onCrossfadeGaplessChange(!crossfadeGapless) }
                    ))
                }
                // Auto-DJ Mixing needs crossfade on (it beatmatches into the crossfade
                // window); Creative Transitions needs Auto-DJ Mixing on (its effects run
                // inside a DJ-mixed transition). Turning the dependency off cascades down
                // rather than leaving a switch on that can no longer do anything.
                LaunchedEffect(crossfadeEnabled) {
                    if (!crossfadeEnabled && autoDjMixingEnabled) onAutoDjMixingEnabledChange(false)
                }
                LaunchedEffect(autoDjMixingEnabled) {
                    if (!autoDjMixingEnabled && creativeTransitionsEnabled) onCreativeTransitionsEnabledChange(false)
                }
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.xan_icon_equalizer),
                    title = { Text(stringResource(R.string.auto_dj_mixing)) },
                    description = {
                        Text(
                            stringResource(
                                if (crossfadeEnabled) R.string.auto_dj_mixing_desc
                                else R.string.auto_dj_mixing_needs_crossfade
                            )
                        )
                    },
                    enabled = crossfadeEnabled,
                    trailingContent = {
                        Switch(
                            checked = autoDjMixingEnabled,
                            enabled = crossfadeEnabled,
                            onCheckedChange = onAutoDjMixingEnabledChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (autoDjMixingEnabled) R.drawable.check else R.drawable.xan_icon_close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = if (crossfadeEnabled) {
                        { onAutoDjMixingEnabledChange(!autoDjMixingEnabled) }
                    } else null
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.xan_icon_equalizer),
                    title = { Text(stringResource(R.string.creative_transitions)) },
                    description = {
                        Text(
                            stringResource(
                                if (autoDjMixingEnabled) R.string.creative_transitions_desc
                                else R.string.creative_transitions_needs_dj
                            )
                        )
                    },
                    enabled = autoDjMixingEnabled,
                    trailingContent = {
                        Switch(
                            checked = creativeTransitionsEnabled,
                            enabled = autoDjMixingEnabled,
                            onCheckedChange = onCreativeTransitionsEnabledChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (creativeTransitionsEnabled) R.drawable.check else R.drawable.xan_icon_close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = if (autoDjMixingEnabled) {
                        { onCreativeTransitionsEnabledChange(!creativeTransitionsEnabled) }
                    } else null
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.tune),
                    title = { Text(stringResource(R.string.hide_volume_bar)) },
                    description = { Text(stringResource(R.string.hide_volume_bar_desc)) },
                    trailingContent = {
                        Switch(
                            checked = hideVolumeBar,
                            onCheckedChange = onHideVolumeBarChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (hideVolumeBar) R.drawable.check else R.drawable.xan_icon_close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onHideVolumeBarChange(!hideVolumeBar) }
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.crop),
                    title = { Text(stringResource(R.string.compact_player_tab_view)) },
                    description = { Text(stringResource(R.string.compact_player_tab_view_desc)) },
                    trailingContent = {
                        Switch(
                            checked = compactPlayerInTabView,
                            onCheckedChange = onCompactPlayerInTabViewChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (compactPlayerInTabView) R.drawable.check else R.drawable.xan_icon_close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onCompactPlayerInTabViewChange(!compactPlayerInTabView) }
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.local_songs),
                    title = { Text(stringResource(R.string.local_only_mode)) },
                    description = { Text(stringResource(R.string.local_only_mode_desc)) },
                    trailingContent = {
                        Switch(
                            checked = localOnlyMode,
                            onCheckedChange = onLocalOnlyModeChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (localOnlyMode) R.drawable.check else R.drawable.xan_icon_close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onLocalOnlyModeChange(!localOnlyMode) }
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.album),
                    title = { Text(stringResource(R.string.local_albums_by_year)) },
                    description = { Text(stringResource(R.string.local_albums_by_year_desc)) },
                    trailingContent = {
                        Switch(
                            checked = localAlbumsByYear,
                            onCheckedChange = onLocalAlbumsByYearChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (localAlbumsByYear) R.drawable.check else R.drawable.xan_icon_close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onLocalAlbumsByYearChange(!localAlbumsByYear) }
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.local_songs),
                    title = { Text(stringResource(R.string.scan_local_files)) },
                    description = { Text(stringResource(R.string.scan_local_files_desc)) },
                    onClick = { navController.navigate("settings/scan_music") }
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.xan_icon_library),
                    title = { Text(stringResource(R.string.excluded_folders)) },
                    description = { Text(stringResource(R.string.excluded_folders_desc)) },
                    onClick = { navController.navigate("settings/player/local_folders") }
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.history),
                    title = { Text(stringResource(R.string.history_duration)) },
                    description = {
                        Column {
                            Text(historyDuration.roundToInt().toString())
                            Slider(
                                value = historyDuration,
                                onValueChange = onHistoryDurationChange,
                                valueRange = 1f..100f
                            )
                        }
                    }
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.fast_forward),
                    title = { Text(stringResource(R.string.skip_silence)) },
                    description = { Text(stringResource(R.string.skip_silence_desc)) },
                    trailingContent = {
                        Switch(
                            checked = skipSilence,
                            onCheckedChange = onSkipSilenceChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (skipSilence) R.drawable.check else R.drawable.xan_icon_close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onSkipSilenceChange(!skipSilence) }
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.xan_icon_next),
                    title = { Text(stringResource(R.string.skip_silence_instant)) },
                    description = { Text(stringResource(R.string.skip_silence_instant_desc)) },
                    trailingContent = {
                        Switch(
                            checked = skipSilenceInstant,
                            onCheckedChange = { onSkipSilenceInstantChange(it) },
                            enabled = skipSilence,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (skipSilenceInstant) R.drawable.check else R.drawable.xan_icon_close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { if (skipSilence) onSkipSilenceInstantChange(!skipSilenceInstant) }
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.volume_up),
                    title = { Text(stringResource(R.string.audio_normalization)) },
                    trailingContent = {
                        Switch(
                            checked = audioNormalization,
                            onCheckedChange = onAudioNormalizationChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (audioNormalization) R.drawable.check else R.drawable.xan_icon_close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onAudioNormalizationChange(!audioNormalization) }
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.graphic_eq),
                    title = { Text(stringResource(R.string.audio_offload)) },
                    description = {
                        Text(
                            if (crossfadeEnabled) stringResource(R.string.audio_offload_disabled_by_crossfade)
                            else stringResource(R.string.audio_offload_description)
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = if (crossfadeEnabled) false else audioOffload,
                            onCheckedChange = onAudioOffloadChange,
                            enabled = !crossfadeEnabled,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (!crossfadeEnabled && audioOffload) R.drawable.check else R.drawable.xan_icon_close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { if (!crossfadeEnabled) onAudioOffloadChange(!audioOffload) }
                ))
                // Cast is bundled in the single all-features release.
                if (BuildConfig.CAST_AVAILABLE) {
                    add(Material3SettingsItem(
                        icon = painterResource(R.drawable.cast),
                        title = { Text(stringResource(R.string.google_cast)) },
                        description = { Text(stringResource(R.string.google_cast_description)) },
                        trailingContent = {
                            Switch(
                                checked = enableGoogleCast,
                                onCheckedChange = onEnableGoogleCastChange,
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (enableGoogleCast) R.drawable.check else R.drawable.xan_icon_close
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            )
                        },
                        onClick = { onEnableGoogleCastChange(!enableGoogleCast) }
                    ))
                }
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.arrow_forward),
                    title = { Text(stringResource(R.string.seek_seconds_addup)) },
                    description = { Text(stringResource(R.string.seek_seconds_addup_description)) },
                    trailingContent = {
                        Switch(
                            checked = seekExtraSeconds,
                            onCheckedChange = onSeekExtraSeconds,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (seekExtraSeconds) R.drawable.check else R.drawable.xan_icon_close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onSeekExtraSeconds(!seekExtraSeconds) }
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.xan_equalizer),
                    title = { Text(stringResource(R.string.xan_equalizer)) },
                    description = { Text(stringResource(R.string.xan_equalizer_desc)) },
                    onClick = { navController.navigate("settings/equalizer") }
                ))
            }
        )

        Spacer(modifier = Modifier.height(27.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.queue),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.xan_icon_queue),
                    title = { Text(stringResource(R.string.persistent_queue)) },
                    description = { Text(stringResource(R.string.persistent_queue_desc)) },
                    trailingContent = {
                        Switch(
                            checked = persistentQueue,
                            onCheckedChange = onPersistentQueueChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (persistentQueue) R.drawable.check else R.drawable.xan_icon_close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onPersistentQueueChange(!persistentQueue) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.playlist_add),
                    title = { Text(stringResource(R.string.auto_load_more)) },
                    description = { Text(stringResource(R.string.auto_load_more_desc)) },
                    trailingContent = {
                        Switch(
                            checked = autoLoadMore,
                            onCheckedChange = onAutoLoadMoreChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (autoLoadMore) R.drawable.check else R.drawable.xan_icon_close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onAutoLoadMoreChange(!autoLoadMore) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.xan_icon_repeat),
                    title = { Text(stringResource(R.string.disable_load_more_when_repeat_all)) },
                    description = { Text(stringResource(R.string.disable_load_more_when_repeat_all_desc)) },
                    trailingContent = {
                        Switch(
                            checked = disableLoadMoreWhenRepeatAll,
                            onCheckedChange = onDisableLoadMoreWhenRepeatAllChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (disableLoadMoreWhenRepeatAll) R.drawable.check else R.drawable.xan_icon_close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onDisableLoadMoreWhenRepeatAllChange(!disableLoadMoreWhenRepeatAll) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.xan_icon_download),
                    title = { Text(stringResource(R.string.auto_download_on_like)) },
                    description = { Text(stringResource(R.string.auto_download_on_like_desc)) },
                    trailingContent = {
                        Switch(
                            checked = autoDownloadOnLike,
                            onCheckedChange = onAutoDownloadOnLikeChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (autoDownloadOnLike) R.drawable.check else R.drawable.xan_icon_close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onAutoDownloadOnLikeChange(!autoDownloadOnLike) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.similar),
                    title = { Text(stringResource(R.string.enable_similar_content)) },
                    description = { Text(stringResource(R.string.similar_content_desc)) },
                    trailingContent = {
                        Switch(
                            checked = similarContentEnabled,
                            onCheckedChange = similarContentEnabledChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (similarContentEnabled) R.drawable.check else R.drawable.xan_icon_close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { similarContentEnabledChange(!similarContentEnabled) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.xan_icon_shuffle),
                    title = { Text(stringResource(R.string.persistent_shuffle_title)) },
                    description = { Text(stringResource(R.string.persistent_shuffle_desc)) },
                    trailingContent = {
                        Switch(
                            checked = persistentShuffleAcrossQueues,
                            onCheckedChange = onPersistentShuffleAcrossQueuesChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (persistentShuffleAcrossQueues) R.drawable.check else R.drawable.xan_icon_close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onPersistentShuffleAcrossQueuesChange(!persistentShuffleAcrossQueues) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.xan_icon_shuffle),
                    title = { Text(stringResource(R.string.remember_shuffle_and_repeat)) },
                    description = { Text(stringResource(R.string.remember_shuffle_and_repeat_desc)) },
                    trailingContent = {
                        Switch(
                            checked = rememberShuffleAndRepeat,
                            onCheckedChange = onRememberShuffleAndRepeatChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (rememberShuffleAndRepeat) R.drawable.check else R.drawable.xan_icon_close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onRememberShuffleAndRepeatChange(!rememberShuffleAndRepeat) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.xan_icon_shuffle),
                    title = { Text(stringResource(R.string.shuffle_playlist_first)) },
                    description = { Text(stringResource(R.string.shuffle_playlist_first_desc)) },
                    trailingContent = {
                        Switch(
                            checked = shufflePlaylistFirst,
                            onCheckedChange = onShufflePlaylistFirstChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (shufflePlaylistFirst) R.drawable.check else R.drawable.xan_icon_close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onShufflePlaylistFirstChange(!shufflePlaylistFirst) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.xan_icon_queue),
                    title = { Text(stringResource(R.string.prevent_duplicate_tracks_in_queue)) },
                    description = { Text(stringResource(R.string.prevent_duplicate_tracks_in_queue_desc)) },
                    trailingContent = {
                        Switch(
                            checked = preventDuplicateTracksInQueue,
                            onCheckedChange = onPreventDuplicateTracksInQueueChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (preventDuplicateTracksInQueue) R.drawable.check else R.drawable.xan_icon_close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onPreventDuplicateTracksInQueueChange(!preventDuplicateTracksInQueue) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.xan_icon_next),
                    title = { Text(stringResource(R.string.auto_skip_next_on_error)) },
                    description = { Text(stringResource(R.string.auto_skip_next_on_error_desc)) },
                    trailingContent = {
                        Switch(
                            checked = autoSkipNextOnError,
                            onCheckedChange = onAutoSkipNextOnErrorChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (autoSkipNextOnError) R.drawable.check else R.drawable.xan_icon_close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onAutoSkipNextOnErrorChange(!autoSkipNextOnError) }
                )
            )
        )

        Spacer(modifier = Modifier.height(27.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.misc),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.volume_off_pause),
                    title = { Text(stringResource(R.string.pause_music_when_media_is_muted)) },
                    trailingContent = {
                        Switch(
                            checked = pauseOnMute,
                            onCheckedChange = onPauseOnMuteChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (pauseOnMute) R.drawable.check else R.drawable.xan_icon_close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onPauseOnMuteChange(!pauseOnMute) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.bluetooth),
                    title = { Text(stringResource(R.string.resume_on_bluetooth_connect)) },
                    trailingContent = {
                        Switch(
                            checked = resumeOnBluetoothConnect,
                            onCheckedChange = onResumeOnBluetoothConnectChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (resumeOnBluetoothConnect) R.drawable.check else R.drawable.xan_icon_close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onResumeOnBluetoothConnectChange(!resumeOnBluetoothConnect) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.screenshot),
                    title = { Text(stringResource(R.string.keep_screen_on_when_player_is_expanded)) },
                    trailingContent = {
                        Switch(
                            checked = keepScreenOn,
                            onCheckedChange = onKeepScreenOnChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (keepScreenOn) R.drawable.check else R.drawable.xan_icon_close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onKeepScreenOnChange(!keepScreenOn) }
                )
            )
        )
        Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    TopAppBar(
            windowInsets = appTopBarWindowInsets(),
        title = { Text(stringResource(R.string.player_and_audio)) },
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
        }
    )
}
