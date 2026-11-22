package dev.ticbox.companion.ui

import android.app.Activity
import androidx.activity.BackEventCompat
import androidx.activity.BackEventCompat.Companion.EDGE_LEFT
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.ticbox.companion.api.CallCommand
import dev.ticbox.companion.api.CloudCapability
import dev.ticbox.companion.api.ConnectionRecoveryClient
import dev.ticbox.companion.api.ConnectionRecoveryState
import dev.ticbox.companion.api.HealthPointPage
import dev.ticbox.companion.api.HealthPointQuery
import dev.ticbox.companion.api.HealthPointType
import dev.ticbox.companion.api.HealthRecordsClient
import dev.ticbox.companion.api.LocalHealthSyncClient
import dev.ticbox.companion.api.LocalHealthSyncMode
import dev.ticbox.companion.api.LocalHealthSyncStage
import dev.ticbox.companion.api.LocalHealthSyncState
import dev.ticbox.companion.api.LocalHealthSyncTiming
import dev.ticbox.companion.api.NotificationFilterMode
import dev.ticbox.companion.api.PairingAssociation
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.api.WatchClient
import dev.ticbox.companion.api.WatchUnpairClient
import dev.ticbox.companion.api.WatchUnpairPhase
import dev.ticbox.companion.api.WatchUnpairResult
import dev.ticbox.companion.api.WatchUnpairState
import dev.ticbox.companion.api.WatchUnpairSupport
import dev.ticbox.companion.demo.DemoWatch
import dev.ticbox.companion.ui.navigation.AccountRoute
import dev.ticbox.companion.ui.navigation.CallingRoute
import dev.ticbox.companion.ui.navigation.ConnectionRoute
import dev.ticbox.companion.ui.navigation.DiagnosticsRoute
import dev.ticbox.companion.ui.navigation.HomeRoute
import dev.ticbox.companion.ui.navigation.PairingRoute
import dev.ticbox.companion.ui.navigation.PhoneTransferRoute
import dev.ticbox.companion.ui.navigation.PhotoFaceRoute
import dev.ticbox.companion.ui.navigation.SetupRoute
import dev.ticbox.companion.ui.navigation.StepGoalRoute
import dev.ticbox.companion.ui.navigation.WatchAppsRoute
import dev.ticbox.companion.ui.navigation.WatchFacesRoute
import dev.ticbox.companion.ui.navigation.WatchLockRoute
import dev.ticbox.companion.ui.navigation.closePage
import dev.ticbox.companion.ui.navigation.resetToHome
import dev.ticbox.companion.ui.navigation.returnToWatchHome
import dev.ticbox.companion.ui.session.SessionState
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionAppTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()

    private lateinit var backDispatcher: OnBackPressedDispatcher

    private lateinit var activity: Activity

    private fun show(dependencies: TestDependencies): () -> NavHostController {
        lateinit var navController: NavHostController
        compose.setContent {
            navController = rememberNavController()
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            activity = LocalActivity.current!!
            CompanionApp(dependencies, NoSystemActions, navController)
        }
        return { navController }
    }

    private fun startBackGesture() {
        compose.runOnUiThread {
            backDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 800f, 0f, EDGE_LEFT))
            backDispatcher.dispatchOnBackProgressed(BackEventCompat(300f, 800f, 0.5f, EDGE_LEFT))
        }
        compose.waitForIdle()
    }

    @Test
    fun anUnavailableServiceOffersRetry() {
        val sessions = FakeSessions(SessionState.Unavailable)
        show(TestDependencies(sessions))
        compose.onNodeWithText("Companion service unavailable").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, sessions.retries)
    }

    @Test
    fun anUnpairedPhoneIsWelcomedWithPairingAndTheDemo() {
        val demo = demoWatch(context)
        val emptyRecovery =
            object : ConnectionRecoveryClient {
                override val state = MutableStateFlow(ConnectionRecoveryState(durable = true))

                override suspend fun resume() = Unit
            }
        val session =
            demo.session(key = "service", client = UnpairedClient()).let {
                it.copy(services = it.services.copy(recovery = emptyRecovery))
            }
        val sessions = FakeSessions(SessionState.Ready(session))
        show(TestDependencies(sessions))
        compose.onNodeWithText("Connect your TicWatch").assertIsDisplayed()
        compose.onNodeWithTag("try_demo").performClick()
        assertEquals(1, sessions.demoRequests)
        compose.onNodeWithTag("demo_banner").assertDoesNotExist()
    }

    @Test
    fun aColdPausedWatchKeepsItsHomeAndCanResumeWithoutPairing() {
        val demo = demoWatch(context)
        val pairingBefore = demo.pairing.state.value
        val peer = demo.client.state.value.connection.peer!!
        var resumes = 0
        val recovery =
            object : ConnectionRecoveryClient {
                override val state =
                    MutableStateFlow(
                        ConnectionRecoveryState(
                            desiredPeer = peer,
                            associationId = 7,
                            durable = true,
                            paused = true,
                        )
                    )

                override suspend fun resume() {
                    resumes++
                }
            }
        val session =
            demo.session(key = "service", client = UnpairedClient()).let {
                it.copy(services = it.services.copy(recovery = recovery))
            }
        val navigation = show(TestDependencies(FakeSessions(SessionState.Ready(session))))
        compose.onNodeWithText("Connect your TicWatch").assertDoesNotExist()
        compose.onNodeWithText("Connection").performScrollTo().performClick()
        compose
            .onNodeWithText("Automatic reconnection paused")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Reconnect").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, resumes)
        assertTrue(navigation().currentDestination!!.hasRoute<ConnectionRoute>())
        assertEquals(pairingBefore, demo.pairing.state.value)
    }

    @Test
    fun anExistingSystemWatchNeedsAnExplicitReconnectAndDoesNotOpenSetup() {
        val demo = demoWatch(context)
        val pairingBefore = demo.pairing.state.value
        val peer = demo.client.state.value.connection.peer!!
        val connects = mutableListOf<PeerAddress>()
        val client =
            object : WatchClient by UnpairedClient() {
                override suspend fun connect(peer: PeerAddress) {
                    connects += peer
                }
            }
        val recovery =
            object : ConnectionRecoveryClient {
                override val state =
                    MutableStateFlow(
                        ConnectionRecoveryState(
                            durable = true,
                            pairedWatches =
                                listOf(PairingAssociation(7, peer, "Saved TicWatch", true)),
                        )
                    )

                override suspend fun resume() = Unit
            }
        val session =
            demo.session(key = "service", client = client).let {
                it.copy(services = it.services.copy(recovery = recovery))
            }
        val navigation = show(TestDependencies(FakeSessions(SessionState.Ready(session))))
        compose.onNodeWithText("Previously paired watches").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Saved TicWatch").performScrollTo().assertIsDisplayed()
        assertTrue(connects.isEmpty())
        compose.onNodeWithTag("reconnect_saved_watch_7").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(peer), connects)
        assertTrue(navigation().currentDestination!!.hasRoute<HomeRoute>())
        assertEquals(pairingBefore, demo.pairing.state.value)
    }

    @Test
    fun theDemoWatchRendersLikeARealOneWithABanner() {
        val demo = demoWatch(context)
        show(TestDependencies(FakeSessions(SessionState.Ready(demo.session()))))
        compose.onNodeWithTag("demo_banner").assertIsDisplayed()
        compose.onNodeWithText("TicWatch Pro 5 Enduro (demo)").assertIsDisplayed()
        compose.onAllNodesWithText("Connected").onFirst().assertIsDisplayed()
        compose.onNodeWithTag("tab_WATCH").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-rUS-w320dp-h891dp")
    fun theShortcutsStayTheSameHeightWhenOnlyOneWraps() {
        val demo = demoWatch(context)
        show(TestDependencies(FakeSessions(SessionState.Ready(demo.session()))))
        fun height(title: String) = compose.onNodeWithText(title).fetchSemanticsNode().size.height
        assertEquals(height("Watch faces"), height("Notifications"))
    }

    @Test
    fun theWatchPageRefreshesTheBatteryByItself() {
        val demo = demoWatch(context)
        var requests = 0
        val client =
            object : WatchClient by demo.client {
                override suspend fun refreshBattery() {
                    requests++
                }
            }
        show(TestDependencies(FakeSessions(SessionState.Ready(demo.session(client = client)))))
        compose.waitUntil(10_000) { requests == 1 }
    }

    @Test
    fun diagnosticsCanStartAFullHealthSync() {
        val demo = demoWatch(context)
        val navigation = show(TestDependencies(FakeSessions(SessionState.Ready(demo.session()))))
        compose.runOnUiThread { navigation().navigate(DiagnosticsRoute) }
        compose
            .onNodeWithText("Sync all health records now")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.waitForIdle()
        assertEquals(LocalHealthSyncMode.FULL, demo.localHealth.state.value.mode)
    }

    @Test
    fun aSupportCheckShowsItsResultOnceAndOnlyOnThePage() {
        val demo = demoWatch(context)
        val unpair =
            object : WatchUnpairClient {
                override val state =
                    MutableStateFlow(
                        WatchUnpairState(
                            phase = WatchUnpairPhase.IDLE,
                            support = WatchUnpairSupport.UNKNOWN,
                            reason = "fresh_home_info_required",
                        )
                    )

                override suspend fun probe(): WatchUnpairState {
                    state.value =
                        WatchUnpairState(
                            phase = WatchUnpairPhase.IDLE,
                            support = WatchUnpairSupport.SUPPORTED,
                        )
                    return state.value
                }

                override suspend fun unpairWithoutReset(): WatchUnpairResult =
                    error("Checking support must not unpair")

                override fun close() = Unit
            }
        val session = demo.session().let { it.copy(services = it.services.copy(unpair = unpair)) }
        val navigation = show(TestDependencies(FakeSessions(SessionState.Ready(session))))
        compose.runOnUiThread { navigation().navigate(PhoneTransferRoute) }
        compose.onNodeWithText("Check watch support").performScrollTo().performClick()
        compose.waitForIdle()
        compose
            .onAllNodesWithText(context.getString(R.string.unpair_support_observed))
            .assertCountEquals(1)
    }

    @Test
    fun theDiagnosticsOverviewShowsOnlyTheTotalSyncTimeUntilOpened() {
        val demo = demoWatch(context)
        val timing =
            LocalHealthSyncTiming(
                startedAtMillis = 0,
                totalMillis = 6_994,
                stages =
                    listOf(
                        LocalHealthSyncStage("data_point", "read", 2_301, count = 6),
                        LocalHealthSyncStage("data_point", "ack", 251, count = 6),
                    ),
            )
        val localHealth =
            object : LocalHealthSyncClient {
                override val state = MutableStateFlow(LocalHealthSyncState(lastRun = timing))

                override suspend fun sync(mode: LocalHealthSyncMode) = Unit

                override suspend fun syncIfStale() = Unit

                override suspend fun cancel() = Unit

                override fun close() = Unit
            }
        val session =
            demo.session().let { it.copy(services = it.services.copy(localHealth = localHealth)) }
        val navigation = show(TestDependencies(FakeSessions(SessionState.Ready(session))))
        compose.runOnUiThread { navigation().navigate(DiagnosticsRoute) }
        compose.waitForIdle()
        val total = context.getString(R.string.diagnostics_millis, "6,994")
        compose.onNodeWithText(total).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("read").assertCountEquals(0)
        compose.onNodeWithText(context.getString(R.string.diagnostics_sync_timing)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("read").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2,301", substring = true).assertIsDisplayed()
    }

    @Test
    fun anInterruptedSupportCheckStillReportsItsResultAfterTheLiveSessionChanges() {
        val demo = demoWatch(context)
        val release = CompletableDeferred<Unit>()
        var unpairCalls = 0
        val unpair =
            object : WatchUnpairClient {
                override val state =
                    MutableStateFlow(
                        WatchUnpairState(
                            phase = WatchUnpairPhase.IDLE,
                            support = WatchUnpairSupport.UNKNOWN,
                            reason = "fresh_home_info_required",
                        )
                    )

                override suspend fun probe(): WatchUnpairState {
                    state.value =
                        state.value.copy(phase = WatchUnpairPhase.PROBING, commandRunning = true)
                    release.await()
                    val result = WatchUnpairState(sessionId = 1, reason = "watch_link_ended")
                    state.value =
                        WatchUnpairState(
                            phase = WatchUnpairPhase.IDLE,
                            sessionId = 2,
                            support = WatchUnpairSupport.UNKNOWN,
                            reason = "fresh_home_info_required",
                        )
                    return result
                }

                override suspend fun unpairWithoutReset(): WatchUnpairResult {
                    unpairCalls++
                    error("Checking support must not unpair")
                }

                override fun close() = Unit
            }
        val session = demo.session().let { it.copy(services = it.services.copy(unpair = unpair)) }
        val navigation = show(TestDependencies(FakeSessions(SessionState.Ready(session))))
        compose.runOnUiThread { navigation().navigate(PhoneTransferRoute) }
        compose.onNodeWithText("Check watch support").performScrollTo().performClick()
        compose.onNodeWithText("Check watch support").assertIsNotEnabled()
        compose.runOnUiThread { release.complete(Unit) }
        compose.waitForIdle()
        compose
            .onNodeWithText(context.getString(R.string.unpair_check_interrupted))
            .assertIsDisplayed()
        compose.onNodeWithText("Check watch support").assertIsEnabled()
        assertEquals(0, unpairCalls)
    }

    @Test
    fun healthCanLeaveOutWhatTheWatchHasNeverRecorded() {
        val demo = demoWatch(context)
        val records =
            object : HealthRecordsClient by demo.healthRecords {
                override suspend fun points(query: HealthPointQuery) =
                    when (query.typeCode) {
                        HealthPointType.VO2MAX.code,
                        HealthPointType.TODAY_MOOD.code -> HealthPointPage(emptyList(), null)
                        HealthPointType.TEMPERATURE.code -> error("fixture storage failure")
                        else -> demo.healthRecords.points(query)
                    }
            }
        val dependencies =
            TestDependencies(
                FakeSessions(SessionState.Ready(demo.session(healthRecords = records)))
            )
        show(dependencies)
        fun scrollTo(key: String) {
            compose.onNodeWithTag("tab_HEALTH").performClick()
            compose.waitForIdle()
            compose.onNode(hasScrollToKeyAction()).performScrollToKey(key)
            compose.waitForIdle()
        }

        scrollTo("body")
        compose.onNodeWithTag("metric_VO2MAX").assertExists()
        scrollTo("logs")
        compose.onNodeWithTag("log_MOOD").assertExists()

        compose.onNodeWithTag("tab_SETTINGS").performClick()
        compose.onNodeWithText("Hide metrics with no data").performScrollTo().performClick()
        assertEquals(true, dependencies.preferences.hideEmptyHealthMetrics.value)

        scrollTo("body")
        compose.onNodeWithTag("metric_VO2MAX").assertDoesNotExist()
        compose.onNodeWithTag("metric_TEMPERATURE").assertExists()
        compose.onNodeWithTag("metric_NOISE").assertExists()
        scrollTo("logs")
        compose.onNodeWithTag("log_MOOD").assertDoesNotExist()
        compose.onNodeWithTag("log_BODY_MIND").assertExists()
    }

    @Test
    fun aNapIsShownApartFromTheNight() {
        val demo = demoWatch(context)
        show(TestDependencies(FakeSessions(SessionState.Ready(demo.session()))))
        compose.onNodeWithTag("tab_HEALTH").performClick()
        // Two days back always has the sample nap, whatever time the test runs.
        repeat(2) {
            compose.onNodeWithContentDescription("Previous day").performClick()
            compose.waitForIdle()
        }
        waitForText("Including 1 nap")
        compose.onNodeWithTag("metric_SLEEP").performClick()
        compose.onNodeWithText("Main sleep").assertIsDisplayed()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("chart_1")
        compose.onNodeWithText("Nap").assertIsDisplayed()
    }

    @Test
    fun healthTabShowsMetricsAndDetailPagesGoBack() {
        val demo = demoWatch(context)
        val navigation = show(TestDependencies(FakeSessions(SessionState.Ready(demo.session()))))
        compose.onNodeWithTag("tab_HEALTH").performClick()
        compose.onNodeWithText("Synced just now").assertIsDisplayed()
        // Yesterday always has a full day of sample data, whatever time the test runs.
        compose.onNodeWithContentDescription("Previous day").performClick()
        compose.waitUntil(5_000) {
            compose
                .onAllNodes(hasTestTag("metric_HEART") and hasText("bpm", substring = true))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("metric_HEART").performClick()
        compose.onNodeWithText("Heart rate").assertIsDisplayed()
        compose.onNodeWithTag("period_WEEK").performClick()
        compose.onNodeWithText("Daily average").assertIsDisplayed()

        compose.runOnUiThread { navigation().navigateUp() }
        compose.onNodeWithTag("metric_HEART").assertIsDisplayed()
        compose.onNodeWithTag("main_navigation").assertIsDisplayed()
    }

    @Test
    fun theDatePickerCardResizesInAWindowThatStaysPut() {
        val demo = demoWatch(context)
        show(TestDependencies(FakeSessions(SessionState.Ready(demo.session()))))
        compose.onNodeWithTag("tab_HEALTH").performClick()
        compose.onNodeWithTag("health_date").performClick()
        val window = compose.onNode(isDialog())
        val windowHeight = window.fetchSemanticsNode().size.height
        val calendarButtons = compose.onNodeWithText("Cancel").getBoundsInRoot().top

        compose.onNodeWithContentDescription("Switch to text input mode").performClick()
        compose.waitForIdle()
        assertEquals(windowHeight, window.fetchSemanticsNode().size.height)
        assertTrue(compose.onNodeWithText("Cancel").getBoundsInRoot().top < calendarButtons)

        // The card keeps taps to itself; outside it they close the dialog.
        compose.onNodeWithText("Select date").performClick()
        window.assertExists()
        window.performTouchInput { click(Offset(1f, 1f)) }
        window.assertDoesNotExist()
    }

    @Test
    fun theDatePickerButtonsStayAboveTheKeyboard() {
        val demo = demoWatch(context)
        show(TestDependencies(FakeSessions(SessionState.Ready(demo.session()))))
        compose.onNodeWithTag("tab_HEALTH").performClick()
        compose.onNodeWithTag("health_date").performClick()
        compose.onNodeWithContentDescription("Switch to text input mode").performClick()
        val window = compose.onNode(isDialog()).fetchSemanticsNode()
        val keyboardTop = window.size.height / 2
        val keyboard =
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, keyboardTop))
                .setVisible(WindowInsetsCompat.Type.ime(), true)
                .build()

        compose.runOnUiThread {
            (window.root as ViewRootForTest)
                .view
                .dispatchApplyWindowInsets(keyboard.toWindowInsets())
        }
        compose.waitForIdle()

        val buttons = compose.onNodeWithText("Cancel").fetchSemanticsNode().boundsInRoot
        assertTrue(buttons.bottom <= keyboardTop, "buttons end at ${buttons.bottom}")
        assertEquals(
            window.size.height,
            compose.onNode(isDialog()).fetchSemanticsNode().size.height,
        )
    }

    @Test
    fun backLeavesTheAppFromEveryTabAndTabsKeepTheirState() {
        val (_, navigation) = showDemo()
        fun assertBackIsTheSystems() {
            compose.waitForIdle()
            assertNull(navigation().previousBackStackEntry)
            assertFalse(backDispatcher.hasEnabledCallbacks())
        }
        assertBackIsTheSystems()
        compose.onNodeWithTag("tab_HEALTH").performClick()
        assertBackIsTheSystems()
        compose.onNodeWithContentDescription("Previous day").performClick()
        waitForText("Yesterday")
        compose.onNodeWithTag("tab_SETTINGS").performClick()
        assertBackIsTheSystems()
        compose.onNodeWithTag("tab_WATCH").performClick()
        assertBackIsTheSystems()
        compose.onNodeWithTag("tab_HEALTH").performClick()
        assertBackIsTheSystems()
        compose.onNodeWithText("Yesterday").assertIsDisplayed()

        // A page on top of a tab still goes back to it.
        compose.onNodeWithTag("tab_SETTINGS").performClick()
        compose.onNodeWithText("Language").performClick()
        compose.waitForIdle()
        assertTrue(backDispatcher.hasEnabledCallbacks())
        compose.runOnUiThread { backDispatcher.onBackPressed() }
        compose.onNodeWithTag("tab_SETTINGS").assertIsDisplayed()
        assertBackIsTheSystems()
    }

    @Test
    fun aSecondBackWhileAPageIsClosingStaysInTheApp() {
        val (_, navigation) = showDemo()
        compose.onNodeWithTag("tab_SETTINGS").performClick()
        compose.onNodeWithText("Language").performClick()
        compose.runOnUiThread {
            navigation().closePage()
            navigation().closePage()
        }
        compose.onNodeWithTag("tab_SETTINGS").assertIsDisplayed()
        assertFalse(activity.isFinishing)
    }

    @Test
    fun pairingStartedOnTheHealthTabEndsOnTheWatchHome() {
        val (_, navigation) = showDemo()
        compose.onNodeWithTag("tab_HEALTH").performClick()
        compose.onNodeWithContentDescription("Previous day").performClick()
        waitForText("Yesterday")
        compose.runOnUiThread {
            navigation().navigate(PairingRoute)
            navigation().navigate(SetupRoute) { popUpTo<PairingRoute> { inclusive = true } }
        }
        compose.waitForIdle()
        compose.runOnUiThread { navigation().returnToWatchHome() }
        compose.onNodeWithTag("tab_WATCH").assertIsDisplayed()
        assertTrue(navigation().currentDestination!!.hasRoute<HomeRoute>())
        assertNull(navigation().previousBackStackEntry)
        compose.onNodeWithTag("tab_HEALTH").performClick()
        compose.onNodeWithText("Yesterday").assertIsDisplayed()
    }

    @Test
    fun aResetForgetsWhatEveryTabSaved() {
        val (_, navigation) = showDemo()
        compose.onNodeWithTag("tab_HEALTH").performClick()
        compose.onNodeWithContentDescription("Previous day").performClick()
        waitForText("Yesterday")
        compose.onNodeWithTag("tab_SETTINGS").performClick()
        compose.runOnUiThread { navigation().resetToHome() }
        compose.onNodeWithTag("tab_WATCH").assertIsDisplayed()
        val home = navigation().currentBackStackEntry!!.id
        compose.onNodeWithTag("tab_HEALTH").performClick()
        compose.onNodeWithText("Yesterday").assertDoesNotExist()
        compose.onNodeWithTag("tab_WATCH").performClick()
        assertEquals(home, navigation().currentBackStackEntry!!.id)
    }

    @Test
    fun aBackGestureCanBeCancelledOrCompleted() {
        val demo = demoWatch(context)
        show(TestDependencies(FakeSessions(SessionState.Ready(demo.session()))))
        val languagePage = hasText("You can also change this", substring = true)
        compose.onNodeWithTag("tab_SETTINGS").performClick()
        compose.onNodeWithText("Language").performClick()
        compose.onNodeWithTag("main_navigation").assertDoesNotExist()

        // Mid-gesture the tab page, with its navigation bar, is revealed underneath.
        startBackGesture()
        compose.onNode(languagePage).assertExists()
        compose.onNodeWithTag("main_navigation").assertExists()
        compose.runOnUiThread { backDispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        compose.onNode(languagePage).assertIsDisplayed()
        compose.onNodeWithTag("main_navigation").assertDoesNotExist()

        startBackGesture()
        compose.runOnUiThread { backDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNode(languagePage).assertDoesNotExist()
        compose.onNodeWithTag("main_navigation").assertIsDisplayed()
    }

    @Test
    fun aCheckedAppMeansItsNotificationsReachTheWatch() {
        val demo = demoWatch(context)
        val apps =
            FakePhoneApps(
                listOf(PhoneApp("com.example.mail", "Mail"), PhoneApp("com.example.chat", "Chat"))
            )
        show(TestDependencies(FakeSessions(SessionState.Ready(demo.session())), apps))
        compose.onNodeWithText("Notifications").performClick()
        compose.onNodeWithTag("app_com.example.chat").assertIsOn()
        compose.onNodeWithTag("app_com.example.chat").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("app_com.example.chat").assertIsOff()
        assertTrue("com.example.chat" in demo.notifications.state.value.settings.blockedPackages)
        assertEquals(NotificationFilterMode.BLOCKLIST, demo.notifications.state.value.settings.mode)
        compose.onNodeWithTag("app_com.example.mail").assertIsOn()
    }

    /** Shows the demo watch on [route], or on the app's start page without one. */
    private fun showDemo(route: Any? = null): Pair<DemoWatch, () -> NavHostController> {
        val demo = demoWatch(context)
        val navigation = show(TestDependencies(FakeSessions(SessionState.Ready(demo.session()))))
        compose.waitForIdle()
        route?.let { compose.runOnUiThread { navigation().navigate(it) } }
        return demo to navigation
    }

    private fun waitForText(text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForNoText(text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun workoutsOpenFromTheHealthTab() {
        showDemo()
        compose.onNodeWithTag("tab_HEALTH").performClick()
        waitForText("Outdoor run")
        compose.onNodeWithText("Outdoor run").performScrollTo().performClick()
        waitForText("Pool swim")
        compose.onNodeWithText("Outdoor run").performClick()
        waitForText("Route")
        compose.onNodeWithContentDescription("Route with 361 points").assertExists()
    }

    @Test
    fun aCallInProgressCanBeHungUp() {
        val (demo) = showDemo(CallingRoute)
        runBlocking { demo.calling.execute(CallCommand.Dial("+1 555 0100")) }
        waitForText("Hang up")
        compose.onNodeWithText("Hang up").performClick()
        waitForNoText("Hang up")
    }

    @Test
    fun theAccountStaysOfflineUntilTheCloudSwitchIsOn() {
        val (demo) = showDemo(AccountRoute)
        val cloudOff = "Mobvoi cloud is off, so nothing here can reach the internet."
        val signIn = hasText("Sign in") and hasClickAction()
        waitForText(cloudOff)
        compose.onNodeWithText("Email or phone").performTextInput("demo@example.com")
        compose.onNode(hasText("Password") and hasSetTextAction()).performTextInput("secret")
        compose.onNode(signIn).assertIsNotEnabled()

        runBlocking { demo.privacy.setEnabled(CloudCapability.MOBVOI_CLOUD, true) }
        waitForNoText(cloudOff)
        compose.onNode(signIn).performClick()
        waitForText("Sign out")
        compose.onNodeWithText("Sign out").performClick()
        compose
            .onNode(hasText("Sign out") and hasClickAction() and hasAnyAncestor(isDialog()))
            .performClick()
        waitForText("Sign in")
    }

    @Test
    fun aFavoriteFaceMovesFromItsMenu() {
        showDemo(WatchFacesRoute)
        waitForText("Essential")
        fun left(name: String) = compose.onNodeWithText(name).getBoundsInRoot().left
        assertTrue(left("Essential") < left("Sport"))
        compose.onNodeWithContentDescription("Options for Essential").performClick()
        compose.onNodeWithText("Move later").performClick()
        compose.waitUntil(5_000) { left("Essential") > left("Sport") }
    }

    @Test
    fun thePhotoFacePageFinishesLoading() {
        showDemo(PhotoFaceRoute)
        waitForText("Use this face")
    }

    @Test
    fun watchAppsAndTheLockHandOverToTheWatch() {
        val (_, navigation) = showDemo(WatchAppsRoute)
        waitForText("Weather")
        compose.onNodeWithContentDescription("Uninstall Weather").assertIsEnabled()
        compose.runOnUiThread { navigation().navigate(WatchLockRoute) }
        waitForText("Choose a lock type")
        compose.onNodeWithText("PIN").performClick()
        waitForText("Continue on the watch to set the lock.")
    }

    @Test
    fun aStepGoalNeedsNeitherTheCloudNorAChangeOnTheWatch() {
        val demo = demoWatch(context)
        val session = demo.session(key = "service")
        val dependencies = TestDependencies(FakeSessions(SessionState.Ready(session)))
        val navigation = show(dependencies)
        waitForText("Goal 8,000")

        compose.runOnUiThread { navigation().navigate(StepGoalRoute) }
        compose.onNode(hasSetTextAction()).performTextReplacement("12000")
        compose.onNodeWithText("Save").performClick()
        waitForText("Use the watch's goal")
        assertEquals(12_000, dependencies.preferences.stepGoal.value)
        assertEquals(8_000, demo.client.state.value.steps?.value?.goal)
        compose.onNodeWithText("Also change it on the watch").assertExists()

        compose.runOnUiThread { navigation().closePage() }
        waitForText("Goal 12,000")

        compose.runOnUiThread { navigation().navigate(StepGoalRoute) }
        waitForText("Use the watch's goal")
        compose.onNodeWithText("Use the watch's goal").performClick()
        compose.waitForIdle()
        assertEquals(null, dependencies.preferences.stepGoal.value)
        compose.runOnUiThread { navigation().closePage() }
        waitForText("Goal 8,000")
    }

    @Test
    fun changingTheGoalOnTheWatchWarnsFirstAndLeavesTheCloudSwitchOff() {
        val demo = demoWatch(context)
        val session = demo.session(key = "service")
        val dependencies = TestDependencies(FakeSessions(SessionState.Ready(session)))
        dependencies.preferences.setStepGoal(12_000)
        val navigation = show(dependencies)
        compose.waitForIdle()
        compose.runOnUiThread { navigation().navigate(StepGoalRoute) }
        val row = "Also change it on the watch"
        waitForText(row)

        compose.onNodeWithText(row).performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertEquals(8_000, demo.client.state.value.steps?.value?.goal)

        compose.onNodeWithText(row).performClick()
        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()
        // The demo watch takes a moment, and the main looper's clock only moves when told to.
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        compose.waitUntil(5_000) { demo.client.state.value.steps?.value?.goal == 12_000 }
        assertEquals(emptySet(), demo.privacy.state.value.enabledCapabilities)
        waitForNoText(row)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp")
    fun chineseUsesTranslatedTextAndNumbers() {
        val demo = demoWatch(context)
        show(TestDependencies(FakeSessions(SessionState.Ready(demo.session()))))
        compose.onNodeWithText("健康").assertIsDisplayed()
        compose.onNodeWithText("设置").assertIsDisplayed()
        compose.onNodeWithText("今日步数").assertIsDisplayed()
        compose.onNodeWithText("目标 8,000").assertIsDisplayed()
    }
}
