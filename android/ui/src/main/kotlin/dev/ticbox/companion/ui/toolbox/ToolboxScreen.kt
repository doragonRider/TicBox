package dev.ticbox.companion.ui.toolbox

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.ToolboxBackendKind
import dev.ticbox.companion.api.ToolboxBackendState
import dev.ticbox.companion.api.ToolboxPhase
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.bridge.LocalBridgeStatus
import dev.ticbox.companion.ui.bridge.rememberLocalBridgeStatus
import dev.ticbox.companion.ui.bridge.rememberPhoneVersion
import dev.ticbox.companion.ui.common.asString
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.BackButton
import dev.ticbox.companion.ui.designsystem.BannerTone
import dev.ticbox.companion.ui.designsystem.GrowingGaps
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.NavigationRow
import dev.ticbox.companion.ui.designsystem.PageList
import dev.ticbox.companion.ui.designsystem.Reveal
import dev.ticbox.companion.ui.designsystem.SectionTitle
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.navigation.AdbConnectRoute
import dev.ticbox.companion.ui.navigation.ApkInstallerRoute
import dev.ticbox.companion.ui.navigation.AppManagerRoute
import dev.ticbox.companion.ui.navigation.ClipboardRoute
import dev.ticbox.companion.ui.navigation.FileManagerRoute
import dev.ticbox.companion.ui.navigation.LocalBridgeRoute
import dev.ticbox.companion.ui.navigation.RemoteScreenRoute
import dev.ticbox.companion.ui.navigation.SplitInstallerRoute
import dev.ticbox.companion.ui.navigation.TerminalRoute

private data class Tool(
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int,
    @param:DrawableRes val icon: Int,
    val route: Any,
)

private val installers =
    listOf(
        Tool(
            R.string.tool_apk_title,
            R.string.tool_apk_summary,
            AppIcons.InstallApk,
            ApkInstallerRoute,
        ),
        Tool(
            R.string.tool_split_title,
            R.string.tool_split_summary,
            AppIcons.SplitApk,
            SplitInstallerRoute,
        ),
    )

private val tools =
    listOf(
        Tool(
            R.string.tool_terminal_title,
            R.string.tool_terminal_summary,
            AppIcons.Terminal,
            TerminalRoute,
        ),
        Tool(
            R.string.tool_screen_title,
            R.string.tool_screen_summary,
            AppIcons.ScreenShare,
            RemoteScreenRoute,
        ),
        Tool(
            R.string.tool_files_title,
            R.string.tool_files_summary,
            AppIcons.Storage,
            FileManagerRoute,
        ),
        Tool(R.string.tool_apps_title, R.string.tool_apps_summary, AppIcons.Apps, AppManagerRoute),
        Tool(
            R.string.tool_clipboard_title,
            R.string.tool_clipboard_summary,
            AppIcons.Paste,
            ClipboardRoute,
        ),
    )

/** Watch → Manage → Advanced: how the phone reaches the watch, then what it can do with that. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolboxScreen(graph: ViewModelStoreOwner, onBack: () -> Unit, navigate: (Any) -> Unit) {
    val viewModel = toolboxViewModel(graph)
    val state by viewModel.access.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val access = state ?: ToolboxAccess()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.toolbox_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton(onBack) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val status = rememberLocalBridgeStatus()
        val bridge by viewModel.bridgeOnWatch.collectAsStateWithLifecycle()
        val phoneVersion = rememberPhoneVersion()
        LaunchedEffect(access.connected, access.target) {
            if (access.connected) viewModel.checkBridge()
        }
        PageList(padding, spacing = 16.dp) {
            // One item, so a banner coming or going moves everything below it smoothly, and one
            // added above the list's first item doesn't land out of sight above the scroll
            // position.
            item(key = "backends") {
                Column(
                    Modifier.animateContentSize(),
                    verticalArrangement = GrowingGaps(16.dp),
                ) {
                    Reveal(access.deviceChanged) {
                        StatusBanner(
                            stringResource(R.string.toolbox_device_changed),
                            tone = BannerTone.Warning,
                            actionLabel = stringResource(R.string.toolbox_accept_device),
                            onAction = { viewModel.confirmDevice(access.target) },
                        )
                    }
                    Reveal(!access.deviceChanged && !access.connected) {
                        StatusBanner(
                            stringResource(R.string.toolbox_needs_connection),
                            title = stringResource(R.string.toolbox_no_backend),
                        )
                    }
                    BackendGroup(
                        access,
                        status,
                        bridgeOutdated = bridge?.let { it.versionCode < phoneVersion } == true,
                        busy = ToolboxViewModel.CONNECT_BRIDGE in pending,
                        onConnectBridge = viewModel::connectLocalBridge,
                        onDisconnectBridge = viewModel::disconnectLocalBridge,
                        navigate = navigate,
                    )
                    Reveal(access.bothConnected) { BackendChoice(access, viewModel::select) }
                }
            }
            item(key = "installers") {
                ToolSection(R.string.toolbox_section_installers, installers, access, navigate, true)
            }
            item(key = "tools") {
                ToolSection(R.string.toolbox_section_tools, tools, access, navigate, false)
            }
        }
    }
}

/** These can only be fixed by updating Local Bridge, so connecting again gets nowhere. */
private val BRIDGE_NEEDS_UPDATE = setOf("agent_version_mismatch", "bridge_update_needs_adb")

@Composable
private fun BackendGroup(
    access: ToolboxAccess,
    status: LocalBridgeStatus,
    bridgeOutdated: Boolean,
    busy: Boolean,
    onConnectBridge: () -> Unit,
    onDisconnectBridge: () -> Unit,
    navigate: (Any) -> Unit,
) {
    val rootConnected = access.rootBridge.phase == ToolboxPhase.CONNECTED
    val rootNeedsUpdate = access.rootBridge.error in BRIDGE_NEEDS_UPDATE
    ListGroup(title = stringResource(R.string.toolbox_section_backends)) {
        NavigationRow(
            stringResource(R.string.adb_title),
            onClick = { navigate(AdbConnectRoute) },
            icon = AppIcons.Wifi,
            summary = backendSummary(access.adb),
        )
        NavigationRow(
            stringResource(R.string.toolbox_root_bridge),
            onClick =
                when {
                    rootConnected -> onDisconnectBridge
                    rootNeedsUpdate -> {
                        { navigate(LocalBridgeRoute) }
                    }
                    else -> onConnectBridge
                },
            icon = AppIcons.Key,
            summary =
                if (status == LocalBridgeStatus.NOT_INSTALLED) {
                    stringResource(R.string.toolbox_root_needs_bridge)
                } else {
                    backendSummary(access.rootBridge)
                },
            enabled = status != LocalBridgeStatus.NOT_INSTALLED && (!busy || rootConnected),
            trailingIcon =
                when {
                    rootConnected -> AppIcons.Disconnect
                    rootNeedsUpdate -> AppIcons.ChevronRight
                    else -> null
                },
            fadeTrailingIcon = true,
        )
        NavigationRow(
            stringResource(R.string.bridge_title),
            onClick = { navigate(LocalBridgeRoute) },
            icon = AppIcons.Download,
            summary =
                stringResource(
                    when {
                        bridgeOutdated -> R.string.bridge_status_update
                        else -> bridgeStatusText(status)
                    }
                ),
        )
    }
}

@StringRes
private fun bridgeStatusText(status: LocalBridgeStatus): Int =
    when (status) {
        LocalBridgeStatus.READY -> R.string.bridge_status_ready
        LocalBridgeStatus.NOT_INSTALLED -> R.string.bridge_status_missing
        LocalBridgeStatus.NOT_ALLOWED -> R.string.bridge_status_not_allowed
        LocalBridgeStatus.UNREACHABLE -> R.string.bridge_status_unreachable
        LocalBridgeStatus.UNKNOWN -> R.string.bridge_status_unknown
    }

@Composable
private fun backendSummary(state: ToolboxBackendState): String =
    when (state.phase) {
        ToolboxPhase.CONNECTED ->
            state.device?.let { stringResource(R.string.adb_connected_to, it) }
                ?: stringResource(R.string.adb_connected)
        ToolboxPhase.CONNECTING -> stringResource(R.string.toolbox_connecting)
        ToolboxPhase.FAILED ->
            state.error?.let { toolboxMessage(it).asString() }
                ?: stringResource(R.string.toolbox_not_connected)
        ToolboxPhase.DISCONNECTED,
        ToolboxPhase.CLOSED -> stringResource(R.string.toolbox_not_connected)
    }

@Composable
private fun BackendChoice(access: ToolboxAccess, onSelect: (ToolboxBackendKind) -> Unit) {
    val options =
        listOf(
            ToolboxBackendKind.ADB to R.string.toolbox_use_adb,
            ToolboxBackendKind.ROOT_BRIDGE to R.string.toolbox_use_root,
        )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.toolbox_use_title))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (kind, label) ->
                SegmentedButton(
                    selected = access.selected == kind,
                    onClick = { onSelect(kind) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) {
                    Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ToolSection(
    @StringRes title: Int,
    tools: List<Tool>,
    access: ToolboxAccess,
    navigate: (Any) -> Unit,
    warm: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(stringResource(title))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth >= 340.dp && LocalDensity.current.fontScale <= 1.3f) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                tools.chunked(columns).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        row.forEach { tool ->
                            ToolCard(
                                tool,
                                warm = warm,
                                enabled = access.connected,
                                onClick = { navigate(tool.route) },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                        if (row.size < columns) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun fading(color: Color): Color = animateColorAsState(color, label = "tool_card").value

/** Grey without a backend, yet still opens: the page inside shows what the tool would do. */
@Composable
private fun ToolCard(
    tool: Tool,
    warm: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val disabled = colors.onSurface.copy(alpha = 0.38f)
    val container =
        when {
            !enabled -> colors.onSurface.copy(alpha = 0.08f)
            warm -> colors.tertiaryContainer
            else -> colors.surfaceContainerHigh
        }
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = fading(container),
    ) {
        Column(Modifier.padding(16.dp)) {
            ToolBadge(
                tool.icon,
                container =
                    fading(
                        if (enabled) colors.surface.copy(alpha = 0.8f)
                        else colors.onSurface.copy(alpha = 0.08f)
                    ),
                tint = fading(if (enabled) colors.primary else disabled),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(tool.title),
                style = MaterialTheme.typography.titleMedium,
                color =
                    fading(
                        when {
                            !enabled -> disabled
                            warm -> colors.onTertiaryContainer
                            else -> colors.onSurface
                        }
                    ),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(tool.summary),
                style = MaterialTheme.typography.bodyMedium,
                color = fading(if (enabled) colors.onSurfaceVariant else disabled),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
