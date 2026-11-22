package dev.ticbox.companion.ui.toolbox

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.AdbDiscoveredEndpoint
import dev.ticbox.companion.api.AdbEndpoint
import dev.ticbox.companion.api.AdbPairingStatus
import dev.ticbox.companion.api.AdbToolboxClient
import dev.ticbox.companion.api.ToolboxBackendState
import dev.ticbox.companion.api.ToolboxException
import dev.ticbox.companion.api.ToolboxPhase
import dev.ticbox.companion.api.WatchSettingsScreen
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.ActionIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.BannerTone
import dev.ticbox.companion.ui.designsystem.BusyBar
import dev.ticbox.companion.ui.designsystem.FadeSwitch
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.GrowingGaps
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.NavigationRow
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.Reveal
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.device.message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class AdbConnectViewModel(dependencies: CompanionDependencies) : SessionViewModel(dependencies) {
    private val found = MutableStateFlow<List<AdbDiscoveredEndpoint>?>(null)

    /** What the last search found; null until one finishes. */
    val endpoints: StateFlow<List<AdbDiscoveredEndpoint>?> = found.asStateFlow()

    val link: StateFlow<ToolboxBackendState?> =
        sessionState(
            current = { it.services.toolbox.adb.state.value },
            updates = { it.services.toolbox.adb.state },
        )

    /**
     * The search the page starts by itself. It also tries the last device straight away, as some
     * watches can only be found just after wireless debugging is turned on, and if that address no
     * longer answers, the search connects to the same device at its new one. Neither happens after
     * the person disconnected, until they connect again.
     */
    fun arrive() {
        reconnect()
        discover()
    }

    /**
     * To the last address, or [to] the same device found at a new one. Says nothing when it doesn't
     * work out: the person didn't ask for this.
     */
    private fun reconnect(to: AdbEndpoint? = null) =
        command(RECONNECT) { session ->
            val adb = session.services.toolbox.adb
            try {
                if (to == null) adb.reconnectLast() else connect(adb, to)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {}
            null
        }

    fun discover() =
        command(DISCOVER) { session ->
            val adb = session.services.toolbox.adb
            try {
                found.value = adb.discover()
            } finally {
                // A search that failed has answered too; it just has nothing to show for it.
                found.update { list -> list ?: emptyList() }
            }
            // Only once this app's key has just been accepted, so trying can't end in a pairing
            // error.
            val last =
                found.value.orEmpty().singleOrNull {
                    it.autoConnect && it.pairingStatus == AdbPairingStatus.PAIRED
                }
            val idle =
                RECONNECT !in pending.value &&
                    adb.state.value.phase.let {
                        it != ToolboxPhase.CONNECTING && it != ToolboxPhase.CONNECTED
                    }
            if (last != null && idle) reconnect(last.endpoint)
            null
        }

    fun connect(endpoint: AdbEndpoint) =
        command(CONNECT) { session ->
            connect(session.services.toolbox.adb, endpoint)
            null
        }

    /** The watch connects on a different port than it pairs on, so look for that one next. */
    fun pair(endpoint: AdbEndpoint, code: String) =
        command(PAIR) { session ->
            val adb = session.services.toolbox.adb
            failingAs("adb_pair_failed") { adb.pair(endpoint, code) }
            val next = adb.discover().also { found.value = it }
            val target = next.firstOrNull { !it.pairing && it.endpoint.host == endpoint.host }
            if (target != null) {
                connect(adb, target.endpoint)
                null
            } else {
                uiText(R.string.adb_paired_connect_next)
            }
        }

    fun disconnect() =
        command(DISCONNECT) {
            it.services.toolbox.adb.disconnectByChoice()
            null
        }

    fun openWatchSettings() =
        command(OPEN_SETTINGS) {
            it.services.deviceSettings
                .openSettings(WatchSettingsScreen.ABOUT)
                .message(opened = true)
        }

    private suspend fun connect(adb: AdbToolboxClient, to: AdbEndpoint) {
        try {
            failingAs("adb_connect_failed") { adb.connect(to) }
            pairingObserved(to, AdbPairingStatus.PAIRED)
        } catch (problem: ToolboxException) {
            if (problem.code == "adb_pairing_required")
                pairingObserved(to, AdbPairingStatus.REQUIRED)
            throw problem
        }
    }

    private fun pairingObserved(endpoint: AdbEndpoint, status: AdbPairingStatus) {
        found.update { endpoints ->
            endpoints?.map {
                if (!it.pairing && it.endpoint == endpoint) it.copy(pairingStatus = status) else it
            }
        }
    }

    /** Kadb's own exceptions say nothing a person can act on. */
    private suspend fun failingAs(code: String, block: suspend () -> Unit) {
        try {
            reportingTimeouts(block)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (known: ToolboxException) {
            throw known
        } catch (invalid: IllegalArgumentException) {
            throw invalid
        } catch (problem: Exception) {
            throw ToolboxException(code, problem)
        }
    }

    companion object {
        const val DISCOVER = "adb_discover"
        const val RECONNECT = "adb_reconnect"
        const val CONNECT = "adb_connect"
        const val PAIR = "adb_pair"
        const val DISCONNECT = "adb_disconnect"
        const val OPEN_SETTINGS = "adb_open_settings"
    }
}

/** Pairing, connecting and leading the user through the watch's wireless debugging settings. */
@Composable
fun AdbConnectScreen(onBack: () -> Unit) {
    val viewModel = companionViewModel { AdbConnectViewModel(it) }
    val link by viewModel.link.collectAsStateWithLifecycle()
    val endpoints by viewModel.endpoints.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val network = rememberLocalNetworkAccess()
    val phase = link?.phase ?: ToolboxPhase.DISCONNECTED
    val connected = phase == ToolboxPhase.CONNECTED
    val working = phase == ToolboxPhase.CONNECTING || AdbConnectViewModel.PAIR in pending
    // The first search starts a moment after the page, so nothing has been found to say so yet.
    val searching =
        AdbConnectViewModel.DISCOVER in pending ||
            (endpoints == null && network.granted && link != null)
    LaunchedEffect(network.granted, connected) {
        if (network.granted && !connected) viewModel.arrive()
    }
    PageScaffold(
        stringResource(R.string.adb_title),
        onBack = onBack,
        actions = {
            AnimatedVisibility(!connected, enter = fadeIn(), exit = fadeOut()) {
                ActionIcon(
                    AppIcons.Refresh,
                    stringResource(R.string.adb_search_again),
                    viewModel::discover,
                    enabled = network.granted && !searching && !working,
                )
            }
        },
    ) { padding ->
        PageColumn(padding) {
            Reveal(!network.granted) {
                StatusBanner(
                    stringResource(R.string.adb_local_network_required),
                    tone = BannerTone.Warning,
                    actionLabel = stringResource(R.string.notifications_grant_access),
                    onAction = network.request,
                )
            }
            AnimatedContent(
                connected,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "adb_connection",
            ) { isConnected ->
                Column(verticalArrangement = GrowingGaps(20.dp)) {
                    if (isConnected) {
                        ConnectedCard(link, onDisconnect = viewModel::disconnect)
                        return@Column
                    }
                    BusyBar(working)
                    val found: @Composable () -> Unit = {
                        Found(
                            endpoints,
                            searching = searching,
                            enabled = network.granted && !working,
                            onConnect = viewModel::connect,
                            onPair = viewModel::pair,
                        )
                    }
                    // Once the watch turns up, choosing it is the next step, not the steps before.
                    val anyFound = !endpoints.isNullOrEmpty()
                    Reveal(anyFound) { found() }
                    WatchSteps(onOpenSettings = viewModel::openWatchSettings)
                    Reveal(!anyFound) { found() }
                    ManualEntry(
                        enabled = network.granted && !working,
                        onConnect = viewModel::connect,
                        onPair = viewModel::pair,
                    )
                }
            }
        }
    }
}

private class LocalNetworkAccess(val granted: Boolean, val request: () -> Unit)

/** Android 17 made reaching other devices on the network a runtime permission. */
@Composable
private fun rememberLocalNetworkAccess(): LocalNetworkAccess {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasLocalNetworkAccess(context)) }
    var denied by rememberSaveable { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = it
            denied = !it
        }
    return LocalNetworkAccess(granted) {
        // The system stops showing its prompt after a refusal, leaving only its settings page.
        if (denied) {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                )
            )
        } else {
            launcher.launch(LOCAL_NETWORK_PERMISSION)
        }
    }
}

private fun hasLocalNetworkAccess(context: Context): Boolean =
    Build.VERSION.SDK_INT < 37 ||
        context.checkSelfPermission(LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

@Composable
private fun ConnectedCard(link: ToolboxBackendState?, onDisconnect: () -> Unit) {
    Reveal(link?.previousDeviceId != null) {
        StatusBanner(stringResource(R.string.toolbox_device_changed), tone = BannerTone.Warning)
    }
    StatusBanner(
        stringResource(R.string.adb_connected_to, link?.device.orEmpty()),
        tone = BannerTone.Success,
        title = stringResource(R.string.adb_connected),
        actionLabel = stringResource(R.string.adb_disconnect),
        onAction = onDisconnect,
    )
}

@Composable
private fun WatchSteps(onOpenSettings: () -> Unit) {
    StepCard(1, stringResource(R.string.adb_step_developer_title)) {
        StepBody(stringResource(R.string.adb_step_developer_body))
        FilledTonalButton(onClick = onOpenSettings) {
            Text(stringResource(R.string.adb_open_about))
        }
    }
    StepCard(2, stringResource(R.string.adb_step_wireless_title)) {
        StepBody(stringResource(R.string.adb_step_wireless_body))
    }
    StepCard(3, stringResource(R.string.adb_step_pair_title)) {
        StepBody(stringResource(R.string.adb_step_pair_body))
    }
}

@Composable
private fun Found(
    endpoints: List<AdbDiscoveredEndpoint>?,
    searching: Boolean,
    enabled: Boolean,
    onConnect: (AdbEndpoint) -> Unit,
    onPair: (AdbEndpoint, String) -> Unit,
) {
    var pairing by remember { mutableStateOf<AdbEndpoint?>(null) }
    ListGroup(title = stringResource(R.string.adb_found_title)) {
        Reveal(searching) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
        Reveal(endpoints.isNullOrEmpty() && !searching) {
            Text(
                stringResource(R.string.adb_found_none),
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FadeSwitch(endpoints.orEmpty()) { shown ->
            Column(Modifier.fillMaxWidth()) {
                shown.forEach { found ->
                    val address = "${found.endpoint.host}:${found.endpoint.port}"
                    NavigationRow(
                        found.name,
                        onClick = {
                            if (found.pairing) pairing = found.endpoint
                            else onConnect(found.endpoint)
                        },
                        icon = if (found.pairing) AppIcons.Key else AppIcons.Wifi,
                        summary =
                            stringResource(
                                when {
                                    found.pairing -> R.string.adb_found_pairing
                                    found.pairingStatus == AdbPairingStatus.REQUIRED ->
                                        R.string.adb_found_unpaired
                                    found.pairingStatus == AdbPairingStatus.PAIRED ->
                                        R.string.adb_found_ready
                                    else -> R.string.adb_found_pairing_unknown
                                },
                                address,
                            ),
                        enabled = enabled,
                    )
                }
            }
        }
    }
    pairing?.let { endpoint ->
        PairingCodeDialog(
            onDismiss = { pairing = null },
            onPair = { code ->
                pairing = null
                onPair(endpoint, code)
            },
        )
    }
}

@Composable
private fun PairingCodeDialog(onDismiss: () -> Unit, onPair: (String) -> Unit) {
    // A one-time secret: kept out of saved state on purpose.
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.adb_pair_title)) },
        text = {
            PairingCodeField(code, onChange = { code = it }, enabled = true)
        },
        confirmButton = {
            TextButton(onClick = { onPair(code) }, enabled = isPairingCode(code)) {
                Text(stringResource(R.string.adb_pair))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun PairingCodeField(code: String, onChange: (String) -> Unit, enabled: Boolean) {
    OutlinedTextField(
        value = code,
        onValueChange = { onChange(it.filter(Char::isDigit).take(PAIRING_CODE_LENGTH)) },
        label = { Text(stringResource(R.string.adb_pairing_code)) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** For when discovery can't see the watch, such as on a network that blocks multicast. */
@Composable
private fun ManualEntry(
    enabled: Boolean,
    onConnect: (AdbEndpoint) -> Unit,
    onPair: (AdbEndpoint, String) -> Unit,
) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val endpoint = adbEndpointOf(host, port)
    ListGroup(title = stringResource(R.string.adb_manual_title)) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it.trim() },
                label = { Text(stringResource(R.string.adb_host)) },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text(stringResource(R.string.adb_port)) },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            PairingCodeField(code, onChange = { code = it }, enabled = enabled)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { endpoint?.let { onPair(it, code) } },
                    enabled = enabled && endpoint != null && isPairingCode(code),
                ) {
                    Text(stringResource(R.string.adb_pair))
                }
                Button(
                    onClick = { endpoint?.let(onConnect) },
                    enabled = enabled && endpoint != null,
                ) {
                    Text(stringResource(R.string.adb_connect))
                }
            }
        }
    }
    FootnoteText(stringResource(R.string.adb_manual_note))
}

private const val PAIRING_CODE_LENGTH = 6

internal fun isPairingCode(code: String): Boolean =
    code.length == PAIRING_CODE_LENGTH && code.all(Char::isDigit)

internal fun adbEndpointOf(host: String, port: String): AdbEndpoint? {
    val number = port.toIntOrNull() ?: return null
    return runCatching { AdbEndpoint(host, number) }.getOrNull()
}
