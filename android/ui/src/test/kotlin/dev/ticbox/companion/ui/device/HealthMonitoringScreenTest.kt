package dev.ticbox.companion.ui.device

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.ticbox.companion.api.MonitoringChoice
import dev.ticbox.companion.api.MonitoringCommandResult
import dev.ticbox.companion.api.MonitoringCommandStatus
import dev.ticbox.companion.api.MonitoringPhase
import dev.ticbox.companion.api.MonitoringSettingDescriptor
import dev.ticbox.companion.api.MonitoringSettingId
import dev.ticbox.companion.api.MonitoringSettingObservation
import dev.ticbox.companion.api.MonitoringSettingsClient
import dev.ticbox.companion.api.MonitoringSettingsState
import dev.ticbox.companion.api.MonitoringSource
import dev.ticbox.companion.api.MonitoringValue
import dev.ticbox.companion.api.MonitoringValueKind
import dev.ticbox.companion.ui.CompanionApp
import dev.ticbox.companion.ui.FakeSessions
import dev.ticbox.companion.ui.NoSystemActions
import dev.ticbox.companion.ui.TestDependencies
import dev.ticbox.companion.ui.demoWatch
import dev.ticbox.companion.ui.navigation.HealthMonitoringRoute
import dev.ticbox.companion.ui.navigation.WatchPreferencesRoute
import dev.ticbox.companion.ui.session
import dev.ticbox.companion.ui.session.SessionState
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h2000dp")
class HealthMonitoringScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()

    private fun show(
        monitoring: MonitoringSettingsClient? = null,
        route: Any = HealthMonitoringRoute,
    ): NavHostController {
        val watch = demoWatch(context)
        val session = watch.session(monitoring = monitoring ?: watch.monitoring)
        lateinit var navController: NavHostController
        compose.setContent {
            navController = rememberNavController()
            CompanionApp(
                TestDependencies(FakeSessions(SessionState.Ready(session))),
                NoSystemActions,
                navController,
            )
        }
        compose.waitForIdle()
        compose.runOnUiThread { navController.navigate(route) }
        compose.waitForIdle()
        return navController
    }

    private fun text(value: String) = compose.onNodeWithText(value)

    private fun waitFor(value: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(value, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun switch(title: String) = hasText(title) and isToggleable()

    private fun waitForSwitch(title: String, on: Boolean) {
        compose.waitUntil(5_000) {
            compose
                .onAllNodes(switch(title) and if (on) isOn() else isOff())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.waitForIdle()
    }

    @Test
    fun theWatchsValuesAreReadOnceOnEnteringAndHidingHeartHealthTurnsItsMonitoringOff() {
        show()
        waitForSwitch(HEART_HEALTH, on = false)

        compose.onNode(switch(HEART_HEALTH)).performScrollTo().performClick()
        waitForSwitch(HEART_HEALTH, on = true)

        text("Hide heart health on the watch").performScrollTo().performClick()
        waitForSwitch(HEART_HEALTH, on = false)
        waitFor("Sent to your watch")
    }

    @Test
    fun preferencesLeadHereWithoutReadingAnythingOnTheirOwn() {
        val monitoring = FakeMonitoring(onRead = { it.copy(current = true) })
        show(monitoring, route = WatchPreferencesRoute)
        assertEquals(0, monitoring.reads)

        text("Health monitoring").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, monitoring.reads)
    }

    @Test
    fun aReadThatFailsIsNotRepeatedUntilAskedAndSaysWhy() {
        val monitoring =
            FakeMonitoring(
                onRead = {
                    it.copy(
                        lastOperation =
                            MonitoringCommandResult(
                                MonitoringCommandStatus.UNAVAILABLE,
                                reason = "monitoring_root_required",
                            )
                    )
                }
            )
        show(monitoring)
        waitFor("Local Bridge didn't get root")
        assertEquals(1, monitoring.reads)

        text("Read again").performClick()
        compose.waitForIdle()
        assertEquals(2, monitoring.reads)
    }

    @Test
    fun aWriteThatCouldNotBeConfirmedLeavesNothingToChangeUntilReadAgain() {
        val monitoring =
            FakeMonitoring(
                onRead = {
                    it.copy(settings = listOf(toggle(HEART_HEALTH_ID, false)), current = true)
                },
                onWrite = {
                    it.copy(
                        current = false,
                        lastOperation =
                            MonitoringCommandResult(
                                MonitoringCommandStatus.STORED_UNVERIFIED,
                                HEART_HEALTH_ID,
                                "monitoring_timeout",
                            ),
                    )
                },
            )
        show(monitoring)
        waitForSwitch(HEART_HEALTH, on = false)

        compose.onNode(switch(HEART_HEALTH)).performClick()
        waitFor("Couldn't confirm the watch saved")
        assertEquals(
            listOf<Pair<MonitoringSettingId, MonitoringValue>>(
                HEART_HEALTH_ID to MonitoringValue.Toggle(true)
            ),
            monitoring.writes,
        )
        compose.onNode(switch(HEART_HEALTH)).assertIsNotEnabled()
    }

    @Test
    fun aHealthAppVersionThatCannotBeWrittenStillShowsItsValues() {
        val monitoring =
            FakeMonitoring(
                onRead = {
                    it.copy(
                        source = MonitoringSource("health", "5.9.0", 1),
                        settings = listOf(toggle(HEART_HEALTH_ID, true, writable = false)),
                        current = true,
                    )
                }
            )
        show(monitoring)
        waitForSwitch(HEART_HEALTH, on = true)
        compose.onNode(switch(HEART_HEALTH)).assertIsNotEnabled()
        waitFor("(5.9.0)")
    }

    @Test
    fun withoutTheModuleItsRowsGiveWayToOneNotice() {
        val monitoring =
            FakeMonitoring(
                onRead = {
                    it.copy(
                        settings =
                            listOf(
                                toggle(HEART_HEALTH_ID, false),
                                moduleRow(
                                    MonitoringSettingId.SEDENTARY_REMINDER,
                                    available = false,
                                ),
                                moduleRow(MonitoringSettingId.SLEEP_WAKE_ALARM, available = false),
                            ),
                        current = true,
                    )
                }
            )
        show(monitoring)
        waitFor("Reminders and sleep schedule need a Magisk module")
        text("Sedentary reminder").assertDoesNotExist()
        text("Wake-up alarm").assertDoesNotExist()
    }

    @Test
    fun oneModuleRowMissingSaysSoAndShowsNoPosition() {
        val monitoring =
            FakeMonitoring(
                onRead = {
                    it.copy(
                        settings =
                            listOf(
                                moduleRow(MonitoringSettingId.SEDENTARY_REMINDER, available = true),
                                moduleRow(MonitoringSettingId.SLEEP_WAKE_ALARM, available = false),
                            ),
                        current = true,
                    )
                }
            )
        show(monitoring)
        waitFor("Needs the Magisk module")
        compose.onNode(switch("Sedentary reminder")).assertIsEnabled()
        compose.onNode(switch("Wake-up alarm")).assertDoesNotExist()
        text("Wake-up alarm").assertIsNotEnabled()
    }

    @Test
    fun anOlderProviderProxyKeepsWorkingPrivateSettingsAndOffersTheModuleUpdate() {
        val monitoring =
            FakeMonitoring(
                onRead = {
                    it.copy(
                        settings =
                            listOf(
                                toggle(MonitoringSettingId.PRESSURE, false, writable = false)
                                    .copy(
                                        storedValue = null,
                                        available = false,
                                        unavailableReason = "monitoring_module_update_required",
                                    ),
                                moduleRow(MonitoringSettingId.SEDENTARY_REMINDER, available = true),
                            ),
                        current = true,
                    )
                }
            )
        show(monitoring)
        waitFor("Update the watch module, then restart the watch")
        compose.onNode(switch("Sedentary reminder")).assertIsEnabled()
        text("Automatic stress").assertIsNotEnabled()
        text("The watch doesn't allow that with this connection.").assertDoesNotExist()
    }

    @Test
    fun aChoiceIsSentWithTheWatchsOwnValue() {
        val mode =
            MonitoringSettingObservation(
                MonitoringSettingDescriptor(
                    MonitoringSettingId.BACKGROUND_HEART_RATE_MODE,
                    MonitoringValueKind.INTEGER,
                    group = "heart_rate",
                    choices =
                        listOf(
                            MonitoringChoice(MonitoringValue.Number(0), "normal_600_seconds"),
                            MonitoringChoice(MonitoringValue.Number(1), "realtime_1_second"),
                        ),
                ),
                MonitoringValue.Number(0),
            )
        val monitoring =
            FakeMonitoring(onRead = { it.copy(settings = listOf(mode), current = true) })
        show(monitoring)
        waitFor("Normal, about every 10 minutes")

        text("Heart rate measuring").performClick()
        waitFor("Real time, every second")
        text("Real time, every second").performClick()
        compose.waitForIdle()
        assertEquals(
            listOf<Pair<MonitoringSettingId, MonitoringValue>>(
                MonitoringSettingId.BACKGROUND_HEART_RATE_MODE to MonitoringValue.Number(1)
            ),
            monitoring.writes,
        )
    }

    @Test
    fun aSwitchWithNoKnownPositionAsksInsteadOfGuessing() {
        val unknown =
            MonitoringSettingObservation(
                MonitoringSettingDescriptor(
                    MonitoringSettingId.BLOOD_OXYGEN,
                    MonitoringValueKind.BOOLEAN,
                    group = "blood_oxygen",
                    deviceDependentDefault = true,
                ),
                storedValue = null,
            )
        val monitoring =
            FakeMonitoring(onRead = { it.copy(settings = listOf(unknown), current = true) })
        show(monitoring)
        waitFor("Unknown")
        compose.onNode(switch("Automatic blood oxygen")).assertDoesNotExist()

        text("Automatic blood oxygen").performClick()
        waitFor("On")
        text("On").performClick()
        compose.waitForIdle()
        assertEquals(
            listOf<Pair<MonitoringSettingId, MonitoringValue>>(
                MonitoringSettingId.BLOOD_OXYGEN to MonitoringValue.Toggle(true)
            ),
            monitoring.writes,
        )
    }

    @Test
    fun aTimeIsSentInTheFormTheWatchTakes() {
        val time =
            MonitoringSettingObservation(
                MonitoringSettingDescriptor(
                    MonitoringSettingId.HEALTH_QUIET_START,
                    MonitoringValueKind.TEXT,
                    group = "activity_reminders",
                    unit = "local_time",
                    requiresInProcessModule = true,
                ),
                MonitoringValue.Text("07:05"),
            )
        val monitoring =
            FakeMonitoring(onRead = { it.copy(settings = listOf(time), current = true) })
        show(monitoring)
        waitFor("07:05")

        text("Quiet hours start").performClick()
        waitFor("Save")
        text("Save").performClick()
        compose.waitForIdle()
        assertEquals(
            listOf<Pair<MonitoringSettingId, MonitoringValue>>(
                MonitoringSettingId.HEALTH_QUIET_START to MonitoringValue.Text("07:05")
            ),
            monitoring.writes,
        )
    }

    private companion object {
        const val HEART_HEALTH = "Heart health monitoring"
        val HEART_HEALTH_ID = MonitoringSettingId.HEART_HEALTH
    }
}

private fun toggle(id: MonitoringSettingId, on: Boolean, writable: Boolean = true) =
    MonitoringSettingObservation(
        MonitoringSettingDescriptor(
            id,
            MonitoringValueKind.BOOLEAN,
            group = "test",
            writable = writable,
        ),
        MonitoringValue.Toggle(on),
    )

private fun moduleRow(id: MonitoringSettingId, available: Boolean) =
    MonitoringSettingObservation(
        MonitoringSettingDescriptor(
            id,
            MonitoringValueKind.BOOLEAN,
            group = "test",
            defaultValue = MonitoringValue.Toggle(true),
            writable = available,
            requiresInProcessModule = true,
        ),
        storedValue = null,
        available = available,
        unavailableReason = "monitoring_module_unavailable".takeUnless { available },
    )

private class FakeMonitoring(
    private val onRead: (MonitoringSettingsState) -> MonitoringSettingsState,
    private val onWrite: (MonitoringSettingsState) -> MonitoringSettingsState = { it },
) : MonitoringSettingsClient {
    override val state = MutableStateFlow(MonitoringSettingsState(phase = MonitoringPhase.READY))
    var reads = 0
    val writes = mutableListOf<Pair<MonitoringSettingId, MonitoringValue>>()

    override suspend fun refresh(): MonitoringCommandResult {
        reads++
        state.value = onRead(state.value)
        return MonitoringCommandResult(MonitoringCommandStatus.READ)
    }

    override suspend fun set(
        id: MonitoringSettingId,
        value: MonitoringValue,
    ): MonitoringCommandResult {
        writes += id to value
        state.value = onWrite(state.value)
        return MonitoringCommandResult(MonitoringCommandStatus.STORED_AND_NOTIFIED, id)
    }

    override fun close() = Unit
}
