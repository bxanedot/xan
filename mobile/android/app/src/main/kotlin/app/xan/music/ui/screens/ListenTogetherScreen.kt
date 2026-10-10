/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.xan.music.LocalListenTogetherManager
import app.xan.music.LocalPlayerAwareWindowInsets
import app.xan.music.R
import app.xan.music.constants.AppBarHeight
import app.xan.music.constants.ListenTogetherAutoAddSuggestionsKey
import app.xan.music.constants.ListenTogetherAutoApprovalKey
import app.xan.music.constants.ListenTogetherCloudflareUrlKey
import app.xan.music.constants.ListenTogetherHuggingFaceUrlKey
import app.xan.music.constants.ListenTogetherProviderKey
import app.xan.music.constants.ListenTogetherServerUrlKey
import app.xan.music.constants.ListenTogetherSmartResyncKey
import app.xan.music.constants.ListenTogetherSyncVolumeKey
import app.xan.music.constants.ListenTogetherUsernameKey
import app.xan.music.listentogether.ConnectionState
import app.xan.music.listentogether.ListenTogetherEvent
import app.xan.music.listentogether.ListenTogetherProvider
import app.xan.music.listentogether.ListenTogetherServers
import app.xan.music.listentogether.MAX_ROOM_CODE_LENGTH
import app.xan.music.listentogether.isValidRoomCode
import app.xan.music.listentogether.normalizeRoomCode
import app.xan.music.ui.component.IconButton
import app.xan.music.ui.utils.appTopBarWindowInsets
import app.xan.music.ui.utils.backToMain
import app.xan.music.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenTogetherScreen(
    navController: NavController,
    showTopBar: Boolean = false,
    showSettings: Boolean = false,
) {
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val direction = LocalLayoutDirection.current

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(
                    PaddingValues(
                        start = insets.calculateStartPadding(direction) + 16.dp,
                        end = insets.calculateEndPadding(direction) + 16.dp,
                        top = insets.calculateTopPadding() + 16.dp,
                        bottom = insets.calculateBottomPadding() + AppBarHeight + 16.dp,
                    )
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ListenTogetherContent(Modifier.widthIn(max = 520.dp).fillMaxWidth())
            if (showSettings) {
                Spacer(Modifier.size(20.dp))
                ListenTogetherSettingsControls(Modifier.widthIn(max = 520.dp).fillMaxWidth())
            }
        }
        if (showTopBar) {
            TopAppBar(
                windowInsets = appTopBarWindowInsets(),
                title = { Text(stringResource(R.string.listen_together)) },
                navigationIcon = {
                    IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                        Icon(painterResource(R.drawable.xan_icon_back), contentDescription = null)
                    }
                },
            )
        }
    }
}

/** The same code-only workflow is used by every Android entry point. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListenTogetherContent(modifier: Modifier = Modifier) {
    val manager = LocalListenTogetherManager.current
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    if (manager == null) {
        Text(stringResource(R.string.listen_together_unavailable), modifier = modifier)
        return
    }

    val room by manager.roomState.collectAsState()
    val userId by manager.userId.collectAsState()
    val connection by manager.connectionState.collectAsState()
    val pendingJoinRequests by manager.pendingJoinRequests.collectAsState()
    var codeInput by rememberSaveable { mutableStateOf("") }
    var pendingAction by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(manager) {
        manager.events.collect { event ->
            when (event) {
                is ListenTogetherEvent.RoomCreated, is ListenTogetherEvent.JoinApproved,
                is ListenTogetherEvent.Reconnected -> {
                    pendingAction = null
                    error = null
                    codeInput = ""
                }
                is ListenTogetherEvent.ConnectionError -> { pendingAction = null; error = event.error }
                is ListenTogetherEvent.ServerError -> { pendingAction = null; error = event.message }
                is ListenTogetherEvent.JoinRejected -> { pendingAction = null; error = event.reason }
                is ListenTogetherEvent.RoomClosed -> { pendingAction = null; error = event.reason }
                is ListenTogetherEvent.Kicked -> { pendingAction = null; error = event.reason }
                else -> Unit
            }
        }
    }
    LaunchedEffect(connection) {
        if (connection == ConnectionState.ERROR) pendingAction = null
    }

    val busy = pendingAction != null || connection == ConnectionState.CONNECTING || connection == ConnectionState.RECONNECTING
    val join = {
        if (!busy && isValidRoomCode(codeInput)) {
            keyboard?.hide()
            error = null
            pendingAction = "join"
            manager.joinRoom(codeInput)
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        val currentRoom = room
        if (currentRoom == null) {
            Text(
                stringResource(R.string.listen_together_simple_description),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = {
                            keyboard?.hide()
                            error = null
                            pendingAction = "host"
                            manager.createRoom()
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.listen_together_create_room))
                    }
                    HorizontalDivider()
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { codeInput = normalizeRoomCode(it).take(MAX_ROOM_CODE_LENGTH) },
                        label = { Text(stringResource(R.string.enter_room_code)) },
                        enabled = !busy,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { join() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = join, enabled = !busy && isValidRoomCode(codeInput), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.join_room))
                    }
                }
            }
            if (busy) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(if (pendingAction == "host") R.string.creating_room else R.string.listen_together_joining),
                        modifier = Modifier.weight(1f),
                    )
                }
                TextButton(onClick = { manager.disconnect(); pendingAction = null }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cancel))
                }
            }
        } else {
            val isHost = currentRoom.hostId == userId
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(if (isHost) R.string.listen_together_your_code else R.string.listen_together_listening),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    // Intrinsic character boxes wrap at larger font scales instead of clipping the final digits.
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        currentRoom.roomCode.forEach { character ->
                            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                                Text(
                                    character.toString(),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    if (isHost) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.room_code), currentRoom.roomCode))
                                Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                            },
                        ) { Text(stringResource(R.string.copy_code)) }
                        Text(
                            stringResource(if (currentRoom.users.count { it.isConnected } > 1) R.string.listen_together_listening else R.string.listen_together_give_code),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (connection != ConnectionState.CONNECTED) {
                        Text(stringResource(R.string.listen_together_reconnecting), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (isHost && pendingJoinRequests.isNotEmpty()) {
                        HorizontalDivider()
                        Text(stringResource(R.string.listen_together_join_requests), style = MaterialTheme.typography.titleMedium)
                        pendingJoinRequests.forEach { request ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(request.username, modifier = Modifier.weight(1f))
                                TextButton(onClick = { manager.rejectJoin(request.userId) }) {
                                    Text(stringResource(R.string.reject))
                                }
                                Button(onClick = { manager.approveJoin(request.userId) }) {
                                    Text(stringResource(R.string.approve))
                                }
                            }
                        }
                    }
                    TextButton(onClick = { manager.leaveRoom(); pendingAction = null; error = null }) {
                        Text(stringResource(R.string.leave_room))
                    }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun ListenTogetherSettingsControls(modifier: Modifier = Modifier) {
    val manager = LocalListenTogetherManager.current
    val connection by manager?.connectionState?.collectAsState()
        ?: remember { mutableStateOf(ConnectionState.DISCONNECTED) }
    val canEditConnection = connection == ConnectionState.DISCONNECTED || connection == ConnectionState.ERROR
    val (providerValue, setProviderValue) = rememberPreference(
        ListenTogetherProviderKey,
        ListenTogetherProvider.CLOUDFLARE.storageValue,
    )
    val provider = ListenTogetherProvider.fromStorage(providerValue)
    val (serverUrl, setServerUrl) = rememberPreference(
        ListenTogetherServerUrlKey,
        ListenTogetherServers.defaultUrl(provider),
    )
    val (cloudflareUrl, setCloudflareUrl) = rememberPreference(
        ListenTogetherCloudflareUrlKey,
        if (provider == ListenTogetherProvider.CLOUDFLARE) {
            serverUrl.ifBlank { ListenTogetherServers.defaultUrl(ListenTogetherProvider.CLOUDFLARE) }
        } else ListenTogetherServers.defaultUrl(ListenTogetherProvider.CLOUDFLARE),
    )
    val (huggingFaceUrl, setHuggingFaceUrl) = rememberPreference(
        ListenTogetherHuggingFaceUrlKey,
        if (provider == ListenTogetherProvider.HUGGING_FACE) {
            serverUrl.ifBlank { ListenTogetherServers.defaultUrl(ListenTogetherProvider.HUGGING_FACE) }
        } else ListenTogetherServers.defaultUrl(ListenTogetherProvider.HUGGING_FACE),
    )
    val (username, setUsername) = rememberPreference(ListenTogetherUsernameKey, "")
    val (autoApproval, setAutoApproval) = rememberPreference(ListenTogetherAutoApprovalKey, true)
    val (autoAddSuggestions, setAutoAddSuggestions) = rememberPreference(ListenTogetherAutoAddSuggestionsKey, false)
    val (syncVolume, setSyncVolume) = rememberPreference(ListenTogetherSyncVolumeKey, true)
    val (smartResync, setSmartResync) = rememberPreference(ListenTogetherSmartResyncKey, true)
    var expanded by rememberSaveable { mutableStateOf(false) }
    var serverUrlError by rememberSaveable { mutableStateOf(false) }
    var usernameDraft by rememberSaveable { mutableStateOf(username) }

    LaunchedEffect(username) { usernameDraft = username }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.listen_together_settings), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.listen_together_settings_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(if (expanded) "−" else "+", style = MaterialTheme.typography.titleLarge)
        }

        if (expanded) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(stringResource(R.string.listen_together_server_url), style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = provider == ListenTogetherProvider.CLOUDFLARE,
                            enabled = canEditConnection,
                            onClick = {
                                setProviderValue(ListenTogetherProvider.CLOUDFLARE.storageValue)
                                setServerUrl(cloudflareUrl)
                                serverUrlError = false
                            },
                            label = { Text("Cloudflare") },
                        )
                        FilterChip(
                            selected = provider == ListenTogetherProvider.HUGGING_FACE,
                            enabled = canEditConnection,
                            onClick = {
                                setProviderValue(ListenTogetherProvider.HUGGING_FACE.storageValue)
                                setServerUrl(huggingFaceUrl)
                                serverUrlError = false
                            },
                            label = { Text("Hugging Face") },
                        )
                    }
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { value ->
                            setServerUrl(value)
                            serverUrlError = value.isNotBlank() && ListenTogetherServers.normalizeServerUrl(value) == null
                            if (provider == ListenTogetherProvider.CLOUDFLARE) setCloudflareUrl(value)
                            else setHuggingFaceUrl(value)
                        },
                        label = { Text(stringResource(R.string.listen_together_server_url)) },
                        supportingText = {
                            Text(
                                when {
                                    serverUrlError -> stringResource(R.string.listen_together_invalid_server_url)
                                    serverUrl.isBlank() -> stringResource(R.string.listen_together_server_url_required)
                                    else -> stringResource(R.string.listen_together_server_url_help)
                                },
                            )
                        },
                        isError = serverUrlError,
                        enabled = canEditConnection,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HorizontalDivider()
                    OutlinedTextField(
                        value = usernameDraft,
                        onValueChange = { usernameDraft = it.take(40); setUsername(it.take(40).trim()) },
                        label = { Text(stringResource(R.string.listen_together_username)) },
                        supportingText = { Text(stringResource(R.string.listen_together_username_help)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HorizontalDivider()
                    ListenTogetherSettingSwitch(
                        title = stringResource(R.string.listen_together_auto_approval),
                        description = stringResource(R.string.listen_together_auto_approval_desc),
                        checked = autoApproval,
                        onCheckedChange = setAutoApproval,
                    )
                    ListenTogetherSettingSwitch(
                        title = stringResource(R.string.listen_together_auto_add),
                        description = stringResource(R.string.listen_together_auto_add_desc),
                        checked = autoAddSuggestions,
                        onCheckedChange = setAutoAddSuggestions,
                    )
                    ListenTogetherSettingSwitch(
                        title = stringResource(R.string.listen_together_sync_volume),
                        description = stringResource(R.string.listen_together_sync_volume_desc),
                        checked = syncVolume,
                        onCheckedChange = setSyncVolume,
                    )
                    ListenTogetherSettingSwitch(
                        title = stringResource(R.string.listen_together_smart_resync),
                        description = stringResource(R.string.listen_together_smart_resync_desc),
                        checked = smartResync,
                        onCheckedChange = setSmartResync,
                    )
                }
            }
        }
    }
}

@Composable
private fun ListenTogetherSettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
