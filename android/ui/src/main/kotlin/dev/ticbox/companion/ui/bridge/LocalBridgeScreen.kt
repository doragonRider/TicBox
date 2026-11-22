package dev.ticbox.companion.ui.bridge

import android.content.res.AssetManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.ticbox.companion.api.ToolboxApk
import dev.ticbox.companion.api.ToolboxApp
import dev.ticbox.companion.api.ToolboxAppAction
import dev.ticbox.companion.api.ToolboxAppRequest
import dev.ticbox.companion.api.ToolboxBackendKind
import dev.ticbox.companion.api.ToolboxBackendState
import dev.ticbox.companion.api.ToolboxException
import dev.ticbox.companion.api.ToolboxInstallOptions
import dev.ticbox.companion.api.ToolboxPhase
import dev.ticbox.companion.api.ToolboxTarget
import dev.ticbox.companion.api.WatchToolboxBackend
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.WatchUiServices
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.UiText
import dev.ticbox.companion.ui.common.asString
import dev.ticbox.companion.ui.common.toUiText
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.BannerTone
import dev.ticbox.companion.ui.designsystem.FadeSwitch
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.GrowingGaps
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.NavigationRow
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.ProgressBar
import dev.ticbox.companion.ui.designsystem.Reveal
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.navigation.AdbConnectRoute
import dev.ticbox.companion.ui.navigation.HealthJournalRoute
import dev.ticbox.companion.ui.navigation.WatchModuleRoute
import dev.ticbox.companion.ui.toolbox.CommandOutput
import dev.ticbox.companion.ui.toolbox.StepBody
import dev.ticbox.companion.ui.toolbox.StepCard
import dev.ticbox.companion.ui.toolbox.ToolHero
import dev.ticbox.companion.ui.toolbox.fractionOf
import dev.ticbox.companion.ui.toolbox.reportingTimeouts
import dev.ticbox.companion.ui.toolbox.toolboxDeviceSelection
import dev.ticbox.companion.ui.toolbox.toolboxMemory
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The watch app shipped inside the phone app, of exactly the same build and signature. */
fun interface BundledBridge {
    /** Null when this build carries none, as in tests and the demo. */
    suspend fun open(): ToolboxApk?
}

private const val BUNDLED_BRIDGE_ASSET = "watch/local-bridge.apk"

internal class AssetBundledBridge(private val assets: AssetManager) : BundledBridge {
    /** Packaging may compress the asset, and an installer needs the real size up front. */
    override suspend fun open(): ToolboxApk? =
        withContext(Dispatchers.IO) {
            val size =
                runCatching {
                    assets.open(BUNDLED_BRIDGE_ASSET).use {
                        it.copyTo(OutputStream.nullOutputStream())
                    }
                }
                    .getOrNull() ?: return@withContext null
            ToolboxApk("local-bridge.apk", size) { assets.open(BUNDLED_BRIDGE_ASSET) }
        }
}

sealed interface BridgeInstallState {
    data object Idle : BridgeInstallState

    data class Running(val fraction: Float?) : BridgeInstallState

    /** All of it arrived over root, and the bridge that carried it stops to be replaced. */
    data object Restarting : BridgeInstallState

    /** The watch's package manager said so; transferring alone doesn't get here. */
    data object Installed : BridgeInstallState

    /** Local Bridge answered again afterwards, and is the version that was sent. */
    data object Updated : BridgeInstallState

    data class Refused(val output: String?) : BridgeInstallState

    data class Failed(val message: UiText) : BridgeInstallState

    /** The connection dropped after the commit may have begun, so it may still have worked. */
    data object Unknown : BridgeInstallState
}

data class LocalBridgeUiState(
    val status: LocalBridgeStatus = LocalBridgeStatus.UNKNOWN,
    val adb: ToolboxBackendState = ToolboxBackendState(ToolboxBackendKind.ADB),
    val deviceChanged: Boolean = false,
    val root: ToolboxBackendState = ToolboxBackendState(ToolboxBackendKind.ROOT_BRIDGE),
    val rootDeviceChanged: Boolean = false,
) {
    val adbConnected: Boolean
        get() = adb.phase == ToolboxPhase.CONNECTED && !deviceChanged

    val rootConnected: Boolean
        get() = root.phase == ToolboxPhase.CONNECTED && !rootDeviceChanged

    /** Tools refuse a bridge of an older version, but the connection can still replace it. */
    val rootOutdated: Boolean
        get() = root.phase == ToolboxPhase.FAILED && root.error == "agent_version_mismatch"

    /** Wireless debugging comes first when both are up, since it outlasts the bridge's restart. */
    val installsOverRoot: Boolean
        get() = !adbConnected && (rootConnected || rootOutdated)

    val canInstall: Boolean
        get() = adbConnected || installsOverRoot
}

class LocalBridgeViewModel(
    dependencies: CompanionDependencies,
    private val phoneVersion: Long = 0,
    private val updateCheckPauseMillis: Long = 5_000,
    private val bundled: BundledBridge,
) : SessionViewModel(dependencies) {
    private val memory = session.value?.services?.toolbox?.let(::toolboxMemory)
    private val installState =
        memory?.bridgeInstall ?: MutableStateFlow<BridgeInstallState>(BridgeInstallState.Idle)
    private val onWatch = MutableStateFlow<ToolboxApp?>(null)
    private val installTarget =
        memory?.bridgeInstallTarget ?: MutableStateFlow<ToolboxTarget?>(null)

    init {
        // One still running is shown again, while how an earlier one ended no longer matters.
        if (!installState.value.isRunning) installState.value = BridgeInstallState.Idle
    }

    val installationTarget: StateFlow<ToolboxTarget?> = installTarget.asStateFlow()

    val install: StateFlow<BridgeInstallState> = installState.asStateFlow()

    /** What ADB says is installed, once it has been asked. */
    val installed: StateFlow<ToolboxApp?> = onWatch.asStateFlow()

    private fun checked(
        session: dev.ticbox.companion.ui.session.WatchSession,
        adb: ToolboxBackendState,
        root: ToolboxBackendState,
    ): LocalBridgeUiState {
        val accepted = toolboxDeviceSelection(session.services.toolbox)
        val id = adb.target?.deviceId
        if (id != null && accepted.value == null) accepted.value = adb.previousDeviceId ?: id
        val rootId = root.target?.deviceId
        return LocalBridgeUiState(
            session.localBridgeStatus(),
            adb,
            deviceChanged = adb.previousDeviceId != null || id != null && accepted.value != id,
            root,
            rootDeviceChanged =
                root.previousDeviceId != null ||
                    rootId != null && accepted.value != null && accepted.value != rootId,
        )
    }

    val state: StateFlow<LocalBridgeUiState?> =
        sessionState(
            current = {
                checked(
                    it,
                    it.services.toolbox.adb.state.value,
                    it.services.toolbox.localBridge.state.value,
                )
            },
            updates = { session ->
                combine(
                    session.services.localHealth.state,
                    session.services.deviceSettings.state,
                    session.services.toolbox.adb.state,
                    session.services.toolbox.localBridge.state,
                    toolboxDeviceSelection(session.services.toolbox),
                ) { _, _, adb, root, _ ->
                    checked(session, adb, root)
                }
            },
        )

    fun readInstalled() {
        val toolbox = session.value?.services?.toolbox ?: return
        val current = state.value ?: return
        val backend = if (current.adbConnected) toolbox.adb else toolbox.localBridge
        val target = backend.state.value.target ?: return
        val memory = toolboxMemory(toolbox)
        viewModelScope.launch {
            val app =
                try {
                    val apps =
                        memory.apps(target)
                            ?: reportingTimeouts { backend.bind(target).apps() }
                                .also { memory.rememberApps(target, it) }
                    apps.firstOrNull { it.packageName == LOCAL_BRIDGE_PACKAGE }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            if (backend.state.value.target == target) onWatch.value = app
        }
    }

    fun confirmDevice(target: ToolboxTarget? = state.value?.adb?.target) {
        val reviewed = target ?: return
        command(CONFIRM_DEVICE) {
            it.services.toolbox.adb.confirmDevice(reviewed)
            toolboxDeviceSelection(it.services.toolbox).value = reviewed.deviceId
            null
        }
    }

    fun install() {
        if (installState.value.isRunning) return
        if (state.value?.installsOverRoot == true) {
            updateOverRoot()
            return
        }
        val target = state.value?.adb?.target
        val changed = state.value?.deviceChanged == true
        command(INSTALL) { session ->
            if (changed) throw ToolboxException("device_changed")
            val adb =
                session.services.toolbox.adb.bind(
                    target ?: throw ToolboxException("device_identity_required")
                )
            installTarget.value = target
            val apk = bundled.open() ?: throw ToolboxException("bridge_bundle_missing")
            installState.value = BridgeInstallState.Running(null)
            try {
                val result =
                    try {
                        reportingTimeouts {
                            adb.install(
                                listOf(apk),
                                ToolboxInstallOptions(
                                    replace = true,
                                    grantRuntimePermissions = true,
                                ),
                            ) { progress ->
                                installState.value =
                                    BridgeInstallState.Running(
                                        fractionOf(progress.completedBytes, progress.totalBytes)
                                    )
                            }
                        }
                    } finally {
                        toolboxMemory(session.services.toolbox).forgetLists()
                    }
                if (result.success) {
                    installState.value = BridgeInstallState.Installed
                    finishInstall(session.services, adb)
                } else {
                    installState.value =
                        BridgeInstallState.Refused(result.output.trim().ifEmpty { null })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: ToolboxException) {
                installState.value =
                    if (refused.code in KNOWN_FAILURES) {
                        BridgeInstallState.Failed(refused.toUiText())
                    } else {
                        BridgeInstallState.Unknown
                    }
            } catch (_: Exception) {
                installState.value = BridgeInstallState.Unknown
            }
            null
        }
    }

    /** The app taking this route is the one being replaced, which also cuts off its answer. */
    private fun updateOverRoot() =
        command(INSTALL) { session ->
            val services = session.services
            val before = onWatch.value
            val apk = bundled.open() ?: throw ToolboxException("bridge_bundle_missing")
            installTarget.value = null
            installState.value = BridgeInstallState.Running(null)
            val outcome =
                try {
                    val result = reportingTimeouts {
                        services.toolbox.localBridge.updateHost(apk) { progress ->
                            installState.value =
                                BridgeInstallState.Running(
                                    fractionOf(progress.completedBytes, progress.totalBytes)
                                )
                        }
                    }
                    if (result.success) null
                    else BridgeInstallState.Refused(result.output.trim().ifEmpty { null })
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (problem: ToolboxException) {
                    if (problem.code in UNKNOWN_OUTCOMES) null
                    else BridgeInstallState.Failed(problem.toUiText())
                } catch (_: Exception) {
                    null
                }
            installState.value = outcome ?: BridgeInstallState.Restarting
            if (outcome == null) installState.value = confirmUpdate(services, before)
            null
        }

    /**
     * A new install path shows it was replaced, even by the same version. Without one to compare,
     * the version alone does, since tools had just refused the bridge for having another.
     */
    private suspend fun confirmUpdate(
        services: WatchUiServices,
        before: ToolboxApp?,
    ): BridgeInstallState {
        val root = services.toolbox.localBridge
        val memory = toolboxMemory(services.toolbox)
        memory.forgetLists()
        repeat(UPDATE_CHECKS) { check ->
            if (check > 0) delay(updateCheckPauseMillis)
            try {
                reportingTimeouts { root.connect() }
                val target = root.state.value.target ?: return@repeat
                val apps = reportingTimeouts { root.bind(target).apps() }
                memory.rememberApps(target, apps)
                val now = apps.firstOrNull { it.packageName == LOCAL_BRIDGE_PACKAGE }
                onWatch.value = now
                if (
                    now?.versionCode == phoneVersion &&
                        (before == null || now.apkPaths != before.apkPaths)
                ) {
                    runCatching { services.deviceSettings.refresh() }
                    return BridgeInstallState.Updated
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // It's still on its way back.
            }
        }
        return BridgeInstallState.Unknown
    }

    /** Everything here is a convenience on top of a finished install, so failures don't count. */
    private suspend fun finishInstall(services: WatchUiServices, adb: WatchToolboxBackend) {
        runCatching { adb.execute(ALLOW_DND_COMMAND) }
        runCatching { services.deviceSettings.refresh() }
        runCatching { adb.app(ToolboxAppRequest(LOCAL_BRIDGE_PACKAGE, ToolboxAppAction.START)) }
        runCatching {
            val apps = adb.apps()
            adb.state.value.target?.let { toolboxMemory(services.toolbox).rememberApps(it, apps) }
            onWatch.value = apps.firstOrNull { it.packageName == LOCAL_BRIDGE_PACKAGE }
        }
    }

    fun openOnWatch() {
        val target = state.value?.adb?.target
        val changed = state.value?.deviceChanged == true
        command(OPEN) { session ->
            if (changed) throw ToolboxException("device_changed")
            reportingTimeouts {
                session.services.toolbox.adb
                    .bind(target ?: throw ToolboxException("device_identity_required"))
                    .app(ToolboxAppRequest(LOCAL_BRIDGE_PACKAGE, ToolboxAppAction.START))
            }
            null
        }
    }

    fun checkAgain() =
        command(CHECK) { session ->
            session.services.deviceSettings.refresh()
            session.services.localHealth.sync()
            null
        }

    companion object {
        const val INSTALL = "bridge_install"
        const val OPEN = "bridge_open"
        const val CHECK = "bridge_check"
        const val CONFIRM_DEVICE = "bridge_confirm_device"

        private val KNOWN_FAILURES =
            setOf(
                "bridge_bundle_missing",
                "not_connected",
                "adb_not_connected",
                "permission_denied",
                "device_identity_required",
                "device_changed",
                "connection_replaced",
            )
        private val UNKNOWN_OUTCOMES = setOf("install_result_unknown", "timeout")
        private const val UPDATE_CHECKS = 4
    }
}

private val BridgeInstallState.isRunning: Boolean
    get() = this is BridgeInstallState.Running || this == BridgeInstallState.Restarting

/**
 * Local Bridge reaches the watch over wireless debugging, without a computer: connect, install,
 * then allow it on the watch. Once it's there with root, it can update itself to this phone app's
 * version without wireless debugging. Every page that needs it leads here.
 */
@Composable
fun LocalBridgeScreen(onBack: () -> Unit, navigate: (Any) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val phoneVersion = rememberPhoneVersion()
    val viewModel = companionViewModel {
        LocalBridgeViewModel(it, phoneVersion, bundled = AssetBundledBridge(app.assets))
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val attemptedInstall by viewModel.install.collectAsStateWithLifecycle()
    val attemptedTarget by viewModel.installationTarget.collectAsStateWithLifecycle()
    val readApp by viewModel.installed.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val current = state ?: LocalBridgeUiState()
    // Over root there's no target to keep to, as the update itself replaces the connection.
    val install =
        attemptedInstall.takeIf { attemptedTarget == null || attemptedTarget == current.adb.target }
            ?: BridgeInstallState.Idle
    val installed = readApp?.takeIf {
        it.target == current.adb.target || it.target == current.root.target
    }
    val adbConnected = current.adbConnected
    val status = current.status
    val appPresent =
        installed != null ||
            install == BridgeInstallState.Installed ||
            install == BridgeInstallState.Updated ||
            current.installsOverRoot ||
            status == LocalBridgeStatus.READY ||
            status == LocalBridgeStatus.NOT_ALLOWED ||
            status == LocalBridgeStatus.UNREACHABLE
    LaunchedEffect(adbConnected, current.adb.target, current.rootConnected, current.root.target) {
        if (adbConnected || current.rootConnected) viewModel.readInstalled()
    }
    PageScaffold(stringResource(R.string.bridge_title), onBack = onBack) { padding ->
        PageColumn(padding) {
            ToolHero(AppIcons.Download, stringResource(R.string.bridge_intro))
            Reveal(current.deviceChanged, spacing = 20.dp) {
                StatusBanner(
                    stringResource(R.string.toolbox_device_changed),
                    tone = BannerTone.Warning,
                )
                OutlinedButton(onClick = { viewModel.confirmDevice(current.adb.target) }) {
                    Text(stringResource(R.string.toolbox_accept_device))
                }
            }
            // Step 3 says the same, but a fresh install, from here or from a computer, is easily
            // taken for a working one, so it's said first.
            Reveal(status.mayBeSwitchedOff && !install.isRunning, spacing = 20.dp) {
                StatusBanner(
                    stringResource(R.string.bridge_turn_on_body),
                    tone = BannerTone.Warning,
                    title = stringResource(R.string.bridge_turn_on_title),
                    actionLabel = stringResource(R.string.bridge_check_again),
                    onAction = {
                        if (LocalBridgeViewModel.CHECK !in pending) viewModel.checkAgain()
                    },
                )
            }
            Reveal(status == LocalBridgeStatus.READY, spacing = 20.dp) {
                StatusBanner(stringResource(R.string.bridge_ready), tone = BannerTone.Success)
                ListGroup {
                    NavigationRow(
                        stringResource(R.string.journal_title),
                        onClick = { navigate(HealthJournalRoute) },
                        icon = AppIcons.Database,
                        summary = stringResource(R.string.journal_summary),
                    )
                    NavigationRow(
                        stringResource(R.string.module_title),
                        onClick = { navigate(WatchModuleRoute) },
                        icon = AppIcons.Extension,
                        summary = stringResource(R.string.module_entry_summary),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StepCard(
                    1,
                    stringResource(R.string.bridge_step_adb_title),
                    done =
                        adbConnected ||
                            current.installsOverRoot ||
                            status == LocalBridgeStatus.READY,
                ) {
                    StepBody(
                        when {
                            adbConnected ->
                                stringResource(
                                    R.string.adb_connected_to,
                                    current.adb.device.orEmpty(),
                                )
                            current.installsOverRoot ->
                                stringResource(R.string.bridge_step_adb_not_needed)
                            else -> stringResource(R.string.bridge_step_adb_body)
                        }
                    )
                    FilledTonalButton(onClick = { navigate(AdbConnectRoute) }) {
                        Text(stringResource(R.string.bridge_step_adb_action))
                    }
                }
                StepCard(
                    2,
                    stringResource(R.string.bridge_step_install_title),
                    done = appPresent && !install.isRunning && !current.rootOutdated,
                ) {
                    InstallStep(
                        install,
                        installed,
                        update =
                            current.rootOutdated ||
                                installed?.let { it.versionCode < phoneVersion } == true,
                        outdatedOverRoot = current.rootOutdated && !adbConnected,
                        canInstall = current.canInstall,
                        busy = LocalBridgeViewModel.INSTALL in pending,
                        onInstall = viewModel::install,
                    )
                }
                StepCard(
                    3,
                    stringResource(R.string.bridge_step_allow_title),
                    done = status == LocalBridgeStatus.READY,
                ) {
                    StepBody(stringResource(R.string.bridge_step_allow_body))
                    Reveal(adbConnected) {
                        FilledTonalButton(
                            onClick = viewModel::openOnWatch,
                            enabled = appPresent && LocalBridgeViewModel.OPEN !in pending,
                        ) {
                            Text(stringResource(R.string.bridge_open_on_watch))
                        }
                    }
                    OutlinedButton(
                        onClick = viewModel::checkAgain,
                        enabled = LocalBridgeViewModel.CHECK !in pending,
                    ) {
                        Text(stringResource(R.string.bridge_check_again))
                    }
                }
            }
            FootnoteText(stringResource(R.string.bridge_footnote))
        }
    }
}

@Composable
private fun InstallStep(
    install: BridgeInstallState,
    installed: ToolboxApp?,
    update: Boolean,
    outdatedOverRoot: Boolean,
    canInstall: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
) {
    StepBody(
        when {
            installed != null ->
                stringResource(
                    R.string.bridge_step_install_present,
                    installed.versionName ?: installed.versionCode.toString(),
                )
            outdatedOverRoot -> stringResource(R.string.bridge_step_update_root_body)
            else -> stringResource(R.string.bridge_step_install_body)
        }
    )
    FadeSwitch(install, key = { it::class }) { shown ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = GrowingGaps(8.dp)) {
            when (shown) {
                is BridgeInstallState.Running -> {
                    ProgressBar(shown.fraction)
                    FootnoteText(stringResource(R.string.bridge_installing))
                }
                BridgeInstallState.Restarting -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    FootnoteText(stringResource(R.string.bridge_restarting))
                }
                BridgeInstallState.Updated ->
                    StatusBanner(stringResource(R.string.bridge_updated), tone = BannerTone.Success)
                BridgeInstallState.Installed ->
                    StatusBanner(
                        stringResource(R.string.bridge_installed),
                        tone = BannerTone.Success,
                    )
                is BridgeInstallState.Refused -> {
                    StatusBanner(stringResource(R.string.bridge_refused), tone = BannerTone.Error)
                    shown.output?.let { CommandOutput(it) }
                }
                is BridgeInstallState.Failed ->
                    StatusBanner(shown.message.asString(), tone = BannerTone.Error)
                BridgeInstallState.Unknown ->
                    StatusBanner(
                        stringResource(R.string.installer_result_unknown_body),
                        tone = BannerTone.Warning,
                        title = stringResource(R.string.bridge_unknown_title),
                    )
                BridgeInstallState.Idle -> Unit
            }
        }
    }
    Reveal(!install.isRunning) {
        val label =
            when {
                update -> R.string.bridge_update
                installed == null -> R.string.bridge_install
                else -> R.string.bridge_reinstall
            }
        if (installed == null || update) {
            Button(onClick = onInstall, enabled = canInstall && !busy) {
                Text(stringResource(label))
            }
        } else {
            OutlinedButton(onClick = onInstall, enabled = canInstall && !busy) {
                Text(stringResource(label))
            }
        }
    }
}
