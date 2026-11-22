package dev.ticbox.companion.ui.bridge

import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.ConnectionState
import dev.ticbox.companion.api.HealthModuleOutcome
import dev.ticbox.companion.api.HealthModulePhase
import dev.ticbox.companion.api.HealthModuleSnapshot
import dev.ticbox.companion.api.HealthModuleState
import dev.ticbox.companion.api.NotificationAlertPatchMode
import dev.ticbox.companion.api.NotificationAlertPatchPhase
import dev.ticbox.companion.api.NotificationAlertPatchSnapshot
import dev.ticbox.companion.api.NotificationAlertPatchState
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.api.ToolboxApp
import dev.ticbox.companion.api.ToolboxException
import dev.ticbox.companion.api.WatchModuleHostPhase
import dev.ticbox.companion.api.WatchModuleHostState
import dev.ticbox.companion.api.WatchModuleTarget
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.UiText
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.session.WatchSession
import dev.ticbox.companion.ui.toolbox.fractionOf
import dev.ticbox.companion.ui.toolbox.toolboxMemory
import dev.ticbox.companion.ui.toolbox.toolboxMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Where the module stands on the watch, as far as the last read can say. */
internal enum class ModuleStage {
    /** Nothing read yet, and nothing has gone wrong. */
    CHECKING,

    /** The watch couldn't be asked. */
    UNREACHED,
    NOT_INSTALLED,
    UPDATE,

    /** A copy of this build is waiting in Magisk for a restart. */
    REBOOT,

    /** Turned off or marked for removal in Magisk, where only the user may change that. */
    BLOCKED,

    /** This build is active on disk, but the health app hasn't been seen running it. */
    NOT_RUNNING,
    RUNNING,
}

internal fun moduleStage(state: HealthModuleState): ModuleStage {
    // An older reading says nothing once asking has failed, and only a new one can settle it.
    if (!state.current && state.lastOperation?.reason != null) return ModuleStage.UNREACHED
    return lastKnownStage(state) ?: ModuleStage.CHECKING
}

/**
 * What the watch last said, for the checklist to show. Only a restart changes it, and a restart
 * takes the link down, which clears the reading. Nothing is offered on this: see [moduleStage].
 */
internal fun lastKnownStage(state: HealthModuleState): ModuleStage? {
    val snapshot = state.snapshot ?: return null
    val installed = snapshot.installed
    val staged = snapshot.staged
    return when {
        installed?.disabled == true || installed?.removalPending == true -> ModuleStage.BLOCKED
        staged != null -> if (staged.matchesBundled) ModuleStage.REBOOT else ModuleStage.UPDATE
        installed == null -> ModuleStage.NOT_INSTALLED
        !installed.matchesBundled -> ModuleStage.UPDATE
        snapshot.healthRuntimeBuildId == state.bundle?.buildId -> ModuleStage.RUNNING
        else -> ModuleStage.NOT_RUNNING
    }
}

/** Where the optional notification extension stands. The opt-in always comes before loading. */
internal enum class AlertPatchStage {
    UNKNOWN,
    OFF,
    REBOOT_TO_LOAD,

    /** Loaded and passive, or still on a test mode from the command line. */
    READY_TO_APPLY,
    APPLIED,

    /** A restart has passed since opting in, and SystemUI isn't answering. */
    NOT_LOADED,

    /** It loaded but couldn't attach, so the watch alerts the way it always did. */
    NOT_HOOKED,

    /** The marker is gone, but this process has not confirmed a passive mode. */
    STOP_PENDING,
    REBOOT_TO_UNLOAD,
}

/** Holds still during an operation, and an older reading says nothing after a failed one. */
internal fun NotificationAlertPatchState.reading() = snapshot?.takeIf {
    current || phase == NotificationAlertPatchPhase.BUSY
}

internal fun WatchModuleHostState.reading() = snapshot?.takeIf {
    current || phase == WatchModuleHostPhase.BUSY
}

internal fun alertPatchStage(state: NotificationAlertPatchState): AlertPatchStage =
    alertStageOf(state.reading(), state.reason)

/**
 * What it last said, for the card to show. As with [lastKnownStage], nothing is offered on this.
 */
internal fun lastKnownAlertStage(state: NotificationAlertPatchState): AlertPatchStage =
    alertStageOf(state.snapshot, state.reason.takeIf { state.current })

private fun alertStageOf(
    snapshot: NotificationAlertPatchSnapshot?,
    reason: String?,
): AlertPatchStage {
    if (snapshot == null) return AlertPatchStage.UNKNOWN
    return when {
        !snapshot.optedIn ->
            when {
                reason != null ||
                    snapshot.running &&
                        snapshot.mode !in
                            setOf(
                                NotificationAlertPatchMode.OFF,
                                NotificationAlertPatchMode.OBSERVE,
                            ) -> AlertPatchStage.STOP_PENDING
                snapshot.running -> AlertPatchStage.REBOOT_TO_UNLOAD
                else -> AlertPatchStage.OFF
            }
        snapshot.running && (!snapshot.hooked || snapshot.issue != null) ->
            AlertPatchStage.NOT_HOOKED
        snapshot.running ->
            if (snapshot.mode == NotificationAlertPatchMode.ALL) AlertPatchStage.APPLIED
            else AlertPatchStage.READY_TO_APPLY
        snapshot.optInSurvivedReboot -> AlertPatchStage.NOT_LOADED
        else -> AlertPatchStage.REBOOT_TO_LOAD
    }
}

/** Everything still between here and a working module, done in the one go the page offers. */
internal data class ModulePlan(
    val install: Boolean = false,
    val update: Boolean = false,
    val zygisk: Boolean = false,
    val alerts: Boolean = false,
    val restart: Boolean = false,
    /** The system UI alone starts again, which is all the notification extension needs. */
    val restartUi: Boolean = false,
) {
    val restarts: Boolean
        get() = restart || restartUi

    val isEmpty: Boolean
        get() = !install && !update && !zygisk && !alerts && !restarts
}

internal fun modulePlan(
    stage: ModuleStage,
    zygiskOn: Boolean?,
    alertStage: AlertPatchStage,
    wantAlerts: Boolean,
    uiRestartFailed: Boolean = false,
): ModulePlan {
    val install = stage == ModuleStage.NOT_INSTALLED
    val update = stage == ModuleStage.UPDATE
    val onWatch = stage in ON_WATCH
    if (!install && !update && !onWatch) return ModulePlan()
    val zygisk = stage != ModuleStage.RUNNING && zygiskOn != true
    val alerts =
        wantAlerts &&
            (alertStage == AlertPatchStage.UNKNOWN ||
                alertStage == AlertPatchStage.OFF ||
                alertStage == AlertPatchStage.STOP_PENDING ||
                alertStage == AlertPatchStage.REBOOT_TO_UNLOAD)
    val watchRestart = install || update || zygisk || stage == ModuleStage.REBOOT
    // Zygisk looks for the extension's marker whenever the system UI starts.
    val extensionRestart =
        alertStage == AlertPatchStage.REBOOT_TO_LOAD ||
            // The extension is still loaded after turning it off, so back on needs no restart.
            (alerts && alertStage != AlertPatchStage.REBOOT_TO_UNLOAD) ||
            (!wantAlerts &&
                (alertStage == AlertPatchStage.REBOOT_TO_UNLOAD ||
                    alertStage == AlertPatchStage.STOP_PENDING))
    val uiAlone =
        extensionRestart &&
            !watchRestart &&
            !uiRestartFailed &&
            (stage == ModuleStage.RUNNING || stage == ModuleStage.NOT_RUNNING)
    return ModulePlan(
        install = install,
        update = update,
        zygisk = zygisk,
        alerts = alerts,
        restart = watchRestart || (extensionRestart && !uiAlone),
        restartUi = uiAlone,
    )
}

internal data class ModuleSetupRequest(val plan: ModulePlan, val peer: PeerAddress)

private val ON_WATCH = setOf(ModuleStage.REBOOT, ModuleStage.NOT_RUNNING, ModuleStage.RUNNING)

enum class ModuleSetupStep {
    SENDING,
    INSTALLING,
    TURNING_ON,
    RESTARTING,
    RESTARTING_UI,
    RECONNECTING,
    CONFIRMING,
}

/** Kept with the session, because the setup it describes carries on after leaving the page. */
data class ModuleSetupState(
    val step: ModuleSetupStep? = null,
    val fraction: Float? = null,
    val problem: String? = null,
    /** The notification control was asked for and hasn't taken over from the default mode yet. */
    val applyAlerts: Boolean = false,
    /** The watch whose explicit setup confirmation authorized the remaining automatic step. */
    val targetPeer: PeerAddress? = null,
    /** The Local Bridge version behind the latest reading. */
    val bridgeAtRead: Long? = null,
    /** Restarting the system UI didn't get the extension loaded, so the watch is what's left. */
    val uiRestartFailed: Boolean = false,
)

/**
 * The extension's marker goes wherever the next boot looks for it, which takes a copy on the watch.
 */
private val HealthModuleSnapshot?.onWatch: Boolean
    get() =
        this?.staged != null || this?.installed?.let { !it.disabled && !it.removalPending } == true

internal fun moduleMessage(code: String): UiText =
    when (code) {
        "module_root_required" -> uiText(R.string.toolbox_error_root)
        "module_bridge_update_required" -> uiText(R.string.module_error_bridge_update)
        "module_device_unsupported" -> uiText(R.string.module_error_device)
        "magisk_unavailable" -> uiText(R.string.module_error_magisk_missing)
        "magisk_too_old" -> uiText(R.string.module_error_magisk_old)
        "module_disabled_or_removing" -> uiText(R.string.module_error_disabled)
        "module_id_conflict" -> uiText(R.string.module_error_conflict)
        "module_busy" -> uiText(R.string.module_error_busy)
        "module_unavailable",
        "module_payload_unavailable",
        "module_payload_invalid" -> uiText(R.string.module_error_payload)
        "magisk_install_failed" -> uiText(R.string.module_error_install_failed)
        "module_install_unconfirmed",
        "module_operation_interrupted" -> uiText(R.string.module_error_unconfirmed)
        "module_operation_failed",
        "notification_alert_status_unreadable" -> uiText(R.string.module_error_read)
        "module_zygisk_failed" -> uiText(R.string.module_error_zygisk)
        "module_restart_failed" -> uiText(R.string.module_error_restart)
        "module_ui_restart_failed" -> uiText(R.string.module_error_ui_restart)
        "module_ui_restart_not_loaded" -> uiText(R.string.module_error_ui_restart_not_loaded)
        "notification_alert_module_missing" -> uiText(R.string.module_error_not_active)
        "notification_alert_contract_unavailable",
        "notification_alert_control_unavailable" -> uiText(R.string.module_error_alert_control)
        "mode_write_unconfirmed",
        "notification_alert_write_failed",
        "notification_alert_unconfirmed" -> uiText(R.string.module_error_alert_write)
        else -> toolboxMessage(code)
    }

data class WatchModuleUiState(
    val module: HealthModuleState,
    val patch: NotificationAlertPatchState,
    val host: WatchModuleHostState,
    val setup: ModuleSetupState,
    val linkReady: Boolean,
    val bridge: LocalBridgeStatus,
    val peer: PeerAddress?,
    val bridgeOnWatch: ToolboxApp?,
) {
    /** What an older Local Bridge said about the module doesn't outlast it. */
    val bridgeChanged: Boolean
        get() =
            setup.bridgeAtRead != null &&
                bridgeOnWatch != null &&
                bridgeOnWatch.versionCode != setup.bridgeAtRead
}

private fun WatchSession.moduleUiState(): WatchModuleUiState {
    val connection = client.state.value.connection
    return WatchModuleUiState(
        services.healthModule.state.value,
        services.notificationAlertPatch.state.value,
        services.watchModuleHost.state.value,
        services.moduleSetup.value,
        connection.phase == ConnectionPhase.LINK_READY,
        localBridgeStatus(),
        connection.peer,
        toolboxMemory(services.toolbox).bridgeOnWatch.value,
    )
}

private fun WatchSession.linkReady() =
    client.state.value.connection.phase == ConnectionPhase.LINK_READY

class WatchModuleViewModel(dependencies: CompanionDependencies) : SessionViewModel(dependencies) {
    val state: StateFlow<WatchModuleUiState?> =
        sessionState(
            current = { it.moduleUiState() },
            updates = { session ->
                merge<Any?>(
                        session.services.healthModule.state,
                        session.services.notificationAlertPatch.state,
                        session.services.watchModuleHost.state,
                        session.services.moduleSetup,
                        session.client.state,
                        session.services.localHealth.state,
                        session.services.deviceSettings.state,
                        toolboxMemory(session.services.toolbox).bridgeOnWatch,
                    )
                    .map { session.moduleUiState() }
                    .distinctUntilChanged() as Flow<WatchModuleUiState>
            },
        )

    init {
        // Offline too: what's in the phone doesn't take a watch to tell.
        command(BUNDLE) { session ->
            try {
                session.services.healthModule.bundled()
            } catch (_: ToolboxException) {
                // Reading the watch reports this one, once there's a way to act on it.
            }
            null
        }
    }

    /**
     * Opening this page is asking where things stand, so it reads once per connection, which is
     * also what notices a restart, and again once Local Bridge has been updated. A read that failed
     * stays failed until asked again.
     */
    fun readOnEntry() {
        val current = state.value ?: return
        if (current.setup.step != null) return
        val module = current.module
        if (module.phase != HealthModulePhase.READY) return
        val answered = module.snapshot != null || module.lastOperation != null
        if (answered && !current.bridgeChanged) return
        if (current.bridge == LocalBridgeStatus.NOT_INSTALLED) return
        read()
    }

    fun read() {
        val peer = session.value?.client?.state?.value?.connection?.peer ?: return
        command(READ) { session ->
            onWatch(session, peer) {
                look(session, peer, if (session.isDemo) listOf(0L) else LOOKS_WHEN_ASKED)
            }
            null
        }
    }

    /** The request already owns the peer selected when the confirmation dialog was opened. */
    internal fun carryOut(request: ModuleSetupRequest) =
        command(SETUP) { session ->
            val shared = session.services.moduleSetup
            if (shared.value.step == null) {
                shared.update {
                    it.copy(problem = null, targetPeer = request.peer, applyAlerts = false)
                }
                try {
                    onWatch(session, request.peer) {
                        requireTarget(session, request.peer, ready = true)
                        setUp(session, request.plan, request.peer)
                    }
                } finally {
                    // A stopped/failed/cancelled plan grants no future write to a passive refresh.
                    shared.update {
                        it.copy(
                            step = null,
                            fraction = null,
                            applyAlerts = false,
                            targetPeer = null,
                        )
                    }
                }
            }
            null
        }

    private fun requireTarget(
        session: WatchSession,
        peer: PeerAddress,
        ready: Boolean = false,
        connection: ConnectionState = session.client.state.value.connection,
    ) {
        if (connection.peer != null && connection.peer != peer)
            throw ToolboxException("device_changed")
        if (ready && (connection.phase != ConnectionPhase.LINK_READY || connection.peer != peer)) {
            throw ToolboxException("connection_replaced")
        }
    }

    private fun forgetAutomaticRequest(session: WatchSession, problem: String? = null) {
        session.services.moduleSetup.update {
            it.copy(applyAlerts = false, targetPeer = null, problem = problem ?: it.problem)
        }
    }

    private suspend fun onWatch(
        session: WatchSession,
        peer: PeerAddress,
        block: suspend () -> Unit,
    ) {
        withContext(WatchModuleTarget(peer)) {
            try {
                requireTarget(session, peer)
                block()
                requireTarget(session, peer)
            } catch (cancelled: CancellationException) {
                forgetAutomaticRequest(session)
                throw cancelled
            } catch (failure: ToolboxException) {
                forgetAutomaticRequest(session, failure.code)
                throw failure
            }
        }
    }

    private suspend fun setUp(session: WatchSession, plan: ModulePlan, peer: PeerAddress) {
        val services = session.services
        val shared = services.moduleSetup
        val module = services.healthModule
        val host = services.watchModuleHost
        val patch = services.notificationAlertPatch
        fun show(step: ModuleSetupStep, fraction: Float? = null) = shared.update {
            it.copy(step = step, fraction = fraction)
        }

        val installing = plan.install || plan.update
        show(if (installing) ModuleSetupStep.SENDING else ModuleSetupStep.TURNING_ON)
        host.stayAwake()
        requireTarget(session, peer, ready = true)
        if (installing) {
            val result = module.installBundled {
                val done = fractionOf(it.completedBytes, it.totalBytes)
                if (done == null || done >= 1f) show(ModuleSetupStep.INSTALLING)
                else show(ModuleSetupStep.SENDING, done)
            }
            // A result that's unknown may have gone through, so all that follows is finding out.
            requireTarget(session, peer, ready = true)
            if (result.outcome == HealthModuleOutcome.UNCONFIRMED) module.refresh()
            requireTarget(session, peer, ready = true)
            if (!module.state.value.current || moduleStage(module.state.value) !in ON_WATCH) return
        }
        if (plan.zygisk) {
            show(ModuleSetupStep.TURNING_ON)
            requireTarget(session, peer, ready = true)
            host.turnOnZygisk()
            requireTarget(session, peer, ready = true)
            if (!host.state.value.current || host.state.value.snapshot?.zygiskOn != true) {
                shared.update { it.copy(problem = "module_zygisk_failed") }
                return
            }
        }
        if (plan.alerts) {
            show(ModuleSetupStep.TURNING_ON)
            requireTarget(session, peer, ready = true)
            patch.optIn()
            requireTarget(session, peer, ready = true)
            val optIn = patch.state.value
            if (!optIn.current || optIn.snapshot?.optedIn != true || optIn.reason != null) {
                shared.update {
                    it.copy(problem = optIn.reason ?: "notification_alert_unconfirmed")
                }
                return
            }
            shared.update { it.copy(applyAlerts = true, targetPeer = peer) }
        }
        if (plan.restartUi) {
            restartSystemUi(session, peer, ::show)
        } else if (plan.restart) {
            shared.update { it.copy(uiRestartFailed = false) }
            show(ModuleSetupStep.RESTARTING)
            requireTarget(session, peer, ready = true)
            host.restart()
            requireTarget(session, peer)
            if (!awaitRestart(session, peer, ::show)) return
            show(ModuleSetupStep.CONFIRMING)
            look(
                session,
                peer,
                if (session.isDemo) listOf(0L) else LOOKS_AFTER_RESTART,
                afterRestart = true,
            )
        } else {
            show(ModuleSetupStep.CONFIRMING)
            look(session, peer, listOf(0L))
        }
    }

    private suspend fun restartSystemUi(
        session: WatchSession,
        peer: PeerAddress,
        show: (ModuleSetupStep, Float?) -> Unit,
    ) {
        val shared = session.services.moduleSetup
        val host = session.services.watchModuleHost
        fun gaveUp(problem: String) = shared.update {
            it.copy(problem = problem, uiRestartFailed = true)
        }
        show(ModuleSetupStep.RESTARTING_UI, null)
        requireTarget(session, peer, ready = true)
        host.restartSystemUi()
        requireTarget(session, peer)
        if (host.state.value.reason == "module_ui_restart_failed") {
            gaveUp("module_ui_restart_failed")
            return
        }
        // The page says how long the watch takes to come back, so it waits that long as well.
        if (!session.isDemo) delay(UI_RESTART_MILLIS)
        show(ModuleSetupStep.CONFIRMING, null)
        look(
            session,
            peer,
            if (session.isDemo) listOf(0L) else LOOKS_AFTER_UI_RESTART,
            afterRestart = true,
        )
        val patch = session.services.notificationAlertPatch.state.value
        val stillOut =
            patch.current &&
                alertPatchStage(patch).let {
                    it == AlertPatchStage.REBOOT_TO_LOAD || it == AlertPatchStage.NOT_LOADED
                }
        if (stillOut) gaveUp("module_ui_restart_not_loaded")
        else shared.update { it.copy(uiRestartFailed = false) }
    }

    /** False means there's nothing more to do here, and the page says what's wrong by itself. */
    private suspend fun awaitRestart(
        session: WatchSession,
        peer: PeerAddress,
        show: (ModuleSetupStep, Float?) -> Unit,
    ): Boolean {
        val shared = session.services.moduleSetup
        if (session.services.watchModuleHost.state.value.reason == "module_restart_failed") {
            shared.update { it.copy(problem = "module_restart_failed") }
            return false
        }
        if (session.isDemo) return true
        val link = session.client.state
        val dropped =
            withTimeoutOrNull(RESTART_DROP_MILLIS) {
                link.first {
                    requireTarget(session, peer, connection = it.connection)
                    it.connection.phase != ConnectionPhase.LINK_READY
                }
            }
        if (dropped == null) {
            shared.update { it.copy(problem = "module_restart_failed") }
            return false
        }
        show(ModuleSetupStep.RECONNECTING, null)
        val connected =
            withTimeoutOrNull(RECONNECT_MILLIS) {
                link.first {
                    requireTarget(session, peer, connection = it.connection)
                    it.connection.phase == ConnectionPhase.LINK_READY && it.connection.peer == peer
                }
            } != null
        if (!connected) shared.update { it.copy(problem = "connection_replaced") }
        return connected
    }

    /**
     * Reads until there's an answer. Right after a boot the bridge may not be up yet, and apps take
     * a moment to start and verify themselves before they answer.
     */
    private suspend fun look(
        session: WatchSession,
        peer: PeerAddress,
        waits: List<Long>,
        afterRestart: Boolean = false,
    ) {
        var askedHealthApp = false
        var readHost = true
        for (wait in waits) {
            delay(wait)
            requireTarget(session, peer)
            readAll(session, peer, readHost)
            // Only a restart changes what Magisk says, so one answer per look is enough.
            readHost = !session.services.watchModuleHost.state.value.current
            if (!worthAnotherLook(session, afterRestart, askedHealthApp)) return
            val host = session.services.watchModuleHost.state.value.snapshot
            if (!askedHealthApp && host?.healthRunning == false) {
                askedHealthApp = true
                // Whether it started comes with the same reading.
                readHost = true
                // An ordinary read of its settings provider, which is what starts its process.
                try {
                    requireTarget(session, peer, ready = true)
                    session.services.monitoring.refresh()
                    requireTarget(session, peer)
                } catch (failure: ToolboxException) {
                    if (failure.code == "device_changed" || failure.code == "connection_replaced")
                        throw failure
                    // The next read says how things stand either way.
                }
            }
        }
    }

    private suspend fun readAll(session: WatchSession, peer: PeerAddress, readHost: Boolean) {
        val services = session.services
        requireTarget(session, peer)
        if (readHost) {
            services.watchModuleHost.refresh()
            requireTarget(session, peer)
        }
        services.healthModule.refresh()
        requireTarget(session, peer)
        readBridgeVersion(session)
        requireTarget(session, peer)
        val read = services.healthModule.state.value
        if (read.current && read.snapshot.onWatch) {
            services.notificationAlertPatch.refresh()
            requireTarget(session, peer)
            applyIfAskedFor(session, peer)
        }
    }

    /**
     * Fixes to how the module gets read ship inside Local Bridge, so an older one can be wrong
     * about it. A connection that stays up never lists the watch's apps twice.
     */
    private suspend fun readBridgeVersion(session: WatchSession) {
        val toolbox = session.services.toolbox
        val root = toolbox.localBridge
        val target = root.state.value.target
        val memory = toolboxMemory(toolbox)
        try {
            if (target != null && memory.apps(target) == null) {
                memory.rememberApps(target, root.bind(target).apps())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The next read asks again, and nothing here waits for the answer.
        }
        // Whatever came of asking, or a change could keep asking for another read.
        session.services.moduleSetup.update {
            it.copy(bridgeAtRead = memory.bridgeOnWatch.value?.versionCode)
        }
    }

    /** The default mode changes nothing, and turning this on was asking for it to change. */
    private suspend fun applyIfAskedFor(session: WatchSession, peer: PeerAddress) {
        val shared = session.services.moduleSetup
        if (!shared.value.applyAlerts) return
        if (shared.value.targetPeer != peer) throw ToolboxException("device_changed")
        requireTarget(session, peer)
        val patch = session.services.notificationAlertPatch
        val read = patch.state.value
        if (!read.current) return
        when (alertPatchStage(read)) {
            AlertPatchStage.READY_TO_APPLY -> {
                // Consume before dispatch: a lost reply or failed write never grants a retry.
                val grant = shared.getAndUpdate {
                    if (it.targetPeer == peer) it.copy(applyAlerts = false) else it
                }
                if (grant.targetPeer != peer) throw ToolboxException("device_changed")
                if (!grant.applyAlerts) return
                // Someone testing from the command line stays in charge, including later refreshes.
                if (read.snapshot?.mode != NotificationAlertPatchMode.PROBE) {
                    requireTarget(session, peer, ready = true)
                    patch.applyToAll()
                    requireTarget(session, peer)
                }
            }
            AlertPatchStage.APPLIED,
            AlertPatchStage.NOT_HOOKED,
            AlertPatchStage.OFF -> shared.update { it.copy(applyAlerts = false) }
            else -> Unit
        }
    }

    /** Whether it's loaded only changes with a restart, or with this look starting the app. */
    private fun worthAnotherLook(
        session: WatchSession,
        afterRestart: Boolean,
        askedHealthApp: Boolean,
    ): Boolean {
        val services = session.services
        val host = services.watchModuleHost.state.value.snapshot
        val stage = moduleStage(services.healthModule.state.value)
        val alerts = alertPatchStage(services.notificationAlertPatch.state.value)
        return when {
            stage == ModuleStage.CHECKING || stage == ModuleStage.UNREACHED -> afterRestart
            !session.linkReady() || host?.zygiskOn == false -> false
            stage == ModuleStage.NOT_RUNNING ->
                (afterRestart || askedHealthApp || host?.healthRunning == false) &&
                    host?.healthOnDenyList != true &&
                    host?.healthSupported != false
            alerts == AlertPatchStage.NOT_LOADED || alerts == AlertPatchStage.REBOOT_TO_LOAD ->
                afterRestart && host?.systemUiSupported != false
            else -> false
        }
    }

    fun applyAlerts() {
        val peer = session.value?.client?.state?.value?.connection?.peer ?: return
        command(ALERTS) { session ->
            onWatch(session, peer) {
                forgetAutomaticRequest(session)
                requireTarget(session, peer, ready = true)
                session.services.notificationAlertPatch.applyToAll()
                requireTarget(session, peer)
            }
            null
        }
    }

    fun turnOffAlerts() {
        val selected = session.value ?: return
        forgetAutomaticRequest(selected)
        val peer = selected.client.state.value.connection.peer ?: return
        command(ALERTS) { session ->
            onWatch(session, peer) {
                forgetAutomaticRequest(session)
                requireTarget(session, peer, ready = true)
                session.services.notificationAlertPatch.optOut()
                requireTarget(session, peer)
            }
            null
        }
    }

    companion object {
        const val BUNDLE = "module_bundle"
        const val READ = "module_read"
        const val SETUP = "module_setup"
        const val ALERTS = "module_alerts"
        private val LOOKS_WHEN_ASKED = listOf(0L, 6_000L, 10_000L)
        private val LOOKS_AFTER_RESTART = listOf(3_000L, 5_000L, 10_000L, 15_000L, 30_000L, 30_000L)
        private const val UI_RESTART_MILLIS = 20_000L
        private val LOOKS_AFTER_UI_RESTART = listOf(0L, 5_000L, 5_000L, 10_000L, 15_000L)
        private const val RESTART_DROP_MILLIS = 60_000L
        private const val RECONNECT_MILLIS = 300_000L
    }
}
