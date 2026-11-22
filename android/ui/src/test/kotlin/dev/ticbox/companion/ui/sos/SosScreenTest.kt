package dev.ticbox.companion.ui.sos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.ticbox.companion.api.CallAuthorizationAction
import dev.ticbox.companion.api.SosAssistancePhase
import dev.ticbox.companion.api.SosAssistanceState
import dev.ticbox.companion.api.SosClient
import dev.ticbox.companion.api.SosCommandResult
import dev.ticbox.companion.api.SosCommandStatus
import dev.ticbox.companion.api.SosEmergencyContact
import dev.ticbox.companion.api.SosFallDetectionMode
import dev.ticbox.companion.api.SosPhase
import dev.ticbox.companion.api.SosPhoneAssistanceClient
import dev.ticbox.companion.api.SosPhoneAssistanceConfig
import dev.ticbox.companion.api.SosPhoneCapabilities
import dev.ticbox.companion.api.SosState
import dev.ticbox.companion.api.SosWatchEditKind
import dev.ticbox.companion.api.SosWatchEditRequest
import dev.ticbox.companion.api.SosWatchSettings
import dev.ticbox.companion.ui.CompanionApp
import dev.ticbox.companion.ui.FakeSessions
import dev.ticbox.companion.ui.SosAccess
import dev.ticbox.companion.ui.SosEditRequests
import dev.ticbox.companion.ui.SystemActions
import dev.ticbox.companion.ui.TestDependencies
import dev.ticbox.companion.ui.demoWatch
import dev.ticbox.companion.ui.navigation.SosRoute
import dev.ticbox.companion.ui.session
import dev.ticbox.companion.ui.session.SessionState
import dev.ticbox.companion.ui.session.WatchSession
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Nothing here opens a dialog holding a text field. Under Robolectric that window never goes idle,
 * and its text layouts run the test JVM out of memory, which fails every later test in that JVM.
 * The editors' fields are shown here on their own instead, and their rules are in
 * [SosFormRulesTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h2000dp")
class SosScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val system = RecordingActions()

    private fun show(sos: SosClient, open: Boolean = true): NavHostController {
        // The demo has no SIMs to choose from.
        val session = demoWatch(context).session(key = "service", sos = sos)
        lateinit var navController: NavHostController
        compose.setContent {
            navController = rememberNavController()
            CompanionApp(
                TestDependencies(FakeSessions(SessionState.Ready(session))),
                system,
                navController,
            )
        }
        compose.waitForIdle()
        if (open) {
            compose.runOnUiThread { navController.navigate(SosRoute()) }
            compose.waitForIdle()
        }
        return navController
    }

    private fun text(value: String) = compose.onNodeWithText(value)

    private fun waitFor(value: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(value, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun switch(title: String) = compose.onNode(hasText(title) and isToggleable())

    @Test
    fun theHomePageLeadsHereAndShowsWhatTheWatchReported() {
        show(FakeSos(), open = false)
        text("SOS").performScrollTo().performClick()
        waitFor("Emergency number")
        text("110").assertExists()
        text("Ana").assertExists()
        text("+86 138 0000 0000").assertExists()
        switch("Button trigger").assertIsOn()
        text("During workouts only").performScrollTo().assertIsEnabled()
    }

    @Test
    fun aChangeGoesToTheWatchAndAFailureSaysWhatIsKnown() {
        val sos = FakeSos(result = SosCommandResult(SosCommandStatus.TIMED_OUT))
        show(sos)
        switch("Button trigger").performClick()
        waitFor("The change may still have been saved")
        assertEquals(listOf<Any>("button" to false), sos.writes)
        // Nothing moves until the watch has reported the new value.
        switch("Button trigger").assertIsOn()
    }

    @Test
    fun aFallModeGoesToTheWatch() {
        val sos = FakeSos()
        show(sos)
        text("All day").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf<Any>("fall" to SosFallDetectionMode.ALWAYS), sos.writes)
    }

    @Test
    fun contactsThatCouldNotBeReadAreNeverGuessedAt() {
        val sos = FakeSos(settings(contacts = null))
        show(sos)
        waitFor("couldn't be read")
        text("+86 138 0000 0000").assertDoesNotExist()
        text("Add contact").performScrollTo().assertIsEnabled()
    }

    @Test
    fun valuesFromAnEarlierConnectionCanBeSeenButNotChanged() {
        val sos = FakeSos(settingsCurrent = false)
        show(sos)
        waitFor("hasn't reported")
        switch("Button trigger").assertIsNotEnabled()
        text("Add contact").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun anUnknownFallModeIsShownAndNoOptionLooksChosen() {
        val sos = FakeSos(settings(fall = 7))
        show(sos)
        waitFor("doesn't know (7)")
    }

    @Test
    fun turningOnAssistanceAsksFirstAndThenAsksForSmsPermission() {
        val sos = FakeSos()
        show(sos)
        switch("Text my emergency contacts").performScrollTo().performClick()
        waitFor("without asking first")
        assertTrue(sos.assistance.configs.isEmpty())

        text("Cancel").performClick()
        compose.waitForIdle()
        assertTrue(sos.assistance.configs.isEmpty())

        switch("Text my emergency contacts").performScrollTo().performClick()
        waitFor("without asking first")
        text("Turn on").performClick()
        compose.waitForIdle()
        assertEquals(listOf(SosPhoneAssistanceConfig(enabled = true)), sos.assistance.configs)
        assertEquals(listOf(SosAccess.SMS), system.requests)
    }

    @Test
    fun whileItsOnEachThingItNeedsCanBeRequested() {
        val sos =
            FakeSos(
                assistance =
                    SosAssistanceState(
                        config = SosPhoneAssistanceConfig(enabled = true),
                        capabilities =
                            SosPhoneCapabilities(
                                smsSupported = true,
                                notificationPermissionGranted = true,
                                usableSmsSubscription = true,
                            ),
                    )
            )
        show(sos)
        text("SMS permission").performScrollTo().performClick()
        assertEquals(listOf(SosAccess.SMS), system.requests)
        text("Notifications").performScrollTo().assertIsEnabled()

        text("SIM for texts").performScrollTo().performClick()
        assertEquals(listOf(SosAccess.SMS, SosAccess.SIM_SELECTION), system.requests)
    }

    @Test
    fun aRunningAlertNeverClaimsDeliveryAndCanBeStopped() {
        val sos =
            FakeSos(
                assistance =
                    SosAssistanceState(
                        config = SosPhoneAssistanceConfig(enabled = true),
                        phase = SosAssistancePhase.ACTIVE,
                        recipientCount = 2,
                        smsPartsSubmitted = 3,
                        expiresAtMillis = 1_000_000L,
                    )
            )
        show(sos)
        waitFor("doesn't mean they arrived")
        switch("Text my emergency contacts").assertIsOn().assertIsNotEnabled()

        text("Stop").performClick()
        waitFor("Stop SMS assistance?")
        assertEquals(0, sos.assistance.stops)
        compose.onAllNodesWithText("Stop").onLast().performClick()
        compose.waitForIdle()
        assertEquals(1, sos.assistance.stops)
    }

    @Test
    fun aRequestFromTheWatchIsPassedOnOnceEachEvenAfterCollectingAgain() {
        val sos = FakeSos()
        val session = demoWatch(context).session(key = "service", sos = sos)
        val opened = mutableListOf<Long>()
        var current by mutableStateOf<WatchSession?>(session)
        compose.setContent { SosEditRequests(current) { opened += it.sequence } }
        compose.waitForIdle()
        assertEquals(emptyList(), opened)

        fun request(sequence: Long, kind: SosWatchEditKind) {
            sos.state.value =
                sos.state.value.copy(lastWatchEditRequest = SosWatchEditRequest(sequence, kind, 5))
            compose.waitForIdle()
        }
        request(1, SosWatchEditKind.EMERGENCY_NUMBER)
        assertEquals(listOf(1L), opened)

        compose.runOnIdle { current = null }
        compose.waitForIdle()
        compose.runOnIdle { current = session }
        compose.waitForIdle()
        assertEquals(listOf(1L), opened)

        request(2, SosWatchEditKind.CONTACTS)
        assertEquals(listOf(1L, 2L), opened)
    }

    @Test
    fun aContactsFieldsSayWhatIsWrongWithTheNumber() {
        var number by mutableStateOf("")
        compose.setContent { ContactFields("Bo", number, onName = {}, onNumber = { number = it }) }
        val field = compose.onAllNodes(hasSetTextAction())[1]

        field.performTextInput("+-")
        waitFor("at least one digit")
        field.performTextClearance()
        field.performTextInput("*123#a")
        waitFor("can't receive SMS")
        assertEquals("*123#", number)
    }

    @Test
    fun theEmergencyNumbersFieldKeepsOnlyWhatTheWatchAccepts() {
        var number by mutableStateOf("110")
        compose.setContent { EmergencyNumberField(number) { number = it } }
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.waitForIdle()
        assertEquals("", number)
        compose.onNode(hasSetTextAction()).performTextInput("12a3")
        compose.waitForIdle()
        assertEquals("123", number)
    }

    @Test
    fun theShareLocationSwitchIsTheWatchsAndWritesThere() {
        val sos = FakeSos()
        show(sos)
        switch("Include my location").performScrollTo().assertIsOff().performClick()
        compose.waitForIdle()
        assertEquals(listOf<Any>("location" to true), sos.writes)
    }

    private companion object {
        val ANA = SosEmergencyContact("Ana", "+86 138 0000 0000")

        fun settings(
            contacts: List<SosEmergencyContact>? = listOf(ANA),
            fall: Int = SosFallDetectionMode.OFF.wireValue,
        ) =
            SosWatchSettings(
                updatedAtMillis = 1,
                buttonTriggerEnabled = true,
                emergencyNumber = "110",
                fallDetectionValue = fall,
                shareLocation = false,
                contacts = contacts,
                contactsPayload = "",
            )
    }

    private class FakeAssistance(initial: SosAssistanceState) : SosPhoneAssistanceClient {
        override val state = MutableStateFlow(initial)
        val configs = mutableListOf<SosPhoneAssistanceConfig>()
        var stops = 0

        override suspend fun refreshCapabilities() = Unit

        override suspend fun configure(config: SosPhoneAssistanceConfig) {
            configs += config
            state.value = state.value.copy(config = config)
        }

        override suspend fun stop() {
            stops++
        }

        override fun close() = Unit
    }

    private class FakeSos(
        settings: SosWatchSettings = settings(),
        settingsCurrent: Boolean = true,
        assistance: SosAssistanceState = SosAssistanceState(),
        private val result: SosCommandResult = SosCommandResult(SosCommandStatus.CONFIRMED),
    ) : SosClient {
        override val state =
            MutableStateFlow(
                SosState(
                    sessionId = 5,
                    phase = SosPhase.CONNECTED,
                    settings = settings,
                    settingsCurrent = settingsCurrent,
                )
            )
        val assistance = FakeAssistance(assistance)
        override val phoneAssistance: SosPhoneAssistanceClient = this.assistance
        val writes = mutableListOf<Any>()

        private fun write(field: String, value: Any): SosCommandResult {
            writes += field to value
            return result
        }

        override suspend fun refresh() = result

        override suspend fun setEmergencyNumber(number: String) = write("number", number)

        override suspend fun setContacts(contacts: List<SosEmergencyContact>) =
            write("contacts", contacts)

        override suspend fun setButtonTrigger(enabled: Boolean) = write("button", enabled)

        override suspend fun setShareLocation(enabled: Boolean) = write("location", enabled)

        override suspend fun setFallDetection(mode: SosFallDetectionMode) = write("fall", mode)

        override fun close() = Unit
    }

    private class RecordingActions : SystemActions {
        val requests = mutableListOf<SosAccess>()

        override fun openNotificationAccess() = Unit

        override fun openAppNotificationSettings() = Unit

        override fun requestCallingAccess(action: CallAuthorizationAction) = Unit

        override fun requestSosAccess(access: SosAccess) {
            requests += access
        }

        override fun sosSmsSubscriptions() = emptyList<Int>()
    }
}
