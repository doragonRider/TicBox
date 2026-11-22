package dev.ticbox.companion.demo

import dev.ticbox.companion.api.HealthModuleBundle
import dev.ticbox.companion.api.HealthModuleClient
import dev.ticbox.companion.api.HealthModuleImage
import dev.ticbox.companion.api.HealthModuleOutcome
import dev.ticbox.companion.api.HealthModulePhase
import dev.ticbox.companion.api.HealthModuleResult
import dev.ticbox.companion.api.HealthModuleSnapshot
import dev.ticbox.companion.api.HealthModuleState
import dev.ticbox.companion.api.NotificationAlertPatchClient
import dev.ticbox.companion.api.NotificationAlertPatchMode
import dev.ticbox.companion.api.NotificationAlertPatchPhase
import dev.ticbox.companion.api.NotificationAlertPatchSnapshot
import dev.ticbox.companion.api.NotificationAlertPatchState
import dev.ticbox.companion.api.ToolboxBackendKind
import dev.ticbox.companion.api.ToolboxProgress
import dev.ticbox.companion.api.ToolboxTarget
import dev.ticbox.companion.api.WatchModuleHostClient
import dev.ticbox.companion.api.WatchModuleHostPhase
import dev.ticbox.companion.api.WatchModuleHostSnapshot
import dev.ticbox.companion.api.WatchModuleHostState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val DEMO_BUILD = "demo-module-build"
private const val INSTALL_STEPS = 8

/**
 * Walks through the real page's stages with nothing installed anywhere. There's no watch to restart
 * here, so reading again after the setup's restart stands in for having done it.
 */
internal class DemoHealthModule(private val delayMillis: Long) : HealthModuleClient {
    private val bundle = HealthModuleBundle("0.3.3", 6, DEMO_BUILD, "0".repeat(64), 1_572_864)
    private val image =
        HealthModuleImage(
            DEMO_BUILD,
            bundle.versionName,
            bundle.versionCode,
            matchesBundled = true,
            disabled = false,
            removalPending = false,
        )
    private val mutableState =
        MutableStateFlow(HealthModuleState(phase = HealthModulePhase.READY, bundle = bundle))
    override val state = mutableState.asStateFlow()

    private fun snapshot(installed: Boolean, staged: Boolean) =
        HealthModuleSnapshot(
            target = ToolboxTarget("demo", 1, ToolboxBackendKind.ROOT_BRIDGE),
            magiskVersionCode = 27000,
            sdk = 33,
            abi = "arm64-v8a",
            installed = image.takeIf { installed },
            staged = image.takeIf { staged },
            healthRuntimeBuildId = DEMO_BUILD.takeIf { installed },
            runtimeIssue = "module_runtime_not_observed".takeUnless { installed },
        )

    override suspend fun bundled() = bundle

    override suspend fun refresh(): HealthModuleResult {
        mutableState.update { it.copy(phase = HealthModulePhase.READING, current = false) }
        delay(delayMillis)
        val before = mutableState.value.snapshot
        val installed = before?.installed != null || before?.staged != null
        val result = HealthModuleResult(HealthModuleOutcome.READ)
        mutableState.update {
            it.copy(
                phase = HealthModulePhase.READY,
                snapshot = snapshot(installed, staged = false),
                current = true,
                lastOperation = result,
            )
        }
        return result
    }

    override suspend fun installBundled(progress: (ToolboxProgress) -> Unit): HealthModuleResult {
        mutableState.update { it.copy(phase = HealthModulePhase.INSTALLING, current = false) }
        repeat(INSTALL_STEPS) { step ->
            delay(delayMillis / 2)
            progress(ToolboxProgress(bundle.bytes * (step + 1) / INSTALL_STEPS, bundle.bytes))
        }
        delay(delayMillis)
        val result = HealthModuleResult(HealthModuleOutcome.STAGED_REBOOT_REQUIRED)
        mutableState.update {
            it.copy(
                phase = HealthModulePhase.READY,
                snapshot = snapshot(installed = false, staged = true),
                current = true,
                lastOperation = result,
            )
        }
        return result
    }

    override fun close() = Unit
}

/** Like [DemoHealthModule], reading again after the setup's restart counts as having done it. */
internal class DemoAlertPatch(private val delayMillis: Long) : NotificationAlertPatchClient {
    private val mutableState =
        MutableStateFlow(NotificationAlertPatchState(NotificationAlertPatchPhase.READY))
    override val state = mutableState.asStateFlow()

    private suspend fun change(
        next: (NotificationAlertPatchSnapshot) -> NotificationAlertPatchSnapshot
    ) {
        mutableState.update { it.copy(phase = NotificationAlertPatchPhase.BUSY, current = false) }
        delay(delayMillis)
        mutableState.update {
            val before =
                it.snapshot
                    ?: NotificationAlertPatchSnapshot(
                        // On until turned off.
                        optedIn = true,
                        optInSurvivedReboot = false,
                        running = false,
                        hooked = false,
                        mode = null,
                        issue = null,
                    )
            NotificationAlertPatchState(NotificationAlertPatchPhase.READY, next(before), true)
        }
    }

    override suspend fun refresh() = change {
        val loaded = it.optedIn
        it.copy(
            optInSurvivedReboot = loaded,
            running = loaded,
            hooked = loaded,
            mode = if (loaded) it.mode ?: NotificationAlertPatchMode.ALL else null,
        )
    }

    override suspend fun optIn() = change { it.copy(optedIn = true) }

    override suspend fun optOut() = change {
        it.copy(optedIn = false, optInSurvivedReboot = false, mode = NotificationAlertPatchMode.OFF)
    }

    override suspend fun applyToAll() = change { it.copy(mode = NotificationAlertPatchMode.ALL) }

    override fun close() = Unit
}

/** The setting and the restart that the real page drives, kept only in memory. */
internal class DemoModuleHost(private val delayMillis: Long) : WatchModuleHostClient {
    private val mutableState = MutableStateFlow(WatchModuleHostState(WatchModuleHostPhase.READY))
    override val state = mutableState.asStateFlow()
    private var zygiskOn = false

    private suspend fun read() {
        mutableState.update { it.copy(phase = WatchModuleHostPhase.BUSY, current = false) }
        delay(delayMillis)
        mutableState.value =
            WatchModuleHostState(
                WatchModuleHostPhase.READY,
                WatchModuleHostSnapshot(
                    zygiskOn = zygiskOn,
                    healthOnDenyList = false,
                    healthSupported = true,
                    healthRunning = true,
                    systemUiSupported = true,
                ),
                current = true,
            )
    }

    override suspend fun refresh() = read()

    override suspend fun stayAwake() = Unit

    override suspend fun turnOnZygisk() {
        zygiskOn = true
        read()
    }

    override suspend fun restart() = delay(delayMillis)

    override suspend fun restartSystemUi() = delay(delayMillis)

    override fun close() = Unit
}
