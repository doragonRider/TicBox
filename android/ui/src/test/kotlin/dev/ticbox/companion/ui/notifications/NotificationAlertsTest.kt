package dev.ticbox.companion.ui.notifications

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.ticbox.companion.api.NotificationAlertMode
import dev.ticbox.companion.api.NotificationAlertPatchClient
import dev.ticbox.companion.api.NotificationAlertPatchMode
import dev.ticbox.companion.api.NotificationAlertPatchPhase
import dev.ticbox.companion.api.NotificationAlertPatchSnapshot
import dev.ticbox.companion.api.NotificationAlertPatchState
import dev.ticbox.companion.demo.DemoWatch
import dev.ticbox.companion.ui.CompanionApp
import dev.ticbox.companion.ui.FakePhoneApps
import dev.ticbox.companion.ui.FakeSessions
import dev.ticbox.companion.ui.NoSystemActions
import dev.ticbox.companion.ui.PhoneApp
import dev.ticbox.companion.ui.TestDependencies
import dev.ticbox.companion.ui.demoWatch
import dev.ticbox.companion.ui.navigation.NotificationsRoute
import dev.ticbox.companion.ui.session
import dev.ticbox.companion.ui.session.SessionState
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private const val APP = "com.example.chat"

private fun snapshot(
    optedIn: Boolean = true,
    mode: NotificationAlertPatchMode? = NotificationAlertPatchMode.ALL,
) =
    NotificationAlertPatchSnapshot(
        optedIn = optedIn,
        optInSurvivedReboot = optedIn,
        running = mode != null,
        hooked = mode != null,
        mode = mode,
        issue = null,
    )

private class FakePatch(
    initial: NotificationAlertPatchState =
        NotificationAlertPatchState(NotificationAlertPatchPhase.READY),
    private val onRead: NotificationAlertPatchSnapshot = snapshot(),
) : NotificationAlertPatchClient {
    var reads = 0
    override val state = MutableStateFlow(initial)

    override suspend fun refresh() {
        reads++
        state.value =
            NotificationAlertPatchState(NotificationAlertPatchPhase.READY, onRead, current = true)
    }

    override suspend fun optIn() = Unit

    override suspend fun optOut() = Unit

    override suspend fun applyToAll() = Unit

    override fun close() = Unit
}

private fun appliedPatch() =
    FakePatch(
        NotificationAlertPatchState(NotificationAlertPatchPhase.READY, snapshot(), current = true)
    )

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h2000dp")
class NotificationAlertsTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var dependencies: TestDependencies

    private fun show(
        patch: FakePatch,
        key: String = "demo",
        remembered: Boolean = false,
    ): DemoWatch {
        val watch = demoWatch(context)
        runBlocking { watch.notifications.setPackageEnabled(APP, true) }
        val session = watch.session(key = key, notificationAlertPatch = patch)
        dependencies =
            TestDependencies(
                FakeSessions(SessionState.Ready(session)),
                FakePhoneApps(listOf(PhoneApp(APP, "Chat"))),
            )
        dependencies.preferences.notificationAlertsApplied.value = remembered
        lateinit var navController: NavHostController
        compose.setContent {
            navController = rememberNavController()
            CompanionApp(dependencies, NoSystemActions, navController)
        }
        compose.waitForIdle()
        compose.runOnUiThread { navController.navigate(NotificationsRoute) }
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("app_$APP")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        return watch
    }

    @Test
    fun withoutTheExtensionNothingIsOfferedAndTheWatchIsNotAsked() {
        val patch = FakePatch()
        show(patch, key = "real")
        compose.onNodeWithTag("alerts_setup").assertExists()
        compose.onNodeWithTag("alerts_sound").assertDoesNotExist()
        compose.onNodeWithTag("alerts_$APP").assertDoesNotExist()
        assertEquals(0, patch.reads)
    }

    @Test
    fun theRowLeadsToTheModulePage() {
        show(FakePatch(), key = "real")
        compose.onNodeWithTag("alerts_setup").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("module_alerts_switch").assertExists()
    }

    @Test
    fun someoneWhoSetThisUpGetsAskedAboutOnce() {
        val patch = FakePatch()
        show(patch, key = "real", remembered = true)
        compose.onNodeWithTag("alerts_sound").assertExists()
        assertEquals(1, patch.reads)
    }

    @Test
    fun whenTheWatchNoLongerAppliesItTheControlsGoAway() {
        val patch = FakePatch(onRead = snapshot(optedIn = false, mode = null))
        show(patch, key = "real", remembered = true)
        compose.onNodeWithTag("alerts_setup").assertExists()
        compose.onNodeWithTag("alerts_sound").assertDoesNotExist()
        assertFalse(dependencies.preferences.notificationAlertsApplied.value)
        assertEquals(1, patch.reads)
    }

    @Test
    fun theTilesSaveTheGlobalSwitches() {
        val watch = show(appliedPatch())
        compose.onNodeWithTag("alerts_sound").performClick()
        compose.waitForIdle()
        assertFalse(watch.notifications.state.value.settings.soundEnabled)
        assertTrue(watch.notifications.state.value.settings.vibrationEnabled)
        compose.onNodeWithTag("alerts_vibration").performClick()
        compose.waitForIdle()
        assertFalse(watch.notifications.state.value.settings.vibrationEnabled)
    }

    @Test
    fun anAppsChoicesAreSavedSeparatelyAndSummedUpInItsRow() {
        val watch = show(appliedPatch())
        compose.onNodeWithText(APP).assertExists()
        compose.onNodeWithTag("alerts_$APP").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("alert_sound_${APP}_ALWAYS").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("alert_vibration_${APP}_NEVER").performClick()
        compose.waitForIdle()
        val alerts = watch.notifications.state.value.settings.alertsFor(APP)
        assertEquals(NotificationAlertMode.ALWAYS, alerts.sound)
        assertEquals(NotificationAlertMode.NEVER, alerts.vibration)
        compose.onNodeWithText("Always ring · Never vibrate").assertExists()
        compose.onNodeWithText(APP).assertDoesNotExist()
    }

    @Test
    fun onlyOneAppIsOpenAndTheRowStillTogglesSending() {
        val watch = show(appliedPatch())
        compose.onNodeWithTag("alerts_$APP").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("alert_sound_${APP}_NEVER").assertExists()
        compose.onNodeWithTag("app_$APP").performClick()
        compose.waitForIdle()
        assertFalse(watch.notifications.state.value.settings.packageAllowed(APP))
        compose.onNodeWithTag("alert_sound_${APP}_NEVER").assertDoesNotExist()
        compose.onNodeWithTag("alerts_$APP").assertDoesNotExist()
    }

    @Test
    fun aGlobalSwitchThatIsOffWinsOverTheAppsChoice() {
        val watch = show(appliedPatch())
        runBlocking {
            watch.notifications.setPackageSoundMode(APP, NotificationAlertMode.ALWAYS)
            watch.notifications.setSoundEnabled(false)
        }
        compose.waitForIdle()
        // A choice that doesn't count right now isn't presented as what will happen.
        compose.onNodeWithText(APP).assertExists()
        compose.onNodeWithTag("alerts_$APP").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("alert_sound_${APP}_NEVER").assertIsNotEnabled()
        compose.onNodeWithTag("alert_vibration_${APP}_NEVER").assertIsEnabled()
        compose.onNodeWithText("Sound is off for all apps", substring = true).assertExists()
        assertEquals(
            NotificationAlertMode.ALWAYS,
            watch.notifications.state.value.settings.alertsFor(APP).sound,
        )
    }

    @Test
    fun theTilesFollowTheMainSwitch() {
        val watch = show(appliedPatch())
        runBlocking { watch.notifications.setEnabled(false) }
        compose.waitForIdle()
        compose.onNodeWithTag("alerts_sound").assertIsNotEnabled()
    }

    @Test
    fun whatTheRowsShowFollowsTheWatchAndElseMemory() {
        val fresh = NotificationAlertPatchState(NotificationAlertPatchPhase.READY)
        assertEquals(NotificationAlertsUi(), notificationAlertsUi(fresh, remembered = false))
        val remembered = notificationAlertsUi(fresh, remembered = true)
        assertTrue(remembered.applied)
        assertTrue(remembered.needsRead)
        // Offline, or a read that failed: what was last said still stands, and isn't asked again.
        val failed = fresh.copy(phase = NotificationAlertPatchPhase.UNAVAILABLE, reason = "x")
        assertEquals(
            NotificationAlertsUi(applied = true),
            notificationAlertsUi(failed, remembered = true),
        )
        val partWay =
            notificationAlertsUi(
                fresh.copy(snapshot = snapshot(mode = null), current = true),
                remembered = true,
            )
        assertEquals(NotificationAlertsUi(setupStarted = true), partWay)
    }
}
