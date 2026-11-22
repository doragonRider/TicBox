package dev.ticbox.companion.ui.bridge

import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.HealthModuleBundle
import dev.ticbox.companion.api.HealthModuleClient
import dev.ticbox.companion.api.HealthModuleImage
import dev.ticbox.companion.api.HealthModuleOutcome
import dev.ticbox.companion.api.HealthModulePhase
import dev.ticbox.companion.api.HealthModuleResult
import dev.ticbox.companion.api.HealthModuleSnapshot
import dev.ticbox.companion.api.HealthModuleState
import dev.ticbox.companion.api.LocalHealthSyncClient
import dev.ticbox.companion.api.LocalHealthSyncState
import dev.ticbox.companion.api.NotificationAlertPatchClient
import dev.ticbox.companion.api.NotificationAlertPatchMode
import dev.ticbox.companion.api.NotificationAlertPatchPhase
import dev.ticbox.companion.api.NotificationAlertPatchSnapshot
import dev.ticbox.companion.api.NotificationAlertPatchState
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.api.ToolboxApp
import dev.ticbox.companion.api.ToolboxBackendKind
import dev.ticbox.companion.api.ToolboxBackendState
import dev.ticbox.companion.api.ToolboxPhase
import dev.ticbox.companion.api.ToolboxProgress
import dev.ticbox.companion.api.ToolboxTarget
import dev.ticbox.companion.api.WatchClient
import dev.ticbox.companion.api.WatchModuleHostClient
import dev.ticbox.companion.api.WatchModuleHostPhase
import dev.ticbox.companion.api.WatchModuleHostSnapshot
import dev.ticbox.companion.api.WatchModuleHostState
import dev.ticbox.companion.api.WatchToolboxBackend
import dev.ticbox.companion.api.WatchToolboxClient
import dev.ticbox.companion.ui.CompanionApp
import dev.ticbox.companion.ui.FakeSessions
import dev.ticbox.companion.ui.NoSystemActions
import dev.ticbox.companion.ui.TestDependencies
import dev.ticbox.companion.ui.demoWatch
import dev.ticbox.companion.ui.navigation.ConnectionRoute
import dev.ticbox.companion.ui.navigation.LocalBridgeRoute
import dev.ticbox.companion.ui.navigation.NotificationsRoute
import dev.ticbox.companion.ui.navigation.WatchModuleRoute
import dev.ticbox.companion.ui.session
import dev.ticbox.companion.ui.session.SessionState
import dev.ticbox.companion.ui.toolbox.toolboxMemory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private const val BUILD = "build"
private val BUNDLE = HealthModuleBundle("0.3.0", 3, BUILD, "0", 1024)
private val TARGET = ToolboxTarget("w", 1, ToolboxBackendKind.ROOT_BRIDGE)

private fun image(
    matches: Boolean = true,
    disabled: Boolean = false,
    removal: Boolean = false,
) = HealthModuleImage(BUILD, "0.3.0", 3, matches, disabled, removal)

private fun snapshot(
    installed: HealthModuleImage? = null,
    staged: HealthModuleImage? = null,
    runtime: String? = null,
) = HealthModuleSnapshot(TARGET, 27000, 33, "arm64-v8a", installed, staged, runtime, null)

private val HOST_RUNNING =
    WatchModuleHostSnapshot(
        zygiskOn = true,
        healthOnDenyList = false,
        healthSupported = true,
        healthRunning = true,
        systemUiSupported = true,
    )

private class FakeHost(
    private var snapshot: WatchModuleHostSnapshot = HOST_RUNNING,
    private val zygiskWriteSticks: Boolean = true,
) : WatchModuleHostClient {
    var awake = 0
    var zygiskWrites = 0
    var restarts = 0
    var uiRestarts = 0
    override val state = MutableStateFlow(WatchModuleHostState(WatchModuleHostPhase.READY))

    override suspend fun refresh() {
        state.value = WatchModuleHostState(WatchModuleHostPhase.READY, snapshot, current = true)
    }

    override suspend fun stayAwake() {
        awake++
    }

    override suspend fun turnOnZygisk() {
        zygiskWrites++
        if (zygiskWriteSticks) snapshot = snapshot.copy(zygiskOn = true)
        refresh()
    }

    override suspend fun restart() {
        restarts++
    }

    override suspend fun restartSystemUi() {
        uiRestarts++
    }

    override fun close() = Unit
}

private class FakeModule(
    private val installOutcome: HealthModuleResult,
    reason: String? = null,
    var onWatch: HealthModuleSnapshot = snapshot(),
) : HealthModuleClient {
    var installs = 0
    var reads = 0
    private val readResult =
        if (reason == null) HealthModuleResult(HealthModuleOutcome.READ)
        else HealthModuleResult(HealthModuleOutcome.REJECTED, reason)
    override val state =
        MutableStateFlow(HealthModuleState(phase = HealthModulePhase.READY, bundle = BUNDLE))

    override suspend fun bundled() = BUNDLE

    override suspend fun refresh(): HealthModuleResult {
        reads++
        state.value =
            state.value.copy(
                snapshot = onWatch.takeIf { readResult.reason == null },
                current = readResult.reason == null,
                lastOperation = readResult,
                phase =
                    if (readResult.reason == null) HealthModulePhase.READY
                    else HealthModulePhase.UNAVAILABLE,
            )
        return readResult
    }

    override suspend fun installBundled(progress: (ToolboxProgress) -> Unit): HealthModuleResult {
        installs++
        state.value = state.value.copy(lastOperation = installOutcome)
        return installOutcome
    }

    override fun close() = Unit
}

private fun bridgeApp(version: Long) =
    ToolboxApp(
        LOCAL_BRIDGE_PACKAGE,
        "Local Bridge",
        "0.3.$version",
        version,
        uid = 10_000,
        enabled = true,
        system = false,
        apkPaths = listOf("/data/app/bridge/base.apk"),
        dataDirectory = null,
    )

/** A root connection whose app list has Local Bridge at the given version. */
private class FakeToolbox(version: Long) : WatchToolboxClient {
    override val adb = WatchToolboxClient.UNAVAILABLE.adb
    override val localBridge =
        object : WatchToolboxBackend by WatchToolboxClient.UNAVAILABLE.localBridge {
            override val state =
                MutableStateFlow(
                    ToolboxBackendState(
                        ToolboxBackendKind.ROOT_BRIDGE,
                        ToolboxPhase.CONNECTED,
                        target = TARGET,
                    )
                )

            override fun bind(target: ToolboxTarget): WatchToolboxBackend = this

            override suspend fun apps(userId: Int) = listOf(bridgeApp(version))
        }

    override fun close() = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h2600dp")
class WatchModuleScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var navController: NavHostController

    private fun show(
        module: HealthModuleClient? = null,
        host: WatchModuleHostClient? = null,
        client: WatchClient? = null,
        patch: NotificationAlertPatchClient? = null,
        toolbox: WatchToolboxClient = WatchToolboxClient.UNAVAILABLE,
    ) {
        val watch = demoWatch(context)
        // A handshake is what says a real watch has the bridge, as the demo's app list doesn't.
        val handshaken =
            object : LocalHealthSyncClient by watch.localHealth {
                override val state =
                    MutableStateFlow(LocalHealthSyncState(advertisedTables = emptySet()))
            }
        val session =
            watch.session(
                // Only the demo skips waiting for a real link to go down and come back.
                key = if (client == null) "demo" else "service",
                client = client ?: watch.client,
                healthModule = module ?: watch.healthModule,
                watchModuleHost = host ?: watch.watchModuleHost,
                notificationAlertPatch = patch ?: watch.notificationAlertPatch,
                localHealth = handshaken,
                toolbox = toolbox,
            )
        compose.setContent {
            navController = rememberNavController()
            CompanionApp(
                TestDependencies(FakeSessions(SessionState.Ready(session))),
                NoSystemActions,
                navController,
            )
        }
        compose.waitForIdle()
        compose.runOnUiThread { navController.navigate(WatchModuleRoute) }
        compose.waitForIdle()
    }

    private fun waitFor(text: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun confirm() {
        waitFor("Start")
        compose.onAllNodesWithText("Start").onLast().performClick()
        compose.waitForIdle()
    }

    /** Reading semantics is what keeps the main looper, and so the page's commands, running. */
    private fun waitUntilTrue(condition: () -> Boolean) {
        compose.waitUntil(15_000) {
            compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            condition()
        }
        compose.waitForIdle()
    }

    private fun tap(tag: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun showOlderBridge(module: FakeModule, toolbox: FakeToolbox) {
        val phoneApp =
            shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName)
        phoneApp.longVersionCode = 5
        show(module = module, host = FakeHost(), toolbox = toolbox)
        waitForTag("module_update_bridge")
    }

    private fun loadedButMisread() =
        FakeModule(
            HealthModuleResult(HealthModuleOutcome.READ),
            onWatch = snapshot(installed = image()),
        )

    @Test
    fun anOlderBridgeGoesFirstAndUpdatingItReadsAgain() {
        val module = loadedButMisread()
        val toolbox = FakeToolbox(4)
        showOlderBridge(module, toolbox)
        // What it misread mustn't send anyone off to restart the watch.
        compose.onNodeWithText("Checked after Local Bridge is updated.").assertExists()
        assertTrue(
            compose
                .onAllNodes(hasText("hasn't loaded", substring = true))
                .fetchSemanticsNodes()
                .isEmpty()
        )
        compose.onNodeWithTag("module_restart").assertDoesNotExist()
        val reads = module.reads
        compose.runOnUiThread {
            toolboxMemory(toolbox).rememberApps(TARGET, listOf(bridgeApp(5)))
        }
        waitUntilTrue { module.reads > reads }
        waitForTag("module_restart")
        compose.onNodeWithTag("module_update_bridge").assertDoesNotExist()
    }

    @Test
    fun theUpdateLeadsToLocalBridge() {
        showOlderBridge(loadedButMisread(), FakeToolbox(4))
        tap("module_update_bridge")
        assertTrue(navController.currentDestination!!.hasRoute<LocalBridgeRoute>())
    }

    @Test
    fun theStageComesFromWhatTheWatchSaid() {
        fun stage(snapshot: HealthModuleSnapshot) =
            moduleStage(HealthModuleState(bundle = BUNDLE, snapshot = snapshot, current = true))
        assertEquals(ModuleStage.CHECKING, moduleStage(HealthModuleState()))
        assertEquals(
            ModuleStage.UNREACHED,
            moduleStage(
                HealthModuleState(
                    lastOperation = HealthModuleResult(HealthModuleOutcome.REJECTED, "x")
                )
            ),
        )
        // An older reading says nothing once asking has failed.
        assertEquals(
            ModuleStage.UNREACHED,
            moduleStage(
                HealthModuleState(
                    bundle = BUNDLE,
                    snapshot = snapshot(installed = image(), runtime = BUILD),
                    lastOperation = HealthModuleResult(HealthModuleOutcome.REJECTED, "x"),
                )
            ),
        )
        // The checklist keeps it, while nothing is offered on it.
        assertEquals(
            ModuleStage.RUNNING,
            lastKnownStage(
                HealthModuleState(
                    bundle = BUNDLE,
                    snapshot = snapshot(installed = image(), runtime = BUILD),
                    lastOperation = HealthModuleResult(HealthModuleOutcome.REJECTED, "x"),
                )
            ),
        )
        assertEquals(null, lastKnownStage(HealthModuleState()))
        assertEquals(ModuleStage.NOT_INSTALLED, stage(snapshot()))
        assertEquals(ModuleStage.UPDATE, stage(snapshot(installed = image(matches = false))))
        assertEquals(ModuleStage.REBOOT, stage(snapshot(staged = image())))
        assertEquals(ModuleStage.BLOCKED, stage(snapshot(installed = image(disabled = true))))
        assertEquals(ModuleStage.BLOCKED, stage(snapshot(installed = image(removal = true))))
        assertEquals(ModuleStage.NOT_RUNNING, stage(snapshot(installed = image())))
        // A process still running an older build doesn't count as this one running.
        assertEquals(
            ModuleStage.NOT_RUNNING,
            stage(snapshot(installed = image(), runtime = "older")),
        )
        assertEquals(ModuleStage.RUNNING, stage(snapshot(installed = image(), runtime = BUILD)))
    }

    @Test
    fun theExtensionsStageComesFromWhatRootCanSee() {
        fun stage(
            optedIn: Boolean,
            survived: Boolean = false,
            running: Boolean = false,
            hooked: Boolean = true,
            mode: NotificationAlertPatchMode? = NotificationAlertPatchMode.OBSERVE,
            issue: String? = null,
        ) =
            alertPatchStage(
                NotificationAlertPatchState(
                    current = true,
                    snapshot =
                        NotificationAlertPatchSnapshot(
                            optedIn,
                            survived,
                            running,
                            hooked,
                            mode,
                            issue,
                        ),
                )
            )
        assertEquals(AlertPatchStage.UNKNOWN, alertPatchStage(NotificationAlertPatchState()))
        // The card keeps what it last said, with the failure no reason to read it as stopping.
        assertEquals(
            AlertPatchStage.OFF,
            lastKnownAlertStage(
                NotificationAlertPatchState(
                    snapshot =
                        NotificationAlertPatchSnapshot(false, false, false, false, null, null),
                    reason = "x",
                )
            ),
        )
        // An older reading says nothing once asking has failed.
        assertEquals(
            AlertPatchStage.UNKNOWN,
            alertPatchStage(
                NotificationAlertPatchState(
                    snapshot =
                        NotificationAlertPatchSnapshot(
                            optedIn = true,
                            optInSurvivedReboot = true,
                            running = true,
                            hooked = true,
                            mode = NotificationAlertPatchMode.ALL,
                            issue = null,
                        ),
                    reason = "x",
                )
            ),
        )
        assertEquals(AlertPatchStage.OFF, stage(false))
        assertEquals(AlertPatchStage.REBOOT_TO_UNLOAD, stage(false, running = true))
        assertEquals(
            AlertPatchStage.REBOOT_TO_UNLOAD,
            stage(false, running = true, mode = NotificationAlertPatchMode.OFF),
        )
        for (mode in
            listOf(NotificationAlertPatchMode.ALL, NotificationAlertPatchMode.PROBE, null)) {
            assertEquals(AlertPatchStage.STOP_PENDING, stage(false, running = true, mode = mode))
        }
        assertEquals(
            AlertPatchStage.STOP_PENDING,
            alertPatchStage(
                NotificationAlertPatchState(
                    current = true,
                    snapshot =
                        NotificationAlertPatchSnapshot(false, false, false, false, null, null),
                    reason = "notification_alert_control_unavailable",
                )
            ),
        )
        assertEquals(AlertPatchStage.REBOOT_TO_LOAD, stage(true))
        assertEquals(AlertPatchStage.NOT_LOADED, stage(true, survived = true))
        assertEquals(AlertPatchStage.READY_TO_APPLY, stage(true, running = true))
        assertEquals(
            AlertPatchStage.APPLIED,
            stage(true, running = true, mode = NotificationAlertPatchMode.ALL),
        )
        assertEquals(AlertPatchStage.NOT_HOOKED, stage(true, running = true, hooked = false))
        assertEquals(AlertPatchStage.NOT_HOOKED, stage(true, running = true, issue = "x"))
    }

    @Test
    fun thePlanIsEverythingStillMissing() {
        fun plan(
            stage: ModuleStage,
            zygiskOn: Boolean? = true,
            alerts: AlertPatchStage = AlertPatchStage.OFF,
            want: Boolean = false,
        ) = modulePlan(stage, zygiskOn, alerts, want)
        assertEquals(
            ModulePlan(install = true, zygisk = true, alerts = true, restart = true),
            plan(ModuleStage.NOT_INSTALLED, false, AlertPatchStage.UNKNOWN, true),
        )
        // Turned off before there's a copy on the watch, so the new one keeps it off.
        assertEquals(
            ModulePlan(install = true, alertsOff = true, restart = true),
            plan(ModuleStage.NOT_INSTALLED),
        )
        assertEquals(
            ModulePlan(update = true, alertsOff = true, restart = true),
            plan(ModuleStage.UPDATE),
        )
        // On unless turned off: the update's restart loads it, with nothing to write.
        for (alerts in listOf(AlertPatchStage.NOT_LOADED, AlertPatchStage.REBOOT_TO_LOAD)) {
            assertEquals(
                ModulePlan(update = true, alerts = true, restart = true),
                plan(ModuleStage.UPDATE, alerts = alerts, want = true),
            )
        }
        assertEquals(
            ModulePlan(update = true, restart = true),
            plan(ModuleStage.UPDATE, alerts = AlertPatchStage.APPLIED, want = true),
        )
        // Not knowing is no reason to undo what the watch keeps.
        assertEquals(
            ModulePlan(update = true, restart = true),
            plan(ModuleStage.UPDATE, alerts = AlertPatchStage.UNKNOWN, want = true),
        )
        assertEquals(ModulePlan(restart = true), plan(ModuleStage.REBOOT))
        assertEquals(
            ModulePlan(zygisk = true, restart = true),
            plan(ModuleStage.NOT_RUNNING, zygiskOn = false),
        )
        assertTrue(plan(ModuleStage.NOT_RUNNING).isEmpty)
        // A running module is its own proof, whatever the setting says for the next boot.
        assertTrue(plan(ModuleStage.RUNNING, zygiskOn = false).isEmpty)
        // Only the system UI has to start again for the extension, once the module is active.
        for (stage in listOf(ModuleStage.RUNNING, ModuleStage.NOT_RUNNING)) {
            assertEquals(
                ModulePlan(alerts = true, restartUi = true),
                plan(stage, want = true),
            )
        }
        assertEquals(
            ModulePlan(restartUi = true),
            plan(ModuleStage.RUNNING, alerts = AlertPatchStage.REBOOT_TO_LOAD, want = true),
        )
        // A stop that wasn't confirmed may have left it loaded or not, so a restart settles it.
        assertEquals(
            ModulePlan(alerts = true, restartUi = true),
            plan(ModuleStage.RUNNING, alerts = AlertPatchStage.STOP_PENDING, want = true),
        )
        assertEquals(
            ModulePlan(restartUi = true),
            plan(ModuleStage.RUNNING, alerts = AlertPatchStage.STOP_PENDING),
        )
        assertEquals(
            ModulePlan(restartUi = true),
            plan(ModuleStage.RUNNING, alerts = AlertPatchStage.REBOOT_TO_UNLOAD),
        )
        // Whatever else needs the whole watch restarted covers the extension as well.
        assertEquals(
            ModulePlan(alerts = true, restart = true),
            plan(ModuleStage.REBOOT, want = true),
        )
        assertEquals(
            ModulePlan(update = true, alerts = true, restart = true),
            plan(ModuleStage.UPDATE, want = true),
        )
        assertEquals(
            ModulePlan(zygisk = true, alerts = true, restart = true),
            plan(ModuleStage.NOT_RUNNING, zygiskOn = false, want = true),
        )
        // Restarting the system UI wasn't enough, so the whole watch is what's left to try.
        assertEquals(
            ModulePlan(restart = true),
            modulePlan(
                ModuleStage.RUNNING,
                true,
                AlertPatchStage.REBOOT_TO_LOAD,
                true,
                uiRestartFailed = true,
            ),
        )
        // Still loaded after being turned off, so turning it back on takes no restart.
        assertEquals(
            ModulePlan(alerts = true),
            plan(ModuleStage.RUNNING, alerts = AlertPatchStage.REBOOT_TO_UNLOAD, want = true),
        )
        assertTrue(
            plan(ModuleStage.RUNNING, alerts = AlertPatchStage.NOT_LOADED, want = true).isEmpty
        )
        for (stage in listOf(ModuleStage.CHECKING, ModuleStage.UNREACHED, ModuleStage.BLOCKED)) {
            assertTrue(plan(stage, zygiskOn = false, want = true).isEmpty)
        }
    }

    @Test
    fun oneTapGoesFromNothingToChoosingPerApp() {
        show()
        waitFor("Install and restart the watch")
        tap("module_setup")
        waitFor("Turn on Zygisk in Magisk")
        waitFor("Turn on notification sound and vibration control")
        confirm()
        waitFor("has loaded this version")
        waitFor("Applied.")
        compose.runOnUiThread { navController.navigate(NotificationsRoute) }
        compose.waitForIdle()
        waitForTag("alerts_sound")
    }

    @Test
    fun theNotificationSwitchAloneRestartsOnlyTheSystemUi() {
        val host = FakeHost()
        show(
            module =
                FakeModule(
                    HealthModuleResult(HealthModuleOutcome.READ),
                    onWatch = snapshot(installed = image(), runtime = BUILD),
                ),
            host = host,
        )
        waitForTag("module_restart")
        // On unless turned off.
        waitFor("Applied.")
        tap("module_alerts_switch")
        waitFor("Alert correction is inactive")
        tap("module_setup")
        waitFor("takes about 20 seconds")
        confirm()
        waitUntilTrue { host.uiRestarts == 1 }
        waitFor("The watch rings and vibrates for every notification")
        tap("module_alerts_switch")
        waitFor("Turn on and restart the system UI")
        tap("module_setup")
        waitFor("takes about 20 seconds")
        confirm()
        waitUntilTrue { host.uiRestarts == 2 }
        waitFor("Applied.")
        assertEquals(0, host.restarts)
    }

    @Test
    fun turnedOffBeforeInstallingItStaysOffAfterTheRestart() {
        show()
        waitFor("Install and restart the watch")
        tap("module_alerts_switch")
        tap("module_setup")
        waitFor("Turn on Zygisk in Magisk")
        assertEquals(
            0,
            compose
                .onAllNodes(hasText("Turn on notification sound and vibration", substring = true))
                .fetchSemanticsNodes()
                .size,
        )
        confirm()
        waitFor("has loaded this version")
        waitFor("The watch rings and vibrates for every notification")
    }

    @Test
    fun aRemovedMarkerDoesNotHideARunningFixAndTurningOffCanBeRetried() {
        var stops = 0
        val patch =
            object : NotificationAlertPatchClient {
                override val state =
                    MutableStateFlow(
                        NotificationAlertPatchState(
                            phase = NotificationAlertPatchPhase.READY,
                            current = true,
                            snapshot =
                                NotificationAlertPatchSnapshot(
                                    false,
                                    false,
                                    true,
                                    true,
                                    NotificationAlertPatchMode.ALL,
                                    null,
                                ),
                            reason = "mode_write_unconfirmed",
                        )
                    )

                override suspend fun refresh() = Unit

                override suspend fun optIn() = Unit

                override suspend fun applyToAll() = Unit

                override suspend fun optOut() {
                    stops++
                    state.value =
                        state.value.copy(
                            reason = null,
                            snapshot =
                                state.value.snapshot!!.copy(mode = NotificationAlertPatchMode.OFF),
                        )
                }

                override fun close() = Unit
            }
        show(patch = patch)
        waitFor("has not confirmed it stopped")
        assertEquals(0, stops)
        tap("module_alerts_off")
        assertEquals(1, stops)
        waitFor("Alert correction is inactive")
        compose.onNodeWithTag("module_alerts_off").assertDoesNotExist()
    }

    @Test
    fun theSwitchHoldsStillThroughAReadAndAFailedOne() {
        val patch =
            object : NotificationAlertPatchClient {
                override val state =
                    MutableStateFlow(
                        NotificationAlertPatchState(
                            phase = NotificationAlertPatchPhase.BUSY,
                            current = false,
                            snapshot =
                                NotificationAlertPatchSnapshot(
                                    true,
                                    true,
                                    true,
                                    true,
                                    NotificationAlertPatchMode.ALL,
                                    null,
                                ),
                        )
                    )

                override suspend fun refresh() = Unit

                override suspend fun optIn() = Unit

                override suspend fun applyToAll() = Unit

                override suspend fun optOut() = Unit

                override fun close() = Unit
            }
        show(patch = patch)
        waitForTag("module_alerts_switch")
        compose.onNodeWithTag("module_alerts_switch").assertIsOn()
        patch.state.value =
            patch.state.value.copy(phase = NotificationAlertPatchPhase.UNAVAILABLE, reason = "x")
        compose.waitForIdle()
        compose.onNodeWithTag("module_alerts_switch").assertIsOn()
    }

    @Test
    fun whatOnlyARestartChangesStaysThroughAReadAndAFailedOne() {
        val module =
            FakeModule(
                HealthModuleResult(HealthModuleOutcome.READ),
                onWatch = snapshot(installed = image(), runtime = BUILD),
            )
        val host = FakeHost()
        fun stillShown() {
            compose.onNodeWithText("On.").assertExists()
            compose.onNodeWithText("has loaded this version", substring = true).assertExists()
        }
        show(module, host)
        waitForTag("module_restart")
        stillShown()
        host.state.value = host.state.value.copy(phase = WatchModuleHostPhase.BUSY, current = false)
        module.state.value =
            module.state.value.copy(phase = HealthModulePhase.READING, current = false)
        compose.waitForIdle()
        stillShown()
        host.state.value = host.state.value.copy(phase = WatchModuleHostPhase.UNAVAILABLE)
        module.state.value =
            module.state.value.copy(
                phase = HealthModulePhase.UNAVAILABLE,
                lastOperation = HealthModuleResult(HealthModuleOutcome.REJECTED, "x"),
            )
        // Nothing is offered on an answer that asking again couldn't confirm.
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("module_restart")).fetchSemanticsNodes().isEmpty()
        }
        stillShown()
    }

    @Test
    fun anInstallWithAnUnknownResultIsReadBackAndNeverRepeated() {
        val module =
            FakeModule(
                HealthModuleResult(HealthModuleOutcome.UNCONFIRMED, "module_install_unconfirmed")
            )
        val host = FakeHost()
        show(module, host)
        tap("module_setup")
        confirm()
        waitUntilTrue { module.reads == 2 }
        waitForTag("module_setup")
        assertEquals(1, module.installs)
        // It didn't turn out to be there, so nothing after the install went ahead.
        assertEquals(0, host.restarts)
        assertEquals(1, host.awake)
    }

    @Test
    fun anUnknownResultThatDidGoThroughCarriesOnToTheRestart() {
        val module =
            FakeModule(
                HealthModuleResult(HealthModuleOutcome.UNCONFIRMED, "module_install_unconfirmed")
            )
        val host = FakeHost()
        show(module, host)
        tap("module_setup")
        module.onWatch = snapshot(staged = image())
        confirm()
        waitUntilTrue { host.restarts == 1 }
        assertEquals(1, module.installs)
    }

    @Test
    fun aModuleThatIsntRunningSaysWhyAndOffersARestart() {
        val module =
            FakeModule(
                HealthModuleResult(HealthModuleOutcome.READ),
                onWatch = snapshot(installed = image()),
            )
        val host = FakeHost(HOST_RUNNING.copy(healthSupported = false))
        show(module, host)
        waitFor("doesn't support this version")
        // Looking again can't change this one.
        assertEquals(1, module.reads)
        tap("module_restart")
        confirm()
        waitUntilTrue { host.restarts == 1 }
        assertEquals(0, host.zygiskWrites)
    }

    @Test
    fun zygiskBeingOffIsOneTapAway() {
        val module =
            FakeModule(
                HealthModuleResult(HealthModuleOutcome.READ),
                onWatch = snapshot(installed = image()),
            )
        val host = FakeHost(HOST_RUNNING.copy(zygiskOn = false))
        show(module, host)
        waitFor("Zygisk was off")
        waitFor("Turn on and restart the watch")
        tap("module_setup")
        confirm()
        waitUntilTrue { host.restarts == 1 }
        assertEquals(1, host.zygiskWrites)
    }

    @Test
    fun aZygiskWriteThatDidntTakeStopsBeforeTheRestart() {
        val module =
            FakeModule(
                HealthModuleResult(HealthModuleOutcome.READ),
                onWatch = snapshot(installed = image()),
            )
        val host = FakeHost(HOST_RUNNING.copy(zygiskOn = false), zygiskWriteSticks = false)
        show(module, host)
        waitFor("Turn on and restart the watch")
        tap("module_setup")
        confirm()
        waitFor("Couldn't turn on Zygisk")
        assertEquals(1, host.zygiskWrites)
        assertEquals(0, host.restarts)
    }

    @Test
    fun confirmationKeepsTheWatchThatWasSelectedWhenTheDialogOpened() {
        val demo = demoWatch(context)
        val link = MutableStateFlow(demo.client.state.value)
        val client =
            object : WatchClient by demo.client {
                override val state = link
            }
        val module =
            FakeModule(
                HealthModuleResult(HealthModuleOutcome.READ),
                onWatch = snapshot(staged = image()),
            )
        val host = FakeHost()
        show(module, host, client)
        waitFor("Restart the watch")
        tap("module_setup")
        waitFor("Start")
        compose.runOnUiThread {
            link.value =
                link.value.copy(
                    connection =
                        link.value.connection.copy(
                            peer = PeerAddress("02:00:00:00:00:02"),
                            sessionId = 2,
                        )
                )
        }
        compose.waitForIdle()
        confirm()
        waitFor("The connected device has changed")
        assertEquals(0, host.awake)
        assertEquals(0, host.restarts)
        assertEquals(0, host.zygiskWrites)
        assertEquals(0, module.installs)
    }

    @Test
    fun aRestartWaitsForTheLinkToGoAndComeBack() {
        val demo = demoWatch(context)
        val link = MutableStateFlow(demo.client.state.value)
        fun phase(value: ConnectionPhase) {
            link.value = link.value.copy(connection = link.value.connection.copy(phase = value))
        }
        val client =
            object : WatchClient by demo.client {
                override val state = link
            }
        val module =
            FakeModule(
                HealthModuleResult(HealthModuleOutcome.READ),
                onWatch = snapshot(staged = image()),
            )
        val host = FakeHost()
        show(module, host, client)
        waitFor("Restart the watch")
        tap("module_setup")
        confirm()
        waitFor("Restarting the watch")
        waitUntilTrue { host.restarts == 1 }
        phase(ConnectionPhase.CONNECTING)
        waitFor("Waiting for the watch to come back")
        module.onWatch = snapshot(installed = image(), runtime = BUILD)
        phase(ConnectionPhase.LINK_READY)
        waitFor("has loaded this version")
        assertEquals(1, host.restarts)
    }

    @Test
    fun theConnectionPageLeadsToTheModule() {
        show()
        compose.runOnUiThread { navController.navigate(ConnectionRoute) }
        compose.waitForIdle()
        tap("connection_watch_module")
        waitForTag("module_alerts_switch")
    }

    @Test
    fun aRefusedReadSaysWhyAndPointsToTheBridgePage() {
        val module =
            FakeModule(
                HealthModuleResult(HealthModuleOutcome.READ),
                "module_bridge_update_required",
            )
        show(module)
        waitFor("too old for this")
        assertEquals(1, module.reads)
        compose.onAllNodesWithText("Open Local Bridge").onLast().performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Local Bridge").assertExists()
    }
}
