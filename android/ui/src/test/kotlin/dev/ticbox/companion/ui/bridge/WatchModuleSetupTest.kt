package dev.ticbox.companion.ui.bridge

import androidx.lifecycle.viewModelScope
import dev.ticbox.companion.api.ConnectionPhase
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
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.api.ToolboxBackendKind
import dev.ticbox.companion.api.ToolboxProgress
import dev.ticbox.companion.api.ToolboxTarget
import dev.ticbox.companion.api.WatchClient
import dev.ticbox.companion.api.WatchModuleHostClient
import dev.ticbox.companion.api.WatchModuleHostPhase
import dev.ticbox.companion.api.WatchModuleHostSnapshot
import dev.ticbox.companion.api.WatchModuleHostState
import dev.ticbox.companion.api.WatchModuleTarget
import dev.ticbox.companion.demo.DemoWatch
import dev.ticbox.companion.ui.FakeSessions
import dev.ticbox.companion.ui.TestDependencies
import dev.ticbox.companion.ui.session
import dev.ticbox.companion.ui.session.SessionState
import java.io.Closeable
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WatchModuleSetupTest {
    private val peer = PeerAddress("02:00:00:00:00:01")
    private val other = PeerAddress("02:00:00:00:00:02")
    private val bundle = HealthModuleBundle("fixture", 1, "build", "digest", 1024)
    private val image = HealthModuleImage("build", "fixture", 1, true, false, false)

    @Before
    fun mainDispatcher() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun resetDispatcher() {
        Dispatchers.resetMain()
    }

    private inner class Fixture(scope: CoroutineScope) : Closeable {
        val calls = mutableListOf<Pair<String, PeerAddress?>>()
        private val demo = DemoWatch(RuntimeEnvironment.getApplication(), scope)
        val link =
            MutableStateFlow(
                demo.client.state.value.copy(
                    connection =
                        demo.client.state.value.connection.copy(
                            phase = ConnectionPhase.LINK_READY,
                            peer = peer,
                            sessionId = 1,
                        )
                )
            )
        private val client =
            object : WatchClient by demo.client {
                override val state = link
            }

        suspend fun observed(name: String) {
            calls += name to currentCoroutineContext()[WatchModuleTarget]?.peer
        }

        inner class Module : HealthModuleClient {
            var runtime: String? = "build"
            var installs = 0
            var afterRead: (() -> Unit)? = null

            private fun snapshot(staged: Boolean = false) =
                HealthModuleSnapshot(
                    ToolboxTarget("watch", 1, ToolboxBackendKind.ROOT_BRIDGE),
                    27000,
                    33,
                    "arm64-v8a",
                    image.takeUnless { staged },
                    image.takeIf { staged },
                    runtime,
                    null,
                )

            override val state =
                MutableStateFlow(
                    HealthModuleState(HealthModulePhase.READY, bundle, snapshot(), true)
                )

            override suspend fun bundled() = bundle

            override suspend fun refresh(): HealthModuleResult {
                observed("module_read")
                afterRead?.invoke()
                val result = HealthModuleResult(HealthModuleOutcome.READ)
                state.value =
                    HealthModuleState(HealthModulePhase.READY, bundle, snapshot(), true, result)
                return result
            }

            override suspend fun installBundled(
                progress: (ToolboxProgress) -> Unit
            ): HealthModuleResult {
                observed("install")
                installs++
                val result = HealthModuleResult(HealthModuleOutcome.STAGED_REBOOT_REQUIRED)
                state.value =
                    HealthModuleState(HealthModulePhase.READY, bundle, snapshot(true), true, result)
                return result
            }

            override fun close() = Unit
        }

        val module = Module()

        inner class Host : WatchModuleHostClient {
            var restarts = 0
            var uiRestarts = 0
            var zygiskWrites = 0
            var staleZygisk = false
            var unanswered = 0
            var snapshot = WatchModuleHostSnapshot(true, false, true, true, true)
            override val state =
                MutableStateFlow(WatchModuleHostState(WatchModuleHostPhase.READY, snapshot, true))

            override suspend fun refresh() {
                observed("host_read")
                if (unanswered-- > 0) {
                    state.value =
                        state.value.copy(WatchModuleHostPhase.UNAVAILABLE, current = false)
                    return
                }
                state.value = WatchModuleHostState(WatchModuleHostPhase.READY, snapshot, true)
            }

            override suspend fun stayAwake() {
                observed("awake")
            }

            override suspend fun turnOnZygisk() {
                observed("zygisk")
                zygiskWrites++
                state.value =
                    WatchModuleHostState(
                        WatchModuleHostPhase.READY,
                        snapshot,
                        current = !staleZygisk,
                        reason = "timeout".takeIf { staleZygisk },
                    )
            }

            override suspend fun restart() {
                observed("restart")
                restarts++
            }

            override suspend fun restartSystemUi() {
                observed("ui_restart")
                uiRestarts++
            }

            override fun close() = Unit
        }

        val host = Host()

        inner class Patch : NotificationAlertPatchClient {
            var optedIn = false
            var loaded = false
            var mode = NotificationAlertPatchMode.OBSERVE
            var staleOptIn = false
            var failedApply = false
            var applications = 0
            var optIns = 0
            var keptOff = 0
            var keepOffFails = false
            override val state =
                MutableStateFlow(NotificationAlertPatchState(NotificationAlertPatchPhase.READY))

            private fun snapshot() =
                NotificationAlertPatchSnapshot(
                    optedIn,
                    loaded,
                    loaded,
                    loaded,
                    mode.takeIf { loaded },
                    null,
                )

            override suspend fun refresh() {
                observed("patch_read")
                state.value =
                    NotificationAlertPatchState(NotificationAlertPatchPhase.READY, snapshot(), true)
            }

            override suspend fun optIn() {
                observed("optin")
                optIns++
                optedIn = true
                state.value =
                    NotificationAlertPatchState(
                        NotificationAlertPatchPhase.READY,
                        snapshot(),
                        current = !staleOptIn,
                    )
            }

            override suspend fun optOut() {
                observed("optout")
                optedIn = false
                mode = NotificationAlertPatchMode.OFF
                state.value =
                    NotificationAlertPatchState(NotificationAlertPatchPhase.READY, snapshot(), true)
            }

            override suspend fun keepOff() {
                observed("keepoff")
                keptOff++
                if (!keepOffFails) optedIn = false
                state.value =
                    NotificationAlertPatchState(
                        NotificationAlertPatchPhase.READY,
                        snapshot(),
                        true,
                        "notification_alert_write_failed".takeIf { keepOffFails },
                    )
            }

            override suspend fun applyToAll() {
                observed("all")
                applications++
                if (!failedApply) mode = NotificationAlertPatchMode.ALL
                state.value =
                    NotificationAlertPatchState(
                        NotificationAlertPatchPhase.READY,
                        snapshot(),
                        true,
                        "notification_alert_unconfirmed".takeIf { failedApply },
                    )
            }

            override fun close() = Unit
        }

        val patch = Patch()
        val session =
            demo.session(
                key = "service",
                client = client,
                healthModule = module,
                watchModuleHost = host,
                notificationAlertPatch = patch,
            )
        val dependencies = TestDependencies(FakeSessions(SessionState.Ready(session)))
        val viewModel = WatchModuleViewModel(dependencies)
        val setup
            get() = session.services.moduleSetup.value

        fun phase(phase: ConnectionPhase, target: PeerAddress = peer, sessionId: Long = 1) {
            link.value =
                link.value.copy(
                    connection =
                        link.value.connection.copy(
                            phase = phase,
                            peer = target,
                            sessionId = sessionId,
                        )
                )
        }

        fun begin(plan: ModulePlan = ModulePlan(alerts = true, restart = true)) {
            viewModel.carryOut(ModuleSetupRequest(plan, peer))
        }

        override fun close() {
            dependencies.applicationScope.cancel()
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun aPassiveReadKeepsItsOriginalPeerAcrossMultipleBackendReads() = runTest {
        Fixture(backgroundScope).use { f ->
            f.module.afterRead = { f.phase(ConnectionPhase.LINK_READY, other, 2) }
            f.viewModel.read()
            advanceUntilIdle()
            assertEquals(listOf("host_read", "module_read"), f.calls.map { it.first })
            assertTrue(f.calls.all { it.second == peer })
            assertEquals("device_changed", f.setup.problem)
            assertFalse(f.setup.applyAlerts)
            assertEquals(0, f.patch.applications)
        }
    }

    @Test
    fun aPassiveReadOnTheSameReconnectedPeerDoesNotAuthorizeWrites() = runTest {
        Fixture(backgroundScope).use { f ->
            f.phase(ConnectionPhase.LINK_READY, sessionId = 2)
            f.patch.optedIn = true
            f.patch.loaded = true
            f.viewModel.read()
            advanceUntilIdle()
            assertEquals(listOf("host_read", "module_read", "patch_read"), f.calls.map { it.first })
            assertTrue(f.calls.all { it.second == peer })
            assertEquals(0, f.patch.applications)
            assertNull(f.setup.problem)
        }
    }

    @Test
    fun onlyTheSystemUiRestartsAndTheWatchStaysUp() = runTest {
        Fixture(backgroundScope).use { f ->
            f.begin(ModulePlan(alerts = true, restartUi = true))
            runCurrent()
            assertEquals(1, f.host.uiRestarts)
            assertEquals(ModuleSetupStep.RESTARTING_UI, f.setup.step)
            f.patch.loaded = true
            advanceUntilIdle()
            assertEquals(0, f.host.restarts)
            assertEquals(1, f.patch.applications)
            assertNull(f.setup.problem)
            assertNull(f.setup.step)
            assertFalse(f.setup.uiRestartFailed)
        }
    }

    @Test
    fun aSystemUiRestartThatDidntLoadItLeavesTheWatchToRestart() = runTest {
        Fixture(backgroundScope).use { f ->
            f.begin(ModulePlan(alerts = true, restartUi = true))
            advanceUntilIdle()
            assertEquals("module_ui_restart_not_loaded", f.setup.problem)
            assertTrue(f.setup.uiRestartFailed)
            assertEquals(0, f.host.restarts)
            assertEquals(0, f.patch.applications)
            assertNull(f.setup.step)
        }
    }

    @Test
    fun changingPeerWhileWaitingForRestartCannotApplyTheOldGrant() = runTest {
        Fixture(backgroundScope).use { f ->
            f.begin()
            runCurrent()
            assertEquals(1, f.host.restarts)
            f.phase(ConnectionPhase.CONNECTING)
            runCurrent()
            assertEquals(ModuleSetupStep.RECONNECTING, f.setup.step)
            f.patch.loaded = true
            f.phase(ConnectionPhase.LINK_READY, other, 2)
            runCurrent()
            assertEquals("device_changed", f.setup.problem)
            assertNull(f.setup.step)
            assertFalse(f.setup.applyAlerts)
            assertNull(f.setup.targetPeer)
            assertEquals(0, f.patch.applications)
            assertTrue(f.calls.all { it.second == peer })
            assertTrue(f.calls.none { it.first.endsWith("_read") })
        }
    }

    @Test
    fun theSamePeerMayReturnWithANewConnectionSession() = runTest {
        Fixture(backgroundScope).use { f ->
            f.begin()
            runCurrent()
            f.phase(ConnectionPhase.CONNECTING, sessionId = 2)
            runCurrent()
            f.patch.loaded = true
            f.phase(ConnectionPhase.LINK_READY, sessionId = 2)
            advanceUntilIdle()
            assertEquals(1, f.host.restarts)
            assertEquals(1, f.patch.applications)
            assertNull(f.setup.problem)
            assertNull(f.setup.step)
            assertFalse(f.setup.applyAlerts)
            assertTrue(
                f.calls.all { it.second == peer },
                "All post-restart operations retain the confirmed target",
            )
        }
    }

    @Test
    fun anUnconfirmedAllIsNotRepeatedByStartupLooksOrPassiveRefreshes() = runTest {
        Fixture(backgroundScope).use { f ->
            f.module.runtime = null
            f.patch.failedApply = true
            f.begin()
            runCurrent()
            f.phase(ConnectionPhase.CONNECTING, sessionId = 2)
            runCurrent()
            f.patch.loaded = true
            f.phase(ConnectionPhase.LINK_READY, sessionId = 2)
            advanceUntilIdle()
            assertTrue(f.calls.count { it.first == "patch_read" } > 1)
            assertEquals(1, f.patch.applications)
            repeat(2) {
                f.viewModel.read()
                advanceUntilIdle()
            }
            assertEquals(1, f.patch.applications)
            assertFalse(f.setup.applyAlerts)
            f.patch.failedApply = false
            f.viewModel.applyAlerts()
            advanceUntilIdle()
            assertEquals(2, f.patch.applications, "A new explicit Apply remains available")
        }
    }

    @Test
    fun aRefreshAsksOnceBecauseOnlyARestartChangesTheAnswer() = runTest {
        Fixture(backgroundScope).use { f ->
            f.module.runtime = null
            f.viewModel.read()
            advanceUntilIdle()
            assertEquals(1, f.calls.count { it.first == "host_read" })
            assertEquals(1, f.calls.count { it.first == "module_read" })
        }
    }

    @Test
    fun startingTheHealthAppIsWorthLookingAgain() = runTest {
        Fixture(backgroundScope).use { f ->
            f.module.runtime = null
            f.host.snapshot = f.host.snapshot.copy(healthRunning = false)
            f.viewModel.read()
            advanceUntilIdle()
            assertEquals(2, f.calls.count { it.first == "host_read" })
            assertEquals(3, f.calls.count { it.first == "module_read" })
        }
    }

    @Test
    fun afterARestartMagiskIsAskedUntilItAnswersAndNoMore() = runTest {
        Fixture(backgroundScope).use { f ->
            f.module.runtime = null
            f.host.unanswered = 2
            f.begin(ModulePlan(restart = true))
            runCurrent()
            f.phase(ConnectionPhase.CONNECTING, sessionId = 2)
            runCurrent()
            f.phase(ConnectionPhase.LINK_READY, sessionId = 2)
            advanceUntilIdle()
            assertEquals(3, f.calls.count { it.first == "host_read" })
            assertEquals(6, f.calls.count { it.first == "module_read" })
        }
    }

    @Test
    fun aStaleTrueZygiskSnapshotCannotAdvanceThePlan() = runTest {
        Fixture(backgroundScope).use { f ->
            f.host.staleZygisk = true
            f.begin(ModulePlan(zygisk = true, alerts = true, restart = true))
            advanceUntilIdle()
            assertEquals(1, f.host.zygiskWrites)
            assertEquals(0, f.patch.optIns)
            assertEquals(0, f.host.restarts)
            assertEquals("module_zygisk_failed", f.setup.problem)
            assertFalse(f.setup.applyAlerts)
        }
    }

    @Test
    fun turnedOffBeforeInstallingTheNewCopyKeepsItOffThroughTheOneRestart() = runTest {
        Fixture(backgroundScope).use { f ->
            f.patch.optedIn = true
            f.begin(ModulePlan(update = true, alertsOff = true, restart = true))
            advanceUntilIdle()
            val order = f.calls.map { it.first }
            assertTrue(order.indexOf("install") < order.indexOf("keepoff"))
            assertTrue(order.indexOf("keepoff") < order.indexOf("restart"))
            assertFalse(f.patch.optedIn)
            assertEquals(1, f.host.restarts)
            assertEquals(0, f.patch.optIns)
            assertFalse(f.setup.applyAlerts)
        }
    }

    @Test
    fun aRecordThatWasntKeptStopsBeforeTheRestart() = runTest {
        Fixture(backgroundScope).use { f ->
            f.patch.optedIn = true
            f.patch.keepOffFails = true
            f.begin(ModulePlan(install = true, alertsOff = true, restart = true))
            advanceUntilIdle()
            assertEquals(1, f.patch.keptOff)
            assertEquals(0, f.host.restarts)
            assertEquals("notification_alert_write_failed", f.setup.problem)
        }
    }

    @Test
    fun aStaleOptInCannotAuthorizeARestartOrAll() = runTest {
        Fixture(backgroundScope).use { f ->
            f.patch.staleOptIn = true
            f.begin()
            advanceUntilIdle()
            assertEquals(1, f.patch.optIns)
            assertEquals(0, f.host.restarts)
            assertEquals(0, f.patch.applications)
            assertEquals("notification_alert_unconfirmed", f.setup.problem)
            assertFalse(f.setup.applyAlerts)
        }
    }

    @Test
    fun probeModeConsumesTheAutomaticGrantInsteadOfDeferringIt() = runTest {
        Fixture(backgroundScope).use { f ->
            f.patch.loaded = true
            f.patch.mode = NotificationAlertPatchMode.PROBE
            f.begin(ModulePlan(alerts = true))
            advanceUntilIdle()
            assertEquals(0, f.patch.applications)
            assertFalse(f.setup.applyAlerts)
            f.patch.mode = NotificationAlertPatchMode.OBSERVE
            f.viewModel.read()
            advanceUntilIdle()
            assertEquals(0, f.patch.applications)
        }
    }

    @Test
    fun turningOffWhileWaitingRevokesTheRemainingAutomaticStep() = runTest {
        Fixture(backgroundScope).use { f ->
            f.begin()
            runCurrent()
            assertTrue(f.setup.applyAlerts)
            f.viewModel.turnOffAlerts()
            runCurrent()
            assertFalse(f.setup.applyAlerts)
            f.phase(ConnectionPhase.CONNECTING, sessionId = 2)
            runCurrent()
            f.patch.loaded = true
            f.phase(ConnectionPhase.LINK_READY, sessionId = 2)
            advanceUntilIdle()
            assertEquals(0, f.patch.applications)
            f.patch.optedIn = true
            f.patch.mode = NotificationAlertPatchMode.OBSERVE
            f.viewModel.read()
            advanceUntilIdle()
            assertEquals(0, f.patch.applications)
        }
    }

    @Test
    fun cancellingThePlanLeavesNoAutomaticWriteForALaterRefresh() = runTest {
        Fixture(backgroundScope).use { f ->
            f.begin()
            runCurrent()
            assertTrue(f.setup.applyAlerts)
            f.dependencies.applicationScope.coroutineContext.cancelChildren()
            runCurrent()
            assertNull(f.setup.step)
            assertFalse(f.setup.applyAlerts)
            assertNull(f.setup.targetPeer)
            f.patch.loaded = true
            f.viewModel.read()
            advanceUntilIdle()
            assertEquals(0, f.patch.applications)
        }
    }
}
