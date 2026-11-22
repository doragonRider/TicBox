package dev.ticbox.companion.ui.bridge

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.HealthModuleBundle
import dev.ticbox.companion.api.HealthModulePhase
import dev.ticbox.companion.api.NotificationAlertPatchMode
import dev.ticbox.companion.api.NotificationAlertPatchPhase
import dev.ticbox.companion.api.NotificationAlertPatchState
import dev.ticbox.companion.api.ToolboxApp
import dev.ticbox.companion.api.WatchModuleHostPhase
import dev.ticbox.companion.api.WatchModuleHostSnapshot
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.asString
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.ActionIcon
import dev.ticbox.companion.ui.designsystem.AppIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.BannerTone
import dev.ticbox.companion.ui.designsystem.EmptyState
import dev.ticbox.companion.ui.designsystem.FadeSwitch
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.GrowingGaps
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.ProgressBar
import dev.ticbox.companion.ui.designsystem.Reveal
import dev.ticbox.companion.ui.designsystem.RevealValue
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.designsystem.SwitchRow
import dev.ticbox.companion.ui.navigation.HealthMonitoringRoute
import dev.ticbox.companion.ui.navigation.LocalBridgeRoute
import dev.ticbox.companion.ui.navigation.NotificationsRoute
import dev.ticbox.companion.ui.toolbox.ConfirmDialog
import dev.ticbox.companion.ui.toolbox.StepBody
import dev.ticbox.companion.ui.toolbox.ToolHero
import dev.ticbox.companion.ui.toolbox.fileSizeText

/** Codes that the Local Bridge page can do something about. */
private val BRIDGE_CODES =
    setOf(
        "module_bridge_update_required",
        "agent_version_mismatch",
        "bridge_update_needs_adb",
        "device_changed",
        "device_identity_required",
        "bridge_unavailable",
        "bridge_not_connected",
        "bridge_auth_failed",
        "bridge_connect_timeout",
        "root_required",
        "module_root_required",
    )

/**
 * A checklist of what the watch says and one button for everything still missing, so that a single
 * tap and a single restart get from nothing to working.
 */
@Composable
fun WatchModuleScreen(onBack: () -> Unit, navigate: (Any) -> Unit) {
    val viewModel = companionViewModel { WatchModuleViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val current = state
    var alertsChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var confirming by remember { mutableStateOf<ModuleSetupRequest?>(null) }
    val phoneVersion = rememberPhoneVersion()
    LaunchedEffect(
        current?.module?.phase,
        current?.bridge,
        current?.setup?.step,
        current?.bridgeOnWatch?.versionCode,
        // Local Bridge can change during a read, which then has to be followed by another.
        WatchModuleViewModel.READ in pending,
    ) {
        viewModel.readOnEntry()
    }
    val working = current?.setup?.step != null
    val busy =
        working ||
            current?.module?.phase in
                setOf(HealthModulePhase.READING, HealthModulePhase.INSTALLING) ||
            current?.patch?.phase == NotificationAlertPatchPhase.BUSY ||
            current?.host?.phase == WatchModuleHostPhase.BUSY ||
            WatchModuleViewModel.READ in pending ||
            WatchModuleViewModel.SETUP in pending ||
            WatchModuleViewModel.ALERTS in pending
    PageScaffold(
        stringResource(R.string.module_title),
        onBack = onBack,
        actions = {
            ActionIcon(
                AppIcons.Refresh,
                stringResource(R.string.module_check_again),
                viewModel::read,
                enabled = current?.linkReady == true && !busy,
            )
        },
    ) { padding ->
        if (current == null) {
            EmptyState(
                AppIcons.Extension,
                stringResource(R.string.no_watch_title),
                stringResource(R.string.no_watch_body),
                Modifier.padding(padding),
            )
            return@PageScaffold
        }
        val stage = moduleStage(current.module)
        val alertStage = alertPatchStage(current.patch)
        // Only a restart changes these, so the page keeps the last answer through a read and
        // through one that fails. What it offers goes by the two above, which don't.
        val shownStage = lastKnownStage(current.module) ?: stage
        val shownAlerts = lastKnownAlertStage(current.patch)
        val host = current.host.snapshot
        val alertsSupported = host?.systemUiSupported != false
        // On unless turned off, which is the one choice the watch keeps.
        val wantAlerts =
            alertsSupported && (alertsChoice ?: current.patch.reading()?.optedIn ?: true)
        val shownWant = alertsSupported && (alertsChoice ?: current.patch.snapshot?.optedIn ?: true)
        val olderBridge = current.bridgeOnWatch?.takeIf { it.versionCode < phoneVersion }
        // It goes first, because this plan is worked out from what it reads.
        val plan =
            if (olderBridge != null) ModulePlan()
            else
                modulePlan(
                    stage,
                    current.host.reading()?.zygiskOn,
                    alertStage,
                    wantAlerts,
                    current.setup.uiRestartFailed,
                )
        val problem = current.setup.problem ?: current.module.lastOperation?.reason
        val canAct = current.linkReady && current.peer != null && !busy
        val answered = stage != ModuleStage.CHECKING && stage != ModuleStage.UNREACHED
        PageColumn(padding) {
            ToolHero(AppIcons.Extension, stringResource(R.string.module_intro))
            // During a restart it's supposed to be gone, and the progress below says so.
            Reveal(!current.linkReady && !working) {
                StatusBanner(
                    stringResource(R.string.error_not_connected),
                    tone = BannerTone.Warning,
                )
            }
            RevealValue(problem) { code ->
                StatusBanner(
                    moduleMessage(code).asString(),
                    tone = BannerTone.Error,
                    actionLabel =
                        stringResource(R.string.module_open_bridge).takeIf { code in BRIDGE_CODES },
                    onAction =
                        if (code in BRIDGE_CODES) {
                            { navigate(LocalBridgeRoute) }
                        } else null,
                )
            }
            FloatingBusyBar(busy && !working)
            ListGroup {
                BridgeRow(
                    current,
                    stage,
                    olderBridge,
                    enabled = canAct,
                    onOpenBridge = { navigate(LocalBridgeRoute) },
                    onCheck = viewModel::read,
                )
                ModuleRow(current, shownStage)
                ZygiskRow(host, answered)
                HealthRow(
                    shownStage,
                    host,
                    bridgeOutdated = olderBridge != null,
                    onOpenMonitoring = { navigate(HealthMonitoringRoute) },
                )
            }
            AlertsCard(
                current.patch,
                alertStage,
                shownAlerts,
                host,
                wantAlerts,
                shownWant,
                uiRestart = plan.restartUi,
                switchEnabled =
                    canAct && answered && stage != ModuleStage.BLOCKED && alertsSupported,
                enabled = canAct,
                onToggle = { want ->
                    alertsChoice = want
                    // Without a copy on the watch, installing it is what keeps it off.
                    if (
                        !want &&
                            current.patch.snapshot?.optedIn == true &&
                            stage != ModuleStage.NOT_INSTALLED
                    )
                        viewModel.turnOffAlerts()
                },
                onApply = viewModel::applyAlerts,
                onTurnOff = viewModel::turnOffAlerts,
                onOpenNotifications = { navigate(NotificationsRoute) },
            )
            SetupActions(
                current.setup,
                plan,
                stage,
                enabled = canAct,
                answered = answered && olderBridge == null,
                onCarryOut = {
                    current.peer?.let { peer ->
                        val request = ModuleSetupRequest(plan, peer)
                        if (plan.restarts) confirming = request else viewModel.carryOut(request)
                    }
                },
                onRestart = {
                    current.peer?.let {
                        confirming = ModuleSetupRequest(ModulePlan(restart = true), it)
                    }
                },
            )
            FootnoteText(stringResource(R.string.module_note))
        }
    }
    confirming?.let { request ->
        val plan = request.plan
        ConfirmDialog(
            title = stringResource(R.string.module_confirm_title, planLabel(plan)),
            body = planLines(plan, current?.module?.bundle),
            confirmLabel = stringResource(R.string.module_confirm_start),
            onConfirm = {
                viewModel.carryOut(request)
                confirming = null
            },
            onDismiss = { confirming = null },
        )
    }
}

@Composable
private fun planLabel(plan: ModulePlan): String =
    stringResource(
        when {
            plan.install -> R.string.module_action_install
            plan.update -> R.string.module_action_update
            plan.restart && (plan.zygisk || plan.alerts) -> R.string.module_action_turn_on
            plan.restart -> R.string.module_action_restart
            plan.restartUi && plan.alerts -> R.string.module_action_turn_on_ui
            plan.restartUi -> R.string.module_action_restart_ui
            else -> R.string.action_turn_on
        }
    )

@Composable
private fun planLines(plan: ModulePlan, bundle: HealthModuleBundle?): String {
    val context = LocalContext.current
    val version = bundle?.versionName ?: stringResource(R.string.module_version_unknown)
    val size = bundle?.bytes?.let(context::fileSizeText).orEmpty()
    return buildList {
            if (plan.install) add(stringResource(R.string.module_plan_install, version, size))
            if (plan.update) add(stringResource(R.string.module_plan_update, version, size))
            if (plan.zygisk) add(stringResource(R.string.module_plan_zygisk))
            if (plan.alerts) add(stringResource(R.string.module_plan_alerts))
            if (plan.restart) add(stringResource(R.string.module_plan_restart))
            if (plan.restartUi) add(stringResource(R.string.module_plan_restart_ui))
        }
        .joinToString("\n") { "• $it" }
}

/** Sits in the gap above what follows, so that a read starting or ending moves nothing. */
@Composable
private fun FloatingBusyBar(busy: Boolean) {
    Box(Modifier.fillMaxWidth().height(0.dp).offset(y = (-10).dp)) {
        AnimatedVisibility(busy, enter = fadeIn(), exit = fadeOut()) {
            LinearProgressIndicator(Modifier.fillMaxWidth().requiredHeight(4.dp))
        }
    }
}

private sealed interface SetupSlot {
    data class Working(val step: ModuleSetupStep, val fraction: Float?) : SetupSlot

    data class Planned(val plan: ModulePlan) : SetupSlot

    data object Restart : SetupSlot

    data object Empty : SetupSlot
}

@Composable
private fun SetupActions(
    setup: ModuleSetupState,
    plan: ModulePlan,
    stage: ModuleStage,
    enabled: Boolean,
    answered: Boolean,
    onCarryOut: () -> Unit,
    onRestart: () -> Unit,
) {
    val step = setup.step
    val slot =
        when {
            step != null -> SetupSlot.Working(step, setup.fraction)
            !plan.isEmpty -> SetupSlot.Planned(plan)
            answered && stage != ModuleStage.BLOCKED -> SetupSlot.Restart
            else -> SetupSlot.Empty
        }
    // One slot, as two of these trading places would each bring a gap, which moves what's below.
    FadeSwitch(slot, Modifier.fillMaxWidth(), key = { it::class }) { shown ->
        when (shown) {
            is SetupSlot.Working ->
                Column(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                    StepBody(stepText(shown.step))
                    ProgressBar(shown.fraction)
                }
            is SetupSlot.Planned ->
                Button(
                    onClick = onCarryOut,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("module_setup"),
                ) {
                    Text(planLabel(shown.plan))
                }
            // Rarely needed, but when it is it beats finding the watch's own power menu.
            SetupSlot.Restart ->
                OutlinedButton(
                    onClick = onRestart,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("module_restart"),
                ) {
                    AppIcon(AppIcons.Restart, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.module_action_restart))
                }
            SetupSlot.Empty -> Unit
        }
    }
}

@Composable
private fun stepText(step: ModuleSetupStep): String =
    stringResource(
        when (step) {
            ModuleSetupStep.SENDING -> R.string.module_uploading
            ModuleSetupStep.INSTALLING -> R.string.module_installing
            ModuleSetupStep.TURNING_ON -> R.string.module_turning_on
            ModuleSetupStep.RESTARTING -> R.string.module_restarting
            ModuleSetupStep.RESTARTING_UI -> R.string.module_restarting_ui
            ModuleSetupStep.RECONNECTING -> R.string.module_reconnecting
            ModuleSetupStep.CONFIRMING -> R.string.module_confirming
        }
    )

private enum class CheckMark {
    DONE,
    TODO,
    PROBLEM,
}

@Composable
private fun CheckBadge(mark: CheckMark) {
    val colors = MaterialTheme.colorScheme
    Surface(
        Modifier.size(24.dp),
        shape = CircleShape,
        color =
            animateColorAsState(
                    when (mark) {
                        CheckMark.DONE -> colors.primary
                        CheckMark.TODO -> colors.surfaceContainerHighest
                        CheckMark.PROBLEM -> colors.errorContainer
                    },
                    label = "check_badge",
                )
                .value,
    ) {
        Crossfade(mark, label = "check_mark") { shown ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (shown) {
                    CheckMark.DONE ->
                        AppIcon(
                            AppIcons.Check,
                            stringResource(R.string.toolbox_step_done),
                            Modifier.size(16.dp),
                        )
                    CheckMark.PROBLEM ->
                        AppIcon(AppIcons.Warning, contentDescription = null, Modifier.size(16.dp))
                    CheckMark.TODO -> Unit
                }
            }
        }
    }
}

@Composable
private fun CheckRow(
    mark: CheckMark,
    title: String,
    body: String,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CheckBadge(mark)
        Column(Modifier.weight(1f).animateContentSize(), verticalArrangement = GrowingGaps(8.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                StepBody(body)
            }
            actions()
        }
    }
}

@Composable
private fun BridgeRow(
    state: WatchModuleUiState,
    stage: ModuleStage,
    olderBridge: ToolboxApp?,
    enabled: Boolean,
    onOpenBridge: () -> Unit,
    onCheck: () -> Unit,
) {
    val reached = stage != ModuleStage.CHECKING && stage != ModuleStage.UNREACHED
    val magisk = state.module.snapshot?.magiskVersionCode
    CheckRow(
        when {
            olderBridge != null -> CheckMark.PROBLEM
            reached -> CheckMark.DONE
            stage == ModuleStage.UNREACHED -> CheckMark.PROBLEM
            else -> CheckMark.TODO
        },
        stringResource(R.string.module_step_bridge),
        when {
            olderBridge != null ->
                stringResource(
                    R.string.module_bridge_outdated,
                    olderBridge.versionName ?: olderBridge.versionCode.toString(),
                )
            !reached -> stringResource(R.string.module_bridge_body)
            magisk != null -> stringResource(R.string.module_bridge_connected_magisk, magisk)
            else -> stringResource(R.string.module_bridge_connected)
        },
    ) {
        Reveal(olderBridge != null) {
            Button(onClick = onOpenBridge, modifier = Modifier.testTag("module_update_bridge")) {
                Text(stringResource(R.string.module_update_bridge))
            }
        }
        Reveal(!reached && state.bridge.needsSetup) {
            OutlinedButton(onClick = onOpenBridge) {
                Text(stringResource(R.string.module_open_bridge))
            }
        }
        Reveal(stage == ModuleStage.UNREACHED && !state.bridge.needsSetup) {
            OutlinedButton(onClick = onCheck, enabled = enabled) {
                Text(stringResource(R.string.module_check_again))
            }
        }
    }
}

@Composable
private fun ModuleRow(state: WatchModuleUiState, stage: ModuleStage) {
    val version =
        state.module.bundle?.versionName ?: stringResource(R.string.module_version_unknown)
    val onWatch =
        (state.module.snapshot?.staged ?: state.module.snapshot?.installed)?.versionName
            ?: stringResource(R.string.module_version_unknown)
    CheckRow(
        when (stage) {
            ModuleStage.NOT_RUNNING,
            ModuleStage.RUNNING -> CheckMark.DONE
            ModuleStage.BLOCKED -> CheckMark.PROBLEM
            else -> CheckMark.TODO
        },
        stringResource(R.string.module_step_install),
        when (stage) {
            ModuleStage.UPDATE ->
                // The version only moves at releases, and every build carries its own module.
                if (onWatch == version) {
                    stringResource(R.string.module_update_same_version, onWatch)
                } else {
                    stringResource(R.string.module_update_body, onWatch, version)
                }
            ModuleStage.CHECKING,
            ModuleStage.UNREACHED -> stringResource(R.string.module_not_read)
            ModuleStage.REBOOT -> stringResource(R.string.module_staged, onWatch)
            ModuleStage.BLOCKED -> stringResource(R.string.module_error_disabled)
            ModuleStage.NOT_RUNNING,
            ModuleStage.RUNNING -> stringResource(R.string.module_active, onWatch)
            else -> stringResource(R.string.module_not_on_watch)
        },
    )
}

@Composable
private fun ZygiskRow(host: WatchModuleHostSnapshot?, answered: Boolean) {
    CheckRow(
        if (host?.zygiskOn == true) CheckMark.DONE else CheckMark.TODO,
        stringResource(R.string.module_step_zygisk),
        stringResource(
            when {
                host?.zygiskOn == true -> R.string.module_zygisk_on
                host?.zygiskOn == false -> R.string.module_zygisk_off
                answered -> R.string.module_zygisk_unknown
                else -> R.string.module_not_read
            }
        ),
    )
}

@Composable
private fun HealthRow(
    stage: ModuleStage,
    host: WatchModuleHostSnapshot?,
    bridgeOutdated: Boolean,
    onOpenMonitoring: () -> Unit,
) {
    CheckRow(
        when (stage) {
            ModuleStage.RUNNING -> CheckMark.DONE
            ModuleStage.NOT_RUNNING -> if (bridgeOutdated) CheckMark.TODO else CheckMark.PROBLEM
            else -> CheckMark.TODO
        },
        stringResource(R.string.module_step_running),
        stringResource(
            when {
                stage == ModuleStage.RUNNING -> R.string.module_running
                stage == ModuleStage.CHECKING || stage == ModuleStage.UNREACHED ->
                    R.string.module_not_read
                stage != ModuleStage.NOT_RUNNING -> R.string.module_running_later
                bridgeOutdated -> R.string.module_running_after_bridge
                host?.zygiskOn == false -> R.string.module_not_running_zygisk
                host?.healthOnDenyList == true -> R.string.module_not_running_denylist
                host != null && host.healthSupported == null -> R.string.module_not_running_missing
                host?.healthSupported == false -> R.string.module_not_running_version
                host?.healthRunning == false -> R.string.module_not_running_stopped
                else -> R.string.module_not_running
            }
        ),
    ) {
        Reveal(stage == ModuleStage.RUNNING) {
            OutlinedButton(onClick = onOpenMonitoring) {
                Text(stringResource(R.string.module_open_monitoring))
            }
        }
    }
}

@Composable
private fun AlertsCard(
    patch: NotificationAlertPatchState,
    stage: AlertPatchStage,
    shown: AlertPatchStage,
    host: WatchModuleHostSnapshot?,
    wantAlerts: Boolean,
    shownWant: Boolean,
    uiRestart: Boolean,
    switchEnabled: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onApply: () -> Unit,
    onTurnOff: () -> Unit,
    onOpenNotifications: () -> Unit,
) {
    ListGroup {
        SwitchRow(
            stringResource(R.string.module_step_alerts),
            checked = shownWant,
            onCheckedChange = onToggle,
            modifier = Modifier.testTag("module_alerts_switch"),
            summary = alertText(shown, patch, host, shownWant, uiRestart),
            enabled = switchEnabled,
        )
        val padded = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
        Reveal(stage == AlertPatchStage.READY_TO_APPLY && wantAlerts, padded) {
            Button(
                onClick = onApply,
                enabled = enabled,
                modifier = Modifier.testTag("module_alerts_apply"),
            ) {
                Text(stringResource(R.string.module_alerts_apply))
            }
        }
        // Only a confirmed stop ends this, so stopping can be tried again from here.
        Reveal(stage == AlertPatchStage.STOP_PENDING && !wantAlerts, padded) {
            OutlinedButton(
                onClick = onTurnOff,
                enabled = enabled,
                modifier = Modifier.testTag("module_alerts_off"),
            ) {
                Text(stringResource(R.string.action_turn_off))
            }
        }
        Reveal(shown == AlertPatchStage.APPLIED && shownWant, padded) {
            FilledTonalButton(onClick = onOpenNotifications) {
                Text(stringResource(R.string.module_alerts_open))
            }
        }
        RevealValue(patch.reason, padded) { code ->
            Text(
                moduleMessage(code).asString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun alertText(
    stage: AlertPatchStage,
    patch: NotificationAlertPatchState,
    host: WatchModuleHostSnapshot?,
    wantAlerts: Boolean,
    uiRestart: Boolean,
): String =
    stringResource(
        when {
            host?.systemUiSupported == false -> R.string.module_alerts_unsupported
            else ->
                when (stage) {
                    AlertPatchStage.UNKNOWN,
                    AlertPatchStage.OFF ->
                        if (wantAlerts) R.string.module_alerts_pending
                        else R.string.module_alerts_off
                    AlertPatchStage.REBOOT_TO_LOAD ->
                        if (uiRestart) R.string.module_alerts_ui_restart
                        else R.string.module_alerts_reboot
                    AlertPatchStage.READY_TO_APPLY ->
                        if (patch.snapshot?.mode == NotificationAlertPatchMode.PROBE)
                            R.string.module_alerts_probe
                        else R.string.module_alerts_ready
                    AlertPatchStage.APPLIED -> R.string.module_alerts_applied
                    AlertPatchStage.NOT_LOADED ->
                        if (host?.zygiskOn == false) R.string.module_not_running_zygisk
                        else R.string.module_alerts_not_loaded
                    AlertPatchStage.NOT_HOOKED -> R.string.module_alerts_not_hooked
                    AlertPatchStage.STOP_PENDING ->
                        if (wantAlerts) R.string.module_alerts_pending
                        else R.string.module_alerts_stop_pending
                    AlertPatchStage.REBOOT_TO_UNLOAD ->
                        if (wantAlerts) R.string.module_alerts_pending
                        else R.string.module_alerts_unload
                }
        }
    )
